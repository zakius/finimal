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
                // Kept aside rather than overwritten by the next save: it may be the only copy of offline progress.
                file.renameTo(File(file.path + ".corrupt"))
                emptyMap()
            }
            saved.toMutableMap()
        }

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

    // On a full disk the states stay in memory, and the next put writes them all.
    private fun save() {
        try {
            file.writeAtomically(json.encodeToString(states.toMap()))
        } catch (e: IOException) {
        }
    }
}

/**
 * Writes through a synced temp file and a rename, so a crash or power loss leaves the old or the new file, never half
 * of one. Uses [Files.move] because [File.renameTo] refuses to replace an existing file on Windows.
 */
fun File.writeAtomically(text: String) {
    val tmp = File("$path.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    Files.move(tmp.toPath(), toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
}
