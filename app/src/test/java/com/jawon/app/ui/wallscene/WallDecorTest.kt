package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Which records get framed on the wall, and where the frames may hang (`WallDecor`).
 *
 * Both are decisions the owner will notice being wrong — a blank frame, a frame over the neon
 * sign, a wall that reshuffles itself every launch — and both are pure arithmetic over the
 * layout, so they are settled here rather than on his phone.
 */
class WallDecorTest {

    private fun case(id: String, wear: Float, cover: String? = "/covers/$id.jpg") = CaseLayout(
        albumId = id,
        title = id,
        artist = "artist",
        storedHue = 0f,
        fallbackHue = 0f,
        coverPath = cover,
        discFacePath = null,
        row = 0,
        col = 0,
        x = 0f,
        y = 0f,
        wear = wear
    )

    @Test
    fun `the records he plays are the ones framed`() {
        val chosen = WallDecor.chooseAlbums(
            listOf(case("quiet", 0.05f), case("favourite", 0.9f), case("often", 0.5f)),
            limit = 2
        )
        assertEquals(listOf("favourite", "often"), chosen.map { it.albumId })
    }

    @Test
    fun `an album with no cover is skipped, not framed blank`() {
        val chosen = WallDecor.chooseAlbums(
            listOf(case("no-art", 1f, cover = null), case("blank", 0.8f, cover = "  "), case("art", 0.2f))
        )
        assertEquals(listOf("art"), chosen.map { it.albumId })
    }

    @Test
    fun `the same library hangs the same wall every launch`() {
        // Ties break on the album id, so an unplayed library is not reshuffled on each start.
        val fresh = listOf(case("c", 0f), case("a", 0f), case("b", 0f))
        assertEquals(
            WallDecor.chooseAlbums(fresh).map { it.albumId },
            WallDecor.chooseAlbums(fresh.reversed()).map { it.albumId }
        )
        assertEquals(listOf("a", "b", "c"), WallDecor.chooseAlbums(fresh).map { it.albumId })
    }

    @Test
    fun `one album cannot take two frames in a row of one`() {
        val twice = listOf(case("same", 0.4f), case("same", 0.4f), case("other", 0.1f))
        assertEquals(listOf("same", "other"), WallDecor.chooseAlbums(twice).map { it.albumId })
    }

    @Test
    fun `no frame hangs over the neon sign`() {
        WallDecor.slots(-3f, 3f).forEach {
            assertTrue("a frame at $it is over the sign", abs(it) >= WallDecor.CLEAR_OF_SIGN)
        }
    }

    @Test
    fun `no frame hangs off the end of the wall`() {
        val left = -1.4f
        val right = 1.1f
        WallDecor.slots(left, right).forEach {
            assertTrue(it - WallDecor.OUTER_M / 2f >= left)
            assertTrue(it + WallDecor.OUTER_M / 2f <= right)
        }
    }

    @Test
    fun `frames hang a frame's width apart, at least`() {
        val slots = WallDecor.slots(-3f, 3f).sorted()
        slots.zipWithNext { a, b ->
            assertTrue("frames at $a and $b overlap", b - a >= WallDecor.OUTER_M)
        }
        assertTrue("nothing fits on a wall this size", slots.size >= 4)
    }
}
