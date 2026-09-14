package com.jawon.app.ui.wallscene

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The arithmetic behind the vitrine — two lights down the sides of a bay, lighting the record
 * in it.
 *
 * Separated from [RoomLighting] because it is the one part of that design that can be checked
 * without a device. Everything else about how a room looks is a judgement call settled by a
 * screenshot (`tools/emulator/README.md`); this is inverse-square arithmetic, and three
 * separate claims in `RoomLighting`'s own comments rest on it:
 *
 * 1. the pair lights a sleeve more than three times as evenly as one light underneath it did,
 * 2. each light sits *outside* the cover's edge, which is what keeps its reflection off the
 *    artwork on a glossy plate,
 * 3. a case can still turn a full circle in its bay without striking the strips.
 *
 * All distances are metres, in the bay's own frame: the origin is on the board at the bay's
 * centre, +Y is up and +Z is out of the wall toward the camera.
 */
object VitrineGeometry {

    /** Half the width of a face-on cover — [WallDimens.CASE_D] is its width, not its depth. */
    const val COVER_HALF_W = WallDimens.CASE_D / 2f

    /** Half the height of a cover. */
    const val COVER_HALF_H = WallDimens.CASE_H / 2f

    /**
     * Illuminance at a point on the cover from one light, in candela-relative units.
     *
     * `E = I·cos(θ)/d²` with the cover's normal along +Z, which makes `cos(θ)` the light's
     * own forward distance over the distance to the point. Intensity is left out: every use
     * here is a ratio between two points lit by the same fixtures.
     *
     * [x] and [y] are measured from the centre of the cover.
     */
    fun illuminance(x: Float, y: Float, lightX: Float, lightY: Float, lightZ: Float): Float {
        val dx = x - lightX
        val dy = y - lightY
        val d2 = dx * dx + dy * dy + lightZ * lightZ
        if (d2 <= 0f) return 0f
        return lightZ / (sqrt(d2) * d2)
    }

    /**
     * Illuminance from one tube, summed over the points it is made of.
     *
     * [segments] 1 is the single bulb the vitrine started with; [RoomLighting.VITRINE_SEGMENTS]
     * is the tube that replaced it. The difference between those two is the whole of what
     * "not one spotlight, but led tube light" buys, and [evenness] is where it shows.
     */
    fun tubeIlluminance(x: Float, y: Float, tubeX: Float, segments: Int): Float {
        var sum = 0f
        for (i in 0 until segments) {
            sum += illuminance(
                x, y, tubeX, RoomLighting.vitrineSegmentY(i, segments), RoomLighting.UNDER_FORWARD
            ) / segments
        }
        return sum
    }

    /** Illuminance from this bay's own pair of tubes, at a point measured from the cover's centre. */
    fun pairIlluminance(x: Float, y: Float, segments: Int = RoomLighting.VITRINE_SEGMENTS): Float =
        tubeIlluminance(x, y, -RoomLighting.UNDER_SIDE, segments) +
            tubeIlluminance(x, y, RoomLighting.UNDER_SIDE, segments)

    /**
     * What a record in the middle of a full row actually receives: its own pair, plus the
     * nearest light belonging to each neighbour.
     *
     * **The neighbours are not spill to be designed out.** Every strip stands in the channel
     * between two records and is 18mm from the near edge of the one next door, so a bay's own
     * lights fall off toward its edges exactly where its neighbours' pick up. That is what
     * makes a rack of these read as evenly lit rather than as a row of spotlit squares, and it
     * is why [RoomLighting.UNDER_FALLOFF] is set to reach the next bay rather than to stop
     * short of it. The colours stay separable because a bulb only takes
     * [RoomLighting.UNDER_TINT] of its album's hue.
     */
    fun bayIlluminance(x: Float, y: Float, segments: Int = RoomLighting.VITRINE_SEGMENTS): Float =
        pairIlluminance(x, y, segments) +
            tubeIlluminance(x, y, -(WallDimens.SLOT_PITCH - RoomLighting.UNDER_SIDE), segments) +
            tubeIlluminance(x, y, WallDimens.SLOT_PITCH - RoomLighting.UNDER_SIDE, segments)

