package finimal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChaptersTest {
    private val starts = listOf(0L, 20_000L, 60_000L)

    @Test fun nextGoesToTheFollowingStart() {
        assertEquals(20_000, chapterSeek(starts, 3_000, forward = true))
        assertNull(chapterSeek(starts, 70_000, forward = true))
    }

    @Test fun previousRestartsTheChapterUnlessNearItsStart() {
        assertEquals(20_000, chapterSeek(starts, 30_000, forward = false))
        assertEquals(0, chapterSeek(starts, 22_000, forward = false))
        assertNull(chapterSeek(starts, 2_000, forward = false))
    }

    @Test fun currentChapter() {
        assertEquals(1, chapterAt(starts, 20_000))
        assertEquals(-1, chapterAt(listOf(5_000L), 1_000))
    }
}
