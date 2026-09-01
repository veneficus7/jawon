package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stopping the music at a sensible moment. */
class SleepTimerTest {

    private val minute = 60_000L

    @Test
    fun `nothing is counting when nothing is set`() {
        assertNull(SleepTimer.remainingMs(null, 1_000L))
        assertFalse(SleepTimer.hasExpired(null, Long.MAX_VALUE))
    }

    @Test
    fun `a countdown counts down`() {
        val now = 1_000_000L
        val ends = SleepTimer.endsAt(30, now)
        assertEquals(30 * minute, SleepTimer.remainingMs(ends, now))
        assertEquals(29 * minute, SleepTimer.remainingMs(ends, now + minute))
    }

    @Test
    fun `remaining never goes negative`() {
        val ends = SleepTimer.endsAt(15, 0L)
        assertEquals(0L, SleepTimer.remainingMs(ends, ends + 10 * minute))
    }

    @Test
    fun `expiry fires at the moment, not after it`() {
        val ends = SleepTimer.endsAt(15, 0L)
        assertFalse(SleepTimer.hasExpired(ends, ends - 1))
        assertTrue(SleepTimer.hasExpired(ends, ends))
        assertTrue(SleepTimer.hasExpired(ends, ends + 1))
    }

    // ---- the label -------------------------------------------------------------------

    @Test
    fun `off and end of record say what they are`() {
        assertEquals("OFF", SleepTimer.label(SleepTimer.Mode.Off, null))
        assertEquals("END OF RECORD", SleepTimer.label(SleepTimer.Mode.EndOfAlbum, null))
    }

    @Test
    fun `the last minute rounds up rather than sitting on zero`() {
        // A countdown reading "0 MIN" while music is still playing looks broken, and the last
        // minute is the one someone is most likely to be watching.
        assertEquals("1 MIN", SleepTimer.label(SleepTimer.Mode.After(15), 40_000L))
        assertEquals("1 MIN", SleepTimer.label(SleepTimer.Mode.After(15), 1L))
    }

    @Test
    fun `a whole number of minutes is not rounded up past itself`() {
        assertEquals("5 MIN", SleepTimer.label(SleepTimer.Mode.After(15), 5 * minute))
    }

    @Test
    fun `nothing left reads as stopping`() {
        assertEquals("STOPPING", SleepTimer.label(SleepTimer.Mode.After(15), 0L))
    }

    @Test
    fun `a countdown not yet started shows its full length`() {
        assertEquals("30 MIN", SleepTimer.label(SleepTimer.Mode.After(30), null))
    }

    // ---- the offered lengths -----------------------------------------------------------

    @Test
    fun `the offered lengths are ascending and sane`() {
        assertEquals(SleepTimer.OFFERED_MINUTES.sorted(), SleepTimer.OFFERED_MINUTES)
        assertTrue(SleepTimer.OFFERED_MINUTES.all { it in 1..180 })
    }

    @Test
    fun `modes compare by value, so the menu can mark the current one`() {
        // Data classes and objects, not instances — a chip comparing by identity would never
        // show a tick.
        assertEquals(SleepTimer.Mode.After(30), SleepTimer.Mode.After(30))
        assertEquals(SleepTimer.Mode.Off, SleepTimer.Mode.Off)
        assertEquals(SleepTimer.Mode.EndOfAlbum, SleepTimer.Mode.EndOfAlbum)
        assertTrue(SleepTimer.Mode.After(30) != SleepTimer.Mode.After(45))
    }
}
