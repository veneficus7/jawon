package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The conversion the whole effects chain is built on.
 *
 * Every active stage — filter, drive, echo — takes a short to a float and back, so any error
 * here is paid up to three times per sample on every piece of audio the app plays. It is
 * therefore worth checking exhaustively rather than at a few points, and exhaustively is cheap:
 * a short has only 65,536 values.
 */
class Pcm16Test {

    @Test
    fun `every sample survives a round trip unchanged`() {
        // **The regression this file exists for.** The old pair divided by 32768 and multiplied
        // by 32767, which are not inverses, and truncated instead of rounding. A stage doing
        // nothing at all therefore still altered the signal — and the chain has three stages.
        //
        // All 65,536 values, because "spot-checked a few" is exactly how the off-by-one at the
        // rails survived in the first place.
        var worst = 0
        for (value in Short.MIN_VALUE..Short.MAX_VALUE) {
            val sample = value.toShort()
            val round = Pcm16.toShort(Pcm16.toFloat(sample))
            worst = maxOf(worst, abs(round - sample))
        }
        assertEquals("every short must survive exactly", 0, worst)
    }

    @Test
    fun `the rails are exact, including the asymmetric negative one`() {
        // A signed 16-bit sample runs to -32768 but only to +32767. The negative end is the one
        // at true full scale, and it is the one that decides the scale factor.
        assertEquals((-32_768).toShort(), Pcm16.toShort(Pcm16.toFloat((-32_768).toShort())))
        assertEquals(32_767.toShort(), Pcm16.toShort(Pcm16.toFloat(32_767.toShort())))
        assertEquals(-1f, Pcm16.toFloat((-32_768).toShort()), 0f)
        assertEquals(0f, Pcm16.toFloat(0), 0f)
    }

    @Test
    fun `values are rounded, not truncated toward zero`() {
        // Truncation doubles the worst-case error and — the part that is actually audible —
        // makes it correlated with the signal, which is harmonic distortion rather than noise.
        // Half a count above zero must land on one, not on nothing.
        assertEquals(1.toShort(), Pcm16.toShort(0.7f / Pcm16.SCALE))
        assertEquals((-1).toShort(), Pcm16.toShort(-0.7f / Pcm16.SCALE))
        // And a value genuinely below half a count still rounds to silence.
        assertEquals(0.toShort(), Pcm16.toShort(0.4f / Pcm16.SCALE))
    }

    @Test
    fun `overshoot clamps instead of wrapping`() {
        // A resonant filter or a delay at high feedback can exceed full scale on a transient.
        // Wrapping that in the conversion is a click; clamping it is a peak.
        assertEquals(32_767.toShort(), Pcm16.toShort(2f))
        assertEquals((-32_768).toShort(), Pcm16.toShort(-2f))
        assertEquals(32_767.toShort(), Pcm16.toShort(Float.MAX_VALUE))
    }

    @Test
    fun `nothing that is not a number reaches the buffer`() {
        // The filter guards its own state, but this is the last gate before the sink, and a NaN
        // written into an output buffer is a click at best.
        assertEquals(0.toShort(), Pcm16.toShort(Float.NaN))
        assertEquals(0.toShort(), Pcm16.toShort(Float.POSITIVE_INFINITY))
        assertEquals(0.toShort(), Pcm16.toShort(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `the quantisation error never exceeds half a count`() {
        // What rounding buys over truncation, stated as the property rather than as examples.
        var worst = 0f
        var value = -1f
        while (value < 1f) {
            val quantised = Pcm16.toShort(value) / Pcm16.SCALE
            worst = maxOf(worst, abs(quantised - value))
            value += 1f / 4096f
        }
        assertTrue("worst error was $worst counts", worst <= 0.5f / Pcm16.SCALE + 1e-7f)
    }
}
