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

/** The server answered, and refused: worth retrying only for 5xx (and 401, a stale token). */
class HttpStatus(val code: Int) : IOException("HTTP $code")

/** The server's file is not the size it lists: a captive portal or proxy page, or a file replaced mid-download. */
class WrongFile(message: String) : IOException(message)

/** A chunk could not be written: the disk is full or gone. */
class StorageError(cause: IOException) : IOException(cause.message, cause)

/**
 * Server files kept on disk in fixed-size chunks, for streaming and downloads alike: what one fetched, the other
 * reads. An entry is a folder per file version (its key): `length`, then `0`, `1`… for whole chunks and `<n>.part`
 * for the one being written. A chunk file appears only once all of it is on disk and synced, so a crash leaves at
 * worst a `.part`, fetched again.
 *
 * One filler thread per entry fetches what its open [Demand]s ask for. Readers never fetch: they wait for the
 * filler and read the chunk it is writing as the bytes land.
 */
class Scratch(val dir: File, private val http: OkHttpClient, val chunkSize: Int = 4 shl 20) {
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
    fun keys(): List<String> = dir.list().orEmpty().filter { File(dir, it).isDirectory }

    /** Removes [key]'s bytes unless a reader, buffer or download has it open; returns whether it did. */
    fun delete(key: String): Boolean {
        val e = synchronized(this) { entries[key] }
        if (e != null && !e.release()) return false
        // Under the lock: an entry() for the key in between would count the chunks being deleted as present.
        synchronized(this) { entries.remove(key); File(dir, key).deleteRecursively() }
        return true
    }

