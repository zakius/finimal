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
    /** Server codec name → decoder MIME type and the 10-bit profiles that decoder must list. */
    private val codecs = mapOf(
        "h264" to ("video/avc" to setOf(CodecProfileLevel.AVCProfileHigh10)),
        "hevc" to ("video/hevc" to setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10)),
        "vp9" to ("video/x-vnd.on2.vp9" to setOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR)),
        "av1" to ("video/av01" to setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10)),
        "vp8" to ("video/x-vnd.on2.vp8" to emptySet()),
        "mpeg2video" to ("video/mpeg2" to emptySet()),
        "mpeg4" to ("video/mp4v-es" to emptySet()),
    )

    private val all by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.hardware() } }

    private fun MediaCodecInfo.hardware() =
        if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated
        else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")

    private fun capabilities(codec: String) = codecs[codec]?.let { (mime, _) ->
        all.filter { mime in it.supportedTypes }.map { it.getCapabilitiesForType(mime) }
    }.orEmpty()

    override fun hasCodec(codec: String, tenBit: Boolean): Boolean {
        val tenBitProfiles = codecs[codec]?.second ?: return false
        return capabilities(codec).any { caps -> !tenBit || caps.profileLevels.any { it.profile in tenBitProfiles } }
    }

    override fun fits(codec: String, width: Int, height: Int, fps: Float) = capabilities(codec).any { caps ->
        val video = caps.videoCapabilities ?: return@any false
        // Rated sustained rates where the vendor lists them; the plain size check is far more generous.
        val points = if (Build.VERSION.SDK_INT >= 29) video.supportedPerformancePoints else null
        if (!points.isNullOrEmpty()) {
            val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps.toInt().coerceAtLeast(1))
            points.any { it.covers(wanted) }
        } else video.areSizeAndRateSupported(width, height, fps.toDouble())
    }

    /** The tallest common 16:9 height [codec] plays at 30 fps, or null below 720p. */
    fun tallest(codec: String) = listOf(2160, 1440, 1080, 720).firstOrNull { fits(codec, it * 16 / 9, it, 30f) }

    /** The size transcodes are made at on Auto. */
    val h264Height by lazy { tallest("h264") ?: 480 }

    /** For Settings: "H.264 1080p, HEVC 10-bit 4K". */
    fun summary() = listOf("h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1").filter { hasCodec(it.first, false) }
        .joinToString { (codec, name) ->
            name + (if (hasCodec(codec, true)) " 10-bit" else "") + (tallest(codec)?.let { if (it == 2160) " 4K" else " ${it}p" } ?: "")
        }.ifEmpty { "none found" }
}

fun videoInfo(source: MediaSourceInfo): VideoInfo? {
    val v = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
    return VideoInfo(v.codec?.lowercase() ?: return null, v.width ?: return null, v.height ?: return null,
        v.realFrameRate ?: v.averageFrameRate ?: 30f, (v.bitDepth ?: 8) > 8)
}

/** Under the direct play settings. An item without stream details plays directly; a failure still switches. */
fun playsDirectly(item: BaseItemDto): Boolean {
    val video = item.mediaSources?.firstOrNull()?.let(::videoInfo) ?: return true
    val p = App.prefs
    return finimal.core.playsDirectly(video, DirectPlayLimits(p.maxHeight, DirectPlayLimits.Codecs.entries[p.codecs], p.learnedPixelRate(video.codec)), HardwareDecoders)
}
