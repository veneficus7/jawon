package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Turning the disc to scrub.
 *
 * The last of the four pure files `ENGINEERING.md` lists as free to test, and the one where being
 * wrong is least visible: a scrub cursor that drifts by a few milliseconds a frame, or that
 * mishandles a wrap at ±π, produces a disc that feels slightly untrustworthy without ever
 * doing anything obviously broken.
 */
class ScratchTest {

    private val pi = Math.PI.toFloat()
    private val twoPi = pi * 2f

    // ---- angles across the seam ----------------------------------------------------

    @Test
    fun `a small turn is just the difference`() {
        assertEquals(0.5f, Scratch.angleDelta(1.0f, 1.5f), 1e-5f)
        assertEquals(-0.5f, Scratch.angleDelta(1.5f, 1.0f), 1e-5f)
    }

    @Test
    fun `crossing the seam takes the short way round`() {
        // The whole reason this function exists: atan2 wraps at ±π, and a naive subtraction
        // there reports most of a full turn in the wrong direction.
        val justBelow = pi - 0.1f
        val justAbove = -pi + 0.1f
        val d = Scratch.angleDelta(justBelow, justAbove)
        assertEquals("should be a small forward step", 0.2f, d, 1e-4f)

        val back = Scratch.angleDelta(justAbove, justBelow)
        assertEquals("and small backward the other way", -0.2f, back, 1e-4f)
    }

    @Test
    fun `a delta is never more than half a turn`() {
        var a = -twoPi
        while (a <= twoPi) {
            var b = -twoPi
            while (b <= twoPi) {
                val d = Scratch.angleDelta(a, b)
                assertTrue("delta $d out of range for $a -> $b", abs(d) <= pi + 1e-4f)
                b += 0.37f
            }
            a += 0.37f
        }
    }

    // ---- rotation to audio time ----------------------------------------------------

    @Test
    fun `a full revolution is the advertised number of seconds`() {
        assertEquals(
            (Scratch.SEC_PER_REV * 1000f).toLong(),
            Scratch.radiansToMs(twoPi)
        )
    }

    @Test
    fun `turning back moves back`() {
        assertTrue(Scratch.radiansToMs(-twoPi) < 0)
    }

    // ---- walking the album -----------------------------------------------------------

    private val album = listOf(180_000L, 240_000L, 200_000L)

    @Test
    fun `moving within a track just moves the offset`() {
        val c = Scratch.advance(Scratch.Cursor(1, 50_000L), 10_000L, album)
        assertEquals(1, c.trackIndex)
        assertEquals(60_000L, c.offsetMs)
    }

    @Test
    fun `running off the end of a track lands in the next one`() {
        val c = Scratch.advance(Scratch.Cursor(0, 175_000L), 10_000L, album)
        assertEquals(1, c.trackIndex)
        assertEquals(5_000L, c.offsetMs)
    }

    @Test
    fun `scrubbing back past zero lands in the previous track`() {
        val c = Scratch.advance(Scratch.Cursor(1, 3_000L), -10_000L, album)
        assertEquals(0, c.trackIndex)
        assertEquals(173_000L, c.offsetMs)
    }

    @Test
    fun `a big move crosses several tracks at once`() {
        val c = Scratch.advance(Scratch.Cursor(0, 0L), 430_000L, album)
        assertEquals(2, c.trackIndex)
        assertEquals(10_000L, c.offsetMs)
    }

    @Test
    fun `the end of the album stops rather than wrapping`() {
        // The prototype wraps modulo the track count. On a real album, scrubbing off the end
        // and landing back at track one reads as a bug.
        val c = Scratch.advance(Scratch.Cursor(2, 190_000L), 10_000_000L, album)
        assertEquals(2, c.trackIndex)
        assertEquals(album[2], c.offsetMs)
    }

    @Test
    fun `the start of the album stops rather than wrapping`() {
        val c = Scratch.advance(Scratch.Cursor(0, 1_000L), -10_000_000L, album)
        assertEquals(0, c.trackIndex)
        assertEquals(0L, c.offsetMs)
    }

    @Test
    fun `an empty album cannot be scrubbed`() {
        val start = Scratch.Cursor(0, 0L)
        assertEquals(start, Scratch.advance(start, 60_000L, emptyList()))
    }

    @Test
    fun `an out of range track index is brought back in`() {
        // The queue can shrink under a scrub in progress — an album edited on another screen.
        val c = Scratch.advance(Scratch.Cursor(99, 0L), 1_000L, album)
        assertTrue("index should be inside the album", c.trackIndex in album.indices)
        val back = Scratch.advance(Scratch.Cursor(-4, 0L), 1_000L, album)
        assertTrue(back.trackIndex in album.indices)
    }

    @Test
    fun `scrubbing forward then back by the same amount returns to the start`() {
        val start = Scratch.Cursor(1, 100_000L)
        val forward = Scratch.advance(start, 150_000L, album)
        val back = Scratch.advance(forward, -150_000L, album)
        assertEquals(start.trackIndex, back.trackIndex)
        assertEquals(start.offsetMs, back.offsetMs)
    }

