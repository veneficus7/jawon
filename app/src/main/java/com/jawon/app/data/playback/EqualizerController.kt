package com.jawon.app.data.playback

import android.media.audiofx.Equalizer
import android.util.Log
import com.jawon.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One band of the equaliser, as the UI needs to draw it.
 *
 * Read from the device rather than assumed. The number of bands, their centre frequencies and
 * the gain range are all decided by the platform's effect implementation — five bands is
 * usual but not guaranteed, and a UI built on that assumption is wrong on the first phone
 * that disagrees.
 */
data class EqBand(
    val index: Int,
    /** Centre frequency in Hz. */
    val centerHz: Int,
    /** Current gain in millibels. */
    val levelMb: Int
)

/**
 * The playback equaliser.
 *
 * **It lives here rather than in the service because it outlives any one player.** The effect
 * is attached to an audio session id, and the session belongs to the ExoPlayer that
 * `PlaybackService` builds — but the user's settings belong to the app. This holds the
 * settings, and attaches or reattaches to whatever session is current.
 *
 * Everything is defensive. `Equalizer` is an optional platform effect: it can be missing,
 * refused while another app holds it, or simply throw on construction, and none of that is a
 * reason for playback to fail. A device with no working equaliser reports zero bands and the
 * settings screen says so.
 */
@Singleton
class EqualizerController @Inject constructor(
    private val preferences: AppPreferences
) {

    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    /** Gain range in millibels, as [min, max]. Empty until an effect exists. */
    private val _rangeMb = MutableStateFlow(0 to 0)
    val rangeMb: StateFlow<Pair<Int, Int>> = _rangeMb.asStateFlow()

    /** Names the platform offers, e.g. Normal, Rock, Jazz. Empty if it offers none. */
    private val _presets = MutableStateFlow<List<String>>(emptyList())
    val presets: StateFlow<List<String>> = _presets.asStateFlow()

    /** True once an effect has actually been created — the screen needs to know. */
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    val enabled: StateFlow<Boolean> = preferences.equalizerEnabled

    /**
     * Bind to a player's audio session.
     *
     * Called when the service builds its ExoPlayer. Rebinding to the same session is a no-op;
     * a different session releases the old effect first, because an `AudioEffect` left
     * attached to a dead session holds a slot the new one may need.
     */
    fun attach(newSessionId: Int) {
        if (newSessionId == 0) return
        if (equalizer != null && sessionId == newSessionId) return
        release()
        sessionId = newSessionId

        val eq = runCatching { Equalizer(EFFECT_PRIORITY, newSessionId) }
            .onFailure { Log.w(TAG, "no equalizer for session $newSessionId: ${it.message}") }
            .getOrNull() ?: run {
                _available.value = false
                return
            }
        equalizer = eq

        val range = runCatching { eq.bandLevelRange }.getOrNull()
        _rangeMb.value = (range?.getOrNull(0)?.toInt() ?: 0) to (range?.getOrNull(1)?.toInt() ?: 0)
        _presets.value = runCatching {
            (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
        }.getOrDefault(emptyList())

        // Whatever the user last chose, reapplied. The effect is created fresh with every
        // player, so without this the equaliser silently resets every time playback restarts.
        val stored = preferences.equalizerBands.value
        runCatching {
            eq.enabled = preferences.equalizerEnabled.value
            stored.forEachIndexed { index, level ->
                if (index < eq.numberOfBands) {
                    eq.setBandLevel(index.toShort(), level.toShort())
                }
            }
        }.onFailure { Log.w(TAG, "could not restore equaliser: ${it.message}") }

        _available.value = true
        publishBands()
    }

    fun setEnabled(on: Boolean) {
        preferences.setEqualizerEnabled(on)
        runCatching { equalizer?.enabled = on }
            .onFailure { Log.w(TAG, "could not toggle equaliser: ${it.message}") }
    }

    fun setBand(index: Int, levelMb: Int) {
        val eq = equalizer ?: return
        runCatching {
            eq.setBandLevel(index.toShort(), levelMb.toShort())
        }.onFailure { return }
        preferences.setEqualizerBands(currentLevels())
        publishBands()
    }

    /** Apply one of the platform's own presets, then remember the levels it produced. */
    fun usePreset(index: Int) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(index.toShort()) }.onFailure { return }
        preferences.setEqualizerBands(currentLevels())
        publishBands()
    }

    /** Everything back to flat — the honest "off" for an equaliser that is still enabled. */
    fun reset() {
        val eq = equalizer ?: return
        runCatching {
            for (band in 0 until eq.numberOfBands) eq.setBandLevel(band.toShort(), 0)
        }.onFailure { return }
        preferences.setEqualizerBands(currentLevels())
        publishBands()
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        _available.value = false
    }

    private fun currentLevels(): List<Int> = runCatching {
        val eq = equalizer ?: return emptyList()
        (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
    }.getOrDefault(emptyList())

    private fun publishBands() {
        val eq = equalizer ?: return
        _bands.value = runCatching {
            (0 until eq.numberOfBands).map { band ->
                EqBand(
                    index = band,
                    // The platform reports millihertz; nobody thinks in those.
                    centerHz = eq.getCenterFreq(band.toShort()) / 1000,
                    levelMb = eq.getBandLevel(band.toShort()).toInt()
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val TAG = "EqualizerController"

        /**
         * Zero: the ordinary priority for an app touching its own session.
         *
         * Higher values ask the platform to take the effect away from someone else, which is
         * not Jawon's to do — a music player is not more entitled to the hardware than
         * whatever the user already had running.
         */
        const val EFFECT_PRIORITY = 0
    }
}