    /** Stops every filler and reader; the files stay. For a storage switch. */
    fun close() {
        val all = synchronized(this) { closed = true; entries.values.toList() }
        all.forEach { it.shutDown() }
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
        var length = File(folder, "length").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1L
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
        /** The last failure; [fatal] when retrying can't help (refused, wrong file, disk). */
        private var error: IOException? = null
        private var fatal = false
        /** The disk filled up: only readers get chunks written until the next demand. */
        private var lowSpace = false
        private var gone = false
        private var touched = 0L

        init {
            for (f in folder.listFiles().orEmpty()) {
                val i = f.name.toIntOrNull()
                if (i != null) present.set(i) else if (f.name.endsWith(".part") || f.name.endsWith(".import")) f.delete()
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
            expectedSize = size?.takeIf { it > 0 }
        }

        @get:Synchronized val complete get() = length >= 0 && present.cardinality() == chunks()

        /** Bytes of whole chunks on disk. */
        @Synchronized
        fun bytes(): Long {
            if (length < 0) return 0
            var sum = 0L
            var i = present.nextSetBit(0)
            while (i >= 0) { sum += chunkLength(i); i = present.nextSetBit(i + 1) }
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
            while (i < chunks() && present[i]) end += chunkLength(i++)
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

        /** The demands changed: the filler re-picks, and drops a fetch nobody wants any more. */
        @Synchronized
        internal fun wake() {
            (this as Object).notifyAll()
            if (writing >= 0 && target() != writing) call?.cancel()
        }

        /** True when nothing has it open; it is then closed for good. */
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

        /** Sets the length of a file whose chunks come from elsewhere (a migration), before [putChunk]. */
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

        /** Stores chunk [i] whole, from outside the filler. */
        fun putChunk(i: Int, bytes: ByteArray) {
            synchronized(this) { require(bytes.size.toLong() == chunkLength(i)) { "chunk $i is ${chunkLength(i)} bytes, not ${bytes.size}" } }
            val part = File(folder, "$i.import")
            FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
            if (!part.renameTo(chunkFile(i))) throw IOException("rename failed")
            synchronized(this) { present.set(i); (this as Object).notifyAll() }
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
                            // The filler's last try failed (no network, server down): say so now, not at the deadline,
                            // so the player reports it. Its retry is a new request, whose demand wakes the filler.
                            error != null && writing != i -> throw InterruptedIOException("no data: ${error!!.message}")
                        }
                        val wait = deadline - System.currentTimeMillis()
                        if (wait <= 0) throw error?.let { InterruptedIOException("no data: ${it.message}") } ?: InterruptedIOException("no data")
                        (this as Object).wait(wait)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                // The part can be renamed to its chunk, or dropped and begun again, between the check and the open.
                // A FileChannel, not a RandomAccessFile: on Windows only it lets the filler rename the part while open.
                val n = try {
                    FileChannel.open(file.toPath()).use { it.read(ByteBuffer.wrap(buf, off, minOf(len.toLong(), available).toInt()), inChunk) }
                } catch (e: IOException) {
                    // AccessDenied: on Windows, a part being deleted. Retried, though not past the deadline.
                    if (e !is NoSuchFileException && e !is AccessDeniedException || System.currentTimeMillis() > deadline) throw e
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
                error?.let { throw InterruptedIOException("no answer: ${it.message}") }
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw InterruptedIOException("no answer")
                (this as Object).wait(wait)
            }
            length
        }

        /**
         * Fills the whole file, reporting bytes on disk and the length. Throws a fatal failure at once, and a passing
         * one (network) once [stallMs] go by without a new chunk. Interruptible.
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
                    if (bytes != lastBytes) { lastBytes = bytes; lastProgress = now; onProgress(bytes, total) }
                    synchronized(this) {
                        error?.let { if (now - lastProgress > stallMs) throw it }
                        if (!complete) (this as Object).wait(1_000)
                    }
                }
            }
        }

        private fun touch() {
            val now = System.currentTimeMillis()
            // A directory's mtime only, and at most once a minute.
            if (now - touched > 60_000) { touched = now; folder.setLastModified(now) }
        }

        /** The next chunk to fetch: the first missing one in the most pressing demand's range. Readers go newest first. */
        private fun target(): Int? {
            if (gone || fatal) return null
            val order = demands.filter { !lowSpace || it.priority == Priority.READ }
                .sortedWith(compareBy<Demand> { it.priority }.thenByDescending { it.seq })
            if (length < 0) return order.firstOrNull()?.let { (it.position / chunkSize).toInt() }
            for (d in order) {
                if (d.position >= length) continue
                val first = (d.position / chunkSize).toInt()
                val last = if (d.ahead == Long.MAX_VALUE) chunks() - 1
                    else (minOf(length, d.position + d.ahead).minus(1) / chunkSize).toInt()
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
                        if (demands.isEmpty() || gone) { filler = null; return }
                        (this as Object).wait()
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                try {
                    if (fetch(start)) attempt = 0
                } catch (e: StorageError) {
                    if (onStorageFull()) continue
                    synchronized(this) {
                        // Buffering and downloading stop; a reader still gets its chunks written while there is room.
                        if (lowSpace || demands.none { it.priority == Priority.READ }) { error = e; fatal = true } else lowSpace = true
                        (this as Object).notifyAll()
                    }
                } catch (e: WrongFile) {
                    fail(e)
                } catch (e: HttpStatus) {
                    // Refused: a 4xx stays refused. A 401 is the token, and 5xx the server: worth waiting out.
                    if (e.code in 400..499 && e.code != 401) fail(e) else backOff(e, attempt++)
                } catch (e: IOException) {
                    if (synchronized(this) { call?.isCanceled() == true }) continue
                    backOff(e, attempt++)
                } finally {
                    synchronized(this) {
                        call = null
                        // Once gone, the folder may be a new entry's: the next one's init drops a leftover part.
                        if (writing >= 0) { if (!gone) partFile(writing).delete(); writing = -1; written = 0 }
                    }
                }
            }
        }

        private fun fail(e: IOException) = synchronized(this) { error = e; fatal = true; (this as Object).notifyAll() }

        private fun backOff(e: IOException, attempt: Int) = synchronized(this) {
            error = e
            (this as Object).notifyAll()
            // A new demand (a seek, a retry) wakes it early.
            if (!gone && demands.isNotEmpty()) (this as Object).wait(minOf(30_000L, 1_000L shl minOf(attempt, 5)))
        }

        /** Fetches from chunk [start] on while the demands want what comes next. Returns whether a chunk was stored. */
        private fun fetch(start: Int): Boolean {
            val from = start.toLong() * chunkSize
            val c = synchronized(this) {
                http.newCall(Request.Builder().url(url!!()).header("Range", "bytes=$from-").build()).also { call = it }
            }
            var stored = false
            c.execute().use { r ->
                if (!r.isSuccessful) throw HttpStatus(r.code)
                val ranged = r.code == 206
                val range = r.header("Content-Range")
                val begin = if (ranged) range?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() else 0L
                if (begin != (if (ranged) from else 0L)) throw IOException("unexpected range $range")
                val total = if (ranged) range!!.substringAfter('/').toLongOrNull() ?: -1 else r.body.contentLength()
                synchronized(this) {
                    val expected = if (length >= 0) length else expectedSize
                    if (total < 0 || expected != null && total != expected) throw WrongFile("$total bytes, expected $expected")
                    if (length < 0) {
                        try { folder.mkdirs(); File(folder, "length").writeAtomically("$total") } catch (e: IOException) { throw StorageError(e) }
                        length = total
                        (this as Object).notifyAll()
                    }
                }
                val input = r.body.byteStream()
                val buf = ByteArray(1 shl 16)
                var pos = begin!!
                while (pos < length) {
                    val i = (pos / chunkSize).toInt()
                    val end = i.toLong() * chunkSize + chunkLength(i)
                    val have = synchronized(this) {
                        when {
                            present[i] -> true
                            target() == i -> { writing = i; written = 0; false }
                            // A server ignoring the range sends from byte 0: skip to the wanted chunk, not ask again at once.
                            !ranged && target().let { it != null && i < it } -> true
                            else -> return stored
                        }
                    }
                    if (have) {
                        // Only a server ignoring the range sends a chunk already here or not wanted; later ones may be.
                        if (ranged) return stored
                        while (pos < end) pos += input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt()).also { if (it < 0) throw IOException("ended at $pos") }
                        continue
                    }
                    val out = try { FileOutputStream(partFile(i)) } catch (e: IOException) { throw StorageError(e) }
                    out.use {
                        while (pos < end) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                            if (n < 0) throw IOException("ended at $pos of $length bytes")
                            try { out.write(buf, 0, n) } catch (e: IOException) { throw StorageError(e) }
                            pos += n
                            synchronized(this) {
                                written += n
                                (this as Object).notifyAll()
                                // Nobody wants this chunk any more (a seek elsewhere, or everyone left).
                                if (target() != i) return stored
                            }
                        }
                        try { out.fd.sync() } catch (e: IOException) { throw StorageError(e) }
                    }
                    synchronized(this) {
                        // Once gone, the folder may be a new entry's, and the part its filler's.
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
