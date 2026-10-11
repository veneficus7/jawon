package com.jawon.app.ui.vinylscene

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The deck and the record on it, in metres, in the room's own axes: **x to the right, y up, z
 * toward you**. The origin is the middle of the deck's footprint on the table.
 *
 * Every number here has a twin in `tools/models/build_models.py` (the `V_*` constants), which
 * models in Blender's axes — z up, y away — so a y there is a −z here. The app puts the record
 * on the platter, the stylus on the record and a finger on both by these, and a mismatch does not
 * crash: the needle simply rides a few millimetres off the groove it is playing.
 * `VinylDeckTest` holds the arithmetic; `docs/models.md` has the bounds the files were built to.
 */
object VinylDimens {
    const val DECK_W = 0.453f
    const val DECK_D = 0.353f

    /** The top plate's surface, off the table. */
    const val TOP_Y = 0.080f

    /** The platter's centre. A little left and a little back, as on every deck with its arm at the right. */
    const val SPINDLE_X = -0.050f
    const val SPINDLE_Z = -0.012f

    const val PLATTER_R = 0.166f
    const val PLATTER_H = 0.020f
    const val MAT_H = 0.003f

    /** A twelve-inch record. */
    const val LP_R = 0.151f
    const val LP_H = 0.002f
    const val LABEL_R = 0.050f

    /** Where the music is cut: from the run-in at the edge to the dead wax round the label. */
    const val GROOVE_OUT = 0.1465f
    const val GROOVE_IN = 0.0605f

    /** Where a record sits: on the mat. And the surface a stylus rides on. */
    const val RECORD_Y = TOP_Y + PLATTER_H + MAT_H
    const val RECORD_TOP_Y = RECORD_Y + LP_H

    /** The arm's pivot: back right. */
    const val PIVOT_X = 0.152f
    const val PIVOT_Z = -0.106f
    const val PIVOT_Y = TOP_Y + 0.050f

    /** Pivot to stylus — a nine-inch arm. */
    const val ARM_LENGTH = 0.230f

    /** How far off the arm's own line the stylus sits: a headshell is set at an angle to its arm. */
    const val STYLUS_ANGLE_DEG = 4.1f

    /** Where the arm lies when it is not on a record: out past the platter, on its rest. */
    const val REST_RADIUS = 0.176f

    const val SLEEVE = 0.315f
    const val SLEEVE_T = 0.0034f

    /** The table the deck stands on: its front edge, its back, and the floor below. */
    const val TABLE_FRONT_Z = 0.62f
    const val TABLE_BACK_Z = -0.25f
    const val FLOOR_Y = -0.56f

    const val CRATE_W = 0.336f
    const val CRATE_D = 0.420f
}

/**
 * Where the arm has to be for the stylus to be where it is wanted.
 *
 * A pivoted arm does not travel in toward the spindle along a radius; the stylus swings on an arc
 * about the pivot, and where that arc crosses a given groove is a triangle with three known
 * sides. Pure, because it is the difference between a needle that sits in the groove being heard
 * and one that only sits near it — and that is not a thing to find out by squinting at a phone.
 */
object VinylArm {

    /** The pivot's distance from the spindle. */
    val pivotToSpindle: Float = run {
        val dx = VinylDimens.PIVOT_X - VinylDimens.SPINDLE_X
        val dz = VinylDimens.PIVOT_Z - VinylDimens.SPINDLE_Z
        sqrt(dx * dx + dz * dz)
    }

    /** Where the stylus is with the arm swung in to [radius] from the spindle: (x, z) on the deck. */
    fun stylusAt(radius: Float, out: FloatArray) {
        val d = pivotToSpindle
        val ux = (VinylDimens.PIVOT_X - VinylDimens.SPINDLE_X) / d
        val uz = (VinylDimens.PIVOT_Z - VinylDimens.SPINDLE_Z) / d
        val l = VinylDimens.ARM_LENGTH
        val along = (d * d + radius * radius - l * l) / (2f * d)
        val across = sqrt(max(radius * radius - along * along, 0f))
        // Of the two places the arc crosses the groove, the one toward the front of the deck:
        // an arm at the back right plays the side of the record nearest you.
        out[0] = VinylDimens.SPINDLE_X + ux * along - uz * across
        out[1] = VinylDimens.SPINDLE_Z + uz * along + ux * across
    }

