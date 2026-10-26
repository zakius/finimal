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

/** The desktop's share of the scratch: what plays streams through it and buffers to the end. No downloads yet. */
object DesktopMedia {
    val scratch: Scratch by lazy {
        Scratch(File(App.platform.appFiles, "scratch"), App.http).apply {
            onStorageFull = { val before = freeBytes(); evict(); freeBytes() > before }
        }
    }
    private val proxy by lazy { ScratchServer({ scratch }) }

    /** Key of what the player has open; eviction leaves it. */
    @Volatile var playing: String? = null

    private fun entry(item: BaseItemDto): Scratch.Entry {
        val source = item.mediaSources!!.first()
        return scratch.entry(scratchKey(item)).apply { source({ App.server.streamUrl(item.id.toString(), source.id!!) }, source.size) }
    }

    /** The item's file through the scratch, for mpv. */
    fun streamUrl(item: BaseItemDto): String = entry(item).let { proxy.url(scratchKey(item)) }

    private var buffer: Pair<String, Scratch.Demand>? = null

    /** Fills from byte [position] to the end while the player is open; a desktop has no metered networks to spare. */
    @Synchronized
    fun startBuffer(item: BaseItemDto, position: Long) {
        val key = scratchKey(item)
        val current = buffer
        if (current?.first == key) return current.second.moveTo(position)
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

    /** Seconds from [position] the scratch reaches without a gap; bytes map to time linearly. */
    fun cachedUntil(item: BaseItemDto, position: Double, duration: Double): Double {
        val e = scratch.entry(scratchKey(item))
        val len = e.length
        if (len <= 0 || duration <= 0) return 0.0
        val start = (len * position / duration).toLong()
        return (start + e.contiguous(start)) * duration / len
    }

    private fun freeBytes(): Long = scratch.dir.apply { mkdirs() }.usableSpace

    /** Oldest first, down to the buffer limit in Settings and keeping max(2 GB, 5 %) of the disk free. */
    fun evict() {
        val entries = scratch.keys().map { key -> scratch.entry(key).let { CacheEntry(key, it.bytes(), it.touched(), pinned = false) } }
        val limit = bufferLimit(App.prefs.cacheLimitGb * 1_000_000_000L, entries.sumOf { it.bytes }, freeBytes(), scratch.dir.totalSpace)
        evictionOrder(entries, limit, playing).forEach { scratch.delete(it) }
    }
}
