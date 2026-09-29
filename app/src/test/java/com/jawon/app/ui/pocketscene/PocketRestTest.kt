package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The device in the hand and at rest. Each rule is one the owner would feel go wrong: the room
 * pulling away while he is reading the screen, or a device that will not come back when he picks
 * the phone up.
 */
class PocketRestTest {

    /** Advances a state through [ms] of frames at 60Hz, the phone lying [flat] or not. */
    private fun RestState.run(from: Long, ms: Long, flat: Boolean): Long {
        var t = from
        while (t < from + ms) {
            update(t, flat)
            t += 16
        }
        update(from + ms, flat)
        return from + ms
    }

    // ---- when it rests ---------------------------------------------------------------------------

    @Test
    fun `twenty seconds untouched puts it to rest, and not a moment before`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        assertFalse(rest.update(19_900, lyingFlat = false))
        assertTrue(rest.update(20_000, lyingFlat = false))
    }

    @Test
    fun `laid flat and still for a second puts it to rest`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        rest.run(from = 1_000, ms = 900, flat = true)
        assertFalse(rest.atRest)
        rest.run(from = 1_900, ms = 200, flat = true)
        assertTrue(rest.atRest)
    }

    // ---- when it comes back ------------------------------------------------------------------------

    @Test
    fun `a touch brings it back, and the clock starts again from the touch`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        rest.update(20_000, lyingFlat = false)
        rest.touched(21_000)
        assertFalse(rest.update(21_016, lyingFlat = false))
        assertFalse(rest.update(40_900, lyingFlat = false))
        assertTrue(rest.update(41_000, lyingFlat = false))
    }

    @Test
    fun `picked up off the table it comes back, and does not fall straight back to rest`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        val put = rest.run(from = 100, ms = 60_000, flat = true)
        assertTrue(rest.atRest)
        assertFalse(rest.update(put + 16, lyingFlat = false))
        // Long past twenty seconds since the last touch — but the pick-up restarted the clock.
        assertFalse(rest.update(put + 5_000, lyingFlat = false))
    }

    @Test
    fun `touched while it lies on the table, it stays in the hand until twenty seconds pass`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        val down = rest.run(from = 100, ms = 2_000, flat = true)
        assertTrue(rest.atRest)
        rest.touched(down)
        // Still lying flat — being used where it lies. Not back to the table a second later.
        rest.run(from = down + 16, ms = 10_000, flat = true)
        assertFalse(rest.atRest)
        // But left alone long enough, it rests again.
        rest.run(from = down + 10_016, ms = 10_100, flat = true)
        assertTrue(rest.atRest)
    }

    @Test
    fun `a phone held up while resting on the clock stays at rest until it is touched`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        rest.update(20_000, lyingFlat = false)
        // Moving about in the hand is not being picked up — it was never put down.
        assertTrue(rest.update(25_000, lyingFlat = false))
    }

    @Test
    fun `resting on the clock and then put down, it wakes when lifted`() {
        val rest = RestState()
        rest.update(0, lyingFlat = false)
        rest.update(20_000, lyingFlat = false)
        val put = rest.run(from = 21_000, ms = 1_500, flat = true)
        assertTrue(rest.atRest)
        assertFalse(rest.update(put + 16, lyingFlat = false))
    }

    // ---- the move between -----------------------------------------------------------------------

    private fun RestFlight.fly(seconds: Float) {
        repeat((seconds * 60).toInt()) { step(1f / 60f) }
    }

    @Test
    fun `the flight arrives without overshooting and stops exactly`() {
        val flight = RestFlight()
        flight.target = 1f
        var highest = 0f
        repeat(240) {
            flight.step(1f / 60f)
            highest = maxOf(highest, flight.progress)
        }
        assertTrue("overshot to $highest", highest <= 1.0001f)
        assertEquals(1f, flight.progress, 0f)
        assertFalse(flight.isMoving)
    }

    @Test
    fun `reversed mid-flight it turns back smoothly and lands exactly in the hand`() {
        val flight = RestFlight()
        flight.target = 1f
        flight.fly(0.6f)
        val before = flight.progress
        flight.target = 0f
        flight.step(1f / 60f)
        assertTrue("jumped from $before to ${flight.progress}", abs(flight.progress - before) < 0.05f)
        flight.fly(3f)
        assertEquals(0f, flight.progress, 0f)
    }

    @Test
    fun `going out, the camera leads and the device follows`() {
        val flight = RestFlight()
        flight.target = 1f
        flight.fly(0.4f)
        assertTrue(flight.camera > flight.device)
    }

    @Test
    fun `coming back is quicker than going out`() {
        val out = RestFlight().apply { target = 1f }
        var outTime = 0
        while (out.isMoving && outTime < 1_000) { out.step(1f / 60f); outTime++ }
        val back = RestFlight().apply { target = 1f; fly(5f); target = 0f }
        var backTime = 0
        while (back.isMoving && backTime < 1_000) { back.step(1f / 60f); backTime++ }
        assertTrue("out $outTime frames, back $backTime", backTime < outTime)
    }

    @Test
    fun `set down, it lands - it does not hover over the cloth and sink`() {
        // The spring this replaced left the device two centimetres over the cloth at a second and
        // took two more to sink the rest of the way. The last two centimetres of the 29cm descent
        // now take a moment, and then it has landed and nothing moves.
        val flight = RestFlight().apply { target = 1f }
        val nearlyDown = 1f - 0.02f / 0.29f
        var frames = 0
        while (flight.device < nearlyDown) { flight.step(1f / 60f); frames++ }
        var landing = 0
        while (flight.isMoving) { flight.step(1f / 60f); landing++ }
        assertTrue("the last 2cm took ${landing / 60f}s", landing <= 15)
        assertTrue("landed after ${(frames + landing) / 60f}s", frames + landing <= 75)
        assertEquals(1f, flight.device, 0f)
        assertEquals(1f, flight.camera, 0f)
    }

    @Test
    fun `picked up, the camera arrives with the device - the room does not slide in after it`() {
        val flight = RestFlight().apply { target = 1f; fly(5f); target = 0f }
        var deviceHome = -1
        var cameraHome = -1
        for (frame in 1..120) {
            flight.step(1f / 60f)
            if (deviceHome < 0 && flight.device == 0f) deviceHome = frame
            if (cameraHome < 0 && flight.camera == 0f) cameraHome = frame
        }
        assertTrue("device home at frame $deviceHome", deviceHome in 1..40)
        assertTrue("camera home $cameraHome frames, device $deviceHome", abs(cameraHome - deviceHome) <= 1)
    }

    @Test
    fun `turned round anywhere, neither the camera nor the device jumps`() {
        for (turnAt in listOf(0.1f, 0.3f, 0.6f, 0.9f, 1.15f)) {
            val flight = RestFlight().apply { target = 1f; fly(turnAt) }
            var camera = flight.camera
            var device = flight.device
            flight.target = 0f
            repeat(90) {
                flight.step(1f / 60f)
                // At the quickest, the camera back to the hand, a share covers 1.875/0.52 of its
                // way a second at the smootherstep's steepest: 0.060 a frame. A jump is a lot more.
                assertTrue("camera jumped turning at $turnAt", abs(flight.camera - camera) < 0.065f)
                assertTrue("device jumped turning at $turnAt", abs(flight.device - device) < 0.065f)
                camera = flight.camera
                device = flight.device
            }
            assertEquals(0f, flight.progress, 0f)
        }
    }

    // ---- the float, and the camera's aim ------------------------------------------------------------

    @Test
    fun `the wander stays in bounds, moves smoothly, and does not repeat`() {
        val w = Wander(seed = 3, periodS = 2f)
        var last = w.at(0f)
        for (i in 1..6_000) {
            val v = w.at(i / 60f)
            assertTrue(v in -1f..1f)
            assertTrue("a step of ${abs(v - last)}", abs(v - last) < 0.06f)
            last = v
        }
        assertTrue(abs(w.at(1.3f) - w.at(1.3f + 2f * 7f)) > 1e-3f || abs(w.at(3.1f) - w.at(3.1f + 14f)) > 1e-3f)
    }

    // ---- put down on a table -----------------------------------------------------------------------

    /** A quaternion for a phone tipped back by [deg] from upright about its x axis. */
    private fun tipped(deg: Double): FloatArray {
        val h = Math.toRadians(deg) / 2.0
        return floatArrayOf(kotlin.math.sin(h).toFloat(), 0f, 0f, kotlin.math.cos(h).toFloat())
    }

    @Test
    fun `face up and still on a table counts as lying flat`() {
        val flat = FlatDetector()
        val q = tipped(0.0)
        repeat(120) { flat.update(q[0], q[1], q[2], q[3], 1f / 60f) }
        assertTrue(flat.lyingFlat)
    }

    @Test
    fun `held level in a trembling hand is not lying flat`() {
        val flat = FlatDetector()
        repeat(240) { i ->
            val q = tipped(4.0 * kotlin.math.sin(i * 0.4))   // a hand's slow wobble, a few degrees
            flat.update(q[0], q[1], q[2], q[3], 1f / 60f)
        }
        assertFalse(flat.lyingFlat)
    }

    @Test
    fun `held upright and still is not lying flat`() {
        val flat = FlatDetector()
        val q = tipped(90.0)
        repeat(120) { flat.update(q[0], q[1], q[2], q[3], 1f / 60f) }
        assertFalse(flat.lyingFlat)
    }

    @Test
    fun `looking straight ahead is level, down pitches down, and right turns right`() {
        val a = FloatArray(2)
        lookAngles(0f, 0f, 1f, 0f, 0f, 0f, a)
        assertEquals(0f, a[0], 1e-4f)
        assertEquals(0f, a[1], 1e-4f)
        lookAngles(0f, 0f, 1f, 0f, -1f, 0f, a)
        assertEquals(-45f, a[0], 1e-3f)
        lookAngles(0f, 0f, 0f, 1f, 0f, 0f, a)
        assertEquals(-90f, a[1], 1e-3f)
    }
}
