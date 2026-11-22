package com.zakius.finimal.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvTest {
    private fun waitFor(timeoutMs: Long = 15_000, what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test fun playsAGeneratedClipToTheEnd() {
        val mpv = Mpv(mapOf("ao" to "null"))
        try {
            mpv.resize(320, 180)
            mpv.load("av://lavfi:testsrc=duration=2:size=320x240:rate=25", 0.0)
            waitFor(what = "a frame") { mpv.frame != null }
            val frame = assertNotNull(mpv.frame)
            assertEquals(320, frame.width)
            assertEquals(180, frame.height)
            waitFor(what = "the duration") { mpv.duration > 1.5 }
            mpv.seek(1.5)
            waitFor(what = "the end") { mpv.eof }
            assertTrue(mpv.position >= 1.5, "position ${mpv.position}")
            assertNull(mpv.error)
        } finally {
            mpv.close()
        }
    }

    @Test fun closingUnderLoadIsSafe() {
        repeat(15) { round ->
            val mpv = Mpv(mapOf("ao" to "null"))
            mpv.resize(160, 90)
            mpv.load("av://lavfi:testsrc=duration=5:size=320x240:rate=50", 0.0)
            val callers = (1..3).map {
                kotlin.concurrent.thread {
                    repeat(200) {
                        mpv.tracks()
                        mpv.get("time-pos")
                        mpv.seek(1.0)
                    }
                }
            }
            Thread.sleep(30L * round % 200)
            mpv.close()
            callers.forEach { it.join() }
        }
        Thread.sleep(500)
    }

    @Test fun startsAtTheGivenPosition() {
        val mpv = Mpv(mapOf("ao" to "null"))
        try {
            mpv.resize(64, 36)
            mpv.load("av://lavfi:testsrc=duration=10:size=64x48:rate=10", 6.0)
            waitFor(what = "the position") { mpv.loaded && mpv.position >= 5.9 }
            assertTrue(mpv.position < 9, "position ${mpv.position}")
        } finally {
            mpv.close()
        }
    }

    @Test fun aMissingFileIsAnError() {
        val mpv = Mpv(mapOf("ao" to "null"))
        try {
            mpv.load("/nonexistent/file.mkv", 0.0)
            waitFor(what = "the error") { mpv.error != null }
        } finally {
            mpv.close()
        }
    }
}
