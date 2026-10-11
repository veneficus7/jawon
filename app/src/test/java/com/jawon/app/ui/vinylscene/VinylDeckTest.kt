package com.jawon.app.ui.vinylscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The deck's arithmetic: where the needle is, and how an album becomes the sides of a record.
 *
 * None of it can be seen to be wrong on a phone. A stylus four millimetres off the groove being
 * heard still looks like a stylus on a record; an album cut 31 minutes to 9 still plays.
 */
class VinylDeckTest {

    private fun stylus(radius: Float) = FloatArray(2).also { VinylArm.stylusAt(radius, it) }

    private val playable = generateSequence(VinylDimens.GROOVE_IN) { it + 0.004f }.takeWhile { it <= VinylDimens.GROOVE_OUT }.toList()

    // ---- The arm -----------------------------------------------------------------------------

    @Test
    fun `the stylus is on the groove asked for, an arm's length from the pivot, on your side of the record`() {
        for (radius in playable + VinylDimens.REST_RADIUS) {
            val (x, z) = stylus(radius).let { it[0] to it[1] }
            assertEquals("from the spindle", radius, hypot(x - VinylDimens.SPINDLE_X, z - VinylDimens.SPINDLE_Z), 1e-5f)
            assertEquals("from the pivot", VinylDimens.ARM_LENGTH, hypot(x - VinylDimens.PIVOT_X, z - VinylDimens.PIVOT_Z), 1e-5f)
            // Of the two places the arc crosses a groove: nearer you than the pivot, and to the
            // right of the spindle — where a deck with its arm at the back right plays.
            assertTrue("in front of the pivot at $radius", z > VinylDimens.PIVOT_Z + 0.05f)
            assertTrue("right of the spindle at $radius", x > VinylDimens.SPINDLE_X)
        }
    }

    @Test
    fun `turning the arm by the angle given puts the model's own stylus exactly there`() {
        // The model lies along +z with its stylus STYLUS_ANGLE to one side. This is that point,
        // turned about +y as the room turns the arm — and it has to be where stylusAt says, or
        // the tube is aimed at the groove and the needle is beside it.
        val bearing = -Math.toRadians(VinylDimens.STYLUS_ANGLE_DEG.toDouble())
        val localX = (VinylDimens.ARM_LENGTH * sin(bearing)).toFloat()
        val localZ = (VinylDimens.ARM_LENGTH * cos(bearing)).toFloat()
        assertTrue("the stylus is to the left of the arm's line", localX < -0.01f)
        for (radius in playable) {
            val yaw = Math.toRadians(VinylArm.yawDegrees(radius).toDouble())
            val x = VinylDimens.PIVOT_X + (localX * cos(yaw) + localZ * sin(yaw)).toFloat()
            val z = VinylDimens.PIVOT_Z + (-localX * sin(yaw) + localZ * cos(yaw)).toFloat()
            val want = stylus(radius)
            assertEquals("x at $radius", want[0], x, 2e-5f)
            assertEquals("z at $radius", want[1], z, 2e-5f)
        }
    }

    @Test
    fun `the arm swings one way across a side, and the angle says which groove it is over`() {
        var last = VinylArm.yawDegrees(VinylDimens.REST_RADIUS)
        var radius = VinylDimens.REST_RADIUS
        while (radius >= VinylDimens.GROOVE_IN) {
            val yaw = VinylArm.yawDegrees(radius)
            assertTrue("turned back at $radius", yaw <= last + 1e-4f)
            assertEquals("the groove under the stylus at $yaw°", radius, VinylArm.radiusAt(yaw), 2e-5f)
            last = yaw
            radius -= 0.002f
        }
        // From its rest to the last groove is the sweep of a nine-inch arm: some thirty degrees.
        val sweep = VinylArm.yawDegrees(VinylDimens.REST_RADIUS) - VinylArm.yawDegrees(VinylDimens.GROOVE_IN)
        assertTrue("swept $sweep°", sweep in 24f..40f)
    }

