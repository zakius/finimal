@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zakius.finimal

import finimal.core.chapterAt
import finimal.core.Reconnect
import android.os.Looper
import android.os.Handler
import android.net.Network
import android.net.ConnectivityManager
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import java.util.Locale
import java.util.UUID
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import finimal.core.ResumeGuard
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
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
import org.jellyfin.sdk.model.api.MediaSegmentType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import finimal.core.TICKS_PER_MS

private const val REPORT_INTERVAL_MS = 10_000L
private const val RESUME_REWIND_MS = 5_000L
private const val SEEK_MS_PER_WIDTH = 100_000f
/**
 * How long the start waits for subtitle files. The server extracts all of a file's text tracks in one pass: about 1 s
 * for a 2 GB file at home, longer over a slow link or while the server also starts a transcode.
 */
private const val SUBTITLE_WAIT_MS = 10_000L
/** How far behind the playhead the disk buffer starts, so seeking back a little stays local. */
private const val BUFFER_BEHIND_BYTES = 32L * 1000 * 1000

/** Chosen speed, kept for the rest of the session so "Play next" does not reset it. */
private var sessionSpeed = 1f

/** Forced fullscreen and rotation lock, handed from a player to the "Play next" one so the screen doesn't flip in between. */
private var carried: Pair<Boolean, Boolean>? = null

// Material icons that material-icons-core lacks. The extended icon set would add thousands of classes for six paths.
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
fun PlayerScreen(itemId: String, onBack: () -> Unit, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current!!
    val carry = remember { carried }
    // Pre-build the VideoView and initial ExoPlayer immediately so player/OpenGL/libass setup overlaps the item fetch.
    val prewarmView = remember {
        VideoView(context).apply {
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * App.prefs.subtitleScale / 100f)
            subtitleView.setStyle(CaptionStyleCompat(
                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
            ))
        }
    }
    val prewarmPlayer = remember { buildPlayer(context, prewarmView) }
    val playerHandedOff = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        // Cleared only once this screen is composed, which is after the previous player's onDispose below has read it.
        carried = null
        onDispose {
            if (!playerHandedOff[0]) prewarmPlayer.release()
            if (carried == null) {
                systemBars(activity, hidden = false)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
            }
        }
    }
    // Instant Frame 1 start when a fresh prefetch is already in memory and the item is not a local download.
    val instantItem = remember(itemId) {
        val (known, dl) = Media.cachedDownload(itemId)
        if (known && dl == null) AppCache.getFreshItem(itemId)?.takeIf { !it.mediaSources.isNullOrEmpty() } else null
    }
    var item by remember { mutableStateOf<BaseItemDto?>(instantItem) }
    var download by remember { mutableStateOf<Download?>(null) }
    var localPlaylist by remember { mutableStateOf<java.io.File?>(null) }
    var siblings by remember {
        val initialSeriesId = instantItem?.seriesId?.toString()
        val cachedSiblings = if (initialSeriesId != null) {
            AppCache.getList(AppCache.listKey(initialSeriesId, "episodes"))?.items.orEmpty()
        } else {
            emptyList()
        }
        mutableStateOf(cachedSiblings)
    }
    var online by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemId, retry) {
        error = null
        val server = App.server
        if (retry == 0 && instantItem != null) {
            // Item is already fresh from prefetch; flush any unrelated pending states in the background.
            App.platform.scope.launch { server.flush() }
            return@LaunchedEffect
        }
        val dl = withContext(Dispatchers.IO) { Media.download(itemId) }
        val playlist = withContext(Dispatchers.IO) { if (dl != null) Media.playlist(dl) else null }
        val downloaded = dl?.item
        val complete = dl != null && withContext(Dispatchers.IO) { Media.isDone(dl) }
        download = dl
        localPlaylist = playlist
        // Outside this screen's scope, so giving up on a slow server below doesn't cut a flush short.
        val process = App.platform.scope
        val fetch = process.async {
            runCatching {
                // Only this item's unsynced state must reach the server before the item is read; the rest syncs in
                // parallel. The check runs on App.disk so it sees a stop that the previous player recorded on exit.
                val pending = withContext(App.disk) { App.pending.get(itemId) != null }
                if (!pending) process.launch { server.flush() }
                AppCache.fetchOrJoinItem(itemId, flushFirst = pending)
            }
        }
        // A finished download doesn't wait out network timeouts; it plays offline if the server is slow to answer.
        val fetched = if (complete) withTimeoutOrNull(2_000) { fetch.await() } else fetch.await()
        val result = fetched ?: Result.failure(java.util.concurrent.TimeoutException("server slow to answer"))
        result.onFailure { e ->
            if (e is InvalidStatusException && e.status == 401) server.expire()
        }
        val loaded = result.getOrNull()
        online = loaded != null
        // A finished download plays as downloaded, even if the server's file changed since.
        val shown = if (complete) {
            val local = downloaded!!
            val userData = loaded?.userData ?: local.userData
            local.copy(userData = userData)
        } else {
            loaded ?: downloaded
        }
        // A missing episode the server lists (no file behind it) has no source to play.
        if (shown != null && shown.mediaSources.isNullOrEmpty()) {
            error = java.io.FileNotFoundException("This item has no file on the server")
        } else {
            item = shown
            if (shown == null) error = result.exceptionOrNull()
        }
    }
    // The episode list; starts immediately from AppCache when coming from Series/Season, and [refresh] re-fetches after "Mark played".
    LaunchedEffect(item, refresh) {
        val episode = item ?: return@LaunchedEffect
        val server = App.server
        val seriesId = episode.seriesId?.toString()
        if (episode.type == BaseItemKind.EPISODE && seriesId != null) {
            val cacheKey = AppCache.listKey(seriesId, "episodes")
            val cachedEntry = AppCache.getList(cacheKey)
            if (cachedEntry != null && siblings.isEmpty()) siblings = cachedEntry.items
            if (refresh == 0 && cachedEntry != null && AppCache.isListFresh(cachedEntry, maxAgeMs = 15_000L)) {
                return@LaunchedEffect
            }
            val fromServer = if (online) runCatching { server.episodes(seriesId) }.getOrNull() else null
            if (fromServer != null) {
                AppCache.putList(cacheKey, fromServer, fromNetwork = true)
                siblings = fromServer
            } else if (siblings.isEmpty()) {
                val downloads = withContext(Dispatchers.IO) { Media.downloads() }
                siblings = downloads
                    .map { d -> d.item }
                    .filter { d -> d.seriesId == episode.seriesId }
                    .sortedWith(compareBy({ d -> d.parentIndexNumber }, { d -> d.indexNumber }))
            }
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
        else -> Playback(
            current,
            siblings,
            download,
            localPlaylist,
            prewarmView,
            prewarmPlayer,
            onTakeInitialPlayer = { playerHandedOff[0] = true },
            carry,
            online,
            onBack,
            onPlay,
            onChanged = { refresh++ },
        )
    }
}

