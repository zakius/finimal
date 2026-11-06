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
    val config = if (windows) {
        File(env("APPDATA") ?: "$home/AppData/Roaming", "finimal")
    } else {
        File(env("XDG_CONFIG_HOME") ?: "$home/.config", "finimal")
    }
    val data = if (windows) {
        File(env("LOCALAPPDATA") ?: "$home/AppData/Local", "finimal")
    } else {
        File(env("XDG_DATA_HOME") ?: "$home/.local/share", "finimal")
    }
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
        val savedId = store["deviceId"] as String?
        val id = if (savedId != null) {
            savedId
        } else {
            val newId = UUID.randomUUID().toString()
            store.edit { put("deviceId", newId) }
            newId
        }
        // Not InetAddress.getLocalHost(), which resolves the name and can stall for seconds where that fails, as in a sandbox.
        val computerName: String? = System.getenv("COMPUTERNAME")
        val name = if (computerName != null) {
            computerName
        } else {
            val hostname = runCatching { File("/proc/sys/kernel/hostname").readText().trim() }.getOrNull()
            if (hostname.isNullOrEmpty()) "Desktop" else hostname
        }
        options.deviceInfo = DeviceInfo(id, name)
    }

    // The desktop has no background job scheduler, so push unsynced progress now, while the app runs.
    override fun scheduleHousekeeping() { scope.launch { App.server.flush() } }
    override fun askNotifications() {}
    override fun decoderSummary(): String? = null
    override fun setAudioDelay(ms: Int) { App.prefs.audioDelayMs = ms }
}
