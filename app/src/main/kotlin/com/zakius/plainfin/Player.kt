@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zakius.plainfin

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import plainfin.core.TICKS_PER_SECOND

private const val REPORT_INTERVAL_MS = 10_000L
private const val RESUME_REWIND_MS = 5_000L
private const val SEEK_MS_PER_WIDTH = 100_000f

/** Chosen speed, kept for the rest of the session so "Play next" does not reset it. */
private var sessionSpeed = 1f

// Material icons missing from material-icons-core; extended would add thousands of classes for five paths.
private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
private val PauseIcon = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
private val RewindIcon = icon("FastRewind", "M11,18V6l-8.5,6 8.5,6zm0.5,-6l8.5,6V6l-8.5,6z")
private val ForwardIcon = icon("FastForward", "M4,18l8.5,-6L4,6v12zm9,-12v12l8.5,-6L13,6z")
private val NextIcon = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val FullscreenIcon = icon("Fullscreen", "M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
private val FullscreenExitIcon = icon("FullscreenExit", "M5,16h3v3h2v-5H5v2zm3,-8H5v2h5V5H8v3zm6,11h2v-3h3v-2h-5v5zm2,-11V5h-2v5h5V8h-3z")

/** Loads the item (server first, download metadata when offline) and the episodes that can follow it. */
@Composable
fun PlayerScreen(itemId: String, fromStart: Boolean, onBack: () -> Unit, onPlay: (String) -> Unit) {
    var item by remember { mutableStateOf<BaseItemDto?>(null) }
    var siblings by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var online by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemId) {
        val server = App.server
        val loaded = runCatching { server.flush(); server.item(itemId) }.getOrNull()
        online = loaded != null
        // A finished download plays as downloaded, even if the server's file changed since.
        val downloaded = Media.download(itemId)?.item
        item = when {
            downloaded != null && Media.isComplete(Media.key(downloaded)) -> downloaded.copy(userData = loaded?.userData ?: downloaded.userData)
            else -> loaded ?: downloaded
        }
        if (item == null) error = "Server unreachable and this item is not downloaded."
    }
    // Re-fetched after "Mark played" in the episode list.
    LaunchedEffect(item, refresh) {
        val it = item ?: return@LaunchedEffect
        val server = App.server
        val seriesId = it.seriesId?.toString()
        if (it.type == BaseItemKind.EPISODE && seriesId != null) {
            siblings = (if (online) runCatching { server.episodes(seriesId) }.getOrNull() else null)
                ?: Media.downloads().map { d -> d.item }.filter { d -> d.seriesId == it.seriesId }
                    .sortedWith(compareBy({ d -> d.parentIndexNumber }, { d -> d.indexNumber }))
        }
    }
    val current = item
    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!)
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        current == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        else -> Playback(current, siblings, fromStart, online, onBack, onPlay, onChanged = { refresh++ })
    }
}

