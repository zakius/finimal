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
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
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
import finimal.core.PendingStore
import finimal.core.PlayState
import finimal.core.ResumeRules
import finimal.core.shouldPush
import finimal.core.serverCandidates

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
    private val jellyfin = createJellyfin {
        clientInfo = ClientInfo("finimal", App.platform.versionName)
        App.platform.configure(this)
        // One connection pool for API, images and streams. Every API response also updates the server clock offset.
        apiClientFactory = OkHttpFactory(App.http.newBuilder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { r -> r.headers.getDate("Date")?.let { setClock(it.time) } }
        }.build())
    }
    val api = jellyfin.createApi(prefs.baseUrl, prefs.token)

    /**
     * The account a play state was recorded for. Its reports, flush and confirm use this account's
     * own client, user and pending file, so a switch while they are in flight can't send its
     * progress as another user or confirm it in another account's file.
     */
    private class Owner(val userId: UUID, val api: ApiClient, val pending: PendingStore)

    private val playApis = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ApiClient>()

    /** The current account, read under the lock [switchTo], [login] and [logout] change it under; null when logged out. */
    private fun owner(): Owner? = synchronized(App) {
        val base = prefs.baseUrl ?: return null
        val token = prefs.token ?: return null
        val user = prefs.userId ?: return null
        Owner(UUID.fromString(user), playApis.getOrPut(base to token) { jellyfin.createApi(base, token) }, App.pending)
    }

    @Volatile private var clockOffsetMs = prefs.clockOffsetMs

    // The Date header has whole-second precision and the response took time to arrive, so smaller changes are noise.
    private fun setClock(serverMs: Long) {
        val offset = serverMs - System.currentTimeMillis()
        if (abs(offset - clockOffsetMs) > CLOCK_NOISE_MS) { clockOffsetMs = offset; prefs.clockOffsetMs = offset }
    }

    /**
     * The current time on the server's clock. The offset is kept across restarts for offline plays. Play dates
     * are compared with dates other devices sent, so a phone clock an hour fast would otherwise beat a later
     * play elsewhere.
     */
    fun now() = System.currentTimeMillis() + clockOffsetMs

    /** Keeps reports in order, so a slow progress report can't land after the stop. */
    private val reports = Mutex()

    /** The SDK's HTTP calls block the calling thread. */
    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

    val loggedIn get() = prefs.loggedIn
    private val userId get() = UUID.fromString(prefs.userId)

    /** Tries each address [serverCandidates] builds from the form fields, moving to the next only when one can't connect. */
    suspend fun login(server: String, port: String, username: String, password: String) {
        val credentials = AuthenticateUserByName(username, password)
        val tries = serverCandidates(server, port)
        require(tries.isNotEmpty()) { "No server address" }
        for ((i, base) in tries.withIndex()) {
            try { return login(base, credentials) }
            // A certificate problem does not mean the server lacks https. Falling back to http would send the password in clear text.
            catch (e: Exception) { if (i == tries.lastIndex || !unreachable(e) || untrustedCertificate(e)) throw e }
        }
    }

    private suspend fun login(base: String, credentials: AuthenticateUserByName) {
        api.update(baseUrl = base, accessToken = null)
        // If adding an account fails, restore the current account's client so it keeps working.
        val auth = try { io { api.authenticationApi.authenticateUserByName(credentials).content } }
            catch (e: Exception) { api.update(baseUrl = prefs.baseUrl, accessToken = prefs.token); throw e }
        recorded.clear()
        synchronized(App) { prefs.login(base, auth.accessToken!!, auth.user!!.id.toString(), auth.user!!.name.orEmpty()) }
        api.update(baseUrl = base, accessToken = auth.accessToken)
        App.downloads?.accountChanged()
        App.sessionExpired = false
    }

    /** Another saved account takes over; its lists, downloads and unsynced progress are its own. */
    fun switchTo(a: Account) {
        recorded.clear()
        synchronized(App) { prefs.switchTo(a) }
        api.update(accessToken = a.token)
        App.downloads?.accountChanged()
        App.sessionExpired = false
    }

    /** Removes the current account; [keepAccount] only ends its session, keeping its name and PIN for the next login. */
    fun logout(keepAccount: Boolean = false) {
        recorded.clear()
        ListSnapshots.clear() // Another account's lists must not show, even briefly.
        synchronized(App) { if (keepAccount) prefs.endSession() else prefs.logout() }
        api.update(accessToken = null)
        App.downloads?.accountChanged()
    }

    fun expire() {
        logout(keepAccount = true)
        App.sessionExpired = true
    }

    suspend fun userName(): String = io { api.userApi.getCurrentUser().content.name.orEmpty() }

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
            // The server has caught up with our recorded state, or another device changed it since. Either way the server's state wins.
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

    /** Fetches in chunks because every ID goes in the query string, and servers and proxies cap its length at around 8 KB. */
    suspend fun userData(ids: List<UUID>): Map<UUID, UserItemDataDto?> = userData(api, userId, ids)

    private suspend fun userData(api: ApiClient, userId: UUID, ids: List<UUID>): Map<UUID, UserItemDataDto?> = io {
        ids.chunked(100).flatMap { chunk ->
            api.libraryApi.getItems(userId = userId, ids = chunk, enableUserData = true, enableImages = false).content.items.map { it.id to it.userData }
        }.toMap()
    }

    suspend fun seasons(seriesId: String): List<BaseItemDto> =
        io { api.showApi.getSeasons(UUID.fromString(seriesId), userId, enableUserData = true).content.items }

    /**
     * All episodes of a series in season order, or only one season's. [sources] adds media sources (with file sizes)
     * and the overview, which stored downloads need.
     */
    suspend fun episodes(seriesId: String, seasonId: String? = null, sources: Boolean = false): List<BaseItemDto> =
        io { api.showApi.getEpisodes(
            UUID.fromString(seriesId),
            userId,
            fields = if (sources) listOf(ItemFields.OVERVIEW, ItemFields.MEDIA_SOURCES) else emptyList(),
            seasonId = seasonId?.let(UUID::fromString),
            enableUserData = true,
        ).content.items }

    /** True when the server answers at all. If it does, a playback failure lies with the file, not the network. */
    suspend fun reachable(): Boolean = io { runCatching { api.systemApi.getPublicSystemInfo() }.exceptionOrNull()?.let { !transient(it) } ?: true }

    /** Intro, recap and credits, where a server plugin has detected them. */
    suspend fun segments(id: String): List<MediaSegmentDto> = io {
        api.mediaSegmentApi.getItemSegments(UUID.fromString(id), listOf(MediaSegmentType.INTRO, MediaSegmentType.RECAP, MediaSegmentType.OUTRO)).content.items
    }

    /** Null when the item has no such image, so no request is made. The tag changes with the image, which keeps caches fresh. */
    fun imageUrl(item: BaseItemDto, type: ImageType = ImageType.PRIMARY, width: Int = 400) =
        item.imageTags?.get(type)?.let { tag ->
            api.imageApi.getItemImageUrl(item.id, type, maxWidth = width, tag = tag, format = ImageFormat.WEBP, quality = 80)
        }

    fun streamUrl(id: String, mediaSourceId: String) =
        "${prefs.baseUrl}/Videos/$id/stream?static=true&mediaSourceId=$mediaSourceId&ApiKey=${prefs.token}"

    // A fixed H.264/AAC transcode at 8 Mbps, no taller than direct play allows. The server drops embedded subtitles, so
    // players load text subtitles separately ([sideloadedSubs]) and a picture subtitle ([burnInIndex]) is burned in.
    // [playSession] also goes in the stop report, so leaving the player ends the server's ffmpeg job at once.
    fun transcodeUrl(id: String, mediaSourceId: String, audioIndex: Int?, burnInIndex: Int?, playSession: String) =
        "${prefs.baseUrl}/Videos/$id/master.m3u8?mediaSourceId=$mediaSourceId&videoCodec=h264&audioCodec=aac" +
            "&maxAudioChannels=2&maxHeight=${prefs.maxHeight.takeIf { it > 0 } ?: App.platform.transcodeHeight}&videoBitrate=8000000&audioBitrate=192000&playSessionId=$playSession" +
            (audioIndex?.let { "&audioStreamIndex=$it" } ?: "") +
            (burnInIndex?.let { "&subtitleStreamIndex=$it&subtitleMethod=Encode" } ?: "") + "&ApiKey=${prefs.token}"

    fun subtitleUrl(id: String, mediaSourceId: String, stream: MediaStream) =
        "${prefs.baseUrl}/Videos/$id/$mediaSourceId/Subtitles/${stream.index}/0/Stream.${subtitleExt(stream)}?ApiKey=${prefs.token}"

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

    /**
     * Saves locally first, then tells the server. A failed report leaves the local entry for [flush].
     * If a newer local change replaced this state while the report waited for [reports], the report is
     * skipped: the server dates a report on arrival, so the stale report would beat that newer change.
     */
    suspend fun reportProgress(id: String, mediaSourceId: String, positionTicks: Long, paused: Boolean, transcoding: Boolean, serverPlayed: Boolean, playSession: String) = io {
        val played = knownPlayed(id, serverPlayed)
        val state = PlayState(positionTicks, played, now())
        val owner = record(id, state) ?: return@io
        reports.withLock { if (owner.pending.get(id) == state) runCatching {
            owner.api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                    canSeek = true, isPaused = paused, isMuted = false,
                    playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, playSessionId = playSession,
                )
            )
        }.onSuccess { confirm(owner, id, state) } }
    }

    /**
     * Queues the local save on [App.disk] before returning, so a list opened right after this flushes it,
     * then reports the stop. It always sends a position, because without one the server marks the item played at 0.
     */
    fun reportStop(id: String, mediaSourceId: String, positionTicks: Long, runtimeTicks: Long?, serverPlayed: Boolean, playSession: String) {
        App.platform.scope.launch(App.disk) {
            val previous = knownPlayed(id, serverPlayed)
            val state = ResumeRules.onStop(PlayState(0, previous, null), positionTicks, runtimeTicks, now())
            val owner = record(id, state)
            io {
                // Skipped when superseded, as in reportProgress. The server then ends the playback session on its own.
                if (owner != null) reports.withLock { if (owner.pending.get(id) == state) runCatching {
                    owner.api.sessionApi.reportPlaybackStopped(
                        PlaybackStopInfo(itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks, playSessionId = playSession, failed = false)
                    )
                }.onSuccess { confirm(owner, id, state) } }
                App.platform.scheduleHousekeeping()
            }
        }
    }

    /** Bumped on every local play-state change; [localState] reads it so rows redraw without a refetch. */
    var localChanges by mutableIntStateOf(0)
        private set

    /**
     * The last state recorded this session, kept even after the server confirms it. Otherwise a player loaded
     * before "Mark played" would report its stale load-time flag and un-mark the item.
     */
    private val recorded = java.util.concurrent.ConcurrentHashMap<String, PlayState>()

    private fun knownPlayed(id: String, serverPlayed: Boolean) = (App.pending.get(id) ?: recorded[id])?.played ?: serverPlayed

    /**
     * Every local play-state change goes through here: it is queued for the server and kept with the download
     * for offline resume. Returns the account [state] was queued for, or null when logged out, in which case
     * the state waits in the last account's file.
     */
    private suspend fun record(id: String, state: PlayState): Owner? = withContext(App.disk) {
        val owner = owner()
        recorded[id] = state
        (owner?.pending ?: App.pending).put(id, state)
        App.downloads?.rememberState(id, state)
        localChanges++
        owner
    }

    /** "Start over" as a saved position of 0, so a restored back stack never restarts the item a second time. */
    suspend fun startOver(item: BaseItemDto) {
        val id = item.id.toString()
        record(id, PlayState(0, knownPlayed(id, item.userData?.played ?: false), now()))
    }

    /** Marks [state] as stored on the server. Bumping [localChanges] makes rows drop their "not synced" mark at once. */
    private fun confirm(owner: Owner, id: String, state: PlayState) {
        owner.pending.confirm(id, state)
        localChanges++
    }

    /**
     * Takes [ids] in season order. Each gets its own date, a millisecond apart, so the newest one is where a
     * viewer would pick up: the last one marked played, or the first one marked unplayed.
     */
    suspend fun setPlayed(ids: List<String>, played: Boolean) {
        val now = now()
        ids.forEachIndexed { i, id -> record(id, PlayState(0, played, now - if (played) ids.lastIndex - i else i)) }
        flush()
    }

    /**
     * Pushes pending states; the newer LastPlayedDate wins. One failing item doesn't hold up the
     * rest. An item the server doesn't have (deleted, or from a server logged into before) is
     * dropped, and a rejected token ends the session. An unreachable server stops the whole flush
     * rather than timing out once per item. Returns the first failure, null when all went through.
     */
    suspend fun flush(): Throwable? = io { flushing.withLock {
        val (owner, pending) = withContext(App.disk) { owner()?.let { it to it.pending.all() } } ?: return@withLock null
        if (pending.isEmpty()) return@withLock null
        // A 401 for an account we have switched away from must not end the session of the account switched to.
        fun expireOwner() { if (owner()?.api === owner.api) expire() }
        // One request fetches every item's server state. An item the server lacks is absent here, and its push returns 404.
        val serverStates = try { userData(owner.api, owner.userId, pending.keys.map(UUID::fromString)) } catch (e: Exception) {
            if ((e as? InvalidStatusException)?.status == 401) expireOwner()
            return@withLock e
        }
        var failure: Throwable? = null
        for ((id, local) in pending) {
            // Push under [reports], and only while [local] is still pending. A report that landed after
            // serverStates was fetched is newer than both, and pushing [local] would overwrite it.
            runCatching { reports.withLock {
                val uuid = UUID.fromString(id)
                if (owner.pending.get(id) == local && shouldPush(local, serverStates[uuid].toPlayState())) {
                    owner.api.userDataApi.updateItemUserData(
                        uuid, owner.userId,
                        UpdateUserItemDataDto(
                            playbackPositionTicks = local.positionTicks,
                            played = local.played,
                            lastPlayedDate = local.lastPlayedMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) },
                        ),
                    )
                }
            } }.onSuccess { confirm(owner, id, local) }.onFailure { e ->
                when ((e as? InvalidStatusException)?.status) {
                    404 -> confirm(owner, id, local)
                    401 -> { expireOwner(); return@withLock e }
                    else -> { failure = failure ?: e; if (unreachable(e)) return@withLock e }
                }
            }
        }
        failure
    } }

    /** One flush at a time, because two overlapping ones could push an older state after a newer one. */
    private val flushing = Mutex()

    private companion object {
        const val CLOCK_NOISE_MS = 2_000L
    }
}

