package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A record with weight: how it moves when a hand lands on it, while it is held, and when the hand
 * lets go. The sound of a scratch is this motion read out as audio, so these are the rules the
 * sound rests on.
 */
class PlatterTest {

    private val dt = 1.0 / 48_000

    private fun Platter.run(seconds: Double, each: (Double) -> Unit = {}) {
        var t = 0.0
        while (t < seconds) {
            step(dt)
            t += dt
            each(t)
        }
    }

    // ---- A hand lands on a turning record ----------------------------------------------------

    @Test
    fun `a touched record winds down - it does not stop dead and it does not spring back`() {
        val platter = Platter()
        platter.place(position = 10.0, rate = 1.0, playing = true)
        platter.grab(handPosition = 0.0)

        var last = 1.0
        var lowest = 1.0
        var stillAfter = -1.0
        platter.run(0.4) { t ->
            // Only ever slowing: no jolt as the grip takes.
            assertTrue("sped up at $t", platter.rate <= last + 1e-6)
            last = platter.rate
            lowest = minOf(lowest, platter.rate)
            if (stillAfter < 0 && abs(platter.rate) < 0.05) stillAfter = t
        }
        // Heard: a few hundredths of a second of falling pitch.
        assertTrue("stopped in $stillAfter", stillAfter in 0.06..0.14)
        // Never backwards: nothing pulls it to where the finger first touched.
        assertTrue(lowest > -0.01)
        // And it slipped forward a little under the finger, as a real one does.
        assertTrue(platter.position - 10.0 in 0.02..0.06)
    }

    @Test
    fun `a record held still is still`() {
        val platter = Platter()
        platter.place(0.0, 1.0, playing = true)
        platter.grab(0.0)
        platter.run(0.3)
        val held = platter.position
        platter.run(2.0) { assertTrue(abs(platter.rate) < 0.01) }
        // No creep, however long it is held.
        assertEquals(held, platter.position, 0.0005)
    }

    // ---- Held: it goes where the hand goes ---------------------------------------------------

    /**
     * A hand scratching back and forth, reported the way a touchscreen reports it — in steps, a
     * little late — and what the record did about it: **how far behind the hand it ran**, and
     * **how unlike the hand's movement its own was** once that delay is set aside. A few
     * thousandths of a second behind is a record under a finger; a different *shape* would be a
     * different sound.
     */
    private class Followed(val lagSeconds: Double, val shapeError: Double)

    private fun scratch(strokesPerSecond: Double, reach: Double, reportsPerSecond: Int, lateBy: Double): Followed {
        val platter = Platter()
        val hand = HandTrack()
        val now = DoubleArray(2)
        platter.place(0.0, 0.0, playing = false)
        platter.grab(0.0)
        hand.land(0.0, 0L)

        fun truth(t: Double) = reach * sin(2 * PI * strokesPerSecond * t)

        val reportEvery = 1.0 / reportsPerSecond
        var nextReport = 0.0
        var t = 0.0
        val times = ArrayList<Double>()
        val places = ArrayList<Double>()
        while (t < 2.0) {
            if (t >= nextReport) {
                // What arrives now is where the finger was a moment ago.
                val then = t - lateBy
                if (then >= 0) hand.move(truth(then), (then * 1e9).toLong(), (t * 1e9).toLong())
                nextReport += reportEvery
            }
            hand.at((t * 1e9).toLong(), now)
            platter.hand(now[0], now[1])
            platter.step(dt)
            t += dt
            if (t > 0.5) {
                times += t
                places += platter.position
            }
        }
        // The delay that best explains what the record did, and what is left over once it is allowed.
        var best = Followed(0.0, Double.MAX_VALUE)
        var lag = 0.0
        while (lag <= 0.040) {
            var squared = 0.0
            for (i in times.indices step 16) {
                val error = places[i] - truth(times[i] - lag)
                squared += error * error
            }
            val rms = sqrt(squared / (times.size / 16)) / reach
            if (rms < best.shapeError) best = Followed(lag, rms)
            lag += 0.0005
        }
        return best
    }

