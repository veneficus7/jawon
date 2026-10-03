package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The carriage's light turned for the table. What would read as wrong: the reflections on the
 * resting device coming from somewhere the lamp is not — the shadow saying one thing and the glass
 * another — or the room bending out of shape as it turns.
 */
class RestLightTest {

    private fun apply(m: FloatArray, v: FloatArray) = floatArrayOf(
        m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
        m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
        m[2] * v[0] + m[5] * v[1] + m[8] * v[2]
    )

    private fun unit(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun assertVector(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-4f) {
        for (i in 0..2) assertEquals("component $i", expected[i], actual[i], tolerance)
    }

    /** The face's normal for a device turned by SceneView's (pitch, yaw): `Ry · Rx` applied to +Z. */
    private fun normal(pitchDeg: Float, yawDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble())
        val y = Math.toRadians(yawDeg.toDouble())
        val rx = floatArrayOf(0f, (-sin(p)).toFloat(), cos(p).toFloat())
        return floatArrayOf(
            (cos(y) * rx[0] + sin(y) * rx[2]).toFloat(),
            rx[1],
            (-sin(y) * rx[0] + cos(y) * rx[2]).toFloat()
        )
    }

    private fun reflect(v: FloatArray, n: FloatArray): FloatArray {
        val d = 2f * (v[0] * n[0] + v[1] * n[1] + v[2] * n[2])
        return floatArrayOf(v[0] - d * n[0], v[1] - d * n[1], v[2] - d * n[2])
    }

    /** What the glass mirrors in the hand, and at rest. */
    private val handMirror = reflect(floatArrayOf(0f, 0f, -1f), normal(DEVICE_PITCH_DEG, DEVICE_YAW_DEG))
    private val restMirror = reflect(
        unit(
            floatArrayOf(
                RestPose.DEVICE_X - RestPose.CAMERA_X,
                RestPose.DEVICE_Y - RestPose.CAMERA_Y,
                RestPose.DEVICE_Z - RestPose.CAMERA_Z
            )
        ),
        normal(RestPose.DEVICE_PITCH_DEG, RestPose.DEVICE_YAW_DEG)
    )

    @Test
    fun `unswung, the resting device's glass sees what it sees in the hand`() {
        val m = FloatArray(9)
        RestLight.restTurn(0f, m)
        // The room turned by m: what the hand's glass saw now lies where the resting glass looks.
        assertVector(restMirror, apply(m, handMirror))
    }

    @Test
    fun `the swing turns it about the vertical and nothing else`() {
        val unswung = FloatArray(9)
        RestLight.restTurn(0f, unswung)
        for (v in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0.3f, 0.8f, -0.5f), floatArrayOf(0f, 0f, 1f))) {
            val a = apply(unswung, v)
            val b = apply(RestLight.atRest, v)
            assertEquals("height kept", a[1], b[1], 1e-5f)
            assertEquals("distance from the vertical kept", a[0] * a[0] + a[2] * a[2], b[0] * b[0] + b[2] * b[2], 1e-5f)
        }
    }

    @Test
    fun `the lamp hangs where the turned softbox is`() {
        val softbox = FloatArray(3).also { PocketLighting.softboxDirection(it) }
        val lamp = RestLight.restLamp
        val toLamp = unit(floatArrayOf(lamp[0] - RestPose.DEVICE_X, lamp[1] - RestPose.DEVICE_Y, lamp[2] - RestPose.DEVICE_Z))
        assertVector(apply(RestLight.atRest, softbox), toLamp)
    }

    @Test
    fun `the lamp is up to the frame's right and toward its top, so the shadow falls down and left`() {
        val lamp = RestLight.restLamp
        assertTrue("right of the device", lamp[0] > RestPose.DEVICE_X + 0.05f)
        assertTrue("toward the window, the top of the frame", lamp[2] < RestPose.DEVICE_Z - 0.05f)
        assertTrue("well above the sheet", lamp[1] > RestPose.DEVICE_Y + 0.3f)
        // Where the device's top edge throws its shadow on the sheet, 10.5mm down.
        val h = lamp[1] - RestPose.DEVICE_Y
        val shadowX = -(lamp[0] - RestPose.DEVICE_X) / h
        val shadowZ = -(lamp[2] - RestPose.DEVICE_Z) / h
        assertTrue("falls left", shadowX < 0f)
        assertTrue("falls down the frame", shadowZ > 0f)
    }

    @Test
    fun `it is a turn - lengths and angles kept, nothing mirrored`() {
        val m = RestLight.atRest
        val t = FloatArray(9) { i -> m[(i % 3) * 3 + i / 3] }
        val mtm = FloatArray(9)
        // (mᵀ)·(mᵀ)ᵀ = mᵀ·m.
        RestLight.multiplyTransposed(t, t, mtm)
        for (i in 0..8) assertEquals("entry $i", if (i % 4 == 0) 1f else 0f, mtm[i], 1e-4f)
        val det = m[0] * (m[4] * m[8] - m[7] * m[5]) - m[3] * (m[1] * m[8] - m[7] * m[2]) + m[6] * (m[1] * m[5] - m[4] * m[2])
        assertEquals(1f, det, 1e-4f)
    }

    @Test
    fun `part of the way is the same turn, less of it - none in the hand, all of it at rest`() {
        val full = RestLight.atRest
        val none = FloatArray(9)
        RestLight.partial(full, 0f, none)
        for (i in 0..8) assertEquals(if (i % 4 == 0) 1f else 0f, none[i], 1e-6f)
        val all = FloatArray(9)
        RestLight.partial(full, 1f, all)
        for (i in 0..8) assertEquals(full[i], all[i], 1e-4f)
        // Half, twice, is the whole of it: one axis, the angle shared out.
        val half = FloatArray(9)
        RestLight.partial(full, 0.5f, half)
        val twice = FloatArray(9)
        RestLight.multiply(half, half, twice)
        for (i in 0..8) assertEquals("entry $i", full[i], twice[i], 1e-4f)
    }

    @Test
    fun `the products are the arithmetic they say they are`() {
        val a = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val b = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        val ab = FloatArray(9)
        RestLight.multiply(a, b, ab)
        val v = floatArrayOf(0.3f, -0.5f, 0.8f)
        assertVector(apply(a, apply(b, v)), apply(ab, v), 1e-5f)
        val abt = FloatArray(9)
        RestLight.multiplyTransposed(a, b, abt)
        val bt = FloatArray(9) { i -> b[(i % 3) * 3 + i / 3] }
        assertVector(apply(a, apply(bt, v)), apply(abt, v), 1e-5f)
    }
}