// The SDK's LocalDateTime is in the device's time zone, not UTC. Reading it as UTC would shift every date
// by the zone offset and break the newer-wins rule.
fun UserItemDataDto?.toPlayState(): PlayState? = this?.let {
    PlayState(it.playbackPositionTicks, it.played, it.lastPlayedDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli())
}

/** The server state with any unsynced local state on top. The Boolean is true when the local state differs from the server's. */
fun BaseItemDto.localState(): Pair<PlayState?, Boolean> {
    App.server.localChanges // Reading this makes a composable caller redraw on the next local change.
    val local = App.pending.get(id.toString())
    return (local ?: userData.toPlayState()) to (local != null)
}

fun subtitleExt(stream: MediaStream) = when (val c = stream.codec?.lowercase()) {
    "subrip", null -> "srt"
    "webvtt" -> "vtt"
    else -> c
}

/** An https server whose certificate isn't trusted, has expired, or is for another name. */
fun untrustedCertificate(e: Throwable) = generateSequence(e) { it.cause }
    .any { it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException || it is javax.net.ssl.SSLPeerUnverifiedException }

/** No HTTP answer: a timeout, unknown host, refused connection or failed TLS. */
fun unreachable(e: Throwable) = e is TimeoutException || generateSequence(e) { it.cause }.any { it is IOException }

/** Worth retrying later: there was no answer, or the server had an internal error. */
fun transient(e: Throwable) = unreachable(e) || (e as? InvalidStatusException)?.status?.let { it >= 500 } == true
