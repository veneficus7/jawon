package com.jawon.app.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides what the app remembers about your listening.
 *
 * **Everything downstream of `PlayEvent` reads this**: the patina worn into each case on the
 * wall, the listening record, and the tracklist badges. A mistake here does not surface as a
 * mistake — it surfaces as an album that looks less played than it is, or a track wearing
 * NEVER FINISHED that was heard through a dozen times. All of them lie in the same direction
 * and none of them shows its working.
 *
 * `data/playback` was the last package in the app with no tests, for the ordinary reason: this
 * lived inside a class needing a `MediaController` and a running service. None of the decisions
 * needed either.
 */
class PlayAccountingTest {

    private val threeMinutes = 180_000L

    // ---- when a stretch ends ---------------------------------------------------------------

    @Test
    fun `a track change ends the stretch`() {
        assertTrue(PlayAccounting.closes(hasOpenSegment = true, trackChanged = true, isPlaying = true))
    }

    @Test
    fun `stopping ends the stretch even though no track changed`() {
        // The one that is easy to forget. A stretch also ends when someone pauses and walks
        // away — there is no transition then, and waiting for one would silently drop the last
        // track of every listening session.
        assertTrue(PlayAccounting.closes(hasOpenSegment = true, trackChanged = false, isPlaying = false))
    }

    @Test
    fun `playing on through the same track does not end anything`() {
        assertFalse(PlayAccounting.closes(hasOpenSegment = true, trackChanged = false, isPlaying = true))
    }

    @Test
    fun `with nothing open there is nothing to close`() {
        // Events arrive constantly while stopped. Closing a stretch that was never opened would
        // write a row out of whatever the fields happened to hold.
        assertFalse(PlayAccounting.closes(hasOpenSegment = false, trackChanged = true, isPlaying = false))
        assertFalse(PlayAccounting.closes(hasOpenSegment = false, trackChanged = false, isPlaying = false))
    }

    // ---- how far it got --------------------------------------------------------------------

    @Test
    fun `a track that changed ran to its end, not to where the player now is`() {
        // The subtlest thing in this file. By the time anyone asks, the position belongs to the
        // *next* track and is near zero — so reading it there would score every completed track
        // as barely played. That is the exact opposite of the truth, and it would put
        // NEVER FINISHED on an album someone had listened to all the way through.
        assertEquals(
            threeMinutes,
            PlayAccounting.reachedMs(trackChanged = true, durationMs = threeMinutes, positionMs = 400L)
        )
    }

    @Test
    fun `a track of unknown length falls back to the position`() {
        // The player does not always know a duration — a stream still buffering, a container it
        // has not finished parsing. Zero would score the stretch as nothing.
        assertEquals(
            42_000L,
            PlayAccounting.reachedMs(trackChanged = true, durationMs = 0L, positionMs = 42_000L)
        )
        assertEquals(
            42_000L,
            PlayAccounting.reachedMs(trackChanged = true, durationMs = -1L, positionMs = 42_000L)
        )
    }

    @Test
    fun `a stretch that ended without a track change reached wherever the player is`() {
        assertEquals(
            90_000L,
            PlayAccounting.reachedMs(trackChanged = false, durationMs = threeMinutes, positionMs = 90_000L)
        )
    }

    // ---- what counts -----------------------------------------------------------------------

    @Test
    fun `a real listen is counted as time in the track`() {
        // Started 30s in, stopped at 100s: 70 seconds of music, not 100.
        assertEquals(70_000L, PlayAccounting.countedMs(fromPositionMs = 30_000L, reachedMs = 100_000L))
    }

    @Test
    fun `skipping past something is not listening to it`() {
        // The whole reason the threshold exists: running a thumb down the tracklist must not
        // write twenty rows claiming an album was played twenty times.
        assertNull(PlayAccounting.countedMs(fromPositionMs = 0L, reachedMs = 500L))
        assertNull(PlayAccounting.countedMs(fromPositionMs = 10_000L, reachedMs = 11_000L))
    }

    @Test
    fun `exactly the threshold counts`() {
        assertEquals(
            PlayAccounting.MIN_COUNTED_MS,
            PlayAccounting.countedMs(fromPositionMs = 0L, reachedMs = PlayAccounting.MIN_COUNTED_MS)
        )
    }

    @Test
    fun `scrubbing backwards never records a negative`() {
        // Not an edge case here — the disc *is* a scrub control, so ending up behind where the
        // stretch started is an ordinary thing to do. A negative msPlayed would subtract from
        // the album total that the patina scale and every badge are computed from.
        assertNull(PlayAccounting.countedMs(fromPositionMs = 120_000L, reachedMs = 5_000L))
    }

    @Test
    fun `a pause does not inflate the count`() {
        // Measured in the audio, not on the clock. Someone who starts a track, pauses for an
        // hour and stops has listened to ten seconds of it, and the wall should say so.
        assertEquals(10_000L, PlayAccounting.countedMs(fromPositionMs = 0L, reachedMs = 10_000L))
    }

    // ---- the two put together ---------------------------------------------------------------

    @Test
    fun `a track heard start to finish records its whole length`() {
        val reached = PlayAccounting.reachedMs(trackChanged = true, durationMs = threeMinutes, positionMs = 0L)
        assertEquals(threeMinutes, PlayAccounting.countedMs(fromPositionMs = 0L, reachedMs = reached))
    }

    @Test
    fun `resuming mid-track and finishing records only the part actually heard`() {
        // Resume drops the listener back where they left off, so the stretch starts there. The
        // earlier part was already recorded when it was played; counting it again would make
        // every resumed album accumulate faster than one played straight through.
        val reached = PlayAccounting.reachedMs(trackChanged = true, durationMs = threeMinutes, positionMs = 0L)
        assertEquals(60_000L, PlayAccounting.countedMs(fromPositionMs = 120_000L, reachedMs = reached))
    }

    @Test
    fun `skipping a track a second after starting it records nothing`() {
        val reached = PlayAccounting.reachedMs(trackChanged = true, durationMs = threeMinutes, positionMs = 1_000L)
        // The track changed, so it "reached" its full length — but the stretch began at 179s,
        // one second before the end, which is someone landing on the last second while skipping.
        assertNull(PlayAccounting.countedMs(fromPositionMs = 179_000L, reachedMs = reached))
    }
}
