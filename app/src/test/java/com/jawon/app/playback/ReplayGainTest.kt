package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volume levelling.
 *
 * Two things here are worth a test rather than an ear. The tag format is written by a dozen
 * different rippers and is loose in practice, so the parser has to be forgiving without ever
 * guessing; and the clamp is the only place in this app where bad data has a physical
 * consequence — a mistyped tag asking for +40dB through headphones.
 */
class ReplayGainTest {

    // ---- parsing what rippers actually write --------------------------------------

    @Test
    fun `reads the usual forms`() {
        assertEquals(-6.54f, ReplayGain.parseDb("-6.54 dB")!!, 1e-4f)
        assertEquals(-6.54f, ReplayGain.parseDb("-6.54dB")!!, 1e-4f)
        assertEquals(-6.54f, ReplayGain.parseDb("-6.54")!!, 1e-4f)
        assertEquals(3.2f, ReplayGain.parseDb("+3.2 dB")!!, 1e-4f)
        assertEquals(0f, ReplayGain.parseDb("0.00 dB")!!, 1e-4f)
    }

    @Test
    fun `is not case sensitive about the unit and tolerates stray space`() {
        assertEquals(-8f, ReplayGain.parseDb("  -8.00 DB  ")!!, 1e-4f)
        assertEquals(-8f, ReplayGain.parseDb("-8.00 db")!!, 1e-4f)
    }

    @Test
    fun `refuses to guess at anything that is not a number`() {
        assertNull(ReplayGain.parseDb(null))
        assertNull(ReplayGain.parseDb(""))
        assertNull(ReplayGain.parseDb("   "))
        assertNull(ReplayGain.parseDb("dB"))
        assertNull(ReplayGain.parseDb("loud"))
        assertNull(ReplayGain.parseDb("- dB"))
    }

    @Test
    fun `rejects non-finite values`() {
        assertNull(ReplayGain.parseDb("NaN"))
        assertNull(ReplayGain.parseDb("Infinity"))
        assertNull(ReplayGain.parseDb("-Infinity"))
    }

    // ---- decibels to a volume ------------------------------------------------------

    @Test
    fun `no tag means no change`() {
        assertEquals(1f, ReplayGain.volumeFor(null), 1e-6f)
        assertEquals(1f, ReplayGain.volumeFor(0f), 1e-6f)
    }

    @Test
    fun `minus six decibels is about half`() {
        assertEquals(0.5f, ReplayGain.volumeFor(-6.02f), 0.01f)
    }

    @Test
    fun `minus twelve decibels is a quarter`() {
        // Inside the clamp, so this is testing the conversion rather than the ceiling. A
        // loud remaster tagged around -12dB is the realistic worst case.
        assertEquals(0.2512f, ReplayGain.volumeFor(-12f), 0.001f)
    }

    @Test
    fun `the clamp bites before a tenth of full volume`() {
        // -20dB would be 0.1; the ceiling stops it short, which is the intended behaviour and
        // is worth pinning so nobody "fixes" the clamp by widening it without meaning to.
        assertTrue(ReplayGain.volumeFor(-20f) > 0.1f)
        assertEquals(ReplayGain.volumeFor(-ReplayGain.MAX_ABS_DB), ReplayGain.volumeFor(-20f), 1e-6f)
    }

    @Test
    fun `a wild tag cannot deafen anyone`() {
        // The one place bad data has a physical consequence.
        val loud = ReplayGain.volumeFor(40f)
        val expectedCeiling = ReplayGain.volumeFor(ReplayGain.MAX_ABS_DB)
        assertEquals(expectedCeiling, loud, 1e-4f)
        assertTrue("clamped value should still be finite", loud.isFinite())
    }

    @Test
    fun `a wild negative tag cannot silence a record outright`() {
        val quiet = ReplayGain.volumeFor(-200f)
        assertEquals(ReplayGain.volumeFor(-ReplayGain.MAX_ABS_DB), quiet, 1e-6f)
        assertTrue("should attenuate, not mute", quiet > 0f)
    }

    // ---- choosing which tag to honour ----------------------------------------------

    @Test
    fun `off honours nothing, even with tags present`() {
        assertEquals(
            1f,
            ReplayGain.volumeFor(ReplayGain.Mode.OFF, albumGainDb = -9f, trackGainDb = -3f),
            1e-6f
        )
    }

    @Test
    fun `album mode uses album gain when it is there`() {
        assertEquals(
            ReplayGain.volumeFor(-9f),
            ReplayGain.volumeFor(ReplayGain.Mode.ALBUM, albumGainDb = -9f, trackGainDb = -3f),
            1e-6f
        )
    }

    @Test
    fun `album mode falls back to track gain when a record has only that`() {
        assertEquals(
            ReplayGain.volumeFor(-3f),
            ReplayGain.volumeFor(ReplayGain.Mode.ALBUM, albumGainDb = null, trackGainDb = -3f),
            1e-6f
        )
    }

    @Test
    fun `track mode does not fall back to album gain`() {
        // Falling back the other way would move every track in a record by the same amount,
        // which is precisely what someone choosing track gain asked not to happen.
        assertEquals(
            1f,
            ReplayGain.volumeFor(ReplayGain.Mode.TRACK, albumGainDb = -9f, trackGainDb = null),
            1e-6f
        )
    }

    @Test
    fun `an untagged record plays untouched in every mode`() {
        for (mode in ReplayGain.Mode.entries) {
            assertEquals(
                "mode $mode changed the volume of an untagged track",
                1f,
                ReplayGain.volumeFor(mode, null, null),
                1e-6f
            )
        }
    }

    // ---- the stored setting ---------------------------------------------------------

    @Test
    fun `mode survives a round trip through its name`() {
        for (mode in ReplayGain.Mode.entries) {
            assertEquals(mode, ReplayGain.Mode.fromName(mode.name))
        }
    }

    @Test
    fun `an unknown or missing stored mode falls back to album`() {
        assertEquals(ReplayGain.Mode.ALBUM, ReplayGain.Mode.fromName(null))
        assertEquals(ReplayGain.Mode.ALBUM, ReplayGain.Mode.fromName(""))
        assertEquals(ReplayGain.Mode.ALBUM, ReplayGain.Mode.fromName("SOMETHING_ELSE"))
    }
}
