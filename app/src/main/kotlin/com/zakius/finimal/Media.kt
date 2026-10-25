@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zakius.finimal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.work.BackoffPolicy
import kotlin.concurrent.thread
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import finimal.core.CacheEntry
import finimal.core.PlayState
import finimal.core.bufferLimit
import finimal.core.EpisodeRef
import finimal.core.evictionOrder
import finimal.core.keepNextDeletions
import finimal.core.keepNextWindow
import finimal.core.writeAtomically

/**
 * One persistent cache, keyed by item ID, serves streaming, buffering and downloads. A download is
 * a cache fill with a metadata file under `downloads/`; that file is what "pinned" means.
 */
object Media {
    @Volatile lateinit var cache: SimpleCache
        private set
    private lateinit var ctx: Context
    private lateinit var db: StandaloneDatabaseProvider
    /** Shared by the SDK, Coil and the player, so they reuse connections to the server. */
    val http by lazy { OkHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Cache key: item plus file version, so a file replaced on the server never mixes with bytes
     * cached from the old one.
     */
    fun key(item: BaseItemDto): String {
        val source = item.mediaSources?.firstOrNull()
        return "${item.id}-${source?.eTag ?: source?.size ?: 0}"
    }

    /** Cache key of what the player has open; eviction never touches it. */
    @Volatile var playing: String? = null

    /** Tags every buffer fill, so [switchStorage] can stop them all. */
    private const val FILL_TAG = "fill"

    /**
     * The selected SD card was missing at app start. The cache then opens on internal storage for
     * streaming only: nothing downloads or buffers into it, so nothing downloads twice or is left
     * behind when the card comes back. Checked again at the next start.
     */
    @Volatile var storageMissing = false
        private set

    fun onAppStart(context: Context) {
        ctx = context
        db = StandaloneDatabaseProvider(context)
        storageMissing = App.prefs.storageIndex >= storageRoots().size
        cache = openCache()
        val wm = WorkManager.getInstance(context)
        // The player that wanted them is gone with the old process.
        wm.cancelAllWorkByTag(FILL_TAG)
        // Replaced unless running, so a queue left waiting out a retry backoff starts at once.
        // Off the main thread: the state is a database read, and this runs before the first frame.
        thread(name = "kick-downloads") { kickDownloads(restart = wm.getWorkInfosForUniqueWork("downloads").get().none { it.state == WorkInfo.State.RUNNING }) }
        Housekeeping.enqueue(context)
    }

    /** App-specific storage; index 1+ are removable volumes. Read once per process: a card is checked again at the next start. */
    private val roots by lazy { ctx.getExternalFilesDirs(null).filterNotNull().ifEmpty { listOf(ctx.filesDir) } }
    fun storageRoots(): List<File> = roots
    private fun storageRoot() = storageRoots().getOrElse(App.prefs.storageIndex) { storageRoots()[0] }
    private fun cacheDir() = File(storageRoot(), "media")
    // Each cache folder has its own UID, and the shared database keeps one table per UID.
    private fun openCache() = SimpleCache(cacheDir(), NoOpCacheEvictor(), db)

    /**
     * Moves storage to root [index] at once. Bytes are not copied: every download and the buffer
     * cache are deleted, so nothing is orphaned on the old root or downloaded again unasked.
     * Deletes many files: call off the main thread.
     */
    @Synchronized
    fun switchStorage(index: Int) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("downloads")
        wm.cancelAllWorkByTag(FILL_TAG)
        downloads().forEach { val id = it.item.id.toString(); metaFile(id).delete(); subsDir(id).deleteRecursively() }
        memo = null
        changes++
        val old = cache
        val oldDir = cacheDir()
        App.prefs.storageIndex = index
        storageMissing = false
        // ponytail: a cancelled worker still mid-write fails on the released cache rather than being awaited.
        old.release()
        SimpleCache.delete(oldDir, db)
        cache = openCache()
    }

