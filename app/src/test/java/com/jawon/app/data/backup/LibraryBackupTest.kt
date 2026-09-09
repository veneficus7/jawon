package com.jawon.app.data.backup

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.JawonDatabase
import com.jawon.app.data.local.PlayEvent
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.Track
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The backup writer, against a real Room database.
 *
 * **This is the first test in the project that touches Android.** Everything before it covers
 * pure files, which is most of the correctness but none of the risk — and the backup is where
 * the risk actually is. It is what stands between someone and losing a collection they cannot
 * rebuild, it was verified once by hand on an emulator, and a silent regression in it would
 * only ever be discovered by a person who had already lost everything.
 *
 * Robolectric runs it on the JVM in seconds. A real database, a real zip, a real round trip.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric emulates up to SDK 35 and this app targets 37, so the platform it stands up has
// to be pinned. 34 rather than 35 because it is a version real devices are actually on, and
// nothing here is version-sensitive: the backup is files and SQL.
@Config(sdk = [34])
class LibraryBackupTest {

    private lateinit var db: JawonDatabase
    private lateinit var backup: LibraryBackup
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun tempFile(name: String) = File(context.cacheDir, name).also { it.delete() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, JawonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backup = LibraryBackup(db.shelfDao(), db.albumDao(), db.trackDao(), db.playEventDao())
    }

    @After
    fun tearDown() = db.close()

    // ---- fixtures --------------------------------------------------------------------

    private suspend fun seed(coverPath: String? = null) {
        db.shelfDao().insert(Shelf("shelf-1", "Late Night", 0, 0.4f, 1_000L))
        db.albumDao().insert(
            Album(
                id = "album-1", shelfId = "shelf-1", title = "Kid A", artist = "Radiohead",
                year = 2000, caseCoverUri = coverPath, discFaceUri = null, spineHue = 0.6f,
                slotIndex = 0, playCount = 3, addedAt = 2_000L,
                lastTrackIndex = 2, lastPositionMs = 45_000L, lastPlayedAt = 3_000L
            )
        )
        db.trackDao().insert(
            Track(
                id = "track-1", albumId = "album-1", trackNumber = 1,
                title = "Everything In Its Right Place", durationMs = 251_000L,
                fileUri = "content://media/external/audio/media/42",
                mimeType = "audio/flac", sampleRateHz = 44100, bitDepth = 16, bitrateBps = 900_000
            )
        )
        db.playEventDao().insert(PlayEvent(0, "album-1", "track-1", 5_000L, 240_000L))
    }

    // ---- the round trip ----------------------------------------------------------------

    @Test
    fun `a collection survives being written out and read back`() = runTest {
        seed()
        val file = tempFile("round-trip.zip")
        val written = backup.writeTo(context, Uri.fromFile(file))

        assertEquals(1, written.shelves)
        assertEquals(1, written.albums)
        assertEquals(1, written.tracks)
        assertTrue("the zip was not written", file.length() > 0)

        // Wipe everything, exactly as an uninstall would.
        db.clearAllTables()
        assertTrue(db.albumDao().listAll().isEmpty())

        val restored = backup.readFrom(context, Uri.fromFile(file))
        assertEquals(1, restored.albums)
        assertEquals(1, restored.plays)

        val album = db.albumDao().listAll().single()
        assertEquals("Kid A", album.title)
        assertEquals("Radiohead", album.artist)
        assertEquals(2000, album.year)
        assertEquals(0.6f, album.spineHue, 1e-5f)
        // The resume point is part of the curation, and losing it would be a silent regression
        // that nobody would notice until they put a record back on.
        assertEquals(2, album.lastTrackIndex)
        assertEquals(45_000L, album.lastPositionMs)

        val track = db.trackDao().listAll().single()
        assertEquals("content://media/external/audio/media/42", track.fileUri)
        assertEquals(44100, track.sampleRateHz)
        assertEquals(16, track.bitDepth)

        assertEquals("Late Night", db.shelfDao().listAll().single().name)
        assertEquals(240_000L, db.playEventDao().listAll().single().msPlayed)
    }

