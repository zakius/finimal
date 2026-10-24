@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zakius.plainfin

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
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
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
import plainfin.core.CacheEntry
import plainfin.core.PlayState
import plainfin.core.EpisodeRef
import plainfin.core.evictionOrder
import plainfin.core.keepNextDeletions
import plainfin.core.keepNextWindow

/**
 * One persistent cache, keyed by item ID, serves streaming, buffering and downloads. A download is
 * a cache fill with a metadata file under `downloads/`; that file is what "pinned" means.
 */
object Media {
    lateinit var cache: SimpleCache
        private set
    private lateinit var ctx: Context
    val http = OkHttpClient()
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

    fun onAppStart(context: Context) {
        ctx = context
        cache = SimpleCache(File(storageRoot(), "media"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        // Builds before the queue ran one job per download; those would run alongside it.
        downloads().forEach { WorkManager.getInstance(context).cancelUniqueWork("fill-${it.item.id}") }
        kickDownloads(restart = false)
        Housekeeping.enqueue(context)
    }

    /** App-specific storage; index 1+ are removable volumes. Takes effect on the next start. */
    fun storageRoots(): List<File> = ctx.getExternalFilesDirs(null).filterNotNull().ifEmpty { listOf(ctx.filesDir) }
    private fun storageRoot() = storageRoots().getOrElse(App.prefs.storageIndex) { storageRoots()[0] }

    /** Plain HTTP plus file:// for downloaded subtitles. */
    fun upstreamFactory() = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http))

