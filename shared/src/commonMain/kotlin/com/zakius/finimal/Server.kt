package com.zakius.finimal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
import finimal.core.triesNextCandidate

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
        apiClientFactory = OkHttpFactory(App.http.newBuilder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val date = response.headers.getDate("Date")
            if (date != null) setClock(date.time)
            response
        }.build())
    }
    val api = jellyfin.createApi(prefs.baseUrl, prefs.token)

    private class Owner(val userId: UUID, val api: ApiClient, val pending: PendingStore)

    private val playApis = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ApiClient>()

    private fun owner(): Owner? = synchronized(App) {
        val base = prefs.baseUrl ?: return null
        val token = prefs.token ?: return null
        val user = prefs.userId ?: return null
        val ownerId = UUID.fromString(user)
        val playApi = playApis.getOrPut(base to token) { jellyfin.createApi(base, token) }
        Owner(ownerId, playApi, App.pending)
    }

    @Volatile private var clockOffsetMs = prefs.clockOffsetMs

    // The Date header has whole-second precision and the response took time to arrive, so smaller changes are noise.
    private fun setClock(serverMs: Long) {
        val offset = serverMs - System.currentTimeMillis()
        if (abs(offset - clockOffsetMs) > CLOCK_NOISE_MS) {
            clockOffsetMs = offset
            prefs.clockOffsetMs = offset
        }
    }

    /**
     * The current time on the server's clock. The offset is kept across restarts for offline plays. Play dates
     * are compared with dates other devices sent, so a phone clock an hour fast would otherwise beat a later
     * play elsewhere.
     */
    fun now() = System.currentTimeMillis() + clockOffsetMs

    private val itemLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun itemLock(id: String) = itemLocks.computeIfAbsent(id) { Mutex() }

    private val inFlightRecords = AtomicInteger(0)
    private val recordVersion = AtomicLong(0)

    /** True when unsynced local play states exist or a record write is still queued on [App.disk]. */
    fun hasPendingWork(): Boolean = inFlightRecords.get() > 0 || !App.pendingLoadedEmpty()

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
            try {
                return login(base, credentials)
            } catch (e: Exception) {
                if (i == tries.lastIndex || !triesNextCandidate(e, noAnswer = unreachable(e))) throw e
            }
        }
    }

    private suspend fun login(base: String, credentials: AuthenticateUserByName) {
        api.update(baseUrl = base, accessToken = null)
        val auth = try {
            io { api.authenticationApi.authenticateUserByName(credentials).content }
        } catch (e: Exception) {
            api.update(baseUrl = prefs.baseUrl, accessToken = prefs.token)
            throw e
        }
        recorded.clear()
        AppCache.clear()
        synchronized(App) {
            val token = auth.accessToken!!
            val user = auth.user!!
            prefs.login(base, token, user.id.toString(), user.name.orEmpty())
        }
        api.update(baseUrl = base, accessToken = auth.accessToken)
        App.downloads?.accountChanged()
        App.sessionExpired = false
        App.expiredUserName = null
    }

    /** Another saved account takes over; its lists, downloads and unsynced progress are its own. */
    fun switchTo(a: Account) {
        recorded.clear()
        AppCache.clear()
        synchronized(App) { prefs.switchTo(a) }
        api.update(accessToken = a.token)
        App.downloads?.accountChanged()
        App.sessionExpired = false
        App.expiredUserName = null
    }

    /** Removes the current account; [keepAccount] only ends its session, keeping its name and PIN for the next login. */
    fun logout(keepAccount: Boolean = false) {
        recorded.clear()
        AppCache.clear()
        ListSnapshots.clear()
        synchronized(App) { if (keepAccount) prefs.endSession() else prefs.logout() }
        api.update(accessToken = null)
        App.downloads?.accountChanged()
    }

    fun expire() {
        val name = synchronized(App) {
            prefs.accounts().firstOrNull { it.userId == prefs.userId }?.name
        }
        App.expiredUserName = name?.ifBlank { null }
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
            parentId = if (parentId != null) UUID.fromString(parentId) else null,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            fields = listOf(ItemFields.DATE_CREATED, ItemFields.SORT_NAME),
            sortBy = listOf(sort.by, ItemSortBy.SORT_NAME),
            sortOrder = listOf(sort.order, SortOrder.ASCENDING),
            isPlayed = if (unplayedOnly) false else null,
            searchTerm = search.ifBlank { null },
            enableUserData = true,
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
            val failure = flush()
            if (failure != null && unreachable(failure)) {
                dto.cancel()
                throw failure
            }
            userData(uuid)
        } else {
            async { userData(uuid) }.await()
        }
        val full = dto.await().copy(userData = data)
        val server = data.toPlayState()?.lastPlayedMs
        if (server != null) {
            val local = recorded[id]?.lastPlayedMs ?: Long.MAX_VALUE
            if (server >= local) recorded.remove(id)
        }
        full
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
            val items = api.libraryApi.getItems(
                userId = userId,
                ids = chunk,
                enableUserData = true,
                enableImages = false,
                enableTotalRecordCount = false,
            ).content.items
            items.map { item -> item.id to item.userData }
        }.toMap()
    }

    suspend fun seasons(seriesId: String): List<BaseItemDto> =
        io { api.showApi.getSeasons(UUID.fromString(seriesId), userId, enableUserData = true, enableImages = false).content.items }

    /**
     * All episodes of a series in season order, or only one season's. [sources] adds media sources (with file sizes)
     * and the overview, which stored downloads need.
     */
    suspend fun episodes(seriesId: String, seasonId: String? = null, sources: Boolean = false): List<BaseItemDto> =
        io { api.showApi.getEpisodes(
            UUID.fromString(seriesId),
            userId,
            fields = if (sources) listOf(ItemFields.OVERVIEW, ItemFields.MEDIA_SOURCES) else emptyList(),
            seasonId = if (seasonId != null) UUID.fromString(seasonId) else null,
            enableImageTypes = listOf(ImageType.PRIMARY),
            imageTypeLimit = 1,
            enableUserData = true,
        ).content.items }

    /** True when the server answers at all. If it does, a playback failure lies with the file, not the network. */
    suspend fun reachable(): Boolean = io {
        val error = runCatching { api.systemApi.getPublicSystemInfo() }.exceptionOrNull()
        if (error == null) true else !transient(error)
    }

    /** Intro, recap and credits, where a server plugin has detected them. */
    suspend fun segments(id: String): List<MediaSegmentDto> = io {
        api.mediaSegmentApi.getItemSegments(UUID.fromString(id), listOf(MediaSegmentType.INTRO, MediaSegmentType.RECAP, MediaSegmentType.OUTRO)).content.items
    }

    /** Null when the item has no such image, so no request is made. The tag changes with the image, which keeps caches fresh. */
    fun imageUrl(item: BaseItemDto, type: ImageType = ImageType.PRIMARY, width: Int = 400): String? {
        val tag = item.imageTags?.get(type) ?: return null
        return api.imageApi.getItemImageUrl(item.id, type, maxWidth = width, tag = tag, format = ImageFormat.WEBP, quality = 80)
    }

    fun streamUrl(id: String, mediaSourceId: String) =
        "${prefs.baseUrl}/Videos/$id/stream?static=true&mediaSourceId=$mediaSourceId&ApiKey=${prefs.token}"

    // A fixed H.264/AAC transcode at 8 Mbps, no taller than direct play allows. The server drops embedded subtitles, so
    // players load text subtitles separately ([sideloadedSubs]) and a picture subtitle ([burnInIndex]) is burned in.
    // [playSession] also goes in the stop report, so leaving the player ends the server's ffmpeg job at once.
    fun transcodeUrl(id: String, mediaSourceId: String, audioIndex: Int?, burnInIndex: Int?, playSession: String): String {
        val base = prefs.baseUrl
        val maxHeight = prefs.maxHeight
        val height = if (maxHeight > 0) maxHeight else App.platform.transcodeHeight
        val audio = if (audioIndex != null) "&audioStreamIndex=$audioIndex" else ""
        val burnIn = if (burnInIndex != null) "&subtitleStreamIndex=$burnInIndex&subtitleMethod=Encode" else ""
        return "$base/Videos/$id/master.m3u8?mediaSourceId=$mediaSourceId&videoCodec=h264&audioCodec=aac" +
            "&maxAudioChannels=2&maxHeight=$height&videoBitrate=8000000&audioBitrate=192000&playSessionId=$playSession" +
            audio + burnIn + "&ApiKey=${prefs.token}"
    }

    fun subtitleUrl(id: String, mediaSourceId: String, stream: MediaStream) =
        "${prefs.baseUrl}/Videos/$id/$mediaSourceId/Subtitles/${stream.index}/0/Stream.${subtitleExt(stream)}?ApiKey=${prefs.token}"

    suspend fun reportStart(id: String, mediaSourceId: String, positionTicks: Long, transcoding: Boolean, playSession: String) = io {
        itemLock(id).withLock {
            runCatching {
                api.sessionApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                        canSeek = true, isPaused = false, isMuted = false,
                        playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, playSessionId = playSession,
                    )
                )
            }
        }
    }

    /**
     * Saves locally first, then tells the server. A failed report leaves the local entry for [flush].
     * If a newer local change replaced this state while the report waited for [itemLock], the report is
     * skipped: the server dates a report on arrival, so the stale report would beat that newer change.
     */
    suspend fun reportProgress(id: String, mediaSourceId: String, positionTicks: Long, paused: Boolean, transcoding: Boolean, serverPlayed: Boolean, playSession: String) = io {
        val played = knownPlayed(id, serverPlayed)
        val state = PlayState(positionTicks, played, now())
        val owner = record(id, state) ?: return@io
        itemLock(id).withLock {
            if (owner.pending.get(id) == state) {
                runCatching {
                    owner.api.sessionApi.reportPlaybackProgress(
                        PlaybackProgressInfo(
                            itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks,
                            canSeek = true, isPaused = paused, isMuted = false,
                            playMethod = if (transcoding) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                            repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, playSessionId = playSession,
                        )
                    )
                }.onSuccess { confirm(owner, id, state) }
            }
        }
    }

    /**
     * Queues the local save on [App.disk] before returning, so a list opened right after this flushes it,
     * then reports the stop. It always sends a position, because without one the server marks the item played at 0.
     */
    fun reportStop(id: String, mediaSourceId: String, positionTicks: Long, runtimeTicks: Long?, serverPlayed: Boolean, playSession: String) {
        inFlightRecords.incrementAndGet()
        App.platform.scope.launch(App.disk) {
            val owner: Owner?
            val state: PlayState
            try {
                val previous = knownPlayed(id, serverPlayed)
                state = ResumeRules.onStop(PlayState(0, previous, null), positionTicks, runtimeTicks, now())
                owner = recordOnDisk(id, state)
            } finally {
                inFlightRecords.decrementAndGet()
            }
            io {
                if (owner != null) {
                    itemLock(id).withLock {
                        if (owner.pending.get(id) == state) {
                            runCatching {
                                owner.api.sessionApi.reportPlaybackStopped(
                                    PlaybackStopInfo(itemId = UUID.fromString(id), mediaSourceId = mediaSourceId, positionTicks = positionTicks, playSessionId = playSession, failed = false)
                                )
                            }.onSuccess { confirm(owner, id, state) }
                        }
                    }
                }
                App.platform.scheduleHousekeeping()
            }
        }
    }

    /** Bumped on every local play-state change; [localState] reads it so rows redraw without a refetch. */
    var localChanges by mutableIntStateOf(0)
        private set

    private val recorded = java.util.concurrent.ConcurrentHashMap<String, PlayState>()

    private fun knownPlayed(id: String, serverPlayed: Boolean): Boolean {
        val latest = App.pending.get(id) ?: recorded[id]
        return latest?.played ?: serverPlayed
    }

    private fun recordOnDisk(id: String, state: PlayState): Owner? {
        val owner = owner()
        recorded[id] = state
        val pending = owner?.pending ?: App.pending
        pending.put(id, state)
        App.downloads?.rememberState(id, state)
        recordVersion.incrementAndGet()
        localChanges++
        return owner
    }

    private suspend fun record(id: String, state: PlayState): Owner? {
        inFlightRecords.incrementAndGet()
        return try {
            withContext(App.disk) { recordOnDisk(id, state) }
        } finally {
            inFlightRecords.decrementAndGet()
        }
    }

    private suspend fun recordAll(entries: Map<String, PlayState>): Owner? {
        if (entries.isEmpty()) return owner()
        inFlightRecords.incrementAndGet()
        return try {
            withContext(App.disk) {
                val owner = owner()
                recorded.putAll(entries)
                val pending = owner?.pending ?: App.pending
                pending.putAll(entries)
                val downloads = App.downloads
                if (downloads != null) {
                    for ((id, state) in entries) downloads.rememberState(id, state)
                }
                recordVersion.incrementAndGet()
                localChanges++
                owner
            }
        } finally {
            inFlightRecords.decrementAndGet()
        }
    }

    /** "Start over" as a saved position of 0, so a restored back stack never restarts the item a second time. */
    suspend fun startOver(item: BaseItemDto) {
        val id = item.id.toString()
        val serverPlayed = item.userData?.played ?: false
        val played = knownPlayed(id, serverPlayed)
        record(id, PlayState(0, played, now()))
    }

    private fun confirm(owner: Owner, id: String, state: PlayState) {
        owner.pending.confirm(id, state)
        localChanges++
    }

    /**
     * Takes [ids] in season order. Each gets its own date, a millisecond apart, so the newest one is where a
     * viewer would pick up: the last one marked played, or the first one marked unplayed.
     */
    suspend fun setPlayed(ids: List<String>, played: Boolean) {
        if (ids.isEmpty()) return
        val now = now()
        val entries = LinkedHashMap<String, PlayState>(ids.size)
        ids.forEachIndexed { i, id ->
            val stepsBack = if (played) ids.lastIndex - i else i
            entries[id] = PlayState(0, played, now - stepsBack)
        }
        recordAll(entries)
        flush()
    }

    private val flushSyncLock = Any()
    private var activeFlush: Pair<Long, Deferred<Throwable?>>? = null

    /**
     * Pushes pending states; the newer LastPlayedDate wins. One failing item doesn't hold up the
     * rest. An item the server doesn't have (deleted, or from a server logged into before) is
     * dropped, and a rejected token ends the session. An unreachable server stops the whole flush
     * rather than timing out once per item. Returns the first failure, null when all went through.
     *
     * Fast-paths to null when nothing is pending or queued on [App.disk], and coalesces concurrent
     * callers when no new local state was recorded since the running flush started.
     */
    suspend fun flush(): Throwable? {
        if (!hasPendingWork()) return null
        val version = recordVersion.get()
        val joined = synchronized(flushSyncLock) {
            val running = activeFlush
            if (running != null && running.first == version && running.second.isActive) {
                running.second
            } else {
                val created = App.platform.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    runFlush()
                }
                activeFlush = version to created
                created.invokeOnCompletion {
                    synchronized(flushSyncLock) {
                        if (activeFlush?.second === created) activeFlush = null
                    }
                }
                created
            }
        }
        joined.start()
        return joined.await()
    }

    private suspend fun runFlush(): Throwable? = flushing.withLock {
        val snapshot = withContext(App.disk) {
            val current = owner()
            if (current == null) null else current to current.pending.all()
        }
        if (snapshot == null) return@withLock null
        val (owner, pending) = snapshot
        if (pending.isEmpty()) return@withLock null
        fun expireOwner() {
            if (owner()?.api === owner.api) expire()
        }
        val serverStates = try {
            userData(owner.api, owner.userId, pending.keys.map(UUID::fromString))
        } catch (e: Exception) {
            if (e is InvalidStatusException && e.status == 401) expireOwner()
            return@withLock e
        }
        val confirmed = java.util.concurrent.ConcurrentHashMap<String, PlayState>()
        val firstFailure = AtomicReference<Throwable?>(null)
        val abortError = AtomicReference<Throwable?>(null)
        val aborted = AtomicBoolean(false)
        val semaphore = Semaphore(4)
        try {
            coroutineScope {
                pending.entries.map { (id, local) ->
                    async {
                        if (aborted.get()) return@async
                        semaphore.withPermit {
                            if (aborted.get()) return@withPermit
                            runCatching {
                                itemLock(id).withLock {
                                    val uuid = UUID.fromString(id)
                                    if (owner.pending.get(id) == local && shouldPush(local, serverStates[uuid].toPlayState())) {
                                        val lastPlayedMs = local.lastPlayedMs
                                        val lastPlayedDate = if (lastPlayedMs == null) null else LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPlayedMs), ZoneId.systemDefault())
                                        owner.api.userDataApi.updateItemUserData(
                                            uuid, owner.userId,
                                            UpdateUserItemDataDto(
                                                playbackPositionTicks = local.positionTicks,
                                                played = local.played,
                                                lastPlayedDate = lastPlayedDate,
                                            ),
                                        )
                                    }
                                }
                            }.onSuccess {
                                confirmed[id] = local
                            }.onFailure { e ->
                                val status = (e as? InvalidStatusException)?.status
                                when (status) {
                                    404 -> confirmed[id] = local
                                    401 -> {
                                        expireOwner()
                                        abortError.compareAndSet(null, e)
                                        aborted.set(true)
                                    }
                                    else -> {
                                        firstFailure.compareAndSet(null, e)
                                        if (unreachable(e)) {
                                            abortError.compareAndSet(null, e)
                                            aborted.set(true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            if (confirmed.isNotEmpty()) {
                owner.pending.confirmAll(confirmed)
                localChanges++
            }
        }
        abortError.get() ?: firstFailure.get()
    }

    private val flushing = Mutex()

    private companion object {
        const val CLOCK_NOISE_MS = 2_000L
    }
}

private val systemZone: ZoneId = ZoneId.systemDefault()

// The SDK's LocalDateTime is in the device's time zone, not UTC. Reading it as UTC would shift every date
// by the zone offset and break the newer-wins rule.
fun UserItemDataDto?.toPlayState(): PlayState? {
    if (this == null) return null
    val date = lastPlayedDate
    val lastPlayedMs = if (date == null) null else date.atZone(systemZone).toInstant().toEpochMilli()
    return PlayState(playbackPositionTicks, played, lastPlayedMs)
}

/** The server state with any unsynced local state on top. The Boolean is true when the local state differs from the server's. */
fun BaseItemDto.localState(): Pair<PlayState?, Boolean> {
    App.server.localChanges // Reading this makes a composable caller redraw on the next local change.
    val local = if (App.pendingLoadedEmpty()) null else App.pending.get(id.toString())
    val state = local ?: userData.toPlayState()
    return Pair(state, local != null)
}

fun subtitleExt(stream: MediaStream) = when (val c = stream.codec?.lowercase()) {
    "subrip", null -> "srt"
    "webvtt" -> "vtt"
    else -> c
}

/** No HTTP answer: a timeout, unknown host, refused connection or failed TLS. */
fun unreachable(e: Throwable) = e is TimeoutException || generateSequence(e) { it.cause }.any { it is IOException }

/** Worth retrying later: there was no answer, or the server had an internal error. */
fun transient(e: Throwable) = unreachable(e) || (e is InvalidStatusException && e.status >= 500)
