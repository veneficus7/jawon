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
    fun `the pair lights a sleeve nearly four times as evenly as one light below it`() {
        val pair = VitrineGeometry.evenness()
        val under = VitrineGeometry.underlightEvenness()

        // The claim in RoomLighting's header, all three numbers of it.
        assertEquals(5.10f, under, 0.05f)
        assertEquals(1.37f, pair, 0.02f)
        assertEquals(1.36f, VitrineGeometry.evenness(withNeighbours = true), 0.02f)
        assertTrue("the pair should be the more even of the two", pair < under)
    }

    @Test
    fun `a tube is more even than the spot it replaced, and three points get most of it`() {
        // "not one spotlight, but led tube light". A point level with the middle of a sleeve
        // is 71mm from its top edge and 95mm from its top corner, so the corners come out
        // roughly half lit; a row of points spread over the sleeve's own height has one near
        // every part of it. This is the number that claim rests on.
        val spot = VitrineGeometry.evenness(segments = 1)
        val tube = VitrineGeometry.evenness(segments = RoomLighting.VITRINE_SEGMENTS)
        assertEquals(1.54f, spot, 0.02f)
        assertTrue("a tube at $tube should beat a spot at $spot", tube < spot)

        // And the tiering is honest: MEDIUM's three points are within a hair of HIGH's five,
        // which is why QualityTier.vitrineSegments can step down without stepping the look
        // down with it. Past three this stops buying anything at all.
        val three = VitrineGeometry.evenness(segments = 3)
        assertTrue("three points should be within 1% of five", three - tube < 0.01f)
        assertTrue("seven should buy nothing over five",
            tube - VitrineGeometry.evenness(segments = 7) < 0.005f)
    }

    @Test
    fun `a tube moves light from the middle of a sleeve to its ends`() {
        // The shape of the change, not just its size: a tube is DIMMER in the middle than the
        // spot was and no dimmer at the corners. That redistribution is the whole of what a
        // line source does, and it is why the total candela did not have to move.
        val centreSpot = VitrineGeometry.pairIlluminance(0f, 0f, segments = 1)
        val centreTube = VitrineGeometry.pairIlluminance(0f, 0f, segments = 5)
        assertTrue("the tube should be gentler in the middle", centreTube < centreSpot)

        val cornerSpot = VitrineGeometry.pairIlluminance(
            VitrineGeometry.COVER_HALF_W, VitrineGeometry.COVER_HALF_H, segments = 1
        )
        val cornerTube = VitrineGeometry.pairIlluminance(
            VitrineGeometry.COVER_HALF_W, VitrineGeometry.COVER_HALF_H, segments = 5
        )
        assertTrue("the tube must not lose the corners", cornerTube >= cornerSpot)
    }

    @Test
    fun `one point is exactly the fixture the tube replaced`() {
        // What makes QualityTier.vitrineSegments safe to turn down to 1 on LOW: at a count of
        // one the row collapses to a single point at the middle of the record, which is the
        // bulb that was there before. A spot instead of a tube is a look, not a fault.
        assertEquals(0f, RoomLighting.vitrineSegmentY(0, 1), 1e-6f)
        assertEquals(
            VitrineGeometry.illuminance(0f, 0f, RoomLighting.UNDER_SIDE, 0f, RoomLighting.UNDER_FORWARD),
            VitrineGeometry.tubeIlluminance(0f, 0f, RoomLighting.UNDER_SIDE, 1),
            1e-6f
        )
    }

    @Test
    fun `a tube's points sit inside the record it lights`() {
        // A fixture taller than the thing it lights reads as scaffolding. Spread over
        // VITRINE_SPAN and centred, the outermost point lands half a spacing inside each end.
        val n = RoomLighting.VITRINE_SEGMENTS
        val top = RoomLighting.vitrineSegmentY(n - 1, n)
        val bottom = RoomLighting.vitrineSegmentY(0, n)
        assertEquals("the row should be centred on the record", top, -bottom, 1e-6f)
        assertTrue(
            "the tube's top point at $top is outside the sleeve",
            top < VitrineGeometry.COVER_HALF_H
        )
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