    @Test
    fun `a zero length track is stepped over rather than trapping the cursor`() {
        // A corrupt or streaming file can report zero duration, and the forward walk would
        // otherwise never make progress through it.
        val withEmpty = listOf(1_000L, 0L, 5_000L)
        val c = Scratch.advance(Scratch.Cursor(0, 0L), 1_500L, withEmpty)
        assertTrue("cursor did not get past the empty track", c.trackIndex >= 1)
    }

    // ---- What a turn is worth ------------------------------------------------------------------

    private val turn = (2.0 * Math.PI).toFloat()

    /** Turns the disc [turns] in steps of [step] radians (signed by [turns]) and says how much music that was. */
    private fun Scratch.Gearing.spin(turns: Float, step: Float = 0.02f): Double {
        var left = kotlin.math.abs(turns) * turn
        var music = 0.0
        while (left > 0f) {
            val d = kotlin.math.min(step, left)
            music += turn(if (turns > 0f) d else -d)
            left -= d
        }
        return music
    }

    @Test
    fun `scratching, a turn of the disc is a record's - whichever way and however far it has been`() {
        val gearing = Scratch.Gearing()
        // Half a turn each way, again and again: every stroke is exactly a record's gearing,
        // and after any number of them the record is back where it started.
        var at = 0.0
        repeat(40) {
            val out = gearing.spin(0.5f)
            assertEquals(Scratch.SEC_PER_REV / 2.0, out, 1e-4)
            at += out
            at += gearing.spin(-0.5f)
        }
        assertEquals(0.0, at, 1e-3)
    }

    @Test
    fun `going round and round, it becomes a way to travel - and no slower than the disc used to be`() {
        val gearing = Scratch.Gearing()
        val first = gearing.spin(1f)
        val second = gearing.spin(1f)
        val third = gearing.spin(1f)
        val fourth = gearing.spin(1f)
        // The first turn is nearly a record's; each one after is worth more; and it tops out.
        assertTrue("first turn was $first", first in 1.8..2.1)
        assertTrue(second > first * 1.5)
        assertTrue(third > second)
        assertEquals(Scratch.SEC_PER_REV * Scratch.GEAR_TOP, fourth, 1e-3)
        assertEquals(fourth, gearing.spin(1f), 1e-3)
        // The disc was six seconds a turn before it could scratch. Five turns of it now go as far.
        assertTrue(first + second + third + fourth * 2 >= 0.85 * 6.0 * 5)
        // And backwards is the same journey.
        val back = Scratch.Gearing()
        assertEquals(-(first + second), back.spin(-2f), 1e-3)
    }

    @Test
    fun `the gearing never jumps - a spin's speed changes as the hand's does and no faster`() {
        var last = Scratch.boost(0f)
        assertEquals(1.0, last, 0.0)
        var run = 0f
        while (run < 4f * turn) {
            run += 0.01f
            val now = Scratch.boost(run)
            assertTrue("fell at $run", now >= last)
            assertTrue("jumped at $run", now - last < 0.01)
            assertEquals(now, Scratch.boost(-run), 0.0)
            last = now
        }
        assertEquals(Scratch.GEAR_TOP, last, 0.0)
        assertEquals(1.0, Scratch.boost(Scratch.GEAR_FROM_TURNS * turn), 1e-6)
    }

    @Test
    fun `turn back and it is a record again from the first degree`() {
        val gearing = Scratch.Gearing()
        gearing.spin(4f)
        // A scratch straight after a long spin: a quarter turn back, then forth.
        val back = gearing.spin(-0.25f)
        val forth = gearing.spin(0.25f)
        val record = Scratch.SEC_PER_REV / 4.0
        // All but the few degrees it takes to know the hand has turned are at a record's gearing.
        assertTrue("back was $back", back < -record && back > -record * 1.2)
        assertEquals(record, forth, 0.03)
    }

    @Test
    fun `a hand that has gone round is travelling, and one that scratches is not`() {
        val gearing = Scratch.Gearing()
        assertFalse(gearing.travelling)
        // Strokes of half a turn, back and forth: scratching, however long it goes on.
        repeat(10) {
            gearing.spin(0.5f)
            assertFalse(gearing.travelling)
            gearing.spin(-0.5f)
            assertFalse(gearing.travelling)
        }
        // Round and round: finding a place. What it lets go of is set down, not thrown.
        gearing.spin(1.5f)
        assertTrue(gearing.travelling)
        // And turned back, a hand again.
        gearing.spin(-0.1f)
        assertFalse(gearing.travelling)
        gearing.spin(-1.5f)
        assertTrue(gearing.travelling)
        gearing.reset()
        assertFalse(gearing.travelling)
    }

    @Test
    fun `a step backwards in a spinning finger's reports does not drop the gearing`() {
        val steady = Scratch.Gearing()
        steady.spin(3f)
        val jittery = Scratch.Gearing()
        var left = 3f * turn
        // The same spin as a touchscreen might report it: mostly forwards, with a small step
        // back every so often.
        var n = 0
        while (left > 0f) {
            val d = kotlin.math.min(0.05f, left)
            jittery.turn(d)
            left -= d
            if (++n % 7 == 0) {
                jittery.turn(-0.02f)
                left += 0.02f
            }
        }
        // Both are at full gearing: the next stretch is worth the same to each.
        assertEquals(steady.spin(0.25f), jittery.spin(0.25f), 1e-3)
    }
}