    /** Plain HTTP plus file:// for downloaded subtitles. */
    fun upstreamFactory() = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http))

    /** The server holds an HLS segment request open until ffmpeg has written it, which can pass OkHttp's 10 s default. */
    fun transcodeFactory() = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()))

    /** For [FillWorker], the only cache writer. Waits for its own earlier locks instead of skipping ranges. */
    fun writerFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory())
            // 1 MB writes instead of the default 20 KB: far fewer syscalls on SD cards and FUSE.
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setBufferSize(1 shl 20))
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

    /**
     * For the player: reads the cache, never writes it. A writing reader locks the whole uncached
     * rest of the file while its stream is open, which starved the buffer worker until playback
     * caught up. Read-only, it streams uncached ranges directly and rechecks the cache every 100 KB.
     */
    fun playerFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory())
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // --- progress ------------------------------------------------------------------------------

    fun contentLength(key: String) = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    fun cachedBytes(key: String) = cache.getCachedSpans(key).sumOf { it.length }
    /** How far, in ms, the disk cache reaches without a gap from [positionMs]; bytes map to time linearly. */
    fun cachedUntilMs(key: String, positionMs: Long, durationMs: Long): Long {
        val len = contentLength(key)
        if (len <= 0 || durationMs <= 0) return 0
        val start = len * positionMs / durationMs
        val run = cache.getCachedLength(key, start, len - start).coerceAtLeast(0)
        return (start + run) * durationMs / len
    }

    fun isComplete(key: String): Boolean {
        val len = contentLength(key)
        return len > 0 && cache.isCached(key, 0, len)
    }

    // --- downloads -------------------------------------------------------------------------------

    /**
     * A pinned item. Downloads run one at a time, oldest [queuedAt] first; playing an unfinished
     * download moves it to the front.
     */
    @Serializable
    data class Download(
        val item: BaseItemDto,
        val auto: Boolean,
        val queuedAt: Long = 0,
        val paused: Boolean = false,
        /** Byte to fill from first, set while the episode plays so the player's part lands on disk first. */
        val startAt: Long = 0,
        /** The server refused the file (HTTP 4xx, e.g. deleted there); paused with it. Resuming tries again. */
        val failed: Boolean = false,
        /** The disk was full even after evicting the buffer; paused with it. Resuming tries again. */
        val storageFull: Boolean = false,
    )

    private val downloadsDir get() = File(ctx.filesDir, "downloads").apply { mkdirs() }
    private fun metaFile(id: String) = File(downloadsDir, "$id.json")
    private fun write(d: Download) {
        metaFile(d.item.id.toString()).writeAtomically(json.encodeToString(d))
        // Patched rather than cleared: progress reports write every 10 s, and a re-read decodes every file.
        memo = memo?.filterNot { it.item.id == d.item.id }?.plus(d)
        changes++
    }

    /** Bumped on every download change, so open screens re-read statuses at once rather than at the next poll. */
    var changes by mutableIntStateOf(0)
        private set
    fun subsDir(id: String) = File(ctx.filesDir, "subs/$id")

    /** Decoded files, kept until the next change; the status poll would otherwise re-read every file each second. */
    private var memo: List<Download>? = null

    /** All writes go through [write], [deleteDownload] and [switchStorage], which clear [memo]. */
    @Synchronized
    fun downloads(): List<Download> = memo ?: downloadsDir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f ->
            runCatching { json.decodeFromString<Download>(f.readText()) }
                // Undecodable (e.g. SDK schema drift): kept aside, still pinning its bytes through [pinnedIds].
                .onFailure { if (it is IllegalArgumentException) f.renameTo(File(f.path + ".corrupt")) }
                .getOrNull()
        }
        .also { memo = it }

    /**
     * IDs with a meta file, decodable or not, so an unreadable record never turns a download into
     * evictable buffer. A cache key starts with its item ID.
     */
    private fun pinnedIds() = downloadsDir.list().orEmpty().map { it.substringBefore('.') }.toSet()
    private fun isPinned(key: String, ids: Set<String>) = ids.any { key.startsWith("$it-") }

    fun download(id: String): Download? = downloads().firstOrNull { it.item.id.toString() == id }

    /** The ID the queue worker is writing now. */
    @Volatile var downloading: String? = null
        set(value) { field = value; changes++ }

    val queueOrder = compareBy<Download>({ it.queuedAt }, { it.item.parentIndexNumber }, { it.item.indexNumber })
    /** Finished downloads: grouped by series in season order, movies last. */
    val shelfOrder = compareBy<Download>({ it.item.seriesId == null }, { it.item.seriesName }, { it.item.seriesId },
        { it.item.parentIndexNumber }, { it.item.indexNumber }, { it.item.name })

    /** Next unfinished, unpaused download in queue order. */
    fun nextDownload(): Download? =
        downloads().filter { !it.paused && !isComplete(key(it.item)) }.minWithOrNull(queueOrder)

    /** Why the queue can't run on the current network, or null when it can. Mirrors [kickDownloads]' constraint. */
    fun downloadsWaitingFor(): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return when {
            storageMissing -> "waiting for SD card"
            cm.activeNetwork == null -> "waiting for network"
            !App.prefs.downloadOnMetered && cm.isActiveNetworkMetered -> "waiting for unmetered network"
            else -> null
        }
    }

    /**
     * Starts the queue worker. With [restart] a running worker is stopped first, so it picks again
     * (after pause, delete, reprioritise or a network-setting change); its partial file is kept.
     */
    fun kickDownloads(restart: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints(requiredNetworkType = if (App.prefs.downloadOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("downloads", if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** [item] must carry media sources ([Server.item], or episodes fetched with sources) for offline playback. Also resumes a paused download. */
    @Synchronized
    fun startDownload(item: BaseItemDto, auto: Boolean) {
        val id = item.id.toString()
        val existing = download(id)
        if (existing != null && isComplete(key(existing.item))) return
        // An unfinished download takes the fresh item, so a file changed on the server restarts cleanly.
        existing?.let { if (key(it.item) != key(item)) cache.removeResource(key(it.item)) }
        WorkManager.getInstance(ctx).cancelUniqueWork("fill-$id") // the queue takes over from a buffer fill
        write(Download(item, existing?.auto ?: auto, existing?.queuedAt ?: System.currentTimeMillis(), paused = false))
        kickDownloads(restart = false)
    }

    @Synchronized
    fun pauseDownload(id: String) {
        val d = download(id) ?: return
        write(d.copy(paused = true))
        if (downloading == id) kickDownloads(restart = true)
    }

    /**
     * Playing an unfinished download moves it to the front, unless the user paused it, and makes the
     * worker fill from [fromByte] first. Nothing happens when the worker is already on it and that
     * position is cached, so rebuffering doesn't restart the worker.
     */
    /** Takes [id] out of the queue without restarting the worker that calls this. */
    @Synchronized
    fun failDownload(id: String, storageFull: Boolean = false) {
        download(id)?.let { write(it.copy(paused = true, failed = !storageFull, storageFull = storageFull)) }
    }

    @Synchronized
    fun prioritize(id: String, fromByte: Long) {
        val d = download(id) ?: return
        val key = key(d.item)
        if (d.paused || isComplete(key)) return
        if (downloading == id && cache.getCachedLength(key, fromByte, Long.MAX_VALUE) > 0) return
        write(d.copy(queuedAt = Long.MIN_VALUE, startAt = fromByte))
        kickDownloads(restart = true)
    }

    @Synchronized
    fun clearStartAt(id: String) {
        download(id)?.let { write(it.copy(startAt = 0)) }
    }

    /** The bytes go outside the lock: a whole episode is hundreds of fragments, and the player waits on these locks. */
    fun deleteDownload(id: String) {
        val key = synchronized(this) {
            val key = download(id)?.let { key(it.item) }
            metaFile(id).delete()
            memo = memo?.filterNot { it.item.id.toString() == id }
            changes++
            if (downloading == id) kickDownloads(restart = true)
            key
        }
        subsDir(id).deleteRecursively()
        if (key != null && playing != key) removeBytes(key)
    }

    /** Span by span, so SimpleCache's lock is free between files for the player and the workers. */
    private fun removeBytes(key: String) = cache.getCachedSpans(key).forEach(cache::removeSpan)

    /**
     * "Keep next N" turned off for [seriesId]: its finished, unplayed downloads become regular
     * downloads; the played and unfinished ones are deleted.
     */
    @Synchronized
    fun releaseKeepNext(seriesId: String) {
        for (d in downloads().filter { it.auto && it.item.seriesId?.toString() == seriesId }) {
            if (d.item.userData?.played != true && isComplete(key(d.item))) write(d.copy(auto = false))
            else deleteDownload(d.item.id.toString())
        }
    }

    /** Keeps a download's stored play state current, so offline playback resumes where the user left off. */
    @Synchronized
    fun rememberState(id: String, state: PlayState?) {
        val d = download(id) ?: return
        if (state == null) return
        val data = d.item.userData?.copy(
            playbackPositionTicks = state.positionTicks,
            played = state.played,
            lastPlayedDate = state.lastPlayedMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) },
        ) ?: return
        if (data == d.item.userData) return
        // A convenience copy; the pending store holds the state that syncs. A full disk mustn't crash playback.
        try { write(d.copy(item = d.item.copy(userData = data))) } catch (e: IOException) {}
    }

    enum class Fill { DONE, RUNNING, QUEUED, PAUSED }
    /** [position] is 1 for the next queued item; [waitingFor] is [downloadsWaitingFor]. */
    data class DownloadStatus(val download: Download, val percent: Int, val fill: Fill, val bytes: Long, val position: Int, val waitingFor: String?) {
        val label get() = when (fill) {
            Fill.DONE -> "downloaded"
            Fill.RUNNING -> "${waitingFor ?: "downloading"} · $percent%"
            Fill.QUEUED -> "${waitingFor ?: "queued #$position"} · $percent%"
            Fill.PAUSED -> "${if (download.failed) "failed" else if (download.storageFull) "storage full" else "paused"} · $percent%"
        }
    }

    /** Status of every download from the cache and the queue. Reads files: call off the main thread. */
    fun downloadStatuses(): Map<String, DownloadStatus> {
        val current = downloading
        val all = downloads()
        if (all.isEmpty()) return emptyMap()
        val waitingFor = downloadsWaitingFor()
        val queue = all.filter { !it.paused && it.item.id.toString() != current && !isComplete(key(it.item)) }
            .sortedWith(queueOrder).map { it.item.id.toString() }
        return all.associate { d ->
            val id = d.item.id.toString()
            val key = key(d.item)
            val len = contentLength(key)
            val bytes = cachedBytes(key)
            val fill = when {
                len > 0 && cache.isCached(key, 0, len) -> Fill.DONE
                d.paused -> Fill.PAUSED
                current == id -> Fill.RUNNING
                else -> Fill.QUEUED
            }
            id to DownloadStatus(d, if (len > 0) (bytes * 100 / len).toInt() else 0, fill, bytes, queue.indexOf(id) + 1, waitingFor)
        }
    }

    // --- buffering -------------------------------------------------------------------------------

    /** Fills the cache from [position] to the end while the player is open. */
    fun startBuffer(item: BaseItemDto, position: Long) {
        val id = item.id.toString()
        if (download(id) == null) enqueueFill(item, position) else prioritize(id, position)
    }

    fun stopBuffer(id: String) {
        if (download(id) == null) WorkManager.getInstance(ctx).cancelUniqueWork("fill-$id")
    }

    private fun enqueueFill(item: BaseItemDto, position: Long) {
        val source = item.mediaSources?.firstOrNull() ?: return
        val id = item.id.toString()
        val key = key(item)
        val request = OneTimeWorkRequestBuilder<FillWorker>()
            .addTag(FILL_TAG)
            // The URL carries the token; it is built when the work runs rather than stored in WorkManager's database.
            .setInputData(workDataOf("key" to key, "id" to id, "sourceId" to source.id!!, "size" to (source.size ?: 0L), "position" to position))
            .setConstraints(Constraints(requiredNetworkType = if (App.prefs.bufferOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .build()
        // A seek past the cached part restarts the fill there, as [prioritize] does for a download.
        val policy = if (cache.getCachedLength(key, position, Long.MAX_VALUE) > 0) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(ctx).enqueueUniqueWork("fill-$id", policy, request)
    }

    /** Bytes of unpinned (buffered) cache entries, the part [evict] limits. */
    fun bufferedBytes(): Long {
        val ids = pinnedIds()
        return cache.keys.filter { !isPinned(it, ids) }.sumOf(::cachedBytes)
    }

    fun freeBytes() = storageRoot().usableSpace

    fun evict() {
        val ids = pinnedIds()
        val entries = cache.keys.map { key ->
            val spans = cache.getCachedSpans(key)
            CacheEntry(key, spans.sumOf { it.length }, spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0, isPinned(key, ids))
        }
        val limit = bufferLimit(App.prefs.cacheLimitGb * 1_000_000_000L, entries.filter { !it.pinned }.sumOf { it.bytes },
            freeBytes(), storageRoot().totalSpace)
        evictionOrder(entries, limit, playing).forEach(::removeBytes)
    }

    /** After a failed write: true when the disk is still nearly full once the buffer is evicted. */
    fun storageFull(): Boolean {
        if (freeBytes() >= 100_000_000) return false
        evict()
        return freeBytes() < 100_000_000
    }

    /**
     * A captive portal or auth proxy answers 200 with its own page, which would be cached and count
     * as complete. When the server lists the file's [size], a cached length that differs removes the
     * bytes and returns true.
     */
    fun wrongFile(key: String, size: Long?): Boolean {
        val len = contentLength(key)
        return (size != null && size > 0 && len > 0 && len != size).also { if (it) cache.removeResource(key) }
    }

    /** Subtitles fetched for items that were only streamed; downloads and the playing item keep theirs. */
    fun dropStreamedSubtitles() {
        val keep = downloads().map { it.item.id.toString() }.toSet()
        val playing = playing
        File(ctx.filesDir, "subs").listFiles().orEmpty()
            .filter { it.name !in keep && playing?.startsWith(it.name) != true }
            .forEach { it.deleteRecursively() }
    }

    /** External subtitle streams are separate files; fetch them once so playback works offline too. */
    suspend fun fetchSubtitles(item: BaseItemDto): Map<Int, File> = coroutineScope {
        val id = item.id.toString()
        val source = item.mediaSources?.firstOrNull() ?: return@coroutineScope emptyMap()
        val dir = subsDir(id).apply { mkdirs() }
        externalSubs(item).map { stream -> async(Dispatchers.IO) {
            val file = File(dir, "${stream.index}.${subtitleExt(stream)}")
            if (!file.exists()) runCatching {
                http.newCall(Request.Builder().url(App.server.subtitleUrl(id, source.id!!, stream)).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    // Unique, so two fetchers of the same item never write one temp file.
                    val tmp = File.createTempFile(file.name, ".tmp", dir)
                    try {
                        tmp.outputStream().use { (r.body ?: throw IOException("empty body")).byteStream().copyTo(it) }
                        tmp.renameTo(file)
                    } finally { tmp.delete() }
                }
            }
            file.takeIf { it.exists() }?.let { stream.index to it }
        } }.awaitAll().filterNotNull().toMap()
    }
}

fun externalSubs(item: BaseItemDto): List<MediaStream> =
    item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE && it.isExternal }

/**
 * Writes [key] from [position] to the end into the cache; returns once it is all there. A range
 * can come back uncached (another writer held it), so it loops until the cache holds everything.
 * An interrupted run keeps every committed 5 MB fragment, and the next run skips them.
 */
private suspend fun fillCache(key: String, url: String, position: Long, onProgress: CacheWriter.ProgressListener?): Boolean {
    repeat(5) {
        val writer = CacheWriter(
            Media.writerFactory().createDataSource(),
            DataSpec.Builder().setUri(url).setPosition(position).setKey(key)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build(),
            null,
            onProgress,
        )
        runInterruptible(Dispatchers.IO) { writer.cache() }
        val len = Media.contentLength(key)
        if (len > 0 && Media.cache.isCached(key, position, len - position)) return true
    }
    return false
}

/** Buffer-to-end for the episode the player has open. */
class FillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (Media.storageMissing) return Result.success()
        val key = inputData.getString("key")!!
        val url = App.server.streamUrl(inputData.getString("id")!!, inputData.getString("sourceId")!!)
        val done = fillCache(key, url, inputData.getLong("position", 0), null)
        Media.evict()
        when {
            !done -> Result.retry()
            Media.wrongFile(key, inputData.getLong("size", 0)) -> Result.failure()
            else -> Result.success()
        }
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        // A refused file stays refused; the player shows its own error.
        if (e.responseCode < 500) Result.failure() else Result.retry()
    } catch (e: IOException) {
        if (Media.storageFull()) Result.failure() else Result.retry()
    }
}

/** Works through the download queue one episode at a time. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Media.storageMissing) return Result.success() // the next app start kicks the queue again
        var current: String? = null
        try {
            while (true) {
                var d = Media.nextDownload() ?: return Result.success()
                val id = d.item.id.toString()
                current = id
                Media.downloading = id
                // A file replaced on the server since queuing starts over under its new key.
                val fresh = try { App.server.item(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (fresh != null && Media.key(fresh) != Media.key(d.item)) {
                    Media.startDownload(fresh, d.auto)
                    d = Media.download(id) ?: continue
                }
                val source = d.item.mediaSources?.firstOrNull()
                if (source == null) { Media.failDownload(id); continue }
                runCatching { setForeground(foregroundInfo(d, null)) }
                withContext(Dispatchers.IO) { Media.fetchSubtitles(d.item) }
                var lastUpdate = 0L
                var lastPercent = -1
                val progress = CacheWriter.ProgressListener { length, cached, _ ->
                    val now = System.currentTimeMillis()
                    val percent = if (length > 0) (cached * 100 / length).toInt() else -1
                    // Only a new percentage: each update makes SystemUI rebuild the notification, for hours.
                    if (percent >= 0 && percent != lastPercent && now - lastUpdate > 1_000) {
                        lastUpdate = now
                        lastPercent = percent
                        setForegroundAsync(foregroundInfo(d, percent))
                    }
                }
                val key = Media.key(d.item)
                val url = App.server.streamUrl(id, source.id!!)
                try {
                    // From the playhead first while the episode plays, then the rest (cached parts are skipped).
                    if (d.startAt > 0) {
                        if (!fillCache(key, url, d.startAt, progress)) return Result.retry()
                        Media.clearStartAt(id)
                    }
                    if (!fillCache(key, url, 0, progress)) return Result.retry()
                    if (Media.wrongFile(key, source.size)) Media.failDownload(id)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // Refused for this item only; retrying would pick it again forever. A 401 is the token: retry.
                    if (e.responseCode !in 400..499 || e.responseCode == 401) throw e
                    Media.failDownload(id)
                }
            }
        } catch (e: IOException) {
            if (current == null || !Media.storageFull()) return Result.retry()
            // The rest of the queue would fail the same way; the next app start or Resume tries again.
            Media.failDownload(current, storageFull = true)
            return Result.success()
        } finally {
            // A replaced worker can finish after its successor started; don't clear the successor's ID.
            if (Media.downloading == current) Media.downloading = null
        }
    }

    /** The notification's tap target; the first use also creates the channel, so neither repeats per update. */
    private val openDownloads by lazy {
        if (Build.VERSION.SDK_INT >= 26) applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun foregroundInfo(d: Media.Download, percent: Int?): ForegroundInfo {
        val item = d.item
        val name = if (item.type == BaseItemKind.EPISODE) "${item.seriesName} · ${episodeLabel(item)}" else item.name.orEmpty()
        val n = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloads)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private companion object { const val NOTIFICATION_ID = 1 }
}

/** Pushes unsynced progress, applies "keep next N" and trims the cache. Runs whenever a network is up. */
class Housekeeping(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val server = App.server
        if (!server.loggedIn) return Result.success()
        val unsynced = server.flush()
        if (!server.loggedIn) return Result.success() // the token was rejected; logging in pushes again
        if (unsynced != null && transient(unsynced)) return Result.retry()
        // Progress made on other devices reaches offline playback through the stored downloads.
        runCatching {
            val ids = Media.downloads().map { it.item.id }
            // An item still pending has newer local state than the server; the push that failed will retry.
            if (ids.isNotEmpty()) server.userData(ids).forEach { (id, data) ->
                if (App.pending.get(id.toString()) == null) Media.rememberState(id.toString(), data.toPlayState())
            }
        }
        var retry = false
        for ((seriesId, n) in App.prefs.keepNextSeries()) runCatching {
            val episodes = server.episodes(seriesId).filter { it.parentIndexNumber != 0 }
            val refs = episodes.map { EpisodeRef(it.id.toString(), it.localState().first) }
            val window = keepNextWindow(refs, n)
            val have = Media.downloads().associateBy { it.item.id.toString() }
            window.filter { it !in have }.forEach { Media.startDownload(server.item(it), auto = true) }
            val auto = have.filterValues { it.auto }.keys
            keepNextDeletions(auto, refs, window).forEach(Media::deleteDownload)
        }.onFailure { if (transient(it)) retry = true } // a series gone from the server would fail every retry
        Media.evict()
        Media.dropStreamedSubtitles()
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            "housekeeping",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Housekeeping>().setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build(),
        )
    }
}
