package com.zakius.finimal

import java.io.File
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto
import finimal.core.PlayState

/**
 * A pinned item. Downloads run one at a time, oldest [queuedAt] first. Playing an unfinished
 * download fills from the playhead first, through the same scratch entry.
 */
@Serializable
data class Download(
    val item: BaseItemDto,
    val auto: Boolean,
    val queuedAt: Long = 0,
    val paused: Boolean = false,
    /** The server refused the file (HTTP 4xx, e.g. deleted there); paused with it. Resuming tries again. */
    val failed: Boolean = false,
    /** The disk was full even after evicting the buffer; paused with it. Resuming tries again. */
    val storageFull: Boolean = false,
    /** The device can't play the file, so it is stored as the server's transcode, in the transcodes folder. */
    val transcoded: Boolean = false,
)

val queueOrder = compareBy<Download>({ it.queuedAt }, { it.item.parentIndexNumber }, { it.item.indexNumber })
/** Finished downloads: grouped by series in season order, movies last. */
val shelfOrder = compareBy<Download>({ it.item.seriesId == null }, { it.item.seriesName }, { it.item.seriesId },
    { it.item.parentIndexNumber }, { it.item.indexNumber }, { it.item.name })

enum class Fill { DONE, RUNNING, QUEUED, PAUSED }
/** [position] is 1 for the next queued item; [waitingFor] is [Downloads.downloadsWaitingFor]. */
data class DownloadStatus(val download: Download, val percent: Int, val fill: Fill, val bytes: Long, val position: Int, val waitingFor: String?) {
    val label get() = when (fill) {
        Fill.DONE -> if (download.transcoded) "downloaded, transcoded" else "downloaded"
        Fill.RUNNING -> "${waitingFor ?: "downloading"} · $percent%"
        Fill.QUEUED -> "${waitingFor ?: "queued #$position"} · $percent%"
        Fill.PAUSED -> "${if (download.failed) "failed" else if (download.storageFull) "storage full" else "paused"} · $percent%"
    }
}

/**
 * An item's key in the scratch: item plus file version, so a file replaced on the server never mixes with bytes
 * kept from the old one.
 */
fun scratchKey(item: BaseItemDto): String {
    val source = item.mediaSources?.firstOrNull()
    return "${item.id}-${source?.eTag ?: source?.size ?: 0}"
}

/** Downloads as the screens see them; null on a platform without downloads yet. Reads files: call off the main thread unless noted. */
interface Downloads {
    /** Bumped on every change, so open screens re-read statuses at once; read in composition. */
    val changes: Int
    /** The selected SD card was missing at start: the queue waits for it. */
    val storageMissing: Boolean
    fun downloads(): List<Download>
    fun download(id: String): Download?
    fun downloadStatuses(): Map<String, DownloadStatus>
    /** [item] must carry media sources for offline playback. Also resumes a paused download. */
    fun startDownload(item: BaseItemDto, auto: Boolean)
    fun pauseDownload(id: String)
    fun deleteDownload(id: String)
    /** Why the queue can't run now ("waiting for network"), or null. */
    fun downloadsWaitingFor(): String?
    fun kickDownloads(restart: Boolean)
    /** "Keep next N" turned off for the series: its unplayed finished downloads stay, the rest go. */
    fun releaseKeepNext(seriesId: String)
    /** Keeps a download's stored play state current, for offline resume. */
    fun rememberState(id: String, state: PlayState?)
    fun bufferedBytes(): Long
    fun freeBytes(): Long
    /** App-specific storage volumes; 1+ are removable. */
    fun storageRoots(): List<File>
    /** Moves storage to root [index], deleting every download and the buffer. */
    fun switchStorage(index: Int)
    /** Each account has its own downloads and queue; the bytes of an item are shared. Re-reads for the current account. */
    fun accountChanged()
    /** Bytes downloaded only by other accounts on this device; their titles stay theirs. */
    fun otherAccountsBytes(): Long
}
