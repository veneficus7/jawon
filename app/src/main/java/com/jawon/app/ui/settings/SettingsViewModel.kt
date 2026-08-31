package com.jawon.app.ui.settings

import androidx.lifecycle.ViewModel
import com.jawon.app.data.playback.EqBand
import com.jawon.app.data.playback.EqualizerController
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.playback.Crossfade
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val equalizer: EqualizerController
) : ViewModel() {

    val fetchCoverArt: StateFlow<Boolean> = preferences.fetchCoverArt

    val roomScheme: StateFlow<RoomScheme> = preferences.roomScheme

    fun onFetchCoverArtChanged(enabled: Boolean) = preferences.setFetchCoverArt(enabled)

    fun onRoomSchemeChanged(scheme: RoomScheme) = preferences.setRoomScheme(scheme)

    // ---- Equaliser -----------------------------------------------------------------
    //
    // Read straight off the controller rather than mirrored into this ViewModel: the bands
    // and their range are the *device's* answer, discovered when an effect is created, and
    // copying them here would only add a second version that can disagree.

    val eqAvailable: StateFlow<Boolean> = equalizer.available
    val eqEnabled: StateFlow<Boolean> = equalizer.enabled
    val eqBands: StateFlow<List<EqBand>> = equalizer.bands
    val eqRangeMb: StateFlow<Pair<Int, Int>> = equalizer.rangeMb
    val eqPresets: StateFlow<List<String>> = equalizer.presets

    fun onEqEnabledChanged(on: Boolean) = equalizer.setEnabled(on)
    fun onEqBandChanged(index: Int, levelMb: Int) = equalizer.setBand(index, levelMb)
    fun onEqPreset(index: Int) = equalizer.usePreset(index)
    fun onEqReset() = equalizer.reset()

    val crossfadeMs: StateFlow<Int> = preferences.crossfadeMs

    /** Snapped to whole tenths of a second, since the readout is shown to one decimal. */
    fun onCrossfadeChanged(ms: Int) =
        preferences.setCrossfadeMs(if (ms < Crossfade.MIN_MS) 0 else (ms / 100) * 100)
}
