package com.zakius.finimal.desktop

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Rectangle
import java.awt.Toolkit

/** The window, for the player: fullscreen that comes back to what was there before, and the title. */
val LocalWindow = compositionLocalOf<WindowControl?> { null }

class WindowControl(val state: WindowState) {
    var title by mutableStateOf("finimal")
    /** The player's keys, seen first by the whole window. */
    var keys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
    private var beforeFullscreen = WindowPlacement.Floating

    val fullscreen get() = state.placement == WindowPlacement.Fullscreen

    fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        if (on) { beforeFullscreen = state.placement; state.placement = WindowPlacement.Fullscreen; return }
        // Back to maximized when it was, not to the floating size from before that. Through floating: on X11 a
        // fullscreen window told to maximize stays fullscreen.
        state.placement = WindowPlacement.Floating
        if (beforeFullscreen == WindowPlacement.Maximized) scope.launch {
            delay(150)
            if (state.placement == WindowPlacement.Floating) state.placement = WindowPlacement.Maximized
        }
    }

    private val scope = MainScope()

    fun toggleFullscreen() = setFullscreen(!fullscreen)

    companion object {
        private val store get() = DesktopPlatform.store

        /** Where and how big the window was when it last moved; centred at 1280×800 the first time or off-screen. */
        fun restore(): WindowControl {
            fun int(key: String) = (store[key] as Number?)?.toInt()
            val saved = Rectangle(int("window.x") ?: 0, int("window.y") ?: 0, int("window.width") ?: 1280, int("window.height") ?: 800)
            val bounds = runCatching {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val screens = ge.screenDevices.map { it.defaultConfiguration }.map { c ->
                    val i = Toolkit.getDefaultToolkit().getScreenInsets(c)
                    c.bounds.run { Rectangle(x + i.left, y + i.top, width - i.left - i.right, height - i.top - i.bottom) }
                }
                fit(saved, int("window.x") != null && int("window.y") != null, ge.maximumWindowBounds, screens)
            }.getOrNull() // headless
            return WindowControl(WindowState(
                placement = if (store["window.maximized"] == true) WindowPlacement.Maximized else WindowPlacement.Floating,
                position = bounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
                size = (bounds ?: saved).let { DpSize(it.width.dp, it.height.dp) },
            ))
        }

        /**
         * [saved] made to fit the free area (less taskbars and panels) of the screen it overlaps, or centred in the main
         * screen's when it overlaps none (a monitor unplugged since) or has no place yet. A smaller monitor than last
         * time, or a 768 px laptop, would otherwise leave the window's bottom under the taskbar.
         */
        fun fit(saved: Rectangle, placed: Boolean, main: Rectangle, screens: List<Rectangle>): Rectangle {
            val screen = screens.firstOrNull { placed && it.intersects(saved) }
            val area = screen ?: main
            val w = saved.width.coerceAtMost(area.width)
            val h = saved.height.coerceAtMost(area.height)
            if (screen == null) return Rectangle(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
            return Rectangle(saved.x.coerceIn(area.x, area.x + area.width - w), saved.y.coerceIn(area.y, area.y + area.height - h), w, h)
        }
    }

    /** Remembers the floating bounds and whether it was maximized; fullscreen is left to the player, never restored. */
    fun save() {
        val s = state
        store.edit {
            when (s.placement) {
                WindowPlacement.Maximized -> put("window.maximized", true)
                WindowPlacement.Floating -> {
                    put("window.maximized", false)
                    put("window.width", s.size.width.value.toInt())
                    put("window.height", s.size.height.value.toInt())
                    (s.position as? WindowPosition.Absolute)?.let { put("window.x", it.x.value.toInt()); put("window.y", it.y.value.toInt()) }
                }
                WindowPlacement.Fullscreen -> {}
            }
        }
    }
}
