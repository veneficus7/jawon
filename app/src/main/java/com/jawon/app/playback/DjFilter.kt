package com.jawon.app.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tan

/**
 * The one knob every DJ mixer has, and the first of Jawon's own effects.
 *
 * **One control, two filters.** Centre is off. Turn left and a low-pass closes down from wide
 * open until only the bass is left; turn right and a high-pass climbs until only the hats
 * remain. That is a single gesture covering the two things anyone actually does to a track
 * mid-play, and it is why a mixer spends a whole knob on it rather than two.
 *
 * Pure, and deliberately so: every value here can be tested without a device, an audio session
 * or a decoder, which is the same reason [ScratchTone] is shaped this way.
 */
object DjFilter {

    /**
     * How much of the knob's travel around centre counts as off.
     *
     * A filter that is "almost bypassed" is worse than one that is bypassed — it eats the top or
     * bottom of the mix by a decibel or two for no audible benefit, and the user cannot tell
     * whether they have turned it off. A real mixer solves this with a centre detent you feel;
     * this is the software half of that, and the haptic tick is the other.
     */
    const val DEAD_ZONE = 0.06f

    /** Wide open. Above the top of hearing, so the low-pass starts inaudible rather than dull. */
    const val LP_MAX_HZ = 20_000f

    /** As far down as the low-pass goes: bass and kick only, everything else gone. */
    const val LP_MIN_HZ = 60f

    /** The high-pass at rest — below the fundamental of almost everything, so it does nothing. */
    const val HP_MIN_HZ = 20f

    /** As far up as the high-pass goes. Past this the track stops being recognisable. */
    const val HP_MAX_HZ = 8_000f

    /**
     * Resonance at rest and at the extremes.
     *
     * **This is what makes a sweep exciting rather than merely quieter.** A filter with no
     * resonance just removes; a filter with a peak at the cutoff *sings* as it moves, and that
     * ringing edge is the sound people associate with a build-up. Kept modest — past about 3 the
     * peak starts to overwhelm the music it is supposed to be shaping, and on a phone speaker it
     * turns into a whistle.
     */
    const val Q_REST = 0.707f
    const val Q_EXTREME = 2.6f

    enum class Mode { OFF, LOW_PASS, HIGH_PASS }

    /** Which filter a knob at [position] is running. Negative is left, positive is right. */
    fun modeFor(position: Float): Mode = when {
        !position.isFinite() || abs(position) <= DEAD_ZONE -> Mode.OFF
        position < 0f -> Mode.LOW_PASS
        else -> Mode.HIGH_PASS
    }

    /**
     * Cutoff for a knob at [position], in Hz.
     *
     * Exponential rather than linear across the travel, because pitch is exponential: the octave
     * between 100Hz and 200Hz is the same musical distance as the one between 5kHz and 10kHz, and
     * a linear sweep spends nine tenths of the knob in the treble where almost nothing happens.
     * The whole reason a filter sweep feels smooth is that it moves at a constant rate *in
     * octaves*.
     */
    fun cutoffHz(position: Float): Float {
        val travel = travelFor(position)
        return when (modeFor(position)) {
            Mode.OFF -> LP_MAX_HZ
            Mode.LOW_PASS -> LP_MAX_HZ * (LP_MIN_HZ / LP_MAX_HZ).pow(travel)
            Mode.HIGH_PASS -> HP_MIN_HZ * (HP_MAX_HZ / HP_MIN_HZ).pow(travel)
        }
    }

    /** Resonance for a knob at [position]. Rises with the sweep — see [Q_EXTREME]. */
    fun resonanceFor(position: Float): Float =
        Q_REST + (Q_EXTREME - Q_REST) * travelFor(position)

    /** How far past the dead zone the knob is, as 0..1. */
    private fun travelFor(position: Float): Float {
        if (!position.isFinite()) return 0f
        val past = abs(position).coerceIn(0f, 1f) - DEAD_ZONE
        if (past <= 0f) return 0f
        return (past / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
    }

    /**
     * The `g` coefficient — the pre-warped cutoff a [Svf] integrates with.
     *
     * Clamped below Nyquist because `tan` runs to infinity there, and a filter handed infinity
     * does not fail loudly, it fills the buffer with NaN and the speaker goes silent for the rest
     * of the track.
     */
    fun gFor(cutoffHz: Float, sampleRate: Int): Float {
        if (sampleRate <= 0) return 0f
        val nyquist = sampleRate / 2f
        val safe = cutoffHz.coerceIn(10f, nyquist * 0.98f)
        return tan(PI.toFloat() * safe / sampleRate)
    }

    /**
     * A topology-preserving state-variable filter — one structure, both responses.
     *
     * The older Chamberlin form is simpler and is a trap: it becomes unstable as the cutoff
     * approaches a third of the sample rate, which is exactly where a low-pass *starts* before
     * you turn it. This form stays stable across the whole range and gives low-pass and high-pass
     * out of the same two state variables, so switching sides mid-sweep costs nothing.
     *
     * One instance per channel — the state is the filter's memory, and sharing it across left and
     * right would mix them into mono.
     */
    class Svf {
        private var ic1eq = 0f
        private var ic2eq = 0f

        /** Forget the past. Call on a seek or a format change, or the old signal rings on. */
        fun reset() {
            ic1eq = 0f
            ic2eq = 0f
        }

        fun process(input: Float, g: Float, q: Float, highPass: Boolean): Float {
            val k = 1f / q.coerceAtLeast(0.05f)
            val a1 = 1f / (1f + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2

            val v3 = input - ic2eq
            val v1 = a1 * ic1eq + a2 * v3
            val v2 = ic2eq + a2 * ic1eq + a3 * v3
            ic1eq = 2f * v1 - ic1eq
            ic2eq = 2f * v2 - ic2eq

            val out = if (highPass) input - k * v1 - v2 else v2
            // A resonant filter can ring past full scale on a transient even when its input did
            // not, and letting that wrap in the conversion back to 16-bit is a click rather than
            // a resonance. Same reason the scratch's saturator clamps.
            return out.coerceIn(-1f, 1f)
        }
    }

    /**
     * How fast the knob position is allowed to reach the filter, per second of audio.
     *
     * Not instant, for the same reason the scratch's rate is not: a cutoff that jumps discards the
     * filter's state mid-ring and clicks. Faster than the scratch's smoothing though — a filter
     * sweep is a performance gesture and lag makes it feel broken rather than heavy.
     */
    const val SMOOTHING_PER_S = 26f

    /** Ease [current] toward [target] over [dt] seconds. */
    fun approach(current: Float, target: Float, dt: Float): Float {
        if (dt <= 0f) return current
        val k = 1f - kotlin.math.exp(-SMOOTHING_PER_S * dt)
        return current + (target - current) * k
    }
}
