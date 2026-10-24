@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zakius.plainfin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
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

    @Serializable data class Download(val item: BaseItemDto, val auto: Boolean)

    private val downloadsDir get() = File(ctx.filesDir, "downloads").apply { mkdirs() }
    private fun metaFile(id: String) = File(downloadsDir, "$id.json")
    fun subsDir(id: String) = File(ctx.filesDir, "subs/$id")

    fun downloads(): List<Download> =
        downloadsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Download>(it.readText()) }.getOrNull() }

    fun download(id: String): Download? =
        metaFile(id).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<Download>(it.readText()) }.getOrNull() }

    /** [item] must come from [Server.item] so it carries media sources for offline playback. */
    fun startDownload(item: BaseItemDto, auto: Boolean) {
        val id = item.id.toString()
        val existing = download(id)
        if (existing != null && isComplete(key(existing.item))) return
        // An unfinished download takes the fresh item, so a file changed on the server restarts cleanly.
        existing?.let { if (key(it.item) != key(item)) cache.removeResource(key(it.item)) }
        metaFile(id).writeText(json.encodeToString(Download(item, existing?.auto ?: auto)))
        enqueueFill(item, 0, pinned = true)
    }

    fun deleteDownload(id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork("fill-$id")
        val key = download(id)?.let { key(it.item) }
        metaFile(id).delete()
        subsDir(id).deleteRecursively()
        if (key != null && playing != key) cache.removeResource(key)
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
        metaFile(id).writeText(json.encodeToString(d.copy(item = d.item.copy(userData = data))))
    }

    fun pauseDownload(id: String) = WorkManager.getInstance(ctx).cancelUniqueWork("fill-$id")

    enum class Fill { DONE, RUNNING, QUEUED, PAUSED }
    data class DownloadStatus(val percent: Int, val fill: Fill, val bytes: Long) {
        val label get() = when (fill) {
            Fill.DONE -> "downloaded"
            Fill.RUNNING -> "downloading $percent%"
            Fill.QUEUED -> "queued $percent%" + if (App.prefs.downloadOnMetered) "" else " · Wi-Fi only"
            Fill.PAUSED -> "paused $percent%"
        }
    }

    /** Status of every download, from the cache and WorkManager in one query. Blocking: call off the main thread. */
    fun downloadStatuses(): Map<String, DownloadStatus> {
        val active = WorkManager.getInstance(ctx)
            .getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).get()
            .flatMap { info -> info.tags.filter { it.startsWith("fill-") }.map { it.removePrefix("fill-") to info.state } }
            .toMap()
        return downloads().associate { d ->
            val id = d.item.id.toString()
            val key = key(d.item)
            val len = contentLength(key)
            val bytes = cachedBytes(key)
            val fill = when {
                len > 0 && cache.isCached(key, 0, len) -> Fill.DONE
                active[id] == WorkInfo.State.RUNNING -> Fill.RUNNING
                active[id] != null -> Fill.QUEUED
                else -> Fill.PAUSED
            }
            id to DownloadStatus(if (len > 0) (bytes * 100 / len).toInt() else 0, fill, bytes)
        }
    }

    // --- buffering -------------------------------------------------------------------------------

    /** Fills the cache from [position] to the end while the player is open. */
    fun startBuffer(item: BaseItemDto, position: Long) {
        if (download(item.id.toString()) == null) enqueueFill(item, position, pinned = false)
    }

    fun stopBuffer(id: String) {
        if (download(id) == null) WorkManager.getInstance(ctx).cancelUniqueWork("fill-$id")
    }

    private fun enqueueFill(item: BaseItemDto, position: Long, pinned: Boolean) {
        val source = item.mediaSources?.firstOrNull() ?: return
        val id = item.id.toString()
        val metered = if (pinned) App.prefs.downloadOnMetered else App.prefs.bufferOnMetered
        val request = OneTimeWorkRequestBuilder<FillWorker>()
            .setInputData(workDataOf("id" to id, "key" to key(item), "url" to App.server.streamUrl(id, source.id!!), "position" to position, "pinned" to pinned))
            .setConstraints(Constraints(requiredNetworkType = if (metered) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .addTag("fill-$id")
            .build()
        // A download replaces a running buffer fill; a buffer fill never replaces a download.
        WorkManager.getInstance(ctx).enqueueUniqueWork("fill-$id", if (pinned) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Re-enqueues unfinished downloads, e.g. after the metered setting changed. */
    fun requeueDownloads() = downloads().filter { !isComplete(key(it.item)) }.forEach { enqueueFill(it.item, 0, pinned = true) }

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

class FillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id")!!
        val key = inputData.getString("key")!!
        val url = inputData.getString("url")!!
        val pinned = inputData.getBoolean("pinned", false)
        var position = inputData.getLong("position", 0)
        if (pinned) {
            runCatching { setForeground(foregroundInfo(id, null)) }
            Media.download(id)?.let { withContext(Dispatchers.IO) { Media.fetchSubtitles(it.item) } }
        }
        var lastUpdate = 0L
        val progress = CacheWriter.ProgressListener { length, cached, _ ->
            val now = System.currentTimeMillis()
            if (pinned && length > 0 && now - lastUpdate > 1_000) {
                lastUpdate = now
                setForegroundAsync(foregroundInfo(id, (cached * 100 / length).toInt()))
            }
        }
        return try {
            // A range can still come back uncached (e.g. a download replaced a buffer fill mid-write),
            // so loop until the cache really holds everything from the start position.
            repeat(5) {
                val writer = CacheWriter(
                    Media.writerFactory().createDataSource(),
                    // Fragmentation commits every 5 MB: progress becomes visible, the player can read
                    // what's already buffered, and a killed process loses at most one fragment.
                    DataSpec.Builder().setUri(url).setPosition(position).setKey(key)
                        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build(),
                    null,
                    progress,
                )
                runInterruptible(Dispatchers.IO) { writer.cache() }
                val len = Media.contentLength(key)
                if (len > 0 && Media.cache.isCached(key, position, len - position)) {
                    Media.evict()
                    return Result.success()
                }
            }
            Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    private fun foregroundInfo(id: String, percent: Int?): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val item = Media.download(id)?.item
        val name = item?.let { if (it.type == BaseItemKind.EPISODE) "${it.seriesName} · ${episodeLabel(it)}" else it.name } ?: "Downloading"
        val n = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), n)
    }
}

/** Pushes unsynced progress, applies "keep next N" and trims the cache. Runs whenever a network is up. */
class Housekeeping(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val server = App.server
        if (!server.loggedIn) return Result.success()
        if (!server.flush()) return Result.retry()
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
