package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSettleTest {

    private val frame = 1f / 60f

    /** Runs [seconds] of frames from [fromMs], calling [each] before every step. */
    private fun RoomSettle.run(fromMs: Long, seconds: Float, each: (Long) -> Unit = {}): Long {
        var now = fromMs
        var t = 0f
        while (t < seconds) {
            each(now)
            step(frame, now)
            now += (frame * 1000).toLong()
            t += frame
        }
        return now
    }

    @Test
    fun `a room in use keeps its idle life at real time`() {
        val s = RoomSettle()
        s.run(0, 60f) { now -> s.keepAwake(now) }
        assertEquals(1f, s.rate, 0f)
        assertFalse(s.settled)
        // Real time: a minute of frames is a minute of clock.
        assertEquals(60f, s.clock, 0.2f)
    }

    @Test
    fun `left alone it winds down to a stop after the wait, and the clock stops with it`() {
        val s = RoomSettle(settleAfterMs = 20_000L, easeS = 1.6f)
        var now = s.run(0, 19.5f)
        assertEquals(1f, s.rate, 0f) // not yet
        now = s.run(now, 5f)
        assertTrue(s.settled)
        val stoppedAt = s.clock
        s.run(now, 30f)
        assertEquals(stoppedAt, s.clock, 0f)
    }

    @Test
    fun `winding down never jumps, the clock only ever moves forward, slower and slower`() {
        val s = RoomSettle()
        var now = s.run(0, 20f)
        var last = s.clock
        var lastStep = Float.MAX_VALUE
        repeat(240) {
            s.step(frame, now)
            now += 16
            val stepped = s.clock - last
            assertTrue(stepped >= 0f)
            assertTrue(stepped <= lastStep + 1e-6f)
            lastStep = stepped
            last = s.clock
        }
    }

    @Test
    fun `a touch winds it back up, gradually rather than all at once`() {
        val s = RoomSettle()
        var now = s.run(0, 25f)
        assertTrue(s.settled)
        s.keepAwake(now)
        s.step(frame, now)
        assertTrue(s.rate > 0f && s.rate < 0.2f)
        now = s.run(now, 4f) { n -> s.keepAwake(n) }
        assertEquals(1f, s.rate, 0f)
    }
}
