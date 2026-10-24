package plainfin.core

data class CacheEntry(val key: String, val bytes: Long, val lastAccessMs: Long, val pinned: Boolean)

/** Unpinned keys to remove, oldest first, until the total fits [limitBytes]. Pinned bytes count toward the total. */
fun evictionOrder(entries: List<CacheEntry>, limitBytes: Long, protectedKey: String? = null): List<String> {
    var total = entries.sumOf { it.bytes }
    val out = mutableListOf<String>()
    for (e in entries.filter { !it.pinned && it.key != protectedKey }.sortedBy { it.lastAccessMs }) {
        if (total <= limitBytes) break
        out += e.key
        total -= e.bytes
    }
    return out
}
