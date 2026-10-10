package com.jawon.app.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.jawon.app.BuildConfig
import com.jawon.app.Format
import com.jawon.app.data.remote.ServerInvite
import com.jawon.app.data.remote.SubsonicServer
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

    private val _pocketBacklightMs =
        MutableStateFlow(prefs.getLong(KEY_POCKET_BACKLIGHT_MS, 10_000L))

    /**
     * The pocket player's backlight timer: how long its screen stays lit after the last touch,
     * or 0 for always on. The classic's own choices, and its default of ten seconds.
     */
    val pocketBacklightMs: StateFlow<Long> = _pocketBacklightMs.asStateFlow()

    fun setPocketBacklightMs(ms: Long) {
        prefs.edit { putLong(KEY_POCKET_BACKLIGHT_MS, ms) }
        _pocketBacklightMs.value = ms
    }

    private val _pocketClicker = MutableStateFlow(prefs.getBoolean(KEY_POCKET_CLICKER, true))

    /**
     * Whether the pocket player's wheel clicks. On by default — the click is most of what the
     * wheel was remembered for — and the classic let you turn it off, so this does too. The
     * haptic tick is the phone's, not the clicker's, and stays either way.
     */
    val pocketClicker: StateFlow<Boolean> = _pocketClicker.asStateFlow()

    fun setPocketClicker(on: Boolean) {
        prefs.edit { putBoolean(KEY_POCKET_CLICKER, on) }
        _pocketClicker.value = on
    }

    private val _graphics = MutableStateFlow(prefs.getString(KEY_GRAPHICS, null) ?: "BALANCED")

    /**
     * Settings → Graphics (0.7.5), by name: BEST, BALANCED (the default) or LIGHT — see
     * `com.jawon.app.ui.wallscene.Graphics`, which reads the same key as the rooms are built.
     */
    val graphics: StateFlow<String> = _graphics.asStateFlow()

    fun setGraphics(name: String) {
        // commit, not apply: the activity is recreated straight after so the rooms are rebuilt
        // with the choice, and they read it back from this file as they are built.
        prefs.edit(commit = true) { putString(KEY_GRAPHICS, name) }
        _graphics.value = name
    }

    private val _hintsSeen = MutableStateFlow(prefs.getStringSet(KEY_HINTS_SEEN, emptySet()).orEmpty().toSet())

    /** The rooms whose first-run hint has been seen and put away — see `FirstRunHint` (0.7.4). */
    val hintsSeen: StateFlow<Set<String>> = _hintsSeen.asStateFlow()

    fun markHintSeen(id: String) {
        val next = _hintsSeen.value + id
        prefs.edit { putStringSet(KEY_HINTS_SEEN, next) }
        _hintsSeen.value = next
    }

    private val _pocketMotion = MutableStateFlow(prefs.getBoolean(KEY_POCKET_MOTION, true))

    /**
     * The pocket player's Tilt & Turn (0.7.3): turning the phone sideways for Cover Flow, and
     * leaning it to browse the covers. On by default; the device's own Settings turn it off, for
     * anyone who reads lying on their side.
     */
    val pocketMotion: StateFlow<Boolean> = _pocketMotion.asStateFlow()

    fun setPocketMotion(on: Boolean) {
        prefs.edit { putBoolean(KEY_POCKET_MOTION, on) }
        _pocketMotion.value = on
    }

    private val _pocketBrickBest = MutableStateFlow(prefs.getInt(KEY_POCKET_BRICK_BEST, 0))

    /** Brick's best score on this phone. */
    val pocketBrickBest: StateFlow<Int> = _pocketBrickBest.asStateFlow()

    fun setPocketBrickBest(score: Int) {
        if (score <= _pocketBrickBest.value) return
        prefs.edit { putInt(KEY_POCKET_BRICK_BEST, score) }
        _pocketBrickBest.value = score
    }

    private val _pocketFinish = MutableStateFlow(prefs.getString(KEY_POCKET_FINISH, null))

    /**
     * The pocket player's colour, by name — `PocketFinish` — chosen in its own Settings. Null until
     * one has been chosen, which the room reads as silver. By name, like the room scheme, so a
     * finish added later cannot shift what an old one was stored as.
     */
    val pocketFinish: StateFlow<String?> = _pocketFinish.asStateFlow()

    fun setPocketFinish(name: String) {
        prefs.edit { putString(KEY_POCKET_FINISH, name) }
        _pocketFinish.value = name
    }

    private val _format =
        MutableStateFlow(Format.fromName(prefs.getString(KEY_FORMAT, null)))

    /**
     * Which room the app opens in — the medium the library is handled as. See [Format].
     *
     * Read at the navigation host, not inside a room: leaving a room already destroys the
     * SceneView and returning rebuilds it, so a format chosen in Settings simply takes effect
     * on the way back, with no mid-session teardown of Filament resources.
     */
    val format: StateFlow<Format> = _format.asStateFlow()

    fun setFormat(format: Format) {
        // By name, like the room scheme above, and for the same reason.
        prefs.edit { putString(KEY_FORMAT, format.name) }
        _format.value = format
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

    /**
     * Level matching, and it is **off** by default now.
     *
     * **This was why Jawon sounded quieter and weaker than every other player on the phone.**
     * Nearly all commercial music since the nineties is mastered loud and tagged with a negative
     * ReplayGain — typically −6 to −10 dB. Levelling to the reference therefore does not "even
     * things out" on a modern library, it turns almost everything *down*, by a margin nobody
     * could mistake for subtle. Six decibels is heard as a different, worse master: thinner, less
     * punchy, further away. Put next to Spotify or the stock player the same file sounds cheap,
     * and nothing on screen explains why.
     *
     * The other players that do this by default pair it with a pre-amp so the result lands near
     * where it started. Jawon had the attenuation and no pre-amp, which is the one combination
     * that is strictly worse than not doing it at all.
     *
     * Off is also the honest default for a feature whose whole purpose is comparing records to
     * each other: it matters on shuffle across a mixed library, and this app plays albums.
     * Anyone who wants it can still switch it on, and now they will be choosing it rather than
     * wondering why their music sounds flat.
     */
    private val _replayGainMode =
        MutableStateFlow(prefs.getString(KEY_REPLAY_GAIN, null) ?: "OFF")
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

    /**
     * Shaking the phone flips to the next room (0.7.2, on the owner's call). On by default — it is
     * meant to be found and enjoyed — with this switch for anyone it catches out.
     */
    private val _shakeToSwitch = MutableStateFlow(prefs.getBoolean(KEY_SHAKE_TO_SWITCH, true))
    val shakeToSwitch: StateFlow<Boolean> = _shakeToSwitch.asStateFlow()
    fun setShakeToSwitch(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHAKE_TO_SWITCH, enabled) }
        _shakeToSwitch.value = enabled
    }

    /**
     * Whether closing a case leaves the record playing (0.7.3). On by default: the room is for
     * browsing while you listen, the case sits proud in its slot and the bar at the foot of the
     * wall carries the record. Off is the room's old behaviour — the disc goes back in its case
     * and the music pauses — kept for anyone who liked it; the bar stays either way.
     */
    private val _keepPlayingOnClose = MutableStateFlow(prefs.getBoolean(KEY_KEEP_PLAYING_ON_CLOSE, true))
    val keepPlayingOnClose: StateFlow<Boolean> = _keepPlayingOnClose.asStateFlow()
    fun setKeepPlayingOnClose(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_PLAYING_ON_CLOSE, enabled) }
        _keepPlayingOnClose.value = enabled
    }

    private val _fetchLyrics = MutableStateFlow(prefs.getBoolean(KEY_FETCH_LYRICS, true))

    /**
     * Whether to look a track's words up online when the file does not carry them.
     *
     * On by default, for the same reason as [fetchCoverArt] and with the same limits: the
     * feature is useless switched off, and what leaves the device is one title, one artist, one
     * album and a duration — no identifier, no account, nothing about the rest of the library.
     * Asked only when the lyrics page is actually opened for a track that has none, and the
     * answer is cached so a track is never looked up twice.
     *
     * The source is LRCLIB, which is free, unregistered and MIT-licensed. See
     * [com.jawon.app.data.lyrics.LrclibClient] for why not one of the commercial services.
     */
    val fetchLyrics: StateFlow<Boolean> = _fetchLyrics.asStateFlow()

    fun setFetchLyrics(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FETCH_LYRICS, enabled) }
        _fetchLyrics.value = enabled
    }

    private val _preferOnlineLyrics = MutableStateFlow(prefs.getBoolean(KEY_PREFER_ONLINE_LYRICS, false))

    /**
     * Take the words from LRCLIB even when the file carries its own.
     *
     * **Off by default, and it should stay that way for most people**: the file's own tag is
     * instant, offline and private, and asking the network for something already on the phone
     * is the wrong default for a local-first app.
     *
     * It exists because the default is not right for everyone. A great many files carry an
     * unsynced `USLT` — plain words, no timestamps — and Jawon already prefers a *timed* answer
     * from LRCLIB over an untimed local one for exactly that reason. But when LRCLIB's copy is
     * also untimed the local one keeps the slot, and on a library of loose downloads the tags
     * are often wrong as well as untimed. This says: trust the service, not the file.
     */
    val preferOnlineLyrics: StateFlow<Boolean> = _preferOnlineLyrics.asStateFlow()

    fun setPreferOnlineLyrics(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PREFER_ONLINE_LYRICS, enabled) }
        _preferOnlineLyrics.value = enabled
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

    private val _musicServer = MutableStateFlow(readServer())

    /**
     * The music server this phone streams from, or [SubsonicServer.NONE] when there is none.
     *
     * **The password is stored in plain `SharedPreferences`**, in the app's private directory,
     * and that deserves saying out loud rather than being left for someone to discover. On a
     * non-rooted phone no other app can read it. On a rooted one it is readable, and so is
     * anything else here. The alternatives were weighed and rejected: `EncryptedSharedPreferences`
     * is deprecated and its replacement wants a keystore-backed key that a factory reset or a
     * restore invalidates — which turns "your library vanished" into a support problem — and the
     * Subsonic protocol needs the password itself on every request, so it cannot be stored as a
     * one-way hash the way a login secret could. This is the same trade every Subsonic client
     * on the platform makes, and the privacy policy names it.
     */
    val musicServer: StateFlow<SubsonicServer> = _musicServer.asStateFlow()

    fun setMusicServer(server: SubsonicServer) {
        prefs.edit {
            putString(KEY_SERVER_URL, server.url)
            putString(KEY_SERVER_USER, server.username)
            putString(KEY_SERVER_PASSWORD, server.password)
            // Records that the choice is now the user's, which is what makes FORGET stick on a
            // build that ships a default server — see [readServer].
            putBoolean(KEY_SERVER_CHOSEN, true)
        }
        _musicServer.value = server
    }

    /**
     * The server to use: whatever the user set, else whatever this build shipped with.
     *
     * **The flag is what makes FORGET mean something.** On a build carrying a default invite,
     * falling back to it whenever the stored fields are blank would restore the server the
     * instant someone removed it — a button that visibly does nothing. So the first time the
     * user touches the setting at all, their answer wins permanently, including when their
     * answer is "none".
     *
     * A malformed invite is treated as no invite rather than as an error. It reaches this point
     * only from a build flag, so the person who can fix it is the person who cut the build, and
     * failing to launch would be a strange way to tell them.
     */
    private fun readServer(): SubsonicServer {
        val chosen = prefs.getBoolean(KEY_SERVER_CHOSEN, false)
        if (!chosen) {
            ServerInvite.decode(BuildConfig.DEFAULT_SERVER_INVITE)?.let { return it }
        }
        return SubsonicServer(
            url = prefs.getString(KEY_SERVER_URL, null).orEmpty(),
            username = prefs.getString(KEY_SERVER_USER, null).orEmpty(),
            password = prefs.getString(KEY_SERVER_PASSWORD, null).orEmpty()
        )
    }

    /**
     * `true` when this build shipped pointed at a server.
     *
     * The settings screen uses it to explain where a library nobody configured came from —
     * otherwise the first run of a shared build looks like the app inventing music from
     * nowhere.
     */
    val hasBundledServer: Boolean
        get() = ServerInvite.decode(BuildConfig.DEFAULT_SERVER_INVITE) != null

    /**
     * Erases what the removed Spotify route kept here: its Client ID and its sign-in tokens.
     *
     * A refresh token lives until it is revoked, and nothing that could read or clear one is left
     * in the app — so without this it would stay on every phone that ever signed in, a credential
     * kept for nobody. Called at launch by `SpotifyCleanup`, and free when there is nothing here.
     */
    fun forgetSpotify() {
        if (RETIRED_SPOTIFY_KEYS.none { prefs.contains(it) }) return
        prefs.edit { RETIRED_SPOTIFY_KEYS.forEach { remove(it) } }
    }

    private val _streamCacheMb = MutableStateFlow(prefs.getInt(KEY_STREAM_CACHE_MB, DEFAULT_CACHE_MB))

    /**
     * How much of the phone this app may spend holding streamed audio, in megabytes.
     *
     * **This is not a nicety, it is what makes the disc work.** Turning the platter issues a
     * seek per gesture, and against a bare network source every one of those is a fresh range
     * request over the wire — the app's most tactile interaction turned into its laggiest. With
     * a cache in front, a track that has been played once is scrubbed from local disk at local
     * disk speed. See `StreamCache`.
     *
     * 512 MB by default: enough for roughly a day of listening to stay instant, small enough
     * that a phone which is short of space — which is the whole reason to stream — is not
     * quietly filled back up. The user who bought a 1 TB phone can raise it.
     */
    val streamCacheMb: StateFlow<Int> = _streamCacheMb.asStateFlow()

    fun setStreamCacheMb(mb: Int) {
        val clamped = mb.coerceIn(MIN_CACHE_MB, MAX_CACHE_MB)
        prefs.edit { putInt(KEY_STREAM_CACHE_MB, clamped) }
        _streamCacheMb.value = clamped
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SERVER_USER = "server_user"
        const val KEY_SERVER_PASSWORD = "server_password"
        const val KEY_SERVER_CHOSEN = "server_chosen"
        const val KEY_STREAM_CACHE_MB = "stream_cache_mb"

        /** Written by the Spotify route, and kept only so [forgetSpotify] can erase them. */
        val RETIRED_SPOTIFY_KEYS = listOf(
            "spotify_client_id",
            "spotify_access_token",
            "spotify_refresh_token",
            "spotify_expires_at"
        )

        const val DEFAULT_CACHE_MB = 512

        /**
         * Below about this, the cache stops being able to hold even one album and starts
         * evicting the track that is currently playing — which is worse than no cache at all,
         * because it re-downloads what it just threw away.
         */
        const val MIN_CACHE_MB = 128
        const val MAX_CACHE_MB = 16_384

        const val KEY_TRACK_ARTIST_BACKFILL = "track_artist_backfill_done"
        const val KEY_BASS = "effect_bass"
        const val KEY_WIDTH = "effect_width"
        const val KEY_REVERB = "effect_reverb"
        const val KEY_LOUDNESS = "effect_loudness"
        const val KEY_CASE_SPIN = "case_spin"
        const val KEY_CROSSFADE_MS = "crossfade_ms"
        const val KEY_FETCH_COVER_ART = "fetch_cover_art"
        const val KEY_FETCH_LYRICS = "fetch_lyrics"
        const val KEY_SHAKE_TO_SWITCH = "shake_to_switch"
        const val KEY_KEEP_PLAYING_ON_CLOSE = "keep_playing_on_close"
        const val KEY_PREFER_ONLINE_LYRICS = "prefer_online_lyrics"
        const val KEY_ROOM_SCHEME = "room_scheme"
        const val KEY_FORMAT = "format"
        const val KEY_POCKET_BACKLIGHT_MS = "pocket_backlight_ms"
        const val KEY_POCKET_CLICKER = "pocket_clicker"
        const val KEY_POCKET_MOTION = "pocket_motion"
        const val KEY_HINTS_SEEN = "hints_seen"
        const val KEY_GRAPHICS = "graphics"
        const val KEY_POCKET_BRICK_BEST = "pocket_brick_best"
        const val KEY_POCKET_FINISH = "pocket_finish"
        const val KEY_EQ_ENABLED = "equalizer_enabled"
        const val KEY_EQ_BANDS = "equalizer_bands"
        const val KEY_REPLAY_GAIN = "replay_gain_mode"
        const val KEY_WALL_SORT = "wall_sort"
    }
}
