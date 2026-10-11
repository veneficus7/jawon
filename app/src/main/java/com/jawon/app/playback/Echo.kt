package com.jawon.app.playback

import kotlin.math.pow

/**
 * The second of Jawon's own effects: a delay you throw in and pull back out.
 *
 * **What a DJ actually does with one.** Not an ambience — a *gesture*. The mix is turned up for
 * a bar so the last phrase repeats and smears, then turned down again, usually while something
 * else is being cued. Which means the three controls that matter are how long the repeat is, how
 * many repeats there are, and how much of it you hear — and that the last of those has to be able
 * to go fully dry, cleanly, at any moment.
 *
 * Pure, like [DjFilter] and [ScratchEngine], so the feel can be tuned and the safety of the
 * feedback path proved without a device.
 */
object Echo {

    /** Shortest repeat. Below this it stops being an echo and becomes a comb filter. */
    const val MIN_MS = 40f

    /** Longest repeat, and what sizes the buffer. Past a second it stops feeling attached. */
    const val MAX_MS = 1_200f

    /**
     * The hard ceiling on feedback, and the only genuinely dangerous number in this file.
     *
     * Feedback is how much of the delayed signal is fed back in. At exactly 1 the echo never
     * decays; **above 1 it grows without bound** — every pass louder than the last until the
     * output is a full-scale scream. That is not an unpleasant sound, it is a speaker-damaging
     * one, and it arrives in about a second.
     *
     * 0.85 gives a long, musical tail — roughly a dozen audible repeats — and cannot run away.
     */
    const val MAX_FEEDBACK = 0.85f

    /**
     * Delay time for a knob at [position] (0..1), in milliseconds.
     *
     * Exponential for the same reason the filter's sweep is: what the ear tracks is the *ratio*
     * between repeat lengths, not the difference. A linear knob would put everything from an
     * eighth-note to a whole-note slap in the first fifth of its travel.
     */
    fun timeMsFor(position: Float): Float {
        if (!position.isFinite()) return MIN_MS
        val t = position.coerceIn(0f, 1f)
        return MIN_MS * (MAX_MS / MIN_MS).pow(t)
    }

    /** Feedback for a knob at [position] (0..1), clamped to something that cannot run away. */
    fun feedbackFor(position: Float): Float {
        if (!position.isFinite()) return 0f
        return position.coerceIn(0f, 1f) * MAX_FEEDBACK
    }

    /**
     * How fast the delay *time* is allowed to move, per second.
     *
     * Deliberately slow — much slower than the filter's. A delay line whose read point moves is
     * a tape machine whose motor is changing speed, and the pitch of everything already in the
     * buffer bends with it. That is the best sound this effect makes and the reason to glide
     * rather than jump; jumping instead produces a click, because the read point lands somewhere
     * unrelated to where the waveform was.
     */
    const val TIME_GLIDE_PER_S = 3.5f

    /** Mix and feedback can move quickly — neither one repositions the read point. */
    const val LEVEL_SMOOTHING_PER_S = 30f

    fun approach(current: Float, target: Float, dt: Float, ratePerS: Float): Float {
        if (dt <= 0f) return current
        val k = 1f - kotlin.math.exp(-ratePerS * dt)
        return current + (target - current) * k
    }

    /**
     * One channel's delay line.
     *
     * A ring buffer with a fractional read point, which is what allows the time to glide. One
     * instance per channel — sharing would cross-feed left into right.
     */
    class Line(maxFrames: Int) {
        private val buffer = FloatArray(maxFrames.coerceAtLeast(2))
        private var write = 0

        /**
         * How many frames have been written since the last [reset], capped at the buffer size.
         *
         * **This is what makes clearing free, and clearing had to become free.** The buffer holds
         * over a second of audio — some fifty thousand floats per channel — and `reset` used to
         * zero all of it. That is fine once; it is ruinous at the rate this is actually called,
         * because every change to playback speed or pitch flushes the audio pipeline, and a
         * finger dragging the pitch slider produces those by the dozen per second. The playback
         * thread ended up doing nothing but memset, pinned at a whole core, and it never caught
         * up again — measured at 606 jiffies per six seconds against a healthy 11, with the phone
         * hot enough to hit thermal throttling.
         *
         * Counting instead of clearing costs one increment per frame and makes [reset] O(1).
         */
        private var filled = 0

        fun reset() {
            write = 0
            filled = 0
        }

        /**
         * @param delayFrames how far back to read, in frames; interpolated, so fractions are fine.
         * @param feedback how much of what is read goes back in. Clamped — see [MAX_FEEDBACK].
         * @param mix 0 dry, 1 fully wet.
         */
        fun process(input: Float, delayFrames: Float, feedback: Float, mix: Float): Float {
            val size = buffer.size
            // Never read the sample we are about to write, and never read past the buffer.
            val back = delayFrames.coerceIn(1f, (size - 2).toFloat())
            // Nothing was recorded that long ago yet, so there is nothing to hear. This is what
            // stands in for the zeroed buffer `reset` used to leave behind: without it, a line
            // that has just been cleared would read whatever the *previous* track left in the
            // array and play it back as a burst of unrelated audio.
            val delayed = if (back > filled) {
                0f
            } else {
                val readAt = write - back
                // Ring-wrapped, and written so a negative read point lands correctly rather than
                // indexing backwards off the front of the array.
                val base = ((readAt.toInt() % size) + size) % size
                val frac = readAt - kotlin.math.floor(readAt)
                val a = buffer[base]
                val b = buffer[(base + 1) % size]
                a + (b - a) * frac
            }

            // Clamped on the way in, so a runaway is impossible even if a caller passes something
            // unreasonable — the ceiling is not a tuning value, it is a safety rail.
            val fb = feedback.coerceIn(0f, MAX_FEEDBACK)
            buffer[write] = (input + delayed * fb).coerceIn(-4f, 4f)
            write = (write + 1) % size
            if (filled < size) filled++

            val wet = mix.coerceIn(0f, 1f)
            return (input * (1f - wet) + delayed * wet).coerceIn(-1f, 1f)
        }
    }

    /** Frames needed to hold [MAX_MS] at [sampleRate], with a frame of headroom for the read. */
    fun bufferFramesFor(sampleRate: Int): Int =
        ((MAX_MS / 1000f) * sampleRate).toInt().coerceAtLeast(2) + 4
}
