package com.zakius.finimal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import finimal.core.PendingStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.jellyfin.sdk.JellyfinOptions

/** What the shared code needs from the app it runs in, Android's or the desktop's. Set as [App.platform] at start. */
interface Platform {
    val versionName: String
    /** Shows the Android-only settings: interface mode, touch gestures, metered networks, decoder limits. */
    val android: Boolean
    /** App files: download records, unsynced play state. */
    val appFiles: File
    /** Files that can be fetched again: saved lists. */
    val appCache: File
    val store: KeyValueStore
    /** A remote-first device (Android TV, a box without a touchscreen). */
    val autoTv: Boolean
    /** Work that outlives a screen: report flushes, prefetches. */
    val scope: CoroutineScope
    /** Video height a transcode asks for when Settings doesn't cap it: what the device's H.264 decoder takes. */
    val transcodeHeight: Int
    /** Null where there are no downloads yet. */
    val downloads: Downloads?
    /** Device identity for the Jellyfin SDK. */
    fun configure(options: JellyfinOptions.Builder)
    /** Pushes progress and applies "keep next N" in the background. */
    fun scheduleHousekeeping()
    /** Asks for the notification permission downloads show progress with, where there is one. */
    fun askNotifications()
    /** "h264 1080p60, hevc 4K…" for Settings; null hides the line. */
    fun decoderSummary(): String?
    fun setAudioDelay(ms: Int)
}

/** Settings storage: SharedPreferences on Android, a JSON file on the desktop. Numbers come back as any [Number]. */
interface KeyValueStore {
    operator fun get(key: String): Any?
    fun keys(): Set<String>
    /** Applies the puts together; a null value removes the key. */
    fun edit(block: MutableMap<String, Any?>.() -> Unit)
}

/** Process-wide singletons. There is one server and one user, so nothing here needs scoping. */
object App {
    lateinit var platform: Platform

    val prefs by lazy { Prefs(platform.store) }
    val server by lazy { Server() }
    /** Shared by the SDK, images and the player, so they reuse connections to the server. */
    val http by lazy { OkHttpClient() }
    val downloads get() = platform.downloads
    /** The remote-first interface: no touch-only controls, D-pad keys in the player, TV-safe margins. */
    val tv get() = when (prefs.interfaceMode) { 1 -> false; 2 -> true; else -> platform.autoTv }
    val autoTv get() = platform.autoTv

    /** Set when the server rejects the token; the UI returns to login. Unsynced progress is kept and pushed after. Here, not on [Server], so the first frame doesn't build the API client. */
    var sessionExpired by mutableStateOf(false)

    /**
     * Unsynced play states of the logged-in account. Each server and user has its own file, so
     * progress kept over a logout waits for that account and never reaches another.
     */
    val pending: PendingStore get() = synchronized(this) {
        // Logged out (an expired session): stops still recorded belong to the account that just left.
        if (prefs.userId == null) pendingStore?.let { return it }
        val account = prefs.baseUrl to prefs.userId
        pendingStore?.takeIf { pendingAccount == account } ?: run {
            val name = java.security.MessageDigest.getInstance("SHA-1").digest("${account.first}\u0000${account.second}".toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            val file = File(platform.appFiles, "pending-$name.json")
            // From before per-account files: it belongs to whoever logs in next, as it always did.
            val legacy = File(platform.appFiles, "pending.json")
            if (account.second != null && legacy.exists() && !file.exists()) legacy.renameTo(file)
            PendingStore(file).also { pendingStore = it; pendingAccount = account }
        }
    }
    private var pendingStore: PendingStore? = null
    private var pendingAccount: Pair<String?, String?>? = null

    /**
     * IO one task at a time, in the order submitted. Play-state records and [Server.flush]'s read
     * share it, so a stop recorded on leaving the player lands before the next list flushes.
     */
    val disk = Dispatchers.IO.limitedParallelism(1)

    /** Buffer start and stop, in order, apart from [disk] so cache and download locks never hold up play-state records. */
    val buffers = Dispatchers.IO.limitedParallelism(1)
}
