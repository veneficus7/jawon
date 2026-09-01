package com.jawon.app.playback

import com.jawon.app.ui.wallscene.Scratch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The arithmetic that turns a hand on the disc into a sound.
 *
 * Worth testing rather than tuning by ear, because two of these are not matters of taste. The
 * disc's rotation and the audio's rate have to agree by construction or the record drifts away
 * from the platter; and silencing a near-zero rate is what stands between a held disc and a
 * loud buzz, which is the worst thing this feature can do and happens exactly when someone
 * stops moving.
 */
class ScratchToneTest {

    private val twoPi = 6.2831855f

    // ---- the disc and the audio agree ------------------------------------------------

    @Test
    fun `one revolution per SEC_PER_REV is exactly normal speed`() {
        // The whole design: a revolution is SEC_PER_REV seconds of music, so a disc turning
        // at that rate is a record playing normally. If this drifts, the platter and the
        // sound are two different clocks.
        val normal = twoPi / Scratch.SEC_PER_REV
        assertEquals(1f, ScratchTone.rateFor(normal), 1e-4f)
    }

    @Test
    fun `turning backwards plays backwards`() {
        val normal = twoPi / Scratch.SEC_PER_REV
        assertEquals(-1f, ScratchTone.rateFor(-normal), 1e-4f)
    }

    @Test
    fun `a stopped disc makes no sound`() {
        assertEquals(0f, ScratchTone.rateFor(0f), 1e-6f)
        assertFalse(ScratchTone.audible(ScratchTone.rateFor(0f)))
    }

    @Test
    fun `rate scales with how fast the disc is turned`() {
        val normal = twoPi / Scratch.SEC_PER_REV
        assertEquals(2f, ScratchTone.rateFor(normal * 2f), 1e-4f)
        assertEquals(0.5f, ScratchTone.rateFor(normal * 0.5f), 1e-4f)
    }

    @Test
    fun `a violent flick is capped rather than becoming noise`() {
        val absurd = twoPi * 200f
        assertEquals(ScratchTone.MAX_RATE, ScratchTone.rateFor(absurd), 1e-4f)
        assertEquals(-ScratchTone.MAX_RATE, ScratchTone.rateFor(-absurd), 1e-4f)
    }

    @Test
    fun `nonsense velocity does not produce nonsense rate`() {
        assertEquals(0f, ScratchTone.rateFor(Float.NaN), 1e-6f)
        assertEquals(0f, ScratchTone.rateFor(Float.POSITIVE_INFINITY), 1e-6f)
    }

    // ---- silence near zero ------------------------------------------------------------

    @Test
    fun `a barely moving disc is silenced rather than buzzing`() {
        // Reading samples at a near-zero rate repeats the same few over and over, which is a
        // loud tone at the buffer's frequency — not quiet, which is what it should be.
        assertFalse(ScratchTone.audible(0f))
        assertFalse(ScratchTone.audible(ScratchTone.SILENCE_BELOW / 2f))
        assertFalse(ScratchTone.audible(-ScratchTone.SILENCE_BELOW / 2f))
        assertTrue(ScratchTone.audible(0.5f))
        assertTrue(ScratchTone.audible(-0.5f))
    }

    // ---- the smoothing ----------------------------------------------------------------

    @Test
    fun `the rate follows the finger without jumping to it`() {
        // A step change applied straight to a playback rate clicks, because the waveform's
        // slope jumps. This stands in for the mass of a platter.
        val stepped = ScratchTone.approach(current = 0f, target = 1f, dt = 0.01f)
        assertTrue("did not move toward the target", stepped > 0f)
        assertTrue("jumped straight to the target", stepped < 1f)
    }

    @Test
    fun `the rate arrives, given time`() {
        var rate = 0f
        repeat(200) { rate = ScratchTone.approach(rate, 1f, 0.005f) }
        assertEquals(1f, rate, 0.01f)
    }

    @Test
    fun `smoothing never overshoots into the wrong direction`() {
        // Overshoot here would mean the record briefly runs backwards when the finger asked
        // for forward, which is audible and wrong.
        var rate = -1f
        repeat(400) {
            rate = ScratchTone.approach(rate, 1f, 0.005f)
            assertTrue("overshot past the target", rate <= 1f + 1e-4f)
        }
    }

    @Test
    fun `a zero or negative timestep leaves the rate alone`() {
        assertEquals(0.4f, ScratchTone.approach(0.4f, 1f, 0f), 1e-6f)
        assertEquals(0.4f, ScratchTone.approach(0.4f, 1f, -0.5f), 1e-6f)
    }

    // ---- reading samples ---------------------------------------------------------------

    private val ramp = ShortArray(10) { (it * 100).toShort() }

