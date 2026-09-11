package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the lens dirt and the haze (`RoomAir`). What they look like is the
 * owner's phone; these pin the two ways they have already been, or could easily be, broken
 * without anything on screen saying why.
 */
class RoomAirTest {

    @Test
    fun `clean glass passes the bloom at exactly its own strength`() {
        // Filament multiplies the bloom by dirt texel x dirtStrength. The first build had clean
        // glass at 0 and wiped out the room's glow everywhere but the smudges — the cyan halo
        // beside Vigilia's tube measured (0, 79, 154) before and (1, 13, 24) after. Clean glass
        // times the strength must be 1, or every light in the room changes with it.
        assertEquals(1f, RoomAir.DIRT_CLEAN * RoomAtmosphere.LENS_DIRT_STRENGTH, 1e-6f)
    }

    @Test
    fun `a mark on the lens can only add to the glow`() {
        // A texel at full is 1.0, so the most a mark can do is DIRT_CLEAN's inverse — above 1,
        // so marks brighten the bloom where they sit and never darken it.
        assertTrue(RoomAtmosphere.LENS_DIRT_STRENGTH > 1f)
        assertTrue(RoomAir.DIRT_CLEAN in 0.05f..0.95f)
    }

    @Test
    fun `neither haze sheet leaves the rack`() {
        // The back sheet lives in the gap in front of the panel; the front one behind the
        // board's front edge, so it never hangs in the room in front of the furniture.
        assertTrue(RoomAir.HAZE_BACK_Z > WallDimens.WALL_Z)
        assertTrue(RoomAir.HAZE_FRONT_Z < WallDimens.BOARD_D / 2f)
        assertTrue(RoomAir.HAZE_BACK_Z < RoomAir.HAZE_FRONT_Z)
    }

    @Test
    fun `the back haze reaches from the tube to the board and no further`() {
        // Its top edge is the tube's underside and its bottom the board the records stand on,
        // so there is never a visible edge to it: the compartment is 194mm tall, and the tube
        // takes the top 1.1mm of its own half-height out of that.
        val tubeToBoard = WallDimens.CASE_H + RoomLighting.SHELF_LIGHT_LIFT - RoomLighting.STRIP_H / 2f
        assertEquals(tubeToBoard, RoomAir.HAZE_BACK_H, 1e-6f)
        assertTrue(RoomAir.HAZE_FRONT_H < RoomAir.HAZE_BACK_H)
    }
}
