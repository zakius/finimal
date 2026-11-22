package com.zakius.finimal.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** The part of libmpv's client and render API ([client.h], [render.h], mpv 0.41) the player uses. */
@Suppress("FunctionName")
private interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    /** [args] ends with null. */
    fun mpv_command_async(ctx: Pointer, replyUserdata: Long, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, data: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    fun interface UpdateCallback : Callback { fun invoke(data: Pointer?) }
}

@Suppress("FunctionName")
private interface LibC : Library { fun setlocale(category: Int, locale: String): Pointer? }

private object Lib {
    /** Linux runtime packages ship only the versioned name (Debian's libmpv2 has libmpv.so.2), and Windows builds name the DLL libmpv-2. */
    val mpv: LibMpv by lazy {
        val os = System.getProperty("os.name")
        // Windows has no system libmpv, so the app ships one in its resources folder. A newer libmpv-2.dll in
        // %APPDATA%\finimal takes precedence, so mpv can be updated without a new finimal release.
        val appData = System.getenv("APPDATA")
        val userDir = if (os.startsWith("Windows") && appData != null) "$appData\\finimal" else null
        val dirs = listOfNotNull(userDir, System.getProperty("compose.application.resources.dir"))
        if (dirs.isNotEmpty()) System.setProperty("jna.library.path", dirs.joinToString(java.io.File.pathSeparator))
        // libmpv refuses to start unless numbers parse the C way, but the JVM sets the user's locale, which may use a
        // decimal comma. Windows libmpv has no such check.
        if (!os.startsWith("Windows")) {
            val libc = Native.load("c", LibC::class.java)
            val category = if (os.startsWith("Mac")) LC_NUMERIC_MAC else LC_NUMERIC_LINUX
            libc.setlocale(category, "C")
        }
        val names = listOf("mpv", "libmpv.so.2", "libmpv-2", "mpv-2", "mpv-1")
        val loaded = names.firstNotNullOfOrNull { name -> runCatching { Native.load(name, LibMpv::class.java) }.getOrNull() }
        loaded ?: throw UnsatisfiedLinkError("libmpv not found (tried ${names.joinToString()}). On Linux install libmpv2.")
    }
}

private const val LC_NUMERIC_LINUX = 1
private const val LC_NUMERIC_MAC = 4
private const val FORMAT_STRING = 1
private const val FORMAT_FLAG = 3
private const val FORMAT_DOUBLE = 5
private const val EVENT_SHUTDOWN = 1
private const val EVENT_END_FILE = 7
private const val EVENT_FILE_LOADED = 8
private const val EVENT_PROPERTY_CHANGE = 22
private const val END_FILE_REASON_ERROR = 4
private const val RENDER_PARAM_API_TYPE = 1
private const val RENDER_PARAM_SW_SIZE = 17
private const val RENDER_PARAM_SW_FORMAT = 18
private const val RENDER_PARAM_SW_STRIDE = 19
private const val RENDER_PARAM_SW_POINTER = 20
private const val RENDER_UPDATE_FRAME = 1L

/**
 * One libmpv player drawing into Skia bitmaps through mpv's software renderer, so Compose draws our controls over it.
 * Nothing here blocks the UI thread. Commands run async, properties arrive as observed events, and frames render on
 * their own thread. The Android mpv build froze when it read properties on the main thread while the core was busy.
 */
class Mpv(options: Map<String, String>) {
    private val lib = Lib.mpv
    private val ctx: Pointer = lib.mpv_create() ?: error("mpv_create failed")

    var position by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var cachedUntil by mutableDoubleStateOf(0.0); private set
    var paused by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var eof by mutableStateOf(false); private set
    var speed by mutableDoubleStateOf(1.0); private set
    var volume by mutableDoubleStateOf(100.0); private set
    var muted by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    /** Set when a file ends with an error; cleared by the next [load]. */
    var error by mutableStateOf<String?>(null); private set
    /** Bumped when the track list changes, so menus re-read [tracks]. */
    var tracksChanged by mutableIntStateOf(0); private set
    var frame by mutableStateOf<ImageBitmap?>(null); private set

