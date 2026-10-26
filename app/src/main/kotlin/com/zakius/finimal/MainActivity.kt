package com.zakius.finimal

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.layout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface Screen {
    @Serializable data object Login : Screen
    @Serializable data object Libraries : Screen
    @Serializable data object Downloads : Screen
    @Serializable data object Settings : Screen
    @Serializable data class Library(val id: String?, val name: String) : Screen
    @Serializable data class Series(val id: String, val name: String) : Screen
    @Serializable data class Season(val seriesId: String, val seasonId: String, val name: String) : Screen
    @Serializable data class Play(val itemId: String) : Screen
}

class MainActivity : ComponentActivity() {
    // Denied is fine: downloads run without the notification. Android stops asking after two denials.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var openDownloads by mutableStateOf(false)

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash goes the moment the first frame is ready, instead of after Android's fade-out (about 130 ms).
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        enableEdgeToEdge()
        // A recreated activity has its stack restored; don't push Downloads again.
        if (savedInstanceState == null) openDownloads = intent.getBooleanExtra(EXTRA_DOWNLOADS, false)
        setContent { Theme { Root(openDownloads) { openDownloads = false } } }
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                decor.post { // not from inside onDraw
                    decor.viewTreeObserver.removeOnDrawListener(this)
                    App.instance.afterFirstFrame()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_DOWNLOADS, false)) openDownloads = true
    }

    companion object { const val EXTRA_DOWNLOADS = "downloads" }
}

@Composable
private fun Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // Material's focus highlight (10%) can't be seen from a sofa; this is what shows where the D-pad is.
    val ripple = RippleConfiguration(rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.35f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalRippleConfiguration provides ripple) { Surface(Modifier.fillMaxSize(), content = content) }
    }
}

@Composable
private fun Root(openDownloads: Boolean, openedDownloads: () -> Unit) {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { list -> list.map { Json.encodeToString(Screen.serializer(), it) } },
            restore = { saved -> saved.map { Json.decodeFromString(Screen.serializer(), it) }.toMutableStateList() },
        )
    ) { listOf(if (App.prefs.loggedIn) Screen.Libraries else Screen.Login).toMutableStateList() }

    // Each stack entry keeps its UI state (scroll, sort, search) while covered; leaving the stack drops it.
    val saved = rememberSaveableStateHolder()
    fun keyOf(i: Int) = "$i:${stack[i]}"
    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.size > 1) { saved.removeState(keyOf(stack.lastIndex)); stack.removeAt(stack.lastIndex) } }
    fun reset(s: Screen) { stack.indices.forEach { saved.removeState(keyOf(it)) }; stack.clear(); stack.add(s) }

    BackHandler(stack.size > 1) { pop() }
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
                Screen.Libraries -> LibrariesScreen(padded, ::push)
                Screen.Downloads -> DownloadsScreen(padded, ::push, ::pop)
                Screen.Settings -> SettingsScreen(padded, ::pop) { reset(Screen.Login) }
                is Screen.Library -> LibraryScreen(padded, screen, ::push, ::pop)
                is Screen.Series -> SeriesScreen(padded, screen, ::push, ::pop)
                is Screen.Season -> SeasonScreen(padded, screen, ::push, ::pop)
                // "Play next" replaces the player instead of stacking players.
                is Screen.Play -> PlayerScreen(screen.itemId, ::pop) { next -> pop(); push(Screen.Play(next)) }
            } } } }
        }
    } } }
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
