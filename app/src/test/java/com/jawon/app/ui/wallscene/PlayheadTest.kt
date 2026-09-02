package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayheadTest {

    @Test
    fun `moving forward is always taken`() {
        assertEquals(1_000L, Playhead.next(current = 900L, projected = 1_000L))
        assertEquals(90_000L, Playhead.next(current = 1L, projected = 90_000L))
    }

    @Test
    fun `a small step backwards is jitter and is ignored`() {
        // The extrapolation and the player's own reading disagree by a few tens of milliseconds
        // about the same moment. Following that backwards would flick the highlight up a line
        // and straight back down.
        assertEquals(10_000L, Playhead.next(current = 10_000L, projected = 9_990L))
        assertEquals(10_000L, Playhead.next(current = 10_000L, projected = 9_700L))
    }

    @Test
    fun `skipping to another track is not jitter`() {
        // **The bug this exists for.** A new track resets the position to near zero. Refusing to
        // go backwards left the playhead at the old track's time for ever, so the lyrics page
        // sat stuck on the final line of the song before.
        assertEquals(120L, Playhead.next(current = 190_000L, projected = 120L))
        assertEquals(0L, Playhead.next(current = 240_000L, projected = 0L))
    }

    @Test
    fun `seeking backwards inside a track is taken`() {
        assertEquals(30_000L, Playhead.next(current = 200_000L, projected = 30_000L))
    }

    @Test
    fun `the boundary belongs to jitter, and just past it does not`() {
        val current = 100_000L
        assertEquals(current, Playhead.next(current, current - Playhead.JITTER_MS))
        val justPast = current - Playhead.JITTER_MS - 1
        assertEquals(justPast, Playhead.next(current, justPast))
    }

    @Test
    fun `standing still stays still`() {
        assertEquals(5_000L, Playhead.next(current = 5_000L, projected = 5_000L))
    }

    @Test
    fun `the tolerance is smaller than the gap between samples`() {
        // If it were not, a genuine backwards seek of less than one sample interval could be
        // swallowed and the page would lag a whole poll behind the music.
        org.junit.Assert.assertTrue(Playhead.JITTER_MS < 500L)
    }
}
