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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
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
    // Material's default 10% focus highlight can't be seen from a sofa, and it is the only sign of where D-pad focus is.
    val ripple = RippleConfiguration(rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.35f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalRippleConfiguration provides ripple) { Surface(Modifier.fillMaxSize(), content = content) }
    }
}

/**
 * The screen stack. [player] is the platform's own player for an item. [openDownloads] pushes Downloads once, when the
 * download notification is tapped.
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
    // Bumped by reset, so a fresh Libraries screen after switching accounts doesn't reuse the old account's composition.
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    fun keyOf(i: Int) = "$epoch:$i:${stack[i]}"
    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.size > 1) { saved.removeState(keyOf(stack.lastIndex)); stack.removeAt(stack.lastIndex) } }
    fun reset(s: Screen) { stack.indices.forEach { saved.removeState(keyOf(it)) }; epoch++; stack.clear(); stack.add(s) }

    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = stack.size > 1, onBackCompleted = ::pop)
    LaunchedEffect(openDownloads) {
        // Never open over "Who's watching?", because the account still loaded there may be protected by a PIN.
        if (openDownloads && App.prefs.loggedIn && stack.first() != Screen.Accounts && stack.last() != Screen.Downloads) {
            // Close the player first: covered, it would keep playing unseen, and opening a download from the list would start a second one.
            if (stack.last() is Screen.Play) pop()
            push(Screen.Downloads)
        }
        openedDownloads()
    }
    LaunchedEffect(App.sessionExpired) { if (App.sessionExpired) reset(Screen.Login) }
    val topKey = keyOf(stack.lastIndex)
    // Otherwise D-pad focus would stay on an item of the covered screen. A screen coming back on top restores its own focus.
    val focus = LocalFocusManager.current
    LaunchedEffect(topKey) { focus.clearFocus(force = true) }
    // TVs may crop the picture's edges (overscan). These are Android TV's recommended margins, which keep the UI inside.
    val padded = Modifier.safeDrawingPadding().then(if (App.tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier)
    val back = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack)
    val more = rememberVectorPainter(Icons.Default.MoreVert)
    val settings = rememberVectorPainter(Icons.Default.Settings)
    val play = rememberVectorPainter(Icons.Default.PlayArrow)
    val icons = remember(back, more, settings, play) { ListIcons(back, more, settings, play) }
    // Covered screens stay composed so that going back only lays out and draws. Rebuilding the poster grid cost about
    // 13 ms of main-thread time on a phone. Screens read LocalShown to pause their own work while covered.
    // key() gives each entry its own state, so the next episode never inherits the previous player's state.
    CompositionLocalProvider(LocalIcons provides icons) { stack.forEachIndexed { i, screen -> key(keyOf(i)) {
        val shown = i == stack.lastIndex
        // Passed as a State rather than a value: screens watch it from their effects, so covering or uncovering a screen recomposes nothing.
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
 * At a cold start with several accounts, the first screen asks who is watching; a single account opens at once. When
 * logged out with saved accounts left, the picker shows too, because one of them may have a PIN.
 */
private fun startScreen() = when {
    App.prefs.accounts().size > 1 || !App.prefs.loggedIn && App.prefs.accounts().isNotEmpty() -> Screen.Accounts
    App.prefs.loggedIn -> Screen.Libraries
    else -> Screen.Login
}

/**
 * The list screens' icons, one painter each for the whole activity. Otherwise each Icon renders its own bitmap, and on
 * a phone's GPU driver the first bitmap a frame uploads stalled the main thread for 7 to 8 ms on every screen opened.
 * The player keeps its own icons: its play icon is bigger, and a shared painter would redraw at each size.
 */
class ListIcons(val back: Painter, val more: Painter, val settings: Painter, val play: Painter)
val LocalIcons = staticCompositionLocalOf<ListIcons> { error("provided by Root") }

