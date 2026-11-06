package com.zakius.finimal

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import finimal.core.Decoders
import finimal.core.DirectPlayLimits
import finimal.core.VideoInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * The device's hardware video decoders. Software ones are left out: they claim 4K and then drop
 * frames, which is how a file ends up choppy instead of transcoded.
 */
object HardwareDecoders : Decoders {
    /** Maps the server's codec name to the decoder MIME type and the profiles that mean the decoder supports 10-bit. */
    private val codecs = mapOf(
        "h264" to Pair("video/avc", setOf(CodecProfileLevel.AVCProfileHigh10)),
        "hevc" to Pair("video/hevc", setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10)),
        "vp9" to Pair("video/x-vnd.on2.vp9", setOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR)),
        "av1" to Pair("video/av01", setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10)),
        "vp8" to Pair("video/x-vnd.on2.vp8", emptySet<Int>()),
        "mpeg2video" to Pair("video/mpeg2", emptySet<Int>()),
        "mpeg4" to Pair("video/mp4v-es", emptySet<Int>()),
    )

    private val all by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.hardware() } }

    private fun MediaCodecInfo.hardware() =
        if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated
        else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")

    private fun capabilities(codec: String): List<MediaCodecInfo.CodecCapabilities> {
        val mime = codecs[codec]?.first ?: return emptyList()
        val decoders = all.filter { decoder -> mime in decoder.supportedTypes }
        return decoders.map { decoder -> decoder.getCapabilitiesForType(mime) }
    }

    override fun hasCodec(codec: String, tenBit: Boolean): Boolean {
        val tenBitProfiles = codecs[codec]?.second ?: return false
        return capabilities(codec).any { caps ->
            !tenBit || caps.profileLevels.any { level -> level.profile in tenBitProfiles }
        }
    }

    override fun fits(codec: String, width: Int, height: Int, fps: Float): Boolean {
        for (caps in capabilities(codec)) {
            val video = caps.videoCapabilities ?: continue
            // Rated sustained rates where the vendor lists them; the plain size check is far more generous.
            val points = if (Build.VERSION.SDK_INT >= 29) video.supportedPerformancePoints else null
            val fitsHere = if (!points.isNullOrEmpty()) {
                val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps.toInt().coerceAtLeast(1))
                points.any { point -> point.covers(wanted) }
            } else {
                video.areSizeAndRateSupported(width, height, fps.toDouble())
            }
            if (fitsHere) return true
        }
        return false
    }

    /** The tallest common 16:9 height [codec] plays at 30 fps, or null below 720p. */
    fun tallest(codec: String) = listOf(2160, 1440, 1080, 720).firstOrNull { height -> fits(codec, height * 16 / 9, height, 30f) }

    /** The transcode height used when the quality setting is Auto. */
    val h264Height by lazy { tallest("h264") ?: 480 }

    /** The decoder summary shown in Settings, such as "H.264 1080p, HEVC 10-bit 4K". */
    fun summary(): String {
        val names = listOf("h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1")
        val found = names.filter { (codec, _) -> hasCodec(codec, false) }
        val text = found.joinToString { (codec, name) ->
            val tenBit = if (hasCodec(codec, true)) " 10-bit" else ""
            val height = tallest(codec)
            val size = when {
                height == null -> ""
                height == 2160 -> " 4K"
                else -> " ${height}p"
            }
            name + tenBit + size
        }
        return if (text.isEmpty()) "none found" else text
    }
}

fun videoInfo(source: MediaSourceInfo): VideoInfo? {
    val stream = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
    val codec = stream.codec?.lowercase() ?: return null
    val width = stream.width ?: return null
    val height = stream.height ?: return null
    val fps = stream.realFrameRate ?: stream.averageFrameRate ?: 30f
    val tenBit = (stream.bitDepth ?: 8) > 8
    return VideoInfo(codec, width, height, fps, tenBit)
}

/**
 * Whether [item] fits the direct play settings. An item without stream details is assumed to play directly; if it then
 * fails to decode, the player still switches to a transcode.
 */
fun playsDirectly(item: BaseItemDto): Boolean {
    val source = item.mediaSources?.firstOrNull() ?: return true
    val video = videoInfo(source) ?: return true
    val p = App.prefs
    val limits = DirectPlayLimits(p.maxHeight, DirectPlayLimits.Codecs.entries[p.codecs], p.learnedPixelRate(video.codec))
    return finimal.core.playsDirectly(video, limits, HardwareDecoders)
}
