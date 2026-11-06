package finimal.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.BitSet
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** The server refused the request. Only a 5xx, or a 401 from a stale token, is worth retrying. */
class HttpStatus(val code: Int) : IOException("HTTP $code")

/** The server sent a file of a different size than it lists, such as a captive portal page or a file replaced mid-download. */
class WrongFile(message: String) : IOException(message)

/** A chunk could not be written because the disk is full or gone. */
class StorageError(cause: IOException) : IOException(cause.message, cause)

/**
 * Server files kept on disk in fixed-size chunks. Streaming and downloads share them, so bytes fetched by one are
 * read by the other. Each entry is a folder per file version (its key) holding a `length` file, the whole chunks
 * `0`, `1`, and so on, and `<n>.part` for the chunk being written. A chunk file appears only after all of it is on
 * disk and synced, so a crash leaves at worst a `.part`, which is fetched again.
 *
 * One filler thread per entry fetches what its open [Demand]s ask for. Readers never fetch. They wait for the
 * filler and read the chunk it is writing as the bytes arrive.
 */
class Scratch(val dir: File, private val http: OkHttpClient, val chunkSize: Int = 4 * 1024 * 1024) {
    private val entries = HashMap<String, Entry>()
    @Volatile private var closed = false

    /** Called when a chunk can't be written; returns true when it freed space worth trying again for. */
    @Volatile var onStorageFull: () -> Boolean = { false }

    @Synchronized
    fun entry(key: String): Entry {
        check(!closed) { "scratch closed" }
        return entries.getOrPut(key) { Entry(key, File(dir, key)) }
    }

    /** Keys with anything on disk. */
    fun keys(): List<String> = dir.list().orEmpty().filter { name -> File(dir, name).isDirectory }

    /** Removes [key]'s bytes and returns true, unless a reader, buffer or download has it open. */
    fun delete(key: String): Boolean {
        val e = synchronized(this) { entries[key] }
        if (e != null && !e.release()) return false
        // Delete under the lock, so a concurrent entry() for this key can't count the chunks being deleted as present.
        synchronized(this) {
            entries.remove(key)
            File(dir, key).deleteRecursively()
        }
        return true
    }

    /** Stops every filler and reader but keeps the files. Used when switching storage location. */
    fun close() {
        val all = synchronized(this) {
            closed = true
            entries.values.toList()
        }
        for (entry in all) entry.shutDown()
    }

    enum class Priority { READ, BUFFER, DOWNLOAD }

    /** What an open reader, buffer or download wants on disk: [ahead] bytes from [position], or the rest of the file. */
    inner class Demand internal constructor(
        private val entry: Entry, position: Long, val ahead: Long, val priority: Priority, internal val seq: Long,
    ) : Closeable {
        @Volatile var position = position
            private set

        fun moveTo(pos: Long) {
            val crossed = pos / chunkSize != position / chunkSize
            position = pos
            if (crossed) entry.wake()
        }

        override fun close() = entry.drop(this)
    }

