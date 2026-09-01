package com.jawon.app.playback

/**
 * The other hand.
 *
 * **A scratch is two hands, and the app only had one of them.** Moving the record supplies the
 * pitch; cutting the sound in and out supplies the rhythm, and every named technique is a
 * pattern of cuts over a movement — a chirp closes the fader as the record turns around, a
 * transform taps it open while the record runs, a flare cuts holes in a single push, a crab
 * bounces it off four fingers. Without a fader all of those collapse into the same continuous
 * smear, which is why the disc alone sounded like a scrub rather than a scratch however good
 * the needle model got.
 *
 * ### The curve is the instrument
 *
 * A mixer for scratching is not a fader in the mixing sense. It reaches full volume within a
 * few millimetres of the closed edge and stays there across the rest of its travel — that
 * "sharp" curve is what makes a click open and shut cleanly, and it is the difference between
 * a crossfader you can chirp with and one you can only fade with. A linear law here would make
 * every technique above impossible.
 *
 * Pure, so the feel is testable without a device.
 */
object Crossfader {

    /** Below this the fader is shut. A hair off the edge, as a scratch fader is. */
    const val CLOSED_BELOW = 0.07f

    /** And by this it is wide open. The gap between the two is the whole cut. */
    const val OPEN_ABOVE = 0.20f

    /**
     * Gain for a fader at [position], `0` closed and `1` open.
     *
     * Raised-cosine across the cut rather than a straight line, because the ends matter more
     * than the middle: a linear ramp arrives at silence with the signal still moving, which is
     * a click at exactly the moment a chirp is supposed to be clean.
     */
    fun gainFor(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        if (p <= CLOSED_BELOW) return 0f
        if (p >= OPEN_ABOVE) return 1f
        val t = (p - CLOSED_BELOW) / (OPEN_ABOVE - CLOSED_BELOW)
        return 0.5f * (1f - kotlin.math.cos(Math.PI.toFloat() * t))
    }

    /**
     * How fast the gain may move, per second.
     *
     * Fast — this is a cut, not a fade, and smoothing it like the platter's rate would blunt
     * every technique the fader exists for. It is here at all because a gain that jumps between
     * buffers steps the waveform, and a step is a click; a couple of milliseconds of ramp
     * removes that without being audible as a fade.
     */
    const val SMOOTHING_PER_S = 900f

    /** Ease [current] toward [target] over [dt] seconds. */
    fun approach(current: Float, target: Float, dt: Float): Float {
        if (dt <= 0f) return current
        val k = 1f - kotlin.math.exp(-SMOOTHING_PER_S * dt)
        return current + (target - current) * k
    }

    /** Where the fader rests when nobody is touching it: open, so a record simply plays. */
    const val RESTING = 1f
}
