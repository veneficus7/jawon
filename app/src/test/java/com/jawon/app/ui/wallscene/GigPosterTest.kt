package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The parts of the poster wall that are arithmetic rather than taste.
 *
 * Where sheets hang, which albums get one, and how far off square each is taped. What a poster
 * *looks* like needs a device and a screenshot like everything else in this room; none of this
 * does, and all of it is the kind of thing that silently stops being true.
 */
class GigPosterTest {

    private fun case(id: String, wear: Float, cover: String? = "/covers/$id.jpg") = CaseLayout(
        albumId = id,
        title = "Title $id",
        artist = "Artist $id",
        storedHue = 0f,
        fallbackHue = 0.5f,
        coverPath = cover,
        discFacePath = null,
        row = 0,
        col = 0,
        x = 0f,
        y = 0f,
        wear = wear
    )

    private fun library(n: Int) = (1..n).map { case("a%02d".format(it), wear = 1f - it * 0.01f) }

    @Test
    fun `posters and frames never print the same album`() {
        val cases = library(20)
        val framed = WallDecor.chooseAlbums(cases).map { it.albumId }.toSet()
        val postered = WallDecor.choosePosters(cases).map { it.albumId }.toSet()

        assertTrue("both should be populated", framed.isNotEmpty() && postered.isNotEmpty())
        assertTrue(
            "a wall of six records printed twice is not a shop wall: $framed vs $postered",
            framed.intersect(postered).isEmpty()
        )
    }

    @Test
    fun `a library too small to fill both falls back to repeating rather than to a bare wall`() {
        val postered = WallDecor.choosePosters(library(4))
        assertEquals(4, postered.size)
    }

    @Test
    fun `an album with no cover is never chosen for either`() {
        // A poster can be typographic, but the chooser cannot tell a missing file from a
        // missing column, and a wall of ink blocks is not what anybody asked for.
        val cases = listOf(case("a", 1f, cover = null), case("b", 0.9f), case("c", 0.8f, cover = ""))
        assertEquals(listOf("b"), WallDecor.chooseAlbums(cases).map { it.albumId })
        assertTrue(WallDecor.choosePosters(cases).none { it.coverPath.isNullOrBlank() })
    }

    @Test
    fun `posters hang between the frames, not on top of them`() {
        val left = -2.4f
        val right = 2.4f
        val frames = WallDecor.slots(left, right)
        val sheets = GigPoster.slots(left, right)
        assertTrue("both should place something", frames.isNotEmpty() && sheets.isNotEmpty())

        // Half a pitch offset, so nothing shares an x with a frame.
        frames.forEach { f ->
            sheets.forEach { s ->
                assertTrue(
                    "a sheet at $s overlaps a frame at $f",
                    abs(s - f) > (WallDecor.OUTER_M + GigPoster.WIDTH_M) / 2f
                )
            }
        }
    }

    @Test
    fun `nothing hangs where the neon sign is`() {
        GigPoster.slots(-2.4f, 2.4f).forEach {
            assertTrue("a sheet at $it is over the sign", abs(it) >= WallDecor.CLEAR_OF_SIGN)
        }
    }

    @Test
    fun `every sheet stays inside the wall`() {
        val left = -1.1f
        val right = 1.1f
        GigPoster.slots(left, right).forEach {
            assertTrue("sheet at $it hangs off the left", it - GigPoster.WIDTH_M / 2f >= left)
            assertTrue("sheet at $it hangs off the right", it + GigPoster.WIDTH_M / 2f <= right)
        }
    }

    @Test
    fun `the tilt is stable per album, signed both ways, and small`() {
        // Stable because the wall is rebuilt on every recomposition and a sheet that twitched
        // between frames would be the most obvious thing on screen.
        assertEquals(GigPoster.tiltFor("album-17"), GigPoster.tiltFor("album-17"), 0f)
        assertNotEquals(GigPoster.tiltFor("album-17"), GigPoster.tiltFor("album-18"))

        val tilts = (1..80).map { GigPoster.tiltFor("album-$it") }
        assertTrue("some should lean left", tilts.any { it < -0.2f })
        assertTrue("some should lean right", tilts.any { it > 0.2f })
        assertTrue(
            "tape, not a hinge: ${tilts.maxOf { abs(it) }}",
            tilts.all { abs(it) <= GigPoster.MAX_TILT_DEG }
        )
    }

    @Test
    fun `a sheet is portrait and taller than a framed sleeve`() {
        assertTrue(GigPoster.HEIGHT_M > GigPoster.WIDTH_M)
        assertTrue(
            "a poster is the bigger thing on a shop wall",
            GigPoster.HEIGHT_M > WallDecor.OUTER_M
        )
    }

    @Test
    fun `the ink carries the album's colour without becoming it`() {
        // Every poster on a wall in a different saturated colour stops the brick being a room.
        // The ink must move with the hue and must not arrive at it.
        val warm = GigPoster.inkFor(0.08f)
        val cool = GigPoster.inkFor(0.58f)
        assertNotEquals("the ink should follow the record", warm, cool)

        listOf(warm, cool).forEach { ink ->
            val r = (ink shr 16) and 0xFF
            val g = (ink shr 8) and 0xFF
            val b = ink and 0xFF
            assertTrue("ink at rgb($r,$g,$b) is too pale to print with", (r + g + b) / 3 < 170)
            assertFalse("ink must not be pure black either", r + g + b == 0)
        }
    }
}
