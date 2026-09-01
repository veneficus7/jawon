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

    // ---- the needle ---------------------------------------------------------------------

    @Test
    fun `a record moving faster is louder`() {
        // A stylus dragged faster past a groove generates more signal. It is why a shove
        // accents and a nudge does not.
        assertTrue(ScratchTone.gainFor(1.5f) > ScratchTone.gainFor(0.3f))
        assertEquals(ScratchTone.gainFor(1.2f), ScratchTone.gainFor(-1.2f), 1e-6f)
    }

    @Test
    fun `the level tilt is a tilt, not a compressor`() {
        // A scratch that doubles in level is a scratch that hurts on headphones.
        for (rate in listOf(0f, 0.5f, 1f, ScratchTone.MAX_RATE)) {
            val g = ScratchTone.gainFor(rate)
            assertTrue("gain $g out of hand at rate $rate", g in 0.5f..1.4f)
        }
    }

    @Test
    fun `drive rises with speed, so a shove tears and a drag does not`() {
        assertTrue(ScratchTone.driveFor(2f) > ScratchTone.driveFor(0.2f))
        assertEquals(ScratchTone.driveFor(1f), ScratchTone.driveFor(-1f), 1e-6f)
    }

    @Test
    fun `saturation never exceeds full scale`() {
        // Clipping past 1.0 is a wrap, which is a click rather than warmth.
        for (drive in listOf(1f, 3f, 8f)) {
            for (x in -30..30) {
                val v = ScratchTone.saturate(x / 10f, drive)
                assertTrue("saturate($x/10, $drive) = $v escaped", abs(v) <= 1.05f)
            }
        }
    }

    @Test
    fun `saturation is odd symmetric`() {
        // Odd harmonics read as warmth; even ones read as broken.
        for (x in listOf(0.1f, 0.4f, 0.9f)) {
            assertEquals(
                -ScratchTone.saturate(x, 3f),
                ScratchTone.saturate(-x, 3f),
                1e-5f
            )
        }
    }

    @Test
    fun `saturation leaves silence silent`() {
        assertEquals(0f, ScratchTone.saturate(0f, 4f), 1e-6f)
    }

    @Test
    fun `more drive means more compression of the loud parts`() {
        // The definition of soft clipping: the top of the signal moves less than the bottom.
        val quietLow = ScratchTone.saturate(0.2f, 1f)
        val quietHigh = ScratchTone.saturate(0.2f, 6f)
        assertTrue("driving harder should lift quiet parts", quietHigh > quietLow)
    }

    @Test
    fun `the top end falls away as the record is thrown about`() {
        // A stylus cannot follow the groove wall at speed. This is what stops a fast scratch
        // sounding like a sped-up tape.
        val still = ScratchTone.toneCoeffFor(0.1f, 44_100)
        val fast = ScratchTone.toneCoeffFor(2.2f, 44_100)
        assertTrue("filter did not close at speed", fast < still)
    }

    @Test
    fun `the tone filter stays a valid one-pole coefficient`() {
        for (rate in listOf(-3f, -1f, 0f, 1f, 3f)) {
            for (sr in listOf(22_050, 44_100, 48_000)) {
                val a = ScratchTone.toneCoeffFor(rate, sr)
                assertTrue("coefficient $a unstable at rate $rate, ${sr}Hz", a in 0f..1f)
            }
        }
    }

    @Test
    fun `a nonsense sample rate does not produce an unstable filter`() {
        assertEquals(1f, ScratchTone.toneCoeffFor(1f, 0), 1e-6f)
        assertTrue(ScratchTone.toneCoeffFor(1f, -44_100) in 0f..1f)
    }

    @Test
    fun `the ceiling is a scratch, not a cartoon`() {
        // Above about two and a half times, a hand on a real platter cannot go and the sound
        // stops reading as a record.
        assertTrue(ScratchTone.MAX_RATE in 1.5f..3f)
    }
}
