package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class DjFilterTest {

    private val sampleRate = 44_100

    // ---- the knob ------------------------------------------------------------------

    @Test
    fun `the centre of the knob is off, and off has width`() {
        assertEquals(DjFilter.Mode.OFF, DjFilter.modeFor(0f))
        assertEquals(DjFilter.Mode.OFF, DjFilter.modeFor(DjFilter.DEAD_ZONE))
        assertEquals(DjFilter.Mode.OFF, DjFilter.modeFor(-DjFilter.DEAD_ZONE))
        // A filter that is "almost bypassed" is worse than one that is bypassed.
        assertEquals(DjFilter.Mode.LOW_PASS, DjFilter.modeFor(-DjFilter.DEAD_ZONE - 0.01f))
        assertEquals(DjFilter.Mode.HIGH_PASS, DjFilter.modeFor(DjFilter.DEAD_ZONE + 0.01f))
    }

    @Test
    fun `left closes a low-pass down, right opens a high-pass up`() {
        val lpOpen = DjFilter.cutoffHz(-0.2f)
        val lpClosed = DjFilter.cutoffHz(-1f)
        assertTrue("low-pass should fall as the knob turns left", lpClosed < lpOpen)
        assertEquals(DjFilter.LP_MIN_HZ, lpClosed, 1f)

        val hpOpen = DjFilter.cutoffHz(0.2f)
        val hpClosed = DjFilter.cutoffHz(1f)
        assertTrue("high-pass should rise as the knob turns right", hpClosed > hpOpen)
        assertEquals(DjFilter.HP_MAX_HZ, hpClosed, 1f)
    }

    @Test
    fun `the sweep moves at a constant rate in octaves, not in hertz`() {
        // The whole reason a filter sweep feels smooth. A linear sweep would spend nine tenths
        // of the knob in the treble where almost nothing happens.
        val a = ln(DjFilter.cutoffHz(-0.30f) / DjFilter.cutoffHz(-0.55f))
        val b = ln(DjFilter.cutoffHz(-0.55f) / DjFilter.cutoffHz(-0.80f))
        assertEquals("equal travel should be equal octaves", a, b, 0.02f)
    }

    @Test
    fun `resonance rises with the sweep and stays sane`() {
        assertEquals(DjFilter.Q_REST, DjFilter.resonanceFor(0f), 1e-4f)
        assertTrue(DjFilter.resonanceFor(-1f) > DjFilter.resonanceFor(-0.4f))
        // Past about 3 the peak overwhelms the music it is meant to be shaping.
        assertTrue(DjFilter.resonanceFor(-1f) <= 3f)
        assertEquals(
            "the knob should be symmetric",
            DjFilter.resonanceFor(-0.8f), DjFilter.resonanceFor(0.8f), 1e-5f
        )
    }

    @Test
    fun `a nonsense knob position cannot produce a nonsense filter`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 9f, -9f)) {
            assertEquals(
                "mode must stay defined for $bad",
                true,
                DjFilter.modeFor(bad) in DjFilter.Mode.entries
            )
            val hz = DjFilter.cutoffHz(bad)
            assertTrue("cutoff $hz not finite for $bad", hz.isFinite())
        }
    }

    @Test
    fun `the coefficient never reaches the pole at Nyquist`() {
        // tan runs to infinity at Nyquist, and a filter handed infinity does not fail loudly —
        // it fills the buffer with NaN and the track goes silent.
        for (hz in listOf(-100f, 0f, 10f, 20_000f, 22_050f, 40_000f, 1e9f)) {
            val g = DjFilter.gFor(hz, sampleRate)
            assertTrue("g=$g not finite at $hz", g.isFinite())
            assertTrue("g=$g negative at $hz", g >= 0f)
        }
        assertEquals(0f, DjFilter.gFor(1_000f, 0), 0f)
    }

    // ---- the filter actually filtering ---------------------------------------------

    /** RMS of a sine at [toneHz] after passing through the filter set by [position]. */
    private fun rmsThrough(position: Float, toneHz: Float, frames: Int = 8_192): Float {
        val svf = DjFilter.Svf()
        val g = DjFilter.gFor(DjFilter.cutoffHz(position), sampleRate)
        val q = DjFilter.resonanceFor(position)
        val highPass = DjFilter.modeFor(position) == DjFilter.Mode.HIGH_PASS
        var sum = 0.0
        // The first stretch is the filter settling; measuring it would report the transient
        // rather than the response.
        val settle = 2_048
        for (n in 0 until frames + settle) {
            val input = 0.5f * sin(2.0 * PI * toneHz * n / sampleRate).toFloat()
            val out = svf.process(input, g, q, highPass)
            if (n >= settle) sum += (out * out).toDouble()
        }
        return sqrt(sum / frames).toFloat()
    }

    @Test
    fun `a closed low-pass removes the treble and keeps the bass`() {
        val bass = rmsThrough(-1f, 50f)
        val treble = rmsThrough(-1f, 6_000f)
        assertTrue(
            "closed low-pass should crush 6kHz (bass=$bass treble=$treble)",
            treble < bass * 0.05f
        )
    }

    @Test
    fun `a closed high-pass removes the bass and keeps the treble`() {
        val bass = rmsThrough(1f, 60f)
        val treble = rmsThrough(1f, 12_000f)
        assertTrue(
            "closed high-pass should crush 60Hz (bass=$bass treble=$treble)",
            bass < treble * 0.05f
        )
    }

    @Test
    fun `at rest the filter is transparent`() {
        // Off has to mean off: at the centre the knob must not quietly eat a decibel of top end.
        val open = rmsThrough(0f, 1_000f)
        val expected = 0.5f / sqrt(2f)
        assertEquals("a centred knob should pass 1kHz untouched", expected, open, 0.02f)
    }

    @Test
    fun `resonance cannot push the output past full scale`() {
        // A resonant filter rings past full scale on a transient even when its input did not, and
        // letting that wrap in the conversion back to 16-bit is a click, not a resonance.
        val svf = DjFilter.Svf()
        val position = -0.94f
        val g = DjFilter.gFor(DjFilter.cutoffHz(position), sampleRate)
        val q = DjFilter.resonanceFor(position)
        val cutoff = DjFilter.cutoffHz(position)
        var peak = 0f
        for (n in 0 until 20_000) {
            // A full-scale tone sitting right on the resonant peak — the worst case.
            val input = sin(2.0 * PI * cutoff * n / sampleRate).toFloat()
            peak = maxOf(peak, abs(svf.process(input, g, q, false)))
        }
        assertTrue("output reached $peak", peak <= 1f)
    }

    @Test
    fun `resetting clears the ring so a seek does not click`() {
        val svf = DjFilter.Svf()
        val g = DjFilter.gFor(200f, sampleRate)
        repeat(500) { svf.process(0.9f, g, 2.5f, false) }
        svf.reset()
        // With no history, silence in must be silence out.
        assertEquals(0f, svf.process(0f, g, 2.5f, false), 1e-6f)
    }

    // ---- smoothing -------------------------------------------------------------------

    @Test
    fun `the knob reaches the filter quickly but not instantly`() {
        // Instant is a click; slow feels broken. A sweep is a performance gesture.
        val oneStep = DjFilter.approach(0f, -1f, 1f / 44_100f)
        assertTrue("a single sample should barely move it", abs(oneStep) < 0.01f)

        var v = 0f
        repeat(44_100 / 10) { v = DjFilter.approach(v, -1f, 1f / 44_100f) }
        assertTrue("a tenth of a second should get most of the way (got $v)", v < -0.85f)
    }

    @Test
    fun `approach is a no-op for a non-advancing clock`() {
        assertEquals(0.4f, DjFilter.approach(0.4f, -1f, 0f), 0f)
        assertEquals(0.4f, DjFilter.approach(0.4f, -1f, -0.5f), 0f)
    }
}