    @Test
    fun `a streamed album comes back streamed, not as an unplayable local one`() = runTest {
        // **A real bug, caught before it shipped.** The first version of streaming added
        // `sourceId` and `remoteId` to the entities and forgot them here, and the failure was
        // entirely silent: a restored streamed album became a *local* album whose tracks had an
        // empty fileUri — so it sat on the wall looking perfect and played nothing. Worse, with
        // its sourceId gone `AlbumDao.forSource` could no longer see it, so the next sync would
        // insert a second copy of the entire server alongside the first.
        db.shelfDao().insert(Shelf("shelf-s", "Streaming", 0, 0f, 1_000L))
        db.albumDao().insert(
            Album(
                id = "subsonic:album:al-1", shelfId = "shelf-s", title = "Kid A",
                artist = "Radiohead", year = 2000, caseCoverUri = null, discFaceUri = null,
                spineHue = 0f, slotIndex = 0, playCount = 9, addedAt = 2_000L,
                lastTrackIndex = 0, lastPositionMs = 0L, lastPlayedAt = 4_000L,
                sourceId = "subsonic", remoteId = "al-1"
            )
        )
        db.trackDao().insert(
            Track(
                id = "subsonic:track:tr-1", albumId = "subsonic:album:al-1", trackNumber = 1,
                title = "Idioteque", durationMs = 100_000L,
                // Empty, because a stream URL carries a one-shot salt and is built at play time.
                fileUri = "", remoteId = "tr-1",
                mimeType = "audio/flac", sampleRateHz = 44100, bitDepth = 16, bitrateBps = 900_000
            )
        )

        val file = tempFile("streamed.zip")
        backup.writeTo(context, Uri.fromFile(file))
        db.clearAllTables()
        backup.readFrom(context, Uri.fromFile(file))

        val album = db.albumDao().listAll().single()
        assertEquals("subsonic", album.sourceId)
        assertEquals("al-1", album.remoteId)
        assertEquals("the play count is still the curation", 9, album.playCount)

        val track = db.trackDao().listAll().single()
        assertEquals("tr-1", track.remoteId)

        // And the album is findable again by the query the sync depends on. Without this, a
        // restore followed by a sync duplicates every record on the server.
        assertEquals(1, db.albumDao().forSource("subsonic").size)
    }

    @Test
    fun `an id containing a slash is not truncated on the way back`() = runTest {
        // `optName`, used for cover filenames, runs its value through `File(...).name` — which
        // would quietly shorten a server id like "library/1/al-9" to "al-9" and point the album
        // at the wrong record, or at none. The remote ids read through `optText` instead, and
        // this is what says so.
        db.shelfDao().insert(Shelf("shelf-s", "Streaming", 0, 0f, 1_000L))
        db.albumDao().insert(
            Album(
                id = "subsonic:album:x", shelfId = "shelf-s", title = "T", artist = "A",
                year = null, caseCoverUri = null, discFaceUri = null, spineHue = 0f,
                slotIndex = 0, playCount = 0, addedAt = 0L,
                sourceId = "subsonic", remoteId = "library/1/al-9"
            )
        )

        val file = tempFile("slashy.zip")
        backup.writeTo(context, Uri.fromFile(file))
        db.clearAllTables()
        backup.readFrom(context, Uri.fromFile(file))

        assertEquals("library/1/al-9", db.albumDao().listAll().single().remoteId)
    }

    @Test
    fun `an album from before streaming restores as local, not as remote`() = runTest {
        // The inverse, and the one that protects every backup already in existence: an archive
        // written before these keys existed has no `sourceId`, and the absent value must read as
        // null. `optString` would give "" — which is not null, so `forSource("")` could match it
        // and hand a hand-curated local library to a sync entitled to delete from it.
        seed()
        val file = tempFile("legacy.zip")
        backup.writeTo(context, Uri.fromFile(file))
        db.clearAllTables()
        backup.readFrom(context, Uri.fromFile(file))

        val album = db.albumDao().listAll().single()
        assertNull("an album with no server is local", album.sourceId)
        assertNull(album.remoteId)
        assertNull(db.trackDao().listAll().single().remoteId)
    }

