package com.jawon.app.playback

/**
 * Bass and stereo width, computed here rather than asked of the platform.
 *
 * ## Why this is not `BassBoost` and `Virtualizer`
 *
 * It was, and on the owner's phone the TONE key was dead. The device says why, in as many
 * words:
 *
 * ```
 * I JawonEffects: no bass boost on this device
 * java.lang.RuntimeException: Cannot initialize effect engine for type: 0634f220-… Error: -3
 * I JawonEffects: no virtualizer on this device
 * java.lang.RuntimeException: Cannot initialize effect engine for type: 37cc2c00-… Error: -3
 * ```
 *
 * `-3` is `ERROR_INVALID_OPERATION`, and it is what the framework returns when the effect type
 * is already claimed. On a Samsung that is exactly what has happened: the vendor's own audio
 * stack holds bass boost and the virtualiser for the whole device, so no third-party app can
 * instantiate them — the equaliser on the same session builds fine, which is what makes this
 * "these two are taken" rather than "effects do not work here". Nothing an app can do gets them
 * back, and Jawon's target device is a Galaxy.
 *
 * There was never a good reason for these two to be borrowed anyway. The filter, the drive and
 * the echo are all Jawon's own arithmetic in its own processor chain; TONE was the odd one out,
 * and being the odd one out is what made it the one that broke. Writing it here also makes it
 * work where a platform effect cannot bind at all — over a USB DAC, which is how this app is
 * meant to be listened to.
 *
 * ## The two effects
 *
 * **Bass** is a one-pole low-shelf: extract the low end with a single smoothing filter, add a
 * scaled copy back. Cheap, unconditionally stable, and shaped like what a tone control on a
 * real amplifier does — which is the point, since this sits beside a filter that is already a
 * DJ's tool.
 *
 * **Width** is mid/side. The mid is what both speakers agree on, the side is what they differ
 * by, and widening is multiplying the side. At the bottom of the travel the multiplier is
 * exactly 1, which reconstructs the input sample for sample — the same identity-at-zero
 * discipline [Drive] documents, and for the same reason: a control whose off position quietly
 * changes the sound is the bug this project keeps finding.
 *
 * Pure, and testable without a device.
 */
object Tone {

    /**
     * Where the bass shelf turns over, in hertz.
     *
     * Low enough to be weight rather than mud — 120Hz is under the fundamental of most vocals
     * and the body of a snare, so opening it up adds bottom end without thickening the middle,
     * which is what makes a bass control usable rather than merely loud.
     */
    const val SHELF_HZ = 120f

    /**
     * How much of the extracted low end is added back at the top of the travel.
     *
     * About +4.6dB of shelf. Modest on purpose: the trim below is derived from this number, so
     * every dB of boost is a dB the rest of the record gives up, and a huge figure here would
     * buy a spectacular bass control that made everything else inaudible.
     */
    const val MAX_BASS_GAIN = 0.7f

    /** The side signal's multiplier at the top of the travel. Two is wide; past it goes hollow. */
    const val MAX_WIDTH = 2.0f

    /** Below this, a knob is off and the whole effect is bypassed rather than computed. */
    const val AUDIBLE = 0.001f

    fun audible(amount: Float): Boolean = amount.isFinite() && amount > AUDIBLE

    /**
     * The one-pole coefficient for [SHELF_HZ] at this sample rate.
     *
     * The standard `1 - exp(-2πf/fs)` rather than the cruder `2πf/fs`, so the corner stays put
     * at 44.1k and at 192k instead of drifting up with the rate — which would make the same
     * setting sound different on a hi-res file than on a CD rip, on a player whose whole point
     * is that the file is exactly what you own.
     */
    fun shelfCoefficient(sampleRateHz: Int): Float {
        if (sampleRateHz <= 0) return 0f
        return (1.0 - Math.exp(-2.0 * Math.PI * SHELF_HZ / sampleRateHz)).toFloat()
            .coerceIn(0f, 1f)
    }

    /** How much low end is added back, for a knob at [amount]. Zero is untouched. */
    fun gainFor(amount: Float): Float =
        if (!amount.isFinite()) 0f else amount.coerceIn(0f, 1f) * MAX_BASS_GAIN

    /**
     * Output trim for a knob at [amount] — exactly enough to give back what the boost took.
     *
     * **This makes the bass control a tilt, and it has to be one.** A test written for this
     * caught the alternative: with a trim picked by hand rather than derived, a full-scale
     * bass note at full boost sat flat against the rail for most of every cycle, which is a
     * square wave, which is buzz on the loudest passage of a record. There is no headroom above
     * full scale to boost into — so the only honest way to make the low end louder is to make
     * everything else quieter, and this returns precisely the factor that does it.
     *
     * The consequence is worth stating rather than discovering: at full bass the whole record
     * is about 4.6dB down and the bottom end is where it was, so it reads as *more bass* by
     * proportion rather than by level. Turning it up will not make the track louder. That is
     * the same argument as [Drive.MIN_TRIM] — a control that always sounds better turned up is
     * a volume knob wearing a costume — carried to the point where it is a guarantee instead of
     * a tuning.
     */
    fun trimFor(amount: Float): Float {
        if (!amount.isFinite()) return 1f
        return 1f / (1f + gainFor(amount))
    }

    /** The trim at the top of the travel, for anything that wants to state the cost. */
    val MIN_TRIM: Float get() = trimFor(1f)

    /** The side multiplier for a knob at [amount]. Exactly 1 at rest. */
    fun widthFor(amount: Float): Float {
        if (!amount.isFinite()) return 1f
        return 1f + amount.coerceIn(0f, 1f) * (MAX_WIDTH - 1f)
    }

    /**
     * One sample of bass, given the running low-pass state.
     *
     * Returns the new state alongside the output because a one-pole filter is its own memory
     * and the caller owns one per channel — keeping the state outside makes this pure, which is
     * what lets the shape be asserted on the JVM rather than guessed at on a device.
     */
    fun bass(sample: Float, low: Float, coefficient: Float, gain: Float, trim: Float): Pair<Float, Float> {
        if (!sample.isFinite()) return 0f to low
        val nextLow = low + (sample - low) * coefficient
        val out = (sample + nextLow * gain) * trim
        return out.coerceIn(-1f, 1f) to nextLow
    }

    /**
     * Left and right, widened.
     *
     * A mono signal has no side component at all, so this leaves it exactly as it found it —
     * which is correct and worth knowing: widening cannot invent a stereo image that was never
     * recorded, and an effect that appeared to do so would be adding phase trickery the owner
     * did not ask for.
     */
    fun widen(left: Float, right: Float, width: Float): Pair<Float, Float> {
        if (!left.isFinite() || !right.isFinite()) return 0f to 0f
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f * width
        return (mid + side).coerceIn(-1f, 1f) to (mid - side).coerceIn(-1f, 1f)
    }

    /** How fast a moved knob reaches the audio, per second. Matches the other rack effects. */
    const val SMOOTHING_PER_S = 40f
}