@Composable
private fun Playback(
    item: BaseItemDto,
    siblings: List<BaseItemDto>,
    fromStart: Boolean,
    online: Boolean,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current!!
    val prefs = App.prefs
    val id = item.id.toString()
    val source = item.mediaSources!!.first()
    val sourceId = source.id!!
    val seriesKey = item.seriesId?.toString() ?: id
    val runtimeTicks = item.runTimeTicks ?: source.runTimeTicks
    val serverPlayed = item.userData?.played ?: false
    val playable = siblings.filter { it.parentIndexNumber != 0 }
    val next = playable.getOrNull(playable.indexOfFirst { it.id == item.id } + 1)
        ?.takeIf { playable.any { p -> p.id == item.id } }

    var transcoding by remember { mutableStateOf(false) }
    var transcodeFromMs by remember { mutableLongStateOf(0L) }
    var playerError by remember { mutableStateOf<PlaybackException?>(null) }
    var ended by remember { mutableStateOf(false) }
    var intro by remember { mutableStateOf<MediaSegmentDto?>(null) }
    val scope = rememberCoroutineScope()

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleScale / 100f)
            // Text subtitles: white with a black outline, no box. Embedded styles (italics, cue colours) stay on.
            subtitleView?.setStyle(CaptionStyleCompat(
                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
            ))
        }
    }
    val player = remember(transcoding) { buildPlayer(context, playerView, transcoding) }

    // Prepare: external subtitles first (cached on disk), then the media item at the resume point.
    LaunchedEffect(player) {
        val subs = withContext(Dispatchers.IO) { Media.fetchSubtitles(item) }
        val streams = externalSubs(item).associateBy { it.index }
        val url = if (transcoding) App.server.transcodeUrl(id, sourceId, null) else App.server.streamUrl(id, sourceId)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(Media.key(item))
            .setSubtitleConfigurations(subs.mapNotNull { (index, file) ->
                val stream = streams[index] ?: return@mapNotNull null
                val mime = when (file.extension) {
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> return@mapNotNull null
                }
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(mime)
                    .setLanguage(stream.language)
                    .setLabel(stream.displayTitle)
                    .setId("ext${stream.index}")
                    .build()
            })
            .build()
        val resumeMs = if (fromStart) 0 else (item.localState().first?.positionTicks ?: 0) / (TICKS_PER_SECOND / 1000)
        val startMs = if (transcoding) transcodeFromMs else (resumeMs - RESUME_REWIND_MS).coerceAtLeast(0)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            prefs.audioLang(seriesKey)?.let { setPreferredAudioLanguage(it) }
            when (val sub = prefs.subLang(seriesKey)) {
                null -> {}
                "" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(sub)
            }
        }.build()
        player.setPlaybackSpeed(sessionSpeed)
        player.setMediaItem(mediaItem, startMs)
        player.prepare()
        player.play()
        Media.playing = Media.key(item)
        App.server.reportStart(id, sourceId, startMs * 10_000, transcoding)
        if (online) intro = runCatching { App.server.introSegment(id) }.getOrNull()
    }

    fun positionTicks() = player.currentPosition * 10_000
    fun report() = scope.launch { App.server.reportProgress(id, sourceId, positionTicks(), !player.isPlaying, transcoding, serverPlayed) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playbackState == Player.STATE_READY) report()
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) report()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !transcoding) {
                    // The player only reads the cache, so the length may not be recorded yet; the server knows it.
                    val len = Media.contentLength(Media.key(item)).takeIf { it > 0 } ?: source.size ?: 0
                    val fraction = player.currentPosition.toDouble() / player.duration.coerceAtLeast(1)
                    Media.startBuffer(item, if (len > 0) (len * (fraction - 0.05)).toLong().coerceAtLeast(0) else 0)
                }
                if (state == Player.STATE_ENDED && !ended) {
                    ended = true
                    val end = runtimeTicks ?: positionTicks()
                    App.server.reportStop(id, sourceId, end, runtimeTicks, serverPlayed)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val decodeProblem = error.errorCode in listOf(
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                )
                if (decodeProblem && !transcoding && online) {
                    transcodeFromMs = player.currentPosition
                    transcoding = true
                }
                else playerError = error
            }
        }
        player.addListener(listener)
        playerView.player = player
        onDispose {
            player.removeListener(listener)
            if (!ended) {
                App.server.reportStop(id, sourceId, positionTicks(), runtimeTicks, serverPlayed)
            }
            Media.stopBuffer(id)
            Media.playing = null
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            if (player.isPlaying) report()
        }
    }

    // Leaving the app pauses and saves; there is no background playback.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var forcedFullscreen by remember { mutableStateOf(false) }
    var rotationLocked by remember { mutableStateOf(false) }
    val fullscreen = landscape || forcedFullscreen
    fun setFullscreen(on: Boolean) {
        forcedFullscreen = on
        if (!rotationLocked) activity.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }
    DisposableEffect(fullscreen) {
        systemBars(activity, hidden = fullscreen)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            systemBars(activity, hidden = false)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
        }
    }
    BackHandler { if (fullscreen && !landscape) setFullscreen(false) else onBack() }

    val video = @Composable { mod: Modifier ->
        VideoSurface(
            mod, player, playerView, fullscreen, transcoding, Media.key(item).takeUnless { transcoding }, intro, ended, next, playerError,
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            onBack = onBack,
            onNext = next?.let { { onPlay(it.id.toString()) } },
            onRetry = { playerError = null; player.prepare() },
            rotationLocked = rotationLocked,
            onRotationLock = {
                rotationLocked = !rotationLocked
                activity.requestedOrientation = if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            },
            onTracksChosen = { audio, sub -> prefs.setTracks(seriesKey, audio, sub) },
        )
    }

    val downloads = rememberDownloadStatuses()
    if (fullscreen) {
        video(Modifier.fillMaxSize())
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                item {
                    Text(item.seriesName ?: item.name.orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    if (item.type == BaseItemKind.EPISODE) Text(episodeLabel(item), style = MaterialTheme.typography.titleMedium)
                    item.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                }
                items(siblings.filter { it.seasonId == item.seasonId }, key = { it.id }) { ep ->
                    EpisodeRow(ep, downloads?.get(ep.id.toString()), highlighted = ep.id == item.id, onPlay = { onPlay(ep.id.toString()) }, onChanged = onChanged)
                }
            }
        }
    }
}

