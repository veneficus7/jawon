package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class VarispeedTest {

    @Test
    fun `the centre is neutral, and neutral means exactly untouched`() {
        // A speed of 0.999 is not "off" — it is a slow drift no one asked for.
        assertTrue(Varispeed.isNeutral(0f))
        assertEquals(1f, Varispeed.speedFor(0f), 0f)
        assertEquals(1f, Varispeed.pitchFor(0f), 0f)
        assertEquals(0f, Varispeed.semitonesFor(0f), 0f)

        assertTrue(Varispeed.isNeutral(Varispeed.DEAD_ZONE))
        assertTrue(Varispeed.isNeutral(-Varispeed.DEAD_ZONE))
        assertTrue(!Varispeed.isNeutral(Varispeed.DEAD_ZONE + 0.01f))
    }

    @Test
    fun `the ends are half speed and double`() {
        assertEquals(0.5f, Varispeed.speedFor(-1f), 1e-4f)
        assertEquals(2f, Varispeed.speedFor(1f), 1e-4f)
    }

    @Test
    fun `the ends are an octave down and an octave up`() {
        assertEquals(-Varispeed.SEMITONES, Varispeed.semitonesFor(-1f), 1e-3f)
        assertEquals(Varispeed.SEMITONES, Varispeed.semitonesFor(1f), 1e-3f)
        assertEquals(0.5f, Varispeed.pitchFor(-1f), 1e-4f)
        assertEquals(2f, Varispeed.pitchFor(1f), 1e-4f)
    }

    @Test
    fun `equal travel is equal musical distance, not equal percentage`() {
        // A linear knob would put every slow speed in the bottom third and spend the rest going
        // fast. Doubling and halving are the same distance in opposite directions.
        val a = ln(Varispeed.speedFor(0.75f) / Varispeed.speedFor(0.50f))
        val b = ln(Varispeed.speedFor(0.50f) / Varispeed.speedFor(0.25f))
        assertEquals(a, b, 0.02f)
    }

    @Test
    fun `the knob is symmetric about centre`() {
        for (p in listOf(0.2f, 0.5f, 0.9f, 1f)) {
            // Up by x and down by x should be reciprocal rates.
            assertEquals(
                "speed not symmetric at $p",
                1f, Varispeed.speedFor(p) * Varispeed.speedFor(-p), 1e-4f
            )
            assertEquals(
                "pitch not symmetric at $p",
                0f, Varispeed.semitonesFor(p) + Varispeed.semitonesFor(-p), 1e-4f
            )
        }
    }

    @Test
    fun `there is no step at the edge of the detent`() {
        // The first audible value either side must be a small change, not a jump to whatever
        // the dead zone ends at.
        val justPast = Varispeed.DEAD_ZONE + 1e-4f
        assertEquals(1f, Varispeed.speedFor(justPast), 0.002f)
        assertEquals(1f, Varispeed.speedFor(-justPast), 0.002f)
        assertTrue(Varispeed.speedFor(justPast) > 1f)
        assertTrue(Varispeed.speedFor(-justPast) < 1f)
    }

    @Test
    fun `speed and pitch are separate ideas even where the curves agree`() {
        // Twelve semitones is a doubling, so these ranges coincide today. They are still two
        // functions, and the test exists so a future change to one range is a deliberate act
        // rather than a silent re-coupling of the two.
        for (p in listOf(-1f, -0.4f, 0.4f, 1f)) {
            assertEquals(Varispeed.speedFor(p), Varispeed.pitchFor(p), 1e-4f)
        }
        assertEquals(Varispeed.SEMITONES, Varispeed.SPEED_OCTAVES * 12f, 1e-6f)
    }

    @Test
    fun `nonsense in cannot produce a rate the player will throw on`() {
        // Media3 refuses a non-positive rate by throwing, and a crash in the audio service is a
        // crash of the thing playing the music.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -9f, 9f)) {
            assertTrue(Varispeed.speedFor(bad).isFinite())
            assertTrue(Varispeed.speedFor(bad) > 0f)
            assertTrue(Varispeed.pitchFor(bad) > 0f)
            assertTrue(Varispeed.safe(bad) > 0f)
        }
        assertEquals(1f, Varispeed.safe(0f), 0f)
        assertEquals(1f, Varispeed.safe(-1f), 0f)
        assertEquals(1.5f, Varispeed.safe(1.5f), 0f)
    }

    @Test
    fun `every position in range produces a usable rate`() {
        for (i in -100..100) {
            val p = i / 100f
            val s = Varispeed.speedFor(p)
            val t = Varispeed.pitchFor(p)
            assertTrue("speed $s at $p", s in 0.5f..2f)
            assertTrue("pitch $t at $p", t in 0.5f..2f)
        }
    }
}
