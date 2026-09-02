package com.jawon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.jawon.app.data.backup.LibraryBackup
import com.jawon.app.data.playback.EqBand
import com.jawon.app.data.playback.EqualizerController
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.data.update.AvailableUpdate
import com.jawon.app.data.update.UpdateChecker
import com.jawon.app.data.update.UpdateState
import com.jawon.app.playback.Crossfade
import com.jawon.app.ui.theme.DeckStyle
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val equalizer: EqualizerController,
    private val updateChecker: UpdateChecker,
    private val backup: LibraryBackup,
    private val diagnostics: com.jawon.app.ui.wallscene.RoomDiagnostics
) : ViewModel() {

    val fetchCoverArt: StateFlow<Boolean> = preferences.fetchCoverArt

    /** See [com.jawon.app.data.lyrics.LrclibClient] for why LRCLIB and not a commercial one. */
    val fetchLyrics: StateFlow<Boolean> = preferences.fetchLyrics

    val roomScheme: StateFlow<RoomScheme> = preferences.roomScheme

    fun onFetchCoverArtChanged(enabled: Boolean) = preferences.setFetchCoverArt(enabled)

    fun onFetchLyricsChanged(enabled: Boolean) = preferences.setFetchLyrics(enabled)

    fun onRoomSchemeChanged(scheme: RoomScheme) = preferences.setRoomScheme(scheme)

    /** Which deck. See [com.jawon.app.ui.theme.DeckStyle]. */
    val deckStyle: StateFlow<DeckStyle> = preferences.deckStyle

    fun onDeckStyleChanged(style: DeckStyle) = preferences.setDeckStyle(style)

    val caseSpin: StateFlow<Float> = preferences.caseSpin

    /** Snapped to twentieths, so the readout lands on round numbers and zero is reachable. */
    fun onCaseSpinChanged(fraction: Float) =
        preferences.setCaseSpin((Math.round(fraction * 20f) / 20f))

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

    // ---- Updates -------------------------------------------------------------------

    val updateState: StateFlow<UpdateState> = updateChecker.state

    /** Re-read on every recomposition of the screen: the user may grant it and come back. */
    fun canRequestInstall(): Boolean = updateChecker.canRequestInstall()

    fun onCheckForUpdates() = viewModelScope.launch { updateChecker.check() }

    fun onDownloadUpdate(update: AvailableUpdate) =
        viewModelScope.launch { updateChecker.download(update) }

    // ---- Volume levelling ----------------------------------------------------------

    val replayGainMode: StateFlow<String> = preferences.replayGainMode

    fun onReplayGainModeChanged(mode: com.jawon.app.playback.ReplayGain.Mode) =
        preferences.setReplayGainMode(mode.name)

    // ---- Backup --------------------------------------------------------------------

    /**
     * What the last backup or restore did, for the line under the buttons.
     *
     * Stays until the next one replaces it, and is not persisted — it lives as long as this
     * screen's ViewModel and no longer. There was a `dismiss` for it, which nothing ever
     * called: a result line that clears itself while someone is still reading it is worse
     * than one that sits there until they do something else.
     */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    /** The name offered to the file picker. Dated, because people keep more than one. */
    fun suggestedBackupName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return "jawon-library-$stamp.zip"
    }

    fun onWriteBackup(context: Context, destination: Uri) = viewModelScope.launch {
        _backupBusy.value = true
        _backupMessage.value = runCatching { backup.writeTo(context, destination) }.fold(
            onSuccess = { w ->
                "Saved ${w.albums} ${plural(w.albums, "album")} on " +
                    "${w.shelves} ${plural(w.shelves, "shelf", "shelves")}, " +
                    "${w.tracks} ${plural(w.tracks, "track")} and " +
                    "${w.covers} ${plural(w.covers, "image")}."
            },
            // The message, not the class name. A backup failing is one of the few places a
            // user genuinely needs to know what went wrong.
            onFailure = { "Could not write the backup: ${it.message ?: "unknown error"}" }
        )
        _backupBusy.value = false
    }

    fun onReadBackup(context: Context, source: Uri) = viewModelScope.launch {
        _backupBusy.value = true
        _backupMessage.value = runCatching { backup.readFrom(context, source) }.fold(
            onSuccess = { r ->
                "Restored ${r.albums} ${plural(r.albums, "album")} on " +
                    "${r.shelves} ${plural(r.shelves, "shelf", "shelves")}, " +
                    "${r.tracks} ${plural(r.tracks, "track")}, " +
                    "${r.covers} ${plural(r.covers, "image")} and " +
                    "${r.plays} ${plural(r.plays, "play")} of history."
            },
            onFailure = { "Could not restore: ${it.message ?: "unknown error"}" }
        )
        _backupBusy.value = false
    }

    // ---- Diagnostics ---------------------------------------------------------------

    /**
     * Read on demand rather than observed.
     *
     * The frame loop writes plain fields at up to 120Hz; turning that into a flow would
     * recompose this screen every frame for a readout nobody is watching change.
     */
    fun diagnosticsSnapshot() = diagnostics.snapshot()

    fun onResetDiagnostics() = diagnostics.reset()

    /** What the phone says it is, so a screenshot identifies the hardware it came from. */
    fun deviceLine(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "ANDROID ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

    /** "1 shelf", "2 shelves". A backup of one shelf reporting "1 shelves" reads as a stub. */
    private fun plural(count: Int, one: String, many: String = one + "s") =
        if (count == 1) one else many
}
