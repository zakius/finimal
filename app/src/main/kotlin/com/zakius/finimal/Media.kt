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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
import finimal.core.HttpStatus
import finimal.core.Scratch
import finimal.core.ScratchServer
import finimal.core.WrongFile

/**
 * One [Scratch] on the chosen storage, keyed by file version, serves streaming, buffering and downloads; the player
 * reads it through [proxy]. A download is a whole-file fill with a metadata file under `downloads/`; that file is
 * what "pinned" means. A download the device can't play is the server's transcode instead, under `transcodes/`.
 */
object Media : Downloads {
    @Volatile lateinit var scratch: Scratch
        private set
    private lateinit var ctx: Context
    /** Shared by the SDK, Coil and the player, so they reuse connections to the server. */
    val http get() = App.http
    private val json = Json { ignoreUnknownKeys = true }

    fun key(item: BaseItemDto) = scratchKey(item)

    /**
     * A transcoded download: the server's HLS segments as `0.ts`, `1.ts`…, the server's playlist they
     * came from, and, written last, [PLAYLIST] pointing at the local files.
     */
    fun transcodeDir(id: String) = File(storageRoot(), "transcodes/$id")
    private const val PLAYLIST = "index.m3u8"
    private fun isSegment(line: String) = line.isNotBlank() && !line.startsWith("#")
    fun playlist(d: Download) = File(transcodeDir(d.item.id.toString()), PLAYLIST).takeIf { d.transcoded && it.exists() }
    fun isDone(d: Download) = if (d.transcoded) playlist(d) != null else scratch.entry(key(d.item)).complete

    /** Loopback HTTP over [scratch], for the player. */
    private val proxy by lazy { ScratchServer({ scratch }) }

    /** [item]'s entry, with the server's file as its source. */
    fun entry(item: BaseItemDto): Scratch.Entry {
        val source = item.mediaSources!!.first()
        return scratch.entry(key(item)).apply { source({ App.server.streamUrl(item.id.toString(), source.id!!) }, source.size) }
    }

    /** What the player opens for a direct play: the item's file through [scratch], so it reads what is on disk. */
    fun streamUrl(item: BaseItemDto): String = entry(item).let { proxy.url(key(item)) }

    /** Cache key of what the player has open; eviction never touches it. */
    @Volatile var playing: String? = null

    /** Buffer fills ran as work with this tag before 0.7; any left in WorkManager's database are cancelled. */
    private const val FILL_TAG = "fill"

    /**
     * The selected SD card was missing at app start. The scratch then opens on internal storage for
     * streaming only: nothing downloads or buffers to the end into it, so nothing downloads twice or
     * is left behind when the card comes back. Checked again at the next start.
     */
    @Volatile override var storageMissing = false
        private set

    fun onAppStart(context: Context) {
        ctx = context
        adoptLooseRecords()
        storageMissing = App.prefs.storageIndex >= storageRoots().size
        scratch = openScratch()
    }

    /** Off the main thread, after the first frame; see [App.afterFirstFrame]. */
    fun afterFirstFrame(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(FILL_TAG)
        // Media3's cache, which held downloads and the buffer before 0.7. Its downloads fetch again into the scratch.
        storageRoots().forEach { File(it, "media").deleteRecursively() }
        context.deleteDatabase("exoplayer_internal.db")
        // Replaced unless running, so a queue left waiting out a retry backoff starts at once.
        kickDownloads(restart = wm.getWorkInfosForUniqueWork("downloads").get().none { it.state == WorkInfo.State.RUNNING })
        Housekeeping.enqueue(context)
    }

    /** App-specific storage; index 1+ are removable volumes. Read once per process: a card is checked again at the next start. */
    private val roots by lazy { ctx.getExternalFilesDirs(null).filterNotNull().ifEmpty { listOf(ctx.filesDir) } }
    override fun storageRoots(): List<File> = roots
    private fun storageRoot() = storageRoots().getOrElse(App.prefs.storageIndex) { storageRoots()[0] }
    private fun openScratch() = Scratch(File(storageRoot(), "scratch"), http).apply {
        // Buffer first; a fill that still can't write then stops buffering and downloading.
        onStorageFull = { val before = freeBytes(); evict(); freeBytes() > before }
    }

