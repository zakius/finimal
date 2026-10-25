package finimal.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * Play states the server has not confirmed yet, keyed by item ID. Only the latest state per item
 * matters because the server takes absolute values. Every write lands on disk before returning, so
 * a crash never loses progress.
 */
class PendingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val states: MutableMap<String, PlayState> =
        if (!file.exists()) mutableMapOf()
        else runCatching { json.decodeFromString<Map<String, PlayState>>(file.readText()) }
            // Kept aside rather than overwritten by the next save: it may be the only copy of offline progress.
            .onFailure { file.renameTo(File(file.path + ".corrupt")) }
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

    // A full disk keeps the states in memory; the next put writes them all.
    private fun save() = try { file.writeAtomically(json.encodeToString(states.toMap())) } catch (e: IOException) {}
}

/** Through a synced temp file and a rename, so a crash or power loss leaves the old or the new file, never half of one. */
fun File.writeAtomically(text: String) {
    val tmp = File("$path.tmp")
    FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
    if (!tmp.renameTo(this)) error("cannot replace $this")
}