    /** For [FillWorker], the only cache writer. Waits for its own earlier locks instead of skipping ranges. */
    fun writerFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory())
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
    )

    private val downloadsDir get() = File(ctx.filesDir, "downloads").apply { mkdirs() }
    private fun metaFile(id: String) = File(downloadsDir, "$id.json")
    private fun write(d: Download) {
        metaFile(d.item.id.toString()).writeText(json.encodeToString(d))
        changes++
    }

    /** Bumped on every download change, so open screens re-read statuses at once rather than at the next poll. */
    var changes by mutableIntStateOf(0)
        private set
    fun subsDir(id: String) = File(ctx.filesDir, "subs/$id")

    fun downloads(): List<Download> =
        downloadsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Download>(it.readText()) }.getOrNull() }

    fun download(id: String): Download? =
        metaFile(id).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<Download>(it.readText()) }.getOrNull() }

    /** The ID the queue worker is writing now. */
    @Volatile var downloading: String? = null

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
            .setConstraints(Constraints(requiredNetworkType = if (App.prefs.downloadOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("downloads", if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** [item] must come from [Server.item] so it carries media sources for offline playback. Also resumes a paused download. */
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

    @Synchronized
    fun deleteDownload(id: String) {
        val key = download(id)?.let { key(it.item) }
        metaFile(id).delete()
        changes++
        if (downloading == id) kickDownloads(restart = true)
        subsDir(id).deleteRecursively()
        if (key != null && playing != key) cache.removeResource(key)
    }

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
        write(d.copy(item = d.item.copy(userData = data)))
    }

    enum class Fill { DONE, RUNNING, QUEUED, PAUSED }
    /** [position] is 1 for the next queued item; [waitingFor] is [downloadsWaitingFor]. */
    data class DownloadStatus(val download: Download, val percent: Int, val fill: Fill, val bytes: Long, val position: Int = 0, val waitingFor: String? = null) {
        val label get() = when (fill) {
            Fill.DONE -> "downloaded"
            Fill.RUNNING -> "${waitingFor ?: "downloading"} $percent%"
            Fill.QUEUED -> "${waitingFor ?: "queued #$position"} · $percent%"
            Fill.PAUSED -> "paused $percent%"
        }
    }

    /** Status of every download from the cache and the queue. Reads files: call off the main thread. */
    fun downloadStatuses(): Map<String, DownloadStatus> {
        val current = downloading
        val waitingFor = downloadsWaitingFor()
        val all = downloads()
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
        val request = OneTimeWorkRequestBuilder<FillWorker>()
            .setInputData(workDataOf("key" to key(item), "url" to App.server.streamUrl(id, source.id!!), "position" to position))
            .setConstraints(Constraints(requiredNetworkType = if (App.prefs.bufferOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("fill-$id", ExistingWorkPolicy.KEEP, request)
    }

    /** Bytes of unpinned (buffered) cache entries, the part [evict] limits. */
    fun bufferedBytes(): Long {
        val pinned = downloads().map { key(it.item) }.toSet()
        return cache.keys.filter { it !in pinned }.sumOf(::cachedBytes)
    }

    fun freeBytes() = storageRoot().usableSpace

    fun evict() {
        val limit = App.prefs.cacheLimitGb * 1_000_000_000L
        val pinned = downloads().map { key(it.item) }.toSet()
        val entries = cache.keys.map { key ->
            val spans = cache.getCachedSpans(key)
            CacheEntry(key, spans.sumOf { it.length }, spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0, key in pinned)
        }
        evictionOrder(entries, limit, playing).forEach(cache::removeResource)
    }

    /** External subtitle streams are separate files; fetch them once so playback works offline too. */
    fun fetchSubtitles(item: BaseItemDto): Map<Int, File> {
        val id = item.id.toString()
        val source = item.mediaSources?.firstOrNull() ?: return emptyMap()
        val dir = subsDir(id).apply { mkdirs() }
        return externalSubs(item).mapNotNull { stream ->
            val file = File(dir, "${stream.index}.${subtitleExt(stream)}")
            if (!file.exists()) runCatching {
                http.newCall(Request.Builder().url(App.server.subtitleUrl(id, source.id!!, stream)).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val tmp = File(dir, "${file.name}.tmp")
                    tmp.outputStream().use { (r.body ?: throw IOException("empty body")).byteStream().copyTo(it) }
                    tmp.renameTo(file)
                }
            }
            file.takeIf { it.exists() }?.let { stream.index to it }
        }.toMap()
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
        val done = fillCache(inputData.getString("key")!!, inputData.getString("url")!!, inputData.getLong("position", 0), null)
        Media.evict()
        if (done) Result.success() else Result.retry()
    } catch (e: IOException) {
        Result.retry()
    }
}

/** Works through the download queue one episode at a time. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var current: String? = null
        try {
            while (true) {
                val d = Media.nextDownload() ?: return Result.success()
                val id = d.item.id.toString()
                val source = d.item.mediaSources?.firstOrNull() ?: return Result.failure()
                current = id
                Media.downloading = id
                runCatching { setForeground(foregroundInfo(d, null)) }
                withContext(Dispatchers.IO) { Media.fetchSubtitles(d.item) }
                var lastUpdate = 0L
                val progress = CacheWriter.ProgressListener { length, cached, _ ->
                    val now = System.currentTimeMillis()
                    if (length > 0 && now - lastUpdate > 1_000) {
                        lastUpdate = now
                        setForegroundAsync(foregroundInfo(d, (cached * 100 / length).toInt()))
                    }
                }
                val key = Media.key(d.item)
                val url = App.server.streamUrl(id, source.id!!)
                // From the playhead first while the episode plays, then the rest (cached parts are skipped).
                if (d.startAt > 0) {
                    if (!fillCache(key, url, d.startAt, progress)) return Result.retry()
                    Media.clearStartAt(id)
                }
                if (!fillCache(key, url, 0, progress)) return Result.retry()
            }
        } catch (e: IOException) {
            return Result.retry()
        } finally {
            // A replaced worker can finish after its successor started; don't clear the successor's ID.
            if (Media.downloading == current) Media.downloading = null
        }
    }

    private fun foregroundInfo(d: Media.Download, percent: Int?): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val item = d.item
        val name = if (item.type == BaseItemKind.EPISODE) "${item.seriesName} · ${episodeLabel(item)}" else item.name.orEmpty()
        val n = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0,
                Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOWNLOADS, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
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
            if (ids.isNotEmpty()) server.userData(ids).forEach { (id, data) -> Media.rememberState(id.toString(), data.toPlayState()) }
        }
        runCatching {
            for ((seriesId, n) in App.prefs.keepNextSeries()) {
                val episodes = server.episodes(seriesId).filter { it.parentIndexNumber != 0 }
                val refs = episodes.map { EpisodeRef(it.id.toString(), it.localState().first) }
                val window = keepNextWindow(refs, n)
                val have = Media.downloads().associateBy { it.item.id.toString() }
                window.filter { it !in have }.forEach { Media.startDownload(server.item(it), auto = true) }
                val auto = have.filterValues { it.auto }.keys
                keepNextDeletions(auto, refs, window).forEach(Media::deleteDownload)
            }
        }.onFailure { return Result.retry() }
        Media.evict()
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            "housekeeping",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Housekeeping>().setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build(),
        )
    }
}
