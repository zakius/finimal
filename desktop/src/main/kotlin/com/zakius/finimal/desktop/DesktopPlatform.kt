package com.zakius.finimal.desktop

import com.zakius.finimal.App
import com.zakius.finimal.JsonFileStore
import com.zakius.finimal.Platform
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.model.DeviceInfo

/** Folders by the platform's conventions: XDG on Linux, %APPDATA% and %LOCALAPPDATA% on Windows. */
private object Dirs {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val home = System.getProperty("user.home")
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
    val config = File(if (windows) env("APPDATA") ?: "$home/AppData/Roaming" else env("XDG_CONFIG_HOME") ?: "$home/.config", "finimal")
    val data = File(if (windows) env("LOCALAPPDATA") ?: "$home/AppData/Local" else env("XDG_DATA_HOME") ?: "$home/.local/share", "finimal")
    val cache = if (windows) File(data, "cache") else File(env("XDG_CACHE_HOME") ?: "$home/.cache", "finimal")
}

object DesktopPlatform : Platform {
    override val versionName = System.getProperty("finimal.version") ?: "dev"
    override val android = false
    override val appFiles = Dirs.data.apply { mkdirs() }
    override val appCache = Dirs.cache.apply { mkdirs() }
    override val store = JsonFileStore(File(Dirs.config, "settings.json"))
    override val autoTv = false
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // mpv decodes everything, so nothing transcodes unless the "Direct play up to" setting caps the height.
    override val transcodeHeight = 2160
    override val downloads = null

    override fun configure(options: JellyfinOptions.Builder) {
        val id = store["deviceId"] as String? ?: UUID.randomUUID().toString().also { id -> store.edit { put("deviceId", id) } }
        // Not InetAddress.getLocalHost(): it resolves the name, which can stall seconds where it doesn't resolve (a sandbox).
        val name = System.getenv("COMPUTERNAME") ?: runCatching { File("/proc/sys/kernel/hostname").readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: "Desktop"
        options.deviceInfo = DeviceInfo(id, name)
    }

    // No background job scheduler here: push unsynced progress now, while the app runs.
    override fun scheduleHousekeeping() { scope.launch { App.server.flush() } }
    override fun askNotifications() {}
    override fun decoderSummary(): String? = null
    override fun setAudioDelay(ms: Int) { App.prefs.audioDelayMs = ms }
}
