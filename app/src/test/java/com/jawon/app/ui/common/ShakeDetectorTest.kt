package com.jawon.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    @Test
    fun `back and forth, close together, is a shake`() {
        val d = ShakeDetector()
        assertFalse(d.onSample(16f, 0f, 0f, 1_000))
        assertTrue(d.onSample(-15f, 1f, 0f, 1_180))
    }

    @Test
    fun `one hard jolt the same way twice is a phone put down, not a shake`() {
        val d = ShakeDetector()
        assertFalse(d.onSample(0f, 0f, -18f, 1_000))
        assertFalse(d.onSample(0f, 0f, -14f, 1_150))
    }

    @Test
    fun `walking and riding never reach a jolt`() {
        val d = ShakeDetector()
        var t = 0L
        repeat(400) {
            val s = if (it % 2 == 0) 4f else -4f
            assertFalse(d.onSample(s, -s, s / 2f, t))
            t += 20
        }
    }

    @Test
    fun `a reversal too slow is two separate movements`() {
        val d = ShakeDetector()
        assertFalse(d.onSample(16f, 0f, 0f, 1_000))
        assertFalse(d.onSample(-16f, 0f, 0f, 1_000 + ShakeDetector.WINDOW_MS + 50))
    }

    @Test
    fun `one swing sampled twice is not two jolts`() {
        val d = ShakeDetector()
        assertFalse(d.onSample(16f, 0f, 0f, 1_000))
        assertFalse(d.onSample(-16f, 0f, 0f, 1_000 + ShakeDetector.MIN_GAP_MS - 10))
    }

    @Test
    fun `shaking on for fun steps the rooms at a pace you can follow`() {
        val d = ShakeDetector()
        var shakes = 0
        var t = 0L
        // Three seconds of vigorous shaking, a reversal every 120ms.
        while (t < 3_000) {
            val s = if ((t / 120) % 2 == 0L) 18f else -18f
            if (d.onSample(s, 0f, 0f, t)) shakes++
            t += 120
        }
        // One shake per cooldown, never one per reversal.
        assertEquals(3, shakes)
    }
}
