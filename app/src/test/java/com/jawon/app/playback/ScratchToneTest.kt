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

    @Test
    fun `interpolation never wraps past the sixteen bit range`() {
        val extremes = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        for (i in 0..20) {
            val v = ScratchTone.sampleAt(extremes, i / 20.0)
            assertTrue("sample $v out of range", abs(v.toInt()) <= 32768)
        }
    }
}
