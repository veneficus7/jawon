package com.jawon.app.playback

import kotlin.math.cos
import kotlin.math.sin

/**
 * The shape of a crossfade, as pure arithmetic.
 *
 * Separated from the machinery that drives it so the part that decides *when* and *how loud*
 * can be reasoned about and tested without a player, an audio device, or a pair of ears —
 * which matters here, because everything else about this feature can only be judged by
 * listening.
 */
object Crossfade {

    /** Longest fade offered. Past this it stops being a transition and becomes a mashup. */
    const val MAX_MS = 12_000

    /**
     * Below this a fade is not worth the machinery — two players, a preload and a swap for
     * something shorter than a gap between tracks already is.
     */
    const val MIN_MS = 500

    /**
     * How much lead time the incoming side needs to be ready.
     *
     * A player has to open the file, initialise a decoder and seek before it can produce a
     * sample, and a crossfade that starts by waiting for that is a crossfade with a hole in
     * it. Two seconds is generous for local files and costs nothing but an earlier prepare.
     */
    const val PRELOAD_MS = 2_000L

    /**
     * Gain for the track going out, at [progress] 0..1 through the fade.
     *
     * **Equal-power, not linear.** Two uncorrelated signals sum in power, not amplitude, so
     * fading one down a straight line while the other comes up a straight line leaves a
     * noticeable dip in loudness at the midpoint — the two half-volume signals together are
     * about 3dB quieter than either at full. Cosine and sine keep the sum of squares at 1
     * throughout, which is what makes a crossfade sound like one continuous piece of music
     * rather than a sag between two.
     */
    fun gainOut(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return cos(t * HALF_PI).toFloat()
    }

    /** Gain for the track coming in. The other half of [gainOut]. */
    fun gainIn(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return sin(t * HALF_PI).toFloat()
    }

    /**
     * Where in the current track the fade begins.
     *
     * Never before the halfway point: on a track shorter than twice the fade, starting on
     * schedule would mean crossfading almost the whole thing. Clamping keeps a 20-second
     * interlude recognisable instead of turning it into a wash.
     */
    fun startAtMs(durationMs: Long, fadeMs: Int): Long {
        if (durationMs <= 0L) return Long.MAX_VALUE
        val requested = durationMs - fadeMs
        return maxOf(requested, durationMs / 2)
    }

    /**
     * How long the fade actually gets, once the track's own length has had its say.
     *
     * Returns 0 when there is no room for one — the caller should then let the track simply
     * end, which is the honest answer for something too short to fade.
     */
    fun effectiveMs(durationMs: Long, fadeMs: Int): Int {
        if (durationMs <= 0L || fadeMs < MIN_MS) return 0
        val room = durationMs - startAtMs(durationMs, fadeMs)
        return room.coerceAtMost(fadeMs.toLong()).toInt()
    }

    /**
     * Whether the fade should be armed now.
     *
     * Deliberately takes the moment rather than reading a clock, so the decision is testable
     * and the caller is the only thing that needs a player.
     */
    fun shouldArm(
        positionMs: Long,
        durationMs: Long,
        fadeMs: Int,
        hasNextTrack: Boolean
    ): Boolean {
        if (!hasNextTrack) return false
        if (effectiveMs(durationMs, fadeMs) == 0) return false
        val startAt = startAtMs(durationMs, fadeMs)
        return positionMs >= startAt - PRELOAD_MS && positionMs < startAt
    }

    /** Whether the fade itself should be running now. */
    fun shouldStart(
        positionMs: Long,
        durationMs: Long,
        fadeMs: Int,
        hasNextTrack: Boolean
    ): Boolean {
        if (!hasNextTrack) return false
        if (effectiveMs(durationMs, fadeMs) == 0) return false
        return positionMs >= startAtMs(durationMs, fadeMs)
    }

    private const val HALF_PI = Math.PI / 2.0
}
