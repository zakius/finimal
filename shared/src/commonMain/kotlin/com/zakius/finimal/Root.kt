package com.zakius.finimal

import androidx.compose.foundation.isSystemInDarkTheme
import finimal.core.TICKS_PER_MS
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

@Serializable
sealed interface Screen {
    @Serializable data object Login : Screen
    /** Logs another user of the same server in. */
    @Serializable data object AddAccount : Screen
    /** Switching accounts; as the first screen, "Who's watching?". */
    @Serializable data object Accounts : Screen
    @Serializable data object Libraries : Screen
    @Serializable data object Downloads : Screen
    @Serializable data object Settings : Screen
    @Serializable data class Library(val id: String?, val name: String) : Screen
    @Serializable data class Series(val id: String, val name: String) : Screen
    @Serializable data class Season(val seriesId: String, val seasonId: String, val name: String) : Screen
    @Serializable data class Play(val itemId: String) : Screen
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val colors = platformColors(isSystemInDarkTheme())
    // Material's focus highlight (10%) can't be seen from a sofa; this is what shows where the D-pad is.
    val ripple = RippleConfiguration(rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.35f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalRippleConfiguration provides ripple) { Surface(Modifier.fillMaxSize(), content = content) }
    }
}

/**
 * The screen stack. [player] plays an item: each platform has its own. [openDownloads] pushes Downloads once, for the
 * download notification.
 */
@Composable
fun Root(openDownloads: Boolean, openedDownloads: () -> Unit, player: @Composable (itemId: String, onBack: () -> Unit, onPlay: (String) -> Unit) -> Unit) {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { list -> list.map { Json.encodeToString(Screen.serializer(), it) } },
            restore = { saved -> saved.map { Json.decodeFromString(Screen.serializer(), it) }.toMutableStateList() },
        )
    ) { listOf(startScreen()).toMutableStateList() }

    // Each stack entry keeps its UI state (scroll, sort, search) while covered; leaving the stack drops it.
    val saved = rememberSaveableStateHolder()
    // Bumped by reset: a fresh Libraries after switching accounts must not be the old one's composition.
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    fun keyOf(i: Int) = "$epoch:$i:${stack[i]}"
    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.size > 1) { saved.removeState(keyOf(stack.lastIndex)); stack.removeAt(stack.lastIndex) } }
    fun reset(s: Screen) { stack.indices.forEach { saved.removeState(keyOf(it)) }; epoch++; stack.clear(); stack.add(s) }

    @OptIn(ExperimentalComposeUiApi::class) BackHandler(stack.size > 1) { pop() }
    LaunchedEffect(openDownloads) {
        if (openDownloads && App.prefs.loggedIn && stack.last() != Screen.Downloads) push(Screen.Downloads)
        openedDownloads()
    }
    LaunchedEffect(App.sessionExpired) { if (App.sessionExpired) reset(Screen.Login) }
    val topKey = keyOf(stack.lastIndex)
    // D-pad focus stays on a covered screen's item otherwise; the screen coming back on top takes its own back.
    val focus = LocalFocusManager.current
    LaunchedEffect(topKey) { focus.clearFocus(force = true) }
    // TVs may crop the picture's edges (overscan); Android TV's guideline margin keeps the UI inside.
    val padded = Modifier.safeDrawingPadding().then(if (App.tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier)
    val back = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack)
    val more = rememberVectorPainter(Icons.Default.MoreVert)
    val settings = rememberVectorPainter(Icons.Default.Settings)
    val play = rememberVectorPainter(Icons.Default.PlayArrow)
    val icons = remember(back, more, settings, play) { ListIcons(back, more, settings, play) }
    // Covered screens stay composed, so going back only lays out and draws: rebuilding the poster grid took 13 ms of the
    // main thread on the phone (2026-10-26). Screens read LocalShown to pause their own work while covered.
    // key() gives each entry its own state, so the next episode never inherits the previous player.
    CompositionLocalProvider(LocalIcons provides icons) { stack.forEachIndexed { i, screen -> key(keyOf(i)) {
        val shown = i == stack.lastIndex
        // A State, not a value: screens watch it from their effects, so covering or uncovering one recomposes nothing.
        val shownState = rememberUpdatedState(shown)
        Box(if (shown) Modifier.fillMaxSize() else Modifier.hidden(), propagateMinConstraints = true) {
            CompositionLocalProvider(LocalShown provides shownState) { saved.SaveableStateProvider(keyOf(i)) { FocusMemory { when (screen) {
                Screen.Login -> LoginScreen(padded) { reset(Screen.Libraries) }
                Screen.AddAccount -> LoginScreen(padded, onBack = ::pop) { reset(Screen.Libraries) }
                Screen.Accounts -> AccountsScreen(padded, if (i == 0) null else ::pop, { reset(Screen.Libraries) }) { push(Screen.AddAccount) }
                Screen.Libraries -> LibrariesScreen(padded, ::push)
                Screen.Downloads -> DownloadsScreen(padded, ::push, ::pop)
                Screen.Settings -> SettingsScreen(padded, ::pop, { push(Screen.Accounts) }) { reset(startScreen()) }
                is Screen.Library -> LibraryScreen(padded, screen, ::push, ::pop)
                is Screen.Series -> SeriesScreen(padded, screen, ::push, ::pop)
                is Screen.Season -> SeasonScreen(padded, screen, ::push, ::pop)
                // "Play next" replaces the player instead of stacking players.
                is Screen.Play -> player(screen.itemId, ::pop) { next -> pop(); push(Screen.Play(next)) }
            } } } }
        }
    } } }
}

