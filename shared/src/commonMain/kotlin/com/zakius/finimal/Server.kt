package com.zakius.finimal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.abs
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
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageFormat
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
import finimal.core.PlayState
import finimal.core.ResumeRules
import finimal.core.shouldPush

enum class Sort(val label: String, val by: ItemSortBy, val order: SortOrder) {
    NAME("Name", ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
    ADDED("Date added", ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
    RELEASED("Release date", ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING),
}

/**
 * All server traffic. Next Up and Resume endpoints are never called: the "restart from episode 1"
 * bugs in other clients come from them (see docs/design.md).
 */
class Server {
    private val prefs = App.prefs
    val api = createJellyfin {
        clientInfo = ClientInfo("finimal", App.platform.versionName)
        App.platform.configure(this)
        // One connection pool for API, images and streams. API answers also set the server clock.
        apiClientFactory = OkHttpFactory(App.http.newBuilder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { r -> r.headers.getDate("Date")?.let { setClock(it.time) } }
        }.build())
    }.createApi(prefs.baseUrl, prefs.token)

    @Volatile private var clockOffsetMs = prefs.clockOffsetMs

    // The header has whole seconds and the answer took time to arrive; smaller moves are noise.
    private fun setClock(serverMs: Long) {
        val offset = serverMs - System.currentTimeMillis()
        if (abs(offset - clockOffsetMs) > 2_000) { clockOffsetMs = offset; prefs.clockOffsetMs = offset }
    }

    /**
     * Now on the server's clock, kept across restarts for offline plays. Play dates are compared with
     * dates other devices sent; a phone clock an hour fast would otherwise beat a later play elsewhere.
     */
    fun now() = System.currentTimeMillis() + clockOffsetMs

    /** Keeps reports in order, so a slow progress report can't land after the stop. */
    private val reports = Mutex()

    /** The SDK's HTTP calls block the calling thread. */
    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

    val loggedIn get() = prefs.loggedIn
    private val userId get() = UUID.fromString(prefs.userId)

    /** A URL without a scheme tries https, then http (Jellyfin's LAN default) if https can't connect. */
    suspend fun login(url: String, username: String, password: String) {
        val base = url.trim().trimEnd('/')
        val credentials = AuthenticateUserByName(username, password)
        if ("://" in base) return login(base, credentials)
        try { login("https://$base", credentials) }
        // A certificate problem is not a server without https: falling back would send the password in clear text.
        catch (e: Exception) { if (unreachable(e) && !untrustedCertificate(e)) login("http://$base", credentials) else throw e }
    }

    private suspend fun login(base: String, credentials: AuthenticateUserByName) {
        api.update(baseUrl = base, accessToken = null)
        val auth = io { api.authenticationApi.authenticateUserByName(credentials).content }
        prefs.login(base, auth.accessToken, auth.user!!.id.toString())
        api.update(baseUrl = base, accessToken = auth.accessToken)
        App.sessionExpired = false
    }

    fun logout() {
        recorded.clear()
        ListSnapshots.clear() // another account's lists must not show, even briefly
        prefs.logout()
        api.update(accessToken = null)
    }

    fun expire() {
        logout()
        App.sessionExpired = true
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
            // The grid shows one poster and never a count; whole libraries come back in one response.
            enableTotalRecordCount = false,
            enableImageTypes = listOf(ImageType.PRIMARY),
            imageTypeLimit = 1,
        ).content.items }

    /**
     * Full item (media sources included) with play state from [userData]. With [flushFirst] the play
     * state is read after pushing pending states, while the item itself loads alongside the push.
     */
    suspend fun item(id: String, flushFirst: Boolean = false): BaseItemDto = io {
        val uuid = UUID.fromString(id)
        val dto = async { api.libraryApi.getItem(uuid, userId).content }
        val data = if (flushFirst) {
            flush()?.takeIf(::unreachable)?.let { dto.cancel(); throw it }
            userData(uuid)
        } else async { userData(uuid) }.await()
        dto.await().copy(userData = data).also {
            // The server has caught up, or changed it since (another device): its state is the one to build on.
            val server = data.toPlayState()?.lastPlayedMs
            if (server != null && server >= (recorded[id]?.lastPlayedMs ?: Long.MAX_VALUE)) recorded.remove(id)
        }
    }

    /**
     * Play state through the list endpoint. On 12.3.0 the single-item endpoints (`/Items/{id}`,
     * `/UserItems/{id}/UserData`) kept serving a pre-rescan state after a media file was replaced,
     * while list endpoints and the database were right. Resuming from that would jump back into a
     * finished episode.
     */
    private suspend fun userData(id: UUID) = userData(listOf(id))[id]

    /** In chunks: every ID goes in the query string, and servers and proxies cap its length around 8 KB. */
    suspend fun userData(ids: List<UUID>): Map<UUID, UserItemDataDto?> = io {
        ids.chunked(100).flatMap { chunk ->
            api.libraryApi.getItems(userId = userId, ids = chunk, enableUserData = true, enableImages = false).content.items.map { it.id to it.userData }
        }.toMap()
    }

