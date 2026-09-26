package com.jawon.app.ui.pocketscene

import com.jawon.app.ui.pocketscene.ClickWheel.Button
import com.jawon.app.ui.pocketscene.WheelGesture.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The click wheel, as the thumb meets it.
 *
 * What is being guarded is feel, which fails quietly: a wheel that emits a step a few degrees
 * late, presses MENU at the end of a turn that started on it, or scrolls while a thumb drifts
 * across the centre button is not broken in any way a screenshot shows. It is just not the
 * device people remember.
 */
class ClickWheelTest {

    /** A point on the ring at [deg] (0 is 3 o'clock, counter-clockwise positive), in mm. */
    private fun at(deg: Float, r: Float = 14f): Pair<Float, Float> {
        val rad = Math.toRadians(deg.toDouble())
        return (r * cos(rad)).toFloat() to (r * sin(rad)).toFloat()
    }

    /** Drags round the ring from [fromDeg] to [toDeg] in 1° moves, 4ms apart; returns every event. */
    private fun drag(
        g: WheelGesture,
        fromDeg: Float,
        toDeg: Float,
        startMs: Long = 0L,
        msPerDeg: Long = 4L,
        lift: Boolean = true
    ): List<Event> {
        val events = mutableListOf<Event>()
        val (x0, y0) = at(fromDeg)
        events += g.down(x0, y0, startMs)
        val dir = if (toDeg >= fromDeg) 1 else -1
        var deg = fromDeg
        var t = startMs
        while ((toDeg - deg) * dir > 0f) {
            deg += dir
            t += msPerDeg
            val (x, y) = at(deg)
            events += g.move(x, y, t)
        }
        if (lift) {
            val (x, y) = at(deg)
            events += g.up(x, y, t)
        }
        return events
    }

    private fun steps(events: List<Event>) = events.filterIsInstance<Event.Turn>().sumOf { it.steps }

    // ---- where a finger is ------------------------------------------------------------------

    @Test
    fun `the four compass points are the four buttons`() {
        assertEquals(Button.MENU, ClickWheel.buttonAt(0f, 14f))
        assertEquals(Button.NEXT, ClickWheel.buttonAt(14f, 0f))
        assertEquals(Button.PLAY, ClickWheel.buttonAt(0f, -14f))
        assertEquals(Button.PREVIOUS, ClickWheel.buttonAt(-14f, 0f))
    }

    @Test
    fun `each button owns its whole quarter of the ring`() {
        val (x1, y1) = at(90f + 40f)
        assertEquals(Button.MENU, ClickWheel.buttonAt(x1, y1))
        val (x2, y2) = at(90f + 50f)
        assertEquals(Button.PREVIOUS, ClickWheel.buttonAt(x2, y2))
        val (x3, y3) = at(-44f)
        assertEquals(Button.NEXT, ClickWheel.buttonAt(x3, y3))
    }

    @Test
    fun `the middle is the centre button, and far outside is nothing`() {
        assertEquals(Button.SELECT, ClickWheel.buttonAt(1f, -2f))
        assertEquals(Button.SELECT, ClickWheel.buttonAt(0f, ClickWheel.BUTTON_R_MM - 0.1f))
        assertNull(ClickWheel.buttonAt(0f, ClickWheel.WHEEL_R_MM + ClickWheel.RIM_SLOP_MM + 0.5f))
    }

    @Test
    fun `a thumb just past the rim still finds the wheel`() {
        assertEquals(Button.MENU, ClickWheel.buttonAt(0f, ClickWheel.WHEEL_R_MM + 2f))
    }

    // ---- turning ----------------------------------------------------------------------------

    @Test
    fun `a quarter turn clockwise is six steps down`() {
        val events = drag(WheelGesture(), fromDeg = 90f, toDeg = 0f)
        assertEquals(6, steps(events))
    }

    @Test
    fun `counter-clockwise goes up`() {
        val events = drag(WheelGesture(), fromDeg = 0f, toDeg = 90f)
        assertEquals(-6, steps(events))
    }