/**
 * At a cold start with several accounts, who is watching; the only one opens at once. Logged out with accounts left,
 * the picker too: one of them may have a PIN.
 */
private fun startScreen() = when {
    App.prefs.accounts().size > 1 || !App.prefs.loggedIn && App.prefs.accounts().isNotEmpty() -> Screen.Accounts
    App.prefs.loggedIn -> Screen.Libraries
    else -> Screen.Login
}

/**
 * The list screens' icons, one painter each for the whole activity. Each Icon otherwise renders its own bitmap, and
 * the first bitmap a frame uploads stalled the main thread 7 to 8 ms on the phone's GPU driver (2026-10-26): once
 * per screen opened. The player keeps its own: its play icon is bigger, and a shared painter redraws at each size.
 */
class ListIcons(val back: Painter, val more: Painter, val settings: Painter, val play: Painter)
val LocalIcons = staticCompositionLocalOf<ListIcons> { error("provided by Root") }

/** False while another screen covers this one; see Root. Read it in effects (snapshotFlow), not in composition. */
val LocalShown = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

// Composed but never measured or placed: not drawn or touched, and no semantics for accessibility services.
private fun Modifier.hidden() = clearAndSetSemantics {}.layout { _, c -> layout(c.minWidth, c.minHeight) {} }

fun episodeLabel(ep: BaseItemDto) = "S${ep.parentIndexNumber ?: "?"}E${ep.indexNumber ?: "?"} · ${ep.name.orEmpty()}"

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** In season order, never the server's Next Up; specials and missing episodes are skipped. */
fun nextEpisode(item: BaseItemDto, siblings: List<BaseItemDto>): BaseItemDto? {
    val playable = siblings.filter { it.parentIndexNumber != 0 && it.locationType != LocationType.VIRTUAL }
    return playable.getOrNull(playable.indexOfFirst { it.id == item.id } + 1)?.takeIf { playable.any { p -> p.id == item.id } }
}

fun playerTitle(item: BaseItemDto) =
    if (item.type == BaseItemKind.EPISODE) "${item.seriesName.orEmpty()} · ${episodeLabel(item)}" else item.name.orEmpty()

/** The audio stream of [source] in language [lang], for a transcode, which carries one audio track. */
fun audioIndex(source: MediaSourceInfo, lang: String) =
    source.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && sameLanguage(it.language, lang) }?.index

/** Players shorten codes ("ja"), the server keeps three letters ("jpn"). */
fun sameLanguage(a: String?, b: String?) = a != null && b != null &&
    runCatching { Locale.forLanguageTag(a).isO3Language == Locale.forLanguageTag(b).isO3Language }.getOrDefault(a == b)

/**
 * Subtitle streams players add by URL or from a fetched copy: the server's separate files and, for a [transcode],
 * the embedded text ones, which the server extracts with their styling. A transcode drops embedded subtitles.
 */
fun sideloadedSubs(item: BaseItemDto, transcode: Boolean): List<MediaStream> =
    item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
        .filter { it.type == MediaStreamType.SUBTITLE && (it.isExternal || transcode && it.isTextSubtitleStream) }

/** For a transcode: a picture subtitle (PGS, VobSub) in [lang] to burn into the video, when no text one is in that language. */
fun burnInIndex(source: MediaSourceInfo, lang: String?): Int? {
    val subs = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE && sameLanguage(it.language, lang) }
    return subs.firstOrNull()?.takeIf { subs.none { s -> s.isTextSubtitleStream } }?.index
}

class Chapter(val name: String, val startMs: Long)

/** The file's chapters, from the server; a lone chapter is no chapters at all. */
fun chapters(item: BaseItemDto): List<Chapter> =
    item.chapters.orEmpty().map { Chapter(it.name.orEmpty(), it.startPositionTicks / TICKS_PER_MS) }.takeIf { it.size > 1 }.orEmpty()

/** Choices for the wireless audio delay; negative values are for audio that arrives early. */
val AUDIO_DELAYS_MS = (-200..500 step 50).toList()

// --- per platform ------------------------------------------------------------------------------------

/** Android 12+ takes the wallpaper's colours; elsewhere Material's defaults. */
@Composable
internal expect fun platformColors(dark: Boolean): ColorScheme

/** The smaller side of the window, in dp: the poster grid gets bigger cells on tablets and desktops. */
@Composable
internal expect fun smallestWidthDp(): Int

/** A short message over the UI: Android's toast; on the desktop a note at the bottom of the window. */
expect fun toast(text: String)

/**
 * Retries once a different network comes up (back on wifi, out of a dead zone). The network that
 * was already up is skipped: the system reports it at once, and retrying on it would only fail again.
 */
@Composable
internal expect fun RetryOnNewNetwork(onRetry: () -> Unit)
