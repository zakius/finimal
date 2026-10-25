package finimal.core

/** The video stream of a file, as the server describes it. */
data class VideoInfo(val codec: String, val width: Int, val height: Int, val fps: Float, val tenBit: Boolean) {
    /** Decoder load: pixels a second. A 1080p60 file costs about what 4K at 15 fps does. */
    val pixelRate get() = width.toLong() * height * fps.toLong().coerceAtLeast(1)
}

/** Settings → Playback. [maxHeight] 0 and [codecs] AUTO trust the device's decoders. */
data class DirectPlayLimits(val maxHeight: Int, val codecs: Codecs, val learnedPixelRate: Long?) {
    enum class Codecs { AUTO, H264_ONLY, ALL }
}

/** What the device's hardware decoders report; asked only when a setting is on Auto. */
interface Decoders {
    fun hasCodec(codec: String, tenBit: Boolean): Boolean
    fun fits(codec: String, width: Int, height: Int, fps: Float): Boolean
}

/** Whether to play the file as it is, rather than ask the server to transcode it first. */
fun playsDirectly(video: VideoInfo, limits: DirectPlayLimits, decoders: Decoders): Boolean {
    val codecOk = when (limits.codecs) {
        DirectPlayLimits.Codecs.AUTO -> decoders.hasCodec(video.codec, video.tenBit)
        DirectPlayLimits.Codecs.H264_ONLY -> video.codec == "h264" && !video.tenBit
        DirectPlayLimits.Codecs.ALL -> true
    }
    val sizeOk = when {
        limits.maxHeight > 0 -> video.height <= limits.maxHeight
        // ALL: a codec with no hardware decoder is left to the player's software one.
        limits.codecs == DirectPlayLimits.Codecs.ALL && !decoders.hasCodec(video.codec, video.tenBit) -> true
        else -> decoders.fits(video.codec, video.width, video.height, video.fps)
    }
    // A transcode the user picked after dropped frames: this codec chokes at this load and above.
    val learnedOk = limits.learnedPixelRate?.let { video.pixelRate < it } ?: true
    return codecOk && sizeOk && learnedOk
}
