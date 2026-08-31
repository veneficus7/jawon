package com.jawon.app.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The handful of settings that are genuinely user preferences rather than library data.
 *
 * `SharedPreferences` rather than DataStore: there are two booleans here, they are read on a
 * background path, and adding a dependency and a serializer for that would be ceremony. If
 * this grows past a few flags it should move.
 */
@Singleton
class AppPreferences @Inject constructor(@param:ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("jawon", Context.MODE_PRIVATE)

    private val _fetchCoverArt = MutableStateFlow(prefs.getBoolean(KEY_FETCH_COVER_ART, true))

    /**
     * Whether to look an album's cover up online when its own files do not carry one.
     *
     * **On by default, and this is the one default in the app worth arguing about.** Jawon is
     * local-first, and this is the only thing it does that leaves the device — so the case for
     * defaulting it off is real. It is on because the alternative is worse in practice: a user
     * who imports their library and gets a wall of blank cases has been failed by a feature
     * that exists and was switched off for them, and the request that prompted it was
     * explicitly "take album cover arts from some database".
     *
     * What makes that defensible is that it is never hidden. The import screen says it is
     * going to happen before it happens, the switch is in Settings, and what gets sent is one
     * album title and one artist name for records that have no art — no library listing, no
     * device id, no account. See [com.jawon.app.data.importer.CoverArtFetcher].
     */
    val fetchCoverArt: StateFlow<Boolean> = _fetchCoverArt.asStateFlow()

    fun setFetchCoverArt(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FETCH_COVER_ART, enabled) }
        _fetchCoverArt.value = enabled
    }

    private val _roomScheme =
        MutableStateFlow(RoomScheme.fromName(prefs.getString(KEY_ROOM_SCHEME, null)))

    /** What colour the room is lit in. See [RoomScheme]. */
    val roomScheme: StateFlow<RoomScheme> = _roomScheme.asStateFlow()

    fun setRoomScheme(scheme: RoomScheme) {
        // Stored by name, not by ordinal: reordering the enum would otherwise silently move
        // everyone to a different scheme.
        prefs.edit { putString(KEY_ROOM_SCHEME, scheme.name) }
        _roomScheme.value = scheme
    }

    private val _equalizerEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_EQ_ENABLED, false))

    /** Off by default: an equaliser nobody asked for should not colour the sound. */
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    fun setEqualizerEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_EQ_ENABLED, enabled) }
        _equalizerEnabled.value = enabled
    }

    private val _equalizerBands = MutableStateFlow(readBands())

    /**
     * Band gains in millibels, in band order.
     *
     * Stored as text rather than as a set of numbered keys because the band *count* is the
     * device's to decide — a phone with five bands and one with ten both round-trip through
     * one string, and a list that changes length does not leave stale keys behind.
     */
    val equalizerBands: StateFlow<List<Int>> = _equalizerBands.asStateFlow()

    fun setEqualizerBands(levels: List<Int>) {
        prefs.edit { putString(KEY_EQ_BANDS, levels.joinToString(",")) }
        _equalizerBands.value = levels
    }

    private fun readBands(): List<Int> =
        prefs.getString(KEY_EQ_BANDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()

    private val _crossfadeMs = MutableStateFlow(prefs.getInt(KEY_CROSSFADE_MS, 0))

    /**
     * How long one track overlaps the next, in milliseconds. `0` is off.
     *
     * Off by default, and not only out of caution. An album is the atomic unit here, and
     * plenty of records are sequenced to run straight from one track into the next — a
     * crossfade applied to those actively destroys something the artist did on purpose. It is
     * a thing to reach for, not a thing to have on.
     */
    val crossfadeMs: StateFlow<Int> = _crossfadeMs.asStateFlow()

    fun setCrossfadeMs(ms: Int) {
        prefs.edit { putInt(KEY_CROSSFADE_MS, ms) }
        _crossfadeMs.value = ms
    }

    private val _casesTurn = MutableStateFlow(prefs.getBoolean(KEY_CASES_TURN, true))

    /**
     * Whether shelved cases slowly turn to present themselves.
     *
     * On by default — it is most of what makes the wall feel like a room rather than a grid,
     * and `ShelfHover` exists to do it. Off for anyone who finds constant motion in their
     * peripheral vision distracting rather than alive, which is a real thing to find and not
     * something to argue with. The gentle bob and tilt stay either way; it is the *turning*
     * that draws the eye.
     */
    val casesTurn: StateFlow<Boolean> = _casesTurn.asStateFlow()

    fun setCasesTurn(on: Boolean) {
        prefs.edit { putBoolean(KEY_CASES_TURN, on) }
        _casesTurn.value = on
    }

    private companion object {
        const val KEY_CASES_TURN = "cases_turn"
        const val KEY_CROSSFADE_MS = "crossfade_ms"
        const val KEY_FETCH_COVER_ART = "fetch_cover_art"
        const val KEY_ROOM_SCHEME = "room_scheme"
        const val KEY_EQ_ENABLED = "equalizer_enabled"
        const val KEY_EQ_BANDS = "equalizer_bands"
    }
}
