package com.jawon.app.playback

import kotlin.math.roundToInt

/**
 * Converting between 16-bit samples and the floats the effects work in.
 *
 * **Both halves of this were subtly wrong, and the chain applies them three times.**
 * Filter, drive and echo each take a short to a float and back, so whatever error the pair
 * introduces is paid once per active stage, per sample, on every piece of audio in the app.
 *
 * ## The scaling has to match
 *
 * The old code divided by 32768 going in and multiplied by **32767** coming out. Those are not
 * inverses. A sample at full scale came back one count short of where it started, so even a
 * stage doing nothing quietly rewrote the signal — and three stages did it three times.
 * Multiplying by the same 32768 makes the pair exact: `s / 32768 * 32768` is `s`, for every
 * value a short can hold.
 *
 * ## And it has to round, not truncate
 *
 * `toInt()` truncates toward zero. That is not a small difference from rounding: it doubles the
 * worst-case error to a full count and, far more audibly, makes the error **correlated with the
 * signal** rather than random. Correlated quantisation error is harmonic distortion — it tracks
 * the music instead of sitting underneath it, and on quiet passages and fades it is heard as
 * grain or grit. It is a large part of what "cheap" sounds like.
 *
 * Rounding halves the error and largely decorrelates it. It costs one instruction.
 *
 * Kept here rather than repeated in each processor so the two directions cannot drift apart
 * again, and so the round trip can be asserted once for every value a short can take — which
 * [Pcm16Test] does exhaustively, all 65,536 of them.
 */
object Pcm16 {

    /**
     * The scale factor, used in **both** directions.
     *
     * 32768 rather than 32767 because a signed 16-bit sample runs from −32768 to +32767: the
     * negative end is the one at full scale, and it is the one that decides the factor. The
     * cost is that +1.0 is not representable, which is correct — it never was.
     */
    const val SCALE = 32_768f

    /** A 16-bit sample as a float in [−1.0, 1.0). */
    fun toFloat(sample: Short): Float = sample / SCALE

    /**
     * A float back to 16 bits, rounded and clamped.
     *
     * The clamp is not paranoia: a resonant filter or a delay at high feedback can genuinely
     * exceed full scale on a transient, and letting that wrap in the conversion is a click
     * rather than a peak.
     *
     * Non-finite input yields silence rather than propagating. A NaN reaching `roundToInt`
     * produces zero on the JVM anyway, but relying on that is relying on an implementation
     * detail of a conversion, and the rest of this pipeline is explicit about it.
     */
    fun toShort(value: Float): Short {
        if (!value.isFinite()) return 0
        return (value * SCALE).roundToInt().coerceIn(-32_768, 32_767).toShort()
    }
}
