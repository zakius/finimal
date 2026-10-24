package plainfin.core

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Play states the server has not confirmed yet, keyed by item ID. Only the latest state per item
 * matters because the server takes absolute values. Every write lands on disk before returning, so
 * a crash never loses progress.
 */
class PendingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val states: MutableMap<String, PlayState> =
        runCatching { json.decodeFromString<Map<String, PlayState>>(file.readText()) }
            .getOrDefault(emptyMap())
            .toMutableMap()

    @Synchronized fun get(itemId: String): PlayState? = states[itemId]

    @Synchronized fun all(): Map<String, PlayState> = states.toMap()

    @Synchronized
    fun put(itemId: String, state: PlayState) {
        states[itemId] = state
        save()
    }

    /** Removes the entry only if it is still [state], so a newer write made during a push survives. */
    @Synchronized
    fun confirm(itemId: String, state: PlayState) {
        if (states[itemId] == state && states.remove(itemId) != null) save()
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(states.toMap()))
        if (!tmp.renameTo(file)) error("cannot replace $file")
    }
}
