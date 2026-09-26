package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The train outside the pocket player's window. What can go wrong is a wrap the eye can see, a
 * layer moving the wrong way against the others, or a picture that drifts differently at 60
 * and 120 frames a second.
 */
class TrainMotionTest {

    private fun ride(seconds: Float, fps: Int = 60): TrainMotion {
        val train = TrainMotion()
        // Counted in whole frames: accumulating float time runs one frame too many at one rate
        // and not the other, which is half a frame of train — the test measuring itself.
        repeat((seconds * fps).roundToInt()) { train.advance(1f / fps) }
        return train
    }

    @Test
    fun `the speed drifts, but never stops or runs away`() {
        val train = TrainMotion()
        var lo = Float.MAX_VALUE
        var hi = 0f
        var t = 0.0
        while (t < 3_600.0) {
            val v = train.speed(t)
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
            t += 0.5
        }
        assertTrue("slowest $lo", lo > NightWindow.BASE_SPEED_M_S * 0.7f)
        assertTrue("fastest $hi", hi < NightWindow.BASE_SPEED_M_S * 1.3f)
        assertTrue("it should actually drift", hi - lo > NightWindow.BASE_SPEED_M_S * 0.2f)
    }

    @Test
    fun `the distance is the same at any frame rate`() {
        val a = ride(20f, fps = 60)
        val b = ride(20f, fps = 120)
        assertEquals(a.distance, b.distance, 0.05)
    }

    @Test
    fun `near things cross the window faster than far ones`() {
        val train = ride(2f)
        val far = train.scroll(0)
        val mid = train.scroll(1)
        val near = train.scroll(2)
        val poles = train.scroll(3)
        assertTrue(far < mid && mid < near && near < poles)
    }

    @Test
    fun `a layer's scroll wraps at exactly the shader's period, so the wrap is seamless`() {
        // Ride long enough for the nearest lights to have wrapped several times.
        val train = ride(3_000f, fps = 30)
        for (layer in 0..3) {
            val period = NightWindow.LAYER_CELL_M[layer] * NightWindow.PERIOD_CELLS
            val s = train.scroll(layer)
            assertTrue("layer $layer scroll $s", s >= 0f && s < period)
            // Seamless means the unwrapped scroll and the wrapped one land on the same cell phase.
            val unwrapped = train.distance * (0.85 / NightWindow.LAYER_DEPTH_M[layer])
            val cell = NightWindow.LAYER_CELL_M[layer].toDouble()
            val phaseWrapped = (s / cell) % 1.0
            val phaseUnwrapped = (unwrapped / cell) % 1.0
            assertEquals(phaseUnwrapped, phaseWrapped, 1e-3)
        }
    }

    @Test
    fun `the carriage rocks by millimetres, and jolts at each rail joint`() {
        val train = TrainMotion()
        var biggest = 0f
        var jolted = false
        val dt = 1f / 120f
        repeat(120 * 20) {
            train.advance(dt)
            val (x, y) = train.bob()
            biggest = maxOf(biggest, abs(x), abs(y))
            if (abs(train.jolt()) > 0.0008) jolted = true
        }
        assertTrue("rock of $biggest m", biggest < 0.005f)
        assertTrue("a joint should be felt", jolted)
    }

    @Test
    fun `a stalled frame does not throw the train down the line`() {
        val train = TrainMotion()
        train.advance(5f)
        assertTrue(train.distance < NightWindow.BASE_SPEED_M_S * 0.2)
    }
}
