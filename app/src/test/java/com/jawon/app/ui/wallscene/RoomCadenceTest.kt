package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things that can go wrong here are a room that throttles when it should not, and a
 * room that stays throttled after it should have stopped. Both are covered.
 */
class RoomCadenceTest {

    // ---- Which divisor, and when ----------------------------------------------------

    @Test
    fun `a finger on the glass is always full rate`() {
        val cadence = RoomCadence().apply { pointerDown = true }

        // Every combination that would otherwise throttle.
        assertEquals(RoomCadence.LIVE, cadence.divisorFor(discTurning = true, ambientMotion = true))
        assertEquals(RoomCadence.LIVE, cadence.divisorFor(discTurning = false, ambientMotion = true))
        assertEquals(RoomCadence.LIVE, cadence.divisorFor(discTurning = false, ambientMotion = false))
    }

    @Test
    fun `anything still animating is always full rate`() {
        val cadence = RoomCadence().apply { moving = true }

        assertEquals(RoomCadence.LIVE, cadence.divisorFor(discTurning = true, ambientMotion = true))
        assertEquals(RoomCadence.LIVE, cadence.divisorFor(discTurning = false, ambientMotion = false))
    }

    @Test
    fun `a turning disc sets the floor above the ambient room`() {
        val cadence = RoomCadence()

        assertEquals(
            RoomCadence.TURNING,
            cadence.divisorFor(discTurning = true, ambientMotion = true)
        )
        assertEquals(
            RoomCadence.DRIFTING,
            cadence.divisorFor(discTurning = false, ambientMotion = true)
        )
    }

    @Test
    fun `reduced motion with nothing turning is the stillest tier`() {
        val cadence = RoomCadence()

        assertEquals(
            RoomCadence.STILL,
            cadence.divisorFor(discTurning = false, ambientMotion = false)
        )
    }

    @Test
    fun `beginFrame clears the motion flag but not the finger`() {
        val cadence = RoomCadence().apply {
            moving = true
            pointerDown = true
        }

        cadence.beginFrame()

        // `moving` is re-derived every frame by the driver; `pointerDown` is a latch owned by
        // the touch stream and must survive, or a press held still would drop to idle.
        assertFalse(cadence.moving)
        assertTrue(cadence.pointerDown)
    }

    // ---- The counter -----------------------------------------------------------------

    @Test
    fun `a divisor of one draws every frame`() {
        val cadence = RoomCadence()

        repeat(10) { assertTrue(cadence.shouldDraw(RoomCadence.LIVE)) }
    }

    @Test
    fun `a divisor of four draws one frame in four`() {
        val cadence = RoomCadence()

        val drawn = (0 until 12).count { cadence.shouldDraw(RoomCadence.DRIFTING) }

        assertEquals(3, drawn)
    }

    @Test
    fun `the first frame at any divisor is drawn`() {
        assertTrue(RoomCadence().shouldDraw(RoomCadence.STILL))
    }

    @Test
    fun `the gap is never longer than the divisor`() {
        val cadence = RoomCadence()
        var gap = 0

        repeat(64) {
            if (cadence.shouldDraw(RoomCadence.STILL)) gap = 0 else gap++
            assertTrue("skipped $gap in a row at divisor ${RoomCadence.STILL}", gap < RoomCadence.STILL)
        }
    }

    /**
     * The property the whole thing rests on: a touch landing during a long idle gap must be
     * answered on the very next frame, not at the end of the gap it interrupted.
     */
    @Test
    fun `dropping to full rate draws immediately however long the room had been idle`() {
        val cadence = RoomCadence()

        // Settle into the stillest cadence and stop mid-gap.
        cadence.shouldDraw(RoomCadence.STILL)
        assertFalse(cadence.shouldDraw(RoomCadence.STILL))
        assertFalse(cadence.shouldDraw(RoomCadence.STILL))

        // A finger lands.
        cadence.pointerDown = true
        val divisor = cadence.divisorFor(discTurning = false, ambientMotion = false)

        assertEquals(RoomCadence.LIVE, divisor)
        assertTrue(cadence.shouldDraw(divisor))
    }

    @Test
    fun `narrowing the divisor mid-gap ends the gap early`() {
        val cadence = RoomCadence()

        cadence.shouldDraw(RoomCadence.STILL)          // drawn, counter reset
        repeat(3) { assertFalse(cadence.shouldDraw(RoomCadence.STILL)) }

        // Three already skipped, and TURNING only wants one — so the next frame draws.
        assertTrue(cadence.shouldDraw(RoomCadence.TURNING))
    }

    @Test
    fun `widening the divisor mid-gap extends it rather than restarting it`() {
        val cadence = RoomCadence()

        cadence.shouldDraw(RoomCadence.TURNING)        // drawn
        assertFalse(cadence.shouldDraw(RoomCadence.TURNING))

        // One already skipped; DRIFTING wants three, so two more before it draws.
        assertFalse(cadence.shouldDraw(RoomCadence.DRIFTING))
        assertFalse(cadence.shouldDraw(RoomCadence.DRIFTING))
        assertTrue(cadence.shouldDraw(RoomCadence.DRIFTING))
    }

    // ---- The tiers themselves --------------------------------------------------------

    /**
     * Guards the reasoning in [RoomCadence.TURNING]'s KDoc. If the disc's rate is ever raised,
     * the step it takes between drawn frames has to be re-argued, not silently inherited.
     */
    @Test
    fun `the disc moves less than two degrees between drawn frames on a 120Hz panel`() {
        val degreesPerDrawnFrame =
            OpenSequence.SPIN_DEG_PER_S * RoomCadence.TURNING / 120f

        assertTrue("$degreesPerDrawnFrame deg/frame", degreesPerDrawnFrame < 2f)
    }

    @Test
    fun `the tiers only ever get coarser`() {
        assertTrue(RoomCadence.LIVE < RoomCadence.TURNING)
        assertTrue(RoomCadence.TURNING < RoomCadence.DRIFTING)
        assertTrue(RoomCadence.DRIFTING < RoomCadence.STILL)
    }
}
