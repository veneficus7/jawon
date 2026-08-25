package com.jawon.app.data.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class RouteInfo(
    val routeLabel: String,
    val outputSampleRateHz: Int
)

/**
 * Reads the live half of the quality readout — PLAN.md §7's "from the output
 * route" section. No AudioDeviceCallback / live updates (see M3 plan's scope
 * decision #3): callers re-read this when they need a fresh value.
 */
class AudioRouteReader @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun currentRoute(): RouteInfo {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()

        val routeLabel = when {
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "BLUETOOTH"
            devices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "USB DAC"
            devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "WIRED"
            else -> "SPEAKER"
        }

        val outputSampleRateHz = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?: 0

        return RouteInfo(routeLabel, outputSampleRateHz)
    }
}
