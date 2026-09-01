package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding a record by typing it. Ranking is the part that is easy to get subtly wrong. */
class WallSearchTest {

    private fun case(title: String, artist: String = "Someone") = CaseLayout(
        albumId = title, title = title, artist = artist,
        storedHue = 0f, fallbackHue = 0f, coverPath = null, discFacePath = null,
        row = 0, col = 0, x = 0f, y = 0f
    )

    private val shelf = listOf(
        case("Kid A", "Radiohead"),
        case("The Kids Are Alright", "The Who"),
        case("Amnesiac", "Radiohead"),
        case("Blue", "Joni Mitchell"),
        case("Kind of Blue", "Miles Davis")
    )

    private fun titles(q: String) = WallSearch.rank(shelf, q).map { it.title }

    @Test
    fun `an empty query leaves the wall alone`() {
        assertEquals(shelf, WallSearch.rank(shelf, ""))
        assertEquals(shelf, WallSearch.rank(shelf, "   "))
    }

    @Test
    fun `a title that starts with the query wins over one that merely contains it`() {
        val out = titles("kid")
        assertEquals("Kid A", out.first())
        assertTrue("the other match should still be there", out.contains("The Kids Are Alright"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(titles("kid"), titles("KID"))
        assertEquals(titles("kid"), titles("KiD"))
    }

    @Test
    fun `a leading article on the record does not hide it`() {
        // "The Kids Are Alright" should be findable by typing what you actually remember.
        assertTrue(titles("kids").contains("The Kids Are Alright"))
    }

    @Test
    fun `typing the article still finds the record`() {
        assertTrue(titles("the kids").contains("The Kids Are Alright"))
    }

    @Test
    fun `an artist match is found but ranks below a title match`() {
        val out = titles("blue")
        // "Blue" the album starts with it; "Kind of Blue" only contains it.
        assertEquals("Blue", out.first())
        assertTrue(out.contains("Kind of Blue"))
    }

    @Test
    fun `searching an artist returns their records`() {
        val out = titles("radiohead")
        assertTrue(out.contains("Kid A"))
        assertTrue(out.contains("Amnesiac"))
        assertTrue("should not drag in unrelated records", !out.contains("Blue"))
    }

    @Test
    fun `no match returns nothing rather than everything`() {
        assertTrue(WallSearch.rank(shelf, "zzzzz").isEmpty())
    }

    @Test
    fun `equal ranks keep the wall's own order`() {
        // Both are artist-prefix matches; the one earlier on the wall stays earlier, so
        // searching never reshuffles more than it must.
        val two = listOf(case("Second", "Aphex Twin"), case("First", "Aphex Twin"))
        assertEquals(listOf("Second", "First"), WallSearch.rank(two, "aphex").map { it.title })
    }

    @Test
    fun `best is the top of the ranking`() {
        assertEquals("Kid A", WallSearch.best(shelf, "kid")?.title)
    }

    @Test
    fun `best refuses to answer a blank query`() {
        // Otherwise pressing go on an empty box jumps the wall to whatever is first.
        assertNull(WallSearch.best(shelf, ""))
        assertNull(WallSearch.best(shelf, "   "))
    }

    @Test
    fun `an empty library searches to nothing without throwing`() {
        assertTrue(WallSearch.rank(emptyList(), "anything").isEmpty())
        assertNull(WallSearch.best(emptyList(), "anything"))
    }

    @Test
    fun `surrounding whitespace does not change the result`() {
        assertEquals(titles("kid"), titles("   kid   "))
    }
}
