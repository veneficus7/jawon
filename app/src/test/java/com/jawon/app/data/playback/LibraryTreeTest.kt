package com.jawon.app.data.playback

import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library as a car browses it: shelves, the records on them, the tracks on a record. */
class LibraryTreeTest {

    private val shelves = listOf(
        shelf("rock", "ALT ROCK", order = 1),
        shelf("pop", "POP", order = 0),
        shelf("empty", "TO SORT", order = 2)
    )
    private val albums = listOf(
        album("loveless", "rock", "Loveless", "My Bloody Valentine", slot = 1, lastPlayedAt = 300),
        album("ok", "rock", "OK Computer", "Radiohead", slot = 0, lastPlayedAt = 100),
        album("the-wall", "rock", "The Wall", "Pink Floyd", slot = 2, cover = null),
        album("abba", "pop", "Arrival", "ABBA", slot = 0, lastPlayedAt = 200)
    )

    @Test
    fun `the root offers what was on lately, the shelves and every record`() {
        val root = children(LibraryTree.Node.Root)
        assertEquals(
            listOf(LibraryTree.RECENT, LibraryTree.SHELVES, LibraryTree.ALBUMS),
            root.map { it.mediaId }
        )
        // What was on last is the face of "Recently played".
        assertEquals("loveless", root.first().coverOf)
    }

    @Test
    fun `nothing played yet means no recently played, and one shelf means no shelves`() {
        val unplayed = albums.filter { it.shelfId == "pop" }.map { it.copy(lastPlayedAt = null) }
        val root = LibraryTree.children(LibraryTree.Node.Root, shelves, unplayed)!!
        assertEquals(listOf(LibraryTree.ALBUMS), root.map { it.mediaId })
    }

    @Test
    fun `an empty library has nothing to offer, and says so with an empty root`() {
        assertEquals(emptyList<LibraryTree.Entry>(), LibraryTree.children(LibraryTree.Node.Root, shelves, emptyList()))
    }

    @Test
    fun `shelves come in the room's order, and an empty one is not offered`() {
        val list = children(LibraryTree.Node.Shelves)
        assertEquals(listOf("POP", "ALT ROCK"), list.map { it.title })
        assertEquals(listOf("1 record", "3 records"), list.map { it.subtitle })
        // A shelf wears the cover of the first record on it that has one.
        assertEquals("ok", list[1].coverOf)
    }

    @Test
    fun `a shelf's records are in the order they stand on it`() {
        val on = children(LibraryTree.Node.OfShelf("rock"))
        assertEquals(listOf("OK Computer", "Loveless", "The Wall"), on.map { it.title })
        assertEquals("Radiohead", on.first().subtitle)
        assertTrue(on.all { it.kind == LibraryTree.Kind.ALBUM })
    }

    @Test
    fun `every record is filed by title, without its article`() {
        assertEquals(
            listOf("Arrival", "Loveless", "OK Computer", "The Wall"),
            children(LibraryTree.Node.Albums).map { it.title }
        )
    }

    @Test
    fun `recently played is newest first and only what has been played`() {
        assertEquals(
            listOf("Loveless", "Arrival", "OK Computer"),
            children(LibraryTree.Node.Recent).map { it.title }
        )
        val many = (1..30).map { album("a$it", "rock", "Album $it", "X", slot = it, lastPlayedAt = it.toLong()) }
        assertEquals(LibraryTree.RECENT_LIMIT, LibraryTree.children(LibraryTree.Node.Recent, shelves, many)!!.size)
    }

    @Test
    fun `a record opens onto its tracks, each under the id the queue knows it by`() {
        val tracks = listOf(
            track("t1", "loveless", 1, "Only Shallow"),
            track("t2", "loveless", 2, "Loomer", artist = "Someone Else")
        )
        val list = LibraryTree.children(LibraryTree.Node.OfAlbum("loveless"), shelves, albums, tracks)!!
        assertEquals(listOf("t1", "t2"), list.map { it.mediaId })
        // The track's own credit where it has one, the record's where it does not.
        assertEquals(listOf("My Bloody Valentine", "Someone Else"), list.map { it.subtitle })
        assertTrue(list.all { it.kind == LibraryTree.Kind.TRACK && it.albumTitle == "Loveless" && it.coverOf == "loveless" })
    }

