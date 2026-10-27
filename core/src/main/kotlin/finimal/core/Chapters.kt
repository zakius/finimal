package finimal.core

/** Back to the current chapter's start rather than the one before, once this far into it, as in mpv. */
const val CHAPTER_RESTART_MS = 5_000L

/** The chapter playing at [positionMs]: an index into [startsMs], which is in order; -1 before the first. */
fun chapterAt(startsMs: List<Long>, positionMs: Long) = startsMs.indexOfLast { it <= positionMs }

/** Where "next" or "previous chapter" goes from [positionMs]; null past the last chapter or before the first. */
fun chapterSeek(startsMs: List<Long>, positionMs: Long, forward: Boolean): Long? {
    val i = chapterAt(startsMs, positionMs)
    return when {
        forward -> startsMs.getOrNull(i + 1)
        i >= 0 && positionMs - startsMs[i] > CHAPTER_RESTART_MS -> startsMs[i]
        else -> startsMs.getOrNull(i - 1)
    }
}
