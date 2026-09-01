package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * How a shelved case floats and turns.
 *
 * Pure, and untested since the wall was built. What is worth pinning here is not the exact
 * curve — those are tuning values and are meant to move — but the properties the room depends
 * on: that a case never sinks into its shelf, that a wall of them never moves in unison, and
 * that stopping the turn parks every case facing the same way rather than freezing it wherever
 * it happened to be.
 */
class ShelfHoverTest {

    // ---- floating -------------------------------------------------------------------

    @Test
    fun `a case never touches the shelf it floats above`() {
        // LIFT is "just enough to break contact"; if the bob ever exceeded it the case would
        // clip through the board once a cycle, which is the kind of thing nobody notices
        // until it is on a video.
        var t = 0f
        while (t < 40f) {
            for (phase in listOf(0f, 0.13f, 0.5f, 0.77f, 0.99f)) {
                assertTrue(
                    "case sank to ${ShelfHover.lift(t, phase)} at t=$t phase=$phase",
                    ShelfHover.lift(t, phase) > 0f
                )
            }
            t += 0.05f
        }
    }

    @Test
    fun `the float is centred on the lift height`() {
        // Averaged over whole cycles the bob should cancel, leaving LIFT.
        var sum = 0.0
        var n = 0
        var t = 0f
        while (t < 200f) { sum += ShelfHover.lift(t, 0f); n++; t += 0.01f }
        assertEquals(ShelfHover.LIFT.toDouble(), sum / n, 1e-3)
    }

    @Test
    fun `two albums with different phases do not float in unison`() {
        val a = ShelfHover.lift(3f, 0f)
        val b = ShelfHover.lift(3f, 0.5f)
        assertNotEquals("a shelf moving as one block reads as a single object", a, b)
    }

    // ---- turning ---------------------------------------------------------------------

    @Test
    fun `stopped means filed, not frozen mid-sweep`() {
        // At zero speed every case lands on the same angle regardless of its phase or the
        // clock — a stopped wall should look filed.
        for (phase in listOf(0f, 0.25f, 0.6f, 0.95f)) {
            for (t in listOf(0f, 1.7f, 100f)) {
                assertEquals(ShelfHover.RESTING_TURN, ShelfHover.turn(t, phase, 0f), 1e-5f)
            }
        }
    }

    @Test
    fun `a negative speed is treated as stopped rather than turning backwards`() {
        assertEquals(ShelfHover.RESTING_TURN, ShelfHover.turn(5f, 0.2f, -1f), 1e-5f)
    }

    @Test
    fun `the resting angle presents the cover`() {
        // -90 about Y maps the case's +X face onto +Z, toward the camera. Patina is drawn on
        // the cover partly because of this: at rest the spine is never presented at all.
        assertEquals(-90f, ShelfHover.RESTING_TURN, 1e-5f)
    }

    @Test
    fun `turning at full speed actually moves`() {
        assertNotEquals(
            ShelfHover.turn(0f, 0f, 1f),
            ShelfHover.turn(1f, 0f, 1f)
        )
    }

    @Test
    fun `speed scales the turn`() {
        // Half speed should cover half the ground from the same start.
        val start = ShelfHover.turn(0f, 0f, 1f)
        val full = ShelfHover.turn(0.5f, 0f, 1f) - start
        val half = ShelfHover.turn(1f, 0f, 0.5f) - start
        assertEquals(full, half, 1e-3f)
    }

    @Test
    fun `a wall never turns in unison`() {
        val angles = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f).map { ShelfHover.turn(2f, it, 1f) }
        assertEquals("phases collapsed onto one angle", angles.size, angles.toSet().size)
    }

    // ---- leaning ---------------------------------------------------------------------

    @Test
    fun `the tilt stays small enough to read as a lean`() {
        var t = 0f
        while (t < 60f) {
            for (phase in listOf(0f, 0.3f, 0.9f)) {
                assertTrue(
                    "tilt ran away to ${ShelfHover.tilt(t, phase)}",
                    abs(ShelfHover.tilt(t, phase)) <= 5f
                )
            }
            t += 0.05f
        }
    }

    @Test
    fun `float and lean run on different periods`() {
        // Same period would make them one motion, and the case would look hinged rather than
        // floating.
        assertNotEquals(
            ShelfHover.lift(7f, 0f) - ShelfHover.LIFT > 0f,
            ShelfHover.tilt(7f, 0f) > 0f
        )
    }

    // ---- flick decay -----------------------------------------------------------------

    @Test
    fun `a flicked case sheds its spin without stopping dead`() {
        assertTrue(ShelfHover.SPIN_RETAIN_PER_S > 0f)
        assertTrue(ShelfHover.SPIN_RETAIN_PER_S < 1f)
    }
}
