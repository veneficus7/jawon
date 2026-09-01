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

    /**
     * Which ReplayGain tags to level by, as a [com.jawon.app.playback.ReplayGain.Mode] name.
     *
     * Defaults to album gain rather than off. Levelling is what the tags were written for,
     * and a collection that has them is a collection whose owner already decided they wanted
     * it — see the Mode docs for why album and not track.
     */
    /** How records are filed along a shelf, as a [com.jawon.app.ui.wallscene.WallSort] name. */
    private val _wallSort = MutableStateFlow(prefs.getString(KEY_WALL_SORT, null) ?: "SHELF")
    val wallSort: StateFlow<String> = _wallSort.asStateFlow()

    fun setWallSort(name: String) {
        prefs.edit { putString(KEY_WALL_SORT, name) }
        _wallSort.value = name
    }

    private val _replayGainMode =
        MutableStateFlow(prefs.getString(KEY_REPLAY_GAIN, null) ?: "ALBUM")
    val replayGainMode: StateFlow<String> = _replayGainMode.asStateFlow()

    fun setReplayGainMode(name: String) {
        prefs.edit { putString(KEY_REPLAY_GAIN, name) }
        _replayGainMode.value = name
    }

    private val _caseSpin = MutableStateFlow(prefs.getFloat(KEY_CASE_SPIN, 1f))

    /**
     * How fast shelved cases turn to present themselves, as a fraction of full speed.
     *
     * `1f` is the speed the room was designed and tuned at, and the default — it is most of
     * what makes the wall feel like a room rather than a grid. `0f` stops them dead, square
     * on and cover forward, for anyone who finds constant motion at the edge of vision
     * distracting rather than alive. That is a real thing to find and not something to argue
     * with, and the values between let it be slowed rather than only switched off.
     *
     * The gentle bob and lean stay at every setting including zero: that is the room
     * breathing, and it is the *turning* that catches the eye from across the screen.
     */
    val caseSpin: StateFlow<Float> = _caseSpin.asStateFlow()

    fun setCaseSpin(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        prefs.edit { putFloat(KEY_CASE_SPIN, clamped) }
        _caseSpin.value = clamped
    }

    // ---- the platform effects. See [com.jawon.app.data.playback.NativeEffects] -----------
    //
    // Persisted, like the equaliser's bands, because these are a preference rather than a
    // performance: someone who likes a little bass boost likes it on the next record too. The
    // rack's own controls — filter, drive, echo, speed, pitch — deliberately are *not* saved,
    // because those are gestures and a track starting up already filtered would be a fault.

    private val _bassStrength = MutableStateFlow(prefs.getFloat(KEY_BASS, 0f))
    val bassStrength: StateFlow<Float> = _bassStrength.asStateFlow()

    fun setBassStrength(value: Float) {
        prefs.edit { putFloat(KEY_BASS, value) }
        _bassStrength.value = value
    }

    private val _stereoWidth = MutableStateFlow(prefs.getFloat(KEY_WIDTH, 0f))
    val stereoWidth: StateFlow<Float> = _stereoWidth.asStateFlow()

    fun setStereoWidth(value: Float) {
        prefs.edit { putFloat(KEY_WIDTH, value) }
        _stereoWidth.value = value
    }

    private val _reverbPreset = MutableStateFlow(prefs.getInt(KEY_REVERB, 0))
    val reverbPreset: StateFlow<Int> = _reverbPreset.asStateFlow()

    fun setReverbPreset(value: Int) {
        prefs.edit { putInt(KEY_REVERB, value) }
        _reverbPreset.value = value
    }

    private val _loudnessGain = MutableStateFlow(prefs.getFloat(KEY_LOUDNESS, 0f))
    val loudnessGain: StateFlow<Float> = _loudnessGain.asStateFlow()

    fun setLoudnessGain(value: Float) {
        prefs.edit { putFloat(KEY_LOUDNESS, value) }
        _loudnessGain.value = value
    }

    /**
     * Whether the one-time pass that reads per-track artists out of existing files has run.
     *
     * Not a StateFlow: nothing in the UI reacts to it, and it is read once per launch on a
     * background path. See `TrackArtistBackfill`.
     */
    var trackArtistBackfillDone: Boolean
        get() = prefs.getBoolean(KEY_TRACK_ARTIST_BACKFILL, false)
        set(value) = prefs.edit { putBoolean(KEY_TRACK_ARTIST_BACKFILL, value) }

    private companion object {
        const val KEY_TRACK_ARTIST_BACKFILL = "track_artist_backfill_done"
        const val KEY_BASS = "effect_bass"
        const val KEY_WIDTH = "effect_width"
        const val KEY_REVERB = "effect_reverb"
        const val KEY_LOUDNESS = "effect_loudness"
        const val KEY_CASE_SPIN = "case_spin"
        const val KEY_CROSSFADE_MS = "crossfade_ms"
        const val KEY_FETCH_COVER_ART = "fetch_cover_art"
        const val KEY_ROOM_SCHEME = "room_scheme"
        const val KEY_EQ_ENABLED = "equalizer_enabled"
        const val KEY_EQ_BANDS = "equalizer_bands"
        const val KEY_REPLAY_GAIN = "replay_gain_mode"
        const val KEY_WALL_SORT = "wall_sort"
    }
}