    /**
     * Brightest point on the cover over dimmest, sampled on a grid.
     *
     * The number the design is judged by. One light below the sleeve gave **5.1**; the pair
     * gave **1.54** as two bulbs and gives **1.37** as two tubes, **1.36** once the
     * neighbouring bays fill in the edges. That collapse is the whole reason the covers
     * stopped washing at the foot, and [segments] is what the last of it cost.
     */
    fun evenness(
        steps: Int = 9,
        withNeighbours: Boolean = false,
        segments: Int = RoomLighting.VITRINE_SEGMENTS
    ): Float {
        var low = Float.MAX_VALUE
        var high = 0f
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = COVER_HALF_W * (2f * i / (steps - 1) - 1f)
                val y = COVER_HALF_H * (2f * j / (steps - 1) - 1f)
                val e = if (withNeighbours) bayIlluminance(x, y, segments)
                else pairIlluminance(x, y, segments)
                if (e < low) low = e
                if (e > high) high = e
            }
        }
        return if (low <= 0f) Float.MAX_VALUE else high / low
    }

    /**
     * The same, for a single light below the middle of the sleeve — what the underlight was.
     *
     * Kept so the comparison in `RoomLighting`'s header is a computed number rather than a
     * remembered one, and so a change to either arrangement moves both.
     */
    fun underlightEvenness(forward: Float = 0.095f, below: Float = 0.047f, steps: Int = 9): Float {
        var low = Float.MAX_VALUE
        var high = 0f
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = COVER_HALF_W * (2f * i / (steps - 1) - 1f)
                val y = COVER_HALF_H * (2f * j / (steps - 1) - 1f)
                val e = illuminance(x, y, 0f, -below, forward)
                if (e < low) low = e
                if (e > high) high = e
            }
        }
        return if (low <= 0f) Float.MAX_VALUE else high / low
    }

    /**
     * How far a light sits beyond the cover's own edge.
     *
     * Positive means its mirror image lands off the plate, so it cannot put a highlight on the
     * artwork however bright it is. Zero or less and the vitrine is just an underlight that
     * moved sideways.
     */
    fun clearOfCoverEdge(): Float = RoomLighting.UNDER_SIDE - COVER_HALF_W

    /**
     * Air between the rod's inner face and the circle a case sweeps as it turns.
     *
     * The rod is [STRIP_THICKNESS] wide and centred on [STRIP_X]; a case turning about its own
     * axis sweeps a circle of diameter `hypot(CASE_W, CASE_D)`, which is
     * `WallDimens.SLOT_SWEEP`. Negative means a record drives through its own vitrine at some
     * angle — the failure the bay pitch was widened to prevent in the first place.
     */
    fun clearOfTurningCase(): Float =
        (STRIP_X - STRIP_THICKNESS / 2f) -
            hypot(WallDimens.CASE_W, WallDimens.CASE_D) / 2f

    /** The furthest corner of a cover from one of its own lights. */
    fun cornerDistance(): Float {
        val dx = COVER_HALF_W + RoomLighting.UNDER_SIDE
        return sqrt(dx * dx + COVER_HALF_H * COVER_HALF_H + squared(RoomLighting.UNDER_FORWARD))
    }

    /**
     * The nearest point of the *next* record along, from one of these lights: 116mm.
     *
     * Falloff is a hard cutoff in Filament (trap 6), so this against
     * [RoomLighting.UNDER_FALLOFF] is literally whether the bay next door is touched at all —
     * and it is meant to be. See [bayIlluminance]: a strip lives in the channel *between* two
     * records and lights both, which is what evens the wall out.
     */
    fun neighbourDistance(): Float {
        val dx = WallDimens.SLOT_PITCH - COVER_HALF_W - RoomLighting.UNDER_SIDE
        return sqrt(dx * dx + squared(RoomLighting.UNDER_FORWARD))
    }

    /**
     * Where the one visible rod stands: the exact midpoint between this record and its left
     * neighbour, so a row of bays carries one evenly spaced line rather than paired ones.
     *
     * **Not the same as [RoomLighting.UNDER_SIDE], deliberately.** The lights sit at 69.5mm
     * because that is what puts them outside the cover's edge; the rod sits at 75mm because
     * that is what stops it pairing up with the neighbouring bay's. They are 5.5mm apart and a
     * light is invisible, so nothing about that gap can be seen — what can be seen is forty
     * bars becoming twenty, which is what the owner asked for.
     */
    const val STRIP_X = WallDimens.SLOT_PITCH / 2f

    /**
     * Rod section, matching `build_beam` in `tools/models/build_models.py`. Square: it is as
     * deep as it is wide, which is the whole of the fix for "too many glowing bars" — at 48mm
     * deep every off-centre bay showed its side face, 48 x 166mm of emitter apiece.
     */
    const val STRIP_THICKNESS = 0.003f

    private fun squared(v: Float) = v * v
}