    /**
     * How far the arm is turned about its pivot, in degrees about +y, with the stylus at [radius].
     *
     * The model lies along +z with its stylus [VinylDimens.STYLUS_ANGLE_DEG] to one side; this is
     * the turn that carries *that point* — not the tube — onto the groove.
     */
    fun yawDegrees(radius: Float): Float {
        val at = FloatArray(2)
        stylusAt(radius, at)
        val wanted = atan2(at[0] - VinylDimens.PIVOT_X, at[1] - VinylDimens.PIVOT_Z)
        return Math.toDegrees((wanted - stylusBearing).toDouble()).toFloat()
    }

    /** The stylus's own bearing in the arm's frame, from +z toward +x. */
    private val stylusBearing: Float = -Math.toRadians(VinylDimens.STYLUS_ANGLE_DEG.toDouble()).toFloat()

    /** The reverse: the groove radius a stylus is over, with the arm turned to [yawDegrees]. */
    fun radiusAt(yawDegrees: Float): Float {
        val bearing = stylusBearing + Math.toRadians(yawDegrees.toDouble()).toFloat()
        val x = VinylDimens.PIVOT_X + VinylDimens.ARM_LENGTH * sin(bearing) - VinylDimens.SPINDLE_X
        val z = VinylDimens.PIVOT_Z + VinylDimens.ARM_LENGTH * cos(bearing) - VinylDimens.SPINDLE_Z
        return sqrt(x * x + z * z)
    }
}

/**
 * One side of a record: which tracks are on it, and where each lies between the edge and the label.
 *
 * A digital album has no sides, so it is cut into them ([Sides.cut]). Every side is cut across
 * the whole of the band a record has for music, however long it runs — a short side with wide
 * grooves, as a real one is — so the arm always travels the same way across every record, and a
 * needle set down has the most room there can be to find a track by.
 */
