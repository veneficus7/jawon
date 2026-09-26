package com.jawon.app.ui.pocketscene

import com.jawon.app.ui.pocketscene.ClickWheel.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring rocking under a press. Small numbers, but each is a direction a real moulding moves
 * and a wrong sign is a wheel that tips *toward* the thumb.
 */
class WheelRockTest {

    private fun run(rock: WheelRock, seconds: Float, fps: Int = 120) {
        val dt = 1f / fps
        var t = 0f
        while (t < seconds) {
            rock.step(dt)
            t += dt
        }
    }

    @Test
    fun `holding MENU tips the top edge in, and letting go brings it back`() {
        val rock = WheelRock()
        rock.hold(Button.MENU)
        run(rock, 0.3f)
        assertEquals(-WheelRock.TILT_DEG, rock.tiltXDeg, 0.01f)
        assertEquals(0f, rock.tiltYDeg, 1e-4f)
        rock.release()
        run(rock, 0.3f)
        assertEquals(0f, rock.tiltXDeg, 0.01f)
        assertFalse(rock.isMoving)
    }

    @Test
    fun `each side tips its own way`() {
        fun settled(button: Button): WheelRock = WheelRock().also { it.hold(button); run(it, 0.3f) }
        assertTrue(settled(Button.PLAY).tiltXDeg > 0f)
        assertTrue(settled(Button.NEXT).tiltYDeg > 0f)
        assertTrue(settled(Button.PREVIOUS).tiltYDeg < 0f)
        val centre = settled(Button.SELECT)
        assertEquals(WheelRock.BUTTON_TRAVEL_MM, centre.buttonMm, 0.005f)
        assertEquals(0f, centre.tiltXDeg, 1e-4f)
    }

    @Test
    fun `a tap goes in and comes back out on its own`() {
        val rock = WheelRock()
        rock.tap(Button.NEXT)
        run(rock, WheelRock.KICK_S)
        val deepest = rock.tiltYDeg
        assertTrue("a tap should visibly move the ring, got $deepest", deepest > WheelRock.TILT_DEG * 0.5f)
        run(rock, 0.4f)
        assertEquals(0f, rock.tiltYDeg, 0.01f)
        assertFalse(rock.isMoving)
    }

    @Test
    fun `it never overshoots past where it is going`() {
        val rock = WheelRock()
        rock.hold(Button.NEXT)
        var peak = 0f
        repeat(120) {
            rock.step(1f / 120f)
            peak = maxOf(peak, rock.tiltYDeg)
        }
        assertTrue("peak $peak", peak <= WheelRock.TILT_DEG + 1e-3f)
    }

    @Test
    fun `the path is the same at 60 and 120 frames a second`() {
        val a = WheelRock().also { it.hold(Button.MENU) }
        val b = WheelRock().also { it.hold(Button.MENU) }
        run(a, 0.05f, fps = 60)
        run(b, 0.05f, fps = 120)
        assertEquals(a.tiltXDeg, b.tiltXDeg, 0.02f)
    }

    @Test
    fun `the far edge stays level while the pressed edge goes in by the switch's travel`() {
        val rock = WheelRock()
        rock.hold(Button.NEXT)
        run(rock, 0.3f)
        val tilt = Math.toRadians(rock.tiltYDeg.toDouble()).toFloat()
        val r = ClickWheel.WHEEL_R_MM
        // Rotating about the centre moves the rims by ±r·θ; the sink takes r·θ off both.
        val pressedEdge = -r * tilt - rock.sinkMm
        val farEdge = r * tilt - rock.sinkMm
        assertEquals(0f, farEdge, 1e-3f)
        assertEquals(-0.3f, pressedEdge, 0.01f)
    }

    @Test
    fun `a dropped frame does not throw the ring about`() {
        val rock = WheelRock()
        rock.hold(Button.PLAY)
        rock.step(0.5f)
        assertEquals(WheelRock.TILT_DEG, rock.tiltXDeg, 0.02f)
    }
}
