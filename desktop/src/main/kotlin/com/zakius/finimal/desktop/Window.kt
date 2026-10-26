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
            val w = int("window.width") ?: 1280
            val h = int("window.height") ?: 800
            val x = int("window.x")
            val y = int("window.y")
            // A monitor unplugged since would leave it out of reach.
            val visible = x != null && y != null && runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(Rectangle(x, y, w, h)) }
            }.getOrDefault(false)
            return WindowControl(WindowState(
                placement = if (store["window.maximized"] == true) WindowPlacement.Maximized else WindowPlacement.Floating,
                position = if (visible) WindowPosition(x!!.dp, y!!.dp) else WindowPosition(Alignment.Center),
                size = DpSize(w.dp, h.dp),
            ))
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
