package com.jawon.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rooms standing aside while Android slides the app in and out.
 *
 * The owner saw the slide drop frames. What is here cannot be seen on an emulator at all — it is
 * a matter of two things wanting one GPU on one phone — so the rules are pinned where they can be.
 */
class RoomCalmTest {

    private val free = Float.MAX_VALUE

    /** What a room is allowed at each vsync from [from], [frames] of them, [everyMs] apart. */
    private fun Calm.caps(from: Long, frames: Int, everyMs: Long = 8L) = List(frames) { frameCap(from + it * everyMs) }

    @Test
    fun `leaving, the room stops the moment its window loses focus - before it is paused`() {
        val calm = Calm()
        calm.resumed(0L)
        calm.focus(true, sideBySide = false, nowMs = 10L)
        assertFalse(calm.away)
        // The swipe up has begun. Nothing has paused the app yet.
        calm.focus(false, sideBySide = false, nowMs = 5_000L)
        assertTrue(calm.away)
        // The gesture is abandoned and the app is back: it draws again.
        calm.focus(true, sideBySide = false, nowMs = 5_400L)
        assertFalse(calm.away)
    }

    @Test
    fun `arriving, it fills its surface at once and then draws at a quarter of its rate until the slide is over`() {
        val calm = Calm()
        calm.resumed(1_000L)
        val caps = calm.caps(from = 1_000L, frames = 100)
        // The first frames at once: a new surface shown empty is a black flash.
        assertTrue(caps.take(Calm.FIRST_FRAMES).all { it == free })
        // Then held back, for as long as the launcher is still drawing the slide...
        val held = caps.drop(Calm.FIRST_FRAMES).takeWhile { it == Calm.SETTLE_FPS }
        assertEquals((Calm.SETTLE_MS / 8).toInt() - Calm.FIRST_FRAMES + 1, held.size)
        // ...and not a frame longer.
        assertTrue(caps.drop(Calm.FIRST_FRAMES + held.size).all { it == free })
        assertEquals(free, calm.frameCap(1_000L + Calm.SETTLE_MS))
        assertEquals(Calm.SETTLE_FPS, Calm().apply { resumed(0L); caps(0L, Calm.FIRST_FRAMES) }.frameCap(Calm.SETTLE_MS - 1), 0f)
    }

    @Test
    fun `back from another app, it settles once - whichever of resume and focus says so first`() {
        val calm = Calm()
        calm.resumed(0L)
        calm.focus(true, false, 20L)
        // Away, paused, and back a minute later: resumed first, focus a little after.
        calm.focus(false, false, 10_000L)
        calm.paused()
        calm.resumed(70_000L)
        assertFalse("drawing again before focus arrives", calm.away)
        calm.caps(70_000L, 10)
        calm.focus(true, false, 70_120L)
        // The focus that follows is the same arrival, not another: the slide is not timed again.
        assertEquals(Calm.SETTLE_FPS, calm.frameCap(70_000L + Calm.SETTLE_MS - 10), 0f)
        assertEquals(free, calm.frameCap(70_000L + Calm.SETTLE_MS + 1))
    }

    @Test
    fun `a room that never went anywhere is never slowed`() {
        val calm = Calm()
        calm.resumed(0L)
        calm.focus(true, false, 30L)
        assertEquals(free, calm.frameCap(5_000L))
        // Focus said again, with nothing having happened: not an arrival.
        calm.focus(true, false, 6_000L)
        assertEquals(free, calm.frameCap(6_008L))
        calm.resumed(7_000L)
        assertEquals(free, calm.frameCap(7_008L))
    }

    @Test
    fun `the screen going off and on is an arrival too, though the window never lost focus`() {
        val calm = Calm()
        calm.resumed(0L)
        calm.focus(true, false, 10L)
        calm.paused()
        calm.resumed(90_000L)
        val caps = calm.caps(90_000L, 10)
        assertEquals(free, caps.first())
        assertEquals(Calm.SETTLE_FPS, caps.last(), 0f)
    }

    @Test
    fun `side by side with another app, losing focus is not leaving`() {
        val calm = Calm()
        calm.resumed(0L)
        calm.focus(true, sideBySide = true, nowMs = 10L)
        // The other half of the screen was touched. This half is still in plain sight.
        calm.focus(false, sideBySide = true, nowMs = 9_000L)
        assertFalse(calm.away)
        assertEquals(free, calm.frameCap(9_500L))
    }

    @Test
    fun `a menu opening over the room parks it, and closing it brings it back`() {
        val calm = Calm()
        calm.resumed(0L)
        calm.focus(true, false, 10L)
        calm.focus(false, false, 3_000L)       // a dropdown has taken the focus
        assertTrue(calm.away)
        calm.focus(true, false, 4_000L)
        assertFalse(calm.away)
        assertEquals(free, calm.frameCap(4_000L))
    }
}
