package com.jawon.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a badge is allowed to claim.
 *
 * Most of these are about the badges a track must *not* get. A badge that lies is worse than no
 * badge, because a mark on a tracklist looks like the app knows something — and almost every
 * way this can go wrong produces a badge on everything, which is the same as a badge on
 * nothing.
 */
class TrackBadgeTest {

    private val threeMinutes = 180_000L

    private fun stats(plays: Int, furthestMs: Long = threeMinutes, startedAt: Long = 1_000L) =
        TrackPlayStats("t", plays, furthestMs, startedAt)

    private fun badges(
        stats: TrackPlayStats? = null,
        durationMs: Long = threeMinutes,
        mimeType: String = "audio/mpeg",
        bitDepth: Int? = 16,
        sampleRateHz: Int = 44_100,
        mostPlayed: Boolean = false,
        firstInOrder: Boolean = false,
        earliestPlayed: Boolean = false
    ) = TrackBadge.forTrack(
        durationMs = durationMs,
        mimeType = mimeType,
        bitDepth = bitDepth,
        sampleRateHz = sampleRateHz,
        stats = stats,
        isMostPlayedInAlbum = mostPlayed,
        isFirstInAlbumOrder = firstInOrder,
        isEarliestPlayedInAlbum = earliestPlayed
    )

    // ---- most played -----------------------------------------------------------------------

    @Test
    fun `a track played more than the rest is marked`() {
        assertTrue(TrackBadge.MOST_PLAYED in badges(stats(5), mostPlayed = true))
    }

    @Test
    fun `one play is not a favourite`() {
        // An album played once straight through leaves every track on one play. Badging the
        // "most played" of those would put a mark on a record nobody has listened to twice.
        assertFalse(TrackBadge.MOST_PLAYED in badges(stats(1), mostPlayed = true))
    }

    @Test
    fun `a tie at the top is nobody's badge`() {
        val evenlyPlayed = listOf(
            TrackPlayStats("a", plays = 4, furthestMs = threeMinutes, firstStartedAt = 1),
            TrackPlayStats("b", plays = 4, furthestMs = threeMinutes, firstStartedAt = 2)
        )
        // Four plays each means the album was played through four times — a fact about the
        // album, not about either track. Picking one arbitrarily would invent a preference.
        assertEquals(null, TrackBadge.albumFacts(evenlyPlayed).mostPlayedTrackId)
    }

    @Test
    fun `a strict maximum wins it`() {
        val stats = listOf(
            TrackPlayStats("a", plays = 9, furthestMs = threeMinutes, firstStartedAt = 1),
            TrackPlayStats("b", plays = 4, furthestMs = threeMinutes, firstStartedAt = 2)
        )
        assertEquals("a", TrackBadge.albumFacts(stats).mostPlayedTrackId)
    }

    @Test
    fun `an album nobody has played has no favourite`() {
        val stats = listOf(
            TrackPlayStats("a", plays = 0, furthestMs = 0, firstStartedAt = 0),
            TrackPlayStats("b", plays = 0, furthestMs = 0, firstStartedAt = 0)
        )
        assertEquals(null, TrackBadge.albumFacts(stats).mostPlayedTrackId)
        assertEquals(null, TrackBadge.albumFacts(stats).earliestPlayedTrackId)
    }

    // ---- never finished --------------------------------------------------------------------

    @Test
    fun `a track started twice and abandoned twice is marked`() {
        assertTrue(TrackBadge.NEVER_FINISHED in badges(stats(plays = 3, furthestMs = 20_000)))
    }

    @Test
    fun `one abandoned listen is an interruption, not a habit`() {
        // A phone call, a stop, putting the phone down. Calling that "never finished" would
        // badge half of any real library.
        assertFalse(TrackBadge.NEVER_FINISHED in badges(stats(plays = 1, furthestMs = 20_000)))
    }

    @Test
    fun `reaching the end once clears it, however often it was abandoned after`() {
        // furthestMs is the deepest single listen, not the total. A track played four times to
        // the two-minute mark has eight minutes against it and has still never been finished —
        // summing would mark it complete on the strength of never completing it.
        assertFalse(TrackBadge.NEVER_FINISHED in badges(stats(plays = 4, furthestMs = threeMinutes)))
        assertTrue(TrackBadge.NEVER_FINISHED in badges(stats(plays = 4, furthestMs = 120_000)))
    }

    @Test
    fun `stopping just short still counts as finished`() {
        // Nobody sits through the last few seconds of a fade, and a badge that says otherwise
        // would appear on every closing track in the library.
        val nearlyAll = (threeMinutes * 0.95f).toLong()
        assertFalse(TrackBadge.NEVER_FINISHED in badges(stats(plays = 5, furthestMs = nearlyAll)))
    }

