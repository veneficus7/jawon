package com.jawon.app.ui.wallscene

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The shelf-edge rail — the fixture that lights the records (`RoomLighting.RAIL_*`).
 *
 * What it looks like is the owner's phone. What is checked here is the geometry the design
 * depends on: that the rail is where a shop puts it, and that a whole sleeve stands inside its
 * beam. Both were got wrong once each on the fixtures before it — a light aimed at panel the
 * camera cannot see, and a cone whose edge crossed the thing it was lighting.
 */
class RoomRailTest {

    /** Where the rail's diodes are, relative to the centre of the row they light. */
    private val railY = WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT
    private val railZ = RoomLighting.RAIL_Z

    /** The angle, in radians, between the rail's axis and a point on the sleeve below it. */
    private fun offAxis(y: Float, z: Float): Float {
        val vx = 0f
        val vy = y - railY
        val vz = z - railZ
        val len = sqrt(vx * vx + vy * vy + vz * vz)
        val d = RoomLighting.RAIL_DIRECTION
        return acos(((vx * d[0] + vy * d[1] + vz * d[2]) / len).coerceIn(-1f, 1f))
    }

    @Test
    fun `the rail hangs under the shelf's front lip, in front of the records`() {
        // Behind the front edge of the board above, so the lip hides the diodes from below…
        assertTrue(RoomLighting.RAIL_Z < WallDimens.BOARD_D / 2f)
        // …and well in front of the records' faces, or it would light them edge-on.
        assertTrue(RoomLighting.RAIL_Z > WallDimens.CASE_W)
    }

    @Test
    fun `a whole sleeve stands inside the beam's full-strength core`() {
        // The top of a sleeve is the hard case: it is nearest the rail and furthest off its
        // axis. Inside the INNER angle, not merely the outer one — the outer is where a cone
        // fades to nothing, and a cover whose top sits there reads dim at the top.
        val top = offAxis(WallDimens.CASE_H / 2f, WallDimens.CASE_W / 2f)
        val foot = offAxis(-WallDimens.CASE_H / 2f, WallDimens.CASE_W / 2f)
        assertTrue("sleeve top at $top rad is outside the core", top < RoomLighting.RAIL_CONE_INNER)
        assertTrue("sleeve foot at $foot rad is outside the core", foot < RoomLighting.RAIL_CONE_INNER)
        assertTrue(RoomLighting.RAIL_CONE_INNER < RoomLighting.RAIL_CONE_OUTER)
    }

    @Test
    fun `the rail reaches past the foot of what it lights`() {
        // falloff is a hard cutoff (trap 6): a sleeve's foot must be inside it.
        val foot = sqrt(
            (WallDimens.CASE_H + RoomLighting.SHELF_LIGHT_LIFT) *
                (WallDimens.CASE_H + RoomLighting.SHELF_LIGHT_LIFT) + railZ * railZ
        )
        assertTrue("the foot of a sleeve is $foot m away", foot < RoomLighting.RAIL_FALLOFF)
    }

    @Test
    fun `the rail is the working light and the underlight is the accent`() {
        // The owner's complaint was the underlight washing the artwork; it only stopped doing
        // that because the rail took over. If this ever inverts, the wash is back.
        assertTrue(RoomLighting.UNDER_CANDELA < RoomLighting.RAIL_CANDELA)
    }

    @Test
    fun `the lit run covers more than a frame's width`() {
        // Like the ribbon: a pan may only change which diodes exist at the far edges, well out
        // of shot. The frame is about 26cm across at the default zoom.
        val run = (RoomLighting.RAIL_COLUMNS - 1) * RoomLighting.RAIL_COLUMN_SPACING
        assertTrue("the rail lights only $run m of shelf", run > 0.45f)
    }
}
