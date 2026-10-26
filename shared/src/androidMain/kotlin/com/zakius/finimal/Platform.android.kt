package com.zakius.finimal

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** The application context, for toasts; set by the Application before anything else runs. */
lateinit var appContext: Context

@Composable
internal actual fun platformColors(dark: Boolean): ColorScheme {
    val ctx = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
}

@Composable
internal actual fun smallestWidthDp() = LocalConfiguration.current.smallestScreenWidthDp

actual fun toast(text: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }
}

@Composable
internal actual fun RetryOnNewNetwork(onRetry: () -> Unit) {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onRetry)
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val initial = cm.activeNetwork
        val main = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (network != initial) main.post { retry() } }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
}

/** [KeyValueStore] over SharedPreferences. Edits apply in the background, as before the move. */
class PrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun get(key: String): Any? = p.all[key]
    override fun keys(): Set<String> = p.all.keys
    override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
        val changes = LinkedHashMap<String, Any?>().apply(block)
        val e = p.edit()
        for ((k, v) in changes) when (v) {
            null -> e.remove(k)
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            else -> error("unsupported $k: $v")
        }
        e.apply()
    }
}