    @Test
    fun `a track of unknown length is never marked unfinished`() {
        // Guarding a divide by an unknown: a zero duration would make every listen fall short.
        assertFalse(
            TrackBadge.NEVER_FINISHED in badges(stats(plays = 5, furthestMs = 0), durationMs = 0)
        )
    }

    @Test
    fun `a track never played is not unfinished, it is unheard`() {
        assertFalse(TrackBadge.NEVER_FINISHED in badges(stats = null))
    }

    // ---- first played ----------------------------------------------------------------------

    @Test
    fun `the way in is marked when it was not the opener`() {
        // The record you first met through one song you already knew, halfway down side two.
        assertTrue(
            TrackBadge.FIRST_PLAYED in
                badges(stats(1), earliestPlayed = true, firstInOrder = false)
        )
    }

    @Test
    fun `track one being played first is not worth saying`() {
        // True of almost every album ever played. A badge that is obvious four times in five is
        // clutter, and clutter on a tracklist devalues the marks that mean something.
        assertFalse(
            TrackBadge.FIRST_PLAYED in
                badges(stats(1), earliestPlayed = true, firstInOrder = true)
        )
    }

    @Test
    fun `the earliest played is the earliest by time, not by track number`() {
        val stats = listOf(
            TrackPlayStats("opener", plays = 2, furthestMs = 1, firstStartedAt = 9_000),
            TrackPlayStats("the-hit", plays = 1, furthestMs = 1, firstStartedAt = 100)
        )
        assertEquals("the-hit", TrackBadge.albumFacts(stats).earliestPlayedTrackId)
    }

    // ---- what the file is ------------------------------------------------------------------

    @Test
    fun `lossless formats are recognised, including their x- spellings`() {
        for (type in listOf("audio/flac", "audio/x-flac", "audio/alac", "audio/wav", "audio/x-aiff")) {
            assertTrue(type, TrackBadge.LOSSLESS in badges(mimeType = type))
        }
    }

    @Test
    fun `a lossy file is not dressed up as lossless`() {
        for (type in listOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/opus", "audio/ogg")) {
            assertFalse(type, TrackBadge.LOSSLESS in badges(mimeType = type))
        }
    }

    @Test
    fun `mime type case does not decide the answer`() {
        assertTrue(TrackBadge.LOSSLESS in badges(mimeType = "AUDIO/FLAC"))
    }

    @Test
    fun `hi-res is claimed on depth or on rate, either alone`() {
        assertTrue(TrackBadge.HI_RES in badges(bitDepth = 24, sampleRateHz = 44_100))
        assertTrue(TrackBadge.HI_RES in badges(bitDepth = 16, sampleRateHz = 96_000))
        assertTrue(TrackBadge.HI_RES in badges(bitDepth = 24, sampleRateHz = 192_000))
    }

    @Test
    fun `a CD-quality file is not hi-res`() {
        assertFalse(TrackBadge.HI_RES in badges(bitDepth = 16, sampleRateHz = 44_100))
        assertFalse(TrackBadge.HI_RES in badges(bitDepth = 16, sampleRateHz = 48_000))
    }

    @Test
    fun `an unknown bit depth does not become a hi-res claim`() {
        // The common case for FLAC — the platform extractor only reports a depth for raw PCM,
        // so a lossless file usually arrives with it null. That is "not known", and the readout
        // must not turn it into "better than CD". The sample rate is what carries this badge in
        // practice, which is why either measure alone is enough.
        assertFalse(TrackBadge.HI_RES in badges(bitDepth = null, sampleRateHz = 44_100))
        assertTrue(TrackBadge.HI_RES in badges(bitDepth = null, sampleRateHz = 96_000))
    }

    // ---- together ---------------------------------------------------------------------------

    @Test
    fun `a track can carry several, and they come out in a stable order`() {
        val all = badges(
            stats = stats(plays = 6, furthestMs = 10_000),
            mimeType = "audio/flac",
            bitDepth = 24,
            sampleRateHz = 96_000,
            mostPlayed = true,
            earliestPlayed = true,
            firstInOrder = false
        )
        assertEquals(
            listOf(
                TrackBadge.MOST_PLAYED,
                TrackBadge.NEVER_FINISHED,
                TrackBadge.FIRST_PLAYED,
                TrackBadge.LOSSLESS,
                TrackBadge.HI_RES
            ),
            all
        )
    }

    @Test
    fun `an untouched lossy track carries nothing at all`() {
        // The overwhelmingly common case on a library of downloads, and it must stay empty:
        // the value of these marks is entirely in how rarely they appear.
        assertTrue(badges().isEmpty())
    }
}
