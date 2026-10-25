package finimal.core

import finimal.core.DirectPlayLimits.Codecs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectPlayTest {
    /** A box with 8-bit H.264 and HEVC up to 1080p60. */
    private val box = object : Decoders {
        override fun hasCodec(codec: String, tenBit: Boolean) = codec in setOf("h264", "hevc") && !tenBit
        override fun fits(codec: String, width: Int, height: Int, fps: Float) = hasCodec(codec, false) && height <= 1080 && fps <= 60
    }
    private val auto = DirectPlayLimits(0, Codecs.AUTO, null)
    private val hevc1080 = VideoInfo("hevc", 1920, 1080, 23.976f, false)
    private val hevc4k = VideoInfo("hevc", 3840, 2160, 23.976f, false)

    @Test fun autoTrustsTheDecoders() {
        assertTrue(playsDirectly(hevc1080, auto, box))
        assertFalse(playsDirectly(hevc4k, auto, box))
        assertFalse(playsDirectly(hevc1080.copy(tenBit = true), auto, box))
        assertFalse(playsDirectly(hevc1080.copy(codec = "av1"), auto, box))
    }

    @Test fun maxHeightOverridesTheDecoders() {
        assertTrue(playsDirectly(hevc4k, auto.copy(maxHeight = 2160), box))
        assertFalse(playsDirectly(hevc1080, auto.copy(maxHeight = 720), box))
    }

    @Test fun h264Only() {
        val limits = auto.copy(codecs = Codecs.H264_ONLY)
        assertFalse(playsDirectly(hevc1080, limits, box))
        assertTrue(playsDirectly(hevc1080.copy(codec = "h264"), limits, box))
    }

    @Test fun allLeavesUnknownCodecsToThePlayer() {
        val limits = auto.copy(codecs = Codecs.ALL)
        assertTrue(playsDirectly(hevc1080.copy(codec = "av1", tenBit = true), limits, box))
        assertFalse(playsDirectly(hevc4k, limits, box)) // a hardware decoder exists and says no
    }

    @Test fun learnedLimitTranscodesThatLoadAndAbove() {
        val limits = auto.copy(maxHeight = 2160, learnedPixelRate = hevc4k.pixelRate)
        assertFalse(playsDirectly(hevc4k, limits, box))
        assertTrue(playsDirectly(hevc1080, limits, box))
    }
}
