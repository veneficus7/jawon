package com.jawon.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jawon.app.data.importer.CoverImageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What removing a record actually removes.
 *
 * **Written because one of the four steps had been missing since the beginning.**
 * `PlayEventDao.deleteForAlbum` exists, documents itself as *"for the delete path: an album's
 * history goes with it"*, and was never called from anywhere — so every album anyone ever
 * deleted left its whole listening history in the database, keyed by a UUID that can never
 * match a record again. Invisible, unreachable, and growing.
 *
 * That is exactly the failure a shared removal path plus a test is for: the steps are only
 * going to multiply as more of the app can delete things.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumRemoverTest {

    private lateinit var db: JawonDatabase
    private lateinit var remover: AlbumRemover
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, JawonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        remover = AlbumRemover(db.albumDao(), db.trackDao(), db.playEventDao(), CoverImageStore())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(albumId: String, plays: Int = 3, tracks: Int = 4) {
        db.shelfDao().insert(Shelf("shelf-1", "Unsorted", 0, 0f, 1L))
        db.albumDao().insert(
            Album(
                id = albumId, shelfId = "shelf-1", title = "Album $albumId", artist = "An Artist",
                year = 1994, caseCoverUri = null, discFaceUri = null, spineHue = 0f,
                slotIndex = 0, playCount = 0, addedAt = 1L
            )
        )
        repeat(tracks) { i ->
            db.trackDao().insert(
                Track(
                    id = "$albumId-t$i", albumId = albumId, trackNumber = i + 1,
                    title = "Track $i", durationMs = 200_000L,
                    fileUri = "content://test/$albumId/$i", mimeType = "audio/flac",
                    sampleRateHz = 44_100, bitDepth = 16, bitrateBps = 900_000
                )
            )
        }
        repeat(plays) { i ->
            db.playEventDao().insert(
                PlayEvent(albumId = albumId, trackId = "$albumId-t0", startedAt = i.toLong(), msPlayed = 1000L)
            )
        }
    }

    @Test
    fun `removing an album takes its tracks with it`() = runTest {
        seed("a")
        remover.remove("a")

        assertNull(db.albumDao().findById("a"))
        assertTrue(db.trackDao().getForAlbum("a").first().isEmpty())
    }

    @Test
    fun `removing an album takes its listening history with it`() = runTest {
        // The step that was missing. Album ids are UUIDs, so an orphaned PlayEvent row can
        // never be matched to a record again — it is unreachable data that only accumulates.
        seed("a", plays = 5)
        assertEquals(5, db.playEventDao().listAll().count { it.albumId == "a" })

        remover.remove("a")

        assertEquals(0, db.playEventDao().listAll().count { it.albumId == "a" })
    }

    @Test
    fun `it takes nothing belonging to any other record`() = runTest {
        seed("a", plays = 2, tracks = 3)
        seed("b", plays = 4, tracks = 5)

        remover.remove("a")

        assertEquals("b", db.albumDao().findById("b")?.id)
        assertEquals(5, db.trackDao().getForAlbum("b").first().size)
        assertEquals(4, db.playEventDao().listAll().count { it.albumId == "b" })
    }

    @Test
    fun `removing several leaves the rest untouched`() = runTest {
        seed("a"); seed("b"); seed("c")

        remover.removeAll(listOf("a", "c"))

        val left = db.albumDao().getAll().first()
        assertEquals(1, left.size)
        assertEquals("b", left.first().id)
        assertEquals(0, db.playEventDao().listAll().count { it.albumId == "a" || it.albumId == "c" })
    }

    @Test
    fun `removing an album that is not there is not an error`() = runTest {
        // The bulk path can be handed an id that a concurrent single delete already took — and
        // a selection of forty must not abandon the other thirty-nine over it.
        seed("a")
        remover.removeAll(listOf("a", "gone", "also-gone"))

        assertTrue(db.albumDao().getAll().first().isEmpty())
    }
}