    suspend fun seasons(seriesId: String): List<BaseItemDto> =
        io { api.showApi.getSeasons(UUID.fromString(seriesId), userId, enableUserData = true).content.items }

    /** All episodes of a series in season order, or only one season's. [sources] adds media sources (file sizes) and the overview, for stored downloads. */
    suspend fun episodes(seriesId: String, seasonId: String? = null, sources: Boolean = false): List<BaseItemDto> =
        io { api.showApi.getEpisodes(
            UUID.fromString(seriesId),
            userId,
            fields = if (sources) listOf(ItemFields.OVERVIEW, ItemFields.MEDIA_SOURCES) else emptyList(),
            seasonId = seasonId?.let(UUID::fromString),
            enableUserData = true,
        ).content.items }

    /** Intro, recap and credits, where a server plugin has detected them. */
    suspend fun segments(id: String): List<MediaSegmentDto> = io {
        api.mediaSegmentApi.getItemSegments(UUID.fromString(id), listOf(MediaSegmentType.INTRO, MediaSegmentType.RECAP, MediaSegmentType.OUTRO)).content.items
    }

    /** Null when the item has no such image, so no request is made. The tag changes with the image, keeping caches fresh. */
    fun imageUrl(item: BaseItemDto, type: ImageType = ImageType.PRIMARY, width: Int = 400) =
        item.imageTags?.get(type)?.let { tag ->
            api.imageApi.getItemImageUrl(item.id, type, maxWidth = width, tag = tag, format = ImageFormat.WEBP, quality = 80)
        }

    fun streamUrl(id: String, mediaSourceId: String) =
        "${prefs.baseUrl}/Videos/$id/stream?static=true&mediaSourceId=$mediaSourceId&ApiKey=${prefs.token}"

    // ponytail: fixed h264/aac at 8 Mbps, no taller than direct play allows; embedded subtitles are lost here, external ones still load.
    // [playSession] goes in the stop report too, so leaving ends the server's ffmpeg job at once.
    fun transcodeUrl(id: String, mediaSourceId: String, audioIndex: Int?, playSession: String) =
        "${prefs.baseUrl}/Videos/$id/master.m3u8?mediaSourceId=$mediaSourceId&videoCodec=h264&audioCodec=aac" +
            "&maxAudioChannels=2&maxHeight=${prefs.maxHeight.takeIf { it > 0 } ?: App.platform.transcodeHeight}&videoBitrate=8000000&audioBitrate=192000&playSessionId=$playSession" +
            (audioIndex?.let { "&audioStreamIndex=$it" } ?: "") + "&ApiKey=${prefs.token}"

    fun subtitleUrl(id: String, mediaSourceId: String, stream: MediaStream) =
        "${prefs.baseUrl}/Videos/$id/$mediaSourceId/Subtitles/${stream.index}/0/Stream.${subtitleExt(stream)}?ApiKey=${prefs.token}"

    // --- play state ---------------------------------------------------------------------------

