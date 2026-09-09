package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The wall's and floor's surface, where the arithmetic is.
 *
 * The *look* of a mottled wall can only be judged on a device. What can be judged here is that
 * the texture does not change the surface's overall brightness, that it is the same on every
 * launch, that its cells come out square however wide the library has grown, and that it never
 * produces a value that would show as a black or blown texel. Each of those is a visible bug and
 * none of them needs a screen to find.
 */
class RoomSurfaceTest {

    private val px = 64

    // ---- The field itself ---------------------------------------------------------------

    @Test
    fun `the mottle does not change how bright the surface is`() {
        // **The property that makes this safe to add to a tuned room.** Every lamp intensity in
        // RoomLighting was found on the device against a flat wall of a known colour. If the
        // texture's mean were not 1, swapping it in would silently re-expose the whole room and
        // every one of those numbers would be wrong.
        val field = RoomSurface.mottle(px, 6, 6, RoomSurface.WALL_CONTRAST, seed = 1)

        val mean = field.average().toFloat()

        assertEquals(1f, mean, 0.01f)
    }

    @Test
    fun `no texel is ever darker or brighter than the contrast allows`() {
        val contrast = RoomSurface.WALL_CONTRAST
        val field = RoomSurface.mottle(px, 6, 6, contrast, seed = 2)

        for (v in field) {
            assertTrue("$v outside 1 +/- $contrast", abs(v - 1f) <= contrast + 1e-5f)
        }
    }

    @Test
    fun `the wall is the same wall on every launch`() {
        // A room whose stains moved between sessions would be wrong in a way nobody could name.
        // It is also what makes the rest of this file possible to write.
        val a = RoomSurface.mottle(px, 6, 6, 0.1f, seed = 7)
        val b = RoomSurface.mottle(px, 6, 6, 0.1f, seed = 7)

        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `different seeds give different surfaces`() {
        // The wall and the floor use different seeds on purpose: they meet along a line, and a
        // pattern that continued across that join would read as one folded surface.
        val wall = RoomSurface.mottle(px, 6, 6, 0.1f, seed = 1)
        val floor = RoomSurface.mottle(px, 6, 6, 0.1f, seed = 2)

        assertNotEquals(
            "the two surfaces are identical",
            true,
            wall.contentEquals(floor)
        )
    }

    @Test
    fun `the field actually varies`() {
        // A noise function that returned a constant would pass the mean and bounds tests above
        // while leaving the wall exactly as flat as it was — which is the entire problem this
        // file exists to fix, so it is worth an assertion of its own.
        val field = RoomSurface.mottle(px, 6, 6, RoomSurface.WALL_CONTRAST, seed = 3)

        val spread = field.max() - field.min()

        assertTrue("spread $spread", spread > RoomSurface.WALL_CONTRAST)
    }

    @Test
    fun `a zero contrast is exactly flat rather than nearly flat`() {
        val field = RoomSurface.mottle(px, 6, 6, 0f, seed = 4)

        for (v in field) assertEquals(1f, v, 0f)
    }

    @Test
    fun `nonsense contrast does not produce nonsense texels`() {
        // A NaN reaching the bitmap would be a black or transparent patch on the wall, and a
        // negative contrast should read as its magnitude rather than inverting the field.
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -0.1f)) {
            val field = RoomSurface.mottle(px, 6, 6, bad, seed = 5)
            for (v in field) {
                assertTrue("contrast $bad gave $v", v.isFinite() && v > 0f)
            }
        }
    }

    @Test
    fun `the field is the size it was asked for`() {
        assertEquals(px * px, RoomSurface.mottle(px, 4, 4, 0.1f, seed = 6).size)
    }

    // ---- Cell counts, which is what stops the stretch smearing ---------------------------

    @Test
    fun `a square surface gets a square cell grid`() {
        val (across, down) = RoomSurface.cellsFor(1f)

        assertEquals(across, down)
        assertEquals(RoomSurface.CELLS_SHORT_SIDE, down)
    }

    @Test
    fun `a wide surface gets proportionally more cells across`() {
        // **The reason this function exists.** The bitmap is square in pixels but the panel is
        // not, and the stock material has no UV scale — so a square cell grid on a panel four
        // times wider than tall arrives as cells four times wider than tall. Scaling the count
        // along the long axis is what cancels it.
        val (across, down) = RoomSurface.cellsFor(4f)

        assertEquals(RoomSurface.CELLS_SHORT_SIDE, down)
        assertEquals(RoomSurface.CELLS_SHORT_SIDE * 4, across)
    }

    @Test
    fun `a tall surface gets proportionally more cells down`() {
        val (across, down) = RoomSurface.cellsFor(0.25f)

        assertEquals(RoomSurface.CELLS_SHORT_SIDE, across)
        assertEquals(RoomSurface.CELLS_SHORT_SIDE * 4, down)
    }

    @Test
    fun `cells stay roughly square at every aspect a library can reach`() {
        // The real invariant, stated directly rather than through the two cases above. A library
        // can be one album wide or four hundred, so this has to hold across orders of magnitude
        // — up to the point the cap deliberately takes over.
        for (aspect in listOf(0.1f, 0.5f, 1f, 2f, 5f, 7.9f)) {
            val (across, down) = RoomSurface.cellsFor(aspect)
            val cellAspect = (aspect / across) * down

            assertTrue("aspect $aspect gave cell aspect $cellAspect", abs(cellAspect - 1f) < 0.2f)
        }
    }

    @Test
    fun `an absurd aspect is capped rather than asking for cells smaller than a texel`() {
        // Below a few texels per cell the smoothstep has nothing to smooth and the field turns
        // to per-pixel noise, which is the one artefact that would be obvious on screen.
        val (across, _) = RoomSurface.cellsFor(1000f)

        assertTrue("$across cells", across * 4 <= RoomSurface.TEX_PX)
    }

    @Test
    fun `a nonsense aspect falls back to square rather than dividing by it`() {
        for (bad in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val (across, down) = RoomSurface.cellsFor(bad)
            assertTrue("aspect $bad gave $across x $down", across > 0 && down > 0)
        }
    }

    @Test
    fun `the floor is darker than the wall it meets`() {
        // Derived from the wall's colour rather than given its own, so it belongs to all four
        // modes without being tuned four times — and it has to actually end up darker, or the
        // ground reads as a second wall lying down.
        assertTrue(RoomSurface.FLOOR_DARKEN < 1f)
        // And more textured: a floor is walked on and is seen at a grazing angle, where
        // variation compresses toward the horizon.
        assertTrue(RoomSurface.FLOOR_CONTRAST > RoomSurface.WALL_CONTRAST)
    }
}
