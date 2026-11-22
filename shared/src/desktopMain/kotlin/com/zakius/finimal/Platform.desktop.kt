package com.zakius.finimal

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import finimal.core.writeAtomically
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

@Composable
internal actual fun platformColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme() else lightColorScheme()

private val desktopIconFontFamily: FontFamily by lazy {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("finimal_icons.ttf")!!.use { it.readAllBytes() }
    FontFamily(Font("finimal_icons.ttf", bytes))
}
internal actual fun iconFontFamily(): FontFamily = desktopIconFontFamily

@Composable
internal actual fun smallestWidthDp(): Int {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val smallestPx = minOf(size.width, size.height)
    return with(density) { smallestPx.toDp().value.toInt() }
}

object Toasts {
    var current by mutableStateOf<Pair<Int, String>?>(null)
        private set
    private var seq = 0
    fun show(text: String) {
        seq++
        current = Pair(seq, text)
    }
}

actual fun toast(text: String) = Toasts.show(text)

/** The desktop has no network-change events to listen to, so the user presses the on-screen Retry instead. */
@Composable
internal actual fun RetryOnNewNetwork(onRetry: () -> Unit) {}

/**
 * [KeyValueStore] in one JSON file. Reads and updates happen in memory immediately; disk writes are coalesced onto a
 * single background thread so UI clicks (sort, unplayed, audio delay, window save) never block on fsync.
 */
class JsonFileStore(private val file: File) : KeyValueStore {
    private val values: MutableMap<String, Any?> = runCatching { Json.parseToJsonElement(file.readText()) as JsonObject }
        .getOrDefault(JsonObject(emptyMap()))
        .mapValues { (_, v) -> decode(v) }
        .toMutableMap()

    @Volatile private var pendingJson: String? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "prefs-writer").apply { isDaemon = true }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { flushSync() })
    }

    @Synchronized override fun get(key: String): Any? = values[key]
    @Synchronized override fun keys(): Set<String> = values.keys.toSet()

    @Synchronized
    override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
        val changes = LinkedHashMap<String, Any?>().apply(block)
        for ((k, v) in changes) {
            if (v == null) values.remove(k) else values[k] = v
        }
        val encoded = JsonObject(values.mapValues { (_, v) -> encode(v) }).toString()
        val needsSchedule = pendingJson == null
        pendingJson = encoded
        if (needsSchedule) {
            writer.execute { flushSync() }
        }
    }

    private fun flushSync() {
        while (true) {
            val toWrite = synchronized(this) {
                val p = pendingJson ?: return
                pendingJson = null
                p
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeAtomically(toWrite)
            }
        }
    }

    private fun encode(v: Any?): JsonElement = when (v) {
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> error("unsupported $v")
    }

    private fun decode(e: JsonElement): Any? {
        if (e !is JsonPrimitive) return null
        if (e.isString) return e.content
        return e.booleanOrNull ?: e.longOrNull
    }
}
