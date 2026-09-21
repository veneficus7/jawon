package com.jawon.app.data.local

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jawon.app.data.importer.CoverImageStore
import com.jawon.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What removing the Spotify route takes off a phone — and, the half that matters more, what it
 * leaves alone.
 *
 * This deletes rows from a real library at launch, on every launch, with nobody pressing
 * anything. A mistake in it does not show up as a bug; it shows up as records missing from
 * somebody's wall. So most of these tests are about what must survive.
 *
 * The strings `"spotify"` and `"Spotify"` are written out rather than borrowed from the class on
 * purpose: they are what the removed sync wrote into existing databases, and the test should fail
 * if someone "tidies" the constants into values that no longer match that data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotifyCleanupTest {

    private lateinit var db: JawonDatabase
    private lateinit var cleanup: SpotifyCleanup
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, JawonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val remover = AlbumRemover(db.albumDao(), db.trackDao(), db.playEventDao(), CoverImageStore())
        cleanup = SpotifyCleanup(db.albumDao(), db.shelfDao(), remover, AppPreferences(context))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun shelf(id: String, name: String) =
        db.shelfDao().insert(Shelf(id, name, order = 0, accentHue = 0f, createdAt = 1L))

    private suspend fun album(id: String, shelfId: String, sourceId: String?) {
        db.albumDao().insert(
            Album(
                id = id, shelfId = shelfId, title = "Album $id", artist = "An Artist",
                year = 2000, caseCoverUri = null, discFaceUri = null, spineHue = 0f,
                slotIndex = 0, playCount = 3, addedAt = 1L,
                sourceId = sourceId, remoteId = sourceId?.let { "remote-$id" }
            )
        )
        db.trackDao().insert(
            Track(
                id = "$id-t1", albumId = id, trackNumber = 1, title = "Track",
                durationMs = 200_000L, fileUri = if (sourceId == "spotify") "" else "content://$id",
                remoteId = sourceId?.let { "remote-$id-t1" }, mimeType = "audio/flac",
                sampleRateHz = 44_100, bitDepth = 16, bitrateBps = 900_000
            )
        )
        db.playEventDao().insert(
            PlayEvent(albumId = id, trackId = "$id-t1", startedAt = 1L, msPlayed = 60_000L)
        )
    }

    @Test
    fun `spotify albums go, with their tracks, their history and the shelf made for them`() =
        runTest {
            shelf("spotify-shelf", "Spotify")
            album("spotify:album:1", "spotify-shelf", sourceId = "spotify")
            album("spotify:album:2", "spotify-shelf", sourceId = "spotify")

            assertEquals(2, cleanup.run())

            assertTrue(db.albumDao().listAll().isEmpty())
            assertTrue(db.trackDao().listAll().isEmpty())
            assertTrue(db.playEventDao().listAll().isEmpty())
            assertTrue(db.shelfDao().listAll().isEmpty())
        }

    @Test
    fun `every other source is left exactly as it was`() = runTest {
        // A local record, a music server's and one from the record shop, all beside a Spotify
        // one on the same wall. Only the Spotify one may move.
        shelf("mine", "Unsorted")
        shelf("spotify-shelf", "Spotify")
        album("local", "mine", sourceId = null)
        album("subsonic:album:1", "mine", sourceId = "subsonic")
        album("archive:album:1", "mine", sourceId = "archive")
        album("spotify:album:1", "spotify-shelf", sourceId = "spotify")

        assertEquals(1, cleanup.run())

        assertEquals(
            setOf("local", "subsonic:album:1", "archive:album:1"),
            db.albumDao().listAll().map { it.id }.toSet()
        )
        assertEquals(3, db.trackDao().listAll().size)
        assertEquals(3, db.playEventDao().listAll().size)
        assertEquals(listOf("mine"), db.shelfDao().listAll().map { it.id })
    }

    @Test
    fun `a shelf called Spotify that still holds records is kept`() = runTest {
        // The name alone proves nothing: the owner may have filed his own records there, or
        // named a shelf that himself. Only a shelf the removal emptied is the sync's leftover.
        shelf("spotify-shelf", "Spotify")
        album("local", "spotify-shelf", sourceId = null)
        album("spotify:album:1", "spotify-shelf", sourceId = "spotify")

        cleanup.run()

        assertNotNull(db.albumDao().findById("local"))
        assertEquals(listOf("spotify-shelf"), db.shelfDao().listAll().map { it.id })
    }

    @Test
    fun `an empty shelf with any other name is not touched`() = runTest {
        shelf("empty", "For later")
        shelf("spotify-shelf", "Spotify")
        album("spotify:album:1", "spotify-shelf", sourceId = "spotify")

        cleanup.run()

        assertEquals(listOf("empty"), db.shelfDao().listAll().map { it.id })
    }

    @Test
    fun `the sign-in is erased and nothing else in preferences is`() = runTest {
        val prefs = context.getSharedPreferences("jawon", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putString("spotify_client_id", "abc123")
            putString("spotify_access_token", "access")
            putString("spotify_refresh_token", "refresh")
            putLong("spotify_expires_at", 42L)
            putString("server_url", "https://music.example")
        }

        cleanup.run()

        listOf(
            "spotify_client_id", "spotify_access_token", "spotify_refresh_token",
            "spotify_expires_at"
        ).forEach { assertFalse("$it should be gone", prefs.contains(it)) }
        assertEquals("https://music.example", prefs.getString("server_url", null))
    }

    @Test
    fun `a second run finds nothing, and a library without spotify is never touched`() =
        runTest {
            // It runs on every launch, so running is the common case and finding something is
            // the rare one.
            shelf("mine", "Unsorted")
            album("local", "mine", sourceId = null)

            assertEquals(0, cleanup.run())
            assertEquals(0, cleanup.run())

            assertEquals(listOf("local"), db.albumDao().getAll().first().map { it.id })
            assertNull(db.albumDao().findById("spotify:album:1"))
        }
}
