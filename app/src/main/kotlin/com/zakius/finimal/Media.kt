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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
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
    @Volatile private var _scratch: Scratch? = null
    val scratch: Scratch
        get() {
            val current = _scratch
            if (current != null) return current
            ensureStarted()
            return _scratch!!
        }
    private lateinit var ctx: Context
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
    fun playlist(d: Download): File? {
        val file = File(transcodeDir(d.item.id.toString()), PLAYLIST)
        return if (d.transcoded && file.exists()) file else null
    }
    fun isDone(d: Download) = if (d.transcoded) playlist(d) != null else scratch.entry(key(d.item)).complete

    private val proxy by lazy { ScratchServer({ scratch }) }

    fun entry(item: BaseItemDto): Scratch.Entry {
        val source = item.mediaSources!!.first()
        val entry = scratch.entry(key(item))
        entry.source({ App.server.streamUrl(item.id.toString(), source.id!!) }, source.size)
        return entry
    }

    fun streamUrl(item: BaseItemDto): String {
        entry(item)
        return proxy.url(key(item))
    }

    /**
     * Starts fetching chunk 0 (container header / cues) in the background while subtitles are being fetched,
     * overlapping media probing with subtitle extraction. Caller must close the returned demand.
     */
    fun prewarmRead(item: BaseItemDto): Scratch.Demand? = runCatching {
        val e = entry(item)
        if (e.complete) null else e.demand(0L, scratch.chunkSize.toLong(), Scratch.Priority.READ)
    }.getOrNull()

    /** Cache key of what the player has open; eviction never touches it. */
    @Volatile var playing: String? = null

    private const val FILL_TAG = "fill"

    @Volatile private var _storageMissing = false
    override val storageMissing: Boolean
        get() {
            ensureStarted()
            return _storageMissing
        }
    @Volatile private var started = false

    /** Binds the application context without doing disk I/O, Binder IPC, or building OkHttp on the main thread. */
    fun attach(context: Context) {
        ctx = context
    }

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            adoptLooseRecords()
            _storageMissing = App.prefs.storageIndex >= storageRoots().size
            _scratch = openScratch()
            started = true
        }
    }

    /** Runs off the main thread after the first frame; see [AndroidApp.afterFirstFrame]. */
    fun afterFirstFrame(context: Context) {
        attach(context)
        ensureStarted()
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(FILL_TAG)
        storageRoots().forEach { File(it, "media").deleteRecursively() }
        context.deleteDatabase("exoplayer_internal.db")
        val workers = wm.getWorkInfosForUniqueWork("downloads").get()
        val running = workers.any { it.state == WorkInfo.State.RUNNING }
        kickDownloads(restart = !running)
        Housekeeping.enqueue(context)
    }

    private val roots by lazy { ctx.getExternalFilesDirs(null).filterNotNull().ifEmpty { listOf(ctx.filesDir) } }
    override fun storageRoots(): List<File> = roots
    private fun storageRoot() = storageRoots().getOrElse(App.prefs.storageIndex) { storageRoots()[0] }
    private fun openScratch() = Scratch(File(storageRoot(), "scratch"), http).apply {
        onStorageFull = {
            val before = freeBytes()
            evict()
            freeBytes() > before
        }
    }

    @Synchronized
    override fun switchStorage(index: Int) {
        WorkManager.getInstance(ctx).cancelUniqueWork("downloads")
        stopBuffer(null)
        pinnedIds().forEach { subsDir(it).deleteRecursively() }
        recordsRoot.deleteRecursively()
        File(storageRoot(), "transcodes").deleteRecursively()
        memo = null
        memoById = null
        transcodeDoneBytes.clear()
        changes++
        val old = scratch
        App.prefs.storageIndex = index
        _storageMissing = false
        old.close()
        old.dir.deleteRecursively()
        _scratch = openScratch()
    }

    fun upstreamFactory() = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http))

    /**
     * The player's data source: through [proxy], or straight to the server for a transcode. The 30-second read timeout
     * covers the proxy waiting up to 20 seconds for the filler, and the server holding an HLS segment request open until
     * ffmpeg has written the segment.
     */
    fun playerFactory(): DefaultDataSource.Factory {
        val client = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        return DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(client))
    }

    /** The file length reported by the server, or -1 until it is known. */
    fun lengthOf(key: String) = scratch.entry(key).length

    /** How far, in ms, the scratch reaches without a gap from [positionMs]; bytes map to time linearly. */
    fun cachedUntilMs(key: String, positionMs: Long, durationMs: Long): Long {
        val e = scratch.entry(key)
        val len = e.length
        if (len <= 0 || durationMs <= 0) return 0
        val start = len * positionMs / durationMs
        return (start + e.contiguous(start)) * durationMs / len
    }

    private val recordsRoot get() = File(ctx.filesDir, "downloads")
    private val downloadsDir get() = File(recordsRoot, App.prefs.userId ?: "none").apply { mkdirs() }
    private fun metaFile(id: String) = File(downloadsDir, "$id.json")
    private fun otherRecordDirs() = recordsRoot.listFiles().orEmpty().filter { it.isDirectory && it != downloadsDir }
    private fun recordedElsewhere(id: String) = otherRecordDirs().any { dir ->
        dir.list().orEmpty().any { name -> name.substringBefore('.') == id }
    }
    private fun ownedElsewhere(d: Download) = otherRecordDirs().any { dir ->
        val id = d.item.id.toString()
        val f = File(dir, "$id.json")
        if (f.exists()) {
            runCatching { json.decodeFromString<Download>(f.readText()).transcoded == d.transcoded }.getOrDefault(true)
        } else {
            dir.list().orEmpty().any { name -> name.substringBefore('.') == id }
        }
    }

    private fun adoptLooseRecords() {
        if (App.prefs.userId == null) return
        val looseFiles = recordsRoot.listFiles { f -> f.isFile } ?: return
        for (file in looseFiles) {
            file.renameTo(File(downloadsDir, file.name))
        }
    }
    private fun write(d: Download) {
        val id = d.item.id.toString()
        metaFile(id).writeAtomically(json.encodeToString(d))
        val old = memo
        if (old != null) {
            val updated = old.filterNot { it.item.id == d.item.id } + d
            memo = updated
            memoById = (memoById ?: emptyMap()) + (id to d)
        }
        changes++
    }

    @Volatile private var _changes = 0
    @Volatile private var _changesState: MutableIntState? = null
    override var changes: Int
        get() {
            val s = _changesState ?: mutableIntStateOf(_changes).also { _changesState = it }
            return s.intValue
        }
        private set(value) {
            _changes = value
            _changesState?.intValue = value
        }
    fun subsDir(id: String) = File(ctx.filesDir, "subs/$id")

    private var memo: List<Download>? = null
    private var memoById: Map<String, Download>? = null
    private val transcodeDoneBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Synchronized
    override fun downloads(): List<Download> {
        val cached = memo
        if (cached != null) return cached
        val files = downloadsDir.listFiles { f -> f.extension == "json" }.orEmpty()
        val decoded = files.mapNotNull { f ->
            runCatching { json.decodeFromString<Download>(f.readText()) }
                .onFailure { error -> if (error is IllegalArgumentException) f.renameTo(File(f.path + ".corrupt")) }
                .getOrNull()
        }
        memo = decoded
        memoById = decoded.associateBy { it.item.id.toString() }
        return decoded
    }

    private fun pinnedIds(): Set<String> {
        val names = recordsRoot.listFiles().orEmpty().flatMap { file ->
            if (file.isDirectory) file.list().orEmpty().toList() else listOf(file.name)
        }
        return names.map { it.substringBefore('.') }.toSet()
    }

    @Synchronized
    override fun accountChanged() {
        adoptLooseRecords()
        memo = null
        memoById = null
        changes++
        kickDownloads(restart = true)
    }

    override fun otherAccountsBytes(): Long {
        if (otherRecordDirs().isEmpty()) return 0L
        val own = downloads().map { it.item.id.toString() }.toSet()
        val others = pinnedIds() - own
        if (others.isEmpty()) return 0L
        val otherKeys = scratch.keys().filter { key -> others.any { id -> key.startsWith("$id-") } }
        val scratchBytes = otherKeys.sumOf { key -> scratch.entry(key).bytes() }
        val transcodeBytes = others.sumOf { id -> transcodeDir(id).walk().filter(File::isFile).sumOf(File::length) }
        return scratchBytes + transcodeBytes
    }
    private fun isPinned(key: String, ids: Set<String>) = ids.any { key.startsWith("$it-") }

    @Synchronized
    override fun download(id: String): Download? {
        if (memo == null) downloads()
        return memoById?.get(id)
    }

    /** Returns (known=true, download) without disk I/O when download records are already in memory. */
    @Synchronized
    fun cachedDownload(id: String): Pair<Boolean, Download?> =
        if (memo != null) true to memoById?.get(id) else false to null

    @Volatile var downloading: String? = null
        set(value) {
            field = value
            changes++
        }

    fun nextDownload(): Download? =
        downloads().filter { !it.paused && !isDone(it) }.minWithOrNull(queueOrder)

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
        val network = if (App.prefs.downloadOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints(requiredNetworkType = network))
            .build()
        val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniqueWork("downloads", policy, request)
    }

    @Synchronized
    override fun startDownload(item: BaseItemDto, auto: Boolean) {
        val id = item.id.toString()
        val existing = download(id)
        if (existing != null && isDone(existing)) return
        val same = existing != null && key(existing.item) == key(item)
        if (existing != null && !same && !ownedElsewhere(existing)) removeDownloadBytes(existing)
        val isAuto = existing?.auto ?: auto
        val queuedAt = existing?.queuedAt ?: System.currentTimeMillis()
        val transcoded = if (same) existing.transcoded else !playsDirectly(item)
        write(Download(item, isAuto, queuedAt, paused = false, transcoded = transcoded))
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
        val d = download(id) ?: return
        write(d.copy(paused = true, failed = !storageFull, storageFull = storageFull))
    }

    override fun deleteDownload(id: String) {
        val d = synchronized(this) {
            val d = download(id)
            metaFile(id).delete()
            memo = memo?.filterNot { it.item.id.toString() == id }
            memoById = memoById?.minus(id)
            transcodeDoneBytes.remove(id)
            changes++
            if (downloading == id) kickDownloads(restart = true)
            d
        }
        if (!recordedElsewhere(id)) subsDir(id).deleteRecursively()
        if (d != null && playing != key(d.item) && !ownedElsewhere(d)) removeDownloadBytes(d)
    }

    private fun removeDownloadBytes(d: Download) {
        if (d.transcoded) transcodeDir(d.item.id.toString()).deleteRecursively() else scratch.delete(key(d.item))
    }

    @Synchronized
    override fun releaseKeepNext(seriesId: String) {
        for (d in downloads().filter { it.auto && it.item.seriesId?.toString() == seriesId }) {
            if (d.item.userData?.played != true && isDone(d)) write(d.copy(auto = false))
            else deleteDownload(d.item.id.toString())
        }
    }

    @Synchronized
    override fun rememberState(id: String, state: PlayState?) {
        val d = download(id) ?: return
        if (state == null) return
        val userData = d.item.userData ?: return
        val lastPlayedMs = state.lastPlayedMs
        val lastPlayed = if (lastPlayedMs == null) {
            null
        } else {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPlayedMs), ZoneId.systemDefault())
        }
        val data = userData.copy(
            playbackPositionTicks = state.positionTicks,
            played = state.played,
            lastPlayedDate = lastPlayed,
        )
        if (data == userData) return
        try {
            write(d.copy(item = d.item.copy(userData = data)))
        } catch (e: IOException) {
        }
    }


    private class Progress(val bytes: Long, val totalBytes: Long, val percent: Int, val done: Boolean)

    private fun progress(d: Download): Progress {
        if (!d.transcoded) {
            val e = scratch.entry(key(d.item))
            val knownLength = e.length
            val len = if (knownLength > 0) knownLength else d.item.mediaSources?.firstOrNull()?.size ?: 0L
            val bytes = e.bytes()
            val percent = if (len > 0) (bytes * 100 / len).toInt() else 0
            return Progress(bytes, len, percent, e.complete)
        }
        val id = d.item.id.toString()
        val dir = transcodeDir(id)
        val playlistFile = File(dir, PLAYLIST)
        val isDone = playlistFile.exists()
        if (isDone) {
            val cachedBytes = transcodeDoneBytes.computeIfAbsent(id) {
                dir.listFiles().orEmpty().sumOf { it.length() }
            }
            return Progress(cachedBytes, 0L, 100, true)
        }
        val files = dir.listFiles().orEmpty()
        val remote = File(dir, "remote.m3u8")
        val total = if (remote.exists()) remote.readLines().count(::isSegment) else 0
        val done = files.count { it.name.endsWith(".ts") }
        val bytes = files.sumOf { it.length() }
        val percent = if (total > 0) done * 100 / total else 0
        return Progress(bytes, 0L, percent, false)
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
            val audioLang = App.prefs.audioLang(seriesKey)
            val audio = if (audioLang == null) null else audioIndex(source, audioLang)
            val burnIn = burnInIndex(source, App.prefs.subLang(seriesKey))
            val masterUrl = App.server.transcodeUrl(id, sourceId, audio, burnIn, UUID.randomUUID().toString())
            val master = fetchText(masterUrl)
            val variant = master.lines().first(::isSegment)
            remote.writeAtomically(fetchText(base + variant))
        }
        val lines = remote.readLines()
        val segments = lines.filter(::isSegment)
        for ((n, uri) in segments.withIndex()) {
            val file = File(dir, "$n.ts")
            if (file.exists()) continue
            // The saved playlist's URLs carry the token from when it was fetched, so swap in the current one.
            val url = base + uri.replace(Regex("ApiKey=[^&]*"), "ApiKey=${App.prefs.token}")
            transcodeHttp.newCall(Request.Builder().url(url).build()).cancellable { r ->
                if (!r.isSuccessful) throw HttpStatus(r.code)
                val tmp = File(dir, "$n.tmp")
                tmp.outputStream().use { out ->
                    r.body.byteStream().copyTo(out)
                }
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            }
            onProgress((n + 1) * 100 / segments.size)
        }
        var segmentNumber = 0
        val localLines = lines.map { line ->
            if (isSegment(line)) {
                val name = "$segmentNumber.ts"
                segmentNumber++
                name
            } else {
                line
            }
        }
        File(dir, PLAYLIST).writeAtomically(localLines.joinToString("\n"))
    }

    private suspend fun fetchText(url: String) = transcodeHttp.newCall(Request.Builder().url(url).build()).cancellable { r ->
        if (!r.isSuccessful) throw HttpStatus(r.code)
        r.body.string()
    }

    private suspend fun <T> Call.cancellable(block: (Response) -> T): T {
        val call = this
        return coroutineScope {
            // Start undispatched: a watcher cancelled before its first run would never reach the finally that cancels the call.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            // A cancelled call fails with an IOException. ensureActive turns that into a cancellation, so it counts as a
            // stop rather than a network or storage failure.
            try {
                call.execute().use(block)
            } catch (e: IOException) {
                ensureActive()
                throw e
            } finally {
                watcher.cancel()
            }
        }
    }

    /** The server holds a segment request open until its encoder has written it. */
    private val transcodeHttp by lazy { http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    override fun downloadStatuses(): Map<String, DownloadStatus> {
        val current = downloading
        val all = downloads()
        if (all.isEmpty()) return emptyMap()
        val waitingFor = downloadsWaitingFor()
        val progress = all.associateWith(::progress)
        val queue = all
            .filter { d -> !d.paused && d.item.id.toString() != current && !progress[d]!!.done }
            .sortedWith(queueOrder)
            .map { d -> d.item.id.toString() }
        val queuePos = HashMap<String, Int>(queue.size).apply {
            queue.forEachIndexed { idx, id -> put(id, idx + 1) }
        }
        return all.associate { d ->
            val id = d.item.id.toString()
            val p = progress[d]!!
            val fill = when {
                p.done -> Fill.DONE
                d.paused -> Fill.PAUSED
                current == id -> Fill.RUNNING
                else -> Fill.QUEUED
            }
            id to DownloadStatus(d, p.percent, fill, p.bytes, queuePos[id] ?: 0, waitingFor, p.totalBytes)
        }
    }

    private var buffer: Pair<String, Scratch.Demand>? = null

    /**
     * Fills the scratch from byte [position] to the end while the player is open, where the network allows. The
     * player's reads fetch what they need regardless; this keeps going while it is paused or reading from memory.
     */
    @Synchronized
    fun startBuffer(item: BaseItemDto, position: Long) {
        val key = key(item)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (storageMissing || (!App.prefs.bufferOnMetered && cm.isActiveNetworkMetered)) {
            stopBuffer(null)
            return
        }
        val current = buffer
        if (current?.first == key) {
            current.second.moveTo(position)
            return
        }
        stopBuffer(null)
        val demand = entry(item).demand(position, Long.MAX_VALUE, Scratch.Priority.BUFFER)
        buffer = Pair(key, demand)
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
        val limitBytes = App.prefs.cacheLimitGb * 1_000_000_000L
        val bufferedBytes = entries.filter { !it.pinned }.sumOf { it.bytes }
        val limit = bufferLimit(limitBytes, bufferedBytes, freeBytes(), storageRoot().totalSpace)
        evictionOrder(entries, limit, playing).forEach { scratch.delete(it) }
    }

    fun clearBuffer() {
        val ids = pinnedIds()
        val active = playing
        scratch.keys()
            .filter { key -> !isPinned(key, ids) && key != active }
            .forEach { scratch.delete(it) }
    }

    /** After a failed write: whether the disk is still nearly full once the buffer is evicted. */
    fun storageFull(): Boolean {
        if (freeBytes() >= NEARLY_FULL_BYTES) return false
        evict()
        return freeBytes() < NEARLY_FULL_BYTES
    }
    private const val NEARLY_FULL_BYTES = 100L * 1000 * 1000

    /** Subtitles fetched for items that were only streamed; downloads and the playing item keep theirs. */
    fun dropStreamedSubtitles() {
        val keep = pinnedIds()
        val playing = playing
        File(ctx.filesDir, "subs").listFiles().orEmpty()
            .filter { dir -> dir.name !in keep && (playing == null || !playing.startsWith(dir.name)) }
            .forEach { it.deleteRecursively() }
    }

    /** [sideloadedSubs] as files, fetched once so playback works offline too. */
    suspend fun fetchSubtitles(item: BaseItemDto, transcode: Boolean): Map<Int, File> {
        val subs = sideloadedSubs(item, transcode)
        if (subs.isEmpty()) return emptyMap()
        val id = item.id.toString()
        val source = item.mediaSources?.firstOrNull() ?: return emptyMap()
        return coroutineScope {
            val dir = withContext(Dispatchers.IO) { subsDir(id).apply { mkdirs() } }
            val fetches = subs.map { stream ->
                async(Dispatchers.IO) {
                    val file = File(dir, "${stream.index}.${subtitleExt(stream)}")
                    if (!file.exists()) {
                        runCatching {
                            val url = App.server.subtitleUrl(id, source.id!!, stream)
                            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                                val tmp = File.createTempFile(file.name, ".tmp", dir)
                                try {
                                    tmp.outputStream().use { out ->
                                        r.body.byteStream().copyTo(out)
                                    }
                                    tmp.renameTo(file)
                                } finally {
                                    tmp.delete()
                                }
                            }
                        }
                    }
                    if (file.exists()) Pair(stream.index, file) else null
                }
            }
            fetches.awaitAll().filterNotNull().toMap()
        }
    }
}


