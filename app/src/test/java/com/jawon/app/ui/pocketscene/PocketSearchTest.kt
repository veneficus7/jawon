package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketSearchTest {

    private val library = PocketLibrary(
        albums = listOf(
            PocketLibrary.Album("a1", "Abbey Road", "The Beatles", null),
            PocketLibrary.Album("a2", "Because the Night", "Patti Smith", null),
            PocketLibrary.Album("a3", "In Rainbows", "Radiohead", null),
            PocketLibrary.Album("a4", "Kid A", "Radiohead", null),
            PocketLibrary.Album("a5", "Blue", "Joni Mitchell", null)
        )
    )

    private fun labels(q: String) = PocketSearch.results(q, library).map { it.label }

    @Test
    fun `nothing typed finds nothing`() {
        assertTrue(PocketSearch.results("", library).isEmpty())
        assertTrue(PocketSearch.results("   ", library).isEmpty())
    }

    @Test
    fun `an album is found by its title or its artist, and an artist on its own row`() {
        val found = PocketSearch.results("radio", library)
        assertEquals(listOf("In Rainbows · Radiohead", "Kid A · Radiohead", "Radiohead"), found.map { it.label })
        assertEquals(RowAction.Open(MenuKey.Album("a3")), found.first().action)
        assertEquals(RowAction.Open(MenuKey.Artist("Radiohead")), found.last().action)
        assertEquals("Artist", found.last().value)
    }

    @Test
    fun `a word starting with it ranks above one only containing it, and The is set aside`() {
        // "be": The Beatles (sorted as Beatles) starts with it; Because starts with it too, and
        // both come before anything that merely contains "be".
        val found = labels("be")
        assertEquals("Abbey Road · The Beatles", found[0])
        assertEquals("Because the Night · Patti Smith", found[1])
    }

    @Test
    fun `typing, a space and taking a letter back`() {
        var q = ""
        q = PocketSearch.type(q, "K")
        q = PocketSearch.type(q, "I")
        q = PocketSearch.type(q, PocketSearch.SPACE)
        q = PocketSearch.type(q, PocketSearch.SPACE) // never two spaces
        assertEquals("KI ", q)
        q = PocketSearch.type(q, PocketSearch.DELETE)
        assertEquals("KI", q)
        assertEquals("", PocketSearch.type("", PocketSearch.DELETE))
        assertEquals("", PocketSearch.type("", PocketSearch.SPACE)) // no leading space
    }

    @Test
    fun `the strip is the alphabet, the digits, a space and delete`() {
        assertEquals("A", PocketSearch.LETTERS.first())
        assertEquals(PocketSearch.DELETE, PocketSearch.LETTERS.last())
        assertEquals(26 + 10 + 2, PocketSearch.LETTERS.size)
    }
}
