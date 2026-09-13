package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vitrine's three claims, as arithmetic.
 *
 * `RoomLighting`'s header says the pair lights a sleeve about twice as evenly as the underlight
 * did, that each light sits outside the cover's edge so its reflection cannot land on the
 * artwork, and that a record still clears the strips as it turns. None of those is a matter of
 * taste and none needs a device, so none of them should be a sentence in a comment that nothing
 * checks — the wall is full of numbers that were true when they were written.
 */
class VitrineGeometryTest {

    @Test
    fun `the pair lights a sleeve three times as evenly as one light below it`() {
        val pair = VitrineGeometry.evenness()
        val under = VitrineGeometry.underlightEvenness()

        // The claim in RoomLighting's header, all three numbers of it.
        assertEquals(5.10f, under, 0.05f)
        assertEquals(1.54f, pair, 0.05f)
        assertEquals(1.52f, VitrineGeometry.evenness(withNeighbours = true), 0.05f)
        assertTrue("the pair should be the more even of the two", pair < under)
    }

    @Test
    fun `each light sits outside the cover's own edge`() {
        // The whole reason the vitrine can be the key light without writing a highlight into
        // the artwork: on a flat glossy plate the reflection of a source appears where that
        // source's mirror image is, and a source past the edge has no mirror image on it.
        val clear = VitrineGeometry.clearOfCoverEdge()
        assertTrue("light is $clear m outside the cover edge; must be positive", clear > 0f)
        assertEquals(0.007f, clear, 0.001f)
    }

    @Test
    fun `a record clears the strips at every angle it can turn to`() {
        // A case sweeps its plan DIAGONAL, not its thickness — the mistake that made the old
        // packed rack drive cases through their neighbours, recorded on WallDimens.SLOT_SWEEP.
        val clear = VitrineGeometry.clearOfTurningCase()
        assertTrue("strips foul the turning case by ${-clear} m", clear > 0.002f)
    }

    @Test
    fun `the whole cover is inside the falloff and the next bay is only just`() {
        // Falloff is a hard cutoff in Filament (trap 6): a corner past it renders black.
        val corner = VitrineGeometry.cornerDistance()
        assertTrue(
            "cover corner at $corner m is outside falloff ${RoomLighting.UNDER_FALLOFF}",
            corner < RoomLighting.UNDER_FALLOFF
        )

        // And the next bay along is inside the reach ON PURPOSE — a strip stands in the
        // channel between two records and lights both. That is what evens the wall out
        // instead of leaving a row of separately spotlit squares.
        val neighbour = VitrineGeometry.neighbourDistance()
        assertEquals(0.116f, neighbour, 0.002f)
        assertTrue(
            "the neighbour at $neighbour m should be inside falloff ${RoomLighting.UNDER_FALLOFF}",
            neighbour < RoomLighting.UNDER_FALLOFF
        )
    }

    @Test
    fun `the lights are level with the middle of the record`() {
        // Level with the middle, the top and bottom of a sleeve are equidistant by
        // construction, which is the whole of why the pair does not wash the foot of the art.
        assertEquals(WallDimens.CASE_H / 2f, RoomLighting.UNDER_LIFT, 1e-6f)

        val top = VitrineGeometry.pairIlluminance(0f, VitrineGeometry.COVER_HALF_H)
        val bottom = VitrineGeometry.pairIlluminance(0f, -VitrineGeometry.COVER_HALF_H)
        assertEquals(top, bottom, top * 1e-4f)
    }
}