    /** Runs on the event thread once a file is loaded, to add external subtitles and pick tracks. */
    var onLoaded: () -> Unit = {}

    private var render: Pointer? = null
    private val renderThread: Thread
    private val eventThread: Thread
    private val handle = ReentrantReadWriteLock()
    private val renderSignal = Any()
    @Volatile private var dirty = false
    @Volatile private var closed = false
    @Volatile private var targetW = 0
    @Volatile private var targetH = 0
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun signalRender() = (renderSignal as Object).notifyAll()

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun awaitRender() = (renderSignal as Object).wait()

    // Held in a field because mpv's native reference doesn't keep it alive: JNA would garbage-collect a callback nothing in the JVM references.
    private val updateCallback = LibMpv.UpdateCallback {
        synchronized(renderSignal) {
            dirty = true
            signalRender()
        }
    }

    init {
        // Config files and key bindings would only get in the way of an embedded player. Scripts are off because the
        // built-in ones do nothing useful here but cost start time. The ytdl hook would even run yt-dlp on
        // extension-less http URLs like the scratch proxy's.
        val defaults = mapOf("config" to "no", "input-default-bindings" to "no", "osc" to "no", "load-scripts" to "no", "ytdl" to "no",
            "vo" to "libmpv", "keep-open" to "yes", "hwdec" to "auto-copy", "idle" to "yes", "terminal" to "no")
        val logFile = System.getenv("FINIMAL_MPV_LOG")
        val log = if (logFile != null) mapOf("log-file" to logFile, "msg-level" to "all=v") else emptyMap()
        (defaults + options + log).forEach { (k, v) -> lib.mpv_set_option_string(ctx, k, v) }
        check(lib.mpv_initialize(ctx) >= 0) { "mpv_initialize failed" }
        listOf("time-pos" to FORMAT_DOUBLE, "duration" to FORMAT_DOUBLE, "demuxer-cache-time" to FORMAT_DOUBLE, "pause" to FORMAT_FLAG,
            "paused-for-cache" to FORMAT_FLAG, "seeking" to FORMAT_FLAG, "eof-reached" to FORMAT_FLAG, "speed" to FORMAT_DOUBLE,
            "volume" to FORMAT_DOUBLE, "mute" to FORMAT_FLAG, "track-list/count" to FORMAT_DOUBLE, "core-idle" to FORMAT_FLAG,
        ).forEach { (name, format) -> lib.mpv_observe_property(ctx, 0, name, format) }
        val apiType = Memory(3).apply { setString(0, "sw") }
        val params = renderParams(listOf(RENDER_PARAM_API_TYPE to apiType))
        val ref = PointerByReference()
        check(lib.mpv_render_context_create(ref, ctx, params) >= 0) { "mpv_render_context_create failed" }
        Reference.reachabilityFence(apiType)
        render = ref.value
        lib.mpv_render_context_set_update_callback(render!!, updateCallback, null)
        eventThread = thread(name = "mpv-events", isDaemon = true) { events() }
        renderThread = thread(name = "mpv-render", isDaemon = true) { renderLoop() }
    }

    private var seekingNow = false
    private var cacheWait = false
    private var coreIdle = true