class Side(
    /** The index, in the album, of the first track on this side. */
    val firstTrack: Int,
    val trackDurationsMs: List<Long>
) {
    val durationMs: Long = trackDurationsMs.sum()
    val lastTrack: Int get() = firstTrack + trackDurationsMs.size - 1

    operator fun contains(trackIndex: Int): Boolean = trackIndex in firstTrack..lastTrack

    /** How far through the side ([0, 1]) a place in the album is. */
    fun fraction(trackIndex: Int, offsetMs: Long): Float {
        if (durationMs <= 0L) return 0f
        var before = 0L
        for (i in 0 until (trackIndex - firstTrack).coerceIn(0, trackDurationsMs.size)) before += trackDurationsMs[i]
        return ((before + offsetMs).toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }

    /** The groove radius of [fraction] through the side: the edge at 0, the label at 1. */
    fun radiusAt(fraction: Float): Float =
        VinylDimens.GROOVE_OUT - (VinylDimens.GROOVE_OUT - VinylDimens.GROOVE_IN) * fraction.coerceIn(0f, 1f)

    /** And back: how far through the side a stylus at [radius] is. */
    fun fractionAt(radius: Float): Float =
        ((VinylDimens.GROOVE_OUT - radius) / (VinylDimens.GROOVE_OUT - VinylDimens.GROOVE_IN)).coerceIn(0f, 1f)

    /** The place in the album at [fraction] through the side: (track index, milliseconds into it). */
    fun placeAt(fraction: Float): Pair<Int, Long> {
        // Rounded, not cut off: a fraction is a float, and the start of a track would otherwise
        // come back as the last millisecond of the one before it.
        var left = Math.round(fraction.coerceIn(0f, 1f).toDouble() * durationMs)
        for ((i, length) in trackDurationsMs.withIndex()) {
            if (left < length || i == trackDurationsMs.lastIndex) return (firstTrack + i) to left.coerceIn(0L, max(length - 1, 0L))
            left -= length
        }
        return firstTrack to 0L
    }

    /** Where each gap between two tracks lies, as fractions of the side — the smooth rings you can see. */
    fun gaps(): List<Float> {
        if (durationMs <= 0L) return emptyList()
        var at = 0L
        return trackDurationsMs.dropLast(1).map { length ->
            at += length
            (at.toDouble() / durationMs).toFloat()
        }
    }
}

object Sides {

    /** As much as goes on one side before the grooves are too fine to be worth cutting: about 23 minutes. */
    const val LONGEST_SIDE_MS = 23L * 60_000L

    /**
     * Cuts an album into sides: two, or four for a double album, or six.
     *
     * Never through a track — a track is never split across a turn of the record — and as evenly
     * as the tracks allow, because a side of thirty minutes beside one of eight is not how
     * anybody sequences a record. A single track is a single side: there is nothing to turn it
     * over for.
     */
    fun cut(durationsMs: List<Long>, longestSideMs: Long = LONGEST_SIDE_MS): List<Side> {
        if (durationsMs.isEmpty()) return emptyList()
        val total = durationsMs.sum()
        val records = max(1, ceil(total.toDouble() / (2.0 * longestSideMs)).toInt())
        val wanted = (records * 2).coerceAtMost(durationsMs.size)
        val cuts = even(durationsMs, wanted)
        return cuts.indices.map { i ->
            val from = cuts[i]
            val to = if (i == cuts.lastIndex) durationsMs.size else cuts[i + 1]
            Side(from, durationsMs.subList(from, to))
        }
    }

    /**
     * Where to start each of [parts] runs of [lengths] so that the runs are as nearly equal as
     * they can be — the least sum of their squares: the first index of each. Small enough to do
     * exactly; an album is tens of tracks.
     *
     * Not merely "the longest as short as possible", which was the first version: that is
     * satisfied by three full sides and a fourth with one song on it.
     */
    private fun even(lengths: List<Long>, parts: Int): List<Int> {
        val n = lengths.size
        val prefix = LongArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + lengths[i]
        // best[k][i]: the least sum of squares, cutting the first i tracks into k runs.
        val best = Array(parts + 1) { DoubleArray(n + 1) { Double.MAX_VALUE } }
        val from = Array(parts + 1) { IntArray(n + 1) }
        best[0][0] = 0.0
        for (k in 1..parts) {
            for (i in k..n) {
                for (j in k - 1 until i) {
                    if (best[k - 1][j] == Double.MAX_VALUE) continue
                    val run = (prefix[i] - prefix[j]) / 60_000.0
                    val cost = best[k - 1][j] + run * run
                    // On a tie the later cut: a first side a little longer than the second,
                    // which is how records are sequenced.
                    if (cost <= best[k][i]) {
                        best[k][i] = cost
                        from[k][i] = j
                    }
                }
            }
        }
        val starts = IntArray(parts)
        var i = n
        for (k in parts downTo 1) {
            starts[k - 1] = from[k][i]
            i = from[k][i]
        }
        return starts.toList()
    }

    /** The side [trackIndex] is on. */
    fun of(sides: List<Side>, trackIndex: Int): Int =
        sides.indexOfFirst { trackIndex in it }.coerceAtLeast(0)

    /** "A", "B", … for a side; and which record of the set it is on, from 1. */
    fun letter(side: Int): String = ('A' + side.coerceIn(0, 25)).toString()

    fun record(side: Int): Int = side / 2 + 1
}

/**
 * A side as it looks: roughness along the radius, for the record's own material.
 *
 * One line of it — the record's UVs are polar, so u is the radius and nothing varies round the
 * turn (`docs/models.md`). Cut groove scatters light and is rough; where nothing is cut — the
 * run-in at the edge, the gap before each track, the dead wax round the label — the vinyl is as
 * it left the press, and shines. Those rings are how anybody finds a track on a record, and they
 * are in different places on every side there has ever been.
 */
object VinylBands {

    const val GROOVE_ROUGHNESS = 0.20f
    const val SMOOTH_ROUGHNESS = 0.05f

    /** Half the width of the ring between two tracks: a little under a millimetre across. */
    const val GAP_HALF_WIDTH = 0.00045f

    /** Roughness at each of [width] steps from the spindle (0) to the record's edge. */
    fun roughness(side: Side?, width: Int): FloatArray {
        val gaps = side?.gaps().orEmpty().map { side!!.radiusAt(it) }
        return FloatArray(width) { i ->
            val r = (i + 0.5f) / width * VinylDimens.LP_R
            val cut = r <= VinylDimens.GROOVE_OUT && r >= VinylDimens.GROOVE_IN &&
                gaps.none { kotlin.math.abs(r - it) < GAP_HALF_WIDTH }
            if (cut) GROOVE_ROUGHNESS else SMOOTH_ROUGHNESS
        }
    }
}
