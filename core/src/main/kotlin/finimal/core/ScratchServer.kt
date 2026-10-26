package finimal.core

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Serves [Scratch] entries over HTTP on the loopback interface, so any player streams through the chunks on disk.
 * Each request is a reader [Scratch.Demand]: the filler fetches [ahead] bytes in front of what was sent. The path
 * carries a random token: other apps on the device can reach a loopback port too.
 */
class ScratchServer(private val scratch: () -> Scratch, private val ahead: Long = 64L shl 20, private val waitMs: Long = 20_000) : Closeable {
    // Not getLoopbackAddress(): on Android that can be ::1, which the 127.0.0.1 in [url] doesn't reach.
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString()

    init {
        thread(name = "scratch-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                thread(name = "scratch-client", isDaemon = true) {
                    // The player hangs up mid-body all the time (a seek, probing the index at the end). On Android an
                    // exception escaping a thread ends the app.
                    try { client.use(::serve) } catch (e: IOException) {}
                }
            }
        }
    }

    /** For an entry that has its [Scratch.Entry.source] set. */
    fun url(key: String) = "http://127.0.0.1:${socket.localPort}/$token/$key"

    override fun close() = socket.close()

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine()?.split(' ') ?: return
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val out = client.getOutputStream()
        fun respond(status: String, vararg lines: String) =
            out.write((listOf("HTTP/1.1 $status", *lines, "Connection: close", "", "").joinToString("\r\n")).toByteArray())
        val method = request.getOrNull(0)
        val key = request.getOrNull(1)?.takeIf { it.startsWith("/$token/") }?.substringAfter("/$token/")
        if (key == null || key.isEmpty() || '/' in key || method != "GET" && method != "HEAD") return respond("404 Not Found", "Content-Length: 0")
        val entry = try { scratch().entry(key) } catch (e: IllegalStateException) { return respond("404 Not Found", "Content-Length: 0") }
        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLong() ?: 0
        val demand = try { entry.demand(from, ahead, Scratch.Priority.READ) } catch (e: IllegalStateException) {
            return respond("404 Not Found", "Content-Length: 0") // no source: not something this app opened
        }
        demand.use {
            val length = try { entry.awaitLength(waitMs) } catch (e: HttpStatus) {
                return respond("${e.code} Upstream", "Content-Length: 0")
            } catch (e: IOException) {
                return respond("504 Gateway Timeout", "Content-Length: 0")
            }
            if (from >= length && length > 0) return respond("416 Range Not Satisfiable", "Content-Range: bytes */$length", "Content-Length: 0")
            val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            val common = arrayOf("Content-Length: ${to - from + 1}", "Accept-Ranges: bytes", "Content-Type: application/octet-stream")
            if (range != null) respond("206 Partial Content", "Content-Range: bytes $from-$to/$length", *common)
            else respond("200 OK", *common)
            if (method == "HEAD") return
            val buf = ByteArray(1 shl 18)
            var pos = from
            // A failure after the headers can only cut the body short; the player sees that and reconnects.
            while (pos <= to) {
                val n = try { entry.read(pos, buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt(), waitMs) } catch (e: IOException) { return }
                if (n < 0) return
                out.write(buf, 0, n)
                pos += n
                demand.moveTo(pos)
            }
            out.flush()
        }
    }
}