    @Test
    fun `artwork is carried in the zip and rewritten to this install's directory`() = runTest {
        // The bug this guards: a backup stores an absolute path belonging to whichever install
        // wrote it, and after a reinstall that is not where the file is.
        val original = File(context.filesDir, "covers").apply { mkdirs() }
            .resolve("album-1-cover.jpg")
        original.writeBytes(ByteArray(64) { it.toByte() })
        seed(coverPath = original.absolutePath)

        val file = tempFile("art.zip")
        assertEquals(1, backup.writeTo(context, Uri.fromFile(file)).covers)

        db.clearAllTables()
        original.delete()

        assertEquals(1, backup.readFrom(context, Uri.fromFile(file)).covers)
        val path = db.albumDao().listAll().single().caseCoverUri
        assertNotNull("the cover path was lost", path)
        assertTrue("the restored cover file does not exist", File(path!!).isFile)
        assertEquals(64, File(path).length())
    }

    @Test
    fun `restoring twice does not duplicate the collection`() = runTest {
        seed()
        val file = tempFile("twice.zip")
        backup.writeTo(context, Uri.fromFile(file))

        backup.readFrom(context, Uri.fromFile(file))
        backup.readFrom(context, Uri.fromFile(file))

        // Rows are keyed by stable ids, so a second restore overwrites rather than adds.
        assertEquals(1, db.albumDao().listAll().size)
        assertEquals(1, db.shelfDao().listAll().size)
        assertEquals(1, db.trackDao().listAll().size)
    }

    @Test
    fun `restoring merges rather than replacing what is already there`() = runTest {
        seed()
        val file = tempFile("merge.zip")
        backup.writeTo(context, Uri.fromFile(file))

        // An album that exists only on the device must survive a restore. A restore that
        // wiped the current library would be the most destructive button in the app.
        db.albumDao().insert(
            Album(
                id = "album-local", shelfId = "shelf-1", title = "Added Later",
                artist = "Someone", year = null, caseCoverUri = null, discFaceUri = null,
                spineHue = 0f, slotIndex = 1, playCount = 0, addedAt = 9_000L
            )
        )

        backup.readFrom(context, Uri.fromFile(file))
        val titles = db.albumDao().listAll().map { it.title }.toSet()
        assertEquals(setOf("Kid A", "Added Later"), titles)
    }

    @Test
    fun `play history is given fresh ids rather than colliding with existing rows`() = runTest {
        seed()
        val file = tempFile("history.zip")
        backup.writeTo(context, Uri.fromFile(file))

        // A row this install issued for something else entirely. Restoring the backup's id
        // verbatim would overwrite it and silently destroy a real listen.
        db.playEventDao().insert(PlayEvent(0, "album-other", "track-other", 1L, 99L))
        val before = db.playEventDao().listAll()

        backup.readFrom(context, Uri.fromFile(file))
        val after = db.playEventDao().listAll()

        assertEquals("the unrelated listen was lost", before.size + 1, after.size)
        assertTrue(after.any { it.albumId == "album-other" && it.msPlayed == 99L })
    }

    // ---- refusing bad input --------------------------------------------------------------

    @Test(expected = IllegalStateException::class)
    fun `a file that is not a backup is refused`() = runTest {
        val junk = tempFile("not-a-backup.zip")
        junk.writeBytes("this is not a zip".toByteArray())
        backup.readFrom(context, Uri.fromFile(junk))
    }

    @Test
    fun `an empty library writes a backup that restores to an empty library`() = runTest {
        val file = tempFile("empty.zip")
        val written = backup.writeTo(context, Uri.fromFile(file))
        assertEquals(0, written.albums)

        val restored = backup.readFrom(context, Uri.fromFile(file))
        assertEquals(0, restored.albums)
        assertTrue(db.albumDao().listAll().isEmpty())
    }
}