class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Media.storageMissing) return Result.success()
        val user = App.prefs.userId
        var current: String? = null
        try {
            while (true) {
                if (App.prefs.userId != user) return Result.success()
                var d = Media.nextDownload() ?: return Result.success()
                val id = d.item.id.toString()
                current = id
                Media.downloading = id
                val fresh = try {
                    App.server.item(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (App.prefs.userId != user) return Result.success()
                if (fresh != null && Media.key(fresh) != Media.key(d.item)) {
                    Media.startDownload(fresh, d.auto)
                    d = Media.download(id) ?: continue
                }
                val source = d.item.mediaSources?.firstOrNull()
                if (source == null) {
                    Media.failDownload(id)
                    continue
                }
                runCatching { setForeground(foregroundInfo(d, null)) }
                withContext(Dispatchers.IO) { Media.fetchSubtitles(d.item, d.transcoded) }
                var lastUpdate = 0L
                var lastPercent = -1
                fun report(percent: Int) {
                    val now = System.currentTimeMillis()
                    // Update only on a new percentage, at most once a second. Each update makes SystemUI rebuild the
                    // notification, and a download can run for hours.
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
                    val entry = Media.entry(d.item)
                    runInterruptible(Dispatchers.IO) {
                        entry.download { bytes, total ->
                            if (total > 0) report((bytes * 100 / total).toInt())
                        }
                    }
                } catch (e: HttpStatus) {
                    if (e.code !in 400..499 || e.code == 401) throw e
                    Media.failDownload(id)
                } catch (e: WrongFile) {
                    Media.failDownload(id)
                }
            }
        } catch (e: IOException) {
            if (current == null || !Media.storageFull()) return Result.retry()
            Media.failDownload(current, storageFull = true)
            return Result.success()
        } finally {
            // A replaced worker can finish after its successor started; don't clear the successor's ID.
            if (Media.downloading == current) Media.downloading = null
        }
    }

    private val openDownloads by lazy {
        if (Build.VERSION.SDK_INT >= 26) {
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOWNLOADS, true)
        PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

class Housekeeping(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val server = App.server
        if (!server.loggedIn) return Result.success()
        val user = App.prefs.userId
        fun switched() = App.prefs.userId != user
        val unsynced = server.flush()
        if (!server.loggedIn) return Result.success()
        if (unsynced != null && transient(unsynced)) return Result.retry()
        runCatching {
            val ids = Media.downloads().map { it.item.id }
            if (ids.isNotEmpty()) {
                val userData = server.userData(ids)
                for ((id, data) in userData) {
                    if (App.pending.get(id.toString()) == null && !switched()) Media.rememberState(id.toString(), data.toPlayState())
                }
            }
        }
        var retry = false
        for ((seriesId, n) in App.prefs.keepNextSeries()) {
            runCatching {
                if (switched()) return Result.success()
                val episodes = server.episodes(seriesId).filter { it.parentIndexNumber != 0 }
                val refs = episodes.map { episode -> EpisodeRef(episode.id.toString(), episode.localState().first) }
                val window = keepNextWindow(refs, n)
                val have = Media.downloads().associateBy { it.item.id.toString() }
                val missing = window.filter { id -> id !in have }
                for (id in missing) {
                    val item = server.item(id)
                    if (!switched()) Media.startDownload(item, auto = true)
                }
                val auto = have.filterValues { it.auto }.keys
                for (id in keepNextDeletions(auto, refs, window)) {
                    if (!switched()) Media.deleteDownload(id)
                }
            }.onFailure { error ->
                if (transient(error)) retry = true
            }
        }
        Media.evict()
        Media.dropStreamedSubtitles()
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            "housekeeping",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Housekeeping>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build(),
        )
    }
}
