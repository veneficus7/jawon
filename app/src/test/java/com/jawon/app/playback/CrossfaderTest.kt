package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crossfader.
 *
 * The curve is the instrument here, not a detail of it: a mixer fader that reaches full volume
 * gradually across its travel cannot chirp, transform or flare, because every one of those is a
 * fast click open and shut near the closed edge. What is pinned below is the sharpness that
 * makes those possible.
 */
class CrossfaderTest {

    @Test
    fun `shut is silent and open is full`() {
        assertEquals(0f, Crossfader.gainFor(0f), 1e-6f)
        assertEquals(1f, Crossfader.gainFor(1f), 1e-6f)
    }

    @Test
    fun `it is a scratch fader, not a mixing fader`() {
        // Full volume across the great majority of the travel. A linear law would put half
        // volume at the middle, which is a fade — useless for cutting.
        assertEquals(1f, Crossfader.gainFor(0.5f), 1e-6f)
        assertEquals(1f, Crossfader.gainFor(0.25f), 1e-6f)
        assertTrue("the cut should happen near the closed edge", Crossfader.OPEN_ABOVE < 0.35f)
    }

    @Test
    fun `the cut happens in the last fraction of the travel`() {
        assertEquals(0f, Crossfader.gainFor(Crossfader.CLOSED_BELOW), 1e-6f)
        assertEquals(1f, Crossfader.gainFor(Crossfader.OPEN_ABOVE), 1e-6f)
        val middleOfCut = (Crossfader.CLOSED_BELOW + Crossfader.OPEN_ABOVE) / 2f
        val g = Crossfader.gainFor(middleOfCut)
        assertTrue("mid-cut should be part open, was $g", g > 0.2f && g < 0.8f)
    }

    @Test
    fun `the curve never goes backwards`() {
        var previous = -1f
        for (i in 0..1000) {
            val g = Crossfader.gainFor(i / 1000f)
            assertTrue("gain went backwards at $i", g >= previous - 1e-6f)
            previous = g
        }
    }

    @Test
    fun `gain stays inside zero and one everywhere, including off the ends`() {
        for (p in listOf(-5f, -0.001f, 0f, 0.5f, 1f, 1.001f, 12f)) {
            val g = Crossfader.gainFor(p)
            assertTrue("gain $g out of range at $p", g in 0f..1f)
        }
    }

    @Test
    fun `the cut arrives at silence smoothly rather than stepping into it`() {
        // A straight ramp reaches zero with the signal still moving, which is a click at
        // exactly the moment a chirp is supposed to be clean. Raised cosine flattens at both
        // ends, so the approach to silence should be slower than the middle of the cut.
        val span = Crossfader.OPEN_ABOVE - Crossfader.CLOSED_BELOW
        val nearShut = Crossfader.gainFor(Crossfader.CLOSED_BELOW + span * 0.05f)
        val quarter = Crossfader.gainFor(Crossfader.CLOSED_BELOW + span * 0.25f)
        assertTrue("does not ease out of silence", nearShut < quarter * 0.5f)
    }

    @Test
    fun `it rests open, so a record simply plays`() {
        assertEquals(1f, Crossfader.gainFor(Crossfader.RESTING), 1e-6f)
    }

    // ---- the smoothing ----------------------------------------------------------------

    @Test
    fun `a cut is fast enough to still be a cut`() {
        // Two milliseconds should get most of the way there. Smoothed like the platter's rate
        // it would blunt every technique the fader exists for.
        val after2ms = Crossfader.approach(current = 1f, target = 0f, dt = 0.002f)
        assertTrue("the cut is too slow to chirp with, reached $after2ms", after2ms < 0.25f)
    }

    @Test
    fun `but it is not instant, because a jump is a click`() {
        val afterOneSample = Crossfader.approach(1f, 0f, 1f / 44_100f)
        assertTrue("stepped straight to zero", afterOneSample > 0f)
    }

    @Test
    fun `a zero timestep leaves the cut alone`() {
        assertEquals(0.6f, Crossfader.approach(0.6f, 0f, 0f), 1e-6f)
        assertEquals(0.6f, Crossfader.approach(0.6f, 0f, -1f), 1e-6f)
    }

    @Test
    fun `the cut arrives, given a few milliseconds`() {
        // 200 samples is about 4.5ms against a time constant near 1.1ms, so this lands at
        // roughly 98% — which is the right answer for a cut and inaudibly short of silence.
        var g = 1f
        repeat(200) { g = Crossfader.approach(g, 0f, 1f / 44_100f) }
        assertTrue("only reached $g after 4.5ms", g < 0.03f)

        // And it does get all the way there rather than parking just above zero.
        repeat(400) { g = Crossfader.approach(g, 0f, 1f / 44_100f) }
        assertEquals(0f, g, 1e-4f)
    }
}