    suspend fun reportStart(id: String, mediaSourceId: String, positionTicks: Long, transcoding: Boolean, playSession: String) = io { reports.withLock { runCatching {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                canSeek = true, isPaused = false, isMuted = false,
                playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, playSessionId = playSession,
            )
        )
    } } }

    /** Saves locally first, then tells the server. A failed report leaves the local entry for [flush]. */
    suspend fun reportProgress(id: String, mediaSourceId: String, positionTicks: Long, paused: Boolean, transcoding: Boolean, serverPlayed: Boolean, playSession: String) = io {
        val played = knownPlayed(id, serverPlayed)
        val state = PlayState(positionTicks, played, now())
        record(id, state)
        reports.withLock { runCatching {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                    canSeek = true, isPaused = paused, isMuted = false,
                    playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, playSessionId = playSession,
                )
            )
        }.onSuccess { confirm(id, state) } }
    }

    /**
     * Queues the local save on [App.disk] before returning, so a list opened right after flushes it,
     * then reports the stop. Always sends a position: without one the server marks the item played at 0.
     */
    fun reportStop(id: String, mediaSourceId: String, positionTicks: Long, runtimeTicks: Long?, serverPlayed: Boolean, playSession: String) {
        App.platform.scope.launch(App.disk) {
            val previous = knownPlayed(id, serverPlayed)
            val state = ResumeRules.onStop(PlayState(0, previous, null), positionTicks, runtimeTicks, now())
            record(id, state)
            io {
                reports.withLock { runCatching {
                    api.sessionApi.reportPlaybackStopped(
                        PlaybackStopInfo(itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks, playSessionId = playSession, failed = false)
                    )
                }.onSuccess { confirm(id, state) } }
                App.platform.scheduleHousekeeping()
            }
        }
    }

    /** Bumped on every local play-state change; [localState] reads it so rows redraw without a refetch. */
    var localChanges by mutableIntStateOf(0)
        private set

    /**
     * Last state recorded this session, kept after the server confirms it: a player loaded before
     * "Mark played" would otherwise report its stale load-time flag and un-mark the item.
     */
    private val recorded = java.util.concurrent.ConcurrentHashMap<String, PlayState>()

    private fun knownPlayed(id: String, serverPlayed: Boolean) = (App.pending.get(id) ?: recorded[id])?.played ?: serverPlayed

    /** Every local play-state change: queued for the server and kept with the download for offline resume. */
    private suspend fun record(id: String, state: PlayState): Unit = withContext(App.disk) {
        recorded[id] = state
        App.pending.put(id, state)
        App.downloads?.rememberState(id, state)
        localChanges++
    }

    /** "Start over" as a saved position of 0, so a restored back stack never restarts the item a second time. */
    suspend fun startOver(item: BaseItemDto) {
        val id = item.id.toString()
        record(id, PlayState(0, knownPlayed(id, item.userData?.played ?: false), now()))
    }

    /** The server has [state]; rows drop their "not synced" at once rather than at the next local change. */
    private fun confirm(id: String, state: PlayState) {
        App.pending.confirm(id, state)
        localChanges++
    }

    /**
     * [ids] in season order. Each gets its own date, a millisecond apart, so the newest is where a
     * viewer would pick up: the last one marked played, or the first one marked unplayed.
     */
    suspend fun setPlayed(ids: List<String>, played: Boolean) {
        val now = now()
        ids.forEachIndexed { i, id -> record(id, PlayState(0, played, now - if (played) ids.lastIndex - i else i)) }
        flush()
    }

    /**
     * Pushes pending states; the newer LastPlayedDate wins. One failing item doesn't hold up the
     * rest: an item the server doesn't have (deleted, or from a server logged into before) is
     * dropped, and a rejected token ends the session. An unreachable server stops the whole flush
     * rather than timing out once per item. Returns the first failure, null when all went through.
     */
    suspend fun flush(): Throwable? = io { flushing.withLock {
        val pending = withContext(App.disk) { App.pending.all() }
        if (pending.isEmpty()) return@withLock null
        // One request for every item's server state; an item the server lacks is absent and its push 404s.
        val serverStates = try { userData(pending.keys.map(UUID::fromString)) } catch (e: Exception) {
            if ((e as? InvalidStatusException)?.status == 401) expire()
            return@withLock e
        }
        var failure: Throwable? = null
        for ((id, local) in pending) {
            runCatching {
                val uuid = UUID.fromString(id)
                if (shouldPush(local, serverStates[uuid].toPlayState())) {
                    api.userDataApi.updateItemUserData(
                        uuid, userId,
                        UpdateUserItemDataDto(
                            playbackPositionTicks = local.positionTicks,
                            played = local.played,
                            lastPlayedDate = local.lastPlayedMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) },
                        ),
                    )
                }
            }.onSuccess { confirm(id, local) }.onFailure { e ->
                when ((e as? InvalidStatusException)?.status) {
                    404 -> confirm(id, local)
                    401 -> { expire(); return@withLock e }
                    else -> { failure = failure ?: e; if (unreachable(e)) return@withLock e }
                }
            }
        }
        failure
    } }

    /** One flush at a time: two overlapping ones could push an older state after a newer one. */
    private val flushing = Mutex()
}

// The SDK's LocalDateTime is in the device's zone, not UTC; reading it as UTC shifts every date
// by the zone offset and breaks the newer-wins rule.
fun UserItemDataDto?.toPlayState(): PlayState? = this?.let {
    PlayState(it.playbackPositionTicks, it.played, it.lastPlayedDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli())
}

/** Server state with any unsynced local state on top. The flag marks items that differ from the server. */
fun BaseItemDto.localState(): Pair<PlayState?, Boolean> {
    App.server.localChanges // a composable caller redraws on the next local change
    val local = App.pending.get(id.toString())
    return (local ?: userData.toPlayState()) to (local != null)
}

fun subtitleExt(stream: MediaStream) = when (val c = stream.codec?.lowercase()) {
    "subrip", null -> "srt"
    "webvtt" -> "vtt"
    else -> c
}

/** No HTTP answer: timeout, unknown host, refused connection or failed TLS. */
/** An https server whose certificate isn't trusted, expired, or for another name. */
fun untrustedCertificate(e: Throwable) = generateSequence(e) { it.cause }
    .any { it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException || it is javax.net.ssl.SSLPeerUnverifiedException }

fun unreachable(e: Throwable) = e is TimeoutException || generateSequence(e) { it.cause }.any { it is IOException }

/** Worth retrying later: no answer, or a server-side error. */
fun transient(e: Throwable) = unreachable(e) || (e as? InvalidStatusException)?.status?.let { it >= 500 } == true
