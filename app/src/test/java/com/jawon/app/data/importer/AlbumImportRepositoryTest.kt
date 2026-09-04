package com.jawon.app.data.importer

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.JawonDatabase
import com.jawon.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import, against a real database.
 *
 * **This is the code that touches a stranger's library, and it had one test file for nine.**
 * That imbalance is not academic: reading this file in September turned up a live data-loss
 * bug — the device scan was dividing the disc number away, so every multi-disc album collapsed
 * into one record with colliding track numbers. It had been shipping for weeks. Nothing here
 * is exotic; it is simply the least-examined code in the project, and it is the half a user
 * cannot undo.
 *
 * Robolectric gives a real Room database and real SharedPreferences on the JVM, the same way
 * `LibraryBackupTest` does. Nothing reaches the network: the cover-art switch is turned off in
 * [setUp], and every fixture carries a sample rate so `withTechnical` short-circuits instead of
 * opening a file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumImportRepositoryTest {

    private lateinit var db: JawonDatabase
    private lateinit var repository: AlbumImportRepository
    private lateinit var preferences: AppPreferences
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, JawonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = AppPreferences(context)
        // No network from a unit test, and no dependence on the shipped default either — the
        // switch defaults to on, and a test that silently relied on that would break the day
        // someone changed it.
        preferences.setFetchCoverArt(false)
        repository = AlbumImportRepository(
            context = context,
            shelfDao = db.shelfDao(),
            albumDao = db.albumDao(),
            trackDao = db.trackDao(),
            coverArtFetcher = CoverArtFetcher(),
            preferences = preferences
        )
    }

    @After
    fun tearDown() = db.close()

    /**
     * A scanned track.
     *
     * `sampleRateHz` is non-zero on purpose: `TrackMetadataReader.withTechnical` returns the
     * metadata untouched when it already has one, which is what keeps these tests off the
     * filesystem. A zero there would send the importer to open a URI that does not exist.
     */
    private fun track(
        name: String,
        number: Int? = null,
        disc: Int? = null,
        artist: String? = null,
        title: String = name
    ) = TrackMetadata(
        fileUri = Uri.parse("content://test/$name"),
        displayName = name,
        title = title,
        album = "An Album",
        artist = artist,
        trackNumber = number,
        discNumber = disc,
        year = 1994,
        durationMs = 200_000L,
        mimeType = "audio/flac",
        sampleRateHz = 44_100,
        bitDepth = 16,
        bitrateBps = 900_000
    )

    private fun preview(tracks: List<TrackMetadata>, title: String = "An Album") = ImportPreview(
        treeUri = Uri.parse("content://test/tree"),
        tracks = tracks,
        suggestedTitle = title,
        suggestedArtist = "An Artist",
        suggestedYear = 1994,
        failedCount = 0
    )

    private suspend fun commit(preview: ImportPreview, title: String = preview.suggestedTitle) =
        repository.commit(
            preview = preview,
            title = title,
            artist = preview.suggestedArtist,
            year = preview.suggestedYear,
            caseCoverUri = null,
            discFaceUri = null,
            spineHue = 0f
        )

    // ---- the shelf -----------------------------------------------------------------------

    @Test
    fun `the first import creates the Unsorted shelf, and the second reuses it`() = runTest {
        commit(preview(listOf(track("a.flac")), title = "One"))
        commit(preview(listOf(track("b.flac")), title = "Two"))

        val shelves = db.shelfDao().getAll().first()
        assertEquals("a second import must not create a second shelf", 1, shelves.size)
        assertEquals("Unsorted", shelves.first().name)
    }

    // ---- slot indices --------------------------------------------------------------------

    @Test
    fun `albums land on consecutive slots`() = runTest {
        // Distinct titles: since the duplicate guard landed, three commits of one title are one
        // album by design.
        repeat(3) { commit(preview(listOf(track("$it.flac")), title = "Album $it")) }

        val slots = db.albumDao().getAll().first().map { it.slotIndex }.sorted()
        assertEquals(listOf(0, 1, 2), slots)
    }

    @Test
    fun `a new album goes past the last slot, not into a gap left by a deletion`() = runTest {
        // The rule the code documents, and the bug it exists to prevent. A deleted album leaves
        // its gap on the shelf rather than the rest closing up, so the album *count* and the
        // next free *slot* stop agreeing the moment anything is removed. Counting would drop
        // the new record into the hole instead of at the end of the shelf.
        val first = commit(preview(listOf(track("a.flac")), title = "One"))
        commit(preview(listOf(track("b.flac")), title = "Two"))
        val third = commit(preview(listOf(track("c.flac")), title = "Three"))

        // Remove the middle one, leaving slot 1 empty with 2 still occupied.
        db.albumDao().getAll().first().first { it.id != first && it.id != third }
            .let { db.albumDao().delete(it) }

        val fourth = commit(preview(listOf(track("d.flac")), title = "Four"))
        val slot = db.albumDao().getAll().first().first { it.id == fourth }.slotIndex

        assertEquals("expected the slot after the highest in use, not the album count", 3, slot)
        assertNotEquals("2 is what counting albums would have given", 2, slot)
    }

    // ---- track numbering -----------------------------------------------------------------

    @Test
    fun `tagged track numbers are kept exactly as tagged`() = runTest {
        // Including the gap: a rip missing track 2 must not be silently renumbered, because the
        // numbers are printed on the case and a listener knows what is missing.
        val id = commit(preview(listOf(track("a", 1), track("c", 3), track("d", 4))))

        val numbers = db.trackDao().getForAlbum(id).first().map { it.trackNumber }
        assertEquals(listOf(1, 3, 4), numbers)
    }

    @Test
    fun `untagged tracks fall back to their position in the folder`() = runTest {
        val id = commit(preview(listOf(track("a"), track("b"), track("c"))))

        assertEquals(listOf(1, 2, 3), db.trackDao().getForAlbum(id).first().map { it.trackNumber })
    }

    @Test
    fun `a disc number survives the import and orders the tracklist`() = runTest {
        // The bug found by reading this file: the scan divided the disc away, so disc 1 track 1
        // and disc 2 track 1 both arrived as "1" in one album with nothing to order them by.
        val id = commit(
            preview(
                listOf(
                    track("d2t1", number = 1, disc = 2),
                    track("d1t1", number = 1, disc = 1),
                    track("d1t2", number = 2, disc = 1)
                )
            )
        )

        val tracks = db.trackDao().getForAlbum(id).first()
        assertEquals(
            "the tracklist must run disc 1 then disc 2, not interleave them",
            listOf("d1t1", "d1t2", "d2t1"),
            tracks.map { it.title }
        )
        assertEquals(listOf(1, 1, 2), tracks.map { it.discNumber })
    }

    @Test
    fun `a single-disc album gets no disc number at all`() = runTest {
        // Null rather than 1: a record that was never part of a set should not have a disc
        // number printed on its case just because the column exists.
        val id = commit(preview(listOf(track("a", 1))))
        assertNull(db.trackDao().getForAlbum(id).first().first().discNumber)
    }

    // ---- what belongs to the track and what to the album -----------------------------------

    @Test
    fun `a compilation keeps each track's own artist`() = runTest {
        // Until this was fixed the per-track tag was read, used to guess the album artist, and
        // thrown away — so a compilation played back entirely under one name.
        val id = commit(
            preview(listOf(track("a", 1, artist = "Aphex Twin"), track("b", 2, artist = "Autechre")))
        )

        assertEquals(
            listOf("Aphex Twin", "Autechre"),
            db.trackDao().getForAlbum(id).first().map { it.artist }
        )
    }

    @Test
    fun `a track with no artist of its own is left null rather than filled from the album`() = runTest {
        // Null means "the file did not say", and the deck falls back to the album for exactly
        // those. Writing the album's artist here would make a guess indistinguishable from
        // something the file actually claimed.
        val id = commit(preview(listOf(track("a", 1))))
        assertNull(db.trackDao().getForAlbum(id).first().first().artist)
    }

    @Test
    fun `the album takes the title it was committed with, not the one the scan suggested`() = runTest {
        // The edit the user makes on the import screen has to win — that screen exists because
        // the tags are often wrong.
        val id = commit(preview(listOf(track("a", 1)), title = "Scanned Name"), title = "Corrected Name")

        assertEquals("Corrected Name", db.albumDao().getAll().first().first { it.id == id }.title)
    }

    @Test
    fun `technical detail from the scan reaches the database`() = runTest {
        // What the deck's quality readout is drawn from. DESIGN.md rules out guessing any of
        // it, so a silent zero here would be a readout that lies.
        val id = commit(preview(listOf(track("a", 1))))

        val saved = db.trackDao().getForAlbum(id).first().first()
        assertEquals(44_100, saved.sampleRateHz)
        assertEquals(16, saved.bitDepth)
        assertEquals("audio/flac", saved.mimeType)
        assertEquals(200_000L, saved.durationMs)
    }

    // ---- many albums at once ---------------------------------------------------------------

    @Test
    fun `commitAll imports every album and reports how many`() = runTest {
        val count = repository.commitAll(
            listOf(
                preview(listOf(track("a", 1)), title = "One"),
                preview(listOf(track("b", 1)), title = "Two"),
                preview(listOf(track("c", 1)), title = "Three")
            )
        )

        assertEquals(3, count)
        assertEquals(3, db.albumDao().getAll().first().size)
        assertEquals(
            listOf("One", "Three", "Two"),
            db.albumDao().getAll().first().map { it.title }.sorted()
        )
    }

    @Test
    fun `every album gets its own id and its own tracks`() = runTest {
        val one = commit(preview(listOf(track("a", 1), track("b", 2)), title = "One"))
        val two = commit(preview(listOf(track("c", 1)), title = "Two"))

        assertNotEquals(one, two)
        assertEquals(2, db.trackDao().getForAlbum(one).first().size)
        assertEquals(1, db.trackDao().getForAlbum(two).first().size)
    }

    // ---- no duplicates ----------------------------------------------------------------------

    @Test
    fun `importing the same album twice does not put it on the wall twice`() = runTest {
        // Both scan routes had a guard and neither was binding: the device scan drops albums it
        // recognises, and the folder route only *flags* them so the import screen can say
        // "ALREADY ON A SHELF" — leaving the user free to tick it anyway. A second copy is not
        // a small annoyance here: it is a second case on the wall, a second set of tracks, and
        // a play history split across two albums that can never be added back together.
        val first = commit(preview(listOf(track("a", 1), track("b", 2)), title = "An Album"))
        val second = commit(preview(listOf(track("a", 1), track("b", 2)), title = "An Album"))

        assertEquals("the second import should return the album already there", first, second)
        assertEquals(1, db.albumDao().getAll().first().size)
        assertEquals(2, db.trackDao().getForAlbum(first).first().size)
    }

    @Test
    fun `the match ignores case, spacing and punctuation`() = runTest {
        val first = commit(preview(listOf(track("a", 1)), title = "The Dark Side of the Moon"))
        val second = commit(preview(listOf(track("a", 1)), title = "the dark-side of the moon!"))
        assertEquals(first, second)
    }

    @Test
    fun `renaming on the import screen still makes a separate record`() = runTest {
        // The escape hatch, and it has to keep working: two genuinely different records sharing
        // a name by the same artist are rare but real, and editing the title is how someone
        // says so. Refusing that would make the guard worse than the problem.
        val first = commit(preview(listOf(track("a", 1)), title = "Greatest Hits"))
        val second = commit(preview(listOf(track("b", 1))), title = "Greatest Hits Vol. 2")

        assertNotEquals(first, second)
        assertEquals(2, db.albumDao().getAll().first().size)
    }

    @Test
    fun `one file cannot appear twice in the same album`() = runTest {
        // Two rows pointing at one file is a tracklist that plays the same song twice and an
        // album duration that lies. Deduplicated at the write because the two scan routes build
        // their lists differently and this is the path they share.
        val duplicated = track("a", 1)
        val id = commit(preview(listOf(duplicated, duplicated, track("b", 2))))

        assertEquals(2, db.trackDao().getForAlbum(id).first().size)
    }

    // ---- the cover-art count the import screen shows ----------------------------------------

    @Test
    fun `nothing is looked up while the switch is off`() = runTest {
        preferences.setFetchCoverArt(false)
        assertEquals(0, repository.coversToLookUp(listOf(preview(listOf(track("a"))))))
    }

    @Test
    fun `only albums without embedded art are counted`() = runTest {
        preferences.setFetchCoverArt(true)
        val withArt = preview(listOf(track("a")), title = "Has Art")
            .copy(embeddedCoverUri = Uri.parse("content://test/art"))
        val without = preview(listOf(track("b")), title = "No Art")

        // The number shown before an import that is about to reach the network. Counting the
        // album that already has a cover would promise a lookup that never happens, and
        // overstating what leaves the device is the one direction this must not err in.
        assertEquals(1, repository.coversToLookUp(listOf(withArt, without)))
        assertEquals(0, repository.coversToLookUp(listOf(withArt)))
        assertTrue(repository.coversToLookUp(emptyList()) == 0)
    }
}
