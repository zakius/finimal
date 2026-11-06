package finimal.core

/** The video stream of a file, as the server describes it. */
data class VideoInfo(val codec: String, val width: Int, val height: Int, val fps: Float, val tenBit: Boolean) {
    /** Decoder load in pixels per second. A 1080p60 file costs about as much as 4K at 15 fps. */
    val pixelRate get() = width.toLong() * height * fps.toLong().coerceAtLeast(1)
}

/** The Playback settings. A [maxHeight] of 0 and [codecs] AUTO trust the device's decoders. */
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
        // With ALL, a codec that has no hardware decoder is left to the player's software decoder.
        limits.codecs == DirectPlayLimits.Codecs.ALL && !decoders.hasCodec(video.codec, video.tenBit) -> true
        else -> decoders.fits(video.codec, video.width, video.height, video.fps)
    }
    // The user picked a transcode after dropped frames, so this codec can't keep up at this load or above.
    val learned = limits.learnedPixelRate
    val learnedOk = learned == null || video.pixelRate < learned
    return codecOk && sizeOk && learnedOk
}
