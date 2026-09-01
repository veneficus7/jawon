package com.jawon.app.playback

import com.jawon.app.ui.wallscene.Scratch
import kotlin.math.abs

/**
 * The arithmetic behind hearing a record while you scratch it.
 *
 * **Why this cannot be ExoPlayer.** A player can be told to run at half speed or double, but
 * it cannot run *backwards*, and backwards is half of what scratching is. So while the disc is
 * held, the audio around the playhead is decoded into memory and read out by hand at a rate
 * this file computes — positive to go forward, negative to go back, and the size of it setting
 * the pitch. That is the same thing a turntable does mechanically, and it is where the sound
 * comes from: a record dragged at a third speed is a third of the pitch, which is the growl,
 * and shoved forward at three times is the chirp.
 *
 * Pure, so the feel can be tuned and tested without a device or a decoder.
 */
object ScratchTone {

    /**
     * Playback rate for a disc turning at [radiansPerSecond]. `1f` is normal speed, negative
     * is backwards.
     *
     * Derived from [Scratch.SEC_PER_REV] rather than picked, so the disc and the audio agree
     * by construction: one revolution is that many seconds of music, so a disc turning at one
     * revolution per `SEC_PER_REV` seconds is a record playing at exactly normal speed. Tune
     * the disc's feel and the audio follows it.
     */
    fun rateFor(radiansPerSecond: Float): Float {
        if (!radiansPerSecond.isFinite()) return 0f
        val revsPerSecond = radiansPerSecond / TWO_PI
        return (revsPerSecond * Scratch.SEC_PER_REV).coerceIn(-MAX_RATE, MAX_RATE)
    }

    /**
     * How fast the rate is allowed to follow the finger, per second of audio.
     *
     * **Not instant, and this is most of what makes it sound good rather than digital.** A
     * finger reports position in jumps — a touch event every few milliseconds, each one a step
     * change in velocity — and applying those steps directly to a playback rate produces a
     * click on every one of them, because the waveform's slope jumps. A platter has mass and
     * cannot change speed instantly; smoothing the rate is what stands in for that mass, and
     * it turns a stuttering mess into a slur.
     *
     * Fast enough to still feel like your hand, slow enough that a single stray event cannot
     * be heard as a tick.
     */
    const val RATE_SMOOTHING_PER_S = 26f

    /**
     * Rate below which the disc counts as stopped and the output is silenced.
     *
     * A record held still makes no sound, and reading samples at a near-zero rate does not
     * produce silence — it produces the same few samples repeatedly, which is a loud buzz at
     * whatever frequency the buffer happens to be feeding. That buzz is the single worst
     * artefact this whole feature can produce, and it happens exactly when the user stops
     * moving, which is often.
     */
    const val SILENCE_BELOW = 0.02f

    /** Nothing sensible comes of reading a record at thirty times speed. */
    const val MAX_RATE = 4.5f

    /**
     * Ease the current rate toward [target] over [dt] seconds.
     *
     * Exponential rather than linear, so it arrives smoothly from any distance and never
     * overshoots into a direction change the finger did not ask for.
     */
    fun approach(current: Float, target: Float, dt: Float): Float {
        if (dt <= 0f) return current
        val k = 1f - kotlin.math.exp(-RATE_SMOOTHING_PER_S * dt)
        return current + (target - current) * k
    }

    /** Whether a rate should be heard at all. */
    fun audible(rate: Float): Boolean = abs(rate) >= SILENCE_BELOW

    /**
     * One output sample, read from [pcm] at a fractional position.
     *
     * Linear interpolation between neighbours. At a fractional read rate the cursor lands
     * between samples on almost every step, and taking the nearest one instead quantises the
     * waveform to the sample grid — which is audible as a harsh edge on exactly the slow drags
     * where the sound is supposed to be at its most syrupy.
     *
     * Out of range reads as silence rather than clamping to the end sample, because clamping
     * holds a DC offset that thumps when the cursor comes back into range.
     */
    fun sampleAt(pcm: ShortArray, position: Double): Short {
        if (pcm.isEmpty()) return 0
        val i = position.toInt()
        if (position < 0.0 || i >= pcm.size - 1) return 0
        val frac = (position - i).toFloat()
        val a = pcm[i]
        val b = pcm[i + 1]
        return (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
    }

    private const val TWO_PI = 6.2831855f
}