    private fun events() {
        while (true) {
            val e = lib.mpv_wait_event(ctx, -1.0)
            if (closed) return
            when (e.getInt(0)) {
                EVENT_SHUTDOWN -> return
                EVENT_FILE_LOADED -> {
                    onLoaded()
                    loaded = true
                    tracksChanged++
                }
                EVENT_END_FILE -> {
                    val d = e.getPointer(16)
                    if (d != null) {
                        loaded = false
                        if (d.getInt(0) == END_FILE_REASON_ERROR) error = "Playback failed (mpv error ${d.getInt(4)})."
                    }
                }
                EVENT_PROPERTY_CHANGE -> {
                    val p = e.getPointer(16) ?: continue
                    val name = p.getPointer(0).getString(0)
                    val data = p.getPointer(16)
                    val format = p.getInt(8)
                    val d = if (format == FORMAT_DOUBLE && data != null) data.getDouble(0) else null
                    val f = if (format == FORMAT_FLAG && data != null) data.getInt(0) != 0 else null
                    when (name) {
                        "time-pos" -> {
                            val seconds = d ?: 0.0
                            if (kotlin.math.abs(seconds - position) >= 0.25 || seconds == 0.0) position = seconds
                        }
                        "duration" -> duration = d ?: 0.0
                        "demuxer-cache-time" -> cachedUntil = d ?: 0.0
                        "pause" -> paused = f ?: false
                        "paused-for-cache" -> cacheWait = f ?: false
                        "seeking" -> seekingNow = f ?: false
                        "core-idle" -> coreIdle = f ?: true
                        "eof-reached" -> eof = f ?: false
                        "speed" -> speed = d ?: 1.0
                        "volume" -> volume = d ?: 100.0
                        "mute" -> muted = f ?: false
                        "track-list/count" -> tracksChanged++
                    }
                    buffering = !loaded || cacheWait || seekingNow
                }
            }
        }
    }

    /** Sets the size, in pixels, that frames render at, which is the video area. */
    fun resize(width: Int, height: Int) {
        if (width == targetW && height == targetH) return
        targetW = width
        targetH = height
        synchronized(renderSignal) {
            dirty = true
            signalRender()
        }
    }

    // Keeps up to three bitmaps, because Compose may still be drawing the previous one while the next is written.
    private val bitmaps = ArrayDeque<Bitmap>()

