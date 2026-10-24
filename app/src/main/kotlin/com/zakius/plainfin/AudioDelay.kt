@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zakius.plainfin

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Wireless headsets play audio late, by more than Android always accounts for. Video syncs to the
 * audio clock, so reporting that clock [App.prefs]' delay behind holds each frame back to match.
 * Applies only while a wireless output is connected; the speaker and wired outputs stay in sync.
 */
object AudioDelay {
    @Volatile private var wireless = false
    @Volatile private var ms = 0

    private val wirelessTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    private var watching = false

    /** Starts tracking outputs once; the callback reports the current ones at once. */
    fun watch(context: Context) {
        ms = App.prefs.audioDelayMs
        if (watching) return
        watching = true
        val am = context.getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            fun update() { wireless = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wirelessTypes } }
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = update()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = update()
        }, null)
    }

    fun set(delayMs: Int) {
        App.prefs.audioDelayMs = delayMs
        ms = delayMs
    }

    val us get() = if (wireless) ms * 1_000L else 0L

    /** Renderers whose audio clock runs [us] behind. */
    fun renderers(context: Context) = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { sink ->
                object : ForwardingAudioSink(sink) {
                    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                        val position = super.getCurrentPositionUs(sourceEnded)
                        return if (position == AudioSink.CURRENT_POSITION_NOT_SET) position else position - us
                    }
                }
            }
    }
}
