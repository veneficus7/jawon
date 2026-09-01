package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
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
}
