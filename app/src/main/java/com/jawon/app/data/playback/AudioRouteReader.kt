package com.jawon.app.data.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class RouteInfo(
    val routeLabel: String,
    /**
     * The device's output sample rate, or `0` when it cannot be established.
     *
     * Zero is a real answer and the readout treats it as one — it prints nothing rather than
     * guessing. See [AudioRouteReader] for why this is harder to know than it looks.
     */
    val outputSampleRateHz: Int
)

/**
 * Reads the live half of the quality readout — `PLAN.md` §7's "from the output route".
 *
 * **This was wrong in three ways and the readout was quietly lying.** `DESIGN.md` rules out a
 * quality readout that overstates what is happening, so these are worth naming:
 *
 * 1. **It listed connected devices, not the one in use.** `getDevices(GET_DEVICES_OUTPUTS)`
 *    returns everything currently *connected*, and the old code took the first match in a
 *    fixed order with Bluetooth first. A paired speaker sitting idle in the next room made the
 *    readout say BLUETOOTH while the sound came out of the phone. API 34+ can answer the real
 *    question — which device would audio with these attributes actually go to — and that is
 *    what is asked now; below 34 the heuristic stays, reordered so wired beats idle Bluetooth.
 *
 * 2. **It was read once and never again.** The caller cached one `RouteInfo` at construction,
 *    so plugging in headphones after opening the app left the readout claiming SPEAKER for the
 *    rest of the session. It is read on demand now, and the deck asks per track change.
 *
 * 3. **`PROPERTY_OUTPUT_SAMPLE_RATE` is not the media output rate.** It is the *low-latency
 *    fast path's* preferred rate, which is what a game or a synth would use; media is mixed
 *    elsewhere and Bluetooth is resampled again inside the codec. Reporting it as "OUTPUT" was
 *    the most confident of the three lies. It is still the best number available to an app,
 *    so it is still used — but only for the built-in speaker and wired output, where it is
 *    usually right, and never for Bluetooth, where it is usually wrong.
 */
class AudioRouteReader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    fun currentRoute(): RouteInfo {
        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return RouteInfo("", 0)

        val active = activeOutputType(audioManager)
        val routeLabel = when (active) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH"

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"

            // A route we have no name for is better left unnamed than called SPEAKER.
            else -> ""
        }

        // Never claimed for Bluetooth: A2DP re-encodes to the codec's own rate, so the
        // device's mixer rate says nothing about what the headphones receive.
        val rate = if (routeLabel == "BLUETOOTH") {
            0
        } else {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        }

        return RouteInfo(routeLabel, rate)
    }

    /**
     * The device media would actually be played through.
     *
     * `getAudioDevicesForAttributes` is the only public API that answers this rather than
     * listing what is plugged in, and it arrived in API 34. Below that there is no way to
     * ask, so the old heuristic remains — with the order corrected so a *connected but
     * unused* Bluetooth device no longer outranks the wired headphones the sound is really
     * going to.
     */
    private fun activeOutputType(audioManager: AudioManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            runCatching { audioManager.getAudioDevicesForAttributes(attributes) }
                .getOrNull()
                ?.firstOrNull()
                ?.let { return it.type }
        }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        // Wired first: plugging a cable in is the one case where the platform is guaranteed to
        // have switched, whereas a connected A2DP device may be doing nothing at all.
        val order = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        return order.firstOrNull { type -> devices.any { it.type == type } }
            ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }
}
