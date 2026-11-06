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
            // Save a moment after the window settles, not on every pixel of a drag.
            snapshotFlow { Triple(control.state.placement, control.state.position, control.state.size) }.collectLatest {
                delay(500)
                control.save()
            }
        }
        Window(
            ::exitApplication,
            control.state,
            title = control.title,
            icon = remember { appIcon() },
            onPreviewKeyEvent = { event ->
                val handler = control.keys
                if (handler != null) handler(event) else false
            },
        ) {
            Theme {
                CompositionLocalProvider(LocalWindow provides control) {
                    Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { e ->
                        // The mouse's back button acts like Esc.
                        if (e.button == PointerButton.Back) pressEscape()
                    }) {
                        Root(openDownloads = false, openedDownloads = {}) { id, onBack, onPlay -> PlayerScreen(id, onBack, onPlay) }
                        ToastHost(Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/**
 * On Linux, KDE and other desktops that leave X11 apps to scale themselves expose the scale only as Xft.dpi. OpenJDK
 * never reads that (it follows GDK_SCALE and GNOME's setting), so the app came up at 1x on a 2x screen. This sets
 * Java's scale from Xft.dpi unless one is already set (sun.java2d.uiScale or J2D_UISCALE), and must run before the
 * first window opens. Java scales X11 windows by whole numbers only, so 1.5 rounds to 2.
 */
private fun scaleFromXftDpi() {
    if (!System.getProperty("os.name").startsWith("Linux") || System.getProperty("sun.java2d.uiScale") != null ||
        System.getenv("J2D_UISCALE") != null) return
    val resources = runCatching {
        val x11 = Native.load("libX11.so.6", X11::class.java)
        val display = x11.XOpenDisplay(null) ?: return
        try { x11.XResourceManagerString(display) } finally { x11.XCloseDisplay(display) }
    }.getOrNull() ?: return
    val match = Regex("""^Xft\.dpi:\s*([\d.]+)""", RegexOption.MULTILINE).find(resources) ?: return
    val dpi = match.groupValues[1].toDoubleOrNull() ?: return
    val scale = maxOf(1, Math.round(dpi / 96).toInt())
    System.setProperty("sun.java2d.uiScale", scale.toString())
}

@Suppress("FunctionName")
private interface X11 : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XResourceManagerString(display: Pointer): String?
    fun XCloseDisplay(display: Pointer): Int
}

/**
 * Images get their own dispatcher, so a screen of thumbnails doesn't queue behind or ahead of API calls (OkHttp
 * allows 5 per host per dispatcher). The ServiceLoader scan is off, as on Android, and the one fetcher is added here:
 * in the ProGuard build the scan found a registration whose class had been stripped, and no image loaded.
 */
private fun imageLoader(ctx: PlatformContext) = ImageLoader.Builder(ctx).serviceLoaderEnabled(false).components {
    add(OkHttpNetworkFetcherFactory(callFactory = { App.http.newBuilder().dispatcher(Dispatcher()).build() }))
}.build()

/** On the desktop, Esc acts as Back. The player handles it first (leaving fullscreen, then the player), then the screens. */
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
    LaunchedEffect(toast) {
        shown = toast
        delay(3_000)
        shown = null
    }
    val current = shown
    if (current != null) {
        val (_, text) = current
        Text(
            text,
            modifier
                .padding(32.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/**
 * Checks a packaged build without a screen. Loads libmpv, renders a frame of a clip mpv generates, and plays it to
 * the end. Prints the outcome and returns exit code 0 on success.
 */
private fun checkPlayer(): Int = try {
    // Load one class from each runtime module added beyond the defaults in desktop/build.gradle.kts. A trimmed runtime
    // can lack one while the Gradle run on the full JDK works, as happened when dbus-java needed jdk.security.auth.
    listOf("com.sun.security.auth.module.UnixSystem", "javax.naming.directory.InitialDirContext", "sun.misc.Unsafe",
        "sun.security.ec.ECKeyFactory").forEach { Class.forName(it) }
    // Build the image loader as the app does and decode the bundled icon. In a ProGuard build it once loaded nothing.
    val icon = File.createTempFile("finimal-check", ".png")
    icon.deleteOnExit()
    icon.writeBytes(object {}.javaClass.getResource("/finimal.png")!!.readBytes())
    val image = runBlocking {
        val loader = imageLoader(PlatformContext.INSTANCE)
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(icon).build()
        loader.execute(request)
    }
    check(image is SuccessResult) { "images don't load: ${(image as? ErrorResult)?.throwable}" }
    ScreenSaver.checkWindows()
    val mpv = Mpv(mapOf("ao" to "null"))
    mpv.resize(64, 36)
    mpv.load("av://lavfi:testsrc=duration=1:size=64x48:rate=10", 0.0)
    val deadline = System.currentTimeMillis() + 20_000
    while ((mpv.frame == null || !mpv.eof) && mpv.error == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
    // A duration proves the file opened, since an idle mpv draws black frames too.
    val ok = mpv.frame != null && mpv.duration > 0.5 && mpv.eof && mpv.error == null
    println(if (ok) "player ok" else "player failed: frame=${mpv.frame != null} duration=${mpv.duration} eof=${mpv.eof} error=${mpv.error}")
    mpv.close()
    if (ok) 0 else 1
} catch (e: Throwable) {
    println("player failed: $e")
    1
}

private fun appIcon(): BitmapPainter {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("finimal.png")!!
    val bytes = stream.use { it.readAllBytes() }
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
