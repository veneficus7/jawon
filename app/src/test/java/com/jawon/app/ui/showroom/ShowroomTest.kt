package com.jawon.app.ui.showroom

import com.jawon.app.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The showroom between formats: the row you drag along, the pinch that gets you there, and what a
 * tap on a plinth may open. Each rule is one the owner would feel go wrong — a row that will not
 * settle on a plinth, a flick that goes nowhere, a pinch meant for the wall's own zoom that throws
 * him out of the room.
 */
class ShowroomTest {

    /** Lets a released carousel run for [seconds] at 60Hz, counting the ticks it gives. */
    private fun ShowroomCarousel.run(seconds: Float): Int {
        var ticks = 0
        repeat((seconds * 60).toInt()) {
            step(1f / 60f)
            while (ticked()) ticks++
        }
        return ticks
    }

    // ---- the row ---------------------------------------------------------------------------------

    @Test
    fun `a slow drag past halfway settles on the next plinth, and short of it comes back`() {
        val past = ShowroomCarousel(count = 4)
        past.press(0f)
        past.drag(-0.6f)
        past.release(0f)
        past.run(2f)
        assertEquals(1f, past.position, 0f)

        val short = ShowroomCarousel(count = 4)
        short.press(0f)
        short.drag(-0.4f)
        short.release(0f)
        short.run(2f)
        assertEquals(0f, short.position, 0f)
    }

    @Test
    fun `a flick moves on one plinth however short it was, either way`() {
        val onward = ShowroomCarousel(count = 4, start = 1)
        onward.press(0f)
        onward.drag(-0.05f)
        onward.release(-1.2f)
        onward.run(2f)
        assertEquals(2f, onward.position, 0f)

        val back = ShowroomCarousel(count = 4, start = 1)
        back.press(0f)
        back.drag(0.05f)
        back.release(1.2f)
        back.run(2f)
        assertEquals(0f, back.position, 0f)
    }

    @Test
    fun `a hard throw carries past more than one plinth, and never off the row`() {
        val row = ShowroomCarousel(count = 4)
        row.press(0f)
        row.drag(-0.2f)
        row.release(-9f)
        row.run(3f)
        assertEquals(3f, row.position, 0f)
    }

    @Test
    fun `past either end the row gives a little and no further`() {
        val row = ShowroomCarousel(count = 4)
        row.press(0f)
        row.drag(5f)
        assertTrue(row.position < 0f)
        assertTrue(row.position > -ShowroomCarousel.RUBBER)
        row.release(0f)
        row.run(2f)
        assertEquals(0f, row.position, 0f)
    }

    @Test
    fun `it arrives without going past the plinth`() {
        val row = ShowroomCarousel(count = 4)
        row.press(0f)
        row.drag(-0.7f)
        row.release(-1f)
        var furthest = 0f
        repeat(180) {
            row.step(1f / 60f)
            furthest = maxOf(furthest, row.position)
        }
        assertEquals(1f, row.position, 0f)
        assertTrue("overshot to $furthest", furthest <= 1f + 1e-3f)
    }

    @Test
    fun `one tick for each plinth crossed, and none for a drag that comes back`() {
        val across = ShowroomCarousel(count = 4)
        across.press(0f)
        var ticks = 0
        for (i in 1..30) {
            across.drag(-i * 0.1f)
            while (across.ticked()) ticks++
        }
        assertEquals(3, ticks)

        val nudge = ShowroomCarousel(count = 4, start = 2)
        nudge.press(0f)
        nudge.drag(0.3f)
        nudge.release(0f)
        assertEquals(0, nudge.run(2f))
    }

    @Test
    fun `it is still once it has settled, so the room can stop drawing every frame`() {
        val row = ShowroomCarousel(count = 4)
        assertFalse(row.isMoving)
        row.press(0f)
        assertTrue(row.isMoving)
        row.drag(-0.3f)
        row.release(0f)
        assertTrue(row.isMoving)
        row.run(2f)
        assertFalse(row.isMoving)
    }

    // ---- the pinch out of a room -----------------------------------------------------------------

