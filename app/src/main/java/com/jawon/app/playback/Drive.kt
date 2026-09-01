package com.jawon.app.playback

import kotlin.math.abs

/**
 * The third of Jawon's own effects: saturation, from warm to broken.
 *
 * **This is mostly already written.** The waveshaper is [ScratchTone.saturate] — the same
 * rational-tanh approximation that gives the scratch its grit — reused rather than copied, so
 * there is one saturator in this app and tuning it tunes both. What this file adds is what a
 * *rack* effect needs and a scratch does not: a single amount knob, a dry path that is exactly
 * dry, and a trim that stops the knob from being a volume control.
 *
 * Pure, and testable without a device.
 */
object Drive {

    /** Hardest drive at the top of the knob. Past this it stops being dirt and becomes a square. */
    const val MAX_DRIVE = 12f

    /**
     * How far the output is pulled down at full drive.
     *
     * **Without this the knob is a loudness control wearing a costume.** Saturation raises
     * average level even when it leaves the peak alone — that is what it *is* — so a drive knob
     * with no compensation always sounds better turned up, and the user is choosing volume while
     * believing they are choosing character. Trimming back as it opens means turning it up gets
     * you dirt rather than decibels, which is the same argument that capped the scratch's gain.
     */
    const val MIN_TRIM = 0.55f

    /** Drive for a knob at [amount] (0..1). 1 is clean. */
    fun driveFor(amount: Float): Float {
        if (!amount.isFinite()) return 1f
        return 1f + amount.coerceIn(0f, 1f) * (MAX_DRIVE - 1f)
    }

    /** Output trim for a knob at [amount]. Falls as the drive opens — see [MIN_TRIM]. */
    fun trimFor(amount: Float): Float {
        if (!amount.isFinite()) return 1f
        return 1f - amount.coerceIn(0f, 1f) * (1f - MIN_TRIM)
    }

    /**
     * One saturated sample.
     *
     * The dry/wet crossfade is what makes zero mean zero. The shaper is not transparent at a
     * drive of 1 — it is normalised, so it still bends the middle of the range — and a knob whose
     * "off" position quietly reshapes every waveform would be the fader's mistake in a new place.
     * Crossfading to the untouched input guarantees the identity at the bottom of the travel.
     */
    fun process(sample: Float, amount: Float): Float {
        if (!sample.isFinite()) return 0f
        // A non-finite amount reads as off rather than as an error. `coerceIn` does not rescue
        // NaN — every comparison against it is false, so it passes straight through and turns the
        // whole crossfade below into NaN, which reaches the sink as silence for the rest of the
        // track.
        val wet = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        // Clamped even on the bypass path: the output of this goes back to 16-bit, where anything
        // outside full scale wraps to a click rather than clipping.
        if (wet <= 0f) return sample.coerceIn(-1f, 1f)
        val shaped = ScratchTone.saturate(sample, driveFor(wet)) * trimFor(wet)
        return (sample * (1f - wet) + shaped * wet).coerceIn(-1f, 1f)
    }

    /** Whether the knob is far enough up to be worth running at all. */
    fun audible(amount: Float): Boolean = amount.isFinite() && abs(amount) > SILENT

    /** Below this the effect is inaudible and the block can be copied through. */
    const val SILENT = 0.001f

    /** How fast the knob reaches the shaper. Fast — nothing here repositions a read point. */
    const val SMOOTHING_PER_S = 30f

    fun approach(current: Float, target: Float, dt: Float): Float {
        if (dt <= 0f) return current
        val k = 1f - kotlin.math.exp(-SMOOTHING_PER_S * dt)
        return current + (target - current) * k
    }
}
