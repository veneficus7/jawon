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
        assertEquals(1, cadence.divisorFor(discTurning = true, ambientMotion = true))
        assertEquals(1, cadence.divisorFor(discTurning = false, ambientMotion = true))
        assertEquals(1, cadence.divisorFor(discTurning = false, ambientMotion = false))
    }

    @Test
    fun `anything still animating is always full rate`() {
        val cadence = RoomCadence().apply { moving = true }

        assertEquals(1, cadence.divisorFor(discTurning = true, ambientMotion = true))
        assertEquals(1, cadence.divisorFor(discTurning = false, ambientMotion = false))
    }

    @Test
    fun `a turning disc sets the floor above the ambient room`() {
        val cadence = RoomCadence()

        // 120Hz panel: every second vsync for the disc, every fourth for the ambient room.
        assertEquals(2, cadence.divisorFor(discTurning = true, ambientMotion = true))
        assertEquals(4, cadence.divisorFor(discTurning = false, ambientMotion = true))
    }

    @Test
    fun `reduced motion with nothing turning is the stillest tier`() {
        val cadence = RoomCadence()

        assertEquals(8, cadence.divisorFor(discTurning = false, ambientMotion = false))
    }

    @Test
    fun `the same frame rates come out of a 60Hz panel as a 120Hz one`() {
        // **The regression this exists for.** The intervals used to be fixed vsync counts —
        // every second, fourth, eighth — with their reasoning written in the frame rates those
        // give *on a 120Hz panel*. On a 60Hz panel, which is most phones, the same counts
        // silently halved every rate: the drifting wall ran at 15fps and the still room at 7.5.
        // That is visible as judder, and it is invisible in the code, because nothing said the
        // constants had a panel baked into them.
        val fast = RoomCadence().apply { refreshHz = 120f }
        val slow = RoomCadence().apply { refreshHz = 60f }

        fun fpsOf(cadence: RoomCadence, disc: Boolean, ambient: Boolean) =
            cadence.refreshHz / cadence.divisorFor(disc, ambient)

        assertEquals(fpsOf(fast, true, true), fpsOf(slow, true, true), 0.01f)
        assertEquals(fpsOf(fast, false, true), fpsOf(slow, false, true), 0.01f)
        assertEquals(fpsOf(fast, false, false), fpsOf(slow, false, false), 0.01f)

        // And on the slow panel the counts really are different, so this is not passing by the
        // two sides being identical.
        assertEquals(1, slow.divisorFor(discTurning = true, ambientMotion = true))
        assertEquals(2, slow.divisorFor(discTurning = false, ambientMotion = true))
    }

    @Test
    fun `a panel that reports nonsense falls back rather than dividing by it`() {
        val cadence = RoomCadence()
        cadence.refreshHz = 0f
        assertEquals(RoomCadence.DEFAULT_REFRESH_HZ, cadence.refreshHz, 0f)
        cadence.refreshHz = Float.NaN
        assertEquals(RoomCadence.DEFAULT_REFRESH_HZ, cadence.refreshHz, 0f)

        // Whatever happens, a divisor is never zero or negative — that would be a division by
        // zero in the draw check, or a room that never draws at all.
        cadence.refreshHz = 20f
        assertTrue(cadence.divisorFor(discTurning = true, ambientMotion = true) >= 1)
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

        repeat(10) { assertTrue(cadence.shouldDraw(1)) }
    }

    @Test
    fun `a divisor of four draws one frame in four`() {
        val cadence = RoomCadence()

        val drawn = (0 until 12).count { cadence.shouldDraw(4) }

        assertEquals(3, drawn)
    }

    @Test
    fun `the first frame at any divisor is drawn`() {
        assertTrue(RoomCadence().shouldDraw(8))
    }

    @Test
    fun `the gap is never longer than the divisor`() {
        val cadence = RoomCadence()
        var gap = 0

        repeat(64) {
            if (cadence.shouldDraw(8)) gap = 0 else gap++
            assertTrue("skipped $gap in a row at divisor ${8}", gap < 8)
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
        cadence.shouldDraw(8)
        assertFalse(cadence.shouldDraw(8))
        assertFalse(cadence.shouldDraw(8))

        // A finger lands.
        cadence.pointerDown = true
        val divisor = cadence.divisorFor(discTurning = false, ambientMotion = false)

        assertEquals(1, divisor)
        assertTrue(cadence.shouldDraw(divisor))
    }

    @Test
    fun `narrowing the divisor mid-gap ends the gap early`() {
        val cadence = RoomCadence()

        cadence.shouldDraw(8)          // drawn, counter reset
        repeat(3) { assertFalse(cadence.shouldDraw(8)) }

        // Three already skipped, and TURNING only wants one — so the next frame draws.
        assertTrue(cadence.shouldDraw(2))
    }

    @Test
    fun `widening the divisor mid-gap extends it rather than restarting it`() {
        val cadence = RoomCadence()

        cadence.shouldDraw(2)        // drawn
        assertFalse(cadence.shouldDraw(2))

        // One already skipped; DRIFTING wants three, so two more before it draws.
        assertFalse(cadence.shouldDraw(4))
        assertFalse(cadence.shouldDraw(4))
        assertTrue(cadence.shouldDraw(4))
    }

    // ---- The tiers themselves --------------------------------------------------------

    /**
     * Guards the reasoning in [RoomCadence.TURNING_FPS]'s KDoc. If the disc's spin is ever
     * raised, the step it takes between drawn frames has to be re-argued rather than silently
     * inherited — past about two degrees a frame a slow rotation begins to read as stepped, and
     * the disc carries printed artwork that shows it.
     *
     * Stated against the target frame rate rather than a vsync count, so it holds on any panel.
     */
    @Test
    fun `the disc moves less than two degrees between drawn frames`() {
        val degreesPerDrawnFrame = OpenSequence.SPIN_DEG_PER_S / RoomCadence.TURNING_FPS

        assertTrue("$degreesPerDrawnFrame deg/frame", degreesPerDrawnFrame < 2f)
    }

    @Test
    fun `the tiers only ever get slower`() {
        // Ordered by target frame rate, which is what the tiers now mean. Asserting the vsync
        // counts instead would be a tautology on a fixed panel and wrong on any other.
        assertTrue(RoomCadence.TURNING_FPS > RoomCadence.DRIFTING_FPS)
        assertTrue(RoomCadence.DRIFTING_FPS > RoomCadence.STILL_FPS)
        assertTrue(RoomCadence.STILL_FPS > 0f)
    }

    @Test
    fun `the bottom tier is never drawn faster than 30, even with a finger down`() {
        val c = RoomCadence().apply { refreshHz = 120f; maxFps = QualityTier.LOW.maxFps; pointerDown = true }
        assertEquals(4, c.divisorFor(discTurning = false, ambientMotion = true))
        c.refreshHz = 60f
        assertEquals(2, c.divisorFor(discTurning = false, ambientMotion = true))
        // Every other tier is uncapped: full rate under a finger.
        val free = RoomCadence().apply { refreshHz = 120f; maxFps = QualityTier.HIGH.maxFps; pointerDown = true }
        assertEquals(RoomCadence.LIVE, free.divisorFor(discTurning = false, ambientMotion = true))
    }

    @Test
    fun `a settled room is drawn at a heartbeat, below the still room`() {
        val c = RoomCadence().apply { refreshHz = 120f }
        val settled = c.divisorFor(discTurning = false, ambientMotion = false, settled = true)
        val still = c.divisorFor(discTurning = false, ambientMotion = false, settled = false)
        assertTrue(settled > still)
        assertEquals(30, settled)
    }

    @Test
    fun `Balanced draws the turning disc at 40 on a 120Hz panel, Best at 60`() {
        val c = RoomCadence().apply { refreshHz = 120f }
        assertEquals(2, c.divisorFor(discTurning = true, ambientMotion = true))
        c.turningFps = RoomCadence.TURNING_BALANCED_FPS
        assertEquals(3, c.divisorFor(discTurning = true, ambientMotion = true))
    }

    @Test
    fun `an unknown or missing Graphics choice is Balanced`() {
        assertEquals(Graphics.BALANCED, Graphics.fromName(null))
        assertEquals(Graphics.BALANCED, Graphics.fromName("nonsense"))
        assertEquals(Graphics.LIGHT, Graphics.fromName("LIGHT"))
    }
}