    @Test
    fun `turning across the seam at nine o'clock is continuous`() {
        // 170° to 190° is a 20° counter-clockwise move that crosses atan2's ±180° seam.
        val g = WheelGesture()
        val events = mutableListOf<Event>()
        var (x, y) = at(170f)
        events += g.down(x, y, 0L)
        for (i in 1..20) {
            val p = at(170f + i)
            x = p.first; y = p.second
            events += g.move(x, y, i * 4L)
        }
        assertEquals(-1, steps(events))
    }

    @Test
    fun `nothing moves until a whole step has turned`() {
        val g = WheelGesture()
        val events = drag(g, fromDeg = 90f, toDeg = 90f - (ClickWheel.STEP_DEG - 1f), lift = false)
        assertEquals(0, steps(events))
        val (x, y) = at(90f - ClickWheel.STEP_DEG - 0.5f)
        assertEquals(1, steps(g.move(x, y, 400L)))
    }

    @Test
    fun `rocking back and forth inside one step does not chatter`() {
        val g = WheelGesture()
        val events = mutableListOf<Event>()
        val (x0, y0) = at(90f)
        events += g.down(x0, y0, 0L)
        // Over the first step boundary, then rock ±5° around it for a while.
        var t = 0L
        for (deg in listOf(80f, 74f, 70f, 76f, 70f, 78f, 71f, 79f, 72f)) {
            t += 20L
            val (x, y) = at(deg)
            events += g.move(x, y, t)
        }
        assertEquals(1, steps(events))
    }

    @Test
    fun `a thumb crossing the centre does not scroll on the way`() {
        val g = WheelGesture()
        val events = mutableListOf<Event>()
        events += g.down(0f, 14f, 0L)
        // Straight down through the middle to the far side: the angle flips by 180°, which is
        // twelve steps of "turn" if the wheel listened to angle near its centre.
        var t = 0L
        var y = 14f
        while (y > -14f) {
            y -= 1f
            t += 8L
            events += g.move(0.3f, y, t)
        }
        assertEquals(0, steps(events))
    }

    // ---- pressing ---------------------------------------------------------------------------

    @Test
    fun `a tap on a compass point presses it`() {
        val g = WheelGesture()
        g.down(0f, 14f, 0L)
        val events = g.up(0.3f, 14.2f, 90L)
        assertEquals(listOf<Event>(Event.Press(Button.MENU)), events)
    }

    @Test
    fun `a tap in the middle is select`() {
        val g = WheelGesture()
        g.down(0.5f, -0.5f, 0L)
        assertEquals(listOf<Event>(Event.Press(Button.SELECT)), g.up(0.5f, -0.5f, 80L))
    }

    @Test
    fun `a turn that started on a button does not press it when it lifts`() {
        val events = drag(WheelGesture(), fromDeg = 90f, toDeg = 45f)
        assertTrue(events.none { it is Event.Press })
    }

