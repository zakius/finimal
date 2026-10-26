package finimal.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

class ScratchTest {
    private val chunk = 64 * 1024
    /** Not a multiple of the chunk size, so the last chunk is short. */
    private val content = ByteArray(16 * chunk + 1234) { (it * 31 % 251).toByte() }

    // Origin knobs.
    @Volatile private var honorRange = true
    @Volatile private var status = 200
    @Volatile private var failFirst = 0
    @Volatile private var dropAfter = -1L
    @Volatile private var bytesPerSecond = Long.MAX_VALUE
    @Volatile private var hang = false
    private val starts = CopyOnWriteArrayList<Long>()

    private val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/f") { ex ->
            try {
                val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLong()
                starts += from ?: 0
                if (hang) Thread.sleep(60_000)
                if (failFirst > 0) { failFirst--; ex.sendResponseHeaders(503, -1); return@createContext }
                if (status != 200) { ex.sendResponseHeaders(status, -1); return@createContext }
                val start = if (from != null && honorRange) from.toInt() else 0
                if (from != null && honorRange) {
                    ex.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    ex.sendResponseHeaders(206, (content.size - start).toLong())
                } else ex.sendResponseHeaders(200, content.size.toLong())
                val drop = dropAfter.also { dropAfter = -1 }
                var pos = start
                val began = System.nanoTime()
                while (pos < content.size) {
                    val n = minOf(8192, content.size - pos)
                    if (drop >= 0 && pos - start >= drop) { ex.close(); return@createContext } // cut mid-body
                    ex.responseBody.write(content, pos, n)
                    ex.responseBody.flush()
                    pos += n
                    if (bytesPerSecond != Long.MAX_VALUE) {
                        val due = (pos - start) * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - began)
                        if (due > 0) Thread.sleep(due / 1_000_000, (due % 1_000_000).toInt())
                    }
                }
            } catch (e: IOException) {
                // The client went away: a seek, or a cancelled fetch.
            } finally { ex.close() }
        }
        start()
    }
    private val url = "http://127.0.0.1:${origin.address.port}/f"
    private val http = OkHttpClient()
    private val dir = Files.createTempDirectory("scratch").toFile()
    private var scratch = Scratch(dir, http, chunk)
    private var server = ScratchServer({ scratch }, ahead = 4L * chunk, waitMs = 5_000)

    @AfterTest fun stop() {
        server.close(); scratch.close(); origin.stop(0); dir.deleteRecursively()
    }

    private fun entry(key: String = "k", size: Long? = content.size.toLong()) = scratch.entry(key).apply { source({ url }, size) }

    private fun get(key: String = "k", range: String? = null) =
        http.newCall(Request.Builder().url(server.url(key)).apply { range?.let { header("Range", it) } }.build()).execute()

    private fun slice(from: Int, to: Int = content.size) = content.copyOfRange(from, to)

    private fun reopen() {
        server.close(); scratch.close()
        scratch = Scratch(dir, http, chunk)
        server = ScratchServer({ scratch }, ahead = 4L * chunk, waitMs = 5_000)
    }

    @Test fun streamsWholeFileThroughProxy() {
        entry()
        get().use { r ->
            assertEquals(200, r.code)
            assertEquals("${content.size}", r.header("Content-Length"))
            assertContentEquals(content, r.body.bytes())
        }
    }

    @Test fun rangeFromMiddleStartsAtItsChunk() {
        entry()
        get(range = "bytes=500000-").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 500000-${content.size - 1}/${content.size}", r.header("Content-Range"))
            assertContentEquals(slice(500000), r.body.bytes())
        }
        assertEquals(500000L / chunk * chunk, starts.first())
    }

    @Test fun closedRangeReturnsJustThoseBytes() {
        entry()
        get(range = "bytes=100-199").use { r ->
            assertEquals(206, r.code)
            assertContentEquals(slice(100, 200), r.body.bytes())
        }
    }

    @Test fun downloadedFilePlaysOfflineAfterRestart() {
        entry().download()
        assertTrue(scratch.entry("k").complete)
        origin.stop(0)
        reopen()
        val e = entry()
        assertTrue(e.complete)
        assertEquals(content.size.toLong(), e.bytes())
        get(range = "bytes=70000-").use { r -> assertContentEquals(slice(70000), r.body.bytes()) }
    }

    @Test fun readerGetsBytesBeforeTheirChunkIsWhole() {
        bytesPerSecond = 32 * 1024 // a chunk takes 2 s
        entry()
        val began = System.currentTimeMillis()
        get().use { r ->
            val first = ByteArray(8192)
            r.body.byteStream().readNBytes(first, 0, first.size)
            assertTrue(System.currentTimeMillis() - began < 1_500, "first bytes took ${System.currentTimeMillis() - began} ms")
            assertContentEquals(slice(0, 8192), first)
        }
    }

    @Test fun seekElsewherePreemptsTheFill() {
        bytesPerSecond = 64 * 1024 // a chunk a second
        entry()
        val slow = get()
        slow.body.byteStream().read(ByteArray(10)) // a reader at the start keeps its demand open
        val began = System.currentTimeMillis()
        get(range = "bytes=${12 * chunk}-").use { r ->
            val first = ByteArray(1000)
            r.body.byteStream().readNBytes(first, 0, first.size)
            assertContentEquals(slice(12 * chunk, 12 * chunk + 1000), first)
        }
        assertTrue(System.currentTimeMillis() - began < 2_000, "seek took ${System.currentTimeMillis() - began} ms")
        assertTrue(12L * chunk in starts)
        slow.close()
    }

    @Test fun downloadAndReaderShareTheFill() {
        entry()
        val done = thread { scratch.entry("k").download() }
        get(range = "bytes=300000-").use { r -> assertContentEquals(slice(300000), r.body.bytes()) }
        done.join(10_000)
        assertTrue(scratch.entry("k").complete)
        reopen()
        entry()
        get().use { r -> assertContentEquals(content, r.body.bytes()) }
    }

    @Test fun droppedConnectionResumesAtTheFirstMissingChunk() {
        dropAfter = 3L * chunk + 100
        entry().download()
        assertTrue(starts.size >= 2, "starts $starts")
        assertEquals(3L * chunk, starts[1])
        reopen()
        entry()
        get().use { r -> assertContentEquals(content, r.body.bytes()) }
    }

    @Test fun leftoverPartIsDroppedAndFetchedAgain() {
        val folder = File(dir, "k").apply { mkdirs() }
        File(folder, "length").writeText("${content.size}")
        File(folder, "0").writeBytes(slice(0, chunk))
        File(folder, "1.part").writeBytes(ByteArray(500) { 9 })
        val e = entry()
        assertTrue(e.has(0))
        assertFalse(e.has(1))
        assertFalse(File(folder, "1.part").exists())
        e.download()
        assertFalse(0L in starts, "chunk 0 was on disk")
        reopen()
        entry()
        get().use { r -> assertContentEquals(content, r.body.bytes()) }
    }

    @Test fun refusedFileFailsAtOnce() {
        status = 404
        val began = System.currentTimeMillis()
        assertEquals(404, assertFailsWith<HttpStatus> { entry().download() }.code)
        assertTrue(System.currentTimeMillis() - began < 2_000)
        get().use { r -> assertEquals(404, r.code) }
    }

    @Test fun wrongSizeIsRejected() {
        assertFailsWith<WrongFile> { entry(size = content.size + 1L).download() }
    }

    @Test fun passingServerErrorsAreRetried() {
        failFirst = 2
        entry().download()
        assertTrue(scratch.entry("k").complete)
    }

    @Test fun serverIgnoringRangeStillFillsCorrectly() {
        honorRange = false
        val e = entry()
        e.setLength(content.size.toLong())
        e.putChunk(1, slice(chunk, 2 * chunk))
        e.download()
        reopen()
        entry()
        get().use { r -> assertContentEquals(content, r.body.bytes()) }
    }

    @Test fun hangingOriginTimesOutTheReader() {
        hang = true
        server.close()
        server = ScratchServer({ scratch }, ahead = 4L * chunk, waitMs = 500)
        entry()
        val began = System.currentTimeMillis()
        get().use { r -> assertEquals(504, r.code) }
        assertTrue(System.currentTimeMillis() - began < 3_000)
    }

    @Test fun unreachableServerFailsFastAndRecovers() {
        server.close()
        server = ScratchServer({ scratch }, ahead = 4L * chunk, waitMs = 20_000)
        val port = origin.address.port
        origin.stop(0) // connections refused, as with no network
        entry()
        val began = System.currentTimeMillis()
        get().use { r -> assertEquals(504, r.code) }
        assertTrue(System.currentTimeMillis() - began < 3_000, "took ${System.currentTimeMillis() - began} ms")
        // The network comes back: the player's next request gets the file.
        val back = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/f") { ex ->
                val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                ex.responseHeaders.add("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
                ex.sendResponseHeaders(206, (content.size - from).toLong())
                ex.responseBody.write(content, from, content.size - from)
                ex.close()
            }
            start()
        }
        try {
            get().use { r -> assertContentEquals(content, r.body.bytes()) }
        } finally { back.stop(0) }
    }

    @Test fun deleteWaitsUntilNobodyHasItOpen() {
        val e = entry()
        val d = e.demand(0, Long.MAX_VALUE, Scratch.Priority.BUFFER)
        assertFalse(scratch.delete("k"))
        d.close()
        assertTrue(scratch.delete("k"))
        assertFalse(File(dir, "k").exists())
    }

    @Test fun playerHangingUpMidBodyLeavesTheServerUp() {
        val escaped = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        try {
            bytesPerSecond = 256 * 1024
            entry()
            repeat(3) {
                get().use { r -> r.body.byteStream().read(ByteArray(1000)) } // closes with most of the body unsent
            }
            Thread.sleep(500)
            bytesPerSecond = Long.MAX_VALUE
            get(range = "bytes=1000-").use { r -> assertContentEquals(slice(1000), r.body.bytes()) }
            assertEquals(emptyList(), escaped.toList())
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
    }

    @Test fun unknownKeyAndWrongTokenAre404() {
        get("nope").use { r -> assertEquals(404, r.code) }
        http.newCall(Request.Builder().url(server.url("k").replaceAfterLast(':', "1/x/k").let { server.url("k").substringBeforeLast('/').substringBeforeLast('/') + "/x/k" }).build())
            .execute().use { r -> assertEquals(404, r.code) }
    }

    @Test fun contiguousCountsWholeChunksFromAPosition() {
        val e = entry()
        e.setLength(content.size.toLong())
        e.putChunk(2, slice(2 * chunk, 3 * chunk))
        e.putChunk(3, slice(3 * chunk, 4 * chunk))
        assertEquals(0, e.contiguous(0))
        assertEquals(2L * chunk - 10, e.contiguous(2L * chunk + 10))
        assertEquals(2L * chunk, e.bytes())
    }
}
