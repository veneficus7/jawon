package com.jawon.app.ui.showroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The exhibits' float and the camera's aim — written for the pocket player's rest pose, kept with
 * the showroom when that room became a face (the same tests, moved with the code they cover).
 */
class ShowroomFloatTest {

    @Test
    fun `the wander stays in bounds, moves smoothly, and does not repeat`() {
        val w = Wander(seed = 3, periodS = 2f)
        var last = w.at(0f)
        for (i in 1..6_000) {
            val v = w.at(i / 60f)
            assertTrue(v in -1f..1f)
            assertTrue("a step of ${abs(v - last)}", abs(v - last) < 0.06f)
            last = v
        }
        assertTrue(abs(w.at(1.3f) - w.at(1.3f + 2f * 7f)) > 1e-3f || abs(w.at(3.1f) - w.at(3.1f + 14f)) > 1e-3f)
    }

    @Test
    fun `looking straight ahead is level, down pitches down, and right turns right`() {
        val a = FloatArray(2)
        lookAngles(0f, 0f, 1f, 0f, 0f, 0f, a)
        assertEquals(0f, a[0], 1e-4f)
        assertEquals(0f, a[1], 1e-4f)
        lookAngles(0f, 0f, 1f, 0f, -1f, 0f, a)
        assertEquals(-45f, a[0], 1e-3f)
        lookAngles(0f, 0f, 0f, 1f, 0f, 0f, a)
        assertEquals(-90f, a[1], 1e-3f)
    }
}
