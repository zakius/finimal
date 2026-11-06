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

@Suppress("FunctionName")
private interface Kernel32 : com.sun.jna.Library {
    fun SetThreadExecutionState(flags: Int): Int
}

/**
 * Keeps the screen on while a video plays. mpv does this itself only when it has its own window, so the embedded
 * player must do it here. On Linux it uses the desktop portal's Inhibit (which a Flatpak may use), falling back to
 * org.freedesktop.ScreenSaver. On Windows it uses SetThreadExecutionState, which holds only while the thread that set
 * it lives. So everything runs on one worker thread, which also keeps a slow D-Bus from holding up the player.
 */
object ScreenSaver {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "screensaver").apply { isDaemon = true } }
    private var bus: DBusConnection? = null
    private var release: (() -> Unit)? = null

    fun inhibit(on: Boolean) = worker.execute {
        if (on == (release != null)) return@execute
        if (!on) {
            runCatching { release?.invoke() }
            release = null
            return@execute
        }
        val held = runCatching { hold() }
        held.onFailure { e -> System.err.println("finimal: can't keep the screen on: $e") }
        release = held.getOrNull()
    }

    /** For --check-player on Windows. Sets and clears the state on the worker thread as playback would, and throws if it can't. */
    fun checkWindows() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        worker.submit { hold()!!.invoke() }.get()
    }

    private fun hold(): (() -> Unit)? {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val kernel = com.sun.jna.Native.load("kernel32", Kernel32::class.java)
            val flags = ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED
            check(kernel.SetThreadExecutionState(flags) != 0) { "SetThreadExecutionState failed" }
            return { kernel.SetThreadExecutionState(ES_CONTINUOUS) } // ES_CONTINUOUS alone clears the other flags.
        }
        val conn = bus ?: DBusConnectionBuilder.forSessionBus().build()
        bus = conn
        return runCatching {
            val portal = conn.getRemoteObject("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", PortalInhibit::class.java)
            // The inhibition lasts until its request object is closed.
            val request = portal.Inhibit("", UInt32(INHIBIT_IDLE), mapOf("reason" to Variant("Playing a video")))
            val handle = conn.getRemoteObject("org.freedesktop.portal.Desktop", request.path, PortalRequest::class.java)
            ({ handle.Close() })
        }.getOrElse {
            val saver = conn.getRemoteObject("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", FreedesktopScreenSaver::class.java)
            val cookie = saver.Inhibit("finimal", "Playing a video")
            ({ saver.UnInhibit(cookie) })
        }
    }
}

private const val ES_CONTINUOUS = 0x80000000.toInt()
private const val ES_DISPLAY_REQUIRED = 0x2
private const val ES_SYSTEM_REQUIRED = 0x1
private const val INHIBIT_IDLE = 8L
