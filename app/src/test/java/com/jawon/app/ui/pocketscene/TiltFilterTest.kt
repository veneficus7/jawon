package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The phone's tilt, as the pocket player's room feels it. The failure worth guarding is the one
 * Euler angles have: a phone held upright is exactly where they lock.
 */
class TiltFilterTest {

    /** A quaternion (x, y, z, w) for [angle] radians about a unit axis. */
    private fun quat(ax: Float, ay: Float, az: Float, angle: Float): FloatArray {
        val s = sin(angle / 2f)
        return floatArrayOf(ax * s, ay * s, az * s, cos(angle / 2f))
    }

    /** a · b */
    private fun mul(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    )

    private fun TiltFilter.feed(q: FloatArray, dt: Float = 1f / 50f) = update(q[0], q[1], q[2], q[3], dt)

    /** The phone held upright in portrait: pitched 90° about the world's X. */
    private val upright = quat(1f, 0f, 0f, (Math.PI / 2).toFloat())

    @Test
    fun `however it is held, holding still is neutral`() {
        val filter = TiltFilter()
        repeat(100) { filter.feed(upright) }
        assertEquals(0f, filter.pitch, 1e-4f)
        assertEquals(0f, filter.yaw, 1e-4f)
    }

    @Test
    fun `tipping the top edge in reads as pitch, even held upright where Euler angles lock`() {
        val filter = TiltFilter()
        filter.feed(upright)
        // A tilt of 0.1 rad about the phone's *own* X axis: applied on the right.
        filter.feed(mul(upright, quat(1f, 0f, 0f, 0.1f)))
        assertEquals(0.1f, filter.pitch, 0.003f)
        assertEquals(0f, filter.yaw, 0.003f)
    }

    @Test
    fun `turning the right edge toward you reads as negative yaw`() {
        val filter = TiltFilter()
        filter.feed(upright)
        filter.feed(mul(upright, quat(0f, 1f, 0f, -0.08f)))
        assertEquals(-0.08f, filter.yaw, 0.003f)
        assertEquals(0f, filter.pitch, 0.003f)
    }

    @Test
    fun `a new way of holding it becomes neutral within a few seconds`() {
        val filter = TiltFilter()
        filter.feed(upright)
        val leaned = mul(upright, quat(1f, 0f, 0f, 0.2f))
        filter.feed(leaned)
        val first = filter.pitch
        repeat(50 * 6) { filter.feed(leaned) }
        assertTrue(first > 0.15f)
        assertTrue("still leaning ${filter.pitch}", abs(filter.pitch) < 0.01f)
    }

    @Test
    fun `a violent turn is clamped`() {
        val filter = TiltFilter(limitRad = 0.3f)
        filter.feed(upright)
        filter.feed(mul(upright, quat(0f, 1f, 0f, 1.2f)))
        assertEquals(0.3f, filter.yaw, 1e-4f)
    }

    @Test
    fun `the same attitude with its sign flipped is the same attitude`() {
        val filter = TiltFilter()
        filter.feed(upright)
        val q = mul(upright, quat(1f, 0f, 0f, 0.05f))
        filter.feed(floatArrayOf(-q[0], -q[1], -q[2], -q[3]))
        assertEquals(0.05f, filter.pitch, 0.003f)
    }
}
