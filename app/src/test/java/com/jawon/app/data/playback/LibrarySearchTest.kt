package com.jawon.app.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Play ⟨something⟩ on Jawon": what a spoken request means in this library. */
class LibrarySearchTest {

    private val shelves = listOf(
        shelf("rock", "ALT ROCK", order = 0),
        shelf("hiphop", "HIP HOP", order = 1)
    )
    private val albums = listOf(
        album("loveless", "rock", "Loveless", "My Bloody Valentine", slot = 0, lastPlayedAt = 100),
        album("ok", "rock", "OK Computer", "Radiohead", slot = 1, lastPlayedAt = 300),
        album("bends", "rock", "The Bends", "Radiohead", slot = 2, lastPlayedAt = 200),
        album("madvillainy", "hiphop", "Madvillainy", "Madvillain", slot = 0, lastPlayedAt = 50),
        album("donuts", "hiphop", "Donuts", "J Dilla", slot = 1),
        album("blue", "rock", "Blue", "Joni Mitchell", slot = 3)
    )
    private val tracks = listOf(
        track("only", "loveless", 1, "Only Shallow"),
        track("sometimes", "loveless", 2, "Sometimes"),
        track("airbag", "ok", 1, "Airbag"),
        track("paranoid", "ok", 2, "Paranoid Android"),
        track("fake", "bends", 1, "Fake Plastic Trees"),
        track("accordion", "madvillainy", 1, "Accordion"),
        track("blue-song", "blue", 5, "Blue"),
        track("workinonit", "donuts", 1, "Workinonit")
    )

    private fun pick(query: String?, hints: LibrarySearch.Hints = LibrarySearch.Hints()) =
        LibrarySearch.pick(query, hints, shelves, albums, tracks)

    // ---- A record by name ---------------------------------------------------------------------

    @Test
    fun `a record's name plays that record from the top`() {
        assertEquals(LibrarySearch.Pick("loveless"), pick("Loveless"))
        assertEquals(LibrarySearch.Pick("ok"), pick("ok computer"))
    }

    @Test
    fun `speech is not typing - split words, a dropped article, punctuation gone`() {
        assertEquals("loveless", pick("love less")?.albumId)
        assertEquals("bends", pick("bends")?.albumId)
        assertEquals("ok", pick("O.K. Computer!")?.albumId)
        assertEquals("madvillainy", pick("mad villainy")?.albumId)
    }

    @Test
    fun `the words a request arrives wrapped in are not part of the name`() {
        assertEquals("loveless", pick("the album Loveless")?.albumId)
        assertEquals("ok", pick("OK Computer album")?.albumId)
    }

    @Test
    fun `a record and its artist said in one breath, either way round`() {
        assertEquals("loveless", pick("loveless my bloody valentine")?.albumId)
        assertEquals("bends", pick("radiohead the bends")?.albumId)
        assertEquals(LibrarySearch.Pick("bends"), pick("The Bends by Radiohead"))
    }

    // ---- A song --------------------------------------------------------------------------------

    @Test
    fun `a song's name plays its record from that song`() {
        assertEquals(LibrarySearch.Pick("ok", trackId = "paranoid"), pick("Paranoid Android"))
        assertEquals(LibrarySearch.Pick("bends", trackId = "fake"), pick("fake plastic trees by radiohead"))
    }

    @Test
    fun `a record beats a song of the same name - the album is the unit`() {
        assertEquals(LibrarySearch.Pick("blue"), pick("Blue"))
    }

    // ---- An artist, a shelf --------------------------------------------------------------------

    @Test
    fun `an artist's name plays the record of theirs that was on most recently`() {
        assertEquals(LibrarySearch.Pick("ok"), pick("Radiohead"))
        // Never played: the first of theirs by title.
        assertEquals(LibrarySearch.Pick("donuts"), pick("J Dilla"))
    }