    @Test
    fun `a held record does what the hand does, a hair later`() {
        // Three strokes a second, a tenth of a second of music each way, on a 120Hz screen.
        val followed = scratch(strokesPerSecond = 3.0, reach = 0.1, reportsPerSecond = 120, lateBy = 0.008)
        assertTrue("behind by ${followed.lagSeconds * 1000}ms", followed.lagSeconds <= 0.019)
        assertTrue("shape off by ${followed.shapeError * 100}%", followed.shapeError < 0.02)
    }

    @Test
    fun `it keeps up with fast scratching, and with a slow screen`() {
        val fast = scratch(strokesPerSecond = 7.0, reach = 0.04, reportsPerSecond = 120, lateBy = 0.008)
        assertTrue("fast: behind by ${fast.lagSeconds * 1000}ms", fast.lagSeconds <= 0.019)
        assertTrue("fast: shape off by ${fast.shapeError * 100}%", fast.shapeError < 0.05)
        val slowScreen = scratch(strokesPerSecond = 3.0, reach = 0.1, reportsPerSecond = 60, lateBy = 0.016)
        assertTrue("60Hz: behind by ${slowScreen.lagSeconds * 1000}ms", slowScreen.lagSeconds <= 0.036)
        assertTrue("60Hz: shape off by ${slowScreen.shapeError * 100}%", slowScreen.shapeError < 0.02)
        // A screen that reports twice as often is followed twice as closely.
        val quickScreen = scratch(strokesPerSecond = 5.0, reach = 0.06, reportsPerSecond = 240, lateBy = 0.006)
        assertTrue("240Hz: behind by ${quickScreen.lagSeconds * 1000}ms", quickScreen.lagSeconds <= 0.012)
    }

    @Test
    fun `a screen's steps do not reach the pitch as a buzz`() {
        // A steady drag at half speed, reported in 120 steps a second: the record's speed must
        // stay close to steady, not saw-tooth with the reports.
        val platter = Platter()
        val hand = HandTrack()
        val now = DoubleArray(2)
        platter.place(0.0, 0.0, playing = false)
        platter.grab(0.0)
        hand.land(0.0, 0L)
        var t = 0.0
        var nextReport = 0.0
        var lowest = 9.0
        var highest = -9.0
        while (t < 1.5) {
            if (t >= nextReport) {
                hand.move(0.5 * t, (t * 1e9).toLong(), (t * 1e9).toLong())
                nextReport += 1.0 / 120
            }
            hand.at((t * 1e9).toLong(), now)
            platter.hand(now[0], now[1])
            platter.step(dt)
            t += dt
            if (t > 0.5) {
                lowest = minOf(lowest, platter.rate)
                highest = maxOf(highest, platter.rate)
            }
        }
        assertTrue("wobbled $lowest..$highest", highest - lowest < 0.03)
        assertEquals(0.5, (highest + lowest) / 2, 0.02)
    }

    @Test
    fun `a finger that stops is a record that stops - silence from the screen means still`() {
        val platter = Platter()
        val hand = HandTrack()
        val now = DoubleArray(2)
        platter.place(0.0, 0.0, playing = false)
        platter.grab(0.0)
        hand.land(0.0, 0L)
        var t = 0.0
        var nextReport = 0.0
        while (t < 1.0) {
            // Dragging at normal speed for 0.4s, and then no more reports at all.
            if (t < 0.4 && t >= nextReport) {
                hand.move(t, (t * 1e9).toLong(), (t * 1e9).toLong())
                nextReport += 1.0 / 120
            }
            hand.at((t * 1e9).toLong(), now)
            platter.hand(now[0], now[1])
            platter.step(dt)
            t += dt
            if (t > 0.4 + 0.1) assertTrue("still turning at $t: ${platter.rate}", abs(platter.rate) < 0.02)
        }
        // And it stopped where the finger did: nothing carried it past.
        assertEquals(0.4, platter.position, 0.01)
    }

    // ---- Let go ------------------------------------------------------------------------------

