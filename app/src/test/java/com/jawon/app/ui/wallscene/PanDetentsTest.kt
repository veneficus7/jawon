package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "One haptic = one album shift" — as arithmetic.
 *
 * The claim is checkable without a device and the failure mode is one this file has already
 * paid for once: `CrateFlip`'s first version ticked per record under a thumb and fired a
 * couple of hundred times in a single drag, which is a buzz rather than feedback. A pan
 * reports about 120 samples a second, so anything that ticks per *sample* has the same fault
 * with a different cause.
 */
class PanDetentsTest {

    private val pitch = WallDimens.SLOT_PITCH

    @Test
    fun `a drag across one album ticks exactly once`() {
        val d = PanDetents()
        // The first sample only establishes where the wall is.
        assertFalse(d.crossed(0f, 0f))

        var ticks = 0
        // Sixty samples across one slot pitch: a slow, deliberate drag.
        for (i in 1..60) {
            if (d.crossed(pitch * i / 60f, 0f)) ticks++
        }
        assertEquals("one album travelled should be one tick", 1, ticks)
    }

    @Test
    fun `a sample rate of 120 a second does not become a buzz`() {
        val d = PanDetents()
        d.crossed(0f, 0f)
        var ticks = 0
        // Half a second of samples covering a single album: 60 samples, one detent.
        repeat(60) { i -> if (d.crossed(pitch * (i + 1) / 60f, 0f)) ticks++ }
        assertTrue("60 samples over one album gave $ticks ticks", ticks <= 1)
    }

    @Test
    fun `crossing five albums ticks five times however it is sampled`() {
        // Coarse and fine sampling of the same travel must agree, because the hand feels the
        // distance moved and not how often the pointer reported it.
        listOf(10, 50, 400).forEach { samples ->
            val d = PanDetents()
            d.crossed(0f, 0f)
            var ticks = 0
            for (i in 1..samples) {
                if (d.crossed(pitch * 5f * i / samples, 0f)) ticks++
            }
            assertEquals("at $samples samples", 5, ticks)
        }
    }

    @Test
    fun `both axes tick, and a diagonal corner is one event`() {
        val d = PanDetents()
        d.crossed(0f, 0f)
        // Straight up one row.
        assertTrue(d.crossed(0f, WallDimens.ROW_PITCH))

        val diagonal = PanDetents()
        diagonal.crossed(0f, 0f)
        // Crossing a slot and a row in the same sample is ONE tick, because the hand made one
        // movement. Two would read as a stutter at every corner.
        assertTrue(diagonal.crossed(pitch, WallDimens.ROW_PITCH))
    }

    @Test
    fun `the first sample of a gesture never ticks`() {
        // Every touch-down calls reset, including the ones that turn out to be taps. A tick
        // on touch-down would fire when opening a case.
        val d = PanDetents()
        assertFalse("touch-down must be silent", d.crossed(12.5f, -3.25f))
        d.reset()
        assertFalse("and after a reset, wherever the wall is", d.crossed(-40f, 9f))
    }

    @Test
    fun `a jitter across a boundary does not rattle`() {
        // A finger held still on a detent edge still reports samples, and they wander by a
        // fraction of a millimetre. Those must not alternate cells.
        val d = PanDetents()
        val edge = pitch * 0.5f
        d.crossed(edge - 0.0004f, 0f)
        var ticks = 0
        repeat(40) { i ->
            val jitter = if (i % 2 == 0) -0.0004f else -0.0002f
            if (d.crossed(edge + jitter, 0f)) ticks++
        }
        assertEquals("held still should be silent", 0, ticks)
    }

    @Test
    fun `a cell is centred on an album, not started at one`() {
        // So the tick lands as a record passes the middle of the frame, which is where the eye
        // is and where WallSettle brings the wall to rest.
        assertEquals(0, PanDetents.cellOf(0f, pitch))
        assertEquals(0, PanDetents.cellOf(pitch * 0.49f, pitch))
        assertEquals(1, PanDetents.cellOf(pitch * 0.51f, pitch))
        assertEquals(-1, PanDetents.cellOf(-pitch * 0.51f, pitch))
        assertEquals(3, PanDetents.cellOf(pitch * 3f, pitch))
    }

    @Test
    fun `crossing a screen's width is a handful of ticks, not a stream`() {
        // The frame is about 260mm at the default zoom and a slot is 150mm, so a drag across
        // one screen should be felt as about two detents. Sparse enough to be individual,
        // which is the whole point.
        val across = PanDetents.cellsBetween(0f, 0.26f, pitch)
        assertTrue("a screen crossed gave $across ticks", across in 1..3)
    }
}