    @Test
    fun `a shelf's name plays something off it, and not the same thing every time`() {
        // Donuts has never been played, so it goes first.
        assertEquals(LibrarySearch.Pick("donuts"), pick("hip hop"))
        // With everything played, it is the one played longest ago.
        val played = albums.map { if (it.id == "donuts") it.copy(lastPlayedAt = 900) else it }
        assertEquals(
            "madvillainy",
            LibrarySearch.pick("hip hop", LibrarySearch.Hints(), shelves, played, tracks)?.albumId
        )
    }

    // ---- What the assistant sorted out ---------------------------------------------------------

    @Test
    fun `told which word is the album, the album is what plays`() {
        assertEquals(
            LibrarySearch.Pick("blue"),
            pick("blue joni mitchell", LibrarySearch.Hints(album = "Blue", artist = "Joni Mitchell"))
        )
    }

    @Test
    fun `told which word is the song, its record plays from that song`() {
        assertEquals(
            LibrarySearch.Pick("blue", trackId = "blue-song"),
            pick("blue", LibrarySearch.Hints(title = "Blue", artist = "Joni Mitchell"))
        )
    }

    @Test
    fun `told only the artist, a record of theirs plays`() {
        assertEquals(LibrarySearch.Pick("ok"), pick(null, LibrarySearch.Hints(artist = "Radiohead")))
    }

    // ---- No words at all -----------------------------------------------------------------------

    @Test
    fun `play music, with nothing else said, is the record that was on - where it stopped`() {
        assertEquals(LibrarySearch.Pick("ok", resume = true), pick(""))
        assertEquals(LibrarySearch.Pick("ok", resume = true), pick(null))
    }

    @Test
    fun `with nothing ever played it is the first record on the wall`() {
        val fresh = albums.map { it.copy(lastPlayedAt = null) }
        assertEquals(
            LibrarySearch.Pick("loveless"),
            LibrarySearch.pick(null, LibrarySearch.Hints(), shelves, fresh, tracks)
        )
    }

    // ---- Nothing to find -----------------------------------------------------------------------

    @Test
    fun `something this library does not hold finds nothing, rather than something else`() {
        assertNull(pick("Rumours"))
        assertNull(pick("Fleetwood Mac"))
        assertNull(pick(null, LibrarySearch.Hints(album = "Rumours")))
    }

    @Test
    fun `an empty library finds nothing, whatever is asked`() {
        assertNull(LibrarySearch.pick("Loveless", LibrarySearch.Hints(), shelves, emptyList(), emptyList()))
        assertNull(LibrarySearch.pick(null, LibrarySearch.Hints(), shelves, emptyList(), emptyList()))
    }

    // ---- A list of what was found --------------------------------------------------------------

    @Test
    fun `a search lists records before songs, the closest first`() {
        val found = LibrarySearch.results("blue", albums, tracks)
        assertEquals("blue", found.first().album.id)
        assertNull(found.first().track)
        assertTrue(found.any { it.track?.id == "blue-song" })
        // Radiohead finds both of their records, and no songs by that name.
        assertEquals(setOf("ok", "bends"), LibrarySearch.results("radiohead", albums, tracks).map { it.album.id }.toSet())
        assertEquals(emptyList<LibrarySearch.Found>(), LibrarySearch.results("  ", albums, tracks))
    }

    // ---- The comparison itself -----------------------------------------------------------------

    @Test
    fun `text is compared without case, accents, punctuation or a leading article`() {
        assertEquals("sigur ros", LibrarySearch.clean("Sigur Rós"))
        assertEquals("bends", LibrarySearch.clean("The Bends"))
        assertEquals("ok computer", LibrarySearch.clean("  O.K.   Computer "))
        assertEquals("simon and garfunkel", LibrarySearch.clean("Simon & Garfunkel"))
    }

    @Test
    fun `the closer the words, the higher the score, and nothing in common scores nothing`() {
        val wanted = LibrarySearch.clean("computer")
        val exact = LibrarySearch.score("Computer", wanted)
        val word = LibrarySearch.score("OK Computer", wanted)
        val inside = LibrarySearch.score("Microcomputers", wanted)
        assertEquals(100, exact)
        assertTrue(exact > word && word > inside && inside >= LibrarySearch.FLOOR)
        assertEquals(0, LibrarySearch.score("Loveless", wanted))
    }
}