    /**
     * Moves storage to root [index] at once. Bytes are not copied: every download and the buffer
     * cache are deleted, so nothing is orphaned on the old root or downloaded again unasked.
     * Deletes many files: call off the main thread.
     */
    @Synchronized
    override fun switchStorage(index: Int) {
        WorkManager.getInstance(ctx).cancelUniqueWork("downloads")
        stopBuffer(null)
        pinnedIds().forEach { subsDir(it).deleteRecursively() }
        recordsRoot.deleteRecursively()
        File(storageRoot(), "transcodes").deleteRecursively()
        memo = null
        changes++
        val old = scratch
        App.prefs.storageIndex = index
        storageMissing = false
        // A player still open on the old one fails its next read and reconnects to the new one.
        old.close()
        old.dir.deleteRecursively()
        scratch = openScratch()
    }

    /** Plain HTTP plus file:// for downloaded subtitles and transcoded downloads. */
    fun upstreamFactory() = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http))

    /**
     * For the player, through [proxy] or straight to the server for a transcode. 30 s reads: the proxy waits up to
     * 20 s for the filler, and the server holds an HLS segment request open until ffmpeg has written it.
     */
    fun playerFactory() = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()))

    /** The file's length once the server told it; -1 before. */
    fun lengthOf(key: String) = scratch.entry(key).length

    /** How far, in ms, the scratch reaches without a gap from [positionMs]; bytes map to time linearly. */
    fun cachedUntilMs(key: String, positionMs: Long, durationMs: Long): Long {
        val e = scratch.entry(key)
        val len = e.length
        if (len <= 0 || durationMs <= 0) return 0
        val start = len * positionMs / durationMs
        return (start + e.contiguous(start)) * durationMs / len
    }

    // --- downloads -------------------------------------------------------------------------------


    /** A folder of records per account: an account sees, queues and deletes only its own. */
    private val recordsRoot get() = File(ctx.filesDir, "downloads")
    private val downloadsDir get() = File(recordsRoot, App.prefs.userId ?: "none").apply { mkdirs() }
    private fun metaFile(id: String) = File(downloadsDir, "$id.json")
    private fun otherRecordDirs() = recordsRoot.listFiles().orEmpty().filter { it.isDirectory && it != downloadsDir }
    /** Another account has [id] downloaded, decodable or not: the subtitles stay. */
    private fun recordedElsewhere(id: String) = otherRecordDirs().any { it.list().orEmpty().any { n -> n.substringBefore('.') == id } }
    /** Another account's record needs [d]'s bytes: the same kind of file, direct or transcoded. Undecodable counts. */
    private fun ownedElsewhere(d: Download) = otherRecordDirs().any { dir ->
        val id = d.item.id.toString()
        val f = File(dir, "$id.json")
        if (f.exists()) runCatching { json.decodeFromString<Download>(f.readText()).transcoded == d.transcoded }.getOrDefault(true)
        else dir.list().orEmpty().any { it.substringBefore('.') == id }
    }

    /**
     * Records from before accounts lie loose in the folder; they belong to whoever is logged in, now or at the next
     * login. Until then [pinnedIds] counts them.
     */
    private fun adoptLooseRecords() {
        if (App.prefs.userId == null) return
        recordsRoot.listFiles { f -> f.isFile }?.forEach { it.renameTo(File(downloadsDir, it.name)) }
    }
    private fun write(d: Download) {
        metaFile(d.item.id.toString()).writeAtomically(json.encodeToString(d))
        // Patched rather than cleared: progress reports write every 10 s, and a re-read decodes every file.
        memo = memo?.filterNot { it.item.id == d.item.id }?.plus(d)
        changes++
    }

    /** Bumped on every download change, so open screens re-read statuses at once rather than at the next poll. */
    override var changes by mutableIntStateOf(0)
        private set
    fun subsDir(id: String) = File(ctx.filesDir, "subs/$id")

    /** Decoded files, kept until the next change; the status poll would otherwise re-read every file each second. */
    private var memo: List<Download>? = null

    /** All writes go through [write], [deleteDownload] and [switchStorage], which clear [memo]. */
    @Synchronized
    override fun downloads(): List<Download> = memo ?: downloadsDir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f ->
            runCatching { json.decodeFromString<Download>(f.readText()) }
                // Undecodable (e.g. SDK schema drift): kept aside, still pinning its bytes through [pinnedIds].
                .onFailure { if (it is IllegalArgumentException) f.renameTo(File(f.path + ".corrupt")) }
                .getOrNull()
        }
        .also { memo = it }

    /**
     * IDs with a meta file of any account, decodable or not, so an unreadable record never turns a download into
     * evictable buffer. A cache key starts with its item ID.
     */
    private fun pinnedIds() = recordsRoot.listFiles().orEmpty().flatMap { if (it.isDirectory) it.list().orEmpty().toList() else listOf(it.name) }
        .map { it.substringBefore('.') }.toSet()

    @Synchronized
    override fun accountChanged() {
        adoptLooseRecords()
        memo = null
        changes++
        kickDownloads(restart = true)
    }

    override fun otherAccountsBytes(): Long {
        val own = downloads().map { it.item.id.toString() }.toSet()
        val others = pinnedIds() - own
        return scratch.keys().filter { k -> others.any { k.startsWith("$it-") } }.sumOf { scratch.entry(it).bytes() } +
            others.sumOf { transcodeDir(it).walk().filter(File::isFile).sumOf(File::length) }
    }
    private fun isPinned(key: String, ids: Set<String>) = ids.any { key.startsWith("$it-") }

    override fun download(id: String): Download? = downloads().firstOrNull { it.item.id.toString() == id }

    /** The ID the queue worker is writing now. */
    @Volatile var downloading: String? = null
        set(value) { field = value; changes++ }


    /** Next unfinished, unpaused download in queue order. */
    fun nextDownload(): Download? =
        downloads().filter { !it.paused && !isDone(it) }.minWithOrNull(queueOrder)

    /** Why the queue can't run on the current network, or null when it can. Mirrors [kickDownloads]' constraint. */
    override fun downloadsWaitingFor(): String? {
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
    override fun kickDownloads(restart: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints(requiredNetworkType = if (App.prefs.downloadOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("downloads", if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** [item] must carry media sources ([Server.item], or episodes fetched with sources) for offline playback. Also resumes a paused download. */
    @Synchronized
    override fun startDownload(item: BaseItemDto, auto: Boolean) {
        val id = item.id.toString()
        val existing = download(id)
        if (existing != null && isDone(existing)) return
        // An unfinished download takes the fresh item, so a file changed on the server restarts cleanly.
        val same = existing != null && key(existing.item) == key(item)
        if (existing != null && !same && !ownedElsewhere(existing)) removeDownloadBytes(existing)
        write(Download(item, existing?.auto ?: auto, existing?.queuedAt ?: System.currentTimeMillis(), paused = false,
            transcoded = if (same) existing!!.transcoded else !playsDirectly(item)))
        kickDownloads(restart = false)
    }

    @Synchronized
    override fun pauseDownload(id: String) {
        val d = download(id) ?: return
        write(d.copy(paused = true))
        if (downloading == id) kickDownloads(restart = true)
    }

    /** Takes [id] out of the queue without restarting the worker that calls this. */
    @Synchronized
    fun failDownload(id: String, storageFull: Boolean = false) {
        download(id)?.let { write(it.copy(paused = true, failed = !storageFull, storageFull = storageFull)) }
    }

    /** The bytes go outside the lock: a whole episode is hundreds of files on an SD card. */
    override fun deleteDownload(id: String) {
        val d = synchronized(this) {
            val d = download(id)
            metaFile(id).delete()
            memo = memo?.filterNot { it.item.id.toString() == id }
            changes++
            if (downloading == id) kickDownloads(restart = true)
            d
        }
        if (!recordedElsewhere(id)) subsDir(id).deleteRecursively()
        if (d != null && playing != key(d.item) && !ownedElsewhere(d)) removeDownloadBytes(d)
    }

    /** A direct download open in the player stays, now as buffer, for eviction to take later. */
    private fun removeDownloadBytes(d: Download) {
        if (d.transcoded) transcodeDir(d.item.id.toString()).deleteRecursively() else scratch.delete(key(d.item))
    }

    /**
     * "Keep next N" turned off for [seriesId]: its finished, unplayed downloads become regular
     * downloads; the played and unfinished ones are deleted.
     */
    @Synchronized
    override fun releaseKeepNext(seriesId: String) {
        for (d in downloads().filter { it.auto && it.item.seriesId?.toString() == seriesId }) {
            if (d.item.userData?.played != true && isDone(d)) write(d.copy(auto = false))
            else deleteDownload(d.item.id.toString())
        }
    }

    /** Keeps a download's stored play state current, so offline playback resumes where the user left off. */
    @Synchronized
    override fun rememberState(id: String, state: PlayState?) {
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


    /** Bytes on disk, percent, and whether it is all there. A transcode counts segments: their sizes aren't known ahead. */
    private fun progress(d: Download): Triple<Long, Int, Boolean> {
        if (!d.transcoded) {
            val e = scratch.entry(key(d.item))
            val len = e.length.takeIf { it > 0 } ?: d.item.mediaSources?.firstOrNull()?.size ?: 0
            val bytes = e.bytes()
            return Triple(bytes, if (len > 0) (bytes * 100 / len).toInt() else 0, e.complete)
        }
        val dir = transcodeDir(d.item.id.toString())
        val files = dir.listFiles().orEmpty()
        val total = File(dir, "remote.m3u8").takeIf { it.exists() }?.readLines()?.count(::isSegment) ?: 0
        val done = files.count { it.name.endsWith(".ts") }
        return Triple(files.sumOf { it.length() }, if (total > 0) done * 100 / total else 0, File(dir, PLAYLIST).exists())
    }

    /**
     * Fetches the segments [d] lacks, in order, then writes [PLAYLIST]. The server's playlist is kept
     * from the first run, so every segment comes from the same encode settings; a run after an
     * interruption asks for the next segment, and the server starts its encoder there.
     */
    suspend fun downloadTranscode(d: Download, sourceId: String, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val id = d.item.id.toString()
        val dir = transcodeDir(id).apply { mkdirs() }
        val base = "${App.prefs.baseUrl}/Videos/$id/"
        val remote = File(dir, "remote.m3u8")
        if (!remote.exists()) {
            val seriesKey = d.item.seriesId?.toString() ?: id
            val source = d.item.mediaSources!!.first()
            val audio = App.prefs.audioLang(seriesKey)?.let { audioIndex(source, it) }
            val master = fetchText(App.server.transcodeUrl(id, sourceId, audio, burnInIndex(source, App.prefs.subLang(seriesKey)), UUID.randomUUID().toString()))
            remote.writeAtomically(fetchText(base + master.lines().first(::isSegment)))
        }
        val lines = remote.readLines()
        val segments = lines.filter(::isSegment)
        segments.forEachIndexed { n, uri ->
            val file = File(dir, "$n.ts")
            if (file.exists()) return@forEachIndexed
            // The kept URLs carry the token of their day.
            val url = base + uri.replace(Regex("ApiKey=[^&]*"), "ApiKey=${App.prefs.token}")
            transcodeHttp.newCall(Request.Builder().url(url).build()).cancellable { r ->
                if (!r.isSuccessful) throw HttpStatus(r.code)
                val tmp = File(dir, "$n.tmp")
                tmp.outputStream().use { (r.body ?: throw IOException("empty body")).byteStream().copyTo(it) }
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            }
            onProgress((n + 1) * 100 / segments.size)
        }
        var n = 0
        File(dir, PLAYLIST).writeAtomically(lines.joinToString("\n") { if (isSegment(it)) "${n++}.ts" else it })
    }

    private suspend fun fetchText(url: String) = transcodeHttp.newCall(Request.Builder().url(url).build()).cancellable { r ->
        if (!r.isSuccessful) throw HttpStatus(r.code)
        r.body?.string() ?: throw IOException("empty body")
    }

    /** A blocking call that a stopped worker (paused, network now metered) cancels, rather than fetching on unseen. */
    private suspend fun <T> Call.cancellable(block: (Response) -> T): T = coroutineScope {
        // Undispatched: a watcher cancelled before it ran would never cancel the call.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { this@cancellable.cancel() } }
        // Cancelled, the call fails with an IOException: a stop, not a network or storage failure.
        try { execute().use(block) } catch (e: IOException) { ensureActive(); throw e } finally { watcher.cancel() }
    }

    /** The server holds a segment request open until its encoder has written it. */
    private val transcodeHttp by lazy { http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** Status of every download from the scratch and the queue. Reads files: call off the main thread. */
    override fun downloadStatuses(): Map<String, DownloadStatus> {
        val current = downloading
        val all = downloads()
        if (all.isEmpty()) return emptyMap()
        val waitingFor = downloadsWaitingFor()
        val progress = all.associateWith(::progress)
        val queue = all.filter { !it.paused && it.item.id.toString() != current && !progress[it]!!.third }
            .sortedWith(queueOrder).map { it.item.id.toString() }
        return all.associate { d ->
            val id = d.item.id.toString()
            val (bytes, percent, done) = progress[d]!!
            val fill = when {
                done -> Fill.DONE
                d.paused -> Fill.PAUSED
                current == id -> Fill.RUNNING
                else -> Fill.QUEUED
            }
            id to DownloadStatus(d, percent, fill, bytes, queue.indexOf(id) + 1, waitingFor)
        }
    }

    // --- buffering -------------------------------------------------------------------------------

    /** The open player's buffer-to-end demand. */
    private var buffer: Pair<String, Scratch.Demand>? = null

    /**
     * Fills the scratch from byte [position] to the end while the player is open, where the network allows. The
     * player's reads fetch what they need regardless; this keeps going while it is paused or reading from memory.
     */
    @Synchronized
    fun startBuffer(item: BaseItemDto, position: Long) {
        val key = key(item)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (storageMissing || !App.prefs.bufferOnMetered && cm.isActiveNetworkMetered) return stopBuffer(null)
        val current = buffer
        if (current?.first == key) return current.second.moveTo(position)
        stopBuffer(null)
        buffer = key to entry(item).demand(position, Long.MAX_VALUE, Scratch.Priority.BUFFER)
    }

    /** Ends the buffer fill; null ends any. */
    @Synchronized
    fun stopBuffer(id: String?) {
        val current = buffer ?: return
        if (id != null && !current.first.startsWith("$id-")) return
        current.second.close()
        buffer = null
        evict()
    }

    /** Bytes of unpinned (buffered) scratch entries, the part [evict] limits. */
    override fun bufferedBytes(): Long {
        val ids = pinnedIds()
        return scratch.keys().filter { !isPinned(it, ids) }.sumOf { scratch.entry(it).bytes() }
    }

    override fun freeBytes() = storageRoot().usableSpace

    fun evict() {
        val ids = pinnedIds()
        val entries = scratch.keys().map { key ->
            val e = scratch.entry(key)
            CacheEntry(key, e.bytes(), e.touched(), isPinned(key, ids))
        }
        val limit = bufferLimit(App.prefs.cacheLimitGb * 1_000_000_000L, entries.filter { !it.pinned }.sumOf { it.bytes },
            freeBytes(), storageRoot().totalSpace)
        // An entry still open (the player, a download) refuses, and goes at a later pass.
        evictionOrder(entries, limit, playing).forEach { scratch.delete(it) }
    }

    /** After a failed write: true when the disk is still nearly full once the buffer is evicted. */
    fun storageFull(): Boolean {
        if (freeBytes() >= 100_000_000) return false
        evict()
        return freeBytes() < 100_000_000
    }

    /** Subtitles fetched for items that were only streamed; downloads and the playing item keep theirs. */
    fun dropStreamedSubtitles() {
        val keep = pinnedIds()
        val playing = playing
        File(ctx.filesDir, "subs").listFiles().orEmpty()
            .filter { it.name !in keep && playing?.startsWith(it.name) != true }
            .forEach { it.deleteRecursively() }
    }

    /** [sideloadedSubs] as files, fetched once so playback works offline too. */
    suspend fun fetchSubtitles(item: BaseItemDto, transcode: Boolean): Map<Int, File> = coroutineScope {
        val id = item.id.toString()
        val source = item.mediaSources?.firstOrNull() ?: return@coroutineScope emptyMap()
        val dir = subsDir(id).apply { mkdirs() }
        sideloadedSubs(item, transcode).map { stream -> async(Dispatchers.IO) {
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


/** Works through the download queue one episode at a time. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Media.storageMissing) return Result.success() // the next app start kicks the queue again
        // An account switch starts a new worker for the new account's queue; this one must not write into it.
        val user = App.prefs.userId
        var current: String? = null
        try {
            while (true) {
                if (App.prefs.userId != user) return Result.success()
                var d = Media.nextDownload() ?: return Result.success()
                val id = d.item.id.toString()
                current = id
                Media.downloading = id
                // A file replaced on the server since queuing starts over under its new key.
                val fresh = try { App.server.item(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (App.prefs.userId != user) return Result.success()
                if (fresh != null && Media.key(fresh) != Media.key(d.item)) {
                    Media.startDownload(fresh, d.auto)
                    d = Media.download(id) ?: continue
                }
                val source = d.item.mediaSources?.firstOrNull()
                if (source == null) { Media.failDownload(id); continue }
                runCatching { setForeground(foregroundInfo(d, null)) }
                withContext(Dispatchers.IO) { Media.fetchSubtitles(d.item, d.transcoded) }
                var lastUpdate = 0L
                var lastPercent = -1
                fun report(percent: Int) {
                    val now = System.currentTimeMillis()
                    // Only a new percentage: each update makes SystemUI rebuild the notification, for hours.
                    if (percent >= 0 && percent != lastPercent && now - lastUpdate > 1_000) {
                        lastUpdate = now
                        lastPercent = percent
                        setForegroundAsync(foregroundInfo(d, percent))
                    }
                }
                try {
                    if (d.transcoded) {
                        Media.downloadTranscode(d, source.id!!, ::report)
                        continue
                    }
                    // Chunks already on disk (buffered while playing, or from an interrupted run) are skipped.
                    val entry = Media.entry(d.item)
                    runInterruptible(Dispatchers.IO) { entry.download { bytes, total -> if (total > 0) report((bytes * 100 / total).toInt()) } }
                } catch (e: HttpStatus) {
                    // Refused for this item only; retrying would pick it again forever. A 401 is the token: retry.
                    if (e.code !in 400..499 || e.code == 401) throw e
                    Media.failDownload(id)
                } catch (e: WrongFile) {
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

    private fun foregroundInfo(d: Download, percent: Int?): ForegroundInfo {
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

/** Pushes unsynced progress, applies "keep next N" and trims the buffer. Runs whenever a network is up. */
class Housekeeping(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val server = App.server
        if (!server.loggedIn) return Result.success()
        // Everything below reads and writes the current account's downloads; a switch midway leaves the rest to the next run.
        val user = App.prefs.userId
        fun switched() = App.prefs.userId != user
        val unsynced = server.flush()
        if (!server.loggedIn) return Result.success() // the token was rejected; logging in pushes again
        if (unsynced != null && transient(unsynced)) return Result.retry()
        // Progress made on other devices reaches offline playback through the stored downloads.
        runCatching {
            val ids = Media.downloads().map { it.item.id }
            // An item still pending has newer local state than the server; the push that failed will retry.
            if (ids.isNotEmpty()) server.userData(ids).forEach { (id, data) ->
                if (App.pending.get(id.toString()) == null && !switched()) Media.rememberState(id.toString(), data.toPlayState())
            }
        }
        var retry = false
        for ((seriesId, n) in App.prefs.keepNextSeries()) runCatching {
            if (switched()) return Result.success()
            val episodes = server.episodes(seriesId).filter { it.parentIndexNumber != 0 }
            val refs = episodes.map { EpisodeRef(it.id.toString(), it.localState().first) }
            val window = keepNextWindow(refs, n)
            val have = Media.downloads().associateBy { it.item.id.toString() }
            window.filter { it !in have }.forEach { server.item(it).let { item -> if (!switched()) Media.startDownload(item, auto = true) } }
            val auto = have.filterValues { it.auto }.keys
            keepNextDeletions(auto, refs, window).forEach { if (!switched()) Media.deleteDownload(it) }
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
