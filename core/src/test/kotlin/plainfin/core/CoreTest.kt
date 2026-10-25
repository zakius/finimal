package plainfin.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val min = 60 * TICKS_PER_SECOND
    private val runtime = 24 * min

    @Test fun stopNearStartStoresZero() {
        assertEquals(PlayState(0, false, 1), ResumeRules.onStop(null, 1 * TICKS_PER_SECOND * 30, runtime, 1))
    }

    @Test fun stopInMiddleKeepsPosition() {
        assertEquals(PlayState(10 * min, false, 1), ResumeRules.onStop(null, 10 * min, runtime, 1))
    }

    @Test fun stopNearEndMarksPlayed() {
        assertEquals(PlayState(0, true, 1), ResumeRules.onStop(null, 22 * min, runtime, 1))
    }

    @Test fun shortItemInMiddleMarksPlayed() {
        assertEquals(PlayState(0, true, 1), ResumeRules.onStop(null, 2 * min, 4 * min, 1))
    }

    @Test fun unknownRuntimeMarksPlayed() {
        assertTrue(ResumeRules.onStop(null, 5 * min, null, 1).played)
    }

    @Test fun rewatchInMiddleKeepsPlayed() {
        assertTrue(ResumeRules.onStop(PlayState(0, true, 0), 10 * min, runtime, 1).played)
    }

    @Test fun newerWins() {
        val local = PlayState(5, false, 200)
        assertTrue(shouldPush(local, null))
        assertTrue(shouldPush(local, PlayState(0, false, null)))
        assertTrue(shouldPush(local, PlayState(0, true, 100)))
        assertFalse(shouldPush(local, PlayState(0, true, 300)))
    }

    @Test fun pendingStoreSurvivesReloadAndKeepsNewerWrites() {
        val f = File.createTempFile("pending", ".json").apply { delete(); deleteOnExit() }
        val a = PlayState(1, false, 1)
        val b = PlayState(2, false, 2)
        PendingStore(f).apply { put("x", a); put("x", b); confirm("x", a) }
        val reloaded = PendingStore(f)
        assertEquals(b, reloaded.get("x"))
        reloaded.confirm("x", b)
        assertNull(PendingStore(f).get("x"))
    }

    private fun ep(id: String, played: Boolean = false, at: Long? = null) =
        EpisodeRef(id, at?.let { PlayState(0, played, it) })

    @Test fun keepNextFromScratch() {
        assertEquals(listOf("1", "2"), keepNextWindow(listOf(ep("1"), ep("2"), ep("3")), 2))
    }

    @Test fun keepNextKeepsUnfinishedAnchor() {
        val eps = listOf(ep("1", true, 1), ep("2", false, 5), ep("3"), ep("4"), ep("5"))
        assertEquals(listOf("2", "3", "4"), keepNextWindow(eps, 2))
    }

    @Test fun keepNextUsesMostRecentNotHighest() {
        val eps = listOf(ep("1", true, 9), ep("2"), ep("3", true, 1), ep("4"))
        assertEquals(listOf("2"), keepNextWindow(eps, 1))
    }

    @Test fun keepNextTiedTimestampsAnchorOnLatestEpisode() {
        // Marking a season played on the server gives every episode the same LastPlayedDate.
        val eps = listOf(ep("1", true, 7), ep("2", true, 7), ep("3", true, 7), ep("4"), ep("5"))
        assertEquals(listOf("4", "5"), keepNextWindow(eps, 2))
    }

    @Test fun pendingStoreKeepsCorruptFile() {
        val f = File.createTempFile("pending", ".json").apply { writeText("{not json") }
        val store = PendingStore(f)
        assertNull(store.get("x"))
        store.put("x", PlayState(1, false, 1))
        assertEquals("{not json", File(f.path + ".corrupt").readText())
    }

    @Test fun keepNextDeletesOnlyPlayedAutoOutsideWindow() {
        val eps = listOf(ep("1", true, 1), ep("2", true, 2), ep("3"), ep("4"))
        val window = keepNextWindow(eps, 1)
        assertEquals(listOf("3"), window)
        assertEquals(listOf("2"), keepNextDeletions(setOf("2", "3", "4"), eps, window))
    }

    @Test fun reconnectBacksOffThenGivesUp() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (0..5).map { reconnectDelayMs(it, 0, 1_000) })
        assertEquals(10_000L, reconnectDelayMs(40, 0, 59_999))
        assertNull(reconnectDelayMs(3, 0, RECONNECT_WINDOW_MS))
    }

    @Test fun evictsOldestUnpinnedUntilUnderLimit() {
        val entries = listOf(
            CacheEntry("pinned", 500, 0, true),
            CacheEntry("old", 30, 1, false),
            CacheEntry("playing", 30, 2, false),
            CacheEntry("new", 30, 3, false),
        )
        // Pinned bytes don't count: 90 buffered against 60 drops only the oldest.
        assertEquals(listOf("old"), evictionOrder(entries, 60, protectedKey = "playing"))
        assertEquals(listOf("old", "new"), evictionOrder(entries, 10, protectedKey = "playing"))
        assertEquals(emptyList(), evictionOrder(entries, 90))
    }

    @Test fun bufferLimitKeepsReserveFree() {
        val gb = 1_000_000_000L
        assertEquals(5 * gb, bufferLimit(5 * gb, 1 * gb, 50 * gb, 64 * gb))
        // 3 GB buffered, 1 GB free on 64 GB: keep 3.2 GB free, so the buffer may hold 0.8 GB.
        assertEquals(800_000_000L, bufferLimit(5 * gb, 3 * gb, 1 * gb, 64 * gb))
        assertEquals(0, bufferLimit(5 * gb, 0, 1 * gb, 16 * gb))
    }

    @Test fun pendingStoreKeepsStateWhenDiskWriteFails() {
        val dir = File.createTempFile("pending", "").apply { delete(); mkdirs(); deleteOnExit() }
        val store = PendingStore(File(dir, "p.json"))
        dir.setWritable(false)
        try {
            store.put("x", PlayState(1, false, 1))
            assertEquals(PlayState(1, false, 1), store.get("x"))
        } finally { dir.setWritable(true) }
    }
}
