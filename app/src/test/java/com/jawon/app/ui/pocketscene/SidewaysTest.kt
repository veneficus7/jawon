package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SidewaysTest {

    private val g = 9.81f

    /** Gravity for a phone held upright and turned [deg] anticlockwise (its top going left). */
    private fun turned(deg: Float): Triple<Float, Float, Float> {
        val r = Math.toRadians(deg.toDouble())
        return Triple((g * sin(r)).toFloat(), (g * cos(r)).toFloat(), 0f)
    }

    private fun SidewaysDetector.hold(t: Triple<Float, Float, Float>, from: Long, forMs: Long): Holding {
        var now = from
        var last = holding
        while (now <= from + forMs) {
            last = update(t.first, t.second, t.third, now)
            now += 20
        }
        return last
    }

    @Test
    fun `turned anticlockwise is its top to the left, clockwise to the right`() {
        val d = SidewaysDetector()
        assertEquals(Holding.LEFT, d.hold(turned(90f), 0, 400))
        d.reset()
        assertEquals(Holding.RIGHT, d.hold(turned(-90f), 0, 400))
    }

    @Test
    fun `a phone tipped while reading is not a phone turned`() {
        val d = SidewaysDetector()
        assertEquals(Holding.UPRIGHT, d.hold(turned(35f), 0, 1_000))
        assertEquals(Holding.UPRIGHT, d.hold(turned(-40f), 1_000, 1_000))
    }

    @Test
    fun `it has to be held sideways a moment, not swung through`() {
        val d = SidewaysDetector()
        // Through sideways for a tenth of a second, and back: it never counted as sideways.
        assertEquals(Holding.UPRIGHT, d.hold(turned(90f), 0, 100))
        assertEquals(Holding.UPRIGHT, d.hold(turned(0f), 120, 600))
    }

    @Test
    fun `half way back is still sideways, and clearly upright comes back`() {
        val d = SidewaysDetector()
        d.hold(turned(90f), 0, 400)
        // 45°: between the two thresholds, so it stays as it was.
        assertEquals(Holding.LEFT, d.hold(turned(45f), 500, 1_000))
        assertEquals(Holding.UPRIGHT, d.hold(turned(15f), 1_600, 400))
    }

    @Test
    fun `lying flat changes nothing, whichever way it was`() {
        val d = SidewaysDetector()
        d.hold(turned(90f), 0, 400)
        assertEquals(Holding.LEFT, d.hold(Triple(0.5f, 0.3f, g), 500, 2_000))
        d.reset()
        assertEquals(Holding.UPRIGHT, d.hold(Triple(0.5f, 0.3f, -g), 0, 2_000))
    }

    @Test
    fun `the lean is right edge down positive, and full by about 35 degrees`() {
        val (x, y, z) = turned(-20f) // clockwise: the right edge goes down
        assertTrue(leanOf(x, y, z) > 0.5f)
        val (x2, y2, z2) = turned(20f)
        assertTrue(leanOf(x2, y2, z2) < -0.5f)
        val (x3, y3, z3) = turned(-60f)
        assertEquals(1f, leanOf(x3, y3, z3), 1e-6f)
        assertEquals(0f, leanOf(0f, g, 0f), 1e-6f)
    }

    @Test
    fun `the room's light dims the screen only in the dark, more the darker`() {
        assertEquals(0f, roomDimFor(500f), 1e-6f)
        assertEquals(0f, roomDimFor(60f), 1e-6f)
        assertTrue(roomDimFor(5f) > roomDimFor(30f))
        assertTrue(roomDimFor(0f) <= 0.33f)
        assertTrue(roomDimFor(0f) > 0.25f)
    }
}
