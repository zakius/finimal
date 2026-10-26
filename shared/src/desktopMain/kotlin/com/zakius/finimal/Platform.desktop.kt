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

@Composable
internal actual fun smallestWidthDp(): Int {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { minOf(size.width, size.height).toDp().value.toInt() }
}

/** The latest [toast], which the window shows for a few seconds; [seq] tells a repeat of the same text from the last. */
object Toasts {
    var current by mutableStateOf<Pair<Int, String>?>(null)
        private set
    private var seq = 0
    fun show(text: String) { current = ++seq to text }
}

actual fun toast(text: String) = Toasts.show(text)

/** The desktop has no network-change events to listen to; Retry is on screen. */
@Composable
internal actual fun RetryOnNewNetwork(onRetry: () -> Unit) {}

/** [KeyValueStore] in one JSON file, rewritten whole on each edit: settings are a few dozen keys. */
class JsonFileStore(private val file: File) : KeyValueStore {
    private val values: MutableMap<String, Any?> = runCatching {
        Json.parseToJsonElement(file.readText()) as JsonObject
    }.getOrDefault(JsonObject(emptyMap())).mapValues { (_, v) -> decode(v) }.toMutableMap()

    @Synchronized override fun get(key: String): Any? = values[key]
    @Synchronized override fun keys(): Set<String> = values.keys.toSet()

    @Synchronized
    override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
        val changes = LinkedHashMap<String, Any?>().apply(block)
        for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
        file.parentFile?.mkdirs()
        file.writeAtomically(JsonObject(values.mapValues { (_, v) -> encode(v) }).toString())
    }

    private fun encode(v: Any?): JsonElement = when (v) {
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> error("unsupported $v")
    }

    private fun decode(e: JsonElement): Any? = (e as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content else p.booleanOrNull ?: p.longOrNull
    }
}
