package com.zakius.finimal.desktop

import java.util.concurrent.Executors
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.portal.Inhibit")
private interface PortalInhibit : DBusInterface {
    fun Inhibit(window: String, flags: UInt32, options: Map<String, Variant<*>>): DBusPath
}

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.portal.Request")
private interface PortalRequest : DBusInterface { fun Close() }

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.ScreenSaver")
private interface FreedesktopScreenSaver : DBusInterface {
    fun Inhibit(application: String, reason: String): UInt32
    fun UnInhibit(cookie: UInt32)
}

/**
 * Keeps the screen on while a video plays. mpv does this itself only with its own window; embedded, it's ours to do.
 * Linux: the desktop portal's Inhibit (what a Flatpak may use), else org.freedesktop.ScreenSaver. D-Bus calls run on
 * their own thread, so a slow bus never holds up the player.
 */
object ScreenSaver {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "screensaver").apply { isDaemon = true } }
    private var bus: DBusConnection? = null
    private var release: (() -> Unit)? = null

    fun inhibit(on: Boolean) = worker.execute {
        if (on == (release != null)) return@execute
        if (!on) { runCatching { release?.invoke() }; release = null; return@execute }
        release = runCatching { hold() }.getOrNull()
    }

    private fun hold(): (() -> Unit)? {
        if (System.getProperty("os.name").startsWith("Windows")) return null
        val conn = bus ?: DBusConnectionBuilder.forSessionBus().build().also { bus = it }
        return runCatching {
            val portal = conn.getRemoteObject("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", PortalInhibit::class.java)
            // 8: idle. The inhibition lasts until its request object is closed.
            val request = portal.Inhibit("", UInt32(8), mapOf("reason" to Variant("Playing a video")))
            val handle = conn.getRemoteObject("org.freedesktop.portal.Desktop", request.path, PortalRequest::class.java)
            ({ handle.Close() })
        }.getOrElse {
            val saver = conn.getRemoteObject("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", FreedesktopScreenSaver::class.java)
            val cookie = saver.Inhibit("finimal", "Playing a video")
            ({ saver.UnInhibit(cookie) })
        }
    }
}
