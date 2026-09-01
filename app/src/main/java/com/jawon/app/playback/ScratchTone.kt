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
    const val RATE_SMOOTHING_PER_S = 72f

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

    /**
     * The fastest a record may be read, and the reason the first attempt sounded like a
     * cartoon.
     *
     * A turntable's pitch follows its speed — that is not a side effect, it is the whole
     * instrument, and the technique is literally "one hand controlling the pitch and speed of
     * the turntable, the other controlling volume". Taking pitch out to stop the squeaking
     * threw away the thing that makes a scratch a scratch.
     *
     * What was actually wrong was the ceiling. 4.5x is more than two octaves up, and nothing
     * a hand does on a real platter goes there — the mass of the record will not allow it. At
     * 2.4x the top of the range is about where a hard forward shove lands, so a fast movement
     * reads as aggressive rather than as a chipmunk.
     */
    const val MAX_RATE = 2.4f

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

    // ---- The needle. What turns a pitch-shifted seek into a scratch ------------------
    //
    // Playing the audio back at a signed rate gets the pitch right and still sounds thin and
    // digital, because a real turntable is not a resampler. Three things happen between the
    // groove and the speaker, and every one of them is speed-dependent:
    //
    //   1. The louder-when-faster of a stylus tracking a groove past itself more quickly.
    //   2. The high end falling away as the stylus loses its grip at speed.
    //   3. Saturation, from the cartridge and the phono stage, which is where the grit is.
    //
    // Those are what the ear reads as "vinyl" rather than "sample playback", and together they
    // are the hard, dirty quality the effect was missing.

    /**
     * Output gain for a record moving at [rate].
     *
     * A stylus dragged faster past a groove generates more signal, which is why a shove is
     * louder than a nudge on a real deck and why a scratch has a natural accent on the fast
     * part of the movement. Deliberately gentle and capped: this is a tilt, not a compressor,
     * and a scratch that doubles in level is a scratch that hurts on headphones.
     */
    fun gainFor(rate: Float): Float {
        val speed = abs(rate)
        return (0.76f + 0.40f * speed).coerceAtMost(1.38f)
    }

    /**
     * How hard the signal is driven into the saturator at [rate].
     *
     * **This is where the dirt is.** A cartridge and a phono stage are not clean, and they are
     * least clean when the stylus is being thrown about. Drive rising with speed means a slow
     * drag stays close to the record and a hard shove tears — which is the difference between
     * a scrub and a scratch, and it is what "distorted, hard" describes.
     */
    fun driveFor(rate: Float): Float {
        val speed = abs(rate)
        return 1.5f + 5.2f * speed
    }

    /**
     * Soft clip, normalised so quiet passages are not also turned down.
     *
     * A rational approximation of `tanh` rather than the real thing: this runs on every sample
     * on the audio thread, tens of thousands of times a second, and the difference is inaudible
     * where a stall is not. Odd-symmetric, so it adds the odd harmonics that read as warmth and
     * grit rather than the even ones that read as broken.
     */
    fun saturate(sample: Float, drive: Float): Float {
        val x = sample * drive
        val shaped = x / (1f + abs(x))
        // Divided by what the shaper does to full scale, so raising drive adds harmonics
        // instead of simply making everything quieter.
        val ceiling = drive / (1f + drive)
        val normalised = if (ceiling > 1e-4f) shaped / ceiling else shaped
        // Clamped, because the normalisation above is calibrated for a signal inside full
        // scale and will push past 1 for anything louder. Sample data can arrive hotter than
        // that — a mastered-loud track through the gain tilt is enough — and letting it out
        // would wrap in the conversion to 16-bit, which is a click rather than warmth. The
        // whole point of a soft clipper is that nothing leaves it hard.
        return normalised.coerceIn(-1f, 1f)
    }

    /**
     * One-pole low-pass coefficient for a record moving at [rate].
     *
     * The top end goes first when a stylus is moved quickly — it cannot follow the groove wall
     * accurately any more. Modelling that is what stops a fast scratch sounding like a sped-up
     * tape and starts it sounding like a needle being dragged: dull, thick and physical.
     *
     * Returns the smoothing weight for `y += a * (x - y)`; 1 is wide open.
     */
    fun toneCoeffFor(rate: Float, sampleRate: Int): Float {
        if (sampleRate <= 0) return 1f
        val speed = abs(rate)
        // Wide open at rest, closing toward a few kHz as the record is thrown about.
        val cutoffHz = (15_000f - 5_800f * speed).coerceIn(1_400f, 20_000f)
        val x = TWO_PI * cutoffHz / sampleRate
        return (x / (x + 1f)).coerceIn(0.01f, 1f)
    }


    private const val TWO_PI = 6.2831855f
}