    @Test
    fun `a record with no cover offers none, rather than an address that opens nothing`() {
        val wall = children(LibraryTree.Node.OfShelf("rock")).first { it.title == "The Wall" }
        assertNull(wall.coverOf)
    }

    @Test
    fun `ids go out and come back as the same node`() {
        assertEquals(LibraryTree.Node.Root, LibraryTree.parse(LibraryTree.ROOT))
        assertEquals(LibraryTree.Node.Recent, LibraryTree.parse(LibraryTree.RECENT))
        assertEquals(LibraryTree.Node.Shelves, LibraryTree.parse(LibraryTree.SHELVES))
        assertEquals(LibraryTree.Node.Albums, LibraryTree.parse(LibraryTree.ALBUMS))
        assertEquals(LibraryTree.Node.OfShelf("rock"), LibraryTree.parse(LibraryTree.shelfNode("rock")))
        assertEquals(LibraryTree.Node.OfAlbum("loveless"), LibraryTree.parse(LibraryTree.albumNode("loveless")))
        // Anything else is a track's own id, untouched.
        assertEquals(LibraryTree.Node.OfTrack("4f1c-uuid"), LibraryTree.parse("4f1c-uuid"))
    }

    @Test
    fun `every entry the tree offers can be asked for again by its id`() {
        val tracks = listOf(track("t1", "loveless", 1, "Only Shallow"))
        val offered = children(LibraryTree.Node.Root) + children(LibraryTree.Node.Shelves) +
            children(LibraryTree.Node.Albums) + children(LibraryTree.Node.Recent)
        for (entry in offered) {
            val again = LibraryTree.entry(LibraryTree.parse(entry.mediaId), shelves, albums)
            assertEquals(entry.title, again?.title)
        }
        assertNotNull(LibraryTree.entry(LibraryTree.Node.OfTrack("t1"), shelves, albums, tracks.first()))
    }

    @Test
    fun `a node that is not there has no children - a shelf since deleted, a record since removed`() {
        assertNull(LibraryTree.children(LibraryTree.Node.OfShelf("gone"), shelves, albums))
        assertNull(LibraryTree.children(LibraryTree.Node.OfAlbum("gone"), shelves, albums))
        assertNull(LibraryTree.children(LibraryTree.Node.OfTrack("t1"), shelves, albums))
    }

    private fun children(node: LibraryTree.Node) = LibraryTree.children(node, shelves, albums)!!
}

internal fun shelf(id: String, name: String, order: Int) =
    Shelf(id = id, name = name, order = order, accentHue = 0f, createdAt = 0L)

internal fun album(
    id: String,
    shelfId: String,
    title: String,
    artist: String,
    slot: Int = 0,
    lastPlayedAt: Long? = null,
    cover: String? = "/covers/$id.jpg",
    lastTrackIndex: Int = 0,
    lastPositionMs: Long = 0L
) = Album(
    id = id,
    shelfId = shelfId,
    title = title,
    artist = artist,
    year = null,
    caseCoverUri = cover,
    discFaceUri = null,
    spineHue = 0f,
    slotIndex = slot,
    playCount = 0,
    addedAt = 0L,
    lastTrackIndex = lastTrackIndex,
    lastPositionMs = lastPositionMs,
    lastPlayedAt = lastPlayedAt
)

internal fun track(id: String, albumId: String, number: Int, title: String, artist: String? = null) = Track(
    id = id,
    albumId = albumId,
    trackNumber = number,
    title = title,
    artist = artist,
    durationMs = 200_000L,
    fileUri = "content://media/$id",
    mimeType = "audio/flac",
    sampleRateHz = 44_100,
    bitDepth = 16,
    bitrateBps = 900_000
)
