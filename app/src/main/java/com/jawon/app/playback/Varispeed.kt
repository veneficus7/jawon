package com.jawon.app.playback

import kotlin.math.abs
import kotlin.math.pow

/**
 * The pitch fader, and the thing next to it that a turntable never had.
 *
 * **These are two knobs because they are two ideas, and that is the whole point.** On a record
 * deck they are one: turning the platter faster raises the pitch, because the groove passes the
 * needle more quickly — which is why [ScratchTone] deliberately keeps them welded together, and
 * why taking the pitch out of a scratch leaves a scrub. Here they come apart. Media3 time-
 * stretches with Sonic, so a record can be slowed to three quarters and still sing in its
 * original key, or pushed up a tone at the tempo it was recorded at. Neither is a thing vinyl
 * can do, and both are things people reach for constantly.
 *
 * Applied by the *player* rather than by an [androidx.media3.common.audio.AudioProcessor]: this
 * is the one part of the rack the platform already does properly, and doing it by hand would be
 * a worse time-stretcher for no reason.
 *
 * Pure, and tested without a device.
 */
object Varispeed {

    /**
     * How much of the travel around centre counts as neutral.
     *
     * Smaller than the filter's, because unlike a filter this control has a *correct* value that
     * people return to — the speed the record was mastered at. A wide dead zone would make the
     * knob feel vague either side of it; a narrow one still guarantees that "off" can be found
     * without squinting at a readout, and the haptic tick does the rest.
     */
    const val DEAD_ZONE = 0.025f

    /**
     * The range, in octaves either way: half speed to double.
     *
     * Wider than a DJ deck's ±8% by a long way, and deliberately so — this is not for beatmatching
     * two records, it is for hearing one differently. Half speed is where a track becomes a
     * different genre; double is where a sample lives.
     */
    const val SPEED_OCTAVES = 1f

    /** Pitch range, in semitones either way. An octave, matching the speed range. */
    const val SEMITONES = 12f

    /** Whether a knob at [position] is close enough to centre to be doing nothing. */
    fun isNeutral(position: Float): Boolean =
        !position.isFinite() || abs(position) <= DEAD_ZONE

    /**
     * Playback rate for a speed knob at [position], −1 … 0 … +1.
     *
     * Exponential, like everything else here that moves in pitch: doubling and halving are the
     * same musical distance in opposite directions, so they get the same amount of travel. A
     * linear knob would put every slow speed in the bottom third and spend the rest going fast.
     */
    fun speedFor(position: Float): Float {
        if (isNeutral(position)) return 1f
        return 2f.pow(travel(position) * SPEED_OCTAVES)
    }

    /** Semitones of shift for a pitch knob at [position]. Negative is down. */
    fun semitonesFor(position: Float): Float {
        if (isNeutral(position)) return 0f
        return travel(position) * SEMITONES
    }

    /**
     * Pitch multiplier for a knob at [position] — what Media3 wants.
     *
     * Twelve semitones is a doubling, which is why this and [speedFor] are the same curve over
     * the same range. They are still two functions, because the ranges are allowed to diverge
     * and a single one would quietly tie them back together — which is the exact thing this
     * whole file exists to undo.
     */
    fun pitchFor(position: Float): Float {
        if (isNeutral(position)) return 1f
        return 2f.pow(semitonesFor(position) / 12f)
    }

    /**
     * The knob's position with the dead zone taken out and the rest stretched to fill.
     *
     * Without the stretch there would be a step at the edge of the detent: the first audible
     * value either side would be the one the dead zone ends at, rather than the smallest change
     * the knob can make.
     */
    private fun travel(position: Float): Float {
        if (!position.isFinite()) return 0f
        val p = position.coerceIn(-1f, 1f)
        val past = abs(p) - DEAD_ZONE
        if (past <= 0f) return 0f
        val scaled = past / (1f - DEAD_ZONE)
        return if (p < 0f) -scaled else scaled
    }

    /**
     * Media3 refuses a non-positive rate, and refuses it by throwing.
     *
     * Nothing here should ever produce one — the maths cannot — but this is the value handed
     * across a process boundary to the player, and a crash in the audio service is a crash of
     * the thing playing music. Cheap insurance at the edge.
     */
    fun safe(value: Float): Float =
        if (value.isFinite() && value > 0f) value.coerceIn(MIN_RATE, MAX_RATE) else 1f

    private const val MIN_RATE = 0.1f
    private const val MAX_RATE = 4f
}
