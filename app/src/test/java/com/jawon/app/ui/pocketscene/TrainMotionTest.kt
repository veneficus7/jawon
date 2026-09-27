package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val v = train.cruise(t)
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

    // ---- the stations: it follows the music ----------------------------------------------------

    /** A train that has been running long enough for a pause to be animated, not placed. */
    private fun underway(fps: Int = 60): TrainMotion {
        val train = TrainMotion()
        repeat(2 * fps) { train.advance(1f / fps) }
        return train
    }

    @Test
    fun `pausing brings it to rest with the platform at the window, never rolling back`() {
        val train = underway()
        train.setRunning(false)
        var last = train.distance
        var stoppedAfter = -1f
        for (frame in 1..15 * 60) {
            train.advance(1f / 60f)
            assertTrue("it rolled back", train.distance >= last - 1e-9)
            last = train.distance
            if (stoppedAfter < 0f && train.stopped) stoppedAfter = frame / 60f
        }
        assertTrue(train.stopped)
        assertEquals(0f, train.speed(), 0f)
        assertEquals(0f, train.stationAhead, 1e-3f)
        // A calm stop, and a sure one: not snatched, not left to creep.
        assertTrue("stopped after $stoppedAfter s", stoppedAfter in 7f..13f)
    }

    @Test
    fun `the brakes come on and off gently - no jolt going in, none as it stands`() {
        val train = underway()
        train.setRunning(false)
        val dt = 1f / 120f
        val decel = mutableListOf<Float>()
        var before = train.speed()
        while (!train.stopped) {
            train.advance(dt)
            decel += (before - train.speed()) / dt
            before = train.speed()
        }
        assertTrue("first ${decel.first()}", decel.first() < 0.1f)
        assertTrue("last ${decel.last()}", decel.last() < 0.1f)
        assertTrue("peak ${decel.max()}", decel.max() < 3.2f)
    }

    @Test
    fun `playing again pulls away, and is back at cruising speed`() {
        val train = underway()
        train.setRunning(false)
        repeat(15 * 60) { train.advance(1f / 60f) }
        train.setRunning(true)
        var before = train.speed()
        repeat(4 * 60) {
            train.advance(1f / 60f)
            assertTrue("it faltered pulling away", train.speed() >= before)
            before = train.speed()
        }
        repeat(6 * 60) { train.advance(1f / 60f) }
        assertEquals(train.cruise(), train.speed(), 0.01f)
        assertFalse(train.stopped)
    }

    @Test
    fun `paused just after leaving, it stops alongside the same platform`() {
        val train = underway()
        train.setRunning(false)
        repeat(15 * 60) { train.advance(1f / 60f) }
        train.setRunning(true)
        repeat(3 * 60) { train.advance(1f / 60f) }
        val left = train.stationAhead
        train.setRunning(false)
        repeat(15 * 60) { train.advance(1f / 60f) }
        assertTrue(train.stopped)
        // Past the platform's middle, and still beside it.
        assertTrue(train.stationAhead < left && train.stationAhead > -NightWindow.PLATFORM_HALF_M)
    }

    @Test
    fun `opened while nothing plays, it is already standing at a platform`() {
        assertTrue(TrainMotion(running = false).stopped)
        assertEquals(0f, TrainMotion(running = false).stationAhead, 0f)
        // And the player's first word, while the room is still being built, is where it is.
        val train = TrainMotion()
        train.advance(0.2f)
        train.setRunning(false)
        assertTrue(train.stopped)
        assertEquals(0f, train.stationAhead, 0f)
    }

    @Test
    fun `a stop lands in the same place at 60 and at 120 frames a second`() {
        for (fps in listOf(60, 120)) {
            val train = underway(fps)
            train.setRunning(false)
            repeat(15 * fps) { train.advance(1f / fps) }
            assertEquals(0f, train.stationAhead, 1e-4f)
        }
    }

    @Test
    fun `standing at a platform the carriage is still, once it has settled`() {
        val train = underway()
        train.setRunning(false)
        repeat(20 * 60) { train.advance(1f / 60f) }
        val (x, y) = train.bob()
        assertEquals(0f, x, 1e-7f)
        assertEquals(0f, y, 1e-7f)
    }

    @Test
    fun `out on the line there is no station to draw`() {
        assertTrue(TrainMotion().stationAhead.isNaN())
        val train = underway()
        train.setRunning(false)
        repeat(15 * 60) { train.advance(1f / 60f) }
        train.setRunning(true)
        repeat(20 * 60) { train.advance(1f / 60f) }
        assertTrue(train.stationAhead.isNaN())
    }

    @Test
    fun `a platform's light is in at the window while it stands, and goes as it pulls away`() {
        assertEquals(0f, TrainMotion().platformLight, 0f)
        assertEquals(1f, TrainMotion(running = false).platformLight, 0f)
        val train = TrainMotion(running = false)
        repeat(60) { train.advance(1f / 60f) }
        train.setRunning(true)
        var last = train.platformLight
        var faded = false
        repeat(15 * 60) {
            train.advance(1f / 60f)
            assertTrue("it came back", train.platformLight <= last + 1e-6f)
            last = train.platformLight
            if (last in 0.05f..0.95f) faded = true
        }
        assertEquals(0f, train.platformLight, 0f)
        assertTrue("it should fade, not switch", faded)
    }
}
