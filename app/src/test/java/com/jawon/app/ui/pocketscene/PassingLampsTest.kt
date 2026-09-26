package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lights that go past the train and land on the device. The rules that matter are the ones
 * a viewer would feel without naming: they all go the same way the window does, they never
 * snap on in mid-air, and there is no beat to the schedule.
 */
class PassingLampsTest {

    /** Runs the lamps for [seconds] and returns the start time of every pass. */
    private fun starts(lamps: PassingLamps, seconds: Float, fps: Int = 60): List<Double> {
        val seen = mutableListOf<Double>()
        val wasActive = BooleanArray(PassingLamps.COUNT)
        repeat((seconds * fps).toInt()) {
            lamps.advance(1f / fps)
            lamps.lamps.forEachIndexed { i, lamp ->
                if (lamp.active && !wasActive[i]) seen += lamps.time
                wasActive[i] = lamp.active
            }
        }
        return seen
    }

    @Test
    fun `every lamp travels the way the window does, right to left`() {
        val lamps = PassingLamps()
        val lastX = FloatArray(PassingLamps.COUNT) { Float.NaN }
        repeat(60 * 30) {
            lamps.advance(1f / 60f)
            lamps.lamps.forEachIndexed { i, lamp ->
                if (lamp.active) {
                    if (!lastX[i].isNaN()) assertTrue(lamp.x <= lastX[i])
                    lastX[i] = lamp.x
                } else {
                    lastX[i] = Float.NaN
                }
            }
        }
    }

    @Test
    fun `a lamp fades in and out rather than switching on in mid-air`() {
        val lamps = PassingLamps()
        var sawBright = false
        repeat(60 * 30) {
            lamps.advance(1f / 60f)
            for (lamp in lamps.lamps) {
                if (!lamp.active) {
                    assertEquals(0f, lamp.intensity, 0f)
                    continue
                }
                val span = lamp.fromX - lamp.toX
                val done = (lamp.fromX - lamp.x) / span
                if (done < 0.02f || done > 0.98f) {
                    assertTrue("intensity ${lamp.intensity} at $done", lamp.intensity < lamp.peak * 0.05f)
                }
                assertTrue(lamp.intensity <= lamp.peak + 1e-3f)
                if (lamp.intensity > lamp.peak * 0.9f) sawBright = true
            }
        }
        assertTrue(sawBright)
    }

    @Test
    fun `lamps come often, but with no beat to them`() {
        val times = starts(PassingLamps(), 180f)
        val rate = times.size / 180.0
        assertTrue("$rate a second", rate in 0.3..1.2)
        val gaps = times.zipWithNext { a, b -> b - a }
        // A beat would show up as gaps that are all nearly alike.
        val mean = gaps.average()
        val spread = kotlin.math.sqrt(gaps.map { (it - mean) * (it - mean) }.average())
        assertTrue("gaps too regular: mean $mean, spread $spread", spread > mean * 0.5)
        assertTrue("there should be clusters", gaps.any { it < 0.6 })
        assertTrue("and dark stretches", gaps.any { it > 5.0 })
    }

    @Test
    fun `the same seed rides the same line`() {
        assertEquals(starts(PassingLamps(seed = 3L), 60f), starts(PassingLamps(seed = 3L), 60f))
    }

    @Test
    fun `lamps behind you and behind the device both come`() {
        val lamps = PassingLamps()
        var behindYou = 0
        var behindDevice = 0
        val wasActive = BooleanArray(PassingLamps.COUNT)
        repeat(60 * 120) {
            lamps.advance(1f / 60f)
            lamps.lamps.forEachIndexed { i, lamp ->
                if (lamp.active && !wasActive[i]) {
                    if (lamp.z > 0f) behindYou++ else behindDevice++
                }
                wasActive[i] = lamp.active
            }
        }
        assertTrue(behindYou > behindDevice)
        assertTrue(behindDevice > 0)
    }
}