    @Test
    fun `at rest the stylus is clear of the record and still over the deck`() {
        val (x, z) = stylus(VinylDimens.REST_RADIUS).let { it[0] to it[1] }
        assertTrue(VinylDimens.REST_RADIUS > VinylDimens.PLATTER_R + 0.005f)
        assertTrue(abs(x) < VinylDimens.DECK_W / 2 - 0.01f)
        assertTrue(abs(z) < VinylDimens.DECK_D / 2 - 0.01f)
    }

    @Test
    fun `the deck's numbers add up - a record lies on the mat, its music inside its edge`() {
        assertEquals(0.103f, VinylDimens.RECORD_Y, 1e-6f)
        assertEquals(0.105f, VinylDimens.RECORD_TOP_Y, 1e-6f)
        assertTrue(VinylDimens.LABEL_R < VinylDimens.GROOVE_IN)
        assertTrue(VinylDimens.GROOVE_IN < VinylDimens.GROOVE_OUT)
        assertTrue(VinylDimens.GROOVE_OUT < VinylDimens.LP_R)
        assertTrue(VinylDimens.LP_R < VinylDimens.PLATTER_R)
        // An arm's pivot stands a little nearer the spindle than its length: the overhang.
        assertTrue(VinylDimens.ARM_LENGTH - VinylArm.pivotToSpindle in 0.004f..0.020f)
    }

    // ---- Sides -------------------------------------------------------------------------------

    private fun minutes(vararg m: Double) = m.map { (it * 60_000).toLong() }

    private fun assertWhole(sides: List<Side>, tracks: List<Long>) {
        // Every track on exactly one side, in order, none of them cut in two.
        assertEquals(0, sides.first().firstTrack)
        assertEquals(tracks.lastIndex, sides.last().lastTrack)
        for (i in 1 until sides.size) assertEquals(sides[i - 1].lastTrack + 1, sides[i].firstTrack)
        assertEquals(tracks, sides.flatMap { it.trackDurationsMs })
        assertTrue(sides.all { it.trackDurationsMs.isNotEmpty() })
    }

    @Test
    fun `an album is two sides, cut between two tracks at the middle of its running time`() {
        val tracks = minutes(9.4, 9.8, 5.6, 11.5, 9.4)      // 45.7 minutes: A is 24.8, B is 20.9
        val sides = Sides.cut(tracks)
        assertEquals(2, sides.size)
        assertWhole(sides, tracks)
        assertEquals(3, sides[0].trackDurationsMs.size)
        assertEquals(3, sides[1].firstTrack)
        // No other cut is more even.
        val best = (1 until tracks.size).minOf { cut -> maxOf(tracks.take(cut).sum(), tracks.drop(cut).sum()) }
        assertEquals(best, sides.maxOf { it.durationMs })
    }

    @Test
    fun `a long album is a double - four sides, none longer than a side can be when the tracks allow`() {
        val tracks = List(18) { ((3.2 + (it * 7 % 5) * 0.5) * 60_000).toLong() }      // about 75 minutes
        val sides = Sides.cut(tracks)
        assertEquals(4, sides.size)
        assertWhole(sides, tracks)
        assertTrue(sides.all { it.durationMs <= Sides.LONGEST_SIDE_MS })
        // Evenly: the longest side is within a track of the shortest.
        assertTrue(sides.maxOf { it.durationMs } - sides.minOf { it.durationMs } <= tracks.max())
        // Two hours is three records.
        assertEquals(6, Sides.cut(List(30) { 4 * 60_000L }).size)
    }

    @Test
    fun `a single is one side, two songs are a side each, and nothing is no record`() {
        assertEquals(1, Sides.cut(minutes(3.5)).size)
        assertEquals(listOf(1, 1), Sides.cut(minutes(3.5, 4.0)).map { it.trackDurationsMs.size })
        assertTrue(Sides.cut(emptyList()).isEmpty())
        // One enormous track cannot be cut, and is not: it is its own side.
        val long = Sides.cut(minutes(41.0, 3.0))
        assertEquals(2, long.size)
        assertEquals(1, long[0].trackDurationsMs.size)
    }

    @Test
    fun `a tie goes to the longer first side`() {
        // 4 + 4 + 4: cut after one or after two, the longest side is eight minutes either way.
        val sides = Sides.cut(minutes(4.0, 4.0, 4.0))
        assertEquals(2, sides[0].trackDurationsMs.size)
    }

