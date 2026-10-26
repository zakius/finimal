package com.zakius.finimal.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zakius.finimal.App
import com.zakius.finimal.AUDIO_DELAYS_MS
import com.zakius.finimal.ErrorText
import com.zakius.finimal.episodeLabel
import com.zakius.finimal.externalSubs
import com.zakius.finimal.formatMs
import com.zakius.finimal.loadError
import com.zakius.finimal.localState
import com.zakius.finimal.nextEpisode
import com.zakius.finimal.playerTitle
import com.zakius.finimal.sameLanguage
import com.zakius.finimal.scratchKey
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

/** Chosen speed and volume, kept for the session so "Play next" does not reset them. */
private var sessionSpeed = 1.0
private var sessionVolume = 100.0

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
private val PauseIcon = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
private val NextIcon = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val FullscreenIcon = icon("Fullscreen", "M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
private val FullscreenExitIcon = icon("FullscreenExit", "M5,16h3v3h2v-5H5v2zm3,-8H5v2h5V5H8v3zm6,11h2v-3h3v-2h-5v5zm2,-11V5h-2v5h5V8h-3z")

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
        // Only this item's unsynced state must reach the server before it is read; the rest syncs alongside.
        val pending = withContext(App.disk) { App.pending.get(itemId) != null }
        if (!pending) App.platform.scope.launch { server.flush() }
        runCatching { server.item(itemId, flushFirst = pending) }
            .onSuccess { loaded ->
                if (loaded.mediaSources.isNullOrEmpty()) error = java.io.FileNotFoundException("This item has no file on the server")
                else item = loaded
            }
            .onFailure { if (it is InvalidStatusException && it.status == 401) server.expire(); error = it }
    }
    LaunchedEffect(item) {
        val it = item ?: return@LaunchedEffect
        val seriesId = it.seriesId?.toString()
        if (it.type == BaseItemKind.EPISODE && seriesId != null) siblings = runCatching { App.server.episodes(seriesId) }.getOrDefault(emptyList())
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
    // mpv decodes everything; only the "Direct play up to" setting makes a transcode, for bandwidth.
    val videoHeight = source.mediaStreams?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }?.height ?: 0
    val transcoding = prefs.maxHeight in 1 until videoHeight
    val playSession = remember { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    val started = remember {
        runCatching { Mpv(mapOf(
            // A cut connection to the scratch proxy (the server dropped) is picked up again at the same byte.
            "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10",
            "sub-scale" to "${prefs.subtitleScale / 100.0}",
            "speed" to "$sessionSpeed",
            "volume" to "$sessionVolume",
            // Late wireless audio: holding the video back matches it. The desktop can't tell which output is wireless.
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

    fun positionMs() = (mpv.position * 1000).toLong()
    fun runtimeTicks() = item.runTimeTicks ?: source.runTimeTicks ?: (mpv.duration * 1000).toLong().takeIf { it > 0 }?.times(TICKS_PER_MS)
    fun positionTicks() = ResumeGuard.position(startTicks, positionMs() * TICKS_PER_MS, runtimeTicks(), System.currentTimeMillis() - startedAt, seeked)
    fun report() = scope.launch { App.server.reportProgress(id, sourceId, positionTicks(), mpv.paused, transcoding, serverPlayed, playSession) }
    fun seekTo(ms: Long, exact: Boolean = true) { seeked = true; mpv.seek(ms / 1000.0, exact); scope.launch { delay(500); report() } }
    fun url() = if (transcoding) App.server.transcodeUrl(id, sourceId, prefs.audioLang(seriesKey)?.let { com.zakius.finimal.audioIndex(source, it) }, playSession)
        else DesktopMedia.streamUrl(item)

    mpv.onLoaded = {
        externalSubs(item).forEach { stream ->
            mpv.command("sub-add", App.server.subtitleUrl(id, sourceId, stream), "auto", stream.displayTitle ?: stream.language ?: "", stream.language ?: "")
        }
        val tracks = mpv.tracks()
        prefs.audioLang(seriesKey)?.let { lang -> tracks.firstOrNull { it.type == "audio" && sameLanguage(it.language, lang) }?.let { mpv.set("aid", it.id) } }
        when (val sub = prefs.subLang(seriesKey)) {
            null -> {}
            "" -> mpv.set("sid", "no")
            else -> mpv.tracks().firstOrNull { it.type == "sub" && sameLanguage(it.language, sub) }?.let { mpv.set("sid", it.id) }
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
        if (!transcoding) withContext(Dispatchers.IO) {
            // The rest of the file lands on disk meanwhile, from a margin behind the start.
            val len = DesktopMedia.lengthOf(item).takeIf { it > 0 } ?: source.size ?: 0
            val dur = runtimeTicks()?.div(TICKS_PER_MS) ?: 0
            DesktopMedia.startBuffer(item, if (len > 0 && dur > 0) (len * startMs / dur - 32_000_000).coerceAtLeast(0) else 0)
        }
        launch { App.server.reportStart(id, sourceId, startTicks, transcoding, playSession) }
        segments = runCatching { App.server.segments(id) }.getOrDefault(emptyList())
    }

    DisposableEffect(mpv) {
        onDispose {
            // Left before loading: there is no position, and reporting 0 would wipe the resume point.
            if (!ended && mpv.loaded) {
                val runtime = runtimeTicks()
                App.server.reportStop(id, sourceId, if (creditsDone && runtime != null) runtime else positionTicks(), runtime, serverPlayed, playSession)
            }
            mpv.close()
            DesktopMedia.playing = null
            App.platform.scope.launch(Dispatchers.IO) { DesktopMedia.stopBuffer() }
        }
    }

    // Pausing reports at once; playing reports every 10 s; the end reports the whole runtime.
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.paused }.collect { if (it && mpv.loaded) report() }
    }
    LaunchedEffect(mpv) {
        while (true) { delay(REPORT_INTERVAL_MS); if (!mpv.paused && mpv.loaded && !ended) report() }
    }
    LaunchedEffect(mpv) {
        snapshotFlow { mpv.eof }.collect { eof ->
            if (eof && !ended) {
                ended = true
                val runtime = runtimeTicks()
                App.server.reportStop(id, sourceId, runtime ?: positionTicks(), runtime, serverPlayed, playSession)
            }
            // Seeking back from the end screen plays on.
            if (!eof) ended = false
        }
    }
    LaunchedEffect(resumedAt) { if (resumedAt != null) { delay(5_000); resumedAt = null } }
    LaunchedEffect(mpv) { snapshotFlow { mpv.volume }.collect { if (mpv.loaded) sessionVolume = it } }
    // The screen stays on only while playing; paused, ended or failed, it times out.
    LaunchedEffect(mpv) { snapshotFlow { mpv.loaded && !mpv.paused && !mpv.eof && mpv.error == null }.collect { ScreenSaver.inhibit(it) } }
    DisposableEffect(mpv) { onDispose { ScreenSaver.inhibit(false) } }
    val window = LocalWindow.current
    DisposableEffect(window) {
        window?.title = "${playerTitle(item)} – finimal"
        onDispose { window?.title = "finimal"; window?.setFullscreen(false) }
    }
    LaunchedEffect(mpv) { snapshotFlow { mpv.position }.collect { if (mpv.loaded && it > 0) lastPositionMs = (it * 1000).toLong() } }

    VideoSurface(mpv, item, transcoding, segments, ended, next, resumedAt,
        title = playerTitle(item),
        onResumeNoteGone = { resumedAt = null },
        onSeek = ::seekTo,
        onBack = onBack,
        onPlay = onPlay,
        onCreditsNext = { creditsDone = true; onPlay(it) },
        onRetry = { mpv.load(url(), lastPositionMs / 1000.0) },
        onTracksChosen = { audio, sub -> prefs.setTracks(seriesKey, audio, sub) },
    )
}

private class Skip(val label: String, val onClick: () -> Unit)

private val playerKeys = setOf(Key.Spacebar, Key.K, Key.MediaPlayPause, Key.DirectionLeft, Key.J, Key.DirectionRight, Key.L,
    Key.DirectionUp, Key.DirectionDown, Key.M, Key.F, Key.N, Key.MediaNext, Key.Escape)

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
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlay by remember { mutableStateOf<String?>(null) }
    val positionMs = (mpv.position * 1000).toLong()
    val durationMs = (mpv.duration * 1000).toLong()
    val step = prefs.doubleTapSeconds * 1000L
    fun clamp(ms: Long) = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
    fun seekBy(ms: Long) { onSeek(clamp(positionMs + ms), true); overlay = (if (ms > 0) "+" else "−") + "${kotlin.math.abs(ms) / 1000}s" }
    fun togglePlay() = if (mpv.paused) mpv.play() else mpv.pause()
    fun volumeBy(step: Double) {
        val v = (mpv.volume + step).coerceIn(0.0, 130.0)
        mpv.set("volume", "$v")
        overlay = "Volume ${v.toInt()}%"
    }
    fun interact() { lastInteraction = System.currentTimeMillis(); controls = true }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (controls && !mpv.paused && System.currentTimeMillis() - lastInteraction > 3_000) controls = false
        }
    }
    LaunchedEffect(overlay) { if (overlay != null) { delay(800); overlay = null } }

    // Offered, never done on its own. Credits offer the next episode in season order when there is one.
    val ranges = remember(segments) { segments.map { it.type to (it.startTicks / TICKS_PER_MS until it.endTicks / TICKS_PER_MS - 1_000) } }
    val skip = ranges.firstOrNull { positionMs in it.second }?.takeIf { !ended }?.let { (type, range) ->
        when (type) {
            MediaSegmentType.INTRO -> Skip("Skip intro") { onSeek(range.last + 1_000, true) }
            MediaSegmentType.RECAP -> Skip("Skip recap") { onSeek(range.last + 1_000, true) }
            MediaSegmentType.OUTRO -> next?.let { n -> Skip("Play next: ${episodeLabel(n)}") { onCreditsNext(n.id.toString()) } }
            else -> null
        }
    }

    // At the window, not on a focused node: covered screens stay composed and can keep focus, and menus take it.
    val keys by rememberUpdatedState { e: KeyEvent ->
        // The release too: a focused button would otherwise click on it (Space), undoing the toggle.
        if (e.type == KeyEventType.KeyUp) e.key in playerKeys
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
                Key.N, Key.MediaNext -> { next?.let { onPlay(it.id.toString()) }; true }
                Key.Escape -> { if (fullscreen) toggleFullscreen() else onBack(); true }
                else -> false
            }
        }
    }
    DisposableEffect(window) {
        val handler: (KeyEvent) -> Boolean = { keys(it) }
        window?.keys = handler
        onDispose { if (window?.keys === handler) window.keys = null }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { interact() }
            .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.firstOrNull()?.scrollDelta?.y?.let { if (it != 0f) volumeBy(if (it < 0) 5.0 else -5.0) } }
            // The cursor hides with the controls, as in other players.
            .pointerHoverIcon(if (controls) PointerIcon.Default else PointerIcon(java.awt.Toolkit.getDefaultToolkit()
                .createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(), "none")))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { togglePlay(); interact() }, onDoubleTap = { toggleFullscreen() })
            }
    ) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { mpv.resize(it.width, it.height) }) {
            mpv.frame?.let { drawImage(it) }
        }

        overlay?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), MaterialTheme.shapes.small).padding(12.dp))
        }
        val error = mpv.error
        if (mpv.buffering && error == null && !ended) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        if (skip != null && !controls) FilledTonalButton(skip.onClick, Modifier.align(Alignment.BottomEnd).padding(32.dp)) { Text(skip.label) }

        if (resumedAt != null && error == null && !ended) Row(Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
            .background(Color(0x99000000), MaterialTheme.shapes.small).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Resumed at ${formatMs(resumedAt)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onSeek(0, true); onResumeNoteGone() }) { Text("Start over") }
        }

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
            Controls(mpv, item, positionMs, durationMs, title, transcoding, fullscreen, skip, next,
                onBack, ::toggleFullscreen, onSeek, onPlay, onTracksChosen, onInteraction = ::interact)
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
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onPlay: (String) -> Unit,
    onTracksChosen: (String?, String?) -> Unit,
    onInteraction: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<List<Mpv.Track>>(emptyList()) }
    var dragging by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(menu) { while (menu) { onInteraction(); delay(1_000) } }
    LaunchedEffect(sub, mpv.tracksChanged) { if (sub == "audio" || sub == "text") tracks = withContext(Dispatchers.IO) { mpv.tracks() } }
    fun close() { menu = false; sub = null }
    Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (transcoding) Text("Transcoding to fit the \"Direct play up to\" setting", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = { menu = true; onInteraction() }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = ::close) {
                    when (sub) {
                        null -> {
                            DropdownMenuItem({ Text("Speed: ${speedLabel(mpv.speed)}") }, { sub = "speed" })
                            DropdownMenuItem({ Text("Audio") }, { sub = "audio" })
                            DropdownMenuItem({ Text("Subtitles") }, { sub = "text" })
                            DropdownMenuItem({ Text("Subtitle size: ${App.prefs.subtitleScale}%") }, { sub = "size" })
                            DropdownMenuItem({ Text("Audio delay: ${App.prefs.audioDelayMs} ms") }, { sub = "delay" })
                        }
                        "speed" -> listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { s ->
                            Choice(speedLabel(s), mpv.speed == s) { sessionSpeed = s; mpv.set("speed", "$s"); close() }
                        }
                        "size" -> listOf(50, 75, 100, 125, 150, 200).forEach { s ->
                            Choice("$s%", App.prefs.subtitleScale == s) { App.prefs.subtitleScale = s; mpv.set("sub-scale", "${s / 100.0}"); close() }
                        }
                        "delay" -> AUDIO_DELAYS_MS.forEach { d ->
                            Choice("$d ms", App.prefs.audioDelayMs == d) { App.prefs.audioDelayMs = d; mpv.set("audio-delay", "${-d / 1000.0}"); close() }
                        }
                        "audio", "text" -> {
                            val type = if (sub == "audio") "audio" else "sub"
                            fun selectedLanguage(t: String) = tracks.firstOrNull { it.type == t && it.selected }?.language ?: if (t == "sub") "" else null
                            if (type == "sub") Choice("Off", tracks.none { it.type == "sub" && it.selected }) {
                                mpv.set("sid", "no"); onTracksChosen(selectedLanguage("audio"), ""); close()
                            }
                            tracks.filter { it.type == type }.forEachIndexed { i, t ->
                                Choice(trackLabel(t).ifEmpty { "Track ${i + 1}" }, t.selected) {
                                    mpv.set(if (type == "audio") "aid" else "sid", t.id)
                                    onTracksChosen(if (type == "audio") t.language else selectedLanguage("audio"), if (type == "sub") t.language else selectedLanguage("sub"))
                                    close()
                                }
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(if (fullscreen) FullscreenExitIcon else FullscreenIcon, if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val shown = if (dragging >= 0) dragging.toLong() else positionMs
            val cached = if (transcoding) (mpv.cachedUntil * 1000).toLong()
                else (DesktopMedia.cachedUntil(item, shown / 1000.0, durationMs / 1000.0) * 1000).toLong().coerceAtLeast((mpv.cachedUntil * 1000).toLong())
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (mpv.paused) mpv.play() else mpv.pause(); onInteraction() }) {
                    Icon(if (mpv.paused) Icons.Default.PlayArrow else PauseIcon, if (mpv.paused) "Play" else "Pause", Modifier.size(32.dp), tint = Color.White)
                }
                if (next != null) IconButton(onClick = { onPlay(next.id.toString()) }) { Icon(NextIcon, "Next episode", tint = Color.White) }
                Text("${formatMs(shown)} / ${formatMs(durationMs)}  (buffered ${formatMs(cached)})", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = shown.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    onValueChange = { dragging = it; onInteraction() },
                    onValueChangeFinished = { if (dragging >= 0) onSeek(dragging.toLong(), false); dragging = -1f },
                    modifier = Modifier.weight(1f),
                )
                if (skip != null) FilledTonalButton(skip.onClick, Modifier.padding(start = 8.dp)) { Text(skip.label) }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    DropdownMenuItem({ Text(label) }, onClick, trailingIcon = { if (selected) Icon(Icons.Default.Check, null) })

private fun speedLabel(s: Double) = "${s.toString().removeSuffix(".0")}×"

/** "English · 5.1", the track's own title first. */
private fun trackLabel(t: Mpv.Track): String {
    val language = t.language?.takeUnless { it == "und" }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
    val channels = when (t.channels) { null -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${t.channels} ch" }
    return listOfNotNull(t.title, language?.takeUnless { t.title?.contains(it, ignoreCase = true) == true }, channels).joinToString(" · ")
}
