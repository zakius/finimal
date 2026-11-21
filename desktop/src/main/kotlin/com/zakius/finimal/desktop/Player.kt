package com.zakius.finimal.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import finimal.core.RECONNECT_WINDOW_MS
import finimal.core.Reconnect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zakius.finimal.App
import com.zakius.finimal.AUDIO_DELAYS_MS
import com.zakius.finimal.ErrorText
import com.zakius.finimal.episodeLabel
import com.zakius.finimal.burnInIndex
import finimal.core.chapterSeek
import finimal.core.chapterAt
import com.zakius.finimal.ChapterMarks
import com.zakius.finimal.chapters
import com.zakius.finimal.sideloadedSubs
import com.zakius.finimal.formatMs
import com.zakius.finimal.loadError
import com.zakius.finimal.localState
import com.zakius.finimal.nextEpisode
import com.zakius.finimal.playerTitle
import com.zakius.finimal.sameLanguage
import com.zakius.finimal.scratchKey
import com.zakius.finimal.startSubtitle
import finimal.core.ResumeGuard
import finimal.core.TICKS_PER_MS
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType

private const val REPORT_INTERVAL_MS = 10_000L
private const val RESUME_REWIND_MS = 5_000L
/** A file that ends this far before its duration was cut off by a dropped connection, not played to the end. */
private const val EARLY_END_MS = 10_000L
/** Buffering this long while the server doesn't answer counts as a dropped connection. */
private const val STALL_MS = 10_000L
/** How far before the resume position, in bytes, background buffering of the rest of the file starts. */
private const val BUFFER_MARGIN_BYTES = 32_000_000L

