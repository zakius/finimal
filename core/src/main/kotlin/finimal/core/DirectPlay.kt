package finimal.core

data class VideoInfo(val codec: String, val width: Int, val height: Int, val fps: Float, val tenBit: Boolean) {
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

fun playsDirectly(video: VideoInfo, limits: DirectPlayLimits, decoders: Decoders): Boolean {
    val codecOk = when (limits.codecs) {
        DirectPlayLimits.Codecs.AUTO -> decoders.hasCodec(video.codec, video.tenBit)
        DirectPlayLimits.Codecs.H264_ONLY -> video.codec == "h264" && !video.tenBit
        DirectPlayLimits.Codecs.ALL -> true
    }
    val sizeOk = when {
        limits.maxHeight > 0 -> video.height <= limits.maxHeight
        limits.codecs == DirectPlayLimits.Codecs.ALL && !decoders.hasCodec(video.codec, video.tenBit) -> true
        else -> decoders.fits(video.codec, video.width, video.height, video.fps)
    }
    val learned = limits.learnedPixelRate
    val learnedOk = learned == null || video.pixelRate < learned
    return codecOk && sizeOk && learnedOk
}