    @Test
    fun `a smear is neither a tap nor a turn`() {
        val g = WheelGesture()
        g.down(0f, 14f, 0L)
        // Radially outward 3mm: no angle, but too far to be a press.
        g.move(0f, 15.5f, 30L)
        val events = g.up(0f, 17f, 60L)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a touch that lands off the wheel is ignored entirely`() {
        val g = WheelGesture()
        assertTrue(g.down(0f, 40f, 0L).isEmpty())
        assertFalse(g.isDown)
        assertTrue(g.move(10f, 14f, 10L).isEmpty())
        assertTrue(g.up(10f, 14f, 20L).isEmpty())
    }

    // ---- holding ----------------------------------------------------------------------------

    @Test
    fun `holding a side starts its long form, and lifting ends it without a press`() {
        val g = WheelGesture()
        g.down(14f, 0f, 0L)
        assertTrue(g.poll(ClickWheel.holdMs(Button.NEXT) - 1).isEmpty())
        assertEquals(listOf<Event>(Event.HoldStart(Button.NEXT)), g.poll(ClickWheel.holdMs(Button.NEXT)))
        assertEquals(Button.NEXT, g.held)
        // A hold is reported once, not every frame.
        assertTrue(g.poll(ClickWheel.holdMs(Button.NEXT) + 500).isEmpty())
        assertEquals(listOf<Event>(Event.HoldEnd(Button.NEXT)), g.up(14f, 0f, 2_000L))
        assertNull(g.held)
    }

    @Test
    fun `switching off needs a deliberate hold`() {
        val g = WheelGesture()
        g.down(0f, -14f, 0L)
        assertTrue(g.poll(ClickWheel.holdMs(Button.NEXT)).isEmpty())
        assertEquals(listOf<Event>(Event.HoldStart(Button.PLAY)), g.poll(ClickWheel.holdMs(Button.PLAY)))
    }

    @Test
    fun `a slow turn never becomes a hold`() {
        val g = WheelGesture()
        // A long, slow drag: well past any hold time, but moving the whole while.
        drag(g, fromDeg = 90f, toDeg = 30f, msPerDeg = 40L, lift = false)
        assertTrue(g.poll(10_000L).isEmpty())
    }

    @Test
    fun `a held button is not turned by a thumb that wanders`() {
        val g = WheelGesture()
        g.down(14f, 0f, 0L)
        g.poll(1_000L)
        val events = mutableListOf<Event>()
        for (i in 1..40) {
            val (x, y) = at(-i.toFloat())
            events += g.move(x, y, 1_000L + i * 5L)
        }
        assertEquals(0, steps(events))
    }

    @Test
    fun `a cancelled hold is still ended`() {
        val g = WheelGesture()
        g.down(-14f, 0f, 0L)
        g.poll(1_000L)
        assertEquals(listOf<Event>(Event.HoldEnd(Button.PREVIOUS)), g.cancel())
        assertFalse(g.isDown)
    }

    // ---- speed ------------------------------------------------------------------------------

    @Test
    fun `a slow turn is one item a step, whatever the list`() {
        assertEquals(1, ClickWheel.itemsPerStep(200f, 5_000))
    }

    @Test
    fun `a fast spin through a long list accelerates, up to a cap`() {
        assertTrue(ClickWheel.itemsPerStep(900f, 500) > 1)
        assertEquals(ClickWheel.ACCEL_MAX, ClickWheel.itemsPerStep(10_000f, 500))
    }

    @Test
    fun `a short list never accelerates`() {
        assertEquals(1, ClickWheel.itemsPerStep(10_000f, 6))
    }

    @Test
    fun `the measured speed follows the thumb`() {
        // 1° every millisecond is 1000°/s.
        val events = drag(WheelGesture(), fromDeg = 90f, toDeg = -180f, msPerDeg = 1L, lift = false)
        val last = events.filterIsInstance<Event.Turn>().last()
        assertEquals(1000f, last.degPerSecond, 60f)
    }

    // ---- the touch, back onto the wheel -----------------------------------------------------

    @Test
    fun `unprojecting through a turned, squashed wheel recovers the point`() {
        // A wheel seen at 22px/mm, turned 15° about the vertical and with screen y pointing down.
        val pxPerMm = 22f
        val squash = cos(Math.toRadians(15.0)).toFloat()
        val cx = 700f
        val cy = 2200f
        val r = ClickWheel.WHEEL_R_MM
        val out = FloatArray(2)
        fun screenOf(u: Float, v: Float) = (cx + u * pxPerMm * squash) to (cy - v * pxPerMm)
        val (tx, ty) = screenOf(6f, -9f)
        val (ex, ey) = screenOf(r, 0f)
        val (fx, fy) = screenOf(0f, r)
        assertTrue(ClickWheel.unproject(cx, cy, ex, ey, fx, fy, tx, ty, out))
        assertEquals(6f, out[0], 1e-3f)
        assertEquals(-9f, out[1], 1e-3f)
    }

    @Test
    fun `a wheel seen edge on cannot be unprojected`() {
        val out = FloatArray(2)
        assertFalse(ClickWheel.unproject(0f, 0f, 10f, 0f, 20f, 0f, 5f, 5f, out))
    }
}