    @Test
    fun `closing the fingers a third again past the limit leaves the room, once`() {
        val out = PullOut()
        out.begin()
        var left = 0
        // A slow pinch, 1% a sample.
        repeat(60) { if (out.pinch(0.99f)) left++ }
        assertEquals(1, left)
        assertTrue(out.left)
        assertEquals(1f, out.progress, 0f)
    }

    @Test
    fun `short of it, letting go springs back and nothing has happened`() {
        val out = PullOut()
        out.begin()
        repeat(10) { out.pinch(0.98f) }
        assertTrue(out.progress > 0.3f)
        assertFalse(out.left)
        out.end()
        repeat(60) { out.step(1f / 60f) }
        assertEquals(0f, out.pull, 0f)
        assertFalse(out.engaged)
    }

    @Test
    fun `opening the fingers again gives the pull back, however it was sampled`() {
        val fine = PullOut()
        fine.begin()
        repeat(20) { fine.pinch(0.99f) }
        val coarse = PullOut()
        coarse.begin()
        coarse.pinch(Math.pow(0.99, 20.0).toFloat())
        assertEquals(fine.pull, coarse.pull, 1e-4f)

        repeat(40) { fine.pinch(1.01f) }
        assertEquals(0f, fine.pull, 0f)
    }

    @Test
    fun `while held it does not spring back`() {
        val out = PullOut()
        out.begin()
        repeat(10) { out.pinch(0.98f) }
        val held = out.pull
        repeat(30) { out.step(1f / 60f) }
        assertEquals(held, out.pull, 0f)
    }

    // ---- the plinths -----------------------------------------------------------------------------

    @Test
    fun `every room has a plinth, and the row is as long as the model's`() {
        Format.entries.forEach { assertEquals(it, Exhibit.of(it).format) }
        assertEquals(ShowroomStage.PLINTHS, Exhibit.entries.size)
    }

    @Test
    fun `a coming plinth never opens, and an unfinished room only where unfinished ones are offered`() {
        assertFalse(Exhibit.opens(Exhibit.VINYL, showUnbuilt = true))
        assertFalse(Exhibit.opens(Exhibit.CASSETTE, showUnbuilt = true))
        assertTrue(Exhibit.opens(Exhibit.CD, showUnbuilt = false))
        Format.entries.filter { !it.built }.forEach {
            assertFalse(Exhibit.opens(Exhibit.of(it), showUnbuilt = false))
            assertTrue(Exhibit.opens(Exhibit.of(it), showUnbuilt = true))
        }
    }

    @Test
    fun `with one finished room and none offered, there is no showroom to pinch into`() {
        val finished = Format.entries.count { it.built }
        assertEquals(finished > 1, Exhibit.reachable(showUnbuilt = false))
        assertTrue(Exhibit.reachable(showUnbuilt = true))
    }

    // ---- the camera ------------------------------------------------------------------------------

    @Test
    fun `the camera is square on to the plinth it is at, whatever else it is doing`() {
        val out = FloatArray(6)
        for (position in listOf(0f, 1f, 2.5f)) {
            for (travel in listOf(0f, 0.5f, 1f)) {
                for (close in listOf(0f, 0.3f, 1f)) {
                    ShowroomCamera.frame(position, travel, close, out)
                    assertEquals(ShowroomStage.plinthX(position), out[0], 1e-6f)
                    assertEquals(out[0], out[3], 1e-6f)
                    assertEquals(0f, out[5], 0f)
                }
            }
        }
    }

    @Test
    fun `travelling pulls it back, and closing in draws it to an arm's length`() {
        val rest = FloatArray(6)
        val moving = FloatArray(6)
        val close = FloatArray(6)
        ShowroomCamera.frame(1f, 0f, 0f, rest)
        ShowroomCamera.frame(1f, 1f, 0f, moving)
        ShowroomCamera.frame(1f, 1f, 1f, close)
        assertTrue(moving[2] > rest[2])
        assertEquals(ShowroomCamera.CLOSE_BACK_M, close[2], 1e-6f)
        // Looked at from above at rest, so the cap reads; level with it close up.
        assertTrue(rest[1] > rest[4])
        assertTrue(close[1] - close[4] < 0.05f)
    }
}
