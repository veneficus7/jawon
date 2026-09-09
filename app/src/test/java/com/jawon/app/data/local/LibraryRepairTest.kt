package com.jawon.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind a delete with no undo, argued with directly.
 *
 * Every test here is written the same way round: what survives matters more than what goes, so
 * each one names the survivor rather than only counting the casualties.
 */
class LibraryRepairTest {

    private fun track(
        id: String,
        title: String,
        number: Int = 0,
        disc: Int? = null,
        durationMs: Long = 200_000L,
        uri: String = "content://$id"
    ) = Track(
        id = id,
        albumId = "album",
        trackNumber = number,
        discNumber = disc,
        title = title,
        artist = null,
        durationMs = durationMs,
        fileUri = uri,
        mimeType = "audio/flac",
        sampleRateHz = 44_100,
        bitDepth = 16,
        bitrateBps = 900_000
    )

    @Test
    fun `a clean album loses nothing`() {
        val tracks = listOf(
            track("a", "Wesley's Theory", number = 1),
            track("b", "For Free?", number = 2),
            track("c", "King Kunta", number = 3)
        )

        assertTrue(LibraryRepair.duplicatesIn(tracks).isEmpty())
    }

    @Test
    fun `the same track imported twice from different files loses the second copy`() {
        // The real case: one folder holding a FLAC and an MP3, or one album imported by folder
        // and again by the device scan. Different URIs, same music — which is why the
        // importer's own de-duplication by file URI never caught this.
        val tracks = listOf(
            track("keep", "King Kunta", number = 3, uri = "content://flac"),
            track("drop", "King Kunta", number = 3, uri = "content://mp3")
        )

        val doomed = LibraryRepair.duplicatesIn(tracks)

        assertEquals(1, doomed.size)
        assertEquals("drop", doomed.single().id)
    }

    @Test
    fun `three copies leave one`() {
        val tracks = listOf(
            track("keep", "Alright", number = 7),
            track("drop1", "Alright", number = 7),
            track("drop2", "Alright", number = 7)
        )

        assertEquals(listOf("drop1", "drop2"), LibraryRepair.duplicatesIn(tracks).map { it.id })
    }

    @Test
    fun `the same number on different discs is not a duplicate`() {
        // A double album has a track 1 on each disc, and they are different songs.
        val tracks = listOf(
            track("a", "Opening", number = 1, disc = 1),
            track("b", "Opening", number = 1, disc = 2)
        )

        assertTrue(LibraryRepair.duplicatesIn(tracks).isEmpty())
    }

    @Test
    fun `a missing disc number reads as disc one rather than as its own disc`() {
        // Half a library carries disc numbers and half does not, and the same album imported
        // twice can easily have them one way each. Treating null as a distinct disc would let
        // exactly that pair survive as two.
        val tracks = listOf(
            track("keep", "Money Trees", number = 4, disc = null),
            track("drop", "Money Trees", number = 4, disc = 1)
        )

        assertEquals("drop", LibraryRepair.duplicatesIn(tracks).single().id)
    }

    @Test
    fun `a remix is not a duplicate of the track it remixes`() {
        // **The line this rule refuses to cross.** Bracketed suffixes are left alone: these are
        // two recordings, and a tidy-up that quietly deleted one would be losing music, not
        // duplicates.
        val tracks = listOf(
            track("a", "Money Trees", number = 4),
            track("b", "Money Trees (Remix)", number = 4)
        )

        assertTrue(LibraryRepair.duplicatesIn(tracks).isEmpty())
    }

    @Test
    fun `punctuation and case do not make two tracks`() {
        val tracks = listOf(
            track("keep", "m.A.A.d city", number = 8),
            track("drop", "MAAD City", number = 8)
        )

        assertEquals("drop", LibraryRepair.duplicatesIn(tracks).single().id)
    }

    @Test
    fun `unnumbered tracks fall back to title and length`() {
        // A folder of loose files carries no numbering at all, so every row would key on the
        // same absent number. Without the length in the key, an album of untracked files would
        // collapse to whichever tracks happened to share a title.
        val tracks = listOf(
            track("keep", "Untitled", durationMs = 200_000L),
            track("other", "Untitled", durationMs = 340_000L),
            track("drop", "Untitled", durationMs = 200_400L)
        )

        // The 200.4s row is the same second as the 200.0s one; the 340s row is a different song.
        assertEquals("drop", LibraryRepair.duplicatesIn(tracks).single().id)
    }

    @Test
    fun `an unnumbered track and a numbered one are left alone`() {
        // No evidence they are the same, and this is a delete with no undo.
        val tracks = listOf(
            track("a", "Alright", number = 0),
            track("b", "Alright", number = 7)
        )

        assertTrue(LibraryRepair.duplicatesIn(tracks).isEmpty())
    }

    @Test
    fun `the survivor is the first in playing order`() {
        val tracks = listOf(
            track("first", "Alright", number = 7),
            track("second", "Alright", number = 7)
        )

        val survivors = tracks - LibraryRepair.duplicatesIn(tracks).toSet()

        assertEquals(listOf("first"), survivors.map { it.id })
    }

    @Test
    fun `an empty album is not a crash`() {
        assertTrue(LibraryRepair.duplicatesIn(emptyList()).isEmpty())
    }

    @Test
    fun `normalising leaves a title that needs nothing done to it`() {
        assertEquals("king kunta", LibraryRepair.normalise("  King Kunta  "))
        assertEquals("dna", LibraryRepair.normalise("DNA."))
    }
}