private fun buildPlayer(context: Context, view: PlayerView, transcoding: Boolean): ExoPlayer {
    val renderers = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    // The RAM buffer stays modest; buffering to the end happens on disk through FillWorker.
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 1_000, 2_000).build()
    return ExoPlayer.Builder(context)
        .setLoadControl(load)
        .setHandleAudioBecomingNoisy(true)
        .buildWithAssSupport(
            context,
            renderType = AssRenderType.OVERLAY_OPEN_GL,
            subtitleView = view.subtitleView,
            dataSourceFactory = if (transcoding) Media.upstreamFactory() else Media.playerFactory(),
            renderersFactory = renderers,
        )
}

private fun systemBars(activity: Activity, hidden: Boolean) {
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

fun episodeLabel(ep: BaseItemDto) = "S${ep.parentIndexNumber ?: "?"}E${ep.indexNumber ?: "?"} · ${ep.name.orEmpty()}"

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun VideoSurface(
    modifier: Modifier,
    player: ExoPlayer,
    playerView: PlayerView,
    fullscreen: Boolean,
    transcoding: Boolean,
    cacheKey: String?,
    intro: MediaSegmentDto?,
    ended: Boolean,
    next: BaseItemDto?,
    playerError: PlaybackException?,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    onNext: (() -> Unit)?,
    onRetry: () -> Unit,
    rotationLocked: Boolean,
    onRotationLock: () -> Unit,
    onTracksChosen: (audio: String?, sub: String?) -> Unit,
) {
    val prefs = App.prefs
    val activity = LocalActivity.current!!
    val audio = remember { activity.getSystemService(AudioManager::class.java) }
    var controls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var crop by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val introRange = intro?.let { it.startTicks / 10_000 until it.endTicks / 10_000 - 1_000 }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            buffered = maxOf(player.bufferedPosition, cacheKey?.let { Media.cachedUntilMs(it, position, duration) } ?: 0)
            playing = player.isPlaying
            // Idle is the time before the first prepare (subtitles are fetched first), or after an error.
            waiting = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            if (controls && playing && System.currentTimeMillis() - lastInteraction > 3_000) controls = false
            delay(250)
        }
    }
    LaunchedEffect(overlay) { if (overlay != null) { delay(800); overlay = null } }
    LaunchedEffect(crop) {
        playerView.resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceIn(0, duration))
        overlay = (if (ms > 0) "+" else "−") + "${abs(ms) / 1000}s"
    }

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(fullscreen) {
                detectTapGestures(
                    onTap = { controls = !controls; lastInteraction = System.currentTimeMillis() },
                    onDoubleTap = { offset ->
                        if (prefs.gestureDoubleTap) seekBy((if (offset.x < size.width / 2) -1 else 1) * prefs.doubleTapSeconds * 1000L)
                    },
                )
            }
            .pointerInput(fullscreen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val zone = when {
                        down.position.x < w / 3 -> 0
                        down.position.x > w * 2 / 3 -> 2
                        else -> 1
                    }
                    var axis = 0 // 0 undecided, 1 horizontal, 2 vertical, 3 pinch
                    var total = androidx.compose.ui.geometry.Offset.Zero
                    var startSpan = 0f
                    val startPos = player.currentPosition
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val startBright = activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                    val brightnessZone = if (prefs.brightnessOnLeft) 0 else 2
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && prefs.gesturePinch && fullscreen) {
                            val span = (pressed[0].position - pressed[1].position).getDistance()
                            if (axis != 3) { axis = 3; startSpan = span }
                            if (span > startSpan * 1.15f) crop = true
                            if (span < startSpan * 0.85f) crop = false
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (axis == 3) continue
                        val change = pressed[0]
                        total += change.positionChange()
                        if (axis == 0 && total.getDistance() > viewConfiguration.touchSlop) axis = if (abs(total.x) > abs(total.y)) 1 else 2
                        when {
                            axis == 1 && fullscreen && prefs.gestureSeek -> {
                                val target = (startPos + (total.x / w * SEEK_MS_PER_WIDTH).toLong()).coerceIn(0, duration)
                                scrubTarget = target
                                overlay = "${formatMs(target)} / ${formatMs(duration)}"
                                change.consume()
                            }
                            axis == 2 && !fullscreen && prefs.gestureFullscreen -> change.consume()
                            axis == 2 && fullscreen && zone == 1 && prefs.gestureFullscreen -> change.consume()
                            axis == 2 && fullscreen && zone != 1 && prefs.gestureLevels -> {
                                val delta = -total.y / (h * 0.8f)
                                if (zone == brightnessZone) {
                                    val b = (startBright + delta).coerceIn(0.01f, 1f)
                                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = b }
                                    overlay = "Brightness ${(b * 100).toInt()}%"
                                } else {
                                    val v = (startVol + delta * maxVol).toInt().coerceIn(0, maxVol)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    overlay = "Volume $v / $maxVol"
                                }
                                change.consume()
                            }
                        }
                    }
                    scrubTarget?.let { player.seekTo(it) }
                    scrubTarget = null
                    if (axis == 2 && prefs.gestureFullscreen) {
                        val threshold = h * 0.15f
                        if (!fullscreen && total.y < -threshold) onToggleFullscreen()
                        if (fullscreen && zone == 1 && total.y > threshold) onToggleFullscreen()
                    }
                }
            }
    ) {
        AndroidView({ playerView }, Modifier.fillMaxSize())

        overlay?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), MaterialTheme.shapes.small).padding(12.dp))
        }

        if (waiting && playerError == null && !ended) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        if (introRange != null && position in introRange && !ended) {
            FilledTonalButton(onClick = { player.seekTo(introRange.last + 1_000) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) { Text("Skip intro") }
        }

        if (playerError != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                val known = playbackError(playerError)
                ErrorText(known ?: "Playback failed.", playerError.errorCodeName.takeIf { known == null }, Color.White)
                Button(onClick = onRetry) { Text("Retry") }
            }
        }

        if (ended) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                if (next != null && onNext != null) Button(onClick = onNext) {
                    Text("Play next: ${episodeLabel(next)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(onClick = onBack) { Text("Back") }
            }
        } else if (controls) {
            Controls(
                player, position, duration, buffered, playing, fullscreen, transcoding, rotationLocked,
                onBack, onToggleFullscreen, onRotationLock, onTracksChosen, ::seekBy, onNext,
                onInteraction = { lastInteraction = System.currentTimeMillis() },
                subtitleView = playerView.subtitleView,
            )
        }
    }
}