/** False while another screen covers this one (see Root). Read it in effects through snapshotFlow, not in composition. */
val LocalShown = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

// Composed but laid out at zero size and never placed, so it is not drawn, gets no touches, and exposes no semantics to accessibility services.
private fun Modifier.hidden() = clearAndSetSemantics {}.layout { _, c -> layout(c.minWidth, c.minHeight) {} }

fun episodeLabel(ep: BaseItemDto) = "S${ep.parentIndexNumber ?: "?"}E${ep.indexNumber ?: "?"} · ${ep.name.orEmpty()}"

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The next episode in season order, never the server's Next Up. Specials and missing episodes are skipped. */
fun nextEpisode(item: BaseItemDto, siblings: List<BaseItemDto>): BaseItemDto? {
    val playable = siblings.filter { it.parentIndexNumber != 0 && it.locationType != LocationType.VIRTUAL }
    return playable.getOrNull(playable.indexOfFirst { it.id == item.id } + 1)?.takeIf { playable.any { p -> p.id == item.id } }
}

fun playerTitle(item: BaseItemDto) =
    if (item.type == BaseItemKind.EPISODE) "${item.seriesName.orEmpty()} · ${episodeLabel(item)}" else item.name.orEmpty()

/** The audio stream of [source] in language [lang], to pick for a transcode, which carries only one audio track. */
fun audioIndex(source: MediaSourceInfo, lang: String) =
    source.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && sameLanguage(it.language, lang) }?.index

/** Players use short codes ("ja") while the server keeps three letters ("jpn"). */
fun sameLanguage(a: String?, b: String?) = a != null && b != null &&
    runCatching { Locale.forLanguageTag(a).isO3Language == Locale.forLanguageTag(b).isO3Language }.getOrDefault(a == b)

/**
 * Subtitle streams that players add by URL or from a fetched copy: the server's external files and, for a [transcode],
 * the embedded text ones, which the server extracts with their styling. A transcode drops embedded subtitles.
 */
fun sideloadedSubs(item: BaseItemDto, transcode: Boolean): List<MediaStream> =
    item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
        .filter { it.type == MediaStreamType.SUBTITLE && (it.isExternal || transcode && it.isTextSubtitleStream) }

/** For a transcode: a picture subtitle (PGS, VobSub) in [lang] to burn into the video, when no text one is in that language. */
fun burnInIndex(source: MediaSourceInfo, lang: String?): Int? {
    // "" means subtitles off. Without this check it would match streams of undetermined language ("und").
    if (lang.isNullOrEmpty()) return null
    val subs = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE && sameLanguage(it.language, lang) }
    return subs.firstOrNull()?.takeIf { subs.none { s -> s.isTextSubtitleStream } }?.index
}

class Chapter(val name: String, val startMs: Long)

/** The file's chapters, from the server. A single chapter counts as no chapters at all. */
fun chapters(item: BaseItemDto): List<Chapter> =
    item.chapters.orEmpty().map { Chapter(it.name.orEmpty(), it.startPositionTicks / TICKS_PER_MS) }.takeIf { it.size > 1 }.orEmpty()

/** Choices for the wireless audio delay; negative values are for audio that arrives early. */
val AUDIO_DELAYS_MS = (-200..500 step 50).toList()

/** Android 12 and later use the wallpaper's colours; other platforms use Material's defaults. */
@Composable
internal expect fun platformColors(dark: Boolean): ColorScheme

/** The smaller side of the window, in dp. The poster grid uses it to give tablets and desktops bigger cells. */
@Composable
internal expect fun smallestWidthDp(): Int

/** A short message over the UI: a toast on Android, and a note at the bottom of the window on the desktop. */
expect fun toast(text: String)

/**
 * Retries once a different network comes up (back on wifi, out of a dead zone). The network that
 * was already up is skipped: the system reports it at once, and retrying on it would only fail again.
 */
@Composable
internal expect fun RetryOnNewNetwork(onRetry: () -> Unit)
