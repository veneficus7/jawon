package com.jawon.app.data.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log
import com.jawon.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The effects Android has had all along, which Jawon never asked for.
 *
 * Until now exactly one platform effect was wired — the equaliser — while bass boost, stereo
 * width, reverb and loudness sat in `android.media.audiofx` unused. They attach to the same
 * audio session the equaliser does, so this is almost entirely plumbing: no DSP, no processor
 * in the pipeline, no cost when they are off.
 *
 * **Availability is discovered, never assumed.** Vendor support for these varies by phone, and
 * an effect that is missing does not announce itself politely — the constructor throws, or
 * succeeds and then refuses the one setting you wanted. So each is *constructed* to find out,
 * and anything that fails is simply absent from the UI rather than present and inert. That is
 * the same mistake the fader made, and it is worth not repeating: a control that does nothing
 * is worse than no control.
 *
 * Lives beside [EqualizerController] and for the same reason — the effects belong to a session
 * that any given ExoPlayer owns, while the settings belong to the app.
 */
@Singleton
class NativeEffects @Inject constructor(
    private val preferences: AppPreferences
) {

    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var reverb: PresetReverb? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    /**
     * Whether these two accept a *degree* of themselves, or only on and off.
     *
     * Vendor implementations differ, and the difference is not a failure — see [attach]. Where
     * the answer is no, the fader still switches the effect in and out; it simply cannot ask
     * for half of it.
     */
    private var bassTakesStrength = false
    private var widthTakesStrength = false

    /** Which of the four this phone actually gave us. Drives what the rack draws. */
    private val _available = MutableStateFlow(emptySet<NativeEffect>())
    val available: StateFlow<Set<NativeEffect>> = _available.asStateFlow()

    /**
     * The reverb tank's id, and how much of the player to send into it.
     *
     * Read by `PlaybackService`, which is the only thing holding an ExoPlayer and therefore the
     * only thing that can route to an auxiliary effect. Zero means "no reverb" — that is
     * Media3's own sentinel for an absent aux effect, so the off case needs no special handling
     * at the far end.
     */
    private val _auxEffectId = MutableStateFlow(0)
    val auxEffectId: StateFlow<Int> = _auxEffectId.asStateFlow()

    private val _auxSendLevel = MutableStateFlow(0f)
    val auxSendLevel: StateFlow<Float> = _auxSendLevel.asStateFlow()

    val bassStrength: StateFlow<Float> = preferences.bassStrength
    val stereoWidth: StateFlow<Float> = preferences.stereoWidth
    val reverbPreset: StateFlow<Int> = preferences.reverbPreset
    val loudnessGain: StateFlow<Float> = preferences.loudnessGain

    /**
     * Bind to a player's audio session.
     *
     * Rebinding to the same session is a no-op; a different one releases first, because an
     * effect left attached to a dead session holds a slot the new one may need.
     */
    fun attach(newSessionId: Int) {
        if (newSessionId == 0) return
        if (sessionId == newSessionId && _available.value.isNotEmpty()) return
        release()
        sessionId = newSessionId

        val found = mutableSetOf<NativeEffect>()

        // **An effect that exists but will not take a strength is still an effect.**
        //
        // These two used to be dropped entirely unless `strengthSupported` was true, on the
        // reasoning that a slider which does nothing is a lie. The reasoning was right and the
        // conclusion was wrong: what a vendor is saying with `strengthSupported = false` is
        // "I do one amount, take it or leave it", and throwing the whole effect away over that
        // costs the user bass boost to avoid mislabelling a fader. It also cost them the TONE
        // key outright — dropping BASS *and* WIDTH takes the cell with it, which is what a
        // person sees as a button that does nothing at all.
        //
        // So: constructed means available, and [bassTakesStrength] / [widthTakesStrength]
        // decide whether the fader's position is passed on or only its on-ness. The fader is
        // then honest in the only way that matters — it does change the sound.
        //
        // The old form also leaked. `?.takeIf { … }` returning null dropped the constructed
        // effect on the floor without releasing it, holding a scarce session slot until the
        // process died.
        bass = build("bass boost") { BassBoost(EFFECT_PRIORITY, newSessionId) }?.also { effect ->
            bassTakesStrength = runCatching { effect.strengthSupported }.getOrDefault(false)
            found += NativeEffect.BASS
        }

        virtualizer = build("virtualizer") { Virtualizer(EFFECT_PRIORITY, newSessionId) }
            ?.also { effect ->
                widthTakesStrength = runCatching { effect.strengthSupported }.getOrDefault(false)
                found += NativeEffect.WIDTH
            }

        // Reverb is the odd one out, and the device said so on the first run: attaching it to a
        // session like the others fails outright — *"Cannot initialize effect engine ...
        // Error: -3"*. Reverb is an **auxiliary** effect. It is not an insert in one stream's
        // chain; it is a shared tank that streams are routed *to*, so it is created against the
        // output mix (session 0, which is what MODIFY_AUDIO_SETTINGS is declared for) and the
        // player is then pointed at it by id. See [auxEffectId].
        reverb = build("reverb") { PresetReverb(EFFECT_PRIORITY, AUX_SESSION) }
            ?.also { found += NativeEffect.REVERB }
        _auxEffectId.value = runCatching { reverb?.id ?: 0 }.getOrDefault(0)

        loudness = build("loudness") { LoudnessEnhancer(newSessionId) }
            ?.also { found += NativeEffect.LOUDNESS }

        _available.value = found
        applyAll()
    }

    fun setBassStrength(value: Float) {
        preferences.setBassStrength(value.coerceIn(0f, 1f))
        applyBass()
    }

    fun setStereoWidth(value: Float) {
        preferences.setStereoWidth(value.coerceIn(0f, 1f))
        applyWidth()
    }

    // ---- the rack's bypass ------------------------------------------------------------
    //
    // Two of the rack's eight cells land here: TONE is the bass and width pair, SPACE is the
    // reverb room and the loudness. A cell that is switched out has to go silent while its
    // faders stay where the user left them, so the stored values are untouched and only the
    // apply functions consult these flags. A bypass therefore reads as a zero strength — which
    // already means "released of duty" below, so switching a cell out also stops it costing
    // anything.
    //
    // Held here rather than read from RackState so this class keeps no dependency on the rack —
    // the caller that flips the cell tells us, in the same breath.

    @Volatile
    private var toneEngaged = true

    @Volatile
    private var spaceEngaged = true

    /** Bass and stereo width, as one cell. */
    fun setToneEngaged(engaged: Boolean) {
        if (toneEngaged == engaged) return
        toneEngaged = engaged
        applyBass()
        applyWidth()
    }

    /** The reverb room and the loudness, as one cell. */
    fun setSpaceEngaged(engaged: Boolean) {
        if (spaceEngaged == engaged) return
        spaceEngaged = engaged
        applyReverb()
        applyLoudness()
    }

    fun setReverbPreset(preset: Int) {
        preferences.setReverbPreset(preset.coerceIn(0, REVERB_PRESETS.lastIndex))
        applyReverb()
    }

    fun setLoudnessGain(value: Float) {
        preferences.setLoudnessGain(value.coerceIn(0f, 1f))
        applyLoudness()
    }

    private fun applyAll() {
        applyBass(); applyWidth(); applyReverb(); applyLoudness()
    }

    private fun applyBass() {
        val effect = bass ?: return
        val value = if (toneEngaged) preferences.bassStrength.value else 0f
        runCatching {
            // Off means *released of duty*, not zero strength: an enabled effect with a zero
            // setting still sits in the chain doing arithmetic on every sample.
            effect.enabled = value > OFF
            if (value > OFF && bassTakesStrength) {
                effect.setStrength((value * MAX_STRENGTH).toInt().toShort())
            }
        }.onFailure { Log.w(TAG, "bass boost refused a value", it) }
    }

    private fun applyWidth() {
        val effect = virtualizer ?: return
        val value = if (toneEngaged) preferences.stereoWidth.value else 0f
        runCatching {
            effect.enabled = value > OFF
            if (value > OFF && widthTakesStrength) {
                effect.setStrength((value * MAX_STRENGTH).toInt().toShort())
            }
        }.onFailure { Log.w(TAG, "virtualizer refused a value", it) }
    }

    private fun applyReverb() {
        val effect = reverb ?: return
        val preset = if (spaceEngaged) preferences.reverbPreset.value else 0
        runCatching {
            effect.enabled = preset > 0
            effect.preset = preset.toShort()
        }.onFailure { Log.w(TAG, "reverb refused a preset", it) }
        // The preset picks the room; the send level decides how much of the record goes into it.
        // Off is a send of zero rather than a disabled effect, because the tank is shared and
        // the player is what stops feeding it.
        _auxSendLevel.value = if (preset > 0) REVERB_SEND else 0f
    }

    private fun applyLoudness() {
        val effect = loudness ?: return
        val value = if (spaceEngaged) preferences.loudnessGain.value else 0f
        runCatching {
            effect.enabled = value > OFF
            // Millibels. Deliberately capped well below what the effect will accept: this is
            // makeup gain on an already-mastered signal, and past a few dB it stops being
            // loudness and starts being clipping.
            if (value > OFF) effect.setTargetGain((value * MAX_LOUDNESS_MB).toInt())
        }.onFailure { Log.w(TAG, "loudness enhancer refused a gain", it) }
    }

    fun release() {
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        runCatching { reverb?.release() }
        runCatching { loudness?.release() }
        bass = null
        virtualizer = null
        reverb = null
        loudness = null
        bassTakesStrength = false
        widthTakesStrength = false
        sessionId = 0
        _available.value = emptySet()
        // Zero is Media3's sentinel for "no aux effect", so the player stops routing to a tank
        // that no longer exists rather than holding a dangling id.
        _auxEffectId.value = 0
        _auxSendLevel.value = 0f
    }

    /**
     * Construct one effect, or find out it is not there.
     *
     * Constructing is the honest test. `AudioEffect.queryEffects()` lists what the platform has
     * an implementation for, which is not the same question as whether this session can have
     * one right now — another app may hold it, or the vendor may ship a stub that throws.
     */
    private fun <T> build(name: String, create: () -> T): T? =
        runCatching { create() }
            .onFailure { Log.i(TAG, "no $name on this device", it) }
            .getOrNull()

    companion object {
        /** Presets `PresetReverb` defines, in its own order. Index 0 is off. */
        val REVERB_PRESETS = listOf(
            "OFF", "SMALL ROOM", "MEDIUM ROOM", "LARGE ROOM",
            "MEDIUM HALL", "LARGE HALL", "PLATE"
        )

        private const val TAG = "JawonEffects"
        private const val EFFECT_PRIORITY = 0

        /** Session 0 is the output mix, which is where an auxiliary effect lives. */
        private const val AUX_SESSION = 0

        /**
         * How much of the record is sent into the reverb when it is on.
         *
         * Not 1: an auxiliary send is *added* to the dry signal rather than replacing it, so a
         * full send is a record with as much reverb as music. Two thirds leaves the room
         * audible behind the track rather than in front of it.
         */
        private const val REVERB_SEND = 0.66f

        private const val MAX_STRENGTH = 1000f
        private const val MAX_LOUDNESS_MB = 1200f
        private const val OFF = 0.001f
    }
}

/** The four this class can offer, when the phone has them. */
enum class NativeEffect { BASS, WIDTH, REVERB, LOUDNESS }
