package com.jawon.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The eight effects the rack can put in or take out — one per cell of its grid.
 *
 * **Effects, not parameters.** An earlier version of this listed six *controls*, which meant the
 * echo appeared three times and could be bypassed in thirds. A cell is a whole effect: tapping
 * it switches the thing in or out, and its parameters — one, two or three of them — appear on
 * the faders below when it is selected. That is what makes eight fit where six controls did.
 *
 * [EQ] is the odd one and always will be: five bands cannot ride three faders, so its cell
 * hands the row over to a five-band view instead. It is in this list because it is still
 * something you switch in and out.
 */
enum class RackChannel {
    FILTER, SPEED, PITCH, DRIVE, ECHO, SPACE, TONE, EQ;

    /** What the cell is called on the face. Kept short — a cell is about 74dp across. */
    val label: String
        get() = when (this) {
            FILTER -> "FILT"
            SPEED -> "SPD"
            PITCH -> "PTCH"
            DRIVE -> "DRV"
            ECHO -> "ECHO"
            SPACE -> "SPACE"
            TONE -> "TONE"
            EQ -> "EQ"
        }
}

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
 *
 * ## Target and effective
 *
 * Every channel now holds two numbers rather than one: where its fader is sitting, and what the
 * audio is actually being given. They differ when the channel is bypassed, and keeping them
 * apart is the entire reason the face works — a bypassed echo stays visibly set to the mix you
 * chose, so tapping it back in returns *exactly* there rather than making you find it again by
 * hand. Dragging a fader up from zero is not a musical gesture; throwing a switch is.
 *
 * The flows named after each control carry the **target**, because that is what the fader draws.
 * The `@Volatile` fields carry the **effective** value, because that is what the audio wants.
 */
@Singleton
class RackState @Inject constructor() {

    // ---- what is in and what is out ---------------------------------------------------

    private val _engaged = MutableStateFlow(RackChannel.values().toSet())

    /**
     * Which channels are currently in circuit.
     *
     * Everything starts engaged, which changes nothing about how the rack behaves out of the
     * box: every fader already rests at a neutral value, so an engaged channel doing nothing
     * and a bypassed one sound identical until you move something.
     *
     * [RackChannel.BASS] and [RackChannel.WIDTH] are in this set even though their values live
     * over in `NativeEffects` rather than here. That is deliberate: "what is in circuit" is one
     * question with one answer, and splitting it across two owners so each could keep its own
     * flags would mean the face had two places to ask.
     */
    val engaged: StateFlow<Set<RackChannel>> = _engaged.asStateFlow()

    fun isEngaged(channel: RackChannel): Boolean = channel in _engaged.value

    /** Put a channel in or take it out, keeping wherever its fader was set. */
    fun setEngaged(channel: RackChannel, engaged: Boolean) {
        val next = if (engaged) _engaged.value + channel else _engaged.value - channel
        if (next == _engaged.value) return
        _engaged.value = next
        republish()
    }

    fun toggleEngaged(channel: RackChannel) = setEngaged(channel, channel !in _engaged.value)

    /**
     * Push every target back through the bypass, after the engaged set has changed.
     *
     * Cheap, and called only on a tap. Recomputing all of them rather than just the one that
     * changed costs nothing and removes a class of bug where a channel is left holding a stale
     * effective value — which is the same mistake the discard animation once made with a pooled
     * node's scale, and it took an invisible album to find.
     */
    private fun republish() {
        filterPosition = through(RackChannel.FILTER, _filter.value, CENTRE)
        drivePosition = through(RackChannel.DRIVE, _drive.value, 0f)
        echoMixPosition = through(RackChannel.ECHO, _echoMix.value, 0f)
        _speedApplied.value = through(RackChannel.SPEED, _speed.value, CENTRE)
        _pitchApplied.value = through(RackChannel.PITCH, _pitchTarget, CENTRE)
    }

    /** The value the audio should get: the target when the channel is in, neutral when it is out. */
    private fun through(channel: RackChannel, target: Float, neutral: Float): Float =
        if (channel in _engaged.value) target else neutral

    // ---- the filter. See [DjFilter] ---------------------------------------------------

    private val _filter = MutableStateFlow(CENTRE)

    /** The filter knob, −1 (low-pass, closed) … 0 (off) … +1 (high-pass, closed). */
    val filter: StateFlow<Float> = _filter.asStateFlow()

    @Volatile
    var filterPosition: Float = CENTRE
        private set

    fun setFilter(position: Float) {
        val clamped = position.coerceIn(-1f, 1f)
        _filter.value = clamped
        filterPosition = through(RackChannel.FILTER, clamped, CENTRE)
    }

    // ---- tempo and key. See [Varispeed] -----------------------------------------------
    //
    // No `@Volatile` twin for these two. Everything else in this class is read by the audio
    // thread every buffer; these are handed to the player once when they change, so a flow is
    // the whole story.

    private val _speed = MutableStateFlow(CENTRE)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /**
     * The speed the player is told about — the target, or neutral while the channel is out.
     *
     * Separate from [speed] for the same reason [pitchApplied] is separate from [pitch], though
     * for a milder version of the same hazard: handing the player a rate is not a cheap setter,
     * it reconfigures the audio pipeline. Bypassing is one crossing per tap rather than the
     * dozens a second a dragging finger produces, and it reaches the player through the same
     * conflated, rate-limited collector everything else does.
     */
    private val _speedApplied = MutableStateFlow(CENTRE)
    val speedApplied: StateFlow<Float> = _speedApplied.asStateFlow()

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
        val clamped = position.coerceIn(-1f, 1f)
        _speed.value = clamped
        _speedApplied.value = through(RackChannel.SPEED, clamped, CENTRE)
    }

    fun setPitch(position: Float) {
        _pitch.value = position.coerceIn(-1f, 1f)
    }

    /**
     * The pitch the user last settled on, whether or not the player is currently being told.
     *
     * Bypassing the pitch cell has to be able to give this back exactly, and neither of the other
     * two fields can: [pitch] follows a finger that may have moved on, and [pitchApplied] reads
     * neutral for as long as the cell is out.
     */
    private var _pitchTarget: Float = CENTRE

    /** The finger has come off the pitch control: hand the settled value to the player. */
    fun commitPitch() {
        _pitchTarget = _pitch.value
        _pitchApplied.value = through(RackChannel.PITCH, _pitchTarget, CENTRE)
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
        _drive.value = clamped
        drivePosition = through(RackChannel.DRIVE, clamped, 0f)
    }

    // ---- the echo. See [Echo] ---------------------------------------------------------
    //
    // Three knobs, each 0..1, because that is what a delay you *play* needs: how long the
    // repeat is, how many there are, and how much of it you hear. Mix rests at zero — an
    // effect you throw in has to start out of the way.
    //
    // Only the mix answers to the bypass. Time and feedback are shape rather than presence:
    // changing them while the echo is out is free, and silently resetting them would throw away
    // a setting the user is in the middle of dialling in.

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
        _echoMix.value = clamped
        echoMixPosition = through(RackChannel.ECHO, clamped, 0f)
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