@Composable
private fun Playback(
    item: BaseItemDto,
    siblings: List<BaseItemDto>,
    download: Download?,
    localPlaylist: java.io.File?,
    playerView: VideoView,
    initialPlayer: ExoPlayer,
    onTakeInitialPlayer: () -> Unit,
    carry: Pair<Boolean, Boolean>?,
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
    val serverPlayed = item.userData?.played ?: false
    val next = remember(item.id, siblings) { nextEpisode(item, siblings) }
    val chapters = remember(item) { chapters(item) }

    val videoStream = remember(source) { videoInfo(source) }
    val cacheKey = remember(item) { Media.key(item) }
    // A file the device can't decode starts as a transcode. A direct download plays as it is; if it then fails to
    // decode, playback still switches to a transcode.
    val initialTranscoding = remember { online && localPlaylist == null && download?.transcoded != false && !playsDirectly(item) }
    var transcoding by remember { mutableStateOf(initialTranscoding) }
    // Where a direct play switched to a transcode; null for a play that started as one, which resumes like any other.
    var transcodeFromMs by remember { mutableStateOf<Long?>(null) }
    var playerError by remember { mutableStateOf<PlaybackException?>(null) }
    var reconnect by remember { mutableStateOf<Reconnect>(Reconnect.Idle) }
    var ended by remember { mutableStateOf(false) }
    var segments by remember { mutableStateOf(AppCache.getCachedSegments(id).orEmpty()) }
    var startTicks by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var seeked by remember { mutableStateOf(false) }
    // What the last progress report sent. Leaving paused at that spot is an idle stop (Server.reportStop).
    var reportedTicks by remember { mutableLongStateOf(-1L) }
    var resumedAt by remember { mutableStateOf<Long?>(null) }
    // Leaving through "Play next" in the credits counts as watched to the end.
    var creditsDone by remember { mutableStateOf(false) }
    var choppy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val player = remember(transcoding) {
        if (transcoding == initialTranscoding) {
            onTakeInitialPlayer()
            initialPlayer
        } else {
            buildPlayer(context, playerView)
        }
    }
    // A new session ID per player. Reports carry it so the server ties them to the transcode job: a stop ends that job,
    // and the stop from an earlier direct-play player can't end the transcode that replaced it.
    val playSession = remember(player) { UUID.randomUUID().toString() }

    // Speculatively prefetch the next episode (metadata, segments, subtitles) once current playback is underway.
    LaunchedEffect(next?.id) {
        val upcoming = next ?: return@LaunchedEffect
        delay(2_000)
        AppCache.prefetchPlayable(upcoming.id.toString())
    }

    // Fetch external subtitles (overlapped with Scratch header pre-warming and segments), then load the media item.
    LaunchedEffect(player) {
        // Set before anything else, so eviction and subtitle cleanup leave the playing item alone.
        Media.playing = cacheKey
        if (online && segments.isEmpty()) {
            launch { segments = runCatching { AppCache.fetchOrJoinSegments(id) }.getOrDefault(emptyList()) }
        }
        // A transcode, live or downloaded, has no embedded subtitles: those come as files too.
        val sideload = transcoding || localPlaylist != null
        val sideloadStreams = sideloadedSubs(item, sideload)
        val prewarm = if (!transcoding && localPlaylist == null && sideloadStreams.isNotEmpty()) {
            withContext(Dispatchers.IO) { Media.prewarmRead(item) }
        } else {
            null
        }
        try {
            val subs = if (sideloadStreams.isEmpty()) {
                emptyMap()
            } else {
                // A slow server holds up the start for at most SUBTITLE_WAIT_MS; after that the episode plays without these
                // subtitles, and the fetch finishes on its own so the files are there next time.
                val fetch = App.platform.scope.async { Media.fetchSubtitles(item, sideload) }
                withTimeoutOrNull(SUBTITLE_WAIT_MS) { fetch.await() } ?: emptyMap()
            }
            val streams = sideloadStreams.associateBy { it.index }
            val startSub = startSubtitle(source, prefs.subLang(seriesKey))?.index
            val audioIndex = prefs.audioLang(seriesKey)?.let { audioIndex(source, it) }
            val url = if (transcoding) {
                val burnIn = burnInIndex(source, prefs.subLang(seriesKey))
                App.server.transcodeUrl(id, sourceId, audioIndex, burnIn, playSession)
            } else if (localPlaylist != null) {
                Uri.fromFile(localPlaylist).toString()
            } else {
                withContext(Dispatchers.IO) { Media.streamUrl(item) }
            }
            val subtitleConfigurations = subs.mapNotNull { (index, file) ->
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
                    // A file has none of the stream's flags, and Media3 only picks a text track flagged default or in a
                    // preferred language. Without this, a transcode starts with subtitles off until a language is remembered.
                    .setSelectionFlags(if (stream.index == startSub) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitleConfigurations)
                .build()
            val savedTicks = item.localState().first?.positionTicks ?: 0L
            val resumeMs = savedTicks / TICKS_PER_MS
            val startMs = transcodeFromMs ?: (resumeMs - RESUME_REWIND_MS).coerceAtLeast(0)
            startTicks = startMs * TICKS_PER_MS
            startedAt = SystemClock.elapsedRealtime()
            reportedTicks = -1L
            if (transcodeFromMs == null && startMs > 0) resumedAt = startMs
            val trackParams = player.trackSelectionParameters.buildUpon()
            val audioLang = prefs.audioLang(seriesKey)
            if (audioLang != null) trackParams.setPreferredAudioLanguage(audioLang)
            when (val sub = prefs.subLang(seriesKey)) {
                null -> {}
                "" -> trackParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> {
                    trackParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    trackParams.setPreferredTextLanguage(sub)
                }
            }
            player.trackSelectionParameters = trackParams.build()
            player.setPlaybackSpeed(sessionSpeed)
            player.setMediaItem(mediaItem, startMs)
            player.prepare()
            player.play()
            launch { App.server.reportStart(id, sourceId, startMs * TICKS_PER_MS, transcoding, playSession) }
        } finally {
            prewarm?.close()
        }
    }

    fun runtimeTicks(): Long? {
        val known = item.runTimeTicks ?: source.runTimeTicks
        if (known != null) return known
        val durationMs = player.duration
        if (durationMs > 0) return durationMs * TICKS_PER_MS
        return null
    }
    fun positionTicks() = ResumeGuard.position(startTicks, player.currentPosition * TICKS_PER_MS, runtimeTicks(),
        SystemClock.elapsedRealtime() - startedAt, seeked)
    fun report() {
        val ticks = positionTicks()
        reportedTicks = ticks
        scope.launch { App.server.reportProgress(id, sourceId, ticks, !player.isPlaying, transcoding, serverPlayed, playSession) }
    }
    fun switchToTranscode() {
        transcodeFromMs = player.currentPosition
        transcoding = true
    }
    // Let the screen time out while paused, after the end, or after a failure that is no longer being retried.
    fun keepScreenOn() {
        playerView.keepScreenOn = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            (player.playerError == null || reconnect is Reconnect.Retrying)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playbackState == Player.STATE_READY) report()
            }

            override fun onEvents(player: Player, events: Player.Events) = keepScreenOn()

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seeked = true
                    report()
                }
            }

            // With no decoder for the video at all, Media3 raises no error and plays the audio over black.
            override fun onTracksChanged(tracks: Tracks) {
                if (!transcoding && online &&
                    tracks.containsType(C.TRACK_TYPE_VIDEO) &&
                    !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)
                ) {
                    switchToTranscode()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) reconnect = Reconnect.Idle
                // Seeking back from the end screen plays on.
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) ended = false
                if (state == Player.STATE_READY && !transcoding && localPlaylist == null) {
                    val fraction = player.currentPosition.toDouble() / player.duration.coerceAtLeast(1)
                    // Runs on App.buffers, like the stop on leaving, so a start can never land after that stop.
                    scope.launch(App.buffers) {
                        val cachedLen = Media.lengthOf(cacheKey)
                        val len = if (cachedLen > 0) cachedLen else (source.size ?: 0L)
                        // A fixed margin rather than a share of the file, which would be a gigabyte behind on a big remux.
                        val from = if (len > 0) ((len * fraction).toLong() - BUFFER_BEHIND_BYTES).coerceAtLeast(0) else 0L
                        Media.startBuffer(item, from)
                    }
                }
                if (state == Player.STATE_ENDED && !ended) {
                    ended = true
                    val runtime = runtimeTicks()
                    App.server.reportStop(id, sourceId, runtime ?: positionTicks(), runtime, serverPlayed, idle = false, playSession)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Media3 reports a network failure only once the buffer is spent. Keep retrying from the same position
                // for a while.
                if (isNetworkFailure(error)) {
                    val now = System.currentTimeMillis()
                    val retrying = reconnect.failed(now)
                    val wait = retrying.delayMs(now)
                    if (wait != null) {
                        reconnect = retrying
                        scope.launch {
                            delay(wait)
                            // A network callback's reload may have recovered meanwhile; then there is nothing to count.
                            val current = reconnect
                            if (current is Reconnect.Retrying) reconnect = current.reloaded()
                            if (player.playbackState == Player.STATE_IDLE) player.reload()
                        }
                        return
                    }
                    reconnect = Reconnect.GaveUp
                } else {
                    reconnect = Reconnect.Idle
                }
                val decodeProblem = error.errorCode in listOf(
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                )
                if (decodeProblem && !transcoding && online) switchToTranscode()
                else playerError = error
            }
        }
        // A weak decoder drops frames without failing. Two batches of 50 or more dropped frames within 30 seconds, at
        // normal speed, make the player offer a transcode.
        var lastDrop = 0L
        val drops = object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (transcoding || !online || player.playbackParameters.speed != 1f || droppedFrames < 50) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastDrop < 30_000) choppy = true
                lastDrop = now
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(drops)
        playerView.player = player
        // The audio delay for headphones connected during playback takes effect only after a seek, as in the menu.
        AudioDelay.onWirelessChanged = { if (prefs.audioDelayMs != 0) player.seekTo(player.currentPosition) }
        onDispose {
            AudioDelay.onWirelessChanged = null
            player.removeListener(listener)
            // If the user left before the item loaded there is no position, and reporting 0 would wipe the resume point.
            if (!ended && player.currentMediaItem != null) {
                val runtime = runtimeTicks()
                val stopTicks = if (creditsDone && runtime != null) runtime else positionTicks()
                val idle = !player.isPlaying && stopTicks == reportedTicks
                App.server.reportStop(id, sourceId, stopTicks, runtime, serverPlayed, idle, playSession)
            }
            App.platform.scope.launch(App.buffers) { Media.stopBuffer(id) }
            Media.playing = null
            player.release()
        }
    }

    // When a network comes back, retry immediately rather than at the next backoff step.
    val retrying = reconnect is Reconnect.Retrying
    DisposableEffect(player, retrying) {
        if (!retrying) return@DisposableEffect onDispose {}
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        // Registration reports the current network immediately. Ignore it, since the backoff already retries on it.
        val initial = cm.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == initial) return
                main.post {
                    if (player.playbackState == Player.STATE_IDLE) player.reload()
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            if (player.isPlaying) report()
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var forcedFullscreen by remember { mutableStateOf(carry?.first ?: false) }
    var rotationLocked by remember { mutableStateOf(carry?.second ?: false) }
    val fullscreen = landscape || forcedFullscreen
    val hideBars by rememberUpdatedState(fullscreen)

    // Leaving the app pauses and saves; there is no background playback. Some ROMs bring the bars back on return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
            if (event == Lifecycle.Event.ON_RESUME) systemBars(activity, hideBars)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun setFullscreen(on: Boolean) {
        val wasForced = forcedFullscreen
        forcedFullscreen = on
        if (!rotationLocked) {
            activity.requestedOrientation = when {
                on -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                wasForced -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
        }
    }
    LaunchedEffect(fullscreen) { systemBars(activity, hidden = fullscreen) }
    LaunchedEffect(Unit) { if (forcedFullscreen && !rotationLocked) setFullscreen(true) }
    LaunchedEffect(choppy) {
        if (choppy) {
            delay(15_000)
            choppy = false
        }
    }
    fun playNext(nextId: String) {
        carried = Pair(forcedFullscreen, rotationLocked)
        onPlay(nextId)
    }
    BackHandler { if (forcedFullscreen) setFullscreen(false) else onBack() }

    val onTranscode: (() -> Unit)? = if (choppy && !transcoding) ({
        choppy = false
        if (videoStream != null) prefs.learnChoppy(videoStream.codec, videoStream.pixelRate)
        switchToTranscode()
    }) else null

    val video = @Composable { mod: Modifier ->
        val streamCacheKey = if (transcoding || localPlaylist != null) null else cacheKey
        VideoSurface(
            mod, player, playerView, fullscreen, transcoding, streamCacheKey, segments, ended, next, playerError, reconnect is Reconnect.Retrying,
            title = if (item.type == BaseItemKind.EPISODE) "${item.seriesName.orEmpty()} · ${episodeLabel(item)}" else item.name.orEmpty(),
            chapters = chapters,
            resumedAt = resumedAt,
            onResumeNoteGone = { resumedAt = null },
            onTranscode = onTranscode,
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            onBack = onBack,
            onPlay = ::playNext,
            onCreditsNext = { nextId ->
                creditsDone = true
                playNext(nextId)
            },
            onRetry = {
                playerError = null
                player.reload()
            },
            rotationLocked = rotationLocked,
            onRotationLock = {
                rotationLocked = !rotationLocked
                activity.requestedOrientation = if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            },
            onTracksChosen = { audio, sub -> prefs.setTracks(seriesKey, audio, sub) },
        )
    }

    // One call site for the video, so toggling fullscreen keeps its surface, subtitle overlay and crop state.
    Column(Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.statusBarsPadding())) {
        video(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9))
        if (!fullscreen) {
            val resumed = resumedAt
            if (resumed != null && playerError == null && !ended) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically,
                ) {
                    Text("Resumed at ${formatMs(resumed)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { player.seekTo(0); resumedAt = null }) { Text("Start over") }
                }
            } else if (onTranscode != null && playerError == null && !ended) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
                    Arrangement.Center,
                    Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onTranscode) { Text("Choppy? Transcode") }
                }
            }
            val downloadsState = rememberDownloadStatusesState()
            val season = remember(siblings, item.seasonId) { siblings.filter { it.seasonId == item.seasonId } }
            val listState = rememberLazyListState()
            // Scroll the playing episode into view once the list arrives. List item 0 is the title.
            LaunchedEffect(season.isNotEmpty()) {
                val i = season.indexOfFirst { it.id == item.id }
                if (i >= 0) {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (i + 1 > lastVisible) listState.scrollToItem(i)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), listState) {
                item {
                    Text(item.seriesName ?: item.name.orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    if (item.type == BaseItemKind.EPISODE) Text(episodeLabel(item), style = MaterialTheme.typography.titleMedium)
                    val overview = item.overview
                    if (overview != null) Text(overview, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(season, key = { it.id }) { ep ->
                    EpisodeRow(ep, statusesState = downloadsState, highlighted = ep.id == item.id, onPlay = { onPlay(ep.id.toString()) },
                        onStartOver = {
                            if (ep.id == item.id) player.seekTo(0)
                            else scope.launch {
                                App.server.startOver(ep)
                                onPlay(ep.id.toString())
                            }
                        },
                        onChanged = onChanged)
                }
            }
        }
    }
}

