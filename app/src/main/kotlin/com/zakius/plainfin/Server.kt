package com.zakius.plainfin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import plainfin.core.PlayState
import plainfin.core.ResumeRules
import plainfin.core.shouldPush

enum class Sort(val label: String, val by: ItemSortBy, val order: SortOrder) {
    NAME("Name", ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
    ADDED("Date added", ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
    RELEASED("Release date", ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING),
}

/**
 * All server traffic. Next Up and Resume endpoints are never called: the "restart from episode 1"
 * bugs in other clients come from them (see docs/design.md).
 */
class Server(context: Context) {
    private val prefs = App.prefs
    val api = createJellyfin {
        clientInfo = ClientInfo("plainfin", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0")
        this.context = context
    }.createApi(prefs.baseUrl, prefs.token)

    /** Keeps reports in order, so a slow progress report can't land after the stop. */
    private val reports = Mutex()

    /** The SDK's HTTP calls block the calling thread. */
    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

    val loggedIn get() = prefs.token != null
    private val userId get() = UUID.fromString(prefs.userId)

    /** A URL without a scheme tries https, then http (Jellyfin's LAN default) if https can't connect. */
    suspend fun login(url: String, username: String, password: String) {
        val base = url.trim().trimEnd('/')
        val credentials = AuthenticateUserByName(username, password)
        if ("://" in base) return login(base, credentials)
        try { login("https://$base", credentials) }
        catch (e: Exception) { if (unreachable(e)) login("http://$base", credentials) else throw e }
    }

    private suspend fun login(base: String, credentials: AuthenticateUserByName) {
        api.update(baseUrl = base, accessToken = null)
        val auth = io { api.authenticationApi.authenticateUserByName(credentials).content }
        prefs.baseUrl = base
        prefs.token = auth.accessToken
        prefs.userId = auth.user!!.id.toString()
        api.update(baseUrl = base, accessToken = auth.accessToken)
        sessionExpired = false
    }

    fun logout() {
        prefs.logout()
        api.update(accessToken = null)
    }

    /** Set when the server rejects the token; the UI returns to login. Unsynced progress is kept and pushed after. */
    var sessionExpired by mutableStateOf(false)

    fun expire() {
        logout()
        sessionExpired = true
    }

    suspend fun libraries(): List<BaseItemDto> =
        io { api.userViewApi.getUserViews(userId).content.items }
            .filter { it.collectionType == CollectionType.MOVIES || it.collectionType == CollectionType.TVSHOWS }

    suspend fun items(parentId: String?, sort: Sort, unplayedOnly: Boolean, search: String): List<BaseItemDto> =
        io { api.libraryApi.getItems(
            userId = userId,
            parentId = parentId?.let(UUID::fromString),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            sortBy = listOf(sort.by, ItemSortBy.SORT_NAME),
            sortOrder = listOf(sort.order, SortOrder.ASCENDING),
            isPlayed = if (unplayedOnly) false else null,
            searchTerm = search.ifBlank { null },
            enableUserData = true,
        ).content.items }

    /** Full item (media sources included) with play state from [userData]. */
    suspend fun item(id: String): BaseItemDto = io {
        api.libraryApi.getItem(UUID.fromString(id), userId).content.copy(userData = userData(UUID.fromString(id)))
    }

    /**
     * Play state through the list endpoint. On 12.3.0 the single-item endpoints (`/Items/{id}`,
     * `/UserItems/{id}/UserData`) kept serving a pre-rescan state after a media file was replaced,
     * while list endpoints and the database were right. Resuming from that would jump back into a
     * finished episode.
     */
    private suspend fun userData(id: UUID) = userData(listOf(id))[id]

    suspend fun userData(ids: List<UUID>): Map<UUID, UserItemDataDto?> = io {
        api.libraryApi.getItems(userId = userId, ids = ids, enableUserData = true).content.items.associate { it.id to it.userData }
    }

    suspend fun seasons(seriesId: String): List<BaseItemDto> =
        io { api.showApi.getSeasons(UUID.fromString(seriesId), userId, enableUserData = true).content.items }

    /** All episodes of a series in season order, or only one season's. [sources] adds media sources (file sizes). */
    suspend fun episodes(seriesId: String, seasonId: String? = null, sources: Boolean = false): List<BaseItemDto> =
        io { api.showApi.getEpisodes(
            UUID.fromString(seriesId),
            userId,
            fields = listOfNotNull(ItemFields.OVERVIEW, ItemFields.MEDIA_SOURCES.takeIf { sources }),
            seasonId = seasonId?.let(UUID::fromString),
            enableUserData = true,
        ).content.items }

    suspend fun introSegment(id: String): MediaSegmentDto? =
        io { api.mediaSegmentApi.getItemSegments(UUID.fromString(id), listOf(MediaSegmentType.INTRO)).content.items.firstOrNull() }

    fun imageUrl(id: UUID, type: ImageType = ImageType.PRIMARY, width: Int = 400) =
        api.imageApi.getItemImageUrl(id, type, maxWidth = width)

    fun streamUrl(id: String, mediaSourceId: String) =
        "${prefs.baseUrl}/Videos/$id/stream?static=true&mediaSourceId=$mediaSourceId&api_key=${prefs.token}"

    // ponytail: fixed h264/aac at 8 Mbps; embedded subtitles are lost here, external ones still load.
    fun transcodeUrl(id: String, mediaSourceId: String, audioIndex: Int?) =
        "${prefs.baseUrl}/Videos/$id/master.m3u8?mediaSourceId=$mediaSourceId&videoCodec=h264&audioCodec=aac" +
            "&maxAudioChannels=2&videoBitrate=8000000&audioBitrate=192000&playSessionId=${UUID.randomUUID()}" +
            (audioIndex?.let { "&audioStreamIndex=$it" } ?: "") + "&api_key=${prefs.token}"

    fun subtitleUrl(id: String, mediaSourceId: String, stream: MediaStream) =
        "${prefs.baseUrl}/Videos/$id/$mediaSourceId/Subtitles/${stream.index}/0/Stream.${subtitleExt(stream)}?api_key=${prefs.token}"

    // --- play state ---------------------------------------------------------------------------

    suspend fun reportStart(id: String, mediaSourceId: String, positionTicks: Long, transcoding: Boolean) = io { runCatching {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                canSeek = true, isPaused = false, isMuted = false,
                playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    } }

    /** Saves locally first, then tells the server. A failed report leaves the local entry for [flush]. */
    suspend fun reportProgress(id: String, mediaSourceId: String, positionTicks: Long, paused: Boolean, transcoding: Boolean, serverPlayed: Boolean) = io {
        val played = App.pending.get(id)?.played ?: serverPlayed
        val state = PlayState(positionTicks, played, System.currentTimeMillis())
        record(id, state)
        reports.withLock { runCatching {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                    canSeek = true, isPaused = paused, isMuted = false,
                    playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
                )
            )
        }.onSuccess { App.pending.confirm(id, state) } }
    }

    /**
     * Saves the stop locally before returning, so a list opened right after already shows it, then
     * reports it in the background. Always sends a position: without one the server marks the item
     * played at 0.
     */
    fun reportStop(id: String, mediaSourceId: String, positionTicks: Long, runtimeTicks: Long?, serverPlayed: Boolean) {
        val previous = App.pending.get(id)?.played ?: serverPlayed
        val state = ResumeRules.onStop(PlayState(0, previous, null), positionTicks, runtimeTicks, System.currentTimeMillis())
        record(id, state)
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            reports.withLock { runCatching {
                api.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks, failed = false)
                )
            }.onSuccess { App.pending.confirm(id, state) } }
            Housekeeping.enqueue(App.instance)
        }
    }

    /** Every local play-state change: queued for the server and kept with the download for offline resume. */
    private fun record(id: String, state: PlayState) {
        App.pending.put(id, state)
        Media.rememberState(id, state)
    }

    suspend fun setPlayed(id: String, played: Boolean) {
        record(id, PlayState(0, played, System.currentTimeMillis()))
        flush()
    }

    /**
     * Pushes pending states; the newer LastPlayedDate wins. One failing item doesn't hold up the
     * rest: an item the server doesn't have (deleted, or from a server logged into before) is
     * dropped, and a rejected token ends the session. Returns the first failure, null when all went through.
     */
    suspend fun flush(): Throwable? = io {
        var failure: Throwable? = null
        for ((id, local) in App.pending.all()) {
            runCatching {
                val uuid = UUID.fromString(id)
                val server = userData(uuid).toPlayState()
                if (shouldPush(local, server)) {
                    api.userDataApi.updateItemUserData(
                        uuid, userId,
                        UpdateUserItemDataDto(
                            playbackPositionTicks = local.positionTicks,
                            played = local.played,
                            lastPlayedDate = local.lastPlayedMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) },
                        ),
                    )
                }
            }.onSuccess { App.pending.confirm(id, local) }.onFailure { e ->
                when ((e as? InvalidStatusException)?.status) {
                    404 -> App.pending.confirm(id, local)
                    401 -> { expire(); return@io e }
                    else -> failure = failure ?: e
                }
            }
        }
        failure
    }
}

// The SDK's LocalDateTime is in the device's zone, not UTC; reading it as UTC shifts every date
// by the zone offset and breaks the newer-wins rule.
fun UserItemDataDto?.toPlayState(): PlayState? = this?.let {
    PlayState(it.playbackPositionTicks, it.played, it.lastPlayedDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli())
}

/** Server state with any unsynced local state on top. The flag marks items that differ from the server. */
fun BaseItemDto.localState(): Pair<PlayState?, Boolean> {
    val local = App.pending.get(id.toString())
    return (local ?: userData.toPlayState()) to (local != null)
}

fun subtitleExt(stream: MediaStream) = when (val c = stream.codec?.lowercase()) {
    "subrip", null -> "srt"
    "webvtt" -> "vtt"
    else -> c
}

/** No HTTP answer: timeout, unknown host, refused connection or failed TLS. */
fun unreachable(e: Throwable) = e is TimeoutException || generateSequence(e) { it.cause }.any { it is IOException }

/** Worth retrying later: no answer, or a server-side error. */
fun transient(e: Throwable) = unreachable(e) || (e as? InvalidStatusException)?.status?.let { it >= 500 } == true
