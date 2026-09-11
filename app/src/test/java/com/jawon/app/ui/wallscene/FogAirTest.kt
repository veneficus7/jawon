package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fog's response to the pan (`FogAir`): dragged along by it, churned while it lasts, and
 * slow to settle. What the fog looks like is the owner's phone; this is the arithmetic that
 * decides how it moves.
 */
class FogAirTest {

    private fun run(air: FogAir, from: Float, seconds: Float, dt: Float = 1f / 60f, pan: (Float) -> Float) {
        var t = from
        while (t < from + seconds) {
            air.update(t, pan(t), 0f)
            t += dt
        }
    }

    @Test
    fun `still air stays still`() {
        val air = FogAir()
        run(air, 0f, 2f) { 0.3f }
        assertEquals(0f, air.stir, 1e-6f)
        assertEquals(0f, air.windX, 1e-6f)
    }

    @Test
    fun `a brisk swipe stirs the air`() {
        val air = FogAir()
        // 0.8 m/s for a third of a second — faster than BRISK_M_PER_S.
        run(air, 0f, 0.33f) { t -> t * 0.8f }
        assertTrue("stir after a brisk swipe: ${air.stir}", air.stir > 0.8f)
    }

    @Test
    fun `the air is dragged a fraction of the way the view moved`() {
        val air = FogAir()
        run(air, 0f, 1f) { t -> t * 0.2f }
        // 0.2m of pan, dragged at DRAG.
        assertEquals(0.2f * FogAir.DRAG, air.windX, 0.01f)
    }

    @Test
    fun `stirred air settles, but not at once`() {
        val air = FogAir()
        run(air, 0f, 0.33f) { t -> t * 0.8f }
        val stirred = air.stir
        // Stop, and give it half a second: still noticeably churned.
        run(air, 0.34f, 0.5f) { 0.264f }
        assertTrue("settling too fast: ${air.stir}", air.stir > stirred * 0.5f)
        // Give it five more: nearly still.
        run(air, 0.84f, 5f) { 0.264f }
        assertTrue("never settles: ${air.stir}", air.stir < 0.05f)
    }

    @Test
    fun `a long pause cannot fling the fog`() {
        // The clock jumps (the app was in the background) while the pan also moved — the step is
        // clamped, so the stir cannot see a huge speed from a tiny dt or vice versa.
        val air = FogAir()
        air.update(0f, 0f, 0f)
        air.update(30f, 0.05f, 0f)
        assertTrue(air.stir in 0f..1f)
    }
}
