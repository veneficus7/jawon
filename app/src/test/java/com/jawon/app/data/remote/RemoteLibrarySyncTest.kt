package com.jawon.app.data.remote

import com.jawon.app.data.local.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this file guards: **a sync updates what the server knows and touches nothing else.**
 *
 * Everything Jawon computes about a record — the patina on its case, its badges, its place in
 * the listening record — hangs off the album id and the play counters. A sync that minted a
 * fresh id, or that let `@Insert(REPLACE)` blank the counters, would not fail. It would render a
 * wall that had forgotten itself, on a phone whose owner had done nothing but press refresh.
 *
 * No Robolectric here: [RemoteLibrarySync.merge] and the two id functions are pure, which is
 * exactly why they were pulled out of the sync loop.
 */
class RemoteLibrarySyncTest {

    private val summary = RemoteAlbum(
        id = "al-1",
        title = "Kid A",
        artist = "Radiohead",
        year = 2000,
        coverArtId = "al-1",
        trackCount = 10
    )

    /** An album that has been on the wall a while and been listened to. */
    private val lived = Album(
        id = RemoteLibrarySync.albumIdFor("al-1"),
        shelfId = "shelf-the-user-moved-it-to",
        title = "Kid A",
        artist = "Radiohead",
        year = 2000,
        caseCoverUri = "/covers/kid-a.jpg",
        discFaceUri = "/covers/kid-a-disc.jpg",
        spineHue = 0.42f,
        slotIndex = 7,
        playCount = 31,
        addedAt = 1_700_000_000_000L,
        lastTrackIndex = 4,
        lastPositionMs = 96_000L,
        lastPlayedAt = 1_800_000_000_000L,
        sourceId = RemoteLibrarySync.SOURCE_ID,
        remoteId = "al-1"
    )

    // ---- ids ------------------------------------------------------------------------------

    @Test
    fun `an album gets the same id on every sync`() {
        // The single most important property in this file. A UUID here — the obvious thing to
        // write, and what the local importer does — would make every refresh insert a second
        // copy of every album and orphan the entire play history behind it.
        assertEquals(RemoteLibrarySync.albumIdFor("al-1"), RemoteLibrarySync.albumIdFor("al-1"))
        assertEquals(RemoteLibrarySync.trackIdFor("tr-1"), RemoteLibrarySync.trackIdFor("tr-1"))
    }

    @Test
    fun `different remote ids do not collide`() {
        assertNotEquals(RemoteLibrarySync.albumIdFor("al-1"), RemoteLibrarySync.albumIdFor("al-2"))
    }

    @Test
    fun `an album id and a track id with the same remote id are different rows`() {
        // Servers number albums and songs in separate spaces, so "1" can legitimately be both.
        // Without the discriminator in the middle, one would overwrite the other.
        assertNotEquals(RemoteLibrarySync.albumIdFor("1"), RemoteLibrarySync.trackIdFor("1"))
    }

    // ---- what a re-sync must not destroy ---------------------------------------------------

    @Test
    fun `re-syncing an album keeps everything the listening record depends on`() {
        val merged = RemoteLibrarySync.merge(
            previous = lived,
            summary = summary,
            albumId = lived.id,
            shelfId = "the-default-streaming-shelf",
            coverPath = lived.caseCoverUri,
            slotIndex = 99,
            now = 1_900_000_000_000L
        )

        assertEquals("the id must not change", lived.id, merged.id)
        assertEquals("play count is this phone's, not the server's", 31, merged.playCount)
        assertEquals(1_800_000_000_000L, merged.lastPlayedAt)
        assertEquals("where the record was left", 4, merged.lastTrackIndex)
        assertEquals(96_000L, merged.lastPositionMs)
        assertEquals("when it joined *this* library", 1_700_000_000_000L, merged.addedAt)
    }

    @Test
    fun `re-syncing keeps the arrangement the user made`() {
        val merged = RemoteLibrarySync.merge(
            previous = lived,
            summary = summary,
            albumId = lived.id,
            shelfId = "the-default-streaming-shelf",
            coverPath = lived.caseCoverUri,
            slotIndex = 99,
            now = 0L
        )

        // Moving a case to a shelf is a deliberate act. A refresh that dragged it back would
        // undo the user's filing every time the server was polled.
        assertEquals("shelf-the-user-moved-it-to", merged.shelfId)
        assertEquals(7, merged.slotIndex)
        assertEquals(0.42f, merged.spineHue, 0.0001f)
        // The server has no concept of a disc face; only Jawon does.
        assertEquals("/covers/kid-a-disc.jpg", merged.discFaceUri)
    }

    @Test
    fun `the server does win on the facts it actually knows`() {
        // The other half of the rule: this is a sync, not a museum. A record renamed or
        // re-tagged on the server should show its new name on the wall.
        val retagged = summary.copy(title = "Kid A Mnesia", artist = "Radiohead ", year = 2021)

        val merged = RemoteLibrarySync.merge(
            previous = lived,
            summary = retagged,
            albumId = lived.id,
            shelfId = "s",
            coverPath = lived.caseCoverUri,
            slotIndex = 0,
            now = 0L
        )

        assertEquals("Kid A Mnesia", merged.title)
        assertEquals(2021, merged.year)
    }

    // ---- a new album ----------------------------------------------------------------------

    @Test
    fun `a newly seen album starts clean and lands where it is put`() {
        val merged = RemoteLibrarySync.merge(
            previous = null,
            summary = summary,
            albumId = RemoteLibrarySync.albumIdFor("al-1"),
            shelfId = "the-default-streaming-shelf",
            coverPath = "/covers/new.jpg",
            slotIndex = 12,
            now = 1_900_000_000_000L
        )

        assertEquals("the-default-streaming-shelf", merged.shelfId)
        assertEquals(12, merged.slotIndex)
        assertEquals(0, merged.playCount)
        assertNull("never played is a real state, not zero", merged.lastPlayedAt)
        assertEquals(1_900_000_000_000L, merged.addedAt)
        // Unset, so CaseArtCache falls back to the cover's own dominant hue — usually a better
        // answer than anything picked from a slider.
        assertEquals(0f, merged.spineHue, 0.0001f)
    }

    @Test
    fun `every synced album is marked as coming from the server`() {
        val merged = RemoteLibrarySync.merge(
            previous = null,
            summary = summary,
            albumId = "any",
            shelfId = "s",
            coverPath = null,
            slotIndex = 0,
            now = 0L
        )

        // Without this, `AlbumDao.forSource` would not find the album on the next sync, which
        // would insert a duplicate — and the album would never be cleaned up when it left the
        // server, because nothing would know where it came from.
        assertEquals(RemoteLibrarySync.SOURCE_ID, merged.sourceId)
        assertEquals("al-1", merged.remoteId)
    }

    @Test
    fun `a local album is never marked as remote`() {
        // The inverse, asserted because it is what protects a pre-existing library: an album
        // with a null sourceId is on the phone, and `forSource` must never return it to a sync
        // that is entitled to delete what the server does not list.
        val local = lived.copy(sourceId = null, remoteId = null)
        assertNull(local.sourceId)
        assertTrue(local.remoteId == null)
    }
}