    inner class Entry internal constructor(val key: String, private val folder: File) {
        // Everything below is guarded by this entry's monitor.
        /** Total bytes; -1 until the server says. */
        var length: Long = run {
            val file = File(folder, "length")
            val saved = if (file.exists()) file.readText().trim().toLongOrNull() else null
            saved ?: -1L
        }
            private set
        private val present = BitSet()
        /** The chunk the filler is writing, and how many of its bytes are on disk. */
        private var writing = -1
        private var written = 0L
        private var url: (() -> String)? = null
        private var expectedSize: Long? = null
        private val demands = ArrayList<Demand>()
        private var seq = 0L
        private var filler: Thread? = null
        private var call: Call? = null
        /** The last failure. [fatal] means retrying can't help: the server refused, the file is wrong, or the disk failed. */
        private var error: IOException? = null
        private var fatal = false
        /** The disk filled up. Until the next demand, only chunks that readers wait for get written. */
        private var lowSpace = false
        private var gone = false
        private var touched = 0L

        init {
            for (f in folder.listFiles().orEmpty()) {
                val i = f.name.toIntOrNull()
                if (i != null) {
                    present.set(i)
                } else if (f.name.endsWith(".part") || f.name.endsWith(".import")) {
                    f.delete()
                }
            }
        }

        private fun chunks() = ((length + chunkSize - 1) / chunkSize).toInt()
        private fun chunkLength(i: Int) = minOf(chunkSize.toLong(), length - i.toLong() * chunkSize)
        private fun chunkFile(i: Int) = File(folder, "$i")
        private fun partFile(i: Int) = File(folder, "$i.part")

        /** Where the bytes come from; [size] is the length the server lists, checked against what it sends. */
        @Synchronized
        fun source(url: () -> String, size: Long?) {
            this.url = url
            expectedSize = if (size != null && size > 0) size else null
        }

        @get:Synchronized val complete get() = length >= 0 && present.cardinality() == chunks()

        /** Bytes of whole chunks on disk. */
        @Synchronized
        fun bytes(): Long {
            if (length < 0) return 0
            var sum = 0L
            var i = present.nextSetBit(0)
            while (i >= 0) {
                sum += chunkLength(i)
                i = present.nextSetBit(i + 1)
            }
            return sum
        }

        /** Last read or write, for eviction. */
        fun touched(): Long = folder.lastModified()

        /** How many bytes from [from] can be read without waiting for the network. */
        @Synchronized
        fun contiguous(from: Long): Long {
            if (length < 0 || from >= length) return 0
            var i = (from / chunkSize).toInt()
            var end = i.toLong() * chunkSize
            while (i < chunks() && present[i]) {
                end += chunkLength(i)
                i++
            }
            if (i < chunks() && writing == i) end += written
            return (minOf(end, length) - from).coerceAtLeast(0)
        }

        @Synchronized
        fun inUse() = demands.isNotEmpty()

        /** Registers a demand and starts the filler. A new demand also clears a failure, so it is tried again. */
        @Synchronized
        fun demand(position: Long, ahead: Long, priority: Priority): Demand {
            check(!gone) { "entry removed" }
            checkNotNull(url) { "no source for $key" }
            val d = Demand(this, position, ahead, priority, seq++)
            demands += d
            error = null
            fatal = false
            lowSpace = false
            if (filler == null) filler = thread(name = "fill-$key", isDaemon = true) { fill() }
            wake()
            return d
        }

        internal fun drop(d: Demand) = synchronized(this) {
            demands -= d
            wake()
        }

        /** Called when the demands change. The filler picks its target again, and a fetch nobody wants any more is cancelled. */
        @Synchronized
        internal fun wake() {
            (this as Object).notifyAll()
            if (writing >= 0 && target() != writing) call?.cancel()
        }

        /** Closes the entry for good and returns true, unless something still has it open. */
        @Synchronized
        internal fun release(): Boolean {
            if (demands.isNotEmpty()) return false
            shutDown()
            return true
        }

        @Synchronized
        internal fun shutDown() {
            gone = true
            demands.clear()
            call?.cancel()
            (this as Object).notifyAll()
        }

        /** Sets the length of a file whose chunks come from elsewhere, such as a migration. Call it before [putChunk]. */
        @Synchronized
        fun setLength(total: Long) {
            if (length == total) return
            check(length < 0) { "length is $length, not $total" }
            folder.mkdirs()
            File(folder, "length").writeAtomically("$total")
            length = total
        }

        @Synchronized
        fun has(i: Int) = present[i]

        /** Stores the whole chunk [i] without going through the filler. */
        fun putChunk(i: Int, bytes: ByteArray) {
            synchronized(this) {
                require(bytes.size.toLong() == chunkLength(i)) { "chunk $i is ${chunkLength(i)} bytes, not ${bytes.size}" }
            }
            val part = File(folder, "$i.import")
            FileOutputStream(part).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!part.renameTo(chunkFile(i))) throw IOException("rename failed")
            synchronized(this) {
                present.set(i)
                (this as Object).notifyAll()
            }
        }

        /**
         * Reads up to [len] bytes at [pos], waiting up to [timeoutMs] for the filler. Returns -1 past the end. Throws
         * the filler's failure, or [InterruptedIOException] when its last try failed or nothing came in time.
         */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val i = (pos / chunkSize).toInt()
                val inChunk = pos - i.toLong() * chunkSize
                val (file, available) = synchronized(this) {
                    while (true) {
                        if (gone) throw InterruptedIOException("closed")
                        if (length in 0..pos) return -1
                        when {
                            length >= 0 && present[i] -> return@synchronized chunkFile(i) to chunkLength(i) - inChunk
                            writing == i && written > inChunk -> return@synchronized partFile(i) to written - inChunk
                            fatal -> throw error!!
                            // The filler's last try failed (no network, or the server is down). Fail now rather than at
                            // the deadline, so the player reports it. The player's retry is a new demand, which wakes the filler.
                            error != null && writing != i -> throw InterruptedIOException("no data: ${error!!.message}")
                        }
                        val wait = deadline - System.currentTimeMillis()
                        if (wait <= 0) {
                            val lastError = error
                            if (lastError != null) throw InterruptedIOException("no data: ${lastError.message}")
                            throw InterruptedIOException("no data")
                        }
                        (this as Object).wait(wait)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                // On Windows, only a FileChannel (not a RandomAccessFile) lets the filler rename the part while it is open.
                val count = minOf(len.toLong(), available).toInt()
                val n = try {
                    FileChannel.open(file.toPath()).use { channel -> channel.read(ByteBuffer.wrap(buf, off, count), inChunk) }
                } catch (e: IOException) {
                    // Between the check above and this open, the filler may have renamed the part to its chunk, or
                    // deleted it to start over. Windows reports a part being deleted as AccessDenied. Retry until the deadline.
                    val partMoved = e is NoSuchFileException || e is AccessDeniedException
                    if (!partMoved || System.currentTimeMillis() > deadline) throw e
                    continue
                }
                if (n <= 0) continue
                touch()
                return n
            }
        }

