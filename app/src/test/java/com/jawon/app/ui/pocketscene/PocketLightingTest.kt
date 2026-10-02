package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The room the pocket player lies in, as far as it is arithmetic: the light it reflects, and the
 * pose that light is turned by. Pure, so it is held here.
 *
 * **Two copies of this room exist and must agree** — this one, and `tools/models/pocket_room.py`,
 * which baked the desk's light. The pinned values below are the Python's own output for the
 * same directions; if either copy changes alone, the device is lit by one room and lies on a desk
 * lit by another, and the first test here fails.
 *
 * Directions are the device's frame, as zenith (from straight up off the desk) and azimuth
 * (`atan2(y, x)`) in degrees. Where the face looks was measured, not derived: the device rendered
 * as a mirror in an environment coloured by direction (`tools/pocket-preview/frame.py --probe`).
 */
class PocketLightingTest {

    private fun direction(zenithDeg: Double, azimuthDeg: Double): FloatArray {
        val z = Math.toRadians(zenithDeg)
        val a = Math.toRadians(azimuthDeg)
        return floatArrayOf((sin(z) * cos(a)).toFloat(), (sin(z) * sin(a)).toFloat(), cos(z).toFloat())
    }

    private fun radiance(d: FloatArray): FloatArray =
        FloatArray(3).also { PocketLighting.radiance(d[0], d[1], d[2], it) }

    private fun luminance(rgb: FloatArray): Float = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]

    private fun luminance(zenithDeg: Double, azimuthDeg: Double): Float =
        luminance(radiance(direction(zenithDeg, azimuthDeg)))

    /**
     * To a tenth of a per cent: Kotlin works in floats and the Python in doubles, and an `acos`
     * near the lamp's centre is worth a few parts in ten thousand. A changed constant is not.
     */
    private fun assertRgb(expected: DoubleArray, d: FloatArray) {
        val actual = radiance(d)
        for (c in 0..2) {
            assertEquals("channel $c", expected[c], actual[c].toDouble(), 1e-3 * maxOf(0.01, expected[c]))
        }
    }

    @Test
    fun `the room is the same room the desk was baked in`() {
        // pocket_room.radiance, for the same directions.
        assertRgb(doubleArrayOf(1.111862, 0.889920, 0.668017), direction(17.0, 18.0))    // the face
        assertRgb(doubleArrayOf(1.237542, 0.990473, 0.743425), direction(13.0, 10.0))    // the wheel
        assertRgb(doubleArrayOf(0.451197, 0.270780, 0.162350), direction(167.0, 180.0))  // the left side
        assertRgb(doubleArrayOf(0.424000, 0.254550, 0.152700), direction(180.0, 0.0))    // the desk
        assertRgb(doubleArrayOf(62.978109, 51.606888, 37.787766), floatArrayOf(-0.15f, 0.20f, 0.34f))
        assertRgb(doubleArrayOf(0.105834, 0.145067, 0.224400), floatArrayOf(-0.662936f, -0.741967f, 0.1f))
        assertRgb(doubleArrayOf(0.005591, 0.004872, 0.004254), direction(80.0, -40.0))   // nothing
    }

    @Test
    fun `the face looks into the ceiling's glow, brighter at its top than its foot`() {
        // The top of the face reflects toward azimuth ~44°, the foot toward ~-10°: the top is
        // nearer the glow over the lamp. 1.06 against 0.81 — a gradient down the face, which is
        // what reads as a curved light on flat metal rather than a lit sheet.
        val top = luminance(21.0, 44.0)
        val foot = luminance(15.0, -10.0)
        assertTrue("top $top against foot $foot", top > foot * 1.15f)
        assertTrue("the foot is still in the light: $foot", foot > 0.5f)
    }

    @Test
    fun `the lamp is the brightest thing in the room by far`() {
        val lamp = luminance(radiance(floatArrayOf(-0.15f, 0.20f, 0.34f)))
        assertTrue("the lamp at $lamp", lamp > 40f * luminance(17.0, 18.0))
    }

    @Test
    fun `below the desk's edge the polished side sees warm wood, not the dark`() {
        val wood = radiance(direction(167.0, 180.0))
        assertTrue("red ${wood[0]} over blue ${wood[2]}", wood[0] > 2f * wood[2])
        assertTrue(luminance(wood) > 0.2f)
    }

    @Test
    fun `away from its lights the room stays dark`() {
        // Low behind the device, to the right: no lamp, no ceiling, no window. The device's
        // blacks stay black because the room is, not because the ambient was turned down.
        assertTrue(luminance(80.0, -40.0) < 0.01f)
    }

    @Test
    fun `the pose is SceneView's, yaw after pitch`() {
        // Rotation(x = -6, y = 15) through kotlin-math's Quaternion.fromEuler, as Node.rotation
        // applies it: Ry(15) · Rx(-6). The preview's Blender pose had the turns the other way
        // round, which is a degree and a half off at the corners.
        val m = PocketLighting.devicePose(-6f, 15f)
        val p = Math.toRadians(-6.0)
        val y = Math.toRadians(15.0)
        val expected = doubleArrayOf(
            cos(y), 0.0, -sin(y),
            sin(y) * sin(p), cos(p), cos(y) * sin(p),
            sin(y) * cos(p), -sin(p), cos(y) * cos(p)
        )
        for (i in 0 until 9) assertEquals("element $i", expected[i], m[i].toDouble(), 1e-5)
    }
}
