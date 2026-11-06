package com.zakius.finimal.desktop

import com.zakius.finimal.App
import com.zakius.finimal.scratchKey
import finimal.core.CacheEntry
import finimal.core.Scratch
import finimal.core.ScratchServer
import finimal.core.bufferLimit
import finimal.core.evictionOrder
import java.io.File
import org.jellyfin.sdk.model.api.BaseItemDto

/** The desktop's use of the scratch: playback streams through it and buffers to the end of the file. No downloads yet. */
object DesktopMedia {
    val scratch: Scratch by lazy {
        Scratch(File(App.platform.appFiles, "scratch"), App.http).apply {
            onStorageFull = {
                val before = freeBytes()
                evict()
                freeBytes() > before
            }
        }
    }
    private val proxy by lazy { ScratchServer({ scratch }) }

    /** The key of what the player has open, which eviction never removes. */
    @Volatile var playing: String? = null

    private fun entry(item: BaseItemDto): Scratch.Entry {
        val source = item.mediaSources!!.first()
        val entry = scratch.entry(scratchKey(item))
        entry.source({ App.server.streamUrl(item.id.toString(), source.id!!) }, source.size)
        return entry
    }

    /** A URL for mpv that serves the item's file through the scratch. */
    fun streamUrl(item: BaseItemDto): String {
        entry(item)
        return proxy.url(scratchKey(item))
    }

    private var buffer: Pair<String, Scratch.Demand>? = null

    /** Fills from byte [position] to the end while the player is open. A desktop doesn't need to save metered data. */
    @Synchronized
    fun startBuffer(item: BaseItemDto, position: Long) {
        val key = scratchKey(item)
        val current = buffer
        if (current != null && current.first == key) {
            current.second.moveTo(position)
            return
        }
        stopBuffer()
        buffer = key to entry(item).demand(position, Long.MAX_VALUE, Scratch.Priority.BUFFER)
    }

    @Synchronized
    fun stopBuffer() {
        buffer?.second?.close()
        buffer = null
        evict()
    }

    fun lengthOf(item: BaseItemDto) = scratch.entry(scratchKey(item)).length

    /** The time, in seconds, up to which the scratch holds data from [position] without a gap. Assumes bytes map to time linearly. */
    fun cachedUntil(item: BaseItemDto, position: Double, duration: Double): Double {
        val e = scratch.entry(scratchKey(item))
        val len = e.length
        if (len <= 0 || duration <= 0) return 0.0
        val start = (len * position / duration).toLong()
        return (start + e.contiguous(start)) * duration / len
    }

    private fun freeBytes(): Long {
        val dir = scratch.dir
        dir.mkdirs()
        return dir.usableSpace
    }

    /** Deletes the oldest entries until within the buffer limit in Settings, keeping 2 GB or 5 % of the disk free, whichever is more. */
    fun evict() {
        val entries = scratch.keys().map { key ->
            val entry = scratch.entry(key)
            CacheEntry(key, entry.bytes(), entry.touched(), pinned = false)
        }
        val maxBytes = App.prefs.cacheLimitGb * 1_000_000_000L
        val limit = bufferLimit(maxBytes, entries.sumOf { it.bytes }, freeBytes(), scratch.dir.totalSpace)
        evictionOrder(entries, limit, playing).forEach { scratch.delete(it) }
    }
}