        /** Waits up to [timeoutMs] for the length, which the first response tells. */
        fun awaitLength(timeoutMs: Long): Long = synchronized(this) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (length < 0) {
                if (gone) throw InterruptedIOException("closed")
                if (fatal) throw error!!
                val lastError = error
                if (lastError != null) throw InterruptedIOException("no answer: ${lastError.message}")
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw InterruptedIOException("no answer")
                (this as Object).wait(wait)
            }
            length
        }

        /**
         * Fills the whole file, reporting bytes on disk and the total length. A fatal failure throws at once. A passing
         * one, such as a network error, throws only after [stallMs] go by without a new chunk. Interruptible.
         */
        fun download(stallMs: Long = 60_000, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
            demand(0, Long.MAX_VALUE, Priority.DOWNLOAD).use {
                var lastBytes = -1L
                var lastProgress = System.currentTimeMillis()
                while (true) {
                    val (bytes, total) = synchronized(this) {
                        if (complete) return
                        if (gone) throw InterruptedIOException("closed")
                        if (fatal) throw error!!
                        if (lowSpace) throw StorageError(IOException("storage full"))
                        bytes() to length
                    }
                    val now = System.currentTimeMillis()
                    if (bytes != lastBytes) {
                        lastBytes = bytes
                        lastProgress = now
                        onProgress(bytes, total)
                    }
                    synchronized(this) {
                        val lastError = error
                        if (lastError != null && now - lastProgress > stallMs) throw lastError
                        if (!complete) (this as Object).wait(1_000)
                    }
                }
            }
        }

        private fun touch() {
            val now = System.currentTimeMillis()
            // Update the folder's mtime at most once a minute, to keep disk writes rare.
            if (now - touched > 60_000) {
                touched = now
                folder.setLastModified(now)
            }
        }

        /** The next chunk to fetch: the first missing one in the most pressing demand's range. Among readers, the newest goes first. */
        private fun target(): Int? {
            if (gone || fatal) return null
            val eligible = demands.filter { d -> !lowSpace || d.priority == Priority.READ }
            val order = eligible.sortedWith(compareBy<Demand> { d -> d.priority }.thenByDescending { d -> d.seq })
            if (length < 0) {
                val first = order.firstOrNull() ?: return null
                return (first.position / chunkSize).toInt()
            }
            for (d in order) {
                if (d.position >= length) continue
                val first = (d.position / chunkSize).toInt()
                val last = if (d.ahead == Long.MAX_VALUE) {
                    chunks() - 1
                } else {
                    val end = minOf(length, d.position + d.ahead)
                    ((end - 1) / chunkSize).toInt()
                }
                val i = present.nextClearBit(first)
                if (i <= last) return i
            }
            return null
        }

        private fun fill() {
            var attempt = 0
            while (true) {
                val start = synchronized(this) {
                    while (true) {
                        val t = target()
                        if (t != null) return@synchronized t
                        if (demands.isEmpty() || gone) {
                            filler = null
                            return
                        }
                        (this as Object).wait()
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                try {
                    if (fetch(start)) attempt = 0
                } catch (e: StorageError) {
                    if (onStorageFull()) continue
                    synchronized(this) {
                        // Buffering and downloading stop, but a reader still gets its chunks written while there is room.
                        if (lowSpace || demands.none { d -> d.priority == Priority.READ }) {
                            error = e
                            fatal = true
                        } else {
                            lowSpace = true
                        }
                        (this as Object).notifyAll()
                    }
                } catch (e: WrongFile) {
                    fail(e)
                } catch (e: HttpStatus) {
                    // A 4xx stays refused. A 401 (stale token) and a 5xx (server trouble) are worth waiting out.
                    if (e.code in 400..499 && e.code != 401) {
                        fail(e)
                    } else {
                        backOff(e, attempt++)
                    }
                } catch (e: IOException) {
                    val cancelled = synchronized(this) { call?.isCanceled() == true }
                    if (cancelled) continue
                    backOff(e, attempt++)
                } finally {
                    synchronized(this) {
                        call = null
                        // Once gone, the folder may belong to a new entry, so leave the part. That entry's init deletes leftovers.
                        if (writing >= 0) {
                            if (!gone) partFile(writing).delete()
                            writing = -1
                            written = 0
                        }
                    }
                }
            }
        }

        private fun fail(e: IOException) = synchronized(this) {
            error = e
            fatal = true
            (this as Object).notifyAll()
        }

        private fun backOff(e: IOException, attempt: Int) = synchronized(this) {
            error = e
            (this as Object).notifyAll()
            // A new demand, such as a seek or a retry, ends the wait early.
            if (!gone && demands.isNotEmpty()) {
                val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
                (this as Object).wait(delays[minOf(attempt, 5)])
            }
        }

        /** Fetches from chunk [start] on while the demands want what comes next. Returns whether a chunk was stored. */
        private fun fetch(start: Int): Boolean {
            val from = start.toLong() * chunkSize
            val c = synchronized(this) {
                val request = Request.Builder().url(url!!()).header("Range", "bytes=$from-").build()
                val newCall = http.newCall(request)
                call = newCall
                newCall
            }
            var stored = false
            c.execute().use { r ->
                if (!r.isSuccessful) throw HttpStatus(r.code)
                val ranged = r.code == 206
                val range = r.header("Content-Range")
                val begin: Long? = when {
                    !ranged -> 0L
                    range == null -> null
                    else -> range.substringAfter("bytes ").substringBefore('-').toLongOrNull()
                }
                val expectedBegin = if (ranged) from else 0L
                if (begin != expectedBegin) throw IOException("unexpected range $range")
                val total = if (ranged) {
                    range!!.substringAfter('/').toLongOrNull() ?: -1L
                } else {
                    r.body.contentLength()
                }
                synchronized(this) {
                    val expected = if (length >= 0) length else expectedSize
                    if (total < 0 || expected != null && total != expected) throw WrongFile("$total bytes, expected $expected")
                    if (length < 0) {
                        try {
                            folder.mkdirs()
                            File(folder, "length").writeAtomically("$total")
                        } catch (e: IOException) {
                            throw StorageError(e)
                        }
                        length = total
                        (this as Object).notifyAll()
                    }
                }
                val input = r.body.byteStream()
                val buf = ByteArray(64 * 1024)
                var pos = begin!!
                while (pos < length) {
                    val i = (pos / chunkSize).toInt()
                    val end = i.toLong() * chunkSize + chunkLength(i)
                    val have = synchronized(this) {
                        val next = target()
                        when {
                            present[i] -> true
                            next == i -> {
                                writing = i
                                written = 0
                                false
                            }
                            // A server that ignores the range sends from byte 0. Skip ahead to the wanted chunk rather than request again at once.
                            !ranged && next != null && i < next -> true
                            else -> return stored
                        }
                    }
                    if (have) {
                        // Only a server that ignores the range sends a chunk that is already here or not wanted. Skip it, since later ones may be wanted.
                        if (ranged) return stored
                        while (pos < end) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                            if (n < 0) throw IOException("ended at $pos")
                            pos += n
                        }
                        continue
                    }
                    val out = try {
                        FileOutputStream(partFile(i))
                    } catch (e: IOException) {
                        throw StorageError(e)
                    }
                    out.use {
                        while (pos < end) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                            if (n < 0) throw IOException("ended at $pos of $length bytes")
                            try {
                                out.write(buf, 0, n)
                            } catch (e: IOException) {
                                throw StorageError(e)
                            }
                            pos += n
                            synchronized(this) {
                                written += n
                                (this as Object).notifyAll()
                                // Nobody wants this chunk any more (a seek elsewhere, or everyone left).
                                if (target() != i) return stored
                            }
                        }
                        try {
                            out.fd.sync()
                        } catch (e: IOException) {
                            throw StorageError(e)
                        }
                    }
                    synchronized(this) {
                        // Once gone, the folder may belong to a new entry, and this part to that entry's filler.
                        if (gone) return stored
                        if (!partFile(i).renameTo(chunkFile(i))) throw StorageError(IOException("rename failed"))
                        present.set(i)
                        writing = -1
                        written = 0
                        stored = true
                        (this as Object).notifyAll()
                    }
                    touch()
                }
            }
            return stored
        }
    }
}