    @Test
    fun `which side a track is on, and what it is called`() {
        val sides = Sides.cut(List(18) { 4 * 60_000L })
        assertEquals(4, sides.size)
        for ((i, side) in sides.withIndex()) {
            for (track in side.firstTrack..side.lastTrack) assertEquals(i, Sides.of(sides, track))
        }
        assertEquals(listOf("A", "B", "C", "D"), sides.indices.map(Sides::letter))
        assertEquals(listOf(1, 1, 2, 2), sides.indices.map(Sides::record))
    }

    // ---- A side, across the record --------------------------------------------------------------

    @Test
    fun `a side runs from the edge to the label, and a place on it and a groove are the same thing`() {
        val side = Side(firstTrack = 3, trackDurationsMs = minutes(4.0, 6.0, 2.0))
        assertEquals(VinylDimens.GROOVE_OUT, side.radiusAt(0f), 0f)
        assertEquals(VinylDimens.GROOVE_IN, side.radiusAt(1f), 1e-6f)
        // The start of each track: nothing, a third, five sixths.
        assertEquals(0f, side.fraction(3, 0L), 0f)
        assertEquals(4f / 12f, side.fraction(4, 0L), 1e-6f)
        assertEquals(10f / 12f, side.fraction(5, 0L), 1e-6f)
        assertEquals(1f, side.fraction(5, 2 * 60_000L), 1e-6f)
        // There and back, to within what a millisecond is worth.
        for (track in 3..5) {
            for (offset in listOf(0L, 61_234L, 119_000L)) {
                val f = side.fraction(track, offset)
                assertEquals(f, side.fractionAt(side.radiusAt(f)), 1e-5f)
                val (backTrack, backOffset) = side.placeAt(f)
                assertEquals(track, backTrack)
                assertEquals(offset.toDouble(), backOffset.toDouble(), 60.0)
            }
        }
        // Past either end of the band is the first groove, or the last.
        assertEquals(0f, side.fractionAt(VinylDimens.LP_R), 0f)
        assertEquals(1f, side.fractionAt(VinylDimens.LABEL_R), 0f)
        assertEquals(5, side.placeAt(1f).first)
    }

    @Test
    fun `the rings you find a track by are where the tracks begin`() {
        val side = Side(0, minutes(4.0, 6.0, 2.0))
        assertEquals(listOf(4f / 12f, 10f / 12f), side.gaps())
        assertTrue(Side(0, minutes(5.0)).gaps().isEmpty())

        val width = 4_096
        val rough = VinylBands.roughness(side, width)
        fun at(radius: Float) = rough[(radius / VinylDimens.LP_R * width).toInt().coerceIn(0, width - 1)]
        // Smooth where nothing is cut: the run-in, the dead wax, and the ring before each track.
        assertEquals(VinylBands.SMOOTH_ROUGHNESS, at(VinylDimens.LP_R - 0.001f), 0f)
        assertEquals(VinylBands.SMOOTH_ROUGHNESS, at(VinylDimens.GROOVE_IN - 0.004f), 0f)
        for (gap in side.gaps()) assertEquals(VinylBands.SMOOTH_ROUGHNESS, at(side.radiusAt(gap)), 0f)
        // Rough where the music is.
        for (f in listOf(0.1f, 0.5f, 0.7f, 0.95f)) assertEquals(VinylBands.GROOVE_ROUGHNESS, at(side.radiusAt(f)), 0f)
        // Two rings between three tracks, and no more: count the smooth runs inside the band.
        var rings = 0
        var smooth = false
        for (i in 0 until width) {
            val r = (i + 0.5f) / width * VinylDimens.LP_R
            if (r < VinylDimens.GROOVE_IN + 0.001f || r > VinylDimens.GROOVE_OUT - 0.001f) continue
            val now = rough[i] == VinylBands.SMOOTH_ROUGHNESS
            if (now && !smooth) rings++
            smooth = now
        }
        assertEquals(2, rings)
        // With no record known, the band is simply cut from edge to label.
        assertEquals(VinylBands.GROOVE_ROUGHNESS, VinylBands.roughness(null, width)[(0.1f / VinylDimens.LP_R * width).toInt()], 0f)
    }
}
