package finimal.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.json.Json

/**
 * Play states the server has not confirmed yet, keyed by item ID. Only the latest state per item
 * matters because the server takes absolute values. Every write lands on disk before returning, so
 * a crash never loses progress.
 */
class PendingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val states: MutableMap<String, PlayState> =
        if (!file.exists()) {
            mutableMapOf()
        } else {
            val saved: Map<String, PlayState> = try {
                json.decodeFromString<Map<String, PlayState>>(file.readText())
            } catch (e: Throwable) {
                file.renameTo(File(file.path + ".corrupt"))
                emptyMap()
            }
            saved.toMutableMap()
        }

    @Volatile private var empty = states.isEmpty()

    @Synchronized fun get(itemId: String): PlayState? = states[itemId]

    fun isEmpty(): Boolean = empty

    @Synchronized fun all(): Map<String, PlayState> = states.toMap()

    @Synchronized
    fun put(itemId: String, state: PlayState) {
        states[itemId] = state
        empty = false
        save()
    }

    /** Records many states in one atomic write, so bulk actions fsync once instead of once per episode. */
    @Synchronized
    fun putAll(entries: Map<String, PlayState>) {
        if (entries.isEmpty()) return
        states.putAll(entries)
        empty = false
        save()
    }

    /** Removes the entry only if it is still [state], so a newer write made during a push survives. */
    @Synchronized
    fun confirm(itemId: String, state: PlayState) {
        if (states[itemId] == state && states.remove(itemId) != null) {
            empty = states.isEmpty()
            save()
        }
    }

    /** Removes every entry whose value still matches [entries] in one atomic write. */
    @Synchronized
    fun confirmAll(entries: Map<String, PlayState>) {
        var changed = false
        for ((id, state) in entries) {
            if (states[id] == state && states.remove(id) != null) changed = true
        }
        if (changed) {
            empty = states.isEmpty()
            save()
        }
    }

    private fun save() {
        try {
            file.writeAtomically(json.encodeToString(states.toMap()))
        } catch (e: IOException) {
        }
    }
}

/**
 * Writes through a temp file and an atomic rename, so a crash or power loss leaves the old or the new file, never half
 * of one. Pass [sync] = false for disposable caches that don't need an fsync stall. Uses [Files.move] because
 * [File.renameTo] refuses to replace an existing file on Windows.
 */
fun File.writeAtomically(text: String, sync: Boolean = true) {
    val tmp = File("$path.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        if (sync) out.fd.sync()
    }
    Files.move(tmp.toPath(), toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
}
