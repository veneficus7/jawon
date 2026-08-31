package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The crate flip — filing letters, and the scrub that maps a thumb to a position on the wall.
 *
 * `WallLayout` is one of the four pure files `ENGINEERING.md` has listed as free to test and
 * untested since the project started. This covers the part of it the flip added.
 */
class WallLayoutFlipTest {

    private fun case(id: String, title: String, row: Int, col: Int) = CaseLayout(
        albumId = id,
        title = title,
        artist = "",
        storedHue = 0f,
        fallbackHue = 0f,
        coverPath = null,
        discFacePath = null,
        row = row,
        col = col,
        x = col * WallDimens.SLOT_PITCH,
        y = -row * WallDimens.ROW_PITCH
    )

    // ---- filing letters ----------------------------------------------------------

    @Test
    fun `files under its first letter, uppercased`() {
        assertEquals('K', WallLayout.initialOf("Kid A"))
        assertEquals('K', WallLayout.initialOf("kid a"))
    }

    @Test
    fun `leading articles do not file half a collection under T`() {
        assertEquals('B', WallLayout.initialOf("The Bends"))
        assertEquals('L', WallLayout.initialOf("A Love Supreme"))
        assertEquals('A', WallLayout.initialOf("An Awesome Wave"))
        // Case-insensitively, because tags are typed by people.
        assertEquals('W', WallLayout.initialOf("the Wall"))
    }

    @Test
    fun `a title that is only an article keeps its own letter`() {
        // "The" alone must not strip to nothing and fall through to '#'.
        assertEquals('T', WallLayout.initialOf("The"))
        assertEquals('A', WallLayout.initialOf("A"))
    }

    @Test
    fun `a word merely starting with an article is not stripped`() {
        assertEquals('T', WallLayout.initialOf("Theatre"))
        assertEquals('A', WallLayout.initialOf("Antichrist Superstar"))
    }

    @Test
    fun `numbers and symbols file under hash`() {
        assertEquals('#', WallLayout.initialOf("1999"))
        assertEquals('#', WallLayout.initialOf("!!!"))
        assertEquals('#', WallLayout.initialOf("...And Justice For All"))
    }

    @Test
    fun `blank and whitespace titles do not throw`() {
        assertEquals('#', WallLayout.initialOf(""))
        assertEquals('#', WallLayout.initialOf("   "))
    }

    @Test
    fun `leading and trailing space is ignored`() {
        assertEquals('B', WallLayout.initialOf("  The Bends  "))
    }

    // ---- the scrub ---------------------------------------------------------------

    private val wall = WallLayout(
        cases = listOf(
            case("d", "Delta", 1, 1),
            case("a", "Alpha", 0, 0),
            case("c", "Charlie", 1, 0),
            case("b", "Bravo", 0, 1)
        ),
        loaded = true
    )

    @Test
    fun `wall order is top row first, then left to right`() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            wall.casesInWallOrder.map { it.albumId }
        )
    }

    @Test
    fun `the ends of the rail reach the ends of the wall`() {
        assertEquals("a", wall.caseAtFraction(0f)?.albumId)
        assertEquals("d", wall.caseAtFraction(1f)?.albumId)
    }

    @Test
    fun `the scrub is monotonic`() {
        // The one thing a scrubber must never do is move backwards under a thumb moving
        // steadily forwards.
        var lastIndex = -1
        for (step in 0..200) {
            val f = step / 200f
            val landed = wall.caseAtFraction(f) ?: error("no case at $f")
            val index = wall.casesInWallOrder.indexOf(landed)
            assert(index >= lastIndex) { "went backwards at $f: $lastIndex -> $index" }
            lastIndex = index
        }
    }

    @Test
    fun `out of range fractions clamp rather than crash`() {
        assertEquals("a", wall.caseAtFraction(-4f)?.albumId)
        assertEquals("d", wall.caseAtFraction(9f)?.albumId)
        assertEquals("a", wall.caseAtFraction(Float.NEGATIVE_INFINITY)?.albumId)
    }

    @Test
    fun `an empty wall has nothing to land on`() {
        assertNull(WallLayout(loaded = true).caseAtFraction(0.5f))
    }

    @Test
    fun `a single album is landed on from anywhere along the rail`() {
        val one = WallLayout(cases = listOf(case("only", "Only", 0, 0)), loaded = true)
        val target = one.casesInWallOrder.single()
        assertSame(target, one.caseAtFraction(0f))
        assertSame(target, one.caseAtFraction(0.5f))
        assertSame(target, one.caseAtFraction(1f))
    }
}