    @Test
    fun `an exact position reads that sample`() {
        assertEquals(300, ScratchTone.sampleAt(ramp, 3.0).toInt())
    }

    @Test
    fun `a fractional position interpolates between neighbours`() {
        // Taking the nearest sample instead quantises the waveform to the sample grid, which
        // is harsh on exactly the slow drags that should sound thickest.
        assertEquals(350, ScratchTone.sampleAt(ramp, 3.5).toInt())
        assertEquals(325, ScratchTone.sampleAt(ramp, 3.25).toInt())
    }

    @Test
    fun `running off either end is silence, not a held sample`() {
        // Clamping to the end sample holds a DC offset, which thumps when the cursor comes
        // back into range.
        assertEquals(0, ScratchTone.sampleAt(ramp, -1.0).toInt())
        assertEquals(0, ScratchTone.sampleAt(ramp, -0.5).toInt())
        assertEquals(0, ScratchTone.sampleAt(ramp, 9.0).toInt())
        assertEquals(0, ScratchTone.sampleAt(ramp, 40.0).toInt())
    }

    @Test
    fun `an empty buffer reads as silence rather than throwing`() {
        assertEquals(0, ScratchTone.sampleAt(ShortArray(0), 0.0).toInt())
    }

    // ---- grains, which are what hold the pitch ----------------------------------------

    @Test
    fun `a grain is always an even number of frames`() {
        // The crash this prevents: grains overlap by exactly half, so an odd length leaves the
        // two halves different sizes and the tail runs off the end of its array — an
        // ArrayIndexOutOfBounds on the audio thread, which took the whole app down the first
        // time anyone spun the disc. 52ms at 44.1kHz is 2293 frames, which is where it bit.
        for (rate in listOf(8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000)) {
            val frames = ScratchTone.grainFramesFor(rate)
            assertEquals("odd grain at ${rate}Hz", 0, frames % 2)
        }
    }

    @Test
    fun `the two halves of a grain are exactly equal`() {
        // Stated directly, because this is the property the overlap buffer depends on.
        for (rate in listOf(44_100, 48_000, 22_050)) {
            val frames = ScratchTone.grainFramesFor(rate)
            val hop = frames / 2
            assertEquals("halves differ at ${rate}Hz", frames, hop * 2)
        }
    }

    @Test
    fun `a grain is never degenerately short even at a silly sample rate`() {
        assertTrue(ScratchTone.grainFramesFor(1) >= 64)
        assertTrue(ScratchTone.grainFramesFor(0) >= 64)
    }

    @Test
    fun `grain length tracks the sample rate`() {
        assertTrue(ScratchTone.grainFramesFor(48_000) > ScratchTone.grainFramesFor(22_050))
    }

    @Test
    fun `a grain window opens and closes at zero`() {
        // A step at either edge is a click, once per grain, twenty times a second.
        assertEquals(0f, ScratchTone.grainWindow(0, 100), 1e-5f)
        assertEquals(0f, ScratchTone.grainWindow(99, 100), 1e-5f)
    }

    @Test
    fun `a grain window is fully open in the middle`() {
        assertEquals(1f, ScratchTone.grainWindow(50, 101), 1e-4f)
    }

    @Test
    fun `two half-overlapped windows sum to one`() {
        // The property the whole scheme rests on: without it the overlap pumps in and out
        // instead of adding back to unit gain.
        val n = 128
        val hop = n / 2
        for (i in 0 until hop) {
            val here = ScratchTone.grainWindow(i + hop, n)
            val next = ScratchTone.grainWindow(i, n)
            assertEquals("overlap does not sum to one at $i", 1f, here + next, 0.02f)
        }
    }

    @Test
    fun `a window is never negative and never exceeds one`() {
        for (i in 0 until 200) {
            val v = ScratchTone.grainWindow(i, 200)
            assertTrue("window out of range at $i: $v", v in -1e-5f..1.0001f)
        }
    }

    @Test
    fun `a degenerate grain length does not divide by zero`() {
        assertEquals(1f, ScratchTone.grainWindow(0, 1), 1e-5f)
        assertEquals(1f, ScratchTone.grainWindow(0, 0), 1e-5f)
    }

    @Test
    fun `a grain is long enough to carry a pitch and short enough to follow a hand`() {
        // Too short and the grains are audible as a buzz at their own rate; too long and the
        // sound smears behind the finger.
        assertTrue(ScratchTone.GRAIN_MS in 25f..90f)
    }

    @Test
    fun `interpolation never wraps past the sixteen bit range`() {
        val extremes = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        for (i in 0..20) {
            val v = ScratchTone.sampleAt(extremes, i / 20.0)
            assertTrue("sample $v out of range", abs(v.toInt()) <= 32768)
        }
    }
}