/** The chosen speed and volume, kept for the session so "Play next" does not reset them. */
private var sessionSpeed = 1.0
private var sessionVolume = 100.0

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
private val PauseIcon = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
private val NextIcon = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val FullscreenIcon = icon("Fullscreen", "M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
private val FullscreenExitIcon = icon("FullscreenExit", "M5,16h3v3h2v-5H5v2zm3,-8H5v2h5V5H8v3zm6,11h2v-3h3v-2h-5v5zm2,-11V5h-2v5h5V8h-3z")
private val RewindIcon = icon("FastRewind", "M11,18V6l-8.5,6 8.5,6zm0.5,-6l8.5,6V6l-8.5,6z")
private val ForwardIcon = icon("FastForward", "M4,18l8.5,-6L4,6v12zm9,-12v12l8.5,-6L13,6z")
private val VolumeUpIcon = icon("VolumeUp", "M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
private val VolumeOffIcon = icon("VolumeOff", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zm2.5,0c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z")

/** Loads the item and the episodes that can follow it, then plays. */
@Composable
fun PlayerScreen(itemId: String, onBack: () -> Unit, onPlay: (String) -> Unit) {
    var item by remember { mutableStateOf<BaseItemDto?>(null) }
    var siblings by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemId, retry) {
        error = null
        val server = App.server
        // Only this item's unsynced state must reach the server before the item is fetched. Other items sync in the background.
        val pending = withContext(App.disk) { App.pending.get(itemId) != null }
        if (!pending) App.platform.scope.launch { server.flush() }
        runCatching { server.item(itemId, flushFirst = pending) }
            .onSuccess { loaded ->
                if (loaded.mediaSources.isNullOrEmpty()) error = java.io.FileNotFoundException("This item has no file on the server")
                else item = loaded
            }
            .onFailure { e ->
                if (e is InvalidStatusException && e.status == 401) server.expire()
                error = e
            }
    }
    LaunchedEffect(item) {
        val loaded = item ?: return@LaunchedEffect
        val seriesId = loaded.seriesId?.toString()
        if (loaded.type == BaseItemKind.EPISODE && seriesId != null) {
            siblings = runCatching { App.server.episodes(seriesId) }.getOrDefault(emptyList())
        }
    }
    val current = item
    when {
        error != null -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            val e = error!!
            ErrorText(loadError(e), e.message ?: e.javaClass.simpleName)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { retry++ }) { Text("Retry") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        current == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        else -> Playback(current, siblings, onBack, onPlay)
    }
}

@Composable
private fun Playback(item: BaseItemDto, siblings: List<BaseItemDto>, onBack: () -> Unit, onPlay: (String) -> Unit) {
    val prefs = App.prefs
    val id = item.id.toString()
    val source = item.mediaSources!!.first()
    val sourceId = source.id!!
    val seriesKey = item.seriesId?.toString() ?: id
    val serverPlayed = item.userData?.played ?: false
    val next = nextEpisode(item, siblings)
    // mpv decodes everything, so the only reason to transcode is the "Direct play up to" setting, which saves bandwidth.
    val videoStream = source.mediaStreams?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }
    val videoHeight = videoStream?.height ?: 0
    val transcoding = prefs.maxHeight in 1 until videoHeight
    val playSession = remember { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    val started = remember {
        runCatching { Mpv(mapOf(
            // When the server drops and the scratch proxy cuts the connection, mpv resumes at the same byte.
            "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10",
            "sub-scale" to "${prefs.subtitleScale / 100.0}",
            "speed" to "$sessionSpeed",
            "volume" to "$sessionVolume",
            // Holding the video back compensates for late wireless audio. The desktop can't tell which output is wireless,
            // so the user sets the delay.
            "audio-delay" to "${-prefs.audioDelayMs / 1000.0}",
        )) }
    }
    val mpv = started.getOrElse { e ->
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            ErrorText("The player can't start.", e.message ?: e.javaClass.simpleName)
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    var segments by remember { mutableStateOf<List<MediaSegmentDto>>(emptyList()) }
    var startTicks by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var seeked by remember { mutableStateOf(false) }
    var resumedAt by remember { mutableStateOf<Long?>(null) }
    var creditsDone by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var lastPositionMs by remember { mutableLongStateOf(0L) }
    var reconnect by remember { mutableStateOf<Reconnect>(Reconnect.Idle) }
    var reloadAtMs by remember { mutableLongStateOf(0L) }
    // The file really does end early: it ended at the same spot again after a reload, while the server was answering.
    var trustEnd by remember { mutableStateOf(false) }
    // A transcode's stream never fails. mpv keeps retrying it behind the spinner for as long as the server is gone,
    // so this flag detects that stall instead.
    var stalled by remember { mutableStateOf(false) }

    fun positionMs() = (mpv.position * 1000).toLong()
    fun runtimeTicks(): Long? {
        val known = item.runTimeTicks ?: source.runTimeTicks
        if (known != null) return known
        val durationMs = (mpv.duration * 1000).toLong()
        return if (durationMs > 0) durationMs * TICKS_PER_MS else null
    }
    fun positionTicks() = ResumeGuard.position(startTicks, positionMs() * TICKS_PER_MS, runtimeTicks(), System.currentTimeMillis() - startedAt, seeked)
    fun report() = scope.launch { App.server.reportProgress(id, sourceId, positionTicks(), mpv.paused, transcoding, serverPlayed, playSession) }
    fun seekTo(ms: Long, exact: Boolean = true) {
        seeked = true
        mpv.seek(ms / 1000.0, exact)
        scope.launch {
            delay(500)
            report()
        }
    }
    // Once mpv's own reconnects give up, a dropped connection ends the file early or fails its load. Hitting the end of
    // what is on disk while seeking never sets eof-reached. mpv just pauses without the user asking.
    fun endedEarly(): Boolean {
        if (trustEnd) return false
        val stopped = mpv.eof || mpv.paused && !mpv.pauseWanted
        if (!stopped) return false
        var durationMs = (mpv.duration * 1000).toLong()
        if (durationMs <= 0) {
            val runtime = runtimeTicks() ?: return false
            durationMs = runtime / TICKS_PER_MS
        }
        return lastPositionMs < durationMs - EARLY_END_MS
    }
    fun url(): String {
        if (!transcoding) return DesktopMedia.streamUrl(item)
        val audioLang = prefs.audioLang(seriesKey)
        val audioIndex = if (audioLang != null) com.zakius.finimal.audioIndex(source, audioLang) else null
        val burnIn = burnInIndex(source, prefs.subLang(seriesKey))
        return App.server.transcodeUrl(id, sourceId, audioIndex, burnIn, playSession)
    }

    mpv.onLoaded = {
        val sub = prefs.subLang(seriesKey)
        val start = startSubtitle(source, sub)
        val sideloaded = sideloadedSubs(item, transcoding)
        sideloaded.forEach { stream ->
            val subtitleUrl = App.server.subtitleUrl(id, sourceId, stream)
            val title = stream.displayTitle ?: stream.language ?: ""
            // mpv has picked its tracks by now, so a file added with "auto" stays off.
            val flag = if (stream.index == start?.index) "select" else "auto"
            mpv.command("sub-add", subtitleUrl, flag, title, stream.language ?: "")
        }
        val tracks = mpv.tracks()
        val audioLang = prefs.audioLang(seriesKey)
        if (audioLang != null) {
            val audio = tracks.firstOrNull { it.type == "audio" && sameLanguage(it.language, audioLang) }
            if (audio != null) mpv.set("aid", audio.id)
        }
        if (sub == "") {
            mpv.set("sid", "no")
        } else if (start != null && start !in sideloaded) {
            // An embedded track, found by its index in the file. Language and title can't tell a release's full and
            // signs-only tracks apart: both are often "English".
            val track = tracks.firstOrNull { it.type == "sub" && it.fileIndex == start.index }
            if (track != null) mpv.set("sid", track.id)
        }
    }

    LaunchedEffect(mpv) {
        DesktopMedia.playing = scratchKey(item)
        val resumeMs = (item.localState().first?.positionTicks ?: 0) / TICKS_PER_MS
        val startMs = (resumeMs - RESUME_REWIND_MS).coerceAtLeast(0)
        startTicks = startMs * TICKS_PER_MS
        startedAt = System.currentTimeMillis()
        lastPositionMs = startMs
        if (startMs > 0) resumedAt = startMs
        mpv.load(url(), startMs / 1000.0)
        if (!transcoding) {
            withContext(Dispatchers.IO) {
                // Meanwhile, buffer the rest of the file to disk, starting a little before the resume position.
                var len = DesktopMedia.lengthOf(item)
                if (len <= 0) len = source.size ?: 0
                val runtime = runtimeTicks()
                val dur = if (runtime != null) runtime / TICKS_PER_MS else 0L
                val from = if (len > 0 && dur > 0) (len * startMs / dur - BUFFER_MARGIN_BYTES).coerceAtLeast(0) else 0L
                DesktopMedia.startBuffer(item, from)
            }
        }
        launch { App.server.reportStart(id, sourceId, startTicks, transcoding, playSession) }
        segments = runCatching { App.server.segments(id) }.getOrDefault(emptyList())
    }

    DisposableEffect(mpv) {
        onDispose {
            // If the user left before the file loaded, there is no position, and reporting 0 would wipe the resume point.
            if (!ended && mpv.loaded) {
                val runtime = runtimeTicks()
                val stopTicks = if (creditsDone && runtime != null) runtime else positionTicks()
                App.server.reportStop(id, sourceId, stopTicks, runtime, serverPlayed, playSession)
            }
            mpv.close()
            DesktopMedia.playing = null
            App.platform.scope.launch(Dispatchers.IO) { DesktopMedia.stopBuffer() }
        }
    }

    // Pausing reports at once, playing reports every REPORT_INTERVAL_MS, and the end reports the whole runtime.
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.paused }.collect { if (it && mpv.loaded) report() }
    }
    LaunchedEffect(mpv) {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            if (!mpv.paused && mpv.loaded && !ended) report()
        }
    }
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.eof to endedEarly() }.collect { (eof, early) ->
            if (eof && !ended && !early) {
                ended = true
                val runtime = runtimeTicks()
                App.server.reportStop(id, sourceId, runtime ?: positionTicks(), runtime, serverPlayed, playSession)
            }
            // Seeking back from the end screen resumes playback.
            if (!eof) ended = false
        }
    }
    // After a dropped connection, reload the file at the same spot with a backoff. After RECONNECT_WINDOW_MS, show the error.
    LaunchedEffect(mpv) {
        // mpv pauses itself at an early end. The reload resumes playback unless the user had paused.
        var playOn = false
        // Where the last reload started. Cleared once playback moves on from there.
        var reloadedAtMs: Long? = null
        while (true) {
            snapshotFlow { mpv.error != null || endedEarly() || stalled }.first { it }
            val reloadedAt = reloadedAtMs
            if (reloadedAt != null && lastPositionMs > reloadedAt + 1_000) reloadedAtMs = null
            // It ended early again right where it was reloaded, while the server answers, so the file really ends there.
            if (mpv.eof && reloadedAtMs != null && App.server.reachable()) {
                trustEnd = true
                reconnect = Reconnect.Idle
                continue
            }
            if (reconnect !is Reconnect.Retrying) {
                // A load error while the server answers is the file's fault. Show it at once and let Retry load again.
                if (mpv.error != null && App.server.reachable()) {
                    snapshotFlow { mpv.error == null }.first { it }
                    continue
                }
                playOn = !mpv.pauseWanted
            }
            val now = System.currentTimeMillis()
            val retrying = reconnect.failed(now)
            reconnect = retrying
            // mpv's own reconnects make a load slow to fail, so the timeout counts from the first failure, however long each attempt takes.
            val wait = retrying.delayMs(now)
            val outcome = if (wait != null) {
                delay(wait)
                val current = reconnect
                if (current is Reconnect.Retrying) reconnect = current.reloaded()
                if (playOn) mpv.play()
                reloadedAtMs = lastPositionMs
                reloadAtMs = lastPositionMs
                mpv.load(url(), lastPositionMs / 1000.0)
                withTimeoutOrNull(RECONNECT_WINDOW_MS - (System.currentTimeMillis() - retrying.sinceMs)) {
                    snapshotFlow { mpv.loaded || mpv.error != null }.first { it }
                }
            } else {
                null
            }
            when {
                outcome == null -> {
                    mpv.command("stop")
                    reconnect = Reconnect.GaveUp
                    snapshotFlow { reconnect }.first { it != Reconnect.GaveUp }
                    // Wait for Retry's load, which replaces the stopped file's state.
                    snapshotFlow { mpv.loaded || mpv.error != null }.first { it }
                }
                // Loaded while the server answers, so the connection is back, and a slow one just buffers. A load can also
                // succeed from what is on disk without the server, but that one fails again where the data on disk ends.
                mpv.error == null && App.server.reachable() -> reconnect = Reconnect.Idle
            }
        }
    }
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.loaded && mpv.buffering }.collectLatest { buffering ->
            if (buffering) {
                delay(STALL_MS)
                stalled = !App.server.reachable()
            } else {
                stalled = false
            }
        }
    }
    // The connection counts as back once playback moves past the reload point, or, if the user paused, once the file is loaded.
    LaunchedEffect(mpv) {
        snapshotFlow {
            val movedOn = lastPositionMs > reloadAtMs + 1_000
            movedOn || mpv.pauseWanted && mpv.loaded && !mpv.buffering && !mpv.eof && mpv.error == null
        }.collect { back ->
            if (back && reconnect is Reconnect.Retrying) reconnect = Reconnect.Idle
        }
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt != null) {
            delay(5_000)
            resumedAt = null
        }
    }
    LaunchedEffect(mpv) { snapshotFlow { mpv.volume }.collect { if (mpv.loaded) sessionVolume = it } }
    // Keep the screen on only while playing. When paused, ended or failed, it times out as usual.
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.loaded && !mpv.paused && !mpv.eof && mpv.error == null }.collect { playing -> ScreenSaver.inhibit(playing) }
    }
    DisposableEffect(mpv) { onDispose { ScreenSaver.inhibit(false) } }
    val window = LocalWindow.current
    DisposableEffect(window) {
        window?.title = "${playerTitle(item)} – finimal"
        onDispose {
            window?.title = "finimal"
            window?.setFullscreen(false)
        }
    }
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.position }.collect { position ->
            if (mpv.loaded && position > 0) lastPositionMs = (position * 1000).toLong()
        }
    }

    VideoSurface(mpv, item, transcoding, segments, ended, next, resumedAt, reconnecting = reconnect is Reconnect.Retrying,
        failure = if (reconnect == Reconnect.GaveUp) "Can't reach the server." else mpv.error,
        title = playerTitle(item),
        onResumeNoteGone = { resumedAt = null },
        onSeek = ::seekTo,
        onBack = onBack,
        onPlay = onPlay,
        onCreditsNext = { nextId ->
            creditsDone = true
            onPlay(nextId)
        },
        onRetry = {
            if (reconnect == Reconnect.GaveUp) reconnect = Reconnect.Idle
            mpv.play()
            mpv.load(url(), lastPositionMs / 1000.0)
        },
        onTracksChosen = { audio, sub -> prefs.setTracks(seriesKey, audio, sub) },
    )
}

