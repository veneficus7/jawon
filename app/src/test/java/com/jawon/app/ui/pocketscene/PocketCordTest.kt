package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The earphone cord. What would read as wrong: a cord that comes away from the plug, stretches like
 * elastic, floats instead of hanging, or never stops swinging.
 */
class PocketCordTest {

    private fun Cord.run(seconds: Float, sy: Float = 0f, ey: Float = 0.30f, ez: Float = 0.30f) {
        repeat((seconds * 60).toInt()) { step(1f / 60f, 0f, sy, 0f, 0f, 1f, 0f, 0.05f, ey, ez) }
    }

    private fun Cord.linkLength(i: Int): Float {
        val dx = x[i + 1] - x[i]
        val dy = y[i + 1] - y[i]
        val dz = z[i + 1] - z[i]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `both ends stay where they are pinned, and it leaves the plug straight`() {
        val cord = Cord()
        cord.run(2f)
        assertEquals(0f, cord.x[0], 1e-6f)
        assertEquals(0f, cord.y[0], 1e-6f)
        assertEquals(0.30f, cord.y[cord.nodes - 1], 1e-6f)
        // The second node is straight out along the plug's axis (up).
        assertEquals(0f, cord.x[1], 1e-6f)
        assertTrue(cord.y[1] > 0f)
    }

    @Test
    fun `it keeps its length - a cord, not an elastic`() {
        val cord = Cord()
        cord.run(3f)
        val link = cord.lengthM / (cord.nodes - 1)
        for (i in 0 until cord.nodes - 1) {
            assertEquals("link $i", link, cord.linkLength(i), link * 0.03f)
        }
    }

    @Test
    fun `with slack it hangs - the middle sags below the straight line`() {
        // Ends 0.42m apart on a 0.62m cord: it has to hang somewhere.
        val cord = Cord()
        cord.run(4f)
        val mid = cord.nodes / 2
        val straightY = cord.y[1] + (0.30f - cord.y[1]) * ((mid - 1).toFloat() / (cord.nodes - 2))
        assertTrue("middle at ${cord.y[mid]}, straight line at $straightY", cord.y[mid] < straightY - 0.02f)
    }

    @Test
    fun `left still, it settles`() {
        val cord = Cord()
        cord.run(8f)
        val before = FloatArray(cord.nodes) { cord.y[it] }
        cord.run(0.5f)
        val moved = (0 until cord.nodes).maxOf { abs(cord.y[it] - before[it]) }
        assertTrue("still moving ${moved * 1000}mm", moved < 0.0005f)
    }

    @Test
    fun `when the plug moves, the cord follows, and a stall does not fling it`() {
        val cord = Cord()
        cord.run(2f)
        // The device carried off in a hurry, then one enormous frame.
        repeat(30) { i -> cord.step(1f / 60f, 0f, -0.01f * i, 0f, 0f, 1f, 0f, 0.05f, 0.30f, 0.30f) }
        cord.step(2f, 0f, -0.30f, 0f, 0f, 1f, 0f, 0.05f, 0.30f, 0.30f)
        for (i in 0 until cord.nodes) {
            assertFalse(cord.x[i].isNaN() || cord.y[i].isNaN() || cord.z[i].isNaN())
            assertTrue("node $i flung to ${cord.y[i]}", abs(cord.y[i]) < 1.5f && abs(cord.x[i]) < 1.5f)
        }
    }
}
