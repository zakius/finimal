package com.zakius.finimal.desktop

import com.zakius.finimal.burnInIndex
import com.zakius.finimal.sideloadedSubs
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A transcode drops embedded subtitles: text ones come as files, a picture one is burned in. */
class SubtitlesTest {
    private fun sub(index: Int, lang: String, text: Boolean, external: Boolean = false) = MediaStream(
        type = MediaStreamType.SUBTITLE, index = index, language = lang, isTextSubtitleStream = text, isExternal = external,
        isInterlaced = false, isDefault = false, isForced = false, isHearingImpaired = false, isOriginal = false, supportsExternalStream = text,
    )

    private val source = MediaSourceInfo(
        mediaStreams = listOf(sub(2, "eng", text = true), sub(3, "jpn", text = false), sub(4, "pol", text = true, external = true)),
        protocol = org.jellyfin.sdk.model.api.MediaProtocol.FILE, type = org.jellyfin.sdk.model.api.MediaSourceType.DEFAULT,
        isRemote = false, readAtNativeFramerate = false, ignoreDts = false, ignoreIndex = false, genPtsInput = false,
        supportsTranscoding = true, supportsDirectStream = true, supportsDirectPlay = true, isInfiniteStream = false,
        requiresOpening = false, requiresClosing = false, requiresLooping = false, supportsProbing = true,
        transcodingSubProtocol = org.jellyfin.sdk.model.api.MediaStreamProtocol.HTTP, hasSegments = false,
    )
    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, mediaSources = listOf(source))

    @Test fun directPlayLoadsOnlyExternalFiles() = assertEquals(listOf(4), sideloadedSubs(item, transcode = false).map { it.index })

    @Test fun transcodeAlsoLoadsEmbeddedText() = assertEquals(listOf(2, 4), sideloadedSubs(item, transcode = true).map { it.index })

    @Test fun burnsInOnlyAPictureSubtitleInTheChosenLanguage() {
        assertEquals(3, burnInIndex(source, "ja"))
        assertNull(burnInIndex(source, "en"))
        assertNull(burnInIndex(source, ""))
        assertNull(burnInIndex(source.copy(mediaStreams = listOf(sub(5, "und", text = false))), ""), "off never burns in")
        assertNull(burnInIndex(source, null))
    }
}
