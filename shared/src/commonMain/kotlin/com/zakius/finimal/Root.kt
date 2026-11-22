package com.zakius.finimal

import androidx.compose.foundation.isSystemInDarkTheme
import finimal.core.TICKS_PER_MS
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
    @Serializable data object AddAccount : Screen
    /** Re-authenticates an account with its password to clear a forgotten PIN. */
    @Serializable data class ResetPin(val username: String) : Screen
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

private val FocusRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.35f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f)
)

@Composable
fun Theme(content: @Composable () -> Unit) {
    val colors = platformColors(isSystemInDarkTheme())
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalRippleConfiguration provides FocusRipple) {
            Surface(Modifier.fillMaxSize(), content = content)
        }
    }
}

/**
 * The screen stack. [player] is the platform's own player for an item. [openDownloads] pushes Downloads once, when the
 * download notification is tapped.
 */
/**
 * Whether the download notification may push Downloads onto [stack]. Never over "Who's watching?": the account still
 * loaded behind it may have a PIN.
 */
fun opensDownloadsOver(stack: List<Screen>) = stack.first() != Screen.Accounts && stack.last() != Screen.Downloads

@Composable
fun Root(
    openDownloads: Boolean,
    openedDownloads: () -> Unit,
    initialStack: List<Screen>? = null,
    player: @Composable (itemId: String, onBack: () -> Unit, onPlay: (String) -> Unit) -> Unit,
) {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { list -> list.map { screen -> Json.encodeToString(Screen.serializer(), screen) } },
            restore = { saved -> saved.map { text -> Json.decodeFromString(Screen.serializer(), text) }.toMutableStateList() },
        )
    ) { (initialStack ?: listOf(startScreen())).toMutableStateList() }

    val saved = rememberSaveableStateHolder()
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    val everShown = remember { HashSet<String>() }
    fun keyOf(i: Int) = "$epoch:$i:${stack[i]}"
    val push: (Screen) -> Unit = remember(stack) { { s -> stack.add(s) } }
    val pop: () -> Unit = remember(stack, saved) {
        {
            if (stack.size > 1) {
                val removed = keyOf(stack.lastIndex)
                saved.removeState(removed)
                everShown.remove(removed)
                stack.removeAt(stack.lastIndex)
            }
        }
    }
    val reset: (Screen) -> Unit = remember(stack, saved) {
        { s ->
            for (i in stack.indices) saved.removeState(keyOf(i))
            everShown.clear()
            epoch++
            stack.clear()
            stack.add(s)
        }
    }
    val onSwitchFromLogin: () -> Unit = remember(reset) {
        {
            App.sessionExpired = false
            App.expiredUserName = null
            reset(Screen.Accounts)
        }
    }
    val onResetToLibraries: () -> Unit = remember(reset) { { reset(Screen.Libraries) } }
    val onResetPinDone: () -> Unit = remember(reset) {
        {
            App.prefs.setPin(App.prefs.userId!!, null)
            reset(Screen.Libraries)
        }
    }
    val onAddAccount: () -> Unit = remember(push) { { push(Screen.AddAccount) } }
    val onForgotPin: (String) -> Unit = remember(push) { { username -> push(Screen.ResetPin(username)) } }
    val onOpenAccounts: () -> Unit = remember(push) { { push(Screen.Accounts) } }
    val onLoggedOut: () -> Unit = remember(reset) { { reset(startScreen()) } }
    val onPlayNext: (String) -> Unit = remember(pop, push) {
        { next ->
            pop()
            push(Screen.Play(next))
        }
    }

    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = stack.size > 1, onBackCompleted = pop)
    LaunchedEffect(openDownloads) {
        if (openDownloads && App.prefs.loggedIn && opensDownloadsOver(stack)) {
            if (stack.last() is Screen.Play) pop()
            push(Screen.Downloads)
        }
        openedDownloads()
    }
    LaunchedEffect(App.sessionExpired) { if (App.sessionExpired) reset(Screen.Login) }
    val topKey = keyOf(stack.lastIndex)
    everShown.add(topKey)
    val focus = LocalFocusManager.current
    LaunchedEffect(topKey) { focus.clearFocus(force = true) }
    // TVs may crop the picture's edges (overscan). These are Android TV's recommended margins, which keep the UI inside.
    val tv = App.tv
    val padded = remember(tv) {
        val overscan = if (tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier
        Modifier.safeDrawingPadding().then(overscan)
    }
    stack.forEachIndexed { i, screen ->
        val entryKey = keyOf(i)
        val shown = i == stack.lastIndex
        if (!shown && entryKey !in everShown) return@forEachIndexed
        key(entryKey) {
            val shownState = rememberUpdatedState(shown)
            Box(if (shown) Modifier.fillMaxSize() else HiddenModifier, propagateMinConstraints = true) {
                CompositionLocalProvider(LocalShown provides shownState) {
                    saved.SaveableStateProvider(entryKey) {
                        FocusMemory {
                            when (screen) {
                                Screen.Login -> LoginScreen(
                                    padded,
                                    onSwitchAccount = onSwitchFromLogin,
                                    onDone = onResetToLibraries,
                                )
                                Screen.AddAccount -> LoginScreen(padded, onBack = pop, onDone = onResetToLibraries)
                                is Screen.ResetPin -> LoginScreen(
                                    padded,
                                    onBack = pop,
                                    title = "Reset PIN",
                                    subtitle = "Log in with your password to clear this account's PIN.",
                                    initialUser = screen.username,
                                    onDone = onResetPinDone,
                                )
                                Screen.Accounts -> AccountsScreen(
                                    padded,
                                    if (i == 0) null else pop,
                                    onResetToLibraries,
                                    onAddAccount,
                                    onForgotPin,
                                )
                                Screen.Libraries -> LibrariesScreen(padded, push)
                                Screen.Downloads -> DownloadsScreen(padded, push, pop)
                                Screen.Settings -> SettingsScreen(padded, pop, onOpenAccounts, onLoggedOut)
                                is Screen.Library -> LibraryScreen(padded, screen, push, pop)
                                is Screen.Series -> SeriesScreen(padded, screen, push, pop)
                                is Screen.Season -> SeasonScreen(padded, screen, push, pop)
                                is Screen.Play -> player(screen.itemId, pop, onPlayNext)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun startScreen(): Screen {
    val count = App.prefs.accountCount
    return when {
        count > 1 || (!App.prefs.loggedIn && count > 0) -> Screen.Accounts
        App.prefs.loggedIn -> Screen.Libraries
        else -> Screen.Login
    }
}

/**
 * Codepoints in the bundled TrueType icon font (`finimal_icons.ttf`). Rendering icons and the avatar circle as
 * text glyphs batches them into Skia's text atlas (`AtlasTextOp`) with zero offscreen bitmaps, zero texture uploads,
 * and zero extra GPU shader compiles on the first frame.
 */
object AppIcons {
    const val SETTINGS = "\uE001"
    const val ARROW_BACK = "\uE002"
    const val MORE_VERT = "\uE003"
    const val PLAY = "\uE004"
    const val CHECK = "\uE005"
    const val CIRCLE = "\uE006"
    const val ARROW_FORWARD = "\uE007"
    /** 2x2 quarter-circle glyphs (16dp each -> 32dp circle) that stay under Skia's A8 text atlas limit, avoiding CircleOp and SDF shaders. */
    const val CIRCLE_2X2 = "\uE008\uE009\n\uE00A\uE00B"
    /** 1dp-tall horizontal divider segment (at 16dp fontSize) so dividers batch into AtlasTextOp instead of compiling FillRectOp. */
    const val HLINE = "\uE00C"
    const val VLINE = "\uE00D"
    const val BLOCK = "\uE00E"
    const val SEARCH = "\uE00F"
    const val DOWNLOAD = "\uE010"
    const val CLOSE = "\uE011"
}

@Composable
fun GlyphIcon(
    glyph: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    autoMirror: Boolean = false,
) {
    val text = if (autoMirror && LocalLayoutDirection.current == LayoutDirection.Rtl && glyph == AppIcons.ARROW_BACK) {
        AppIcons.ARROW_FORWARD
    } else {
        glyph
    }
    val sizeSp = with(LocalDensity.current) { size.toSp() }
    val sem = if (contentDescription != null) {
        Modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Box(modifier.size(size).then(sem), contentAlignment = Alignment.Center) {
        BasicText(
            text = text,
            style = TextStyle(
                color = tint,
                fontSize = sizeSp,
                lineHeight = sizeSp,
                fontFamily = iconFontFamily(),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

internal expect fun iconFontFamily(): FontFamily

/** False while another screen covers this one (see Root). Read it in effects through snapshotFlow, not in composition. */
val LocalShown = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

private val HiddenModifier: Modifier = Modifier.clearAndSetSemantics {}.layout { _, constraints ->
    layout(constraints.minWidth, constraints.minHeight) {}
}

fun episodeLabel(ep: BaseItemDto) = "S${ep.parentIndexNumber ?: "?"}E${ep.indexNumber ?: "?"} · ${ep.name.orEmpty()}"

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    if (s >= 3600) return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    return "%d:%02d".format(s / 60, s % 60)
}

/** The next episode in season order, never the server's Next Up. Specials and missing episodes are skipped. */
fun nextEpisode(item: BaseItemDto, siblings: List<BaseItemDto>): BaseItemDto? {
    val playable = siblings.filter { it.parentIndexNumber != 0 && it.locationType != LocationType.VIRTUAL }
    val index = playable.indexOfFirst { it.id == item.id }
    if (index == -1) return null
    return playable.getOrNull(index + 1)
}

fun playerTitle(item: BaseItemDto) =
    if (item.type == BaseItemKind.EPISODE) "${item.seriesName.orEmpty()} · ${episodeLabel(item)}" else item.name.orEmpty()

/** The audio stream of [source] in language [lang], to pick for a transcode, which carries only one audio track. */
fun audioIndex(source: MediaSourceInfo, lang: String) =
    source.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && sameLanguage(it.language, lang) }?.index

/** Players use short codes ("ja") while the server keeps three letters ("jpn"). */
fun sameLanguage(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    // isO3Language throws for a code it doesn't know; then only the same text counts as the same language.
    val sameCode = runCatching { Locale.forLanguageTag(a).isO3Language == Locale.forLanguageTag(b).isO3Language }
    return sameCode.getOrDefault(a == b)
}

/**
 * Subtitle streams that players add by URL or from a fetched copy: the server's external files and, for a [transcode],
 * the embedded text ones, which the server extracts with their styling. A transcode drops embedded subtitles.
 */
fun sideloadedSubs(item: BaseItemDto, transcode: Boolean): List<MediaStream> {
    val streams = item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
    return streams.filter { it.type == MediaStreamType.SUBTITLE && (it.isExternal || (transcode && it.isTextSubtitleStream)) }
}

/**
 * The subtitle stream to start with. With nothing remembered for the series, the server's default, which follows the
 * account's subtitle settings in Jellyfin. With a remembered language ([lang]), a stream in it: the server's default
 * when it is one, else the first that isn't forced, since a forced track carries only signs and songs. "" means off.
 */
fun startSubtitle(source: MediaSourceInfo, lang: String?): MediaStream? {
    if (lang == "") return null
    val subs = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }
    val serverDefault = subs.firstOrNull { it.index == source.defaultSubtitleStreamIndex }
    if (lang == null) return serverDefault
    val inLanguage = subs.filter { sameLanguage(it.language, lang) }
    if (serverDefault != null && serverDefault in inLanguage) return serverDefault
    val full = inLanguage.firstOrNull { !it.isForced }
    if (full != null) return full
    return inLanguage.firstOrNull()
}

/** For a transcode: a picture subtitle (PGS, VobSub) in [lang] to burn into the video, when no text one is in that language. */
fun burnInIndex(source: MediaSourceInfo, lang: String?): Int? {
    if (lang.isNullOrEmpty()) return null
    val subs = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE && sameLanguage(it.language, lang) }
    if (subs.any { it.isTextSubtitleStream }) return null
    return subs.firstOrNull()?.index
}

class Chapter(val name: String, val startMs: Long)

/** The file's chapters, from the server. A single chapter counts as no chapters at all. */
fun chapters(item: BaseItemDto): List<Chapter> {
    val all = item.chapters.orEmpty().map { Chapter(it.name.orEmpty(), it.startPositionTicks / TICKS_PER_MS) }
    if (all.size > 1) return all
    return emptyList()
}

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