private class Skip(val label: String, val onClick: () -> Unit)

private val playerKeys = setOf(Key.Spacebar, Key.K, Key.MediaPlayPause, Key.DirectionLeft, Key.J, Key.DirectionRight, Key.L,
    Key.DirectionUp, Key.DirectionDown, Key.M, Key.F, Key.N, Key.MediaNext, Key.PageUp, Key.PageDown, Key.Escape)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VideoSurface(
    mpv: Mpv,
    item: BaseItemDto,
    transcoding: Boolean,
    segments: List<MediaSegmentDto>,
    ended: Boolean,
    next: BaseItemDto?,
    resumedAt: Long?,
    reconnecting: Boolean,
    failure: String?,
    title: String,
    onResumeNoteGone: () -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onCreditsNext: (String) -> Unit,
    onRetry: () -> Unit,
    onTracksChosen: (audio: String?, sub: String?) -> Unit,
) {
    val prefs = App.prefs
    val window = LocalWindow.current
    val fullscreen = window?.fullscreen == true
    fun toggleFullscreen() { window?.toggleFullscreen() }
    var controls by remember { mutableStateOf(true) }
    // While the ⋮ menu is open, it gets the keys: Esc closes it and the arrows move through it.
    var menu by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlay by remember { mutableStateOf<String?>(null) }
    val positionMs = (mpv.position * 1000).toLong()
    val durationMs = (mpv.duration * 1000).toLong()
    val step = prefs.doubleTapSeconds * 1000L
    val chapters = remember(item) { chapters(item) }
    fun clamp(ms: Long) = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
    fun seekBy(ms: Long) {
        onSeek(clamp(positionMs + ms), true)
        val sign = if (ms > 0) "+" else "−"
        overlay = sign + "${kotlin.math.abs(ms) / 1000}s"
    }
    fun togglePlay() = if (mpv.paused) mpv.play() else mpv.pause()
    fun volumeBy(step: Double) {
        val v = (mpv.volume + step).coerceIn(0.0, 130.0)
        mpv.set("volume", "$v")
        overlay = "Volume ${v.toInt()}%" + if (mpv.muted) " · muted" else ""
    }
    fun interact() {
        lastInteraction = System.currentTimeMillis()
        controls = true
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (controls && !mpv.paused && System.currentTimeMillis() - lastInteraction > 3_000) controls = false
        }
    }
    LaunchedEffect(overlay) {
        if (overlay != null) {
            delay(800)
            overlay = null
        }
    }
    // Show the mute state when mpv's flag changes, not at the key press. The flag arrives late, so quick presses would show stale text.
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.muted }.drop(1).collect { muted ->
            overlay = if (muted) "Muted" else "Volume ${mpv.volume.toInt()}%"
        }
    }

    // Skipping is offered, never automatic. During the credits, the offer is the next episode in season order, if any.
    val ranges = remember(segments) {
        segments.map { segment ->
            val startMs = segment.startTicks / TICKS_PER_MS
            val endMs = segment.endTicks / TICKS_PER_MS
            segment.type to (startMs until endMs - 1_000)
        }
    }
    val segmentNow = ranges.firstOrNull { positionMs in it.second }
    val skip = if (segmentNow != null && !ended) {
        val (type, range) = segmentNow
        when (type) {
            MediaSegmentType.INTRO -> Skip("Skip intro") { onSeek(range.last + 1_000, true) }
            MediaSegmentType.RECAP -> Skip("Skip recap") { onSeek(range.last + 1_000, true) }
            MediaSegmentType.OUTRO -> if (next != null) Skip("Play next: ${episodeLabel(next)}") { onCreditsNext(next.id.toString()) } else null
            else -> null
        }
    } else {
        null
    }

    // Keys are handled at the window rather than on a focused node, because covered screens stay composed and can keep
    // focus, and menus take it.
    val keys by rememberUpdatedState { e: KeyEvent ->
        if (menu) false
        // Consume the key release too. Otherwise a focused button clicks on Space release, undoing the toggle.
        else if (e.type == KeyEventType.KeyUp) e.key in playerKeys
        else if (e.type != KeyEventType.KeyDown) false
        else {
            interact()
            when (e.key) {
                Key.Spacebar, Key.K, Key.MediaPlayPause -> { togglePlay(); true }
                Key.DirectionLeft, Key.J -> { seekBy(-step); true }
                Key.DirectionRight, Key.L -> { seekBy(step); true }
                Key.DirectionUp -> { volumeBy(5.0); true }
                Key.DirectionDown -> { volumeBy(-5.0); true }
                Key.M -> { mpv.command("cycle", "mute"); true }
                Key.F -> { toggleFullscreen(); true }
                Key.N, Key.MediaNext -> {
                    if (next != null) onPlay(next.id.toString())
                    true
                }
                Key.PageUp, Key.PageDown -> {
                    val starts = chapters.map { it.startMs }
                    val ms = chapterSeek(starts, positionMs, forward = e.key == Key.PageDown)
                    if (ms != null) {
                        onSeek(ms, true)
                        val chapter = chapters.first { it.startMs == ms }
                        overlay = chapter.name.ifEmpty { formatMs(ms) }
                    }
                    true
                }
                Key.Escape -> {
                    if (fullscreen) toggleFullscreen() else onBack()
                    true
                }
                else -> false
            }
        }
    }
    DisposableEffect(window) {
        val handler: (KeyEvent) -> Boolean = { keys(it) }
        window?.keys = handler
        onDispose { if (window?.keys === handler) window.keys = null }
    }
    // Hide the cursor with the controls by switching to a blank 1x1 cursor.
    val cursor = if (controls) {
        PointerIcon.Default
    } else {
        val blank = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(blank, java.awt.Point(), "none"))
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Move) { interact() }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val scroll = e.changes.firstOrNull()?.scrollDelta?.y
                    if (scroll != null && scroll != 0f) volumeBy(if (scroll < 0) 5.0 else -5.0)
                }
                .pointerHoverIcon(cursor)
                .pointerInput(mpv) {
                    var lastTapTime = -1L
                    var lastTapPos = Offset.Zero
                    var lastTapPausedBefore = false
                    val doubleTapSlop = 48.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        val downPos = down.position
                        var moved = false
                        var up: PointerInputChange? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) {
                                moved = true
                                break
                            }
                            if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                            }
                            if (change.changedToUp()) {
                                up = change
                                break
                            }
                            if (!change.pressed) break
                        }
                        val release = up ?: return@awaitEachGesture
                        if (moved) return@awaitEachGesture
                        release.consume()
                        val now = release.uptimeMillis
                        val isDouble = lastTapTime >= 0L &&
                            now - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                            (release.position - lastTapPos).getDistance() <= doubleTapSlop
                        if (isDouble) {
                            lastTapTime = -1L
                            if (lastTapPausedBefore) mpv.pause() else mpv.play()
                            toggleFullscreen()
                        } else {
                            lastTapTime = now
                            lastTapPos = release.position
                            lastTapPausedBefore = mpv.paused
                            togglePlay()
                            interact()
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { size -> mpv.resize(size.width, size.height) }) {
                mpv.frame?.let { drawImage(it) }
            }

            val overlayText = overlay
            if (overlayText != null) {
                Text(
                    overlayText,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), MaterialTheme.shapes.small)
                        .padding(12.dp),
                )
            }
            // While reconnecting, the last frame stays up with a note, instead of the error or the end screen.
            val error = if (reconnecting) null else failure
            val ended = ended && !reconnecting
            if (reconnecting) {
                Text(
                    "Reconnecting…",
                    Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), MaterialTheme.shapes.small)
                        .padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else if (mpv.buffering && error == null && !ended) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }

            if (skip != null && !controls) FilledTonalButton(skip.onClick, Modifier.align(Alignment.BottomEnd).padding(32.dp)) { Text(skip.label) }

            if (error != null) {
                Column(Modifier.align(Alignment.Center).padding(16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    ErrorText(error, null, Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRetry) { Text("Retry") }
                        FilledTonalButton(onClick = onBack) { Text("Back") }
                    }
                }
            } else if (ended) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    if (next != null) Button(onClick = { onPlay(next.id.toString()) }) {
                        Text("Play next: ${episodeLabel(next)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(onClick = onBack) { Text("Back") }
                }
            } else if (controls) {
                Controls(mpv, item, positionMs, durationMs, title, transcoding, fullscreen, skip, next, menu, { menu = it },
                    stepSeconds = prefs.doubleTapSeconds,
                    onBack = onBack,
                    onToggleFullscreen = ::toggleFullscreen,
                    onSeek = onSeek,
                    onSeekBy = ::seekBy,
                    onPlay = onPlay,
                    onTracksChosen = onTracksChosen,
                    onInteraction = ::interact)
            }

            if (resumedAt != null && error == null && !ended) {
                val noteModifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color(0x99000000), MaterialTheme.shapes.small)
                    .padding(start = 12.dp)
                Row(noteModifier, verticalAlignment = Alignment.CenterVertically) {
                    Text("Resumed at ${formatMs(resumedAt)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    TextButton(
                        onClick = {
                            onSeek(0, true)
                            onResumeNoteGone()
                        },
                    ) { Text("Start over") }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    mpv: Mpv,
    item: BaseItemDto,
    positionMs: Long,
    durationMs: Long,
    title: String,
    transcoding: Boolean,
    fullscreen: Boolean,
    skip: Skip?,
    next: BaseItemDto?,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    stepSeconds: Int,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlay: (String) -> Unit,
    onTracksChosen: (String?, String?) -> Unit,
    onInteraction: () -> Unit,
) {
    var sub by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<List<Mpv.Track>?>(null) }
    var subtitleScale by remember { mutableIntStateOf(App.prefs.subtitleScale) }
    var audioDelayMs by remember { mutableIntStateOf(App.prefs.audioDelayMs) }
    var dragging by remember { mutableFloatStateOf(-1f) }
    var volumeDragging by remember { mutableFloatStateOf(-1f) }
    val chapters = remember(item) { chapters(item) }
    val stepMs = stepSeconds * 1000L
    LaunchedEffect(menu) {
        while (menu) {
            onInteraction()
            delay(1_000)
        }
    }
    // The end or error screen can replace the controls while the menu is open. Close the menu so it doesn't keep the keys.
    DisposableEffect(Unit) { onDispose { onMenu(false) } }
    LaunchedEffect(menu, mpv.tracksChanged) {
        if (menu) tracks = withContext(Dispatchers.IO) { mpv.tracks() }
    }
    fun close() {
        onMenu(false)
        sub = null
    }
    Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tip("Back (Esc)") {
                IconButton(onClick = onBack) { com.zakius.finimal.GlyphIcon(com.zakius.finimal.AppIcons.ARROW_BACK, "Back", tint = Color.White, autoMirror = true) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (transcoding) Text("Transcoding to fit the \"Direct play up to\" setting", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                Tip("Menu") {
                    IconButton(
                        onClick = {
                            onMenu(true)
                            onInteraction()
                        },
                    ) { com.zakius.finimal.GlyphIcon(com.zakius.finimal.AppIcons.MORE_VERT, "Menu", tint = Color.White) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = ::close) {
                    when (sub) {
                        null -> {
                            val loaded = tracks
                            val audioTracks = loaded?.filter { it.type == "audio" }
                            val audioText = audioTracks?.indexOfFirst { it.selected }?.takeIf { it >= 0 }?.let { i ->
                                "Audio: ${trackLabel(audioTracks[i]).ifEmpty { "Track ${i + 1}" }}"
                            } ?: "Audio"
                            val subTracks = loaded?.filter { it.type == "sub" }
                            val subText = if (subTracks == null) {
                                "Subtitles"
                            } else {
                                val i = subTracks.indexOfFirst { it.selected }
                                if (i < 0) "Subtitles: Off" else "Subtitles: ${trackLabel(subTracks[i]).ifEmpty { "Track ${i + 1}" }}"
                            }
                            DropdownMenuItem({ Text("Speed: ${speedLabel(mpv.speed)}") }, { sub = "speed" })
                            DropdownMenuItem({ Text(audioText) }, { sub = "audio" })
                            DropdownMenuItem({ Text(subText) }, { sub = "text" })
                            DropdownMenuItem({ Text("Subtitle size: $subtitleScale%") }, { sub = "size" })
                            DropdownMenuItem({ Text("Audio delay: $audioDelayMs ms") }, { sub = "delay" })
                            if (chapters.isNotEmpty()) DropdownMenuItem({ Text("Chapters") }, { sub = "chapters" })
                        }
                        "chapters" -> {
                            SubmenuBack("Chapters") { sub = null }
                            val current = chapterAt(chapters.map { it.startMs }, positionMs)
                            chapters.forEachIndexed { i, c ->
                                val name = c.name.ifEmpty { "Chapter ${i + 1}" }
                                Choice("${formatMs(c.startMs)}  $name", i == current) {
                                    onSeek(c.startMs, true)
                                    close()
                                }
                            }
                        }
                        "speed" -> {
                            SubmenuBack("Speed") { sub = null }
                            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { s ->
                                Choice(speedLabel(s), mpv.speed == s) {
                                    sessionSpeed = s
                                    mpv.set("speed", "$s")
                                    close()
                                }
                            }
                        }
                        "size" -> {
                            SubmenuBack("Subtitle size") { sub = null }
                            listOf(50, 75, 100, 125, 150, 200).forEach { s ->
                                Choice("$s%", subtitleScale == s) {
                                    subtitleScale = s
                                    App.prefs.subtitleScale = s
                                    mpv.set("sub-scale", "${s / 100.0}")
                                }
                            }
                        }
                        "delay" -> {
                            SubmenuBack("Audio delay") { sub = null }
                            AUDIO_DELAYS_MS.forEach { d ->
                                Choice("$d ms", audioDelayMs == d) {
                                    audioDelayMs = d
                                    App.prefs.audioDelayMs = d
                                    mpv.set("audio-delay", "${-d / 1000.0}")
                                }
                            }
                        }
                        "audio", "text" -> {
                            val type = if (sub == "audio") "audio" else "sub"
                            SubmenuBack(if (type == "audio") "Audio" else "Subtitles") { sub = null }
                            val loaded = tracks
                            if (loaded == null) {
                                DropdownMenuItem({ Text("Loading…") }, {}, enabled = false)
                            } else {
                                val matching = loaded.filter { it.type == type }
                                fun selectedLanguage(t: String): String? {
                                    val language = loaded.firstOrNull { it.type == t && it.selected }?.language
                                    if (language != null) return language
                                    return if (t == "sub") "" else null
                                }
                                if (matching.isEmpty()) {
                                    DropdownMenuItem({ Text("No tracks") }, {}, enabled = false)
                                } else {
                                    if (type == "sub") {
                                        Choice("Off", matching.none { it.selected }) {
                                            mpv.set("sid", "no")
                                            onTracksChosen(selectedLanguage("audio"), "")
                                            close()
                                        }
                                    }
                                    matching.forEachIndexed { i, t ->
                                        Choice(trackLabel(t).ifEmpty { "Track ${i + 1}" }, t.selected) {
                                            mpv.set(if (type == "audio") "aid" else "sid", t.id)
                                            val audio = if (type == "audio") t.language else selectedLanguage("audio")
                                            val subtitle = if (type == "sub") t.language else selectedLanguage("sub")
                                            onTracksChosen(audio, subtitle)
                                            close()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Tip(if (fullscreen) "Exit fullscreen (F)" else "Fullscreen (F)") {
                IconButton(onClick = onToggleFullscreen) {
                    Icon(if (fullscreen) FullscreenExitIcon else FullscreenIcon, if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val shown = if (dragging >= 0) dragging.toLong() else positionMs
            val cached = if (transcoding) {
                (mpv.cachedUntil * 1000).toLong()
            } else {
                val onDiskMs = (DesktopMedia.cachedUntil(item, shown / 1000.0, durationMs / 1000.0) * 1000).toLong()
                onDiskMs.coerceAtLeast((mpv.cachedUntil * 1000).toLong())
            }
            val bufferedNote = if (durationMs > 0 && cached >= durationMs - 2_000) "" else "  (buffered ${formatMs(cached)})"
            val chapterNote = if (dragging >= 0 && chapters.isNotEmpty()) {
                val i = chapterAt(chapters.map { it.startMs }, shown)
                if (i >= 0) " · ${chapters[i].name.ifEmpty { "Chapter ${i + 1}" }}" else ""
            } else {
                ""
            }
            val shownVolume = if (volumeDragging >= 0f) volumeDragging else if (mpv.muted) 0f else mpv.volume.toFloat().coerceIn(0f, 130f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Tip("Back ${stepSeconds}s (←)") {
                    IconButton(onClick = { onSeekBy(-stepMs); onInteraction() }) {
                        Icon(RewindIcon, "Back ${stepSeconds}s", tint = Color.White)
                    }
                }
                Tip("Play / Pause (Space)") {
                    IconButton(
                        onClick = {
                            if (mpv.paused) mpv.play() else mpv.pause()
                            onInteraction()
                        },
                    ) {
                        if (mpv.paused) com.zakius.finimal.GlyphIcon(com.zakius.finimal.AppIcons.PLAY, "Play", size = 32.dp, tint = Color.White)
                        else Icon(PauseIcon, "Pause", Modifier.size(32.dp), tint = Color.White)
                    }
                }
                Tip("Forward ${stepSeconds}s (→)") {
                    IconButton(onClick = { onSeekBy(stepMs); onInteraction() }) {
                        Icon(ForwardIcon, "Forward ${stepSeconds}s", tint = Color.White)
                    }
                }
                if (next != null) {
                    Tip("Next episode (N)") {
                        IconButton(onClick = { onPlay(next.id.toString()) }) { Icon(NextIcon, "Next episode", tint = Color.White) }
                    }
                }
                Text(
                    "${formatMs(shown)} / ${formatMs(durationMs)}$bufferedNote$chapterNote",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Tip("Mute (M)") {
                    IconButton(onClick = { mpv.command("cycle", "mute"); onInteraction() }) {
                        Icon(if (mpv.muted || shownVolume <= 0f) VolumeOffIcon else VolumeUpIcon, if (mpv.muted) "Unmute" else "Mute", tint = Color.White)
                    }
                }
                Slider(
                    value = shownVolume.coerceIn(0f, 130f),
                    valueRange = 0f..130f,
                    onValueChange = { v ->
                        volumeDragging = v
                        if (v > 0f && mpv.muted) mpv.set("mute", "no")
                        mpv.set("volume", "$v")
                        onInteraction()
                    },
                    onValueChangeFinished = { volumeDragging = -1f },
                    modifier = Modifier.width(100.dp),
                )
                Text(
                    "${shownVolume.toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Canvas(Modifier.matchParentSize()) {
                        if (durationMs <= 0 || cached <= 0) return@Canvas
                        val left = 10.dp.toPx()
                        val trackWidth = size.width - 2 * left
                        if (trackWidth <= 0f) return@Canvas
                        val endX = left + trackWidth * (cached.toFloat() / durationMs).coerceIn(0f, 1f)
                        drawLine(
                            color = Color.White.copy(alpha = 0.35f),
                            start = Offset(left, center.y),
                            end = Offset(endX, center.y),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    ChapterMarks(chapters, durationMs, Modifier.matchParentSize())
                    Slider(
                        value = shown.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        onValueChange = { value ->
                            dragging = value
                            onInteraction()
                        },
                        onValueChangeFinished = {
                            if (dragging >= 0) onSeek(dragging.toLong(), false)
                            dragging = -1f
                        },
                        colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (skip != null) FilledTonalButton(skip.onClick, Modifier.padding(start = 8.dp)) { Text(skip.label) }
            }
        }
    }
}

@Composable
private fun SubmenuBack(title: String, onBack: () -> Unit) {
    DropdownMenuItem({ Text("← $title") }, onBack)
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tip(text: String, content: @Composable () -> Unit) = TooltipArea(
    tooltip = {
        Surface(
            color = Color(0xE61C1C1C),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.extraSmall,
            shadowElevation = 4.dp,
        ) {
            Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
    },
    content = content,
)

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    DropdownMenuItem({ Text(label) }, onClick, trailingIcon = { if (selected) com.zakius.finimal.GlyphIcon(com.zakius.finimal.AppIcons.CHECK, null) })

private fun speedLabel(s: Double) = "${s.toString().removeSuffix(".0")}×"

/** Builds a label like "English · 5.1", with the track's own title first. */
private fun trackLabel(t: Mpv.Track): String {
    val code = t.language
    val language = if (code == null || code == "und") null else Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
    val channels = when (t.channels) {
        null -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${t.channels} ch"
    }
    // Skip the language when the title already names it.
    val titleNamesLanguage = language != null && t.title?.contains(language, ignoreCase = true) == true
    val shownLanguage = if (titleNamesLanguage) null else language
    return listOfNotNull(t.title, shownLanguage, channels).joinToString(" · ")
}
