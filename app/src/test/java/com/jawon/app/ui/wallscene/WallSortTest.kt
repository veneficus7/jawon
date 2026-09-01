package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How records are filed along a shelf.
 *
 * The two things worth pinning are the ones a comparator gets wrong quietly. A sort that is
 * not *total* lets an unrelated database write rearrange a shelf, which reads as the wall
 * shuffling itself for no reason; and the filing rule has to match the one the crate flip's
 * dividers use, or a record sorts to one letter and is labelled with another.
 */
class WallSortTest {

    private fun album(
        id: String,
        title: String,
        artist: String = "Artist",
        year: Int? = 2000,
        slot: Int = 0,
        added: Long = 0L
    ) = Album(
        id = id, shelfId = "s", title = title, artist = artist, year = year,
        caseCoverUri = null, discFaceUri = null, spineHue = 0f, slotIndex = slot,
        playCount = 0, addedAt = added
    )

    private fun titles(list: List<Album>) = list.map { it.title }

    // ---- as filed --------------------------------------------------------------------

    @Test
    fun `as filed keeps the shelf's own order`() {
        val albums = listOf(
            album("c", "Third", slot = 2),
            album("a", "First", slot = 0),
            album("b", "Second", slot = 1)
        )
        assertEquals(
            listOf("First", "Second", "Third"),
            titles(WallSort.order(albums, WallSort.SHELF))
        )
    }

    // ---- alphabetical ----------------------------------------------------------------

    @Test
    fun `by album ignores leading articles`() {
        val albums = listOf(
            album("1", "The Bends"),
            album("2", "Amnesiac"),
            album("3", "A Love Supreme"),
            album("4", "Kid A")
        )
        assertEquals(
            listOf("Amnesiac", "The Bends", "Kid A", "A Love Supreme"),
            titles(WallSort.order(albums, WallSort.TITLE))
        )
    }

    @Test
    fun `the sort agrees with the divider the crate flip would show`() {
        // If these two rules ever diverge, a record files under one letter and is labelled
        // with another, and the rail stops being an index.
        val albums = listOf("The Bends", "A Love Supreme", "An Awesome Wave", "Kid A", "1999")
            .mapIndexed { i, t -> album("$i", t) }
        val ordered = WallSort.order(albums, WallSort.TITLE)
        val letters = ordered.map { WallLayout.initialOf(it.title) }
        assertEquals(
            "the letters are not in order, so the sort and the rail disagree",
            letters.sortedBy { if (it == '#') ' ' else it },
            letters
        )
    }

    @Test
    fun `by artist groups a discography and orders it by album`() {
        val albums = listOf(
            album("1", "Kid A", artist = "Radiohead"),
            album("2", "Blue", artist = "Joni Mitchell"),
            album("3", "Amnesiac", artist = "Radiohead")
        )
        assertEquals(
            listOf("Blue", "Amnesiac", "Kid A"),
            titles(WallSort.order(albums, WallSort.ARTIST))
        )
    }

    @Test
    fun `by artist ignores a leading article on the band name`() {
        val albums = listOf(
            album("1", "X", artist = "The Beatles"),
            album("2", "Y", artist = "Autechre")
        )
        assertEquals(listOf("Y", "X"), titles(WallSort.order(albums, WallSort.ARTIST)))
    }

    // ---- year ------------------------------------------------------------------------

    @Test
    fun `by year runs oldest first`() {
        val albums = listOf(
            album("1", "New", year = 2020),
            album("2", "Old", year = 1971),
            album("3", "Middle", year = 1994)
        )
        assertEquals(listOf("Old", "Middle", "New"), titles(WallSort.order(albums, WallSort.YEAR)))
    }

    @Test
    fun `an album with no year goes last, not to 1970`() {
        val albums = listOf(
            album("1", "Undated", year = null),
            album("2", "Ancient", year = 1955)
        )
        assertEquals(
            listOf("Ancient", "Undated"),
            titles(WallSort.order(albums, WallSort.YEAR))
        )
    }

    // ---- most played -----------------------------------------------------------------

    @Test
    fun `most played runs the loved ones first`() {
        val albums = listOf(
            album("a", "Barely"),
            album("b", "Loved"),
            album("c", "Never")
        )
        val listened = mapOf("a" to 60_000L, "b" to 40 * 3_600_000L)
        assertEquals(
            listOf("Loved", "Barely", "Never"),
            titles(WallSort.order(albums, WallSort.PLAYED, listened))
        )
    }

    @Test
    fun `never played albums do not vanish`() {
        val albums = listOf(album("a", "One"), album("b", "Two"))
        assertEquals(2, WallSort.order(albums, WallSort.PLAYED, emptyMap()).size)
    }

    // ---- recently added --------------------------------------------------------------

    @Test
    fun `recently added runs newest first`() {
        val albums = listOf(
            album("1", "Older", added = 1_000L),
            album("2", "Newest", added = 9_000L)
        )
        assertEquals(listOf("Newest", "Older"), titles(WallSort.order(albums, WallSort.ADDED)))
    }

    // ---- totality --------------------------------------------------------------------

    @Test
    fun `every order is total, so a shelf never rearranges itself`() {
        // Ties on the primary key everywhere: same year, same play time, same added-at. If a
        // comparator leaves those in input order, an unrelated write reshuffles the wall.
        val a = listOf(
            album("z", "Zebra", year = 1999, added = 5L),
            album("m", "Mango", year = 1999, added = 5L),
            album("a", "Apple", year = 1999, added = 5L)
        )
        val reversed = a.reversed()
        for (sort in WallSort.entries) {
            if (sort == WallSort.SHELF) continue // by definition follows the stored index
            assertEquals(
                "$sort is not a total order",
                titles(WallSort.order(a, sort)),
                titles(WallSort.order(reversed, sort))
            )
        }
    }

    @Test
    fun `no order ever loses or invents an album`() {
        val albums = (1..12).map { album("id$it", "Album $it", year = it) }
        for (sort in WallSort.entries) {
            val out = WallSort.order(albums, sort)
            assertEquals("$sort changed the count", albums.size, out.size)
            assertEquals("$sort changed the set", albums.map { it.id }.toSet(), out.map { it.id }.toSet())
        }
    }

    @Test
    fun `an empty shelf sorts to an empty shelf`() {
        for (sort in WallSort.entries) {
            assertTrue(WallSort.order(emptyList(), sort).isEmpty())
        }
    }

    // ---- the stored setting ------------------------------------------------------------

    @Test
    fun `only the alphabetical orders claim to be an index`() {
        assertTrue(WallSort.TITLE.isAlphabetical)
        assertTrue(WallSort.ARTIST.isAlphabetical)
        for (other in listOf(WallSort.SHELF, WallSort.YEAR, WallSort.PLAYED, WallSort.ADDED)) {
            assertTrue("$other should not claim to be an A-Z index", !other.isAlphabetical)
        }
    }

    @Test
    fun `an unknown stored order falls back to as filed`() {
        assertEquals(WallSort.SHELF, WallSort.fromName(null))
        assertEquals(WallSort.SHELF, WallSort.fromName("NONSENSE"))
        for (sort in WallSort.entries) assertEquals(sort, WallSort.fromName(sort.name))
    }
}