private fun buildPlayer(context: Context, view: VideoView): ExoPlayer {
    AudioDelay.watch(context)
    val renderers = AudioDelay.renderers(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true) // Try another device decoder before giving up and transcoding.
    // The RAM buffer stays modest, because buffering to the end happens on disk in Media's scratch.
    // A 15-second back buffer keeps skip-back and the subtitle re-read seek in memory instead of reloading.
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 1_000, 2_000)
        .setBackBuffer(15_000, true).build()
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    return ExoPlayer.Builder(context)
        .setLoadControl(load)
        // Pauses for calls and other players, ducks under navigation prompts.
        .setAudioAttributes(audioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        .buildWithAssSupport(
            context,
            renderType = AssRenderType.OVERLAY_OPEN_GL,
            subtitleView = view.subtitleView,
            dataSourceFactory = Media.playerFactory(),
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

@Composable
private fun VideoSurface(
    modifier: Modifier,
    player: ExoPlayer,
    playerView: VideoView,
    fullscreen: Boolean,
    transcoding: Boolean,
    cacheKey: String?,
    segments: List<MediaSegmentDto>,
    ended: Boolean,
    next: BaseItemDto?,
    playerError: PlaybackException?,
    reconnecting: Boolean,
    title: String,
    chapters: List<Chapter>,
    resumedAt: Long?,
    onResumeNoteGone: () -> Unit,
    onTranscode: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onCreditsNext: (String) -> Unit,
    onRetry: () -> Unit,
    rotationLocked: Boolean,
    onRotationLock: () -> Unit,
    onTracksChosen: (audio: String?, sub: String?) -> Unit,
) {
    val prefs = App.prefs
    val activity = LocalActivity.current!!
    val audio = remember { activity.getSystemService(AudioManager::class.java) }
    var controls by remember { mutableStateOf(true) }
    val positionState = remember { mutableLongStateOf(0L) }
    val durationState = remember { mutableLongStateOf(0L) }
    val bufferedState = remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var crop by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ranges = remember(segments) {
        segments.map { s ->
            val startMs = s.startTicks / TICKS_PER_MS
            val endMs = s.endTicks / TICKS_PER_MS - 1_000
            Pair(s.type, startMs until endMs)
        }
    }
    // Derived state, so the 250 ms position tick doesn't recompose VideoSurface at all.
    val segment by remember(ranges) { derivedStateOf { ranges.firstOrNull { positionState.longValue in it.second } } }
    var fastHold by remember { mutableStateOf(false) }
    val fastHolding by rememberUpdatedState(fastHold)
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(player) {
        while (true) {
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(0)
            positionState.longValue = pos
            durationState.longValue = dur
            // The cached range is shown only with the controls. It is read off the main thread because cache lookups
            // wait on the fill worker's commit lock.
            var cached = 0L
            if (controls && cacheKey != null) {
                cached = withContext(Dispatchers.IO) { Media.cachedUntilMs(cacheKey, pos, dur) }
            }
            bufferedState.longValue = maxOf(player.bufferedPosition, cached)
            playing = player.isPlaying
            // The player is idle before the first prepare (subtitles are fetched first) and after an error.
            waiting = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            if (controls && playing && System.currentTimeMillis() - lastInteraction > 3_000) controls = false
            delay(250)
        }
    }
    LaunchedEffect(overlay) {
        if (overlay != null) {
            delay(800)
            overlay = null
        }
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt != null) {
            delay(5_000)
            onResumeNoteGone()
        }
    }
    // The duration reads 0 until it is known (loading, reconnecting), and clamping to it would seek to the start.
    fun clamp(ms: Long): Long {
        val d = durationState.longValue
        return if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0)
    }
    LaunchedEffect(crop) {
        playerView.resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    fun seekBy(ms: Long) {
        player.seekTo(clamp(player.currentPosition + ms))
        val sign = if (ms > 0) "+" else "−"
        overlay = "$sign${abs(ms) / 1000}s"
    }
    fun togglePlay() = if (player.isPlaying) player.pause() else player.play()

    // Skipping is offered, never automatic. During the credits the button offers the next episode, if there is one.
    val activeSegment = segment
    val skip: Skip? = if (activeSegment == null || ended) {
        null
    } else {
        val (type, range) = activeSegment
        when (type) {
            MediaSegmentType.INTRO -> Skip("Skip intro") { player.seekTo(range.last + 1_000) }
            MediaSegmentType.RECAP -> Skip("Skip recap") { player.seekTo(range.last + 1_000) }
            MediaSegmentType.OUTRO -> {
                if (next != null) Skip("Play next: ${episodeLabel(next)}") { onCreditsNext(next.id.toString()) } else null
            }
            else -> null
        }
    }

    // Remote and keyboard input. The surface holds focus while no button is shown; a button takes focus as it appears
    // (see Modifier.takeFocus).
    val surfaceFocus = remember { FocusRequester() }
    val skipShown = skip != null && !controls
    LaunchedEffect(controls, skipShown, ended, playerError) {
        if (!controls && !skipShown && !ended && playerError == null) surfaceFocus.requestFocus()
    }

    Box(
        modifier
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                lastInteraction = System.currentTimeMillis() // Keys keep the controls up, like taps.
                // On TV, Back hides the controls before leaving. This can't be a BackHandler, because the player
                // screen's own BackHandler would run first.
                if (e.key == Key.Back && App.tv && controls) {
                    if (e.type == KeyEventType.KeyUp) controls = false
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = prefs.doubleTapSeconds * 1000L
                when (e.key) {
                    Key.MediaPlayPause -> togglePlay()
                    Key.MediaPlay -> player.play()
                    Key.MediaPause -> player.pause()
                    Key.MediaFastForward -> seekBy(step)
                    Key.MediaRewind -> seekBy(-step)
                    Key.MediaNext -> if (next != null) onPlay(next.id.toString())
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            // Only keys that no button consumed reach here. With the controls up, the arrows move between buttons instead.
            .onKeyEvent { e ->
                if (controls) return@onKeyEvent false
                val step = prefs.doubleTapSeconds * 1000L
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> seekBy(-step)
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> seekBy(step)
                    e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> controls = true
                    // Act on release, so the press that brings the controls up doesn't also click the button they focus.
                    e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                        togglePlay()
                        controls = true
                    }
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusRequester(surfaceFocus)
            .focusable()
            .pointerInput(fullscreen) {
                detectTapGestures(
                    // Holding plays at 2x until release, then the user's chosen speed comes back.
                    onLongPress = {
                        if (prefs.gestureHold) {
                            fastHold = true
                            player.setPlaybackSpeed(2f)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onPress = {
                        // Restore the speed in finally: a rotation restarts this block mid-hold, and 2x must not stick.
                        try {
                            tryAwaitRelease()
                        } finally {
                            if (fastHold) {
                                fastHold = false
                                player.setPlaybackSpeed(sessionSpeed)
                            }
                        }
                    },
                    onTap = {
                        controls = !controls
                        lastInteraction = System.currentTimeMillis()
                    },
                    // Null when disabled, so a single tap doesn't wait out the double-tap window.
                    onDoubleTap = if (!prefs.gestureDoubleTap) null else { offset ->
                        val direction = if (offset.x < size.width / 2) -1 else 1
                        seekBy(direction * prefs.doubleTapSeconds * 1000L)
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
                    var held = false
                    var total = Offset.Zero
                    var startSpan = 0f
                    val startPos = player.currentPosition
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val windowBright = activity.window.attributes.screenBrightness
                    val startBright = if (windowBright >= 0) windowBright else runCatching {
                        val sys = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        (sys / 255f).coerceIn(0.01f, 1f)
                    }.getOrDefault(0.5f)
                    val brightnessZone = if (prefs.brightnessOnLeft) 0 else 2
                    // Apply only when the value changes. Each apply calls into the window or audio service, and touch
                    // events arrive far more often than the value changes.
                    var lastPercent = -1
                    var lastVol = startVol
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (fastHolding) {
                            held = true
                            continue
                        }
                        if (held) continue
                        if (pressed.size >= 2 && prefs.gesturePinch && fullscreen) {
                            val span = (pressed[0].position - pressed[1].position).getDistance()
                            if (axis != 3) {
                                axis = 3
                                startSpan = span
                            }
                            val nextCrop = when {
                                span > startSpan * 1.15f -> true
                                span < startSpan * 0.85f -> false
                                else -> crop
                            }
                            if (nextCrop != crop) {
                                crop = nextCrop
                                overlay = if (crop) "Crop" else "Fit"
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (axis == 3) continue
                        val change = pressed[0]
                        total += change.positionChange()
                        if (axis == 0 && total.getDistance() > viewConfiguration.touchSlop) {
                            axis = if (abs(total.x) > abs(total.y)) 1 else 2
                        }
                        when {
                            axis == 1 && fullscreen && prefs.gestureSeek -> {
                                val target = clamp(startPos + (total.x / w * SEEK_MS_PER_WIDTH).toLong())
                                val deltaMs = target - startPos
                                val sign = if (deltaMs / 1000 < 0) "−" else "+"
                                scrubTarget = target
                                overlay = "$sign${formatMs(abs(deltaMs))} · ${formatMs(target)} / ${formatMs(durationState.longValue)}"
                                change.consume()
                            }
                            axis == 2 && !fullscreen && prefs.gestureFullscreen -> change.consume()
                            axis == 2 && fullscreen && zone == 1 && prefs.gestureFullscreen -> change.consume()
                            axis == 2 && fullscreen && zone != 1 && prefs.gestureLevels -> {
                                val delta = -total.y / (h * 0.8f)
                                if (zone == brightnessZone) {
                                    val brightness = (startBright + delta).coerceIn(0.01f, 1f)
                                    val percent = (brightness * 100).toInt().coerceAtLeast(1)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = percent / 100f }
                                    }
                                    overlay = "Brightness $percent%"
                                } else {
                                    val v = (startVol + delta * maxVol).toInt().coerceIn(0, maxVol)
                                    if (v != lastVol) {
                                        lastVol = v
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    }
                                    overlay = "Volume $v / $maxVol"
                                }
                                change.consume()
                            }
                        }
                    }
                    if (!held) {
                        val scrubEnd = scrubTarget
                        if (scrubEnd != null) player.seekToNearestKeyframe(scrubEnd)
                        scrubTarget = null
                        if (axis == 2 && prefs.gestureFullscreen) {
                            val threshold = h * 0.15f
                            if (!fullscreen && total.y < -threshold) onToggleFullscreen()
                            if (fullscreen && zone == 1 && total.y > threshold) onToggleFullscreen()
                        }
                    } else {
                        scrubTarget = null
                    }
                }
            }
    ) {
        AndroidView({ playerView }, Modifier.fillMaxSize())

        MaterialTheme(colorScheme = darkColorScheme()) {
            val overlayText = overlay
            if (overlayText != null) {
                Text(overlayText, color = Color.White, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), MaterialTheme.shapes.small).padding(12.dp))
            }

            if (fastHold) Text("2× ▶▶", color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color(0x99000000), MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp))

            if (waiting && playerError == null && !ended) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            if (reconnecting && playerError == null) Text("Reconnecting…", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Center).padding(top = 72.dp).background(Color(0x99000000), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp))

            // With the controls shown, the skip button sits beside the seek bar instead, so the bar and scrim never cover it.
            if (skip != null && !controls) SkipButton(skip,
                Modifier.align(Alignment.BottomEnd).then(if (fullscreen) Modifier.safeDrawingPadding() else Modifier).padding(if (App.tv) 48.dp else 24.dp).takeFocus())

            if (playerError != null) {
                // Shown over the controls' scrim, without the control buttons underneath.
                Column(Modifier.align(Alignment.Center).padding(16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    val known = playbackError(playerError)
                    val detail = if (known == null) playerError.errorCodeName else null
                    ErrorText(known ?: "Playback failed.", detail, Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRetry, Modifier.takeFocus()) { Text("Retry") }
                        FilledTonalButton(onClick = onBack) { Text("Back") }
                    }
                }
            } else if (ended) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    if (next != null) Button(onClick = { onPlay(next.id.toString()) }, Modifier.takeFocus()) {
                        Text("Play next: ${episodeLabel(next)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(onClick = onBack, if (next == null) Modifier.takeFocus() else Modifier) { Text("Back") }
                }
            } else if (controls) {
                var onNext: (() -> Unit)? = null
                if (next != null) onNext = { onPlay(next.id.toString()) }
                Controls(
                    player, positionState, durationState, bufferedState, playing, fullscreen, transcoding, rotationLocked, title,
                    onBack, onToggleFullscreen, onRotationLock, onTracksChosen, ::seekBy, onNext, skip,
                    onInteraction = { lastInteraction = System.currentTimeMillis() },
                    subtitleView = playerView.subtitleView,
                    chapters = chapters,
                )
            }

            // Drawn after Controls so its scrim never dims the banner. In portrait it sits below the 16:9 video instead.
            if (fullscreen && playerError == null && !ended) {
                val note = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp)
                if (resumedAt != null) Row(
                    note.background(Color(0x99000000), MaterialTheme.shapes.small).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Resumed at ${formatMs(resumedAt)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { player.seekTo(0); onResumeNoteGone() }) { Text("Start over") }
                } else if (onTranscode != null) {
                    FilledTonalButton(onTranscode, note) { Text("Choppy? Transcode") }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    player: ExoPlayer,
    positionState: LongState,
    durationState: LongState,
    bufferedState: LongState,
    playing: Boolean,
    fullscreen: Boolean,
    transcoding: Boolean,
    rotationLocked: Boolean,
    title: String,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onRotationLock: () -> Unit,
    onTracksChosen: (String?, String?) -> Unit,
    onSeek: (Long) -> Unit,
    onNext: (() -> Unit)?,
    skip: Skip?,
    onInteraction: () -> Unit,
    subtitleView: SubtitleView,
    chapters: List<Chapter>,
) {
    var menu by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    var subtitleScale by remember { mutableIntStateOf(App.prefs.subtitleScale) }
    var audioDelayMs by remember { mutableIntStateOf(App.prefs.audioDelayMs) }
    val chapterStarts = remember(chapters) { chapters.map { it.startMs } }
    // Keep the controls alive while the menu is open, since auto-hiding them would close it.
    LaunchedEffect(menu) {
        while (menu) {
            onInteraction()
            delay(1_000)
        }
    }
    // Portrait already sits below the status bar; the rest of the safe area is below the video.
    Box(Modifier.fillMaxSize().background(Color(0x55000000)).then(if (fullscreen) Modifier.safeDrawingPadding() else Modifier)
        .then(if (App.tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier)) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { GlyphIcon(AppIcons.ARROW_BACK, "Back", tint = Color.White, autoMirror = true) }
            // In portrait the title is under the video instead.
            Column(Modifier.weight(1f)) {
                if (fullscreen) Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (transcoding) Text("Transcoding: buffering to the end is best-effort", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = {
                    subtitleScale = App.prefs.subtitleScale
                    audioDelayMs = App.prefs.audioDelayMs
                    menu = true
                    onInteraction()
                }) {
                    GlyphIcon(AppIcons.MORE_VERT, "Menu", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = {
                    menu = false
                    sub = null
                }) {
                    if (sub != null) {
                        val subTitle = when (sub) {
                            "chapters" -> "Chapters"
                            "speed" -> "Speed"
                            "size" -> "Subtitle size"
                            "delay" -> "Wireless audio delay"
                            "audio" -> "Audio"
                            "text" -> "Subtitles"
                            else -> ""
                        }
                        DropdownMenuItem({ Text("← $subTitle") }, { sub = null })
                        HorizontalDivider()
                    }
                    when (sub) {
                        null -> {
                            val activeAudio = selectedTrackLabel(player, C.TRACK_TYPE_AUDIO)
                            val activeSub = selectedTrackLabel(player, C.TRACK_TYPE_TEXT)
                            DropdownMenuItem({ Text("Speed: ${speedLabel(player.playbackParameters.speed)}") }, { sub = "speed" })
                            DropdownMenuItem({ Text(if (activeAudio != null) "Audio: $activeAudio" else "Audio", maxLines = 1, overflow = TextOverflow.Ellipsis) }, { sub = "audio" })
                            DropdownMenuItem({ Text("Subtitles: ${activeSub ?: "Off"}", maxLines = 1, overflow = TextOverflow.Ellipsis) }, { sub = "text" })
                            DropdownMenuItem({ Text("Subtitle size: $subtitleScale%") }, { sub = "size" })
                            DropdownMenuItem({ Text("Wireless audio delay: $audioDelayMs ms") }, { sub = "delay" })
                            if (chapters.isNotEmpty()) DropdownMenuItem({ Text("Chapters") }, { sub = "chapters" })
                            if (!App.tv) {
                                val rotationLabel = if (rotationLocked) "Unlock rotation" else "Lock rotation"
                                DropdownMenuItem({ Text(rotationLabel) }, {
                                    onRotationLock()
                                    menu = false
                                })
                            }
                        }
                        "chapters" -> {
                            val current = chapterAt(chapterStarts, positionState.longValue)
                            chapters.forEachIndexed { i, c ->
                                Choice("${formatMs(c.startMs)}  ${c.name.ifEmpty { "Chapter ${i + 1}" }}", i == current) {
                                    player.seekTo(c.startMs)
                                    menu = false
                                    sub = null
                                }
                            }
                        }
                        "speed" -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            Choice(speedLabel(s), player.playbackParameters.speed == s) {
                                sessionSpeed = s
                                player.setPlaybackSpeed(s)
                                menu = false
                                sub = null
                            }
                        }
                        "size" -> {
                            DropdownMenuItem({ Text("Text subtitles only (not ASS/PGS)", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
                            listOf(50, 75, 100, 125, 150, 200).forEach { s ->
                                Choice("$s%", subtitleScale == s) {
                                    subtitleScale = s
                                    App.prefs.subtitleScale = s
                                    subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s / 100f)
                                }
                            }
                        }
                        "delay" -> {
                            if (!AudioDelay.isWireless) {
                                DropdownMenuItem({ Text("Applies to wireless audio (none connected)", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
                            }
                            AUDIO_DELAYS_MS.forEach { d ->
                                Choice("$d ms", audioDelayMs == d) {
                                    audioDelayMs = d
                                    AudioDelay.set(d)
                                    // The audio clock never moves backwards on its own, so seek to apply a larger delay now.
                                    player.seekTo(player.currentPosition)
                                }
                            }
                        }
                        "audio", "text" -> {
                            val type = if (sub == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                            val groups = player.currentTracks.groups.filter { it.type == type && it.length > 0 }
                            val textOff = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes ||
                                player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                            if (type == C.TRACK_TYPE_TEXT && groups.isNotEmpty()) Choice("Off", textOff) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                onTracksChosen(selectedLanguage(player, C.TRACK_TYPE_AUDIO), "")
                                menu = false
                                sub = null
                            }
                            if (groups.isEmpty()) {
                                DropdownMenuItem({ Text("No tracks") }, {}, enabled = false)
                            } else {
                                groups.forEach { group ->
                                    for (i in 0 until group.length) {
                                        val f = group.getTrackFormat(i)
                                        val label = trackLabel(f).ifEmpty { "Track ${i + 1}" }
                                        Choice(label, group.isTrackSelected(i)) {
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                .setTrackTypeDisabled(type, false)
                                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                .build()
                                            val audioLang = if (type == C.TRACK_TYPE_AUDIO) f.language else selectedLanguage(player, C.TRACK_TYPE_AUDIO)
                                            val subLang = if (type == C.TRACK_TYPE_TEXT) f.language else selectedLanguage(player, C.TRACK_TYPE_TEXT)
                                            onTracksChosen(audioLang, subLang)
                                            // Seek in place so the current subtitle line shows now, not at the next cue.
                                            if (type == C.TRACK_TYPE_TEXT) player.seekTo(player.currentPosition)
                                            menu = false
                                            sub = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!App.tv) IconButton(onClick = onToggleFullscreen) {
                Icon(if (fullscreen) FullscreenExitIcon else FullscreenIcon, if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White)
            }
        }

        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            val step = App.prefs.doubleTapSeconds
            IconButton(onClick = {
                onSeek(-step * 1000L)
                onInteraction()
            }) {
                Icon(RewindIcon, "Back $step seconds", tint = Color.White)
            }
            IconButton(onClick = {
                if (playing) player.pause() else player.play()
                onInteraction()
            }, Modifier.padding(horizontal = 16.dp).size(64.dp).takeFocus()) {
                if (playing) Icon(PauseIcon, "Pause", Modifier.size(40.dp), tint = Color.White)
                else GlyphIcon(AppIcons.PLAY, "Play", size = 40.dp, tint = Color.White)
            }
            IconButton(onClick = {
                onSeek(step * 1000L)
                onInteraction()
            }) {
                Icon(ForwardIcon, "Forward $step seconds", tint = Color.White)
            }
            if (onNext != null) IconButton(onClick = onNext) { Icon(NextIcon, "Next episode", tint = Color.White) }
        }

        AndroidSeekBarSection(
            player = player,
            positionState = positionState,
            durationState = durationState,
            bufferedState = bufferedState,
            chapters = chapters,
            chapterStarts = chapterStarts,
            skip = skip,
            onInteraction = onInteraction,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp),
        )
    }
}

/** Isolated from [Controls] so the 250 ms position/buffer tick only recomposes the time text and seek bar. */
@Composable
private fun AndroidSeekBarSection(
    player: ExoPlayer,
    positionState: LongState,
    durationState: LongState,
    bufferedState: LongState,
    chapters: List<Chapter>,
    chapterStarts: List<Long>,
    skip: Skip?,
    onInteraction: () -> Unit,
    modifier: Modifier,
) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    val position = positionState.longValue
    val duration = durationState.longValue
    val buffered = bufferedState.longValue
    Column(modifier) {
        val shown = if (dragging >= 0) dragging.toLong() else position
        val bufferText = if (duration > 0 && buffered >= duration - 2_000) "" else "  (buffered ${formatMs(buffered)})"
        val chapterText = if (dragging >= 0 && chapters.isNotEmpty()) {
            val i = chapterAt(chapterStarts, shown)
            if (i >= 0) " · ${chapters[i].name.ifEmpty { "Chapter ${i + 1}" }}" else ""
        } else ""
        Text("${formatMs(shown)} / ${formatMs(duration)}$bufferText$chapterText", color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Canvas(Modifier.matchParentSize()) {
                    if (duration <= 0 || buffered <= 0) return@Canvas
                    val left = 10.dp.toPx()
                    val end = left + (size.width - 2 * left) * (buffered.toFloat() / duration).coerceIn(0f, 1f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.35f),
                        start = Offset(left, center.y),
                        end = Offset(end, center.y),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                ChapterMarks(chapters, duration, Modifier.matchParentSize())
                Slider(
                    value = shown.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    onValueChange = { value ->
                        dragging = value
                        onInteraction()
                    },
                    // D-pad steps can report finished without a change in between, so check [dragging].
                    onValueChangeFinished = {
                        if (dragging >= 0) player.seekToNearestKeyframe(dragging.toLong())
                        dragging = -1f
                    },
                    colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (skip != null) SkipButton(skip, Modifier.padding(start = 8.dp))
        }
    }
}

/** Focuses a button as it appears, so the remote's OK presses it. Touch mode ignores this. */
private fun Modifier.takeFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    focusRequester(requester)
}

@Composable
private fun SkipButton(skip: Skip, modifier: Modifier) =
    FilledTonalButton(skip.onClick, modifier) { Text(skip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }

private class Skip(val label: String, val onClick: () -> Unit)

/**
 * Media3's PlayerView reduced to its surface, shutter and subtitles, wired the same way. PlayerView always links its
 * controller and, with it, RecyclerView, which adds 130 KB to every APK.
 * The shutter hides at the first frame and stays hidden, so the last frame stays on screen while a reconnect reloads.
 */
private class VideoView(context: Context) : android.widget.FrameLayout(context), Player.Listener {
    init { inflate(context, R.layout.player_surface, this) }
    private val frame: AspectRatioFrameLayout = findViewById(androidx.media3.ui.R.id.exo_content_frame)
    private val shutter: android.view.View = findViewById(androidx.media3.ui.R.id.exo_shutter)
    val subtitleView: SubtitleView = findViewById(androidx.media3.ui.R.id.exo_subtitles)
    private val surface = android.view.SurfaceView(context)
    init { frame.addView(surface, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    var resizeMode by frame::resizeMode
    var player: Player? = null
        set(value) {
            val old = field
            if (old != null) {
                old.removeListener(this)
                old.clearVideoSurfaceView(surface)
            }
            field = value
            if (value != null) {
                value.setVideoSurfaceView(surface)
                value.addListener(this)
                subtitleView.setCues(value.currentCues.cues)
            }
        }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.height > 0) frame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
    }
    override fun onRenderedFirstFrame() { shutter.visibility = INVISIBLE }
    override fun onCues(cueGroup: CueGroup) = subtitleView.setCues(cueGroup.cues)
}

/** A menu entry ticked when it is the current choice. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    DropdownMenuItem({ Text(label) }, onClick, trailingIcon = { if (selected) GlyphIcon(AppIcons.CHECK, null) })

/**
 * Starts the current item again from where it stopped. A bare prepare() after a failed load resumes
 * the old extractor mid-file, and the Matroska one then fails on a sample with no format.
 */
private fun ExoPlayer.reload() {
    val item = currentMediaItem
    if (item == null) {
        prepare()
        return
    }
    setMediaItem(item, currentPosition)
    prepare()
}

/**
 * Seeks to the nearest keyframe, for coarse seeks from the slider or a swipe. An exact seek decodes forward from the
 * previous keyframe, which takes seconds on long-GOP files. Skip buttons still seek exactly.
 */
private fun ExoPlayer.seekToNearestKeyframe(ms: Long) {
    setSeekParameters(SeekParameters.CLOSEST_SYNC)
    seekTo(ms)
    setSeekParameters(SeekParameters.DEFAULT)
}

private fun speedLabel(s: Float) = "${s.toString().removeSuffix(".0")}×"

/** "English · 5.1" rather than "eng · mp4a.40.2"; the track's own title first when it has one. */
private fun trackLabel(f: Format): String {
    val code = f.language
    val language = if (code == null || code == "und") null else Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
    val channels = when (f.channelCount) {
        Format.NO_VALUE -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${f.channelCount} ch"
    }
    val label = f.label
    // Skip the language when the track's title already names it.
    val repeatsLabel = language != null && label != null && label.contains(language, ignoreCase = true)
    val shownLanguage = if (repeatsLabel) null else language
    return listOfNotNull(label, shownLanguage, channels).joinToString(" · ")
}

/** Display label of the selected track, or null when none is selected. */
private fun selectedTrackLabel(player: ExoPlayer, type: Int): String? {
    if (type in player.trackSelectionParameters.disabledTrackTypes) return null
    val group = player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected } ?: return null
    val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return null
    return trackLabel(group.getTrackFormat(index)).ifEmpty { "Track ${index + 1}" }
}

/** Language of the selected track, "" when that type is off, null when unknown. */
private fun selectedLanguage(player: ExoPlayer, type: Int): String? {
    if (player.trackSelectionParameters.disabledTrackTypes.contains(type)) return ""
    val selectedGroups = player.currentTracks.groups.filter { it.type == type && it.isSelected }
    for (group in selectedGroups) {
        val index = (0 until group.length).firstOrNull { i -> group.isTrackSelected(i) }
        if (index == null) continue
        val language = group.getTrackFormat(index).language
        if (language != null) return language
    }
    return null
}

/** No answer, or a server-side error: worth retrying. A 4xx would fail the same way every time. */
private fun isNetworkFailure(e: PlaybackException): Boolean {
    val http = generateSequence(e.cause) { it.cause }.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull()
    return when {
        http is HttpDataSource.InvalidResponseCodeException -> http.responseCode >= 500
        // Media3 files OkHttp's wrapped connect failures as IO_UNSPECIFIED, so the transport exception is the signal.
        http != null -> true
        else -> e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
    }
}

/** A plain sentence for common playback failures; null for the rest. */
private fun playbackError(e: PlaybackException) = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Can't reach the server."
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> if (isNetworkFailure(e)) "Can't reach the server." else null
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT -> "The server took too long to respond."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val cause = e.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) "The server returned an error (${cause.responseCode})."
        else "The server returned an error."
    }
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is missing."
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> "This device can't play this file."
    else -> null
}