    @Test
    fun `let go of a still record and the motor winds it up to speed, once, without overshooting`() {
        val platter = Platter()
        platter.place(5.0, 0.0, playing = false)
        platter.grab(0.0)
        platter.run(0.2)
        platter.release(playing = true)

        var last = platter.rate
        var atSpeedAfter = -1.0
        platter.run(0.6) { t ->
            assertTrue("fell back at $t", platter.rate >= last - 1e-9)
            assertTrue("overshot at $t", platter.rate <= 1.0 + 1e-6)
            last = platter.rate
            if (atSpeedAfter < 0 && platter.atSpeed) atSpeedAfter = t
        }
        // A wind-up you can hear, over before you could be waiting for it.
        assertTrue("took $atSpeedAfter", atSpeedAfter in 0.12..0.32)
    }

    @Test
    fun `a record thrown backwards spins back, slows, turns and comes up to speed - a backspin`() {
        val platter = Platter()
        platter.place(20.0, -3.0, playing = true)
        var turnedAt = -1.0
        var atSpeedAfter = -1.0
        var furthestBack = 20.0
        platter.run(1.5) { t ->
            furthestBack = minOf(furthestBack, platter.position)
            if (turnedAt < 0 && platter.rate >= 0) turnedAt = t
            if (atSpeedAfter < 0 && platter.atSpeed) atSpeedAfter = t
        }
        assertTrue("turned at $turnedAt", turnedAt in 0.3..0.5)
        assertTrue("at speed after $atSpeedAfter", atSpeedAfter in 0.5..0.9)
        // It really did go backwards through the music before coming back.
        assertTrue(20.0 - furthestBack > 0.4)
    }

    @Test
    fun `the pull of the mat is steady - the pitch glides in a straight line until it eases in`() {
        val platter = Platter()
        platter.place(0.0, -3.0, playing = true)
        val speeds = ArrayList<Double>()
        platter.run(0.3) { speeds += platter.rate }
        // Sampled a tenth of a second apart, the speed has risen by the same amount each time.
        val step = (0.1 / dt).toInt()
        val first = speeds[step] - speeds[0]
        val second = speeds[2 * step] - speeds[step]
        assertEquals(first, second, 0.01)
        assertEquals(Platter.MOTOR_ACCEL * 0.1, first, 0.02)
    }

    @Test
    fun `where the record will be when it is back at speed can be known the moment it is let go`() {
        for (from in listOf(0.0, -3.0, 0.4, 3.5)) {
            val platter = Platter()
            platter.place(7.0, from, playing = true)
            val told = platter.settle()
            var seconds = 0.0
            while (!platter.atSpeed && seconds < 4.0) {
                platter.step(dt)
                seconds += dt
            }
            assertEquals("time from $from", told.seconds, seconds, 0.003)
            assertEquals("travel from $from", told.travelled, platter.position - 7.0, 0.002)
        }
    }

    @Test
    fun `with the deck's tempo off centre the motor winds up to that speed, not to normal`() {
        for (tempo in listOf(0.5, 0.8, 1.25, 2.0)) {
            val platter = Platter()
            platter.place(3.0, 0.0, playing = true, nominal = tempo)
            val told = platter.settle()
            var seconds = 0.0
            while (!platter.atSpeed && seconds < 4.0) {
                platter.step(dt)
                seconds += dt
            }
            // It arrives, at the tempo, when and where it said it would...
            assertEquals("speed at tempo $tempo", tempo, platter.rate, Platter.AT_SPEED)
            assertEquals("time at tempo $tempo", told.seconds, seconds, 0.003)
            assertEquals("travel at tempo $tempo", told.travelled, platter.position - 3.0, 0.002)
            // ...and stays: a player running at that tempo and a record beside it keep step.
            val from = platter.position
            platter.run(1.0)
            assertEquals("a second of playing at tempo $tempo", tempo, platter.position - from, 0.002)
        }
        // And said nothing of, the motor's speed is normal speed.
        assertEquals(1.0, Platter().apply { place(0.0, 0.0, playing = true) }.nominal, 0.0)
    }

