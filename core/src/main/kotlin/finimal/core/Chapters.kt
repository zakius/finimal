package finimal.core

/** Once this far into a chapter, "previous" restarts it rather than going to the chapter before, as mpv does. */
const val CHAPTER_RESTART_MS = 5_000L

/** The index in the sorted [startsMs] of the chapter playing at [positionMs], or -1 before the first chapter. */
fun chapterAt(startsMs: List<Long>, positionMs: Long) = startsMs.indexOfLast { it <= positionMs }

/** Where "next" or "previous chapter" goes from [positionMs], or null past the last chapter or before the first. */
fun chapterSeek(startsMs: List<Long>, positionMs: Long, forward: Boolean): Long? {
    val i = chapterAt(startsMs, positionMs)
    return when {
        forward -> startsMs.getOrNull(i + 1)
        i >= 0 && positionMs - startsMs[i] > CHAPTER_RESTART_MS -> startsMs[i]
        else -> startsMs.getOrNull(i - 1)
    }
}
