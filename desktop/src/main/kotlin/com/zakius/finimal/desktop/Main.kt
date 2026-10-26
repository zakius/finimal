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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.zakius.finimal.App
import com.zakius.finimal.Root
import com.zakius.finimal.Theme
import com.zakius.finimal.Toasts
import kotlinx.coroutines.delay
import okhttp3.Dispatcher

fun main() {
    App.platform = DesktopPlatform
    // Own dispatcher: a screen of thumbnails must not queue behind or ahead of API calls (5 per host each).
    SingletonImageLoader.setSafe { ctx ->
        ImageLoader.Builder(ctx).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { App.http.newBuilder().dispatcher(Dispatcher()).build() }))
        }.build()
    }
    application {
        val window = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(::exitApplication, window, title = "finimal") {
            Theme { CompositionLocalProvider(LocalWindow provides window) {
                Box(Modifier.fillMaxSize()) {
                    Root(openDownloads = false, openedDownloads = {}) { id, onBack, onPlay -> PlayerScreen(id, onBack, onPlay) }
                    ToastHost(Modifier.align(Alignment.BottomCenter))
                }
            } }
        }
    }
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
