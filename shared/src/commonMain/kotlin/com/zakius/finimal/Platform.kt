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
    /** Durable app files, such as download records and unsynced play state. */
    val appFiles: File
    /** Files that can be fetched again, such as saved lists. */
    val appCache: File
    val store: KeyValueStore
    /** A remote-first device (Android TV, a box without a touchscreen). */
    val autoTv: Boolean
    /** For work that outlives a screen, such as report flushes and prefetches. */
    val scope: CoroutineScope
    /** The video height a transcode asks for when Settings doesn't cap it, chosen to fit the device's H.264 decoder. */
    val transcodeHeight: Int
    /** Null where there are no downloads yet. */
    val downloads: Downloads?
    /** Sets the device identity for the Jellyfin SDK. */
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
    /** Applies all puts in [block] together. A null value removes the key. */
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
    val tv get() = when (prefs.interfaceMode) {
        1 -> false
        2 -> true
        else -> platform.autoTv
    }
    val autoTv get() = platform.autoTv

    /**
     * Set when the server rejects the token, which sends the UI back to login. Unsynced progress is kept
     * and pushed after the next login. It lives here rather than on [Server] so that the first frame
     * doesn't have to build the API client.
     */
    var sessionExpired by mutableStateOf(false)

    /**
     * Unsynced play states of the logged-in account. Each server and user has its own file, so
     * progress kept over a logout waits for that account and never reaches another.
     */
    val pending: PendingStore get() = synchronized(this) {
        // After a session expires, stops that are still being recorded belong to the account that just left.
        if (prefs.userId == null) {
            val kept = pendingStore
            if (kept != null) return kept
        }
        val account = prefs.baseUrl to prefs.userId
        val current = pendingStore
        if (current != null && pendingAccount == account) return current
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest("${account.first}\u0000${account.second}".toByteArray())
        val name = hash.take(8).joinToString("") { "%02x".format(it) }
        val file = File(platform.appFiles, "pending-$name.json")
        // Older versions kept one pending.json for everyone. It goes to the next account that logs in.
        val legacy = File(platform.appFiles, "pending.json")
        if (account.second != null && legacy.exists() && !file.exists()) legacy.renameTo(file)
        val store = PendingStore(file)
        pendingStore = store
        pendingAccount = account
        store
    }
    private var pendingStore: PendingStore? = null
    private var pendingAccount: Pair<String?, String?>? = null

    /**
     * IO one task at a time, in the order submitted. Play-state records and [Server.flush]'s read
     * share it, so a stop recorded on leaving the player lands before the next list flushes.
     */
    val disk = Dispatchers.IO.limitedParallelism(1)

    /**
     * Buffer start and stop, one at a time in order. Kept separate from [disk] so that cache and download
     * locks never hold up play-state records.
     */
    val buffers = Dispatchers.IO.limitedParallelism(1)
}