    @Test
    fun `a record leaves the hand no faster than a hand can throw one`() {
        // Spun to find a place, the disc is geared up and the record under the finger can be
        // going twelve times its speed. Let go at that and left to the mat, it would fly on for
        // seconds: here it is let go at a throw's speed, and stops, or comes back, like one.
        for (playing in listOf(false, true)) {
            for (direction in listOf(1.0, -1.0)) {
                val platter = Platter()
                platter.place(100.0, 0.0, playing = false)
                platter.grab(0.0)
                var hand = 0.0
                platter.run(0.5) {
                    hand += direction * 12.0 * dt
                    platter.hand(hand, direction * 12.0)
                }
                assertTrue("under the hand it was going ${platter.rate}", abs(platter.rate) > 10.0)
                val from = platter.position
                platter.release(playing)
                assertEquals(direction * Platter.LET_GO_FASTEST, platter.rate, 1e-9)
                var seconds = 0.0
                while (!(platter.atRest || platter.atSpeed) && seconds < 10.0) {
                    platter.step(dt)
                    seconds += dt
                }
                assertTrue("took $seconds to settle", seconds < 0.8)
                assertTrue("travelled ${platter.position - from}", abs(platter.position - from) < 1.3)
            }
        }
        // And one thrown gently is let go as it was.
        assertEquals(-1.7, Platter.letGoAt(-1.7), 0.0)
        assertEquals(0.3, Platter.letGoAt(0.3), 0.0)
    }

    @Test
    fun `with the motor off a record let go coasts to a stop, a spun one for longer`() {
        fun stopsAfter(from: Double): Double {
            val platter = Platter()
            platter.place(0.0, from, playing = false)
            var seconds = 0.0
            while (!platter.atRest && seconds < 5.0) {
                platter.step(dt)
                seconds += dt
            }
            return seconds
        }
        val nudge = stopsAfter(1.0)
        val spin = stopsAfter(3.0)
        assertTrue(nudge in 0.15..0.5)
        assertTrue(spin > nudge + 0.3)
        assertFalse(Platter().apply { place(0.0, 1.0, playing = false) }.atRest)
    }

    @Test
    fun `the hand glides to each report in the time the finger took, and arrives`() {
        val hand = HandTrack()
        val now = DoubleArray(2)
        hand.land(0.0, 0L)
        // 10ms later the screen says the finger is 10ms of music further on.
        hand.move(0.010, reportNanos = 10_000_000L, nowNanos = 10_000_000L)
        hand.at(10_000_000L, now)
        assertEquals(0.0, now[0], 1e-9)
        assertEquals(1.0, now[1], 1e-6)
        hand.at(15_000_000L, now)
        assertEquals(0.005, now[0], 1e-9)
        // Arrived, and with no more reports: there, and still. Nothing carries it past.
        hand.at(20_000_000L, now)
        assertEquals(0.010, now[0], 1e-9)
        assertEquals(0.0, now[1], 1e-9)
        hand.at(900_000_000L, now)
        assertEquals(0.010, now[0], 1e-9)
        assertEquals(0.0, now[1], 1e-9)
    }

    @Test
    fun `a new report takes over from wherever the last glide had reached - no step`() {
        val hand = HandTrack()
        val now = DoubleArray(2)
        hand.land(0.0, 0L)
        hand.move(0.010, reportNanos = 10_000_000L, nowNanos = 10_000_000L)
        // Halfway there, the next report lands.
        hand.at(15_000_000L - 1, now)
        val before = now[0]
        hand.move(0.020, reportNanos = 15_000_000L, nowNanos = 15_000_000L)
        hand.at(15_000_000L, now)
        assertEquals(before, now[0], 1e-6)
    }

    @Test
    fun `reports stamped together, far apart or absurdly do not make an impossible hand`() {
        val hand = HandTrack()
        val now = DoubleArray(2)
        hand.land(0.0, 0L)
        // A jump of five seconds of music in a millisecond: the speed is capped.
        hand.move(5.0, reportNanos = 1_000_000L, nowNanos = 1_000_000L)
        hand.at(1_500_000L, now)
        assertEquals(HandTrack.FASTEST, now[1], 1e-6)
        // The same instant twice: still a small move, not a division by nothing.
        hand.move(5.1, reportNanos = 1_000_000L, nowNanos = 2_000_000L)
        hand.at(2_500_000L, now)
        assertTrue(now[1].isFinite())
        // After a long pause the next report is caught up with promptly, not strolled to.
        hand.move(6.0, reportNanos = 3_000_000_000L, nowNanos = 3_000_000_000L)
        hand.at(3_000_000_000L + HandTrack.LONGEST_NANOS, now)
        assertEquals(6.0, now[0], 1e-9)
    }
}