@Composable
private fun Controls(
    player: ExoPlayer,
    position: Long,
    duration: Long,
    buffered: Long,
    playing: Boolean,
    fullscreen: Boolean,
    transcoding: Boolean,
    rotationLocked: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onRotationLock: () -> Unit,
    onTracksChosen: (String?, String?) -> Unit,
    onSeek: (Long) -> Unit,
    onNext: (() -> Unit)?,
    onInteraction: () -> Unit,
    subtitleView: SubtitleView?,
) {
    var menu by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableFloatStateOf(-1f) }
    // Auto-hide would close an open menu.
    LaunchedEffect(menu) { while (menu) { onInteraction(); delay(1_000) } }
    Box(Modifier.fillMaxSize().background(Color(0x55000000)).safeDrawingPadding()) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            if (transcoding) Text("Transcoding: buffering to the end is best-effort", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true; onInteraction() }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false; sub = null }) {
                    when (sub) {
                        null -> {
                            DropdownMenuItem({ Text("Speed: ${player.playbackParameters.speed}x") }, { sub = "speed" })
                            DropdownMenuItem({ Text("Audio") }, { sub = "audio" })
                            DropdownMenuItem({ Text("Subtitles") }, { sub = "text" })
                            DropdownMenuItem({ Text("Subtitle size: ${App.prefs.subtitleScale}%") }, { sub = "size" })
                            DropdownMenuItem({ Text(if (rotationLocked) "Unlock rotation" else "Lock rotation") }, { onRotationLock(); menu = false })
                        }
                        "speed" -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            Choice("${s}x", player.playbackParameters.speed == s) { sessionSpeed = s; player.setPlaybackSpeed(s); menu = false; sub = null }
                        }
                        "size" -> listOf(50, 75, 100, 125, 150, 200).forEach { s ->
                            Choice("$s%", App.prefs.subtitleScale == s) {
                                App.prefs.subtitleScale = s
                                subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s / 100f)
                                menu = false; sub = null
                            }
                        }
                        "audio", "text" -> {
                            val type = if (sub == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                            if (type == C.TRACK_TYPE_TEXT) Choice("Off", C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                onTracksChosen(selectedLanguage(player, C.TRACK_TYPE_AUDIO), "")
                                menu = false; sub = null
                            }
                            player.currentTracks.groups.filter { it.type == type }.forEach { group ->
                                for (i in 0 until group.length) {
                                    val f = group.getTrackFormat(i)
                                    val label = listOfNotNull(f.label, f.language, f.codecs).joinToString(" · ").ifEmpty { "Track ${i + 1}" }
                                    Choice(label, group.isTrackSelected(i)) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                            .setTrackTypeDisabled(type, false)
                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                            .build()
                                        val audioLang = if (type == C.TRACK_TYPE_AUDIO) f.language else selectedLanguage(player, C.TRACK_TYPE_AUDIO)
                                        val subLang = if (type == C.TRACK_TYPE_TEXT) f.language.orEmpty() else selectedLanguage(player, C.TRACK_TYPE_TEXT)
                                        onTracksChosen(audioLang, subLang)
                                        // Re-read from here so the current subtitle line shows now, not at the next one.
                                        if (type == C.TRACK_TYPE_TEXT) player.seekTo(player.currentPosition)
                                        menu = false; sub = null
                                    }
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

        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            val step = App.prefs.doubleTapSeconds
            IconButton(onClick = { onSeek(-step * 1000L); onInteraction() }) { Icon(RewindIcon, "Back $step seconds", tint = Color.White) }
            IconButton(onClick = { if (playing) player.pause() else player.play(); onInteraction() }) {
                Icon(if (playing) PauseIcon else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
            }
            IconButton(onClick = { onSeek(step * 1000L); onInteraction() }) { Icon(ForwardIcon, "Forward $step seconds", tint = Color.White) }
            if (onNext != null) IconButton(onClick = onNext) { Icon(NextIcon, "Next episode", tint = Color.White) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
            val shown = if (dragging >= 0) dragging.toLong() else position
            Text("${formatMs(shown)} / ${formatMs(duration)}  (buffered ${formatMs(buffered)})", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = shown.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                onValueChange = { dragging = it; onInteraction() },
                onValueChangeFinished = { player.seekTo(dragging.toLong()); dragging = -1f },
            )
        }
    }
}

/** A menu entry ticked when it is the current choice. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    DropdownMenuItem({ Text(label) }, onClick, trailingIcon = { if (selected) Icon(Icons.Default.Check, null) })

/** Language of the selected track, "" when that type is off, null when unknown. */
private fun selectedLanguage(player: ExoPlayer, type: Int): String? {
    if (player.trackSelectionParameters.disabledTrackTypes.contains(type)) return ""
    return player.currentTracks.groups.filter { it.type == type && it.isSelected }
        .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
}

/** A plain sentence for common playback failures; null for the rest. */
private fun playbackError(e: PlaybackException) = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Can't reach the server."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT -> "The server took too long to respond."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "The server returned an error" + ((e.cause as? HttpDataSource.InvalidResponseCodeException)?.let { " (${it.responseCode})." } ?: ".")
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is missing."
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
        "This device can't play this file."
    else -> null
}
