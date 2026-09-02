package com.jawon.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the effects rack's controls live, between the finger turning them and the audio thread
 * reading them.
 *
 * **App-scoped, and that is the whole point.** The rack outlives the deck: closing a case must
 * not silently undo a filter sweep, and the service that owns the player is a different object
 * from the composable that draws the knob. This is the one place both can see.
 *
 * Two views of each value on purpose. The [StateFlow] is for Compose, which wants to recompose
 * when it changes. The plain field is for the audio thread, which must never suspend, never
 * allocate and never wait on a lock — it reads the latest value and moves on. `@Volatile` is
 * exactly the right amount of synchronisation for that: the audio thread is guaranteed to see a
 * recent value, and a control that arrives one buffer late is inaudible.
 */
@Singleton
class RackState @Inject constructor() {

    private val _filter = MutableStateFlow(CENTRE)

    /** The filter knob, −1 (low-pass, closed) … 0 (off) … +1 (high-pass, closed). */
    val filter: StateFlow<Float> = _filter.asStateFlow()

    @Volatile
    var filterPosition: Float = CENTRE
        private set

    fun setFilter(position: Float) {
        val clamped = position.coerceIn(-1f, 1f)
        filterPosition = clamped
        _filter.value = clamped
    }

    // ---- tempo and key. See [Varispeed] -----------------------------------------------
    //
    // No `@Volatile` twin for these two. Everything else in this class is read by the audio
    // thread every buffer; these are handed to the player once when they change, so a flow is
    // the whole story.

    private val _speed = MutableStateFlow(CENTRE)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /**
     * Where the pitch control is, for the readout — updated continuously as it is dragged.
     *
     * **Deliberately not what reaches the player.** See [pitchApplied].
     */
    private val _pitch = MutableStateFlow(CENTRE)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    /**
     * The pitch the player is actually told about, which only changes when the finger lifts.
     *
     * **This split is a fix, not a nicety.** Every time the pitch passes through neutral,
     * Media3's time-stretcher is added to or removed from the audio pipeline — a full
     * reconfiguration rather than a change of ratio — and a dragging finger crosses centre
     * repeatedly. That left the playback thread spinning at a whole core with no progress at
     * all, until the player's own watchdog declared it stuck; the phone got hot enough to
     * throttle and the deck sat at 0:00. Throttling the updates did not help, because the
     * problem is the crossing rather than the rate.
     *
     * A settled pitch is cheap at any amount — an octave down measured barely above idle. So
     * the player is told once, when the gesture ends, and never sees the sweep in between.
     *
     * Speed does not need this. It never reproduced the fault, and being able to slew it while
     * listening is most of what it is for.
     */
    private val _pitchApplied = MutableStateFlow(CENTRE)
    val pitchApplied: StateFlow<Float> = _pitchApplied.asStateFlow()

    fun setSpeed(position: Float) {
        _speed.value = position.coerceIn(-1f, 1f)
    }

    fun setPitch(position: Float) {
        _pitch.value = position.coerceIn(-1f, 1f)
    }

    /** The finger has come off the pitch control: hand the settled value to the player. */
    fun commitPitch() {
        _pitchApplied.value = _pitch.value
    }

    // ---- the drive. See [Drive] -------------------------------------------------------

    private val _drive = MutableStateFlow(0f)

    /** Saturation, 0 clean … 1 broken. Rests at zero: a clean player is the honest default. */
    val drive: StateFlow<Float> = _drive.asStateFlow()

    @Volatile
    var drivePosition: Float = 0f
        private set

    fun setDrive(position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        drivePosition = clamped
        _drive.value = clamped
    }

    // ---- the echo. See [Echo] ---------------------------------------------------------
    //
    // Three knobs, each 0..1, because that is what a delay you *play* needs: how long the
    // repeat is, how many there are, and how much of it you hear. Mix rests at zero — an
    // effect you throw in has to start out of the way.

    private val _echoTime = MutableStateFlow(DEFAULT_ECHO_TIME)
    val echoTime: StateFlow<Float> = _echoTime.asStateFlow()

    private val _echoFeedback = MutableStateFlow(DEFAULT_ECHO_FEEDBACK)
    val echoFeedback: StateFlow<Float> = _echoFeedback.asStateFlow()

    private val _echoMix = MutableStateFlow(0f)
    val echoMix: StateFlow<Float> = _echoMix.asStateFlow()

    @Volatile
    var echoTimePosition: Float = DEFAULT_ECHO_TIME
        private set

    @Volatile
    var echoFeedbackPosition: Float = DEFAULT_ECHO_FEEDBACK
        private set

    @Volatile
    var echoMixPosition: Float = 0f
        private set

    fun setEchoTime(position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        echoTimePosition = clamped
        _echoTime.value = clamped
    }

    fun setEchoFeedback(position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        echoFeedbackPosition = clamped
        _echoFeedback.value = clamped
    }

    fun setEchoMix(position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        echoMixPosition = clamped
        _echoMix.value = clamped
    }

    companion object {
        /** Off. A filter knob rests in the middle, unlike a fader, which rests open. */
        const val CENTRE = 0f

        /** About a third of a second — a slap that lands musically under most records. */
        const val DEFAULT_ECHO_TIME = 0.55f

        /** Enough repeats to hear a tail, well short of the ceiling. */
        const val DEFAULT_ECHO_FEEDBACK = 0.45f
    }
}
