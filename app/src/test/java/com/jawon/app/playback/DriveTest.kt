package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DriveTest {

    @Test
    fun `zero means zero`() {
        // The shaper is normalised, so it is *not* transparent at a drive of 1 — it still bends
        // the middle of the range. A knob whose "off" quietly reshapes every waveform would be
        // the fader's mistake in a new place.
        for (x in listOf(-1f, -0.7f, -0.3f, 0f, 0.25f, 0.5f, 0.9f, 1f)) {
            assertEquals("input $x must pass untouched at zero", x, Drive.process(x, 0f), 1e-6f)
        }
    }

    @Test
    fun `it never leaves full scale, at any amount, for any input`() {
        for (amount in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (x in listOf(-4f, -1f, -0.5f, 0f, 0.5f, 1f, 4f)) {
                val out = Drive.process(x, amount)
                assertTrue("out $out for x=$x amount=$amount", out.isFinite() && abs(out) <= 1f)
            }
        }
    }

    @Test
    fun `it is odd-symmetric, so it adds the harmonics that read as warmth`() {
        // Odd symmetry gives odd harmonics, which read as warmth and grit. Even ones read as
        // broken.
        for (x in listOf(0.15f, 0.4f, 0.75f, 1f)) {
            assertEquals(
                "asymmetry at $x",
                Drive.process(x, 0.8f), -Drive.process(-x, 0.8f), 1e-5f
            )
        }
    }

    @Test
    fun `turning it up buys dirt, not decibels`() {
        // Without the trim this knob is a loudness control wearing a costume: saturation raises
        // average level even when it leaves the peak alone, so it always "sounds better" up.
        fun rms(amount: Float): Float {
            var sum = 0.0
            val n = 4_096
            for (i in 0 until n) {
                val input = 0.5f * sin(2.0 * PI * i / 64.0).toFloat()
                val out = Drive.process(input, amount)
                sum += (out * out).toDouble()
            }
            return sqrt(sum / n).toFloat()
        }
        val clean = rms(0f)
        val dirty = rms(1f)
        assertTrue(
            "full drive should not be much louder than clean (clean=$clean dirty=$dirty)",
            dirty < clean * 1.35f
        )
        // But it must still be doing something.
        assertTrue("full drive should differ from clean", abs(dirty - clean) > 1e-3f)
    }

    @Test
    fun `harmonic content rises with the knob even though level does not`() {
        // The point of the effect: more amount, more distortion. Measured as departure from the
        // input rather than as level, because level is deliberately held roughly flat.
        fun distortion(amount: Float): Float {
            var sum = 0.0
            val n = 4_096
            for (i in 0 until n) {
                val input = 0.6f * sin(2.0 * PI * i / 64.0).toFloat()
                val d = Drive.process(input, amount) - input
                sum += (d * d).toDouble()
            }
            return sqrt(sum / n).toFloat()
        }
        val low = distortion(0.2f)
        val mid = distortion(0.6f)
        val high = distortion(1f)
        assertTrue("distortion should rise: $low → $mid → $high", low < mid && mid < high)
    }

    @Test
    fun `the knobs stay sane for nonsense input`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f, 7f)) {
            assertTrue(Drive.driveFor(bad).isFinite())
            assertTrue(Drive.trimFor(bad) in Drive.MIN_TRIM..1f)
            assertTrue(Drive.process(0.5f, bad).isFinite())
        }
    }

    @Test
    fun `drive and trim move in opposite directions`() {
        assertEquals(1f, Drive.driveFor(0f), 1e-6f)
        assertEquals(Drive.MAX_DRIVE, Drive.driveFor(1f), 1e-6f)
        assertEquals(1f, Drive.trimFor(0f), 1e-6f)
        assertEquals(Drive.MIN_TRIM, Drive.trimFor(1f), 1e-6f)
        assertTrue(Drive.driveFor(0.7f) > Drive.driveFor(0.3f))
        assertTrue(Drive.trimFor(0.7f) < Drive.trimFor(0.3f))
    }

    @Test
    fun `full drive is the shaper and the trim, and nothing else`() {
        val x = 0.42f
        val amount = 1f
        val expected = Drive.saturate(x, Drive.driveFor(amount)) * Drive.trimFor(amount)
        assertEquals(expected, Drive.process(x, amount), 1e-6f)
    }

    @Test
    fun `the shaper is the curve the rack has always had`() {
        // Moved here from the scratch in 0.8, and it must not have changed on the way: these
        // are the old function's own values.
        assertEquals(0f, Drive.saturate(0f, 6f), 0f)
        assertEquals(1f, Drive.saturate(1f, 6f), 1e-6f)
        assertEquals(-1f, Drive.saturate(-1f, 6f), 1e-6f)
        assertEquals(0.875f, Drive.saturate(0.5f, 6f), 1e-6f)
        assertEquals(0.4375f, Drive.saturate(0.1f, 6f), 1e-6f)
        // Hotter than full scale leaves at full scale, never past it.
        assertEquals(1f, Drive.saturate(2f, 6f), 0f)
        // Odd-symmetric.
        assertEquals(-Drive.saturate(0.3f, 3f), Drive.saturate(-0.3f, 3f), 1e-6f)
    }
}