    private fun renderLoop() {
        var current: Bitmap? = null
        val sizeMem = Memory(8)
        val formatMem = Memory(5).apply { setString(0, "bgr0") }
        val strideMem = Memory(8)
        val params = Memory(16L * 5).apply {
            clear()
            setInt(0L, RENDER_PARAM_SW_SIZE)
            setPointer(8L, sizeMem)
            setInt(16L, RENDER_PARAM_SW_FORMAT)
            setPointer(24L, formatMem)
            setInt(32L, RENDER_PARAM_SW_STRIDE)
            setPointer(40L, strideMem)
            setInt(48L, RENDER_PARAM_SW_POINTER)
        }
        while (true) {
            synchronized(renderSignal) {
                while (!dirty && !closed) awaitRender()
                dirty = false
            }
            if (closed) return
            val r = render ?: return
            val flags = lib.mpv_render_context_update(r)
            val w = targetW
            val h = targetH
            if (w <= 0 || h <= 0) continue
            // mpv only announces new frames, so after a resize, redraw the last frame at the new size.
            val newFrame = (flags and RENDER_UPDATE_FRAME) != 0L
            if (!newFrame && current != null && current.width == w && current.height == h) continue
            val spare = bitmaps.removeFirstOrNull()
            val bitmap = if (spare != null && spare.width == w && spare.height == h) {
                spare
            } else {
                Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), roundUp(w * 4, 64)) }
            }
            val pixels = bitmap.peekPixels() ?: continue
            sizeMem.setInt(0, w)
            sizeMem.setInt(4, h)
            strideMem.setLong(0, bitmap.rowBytes.toLong())
            params.setPointer(56L, Pointer(pixels.addr))
            if (closed) return
            lib.mpv_render_context_render(r, params)
            // mpv reads these during the native call, so keep the backing allocations reachable until it returns.
            Reference.reachabilityFence(sizeMem)
            Reference.reachabilityFence(formatMem)
            Reference.reachabilityFence(strideMem)
            Reference.reachabilityFence(pixels)
            bitmap.notifyPixelsChanged()
            if (current != null) bitmaps.addLast(current)
            while (bitmaps.size > 2) bitmaps.removeFirst().close()
            current = bitmap
            frame = bitmap.asComposeImageBitmap()
        }
    }

    private fun roundUp(n: Int, multiple: Int) = (n + multiple - 1) / multiple * multiple

    /** Builds an mpv_render_param[] ending with a zero entry. Each { int type; void *data } entry is 16 bytes on 64-bit. */
    private fun renderParams(list: List<Pair<Int, Pointer>>): Memory {
        val m = Memory(16L * (list.size + 1)).apply { clear() }
        list.forEachIndexed { i, (type, data) ->
            m.setInt(16L * i, type)
            m.setPointer(16L * i + 8, data)
        }
        return m
    }

    fun command(vararg args: String) = handle.read {
        if (!closed) lib.mpv_command_async(ctx, 0, arrayOf(*args, null))
    }

    fun set(name: String, value: String) = command("set", name, value)

    fun load(url: String, startSeconds: Double) {
        error = null
        loaded = false
        // Set the start property rather than pass loadfile's per-file options. Their argument position moved in mpv 0.38,
        // which added an index before them, and 0.37 (Ubuntu 24.04) rejects the newer form. Commands run in order, so
        // the start is set by the time the file opens.
        set("start", "$startSeconds")
        command("loadfile", url, "replace")
    }

    /** The user's pause. mpv also pauses on its own at the end of a file (keep-open), which leaves this false. */
    var pauseWanted = false; private set
    fun play() {
        pauseWanted = false
        set("pause", "no")
    }
    fun pause() {
        pauseWanted = true
        set("pause", "yes")
    }
    fun seek(seconds: Double, exact: Boolean = true) = command("seek", "$seconds", if (exact) "absolute+exact" else "absolute+keyframes")

    /** Reads a property. Call it from the event thread or menus, never on the UI thread's frame path. */
    fun get(name: String): String? = handle.read {
        if (closed) return null
        val p = lib.mpv_get_property_string(ctx, name) ?: return null
        val value = p.getString(0)
        lib.mpv_free(p)
        value
    }

    /** [fileIndex] is the stream's index in the file, which the server uses too. It is null for a subtitle added from a file. */
    class Track(val id: String, val type: String, val language: String?, val title: String?, val selected: Boolean, val channels: Int?,
        val fileIndex: Int?)

    /** Reads the track list. It blocks on the core, so call it off the UI thread. */
    fun tracks(): List<Track> {
        val count = get("track-list/count")?.toIntOrNull() ?: 0
        return (0 until count).map { i ->
            val p = "track-list/$i/"
            // An added file reports index 0, its place in that file.
            val fileIndex = if (get("${p}external") == "yes") null else get("${p}ff-index")?.toIntOrNull()
            Track(get("${p}id").orEmpty(), get("${p}type").orEmpty(), get("${p}lang"), get("${p}title"), get("${p}selected") == "yes",
                get("${p}demux-channel-count")?.toIntOrNull(), fileIndex)
        }
    }

    /** Frees mpv off the calling thread, because shutting the core down waits for its threads. */
    fun close() {
        if (closed) return
        closed = true
        synchronized(renderSignal) { signalRender() }
        closer.execute {
            renderThread.join()
            lib.mpv_wakeup(ctx)
            eventThread.join()
            handle.write {
                val r = render
                if (r != null) {
                    lib.mpv_render_context_set_update_callback(r, null, null)
                    lib.mpv_render_context_free(r)
                }
                render = null
                lib.mpv_terminate_destroy(ctx)
            }
        }
    }

    companion object {
        private val closer = Executors.newSingleThreadExecutor { task -> Thread(task, "mpv-close").apply { isDaemon = true } }
        fun warmLibrary() {
            runCatching { Lib.mpv }
        }
    }
}
