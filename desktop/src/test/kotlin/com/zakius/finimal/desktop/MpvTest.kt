package com.zakius.finimal.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The libmpv binding against the real library, on a clip mpv generates itself: frames render, properties arrive,
 * the end is seen, and closing doesn't crash. CI runs it on Linux and Windows, where nobody watches the window.
 */
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
