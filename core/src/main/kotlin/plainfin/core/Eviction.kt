package plainfin.core

data class CacheEntry(val key: String, val bytes: Long, val lastAccessMs: Long, val pinned: Boolean)

/** Unpinned keys to remove, oldest first, until the unpinned total fits [limitBytes]. Downloads have no limit but the disk. */
fun evictionOrder(entries: List<CacheEntry>, limitBytes: Long, protectedKey: String? = null): List<String> {
    val buffer = entries.filter { !it.pinned }
    var total = buffer.sumOf { it.bytes }
    val out = mutableListOf<String>()
    for (e in buffer.filter { it.key != protectedKey }.sortedBy { it.lastAccessMs }) {
        if (total <= limitBytes) break
        out += e.key
        total -= e.bytes
    }
    return out
}

/**
 * The buffer limit, lowered so the volume keeps max(2 GB, 5 %) free: a full disk fails downloads,
 * play-state writes and other apps, and the setting alone can't know how much room is left.
 */
fun bufferLimit(limitBytes: Long, bufferedBytes: Long, freeBytes: Long, volumeBytes: Long): Long =
    minOf(limitBytes, bufferedBytes + freeBytes - maxOf(2_000_000_000L, volumeBytes / 20)).coerceAtLeast(0)
