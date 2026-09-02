package com.jawon.app.ui.wallscene

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which wedge of the band a tap landed in.
 *
 * The segment ring is one canvas rather than eight nodes, so this function *is* the buttons —
 * every tap on the rack in After Hours goes through it. It is also the kind of geometry that
 * looks obviously right and is quietly off by one at the ends of the arc, where the angle wraps
 * through ±180°, so it is worth pinning down properly.
 */
class SegmentRingTest {

    private val size = 400f
    private val centre = size / 2f

    /**
     * The inner edge of the band, in the same pixels the caller works in.
     *
     * Handed in rather than derived, because the drawing measures the band in dp and this has no
     * density — deriving it here is exactly how the two came to disagree.
     */
    private val inner = centre * 0.62f

    /** A point at [deg] on the canvas, [frac] of the way out to the edge. */
    private fun at(deg: Float, frac: Float): Offset {
        val r = centre * frac
        val rad = Math.toRadians(deg.toDouble())
        return Offset(centre + r * cos(rad).toFloat(), centre + r * sin(rad).toFloat())
    }

    /** Mid-band radially: inside the ring, not in the hole and not off the edge. */
    private val onBand = 0.79f

    @Test
    fun `the middle of each wedge answers with that wedge`() {
        val start = -26f
        val step = 232f / 8f
        for (i in 0 until 8) {
            val mid = start + step * i + step / 2f
            assertEquals("wedge $i", i, segmentAt(at(mid, onBand), size, size, inner))
        }
    }

    @Test
    fun `the hole is not a button`() {
        // The controls live in the middle of the ring. A tap there must fall through to them
        // rather than switching whichever effect happens to be behind.
        assertEquals(-1, segmentAt(at(90f, 0.2f), size, size, inner))
        assertEquals(-1, segmentAt(Offset(centre, centre), size, size, inner))
    }

    @Test
    fun `outside the band is not a button either`() {
        assertEquals(-1, segmentAt(at(90f, 1.4f), size, size, inner))
    }

    @Test
    fun `the gap at the top of the arc is dead`() {
        // The band is open at the top so the record stays visible through it. Straight up is
        // -90°, squarely in that opening, and must not be caught by either end wedge.
        assertEquals(-1, segmentAt(at(-90f, onBand), size, size, inner))
    }

    @Test
    fun `both ends of the arc land in the end wedges`() {
        // **Where an off-by-one would live.** The arc starts at -26° and runs 232°, so it ends
        // at 206° — past 180°, which is where `atan2` wraps to negative. Getting the
        // normalisation wrong shows up only at these two points.
        assertEquals(0, segmentAt(at(-24f, onBand), size, size, inner))
        assertEquals(7, segmentAt(at(204f, onBand), size, size, inner))
    }

    @Test
    fun `just outside either end is dead`() {
        assertEquals(-1, segmentAt(at(-30f, onBand), size, size, inner))
        assertEquals(-1, segmentAt(at(210f, onBand), size, size, inner))
    }

    @Test
    fun `the bottom of the arc is the middle of the band`() {
        // Straight down is 90°, which is the centre of a 232° arc starting at -26°, so it falls
        // on the boundary between the fourth and fifth wedges. Either is right; nothing else is.
        val hit = segmentAt(at(90f, onBand), size, size, inner)
        assertEquals(true, hit == 3 || hit == 4)
    }

    @Test
    fun `a wedge is far larger than the stud it replaced`() {
        // The reason this layout changed at all. A 46dp stud covers about 1660 square points; a
        // wedge of a 44dp band across a 29-degree slice covers several times that, which is what
        // "no negative space" buys.
        val step = 232f / 8f
        val start = -26f
        var hits = 0
        var tried = 0
        // Sweep the whole first wedge and confirm it answers across its width and its depth,
        // rather than only at its centre. The innermost probe sits just *inside* the band rather
        // than exactly on its edge: a point at precisely the inner radius is a coin toss in
        // floating point, and testing a boundary that way asserts nothing about the geometry.
        var d = start + 2f
        while (d < start + step - 2f) {
            for (frac in listOf(0.66f, 0.74f, 0.84f, 0.94f)) {
                tried++
                if (segmentAt(at(d, frac), size, size, inner) == 0) hits++
            }
            d += 2f
        }
        assertEquals("every point inside wedge 0 answers 0", tried, hits)
    }
}
