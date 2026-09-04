package com.jawon.app.ui.wallscene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality tiers' safety net.
 *
 * **This had no tests, and it had been silently broken twice.** It was written after three of
 * five testers on S23-class hardware reported the room was "not that smooth", to catch exactly
 * that on a device nobody here owns. It then stopped being able to fire at all — first when
 * `RoomCadence` made most vsyncs deliberately undrawn, so the vsync delta it was reading
 * stopped measuring anything the room did, and again when the panel went to 120Hz and put that
 * delta at 8.3ms against a 20ms threshold it could no longer cross.
 *
 * Neither break was visible, because a watchdog that never fires looks exactly like a device
 * that is coping. That is what these tests are for: a signal nobody watches needs to be a
 * signal something checks.
 */
class FrameWatchTest {

    /** 120Hz, drawing every vsync. */
    private val liveExpected = 1f / 120f

    /** 120Hz at DRIFTING — a drawn frame every fourth vsync. */
    private val driftingExpected = 4f / 120f

    /** Feeds [count] frames arriving at [interval] against [expected]; true if it stepped down. */
    private fun run(watch: FrameWatch, count: Int, interval: Float, expected: Float): Boolean {
        var fired = false
        repeat(count) { if (watch.onDrawnFrame(interval, expected)) fired = true }
        return fired
    }

    @Test
    fun `a device hitting its promise is never stepped down`() {
        val watch = FrameWatch()
        // Exactly on time, for well over a full window.
        assertFalse(run(watch, 1_000, liveExpected, liveExpected))
    }

    @Test
    fun `a little jitter is tolerated`() {
        val watch = FrameWatch()
        // 40% late is past comfortable and short of the 1.5x threshold: scheduling noise, not
        // a device that cannot keep up.
        assertFalse(run(watch, 1_000, liveExpected * 1.4f, liveExpected))
    }

    @Test
    fun `a device consistently missing its promise is stepped down`() {
        val watch = FrameWatch()
        // Twice the promised interval — half the frame rate the cadence asked for.
        assertTrue(run(watch, 300, liveExpected * 2f, liveExpected))
    }

    @Test
    fun `the same interval is healthy at one cadence and late at another`() {
        // The heart of the redesign, and the thing a fixed millisecond threshold cannot express.
        // 33ms is exactly right when the room is drawing every fourth vsync and a catastrophe
        // when it is trying to draw every one — and the old FrameWatch, reading an absolute
        // delta, would have judged both identically.
        val onTime = FrameWatch()
        assertFalse(run(onTime, 1_000, driftingExpected, driftingExpected))

        val late = FrameWatch()
        assertTrue(run(late, 300, driftingExpected, liveExpected))
    }

    @Test
    fun `the regression that broke it - 120Hz vsync spacing is not a fault`() {
        // The literal shape of the bug. At 120Hz the vsync delta is 8.3ms, and the old
        // threshold called anything over 20ms slow, so nothing could ever be reported. Here the
        // same 8.3ms is judged against what the cadence actually promised and correctly reads
        // as a device doing fine.
        val watch = FrameWatch()
        assertFalse(run(watch, 1_000, 1f / 120f, 1f / 120f))
    }

    @Test
    fun `a minority of late frames is a hiccup, not a verdict`() {
        val watch = FrameWatch()
        var fired = false
        // A quarter late — under the one-third ratio the class treats as struggling.
        repeat(1_000) { i ->
            val interval = if (i % 4 == 0) liveExpected * 3f else liveExpected
            if (watch.onDrawnFrame(interval, liveExpected)) fired = true
        }
        assertFalse(fired)
    }

    @Test
    fun `it can fire more than once, so a bad device can fall two tiers`() {
        val watch = FrameWatch()
        // It used to latch after one step, which capped how much help a struggling device could
        // get: one notch and then nothing, however badly it ran. A phone that cannot hold
        // MEDIUM should reach LOW.
        var fires = 0
        repeat(1_000) { if (watch.onDrawnFrame(liveExpected * 3f, liveExpected)) fires++ }
        assertTrue("expected repeated verdicts, got $fires", fires >= 2)
    }

    @Test
    fun `a rough patch costs at most one verdict, never a permanent grudge`() {
        val watch = FrameWatch()

        // Most of a window of bad frames — a cold start, a decode storm, a first import — then
        // sustained good ones.
        run(watch, 200, liveExpected * 3f, liveExpected)

        var fires = 0
        repeat(2_000) { if (watch.onDrawnFrame(liveExpected, liveExpected)) fires++ }

        // Exactly one, and it is worth being precise about why rather than asserting zero.
        //
        // The window is only reset when a verdict is reached, so those 200 bad frames are still
        // in the count when the 240th frame arrives and they carry the ratio past the threshold
        // once. A cold start can therefore cost a single tier step on a device that would have
        // been fine — a real false positive, and the honest bound on this design.
        //
        // What it cannot do is persist: after that verdict the window is clear, and 2,000
        // healthy frames produce nothing further. "Not forever" is the promise the KDoc makes,
        // and it is the one that matters, because the tier only ever moves one way and a device
        // that is actually fine never reaches the floor.
        assertTrue("expected exactly one verdict from the mixed window, got $fires", fires == 1)
    }

    @Test
    fun `a nonsensical promise is ignored rather than treated as failure`() {
        val watch = FrameWatch()
        // Guards the first frames, where the refresh rate or divisor may not be known yet.
        // Dividing by it would make every frame infinitely late and step a healthy device
        // straight to the floor.
        assertFalse(run(watch, 1_000, liveExpected, 0f))
        assertFalse(run(watch, 1_000, liveExpected, -1f))
    }
}
