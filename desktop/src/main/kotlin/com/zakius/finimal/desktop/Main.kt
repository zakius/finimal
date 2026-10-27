package com.zakius.finimal.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import kotlinx.coroutines.flow.collectLatest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import coil3.serviceLoaderEnabled
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.zakius.finimal.App
import com.zakius.finimal.Root
import com.zakius.finimal.Theme
import com.zakius.finimal.Toasts
import kotlinx.coroutines.delay
import okhttp3.Dispatcher
import kotlin.system.exitProcess

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    if ("--check-player" in args) exitProcess(checkPlayer())
    scaleFromXftDpi()
    App.platform = DesktopPlatform
    SingletonImageLoader.setSafe(::imageLoader)
    application {
        val control = remember { WindowControl.restore() }
        LaunchedEffect(control) {
            // Saved a moment after it settles, not on every pixel of a drag.
            snapshotFlow { Triple(control.state.placement, control.state.position, control.state.size) }.collectLatest { delay(500); control.save() }
        }
        Window(::exitApplication, control.state, title = control.title, icon = remember { appIcon() },
            onPreviewKeyEvent = { control.keys?.invoke(it) ?: false }) {
            Theme { CompositionLocalProvider(LocalWindow provides control) {
                Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { e ->
                    // The mouse's back button goes back, as Esc does.
                    if (e.button == PointerButton.Back) pressEscape()
                }) {
                    Root(openDownloads = false, openedDownloads = {}) { id, onBack, onPlay -> PlayerScreen(id, onBack, onPlay) }
                    ToastHost(Modifier.align(Alignment.BottomCenter))
                }
            } }
        }
    }
}

/**
 * Linux: KDE, and other desktops that leave X11 apps to scale themselves, give the scale only as Xft.dpi, which
 * OpenJDK never reads (it follows GDK_SCALE and GNOME's setting), so finimal came up at 1x on a 2x screen. Java's
 * scale from Xft.dpi, then, unless one is set (sun.java2d.uiScale, J2D_UISCALE); before the first window.
 * Java scales X11 windows by whole numbers only: 1.5 rounds to 2.
 */
private fun scaleFromXftDpi() {
    if (!System.getProperty("os.name").startsWith("Linux") || System.getProperty("sun.java2d.uiScale") != null ||
        System.getenv("J2D_UISCALE") != null) return
    val resources = runCatching {
        val x11 = Native.load("libX11.so.6", X11::class.java)
        val display = x11.XOpenDisplay(null) ?: return
        try { x11.XResourceManagerString(display) } finally { x11.XCloseDisplay(display) }
    }.getOrNull() ?: return
    val dpi = Regex("""^Xft\.dpi:\s*([\d.]+)""", RegexOption.MULTILINE).find(resources)?.groupValues?.get(1)?.toDoubleOrNull() ?: return
    System.setProperty("sun.java2d.uiScale", maxOf(1, Math.round(dpi / 96).toInt()).toString())
}

@Suppress("FunctionName")
private interface X11 : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XResourceManagerString(display: Pointer): String?
    fun XCloseDisplay(display: Pointer): Int
}

/**
 * Own dispatcher: a screen of thumbnails must not queue behind or ahead of API calls (5 per host each). No
 * ServiceLoader scan, as on Android: the one fetcher is added here, and in the ProGuard build the scan found a
 * registration whose class was gone, and no image loaded.
 */
private fun imageLoader(ctx: PlatformContext) = ImageLoader.Builder(ctx).serviceLoaderEnabled(false).components {
    add(OkHttpNetworkFetcherFactory(callFactory = { App.http.newBuilder().dispatcher(Dispatcher()).build() }))
}.build()

/** Back on the desktop is Esc: the player takes it first (out of fullscreen, then out), the screens after. */
private fun pressEscape() {
    val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return
    val now = System.currentTimeMillis()
    for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED))
        target.dispatchEvent(KeyEvent(target, id, now, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))
}

/** Shows each [com.zakius.finimal.toast] for 3 s. */
@Composable
private fun ToastHost(modifier: Modifier) {
    val toast = Toasts.current
    var shown by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(toast) { shown = toast; delay(3_000); shown = null }
    shown?.let { (_, text) ->
        Text(text, modifier.padding(32.dp).background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small).padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

/**
 * For package checks without a screen: loads libmpv, renders a frame of a clip mpv generates and plays it to the end.
 * Prints the outcome; exit code 0 when it worked.
 */
private fun checkPlayer(): Int = try {
    // One class from each runtime module beyond the defaults (desktop/build.gradle.kts), which a trimmed runtime can
    // lack while the Gradle run, on the full JDK, works: dbus-java needed jdk.security.auth.
    listOf("com.sun.security.auth.module.UnixSystem", "javax.naming.directory.InitialDirContext", "sun.misc.Unsafe",
        "sun.security.ec.ECKeyFactory").forEach { Class.forName(it) }
    // The image loader as the app builds it, decoding the bundled icon: in a ProGuard build it once loaded nothing.
    val icon = File.createTempFile("finimal-check", ".png").apply { deleteOnExit(); writeBytes(object {}.javaClass.getResource("/finimal.png")!!.readBytes()) }
    val image = runBlocking { imageLoader(PlatformContext.INSTANCE).execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(icon).build()) }
    check(image is SuccessResult) { "images don't load: ${(image as? ErrorResult)?.throwable}" }
    ScreenSaver.checkWindows()
    val mpv = Mpv(mapOf("ao" to "null"))
    mpv.resize(64, 36)
    mpv.load("av://lavfi:testsrc=duration=1:size=64x48:rate=10", 0.0)
    val deadline = System.currentTimeMillis() + 20_000
    while ((mpv.frame == null || !mpv.eof) && mpv.error == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
    // A duration proves the file opened: an idle mpv draws black frames too.
    val ok = mpv.frame != null && mpv.duration > 0.5 && mpv.eof && mpv.error == null
    println(if (ok) "player ok" else "player failed: frame=${mpv.frame != null} duration=${mpv.duration} eof=${mpv.eof} error=${mpv.error}")
    mpv.close()
    if (ok) 0 else 1
} catch (e: Throwable) {
    println("player failed: $e")
    1
}

private fun appIcon() = BitmapPainter(
    Image.makeFromEncoded(Thread.currentThread().contextClassLoader.getResourceAsStream("finimal.png")!!.use { it.readAllBytes() })
        .toComposeImageBitmap())
