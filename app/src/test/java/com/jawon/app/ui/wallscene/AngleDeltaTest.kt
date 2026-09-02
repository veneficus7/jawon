package com.jawon.app.ui.wallscene

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning the band.
 *
 * The whole rotate gesture rests on these two functions, and the interesting one has a single
 * hard case: `atan2` reports −180…180, so a finger crossing the left of the ring reads 179° then
 * −179°. That is a two-degree movement that subtracts to −358, and without the wrap the control
 * would slam end to end every time the drag crossed that line — a fault that appears on one side
 * of the dial only and looks like the hardware is broken.
 */
class AngleDeltaTest {

    private val centre = Offset(100f, 100f)

    @Test
    fun `angles are measured from the centre, clockwise from east`() {
        // Canvas y grows downward, so positive degrees go clockwise on screen.
        assertEquals(0f, angleOf(Offset(200f, 100f), centre), 0.01f)
        assertEquals(90f, angleOf(Offset(100f, 200f), centre), 0.01f)
        assertEquals(-90f, angleOf(Offset(100f, 0f), centre), 0.01f)
        assertEquals(180f, kotlin.math.abs(angleOf(Offset(0f, 100f), centre)), 0.01f)
    }

    @Test
    fun `a small turn is its own size`() {
        assertEquals(5f, angleDelta(10f, 15f), 0.001f)
        assertEquals(-5f, angleDelta(15f, 10f), 0.001f)
    }

    @Test
    fun `crossing the back of the ring takes the short way`() {
        // **The case this exists for.** Two degrees of movement, not 358 of it.
        assertEquals(2f, angleDelta(179f, -179f), 0.001f)
        assertEquals(-2f, angleDelta(-179f, 179f), 0.001f)
    }

    @Test
    fun `a half turn is not flipped into its opposite`() {
        // Exactly 180 is ambiguous by definition; either sign is defensible, but the size of the
        // step must still be a half turn rather than a whole one.
        assertEquals(180f, kotlin.math.abs(angleDelta(0f, 180f)), 0.001f)
    }

    @Test
    fun `standing still moves nothing`() {
        assertEquals(0f, angleDelta(42f, 42f), 0.001f)
    }

    /** What `atan2` would report for a true angle: folded back into −180…180. */
    private fun reported(trueDeg: Float): Float =
        ((trueDeg + 180f) % 360f + 360f) % 360f - 180f

    @Test
    fun `a walk right round the ring sums to one full turn`() {
        // Integrating small steps is exactly what the gesture does, so a lap has to come out as
        // a lap. The first version of this test walked from −180 to +180 in seven-degree steps,
        // which was wrong twice: seven does not divide 360, so it overshot by three degrees —
        // and going from −180 up to +180 passes through zero and never crosses the seam at all,
        // so it could not have caught the bug it was written for. This one walks a real lap and
        // folds each angle the way `atan2` would.
        var total = 0f
        var trueAngle = 0f
        var prev = reported(trueAngle)
        repeat(45) {
            trueAngle += 8f
            val now = reported(trueAngle)
            total += angleDelta(prev, now)
            prev = now
        }
        assertEquals(360f, total, 0.5f)
    }

    @Test
    fun `a lap of the band is one full range of its control`() {
        // The mapping the gesture uses: the arc *is* the scale, rather than an invented
        // sensitivity. Sweeping the band end to end must move a control across its whole travel.
        val sweep = 232f
        var value = 0f
        var prev = -26f
        var a = -26f
        while (a < 206f) {
            a += 4f
            value += angleDelta(prev, a) / sweep
            prev = a
        }
        assertEquals(1f, value, 0.02f)
    }
}
