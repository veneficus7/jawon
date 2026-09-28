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

    // ---- lying on the sheet -------------------------------------------------------------------

    /** A cord laid from the origin, heading −z, over [top], with the plug [plugY] up. */
    private fun laid(top: (Float, Float) -> Float, plugY: Float = 0.005f): Triple<FloatArray, FloatArray, FloatArray> {
        val cord = Cord()
        val xs = FloatArray(cord.nodes)
        val ys = FloatArray(cord.nodes)
        val zs = FloatArray(cord.nodes)
        RestCord.lay(0f, plugY, 0f, 0f, -1f, cord.linkM, cord.nodes, 1f, top, xs, ys, zs)
        return Triple(xs, ys, zs)
    }

    @Test
    fun `lying over folds, every link keeps its length - it drapes, it does not stretch`() {
        // A sheet rising and falling by a centimetre and a half every 12cm.
        val folds = { x: Float, z: Float -> 0.0075f * kotlin.math.sin(z / 0.02f) + 0.004f * kotlin.math.sin(x / 0.03f) }
        val (xs, ys, zs) = laid(folds)
        val link = Cord().linkM
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val dz = zs[i + 1] - zs[i]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            assertEquals("link $i", link, length, link * 0.03f)
        }
    }

    @Test
    fun `past the plug it lies on the surface, one radius up, and it leaves the plug at the plug`() {
        val slope = { x: Float, z: Float -> 0.15f * x - 0.1f * z }
        val (xs, ys, zs) = laid(slope, plugY = 0.02f)
        assertEquals(0.02f, ys[0], 1e-6f)
        var s = 0f
        for (i in 1 until xs.size) {
            s += Cord().linkM
            if (s > RestCord.LEAD_M) {
                assertEquals("node $i", slope(xs[i], zs[i]) + Cord.RADIUS_M, ys[i], 1e-5f)
            }
        }
    }
}
