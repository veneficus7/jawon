package com.jawon.app.ui.tapescene

import kotlin.math.max
import kotlin.math.pow

/**
 * Where the cassette room is looked at from: always straight ahead, level, at whatever is
 * being looked at — the deck's face, or a rack's spines — because both are things that stand up,
 * and a portrait phone held in front of them is the right shape for both (`docs/formats.md` §6).
 *
 * The pocket player's lens and the vinyl room's: a frame is [SENSOR_H_MM] / [FOCAL_MM] tall for
 * every metre away. Pure, and a finger is put on a key by the same arithmetic run backwards ([ray]).
 */
object TapeCamera {

    const val FOCAL_MM = 50f
    const val SENSOR_H_MM = 24f
    private const val TALL_PER_M = SENSOR_H_MM / FOCAL_MM

    class Shot(val x: Float, val y: Float, val z: Float) {
        override fun toString(): String = "(%.3f, %.3f, %.3f)".format(x, y, z)
    }

    // ---- The deck ---------------------------------------------------------------------------------

    /** The whole deck's face: its meters, door and keys, the width of the frame. */
    const val DECK_WIDTH = 0.150f

    /** And at least this much of it tall — the face and the box standing open on top — on a squarer screen. */
    const val DECK_TALL = 0.27f
    const val DECK_LOOK_Y = 0.110f

    /** Pinched in: the cassette in its door, the reels as large as the phone will have them. */
    const val CLOSE_WIDTH = 0.104f
    const val CLOSE_LOOK_Y = 0.083f

    /** How wide the frame is at the deck's face, [zoom] of the way from the whole deck (0) to the tape (1). */
    fun deckWidth(zoom: Float, aspect: Float): Float {
        val whole = max(DECK_WIDTH, DECK_TALL / aspect)
        val close = CLOSE_WIDTH
        return whole * (close / whole).pow(zoom.coerceIn(0f, 1f))
    }

    fun deck(zoom: Float, aspect: Float): Shot {
        val t = zoom.coerceIn(0f, 1f)
        val width = deckWidth(t, aspect)
        val along = (deckWidth(0f, aspect) - width) / (deckWidth(0f, aspect) - deckWidth(1f, aspect)).coerceAtLeast(1e-6f)
        return Shot(
            x = 0f,
            y = DECK_LOOK_Y + (CLOSE_LOOK_Y - DECK_LOOK_Y) * along,
            z = TapeDimens.FASCIA_T + width * aspect / TALL_PER_M
        )
    }

    // ---- A rack ----------------------------------------------------------------------------------------

    /** A rack and a little of each neighbour, across the frame. */
    const val RACK_WIDTH = 0.172f

    /** How far from a rack's front the camera stands for a display [aspect] times as tall as it is wide. */
    fun rackDistance(aspect: Float): Float = RACK_WIDTH * aspect / TALL_PER_M

    /** How tall the frame is at a rack's front. */
    fun rackTall(aspect: Float): Float = RACK_WIDTH * aspect

    fun rack(x: Float, y: Float, aspect: Float): Shot = Shot(x, y, TapeDimens.RACK_FRONT_Z + rackDistance(aspect))

    // ---- Between them ------------------------------------------------------------------------------------

    fun between(a: Shot, b: Shot, t: Float): Shot = Shot(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t
    )

    // ---- A finger -------------------------------------------------------------------------------------------

    /**
     * The direction through a point of the screen, from [shot]: (dx, dy, dz) into [out], with dz = −1.
     * [px], [py] are pixels from the top left of a frame [widthPx] by [heightPx].
     */
    fun ray(px: Float, py: Float, widthPx: Float, heightPx: Float, out: FloatArray) {
        val wide = TALL_PER_M * widthPx / heightPx
        out[0] = (px / widthPx - 0.5f) * wide
        out[1] = (0.5f - py / heightPx) * TALL_PER_M
        out[2] = -1f
    }

    /** Where the ray through a point of the screen meets the upright plane z = [planeZ]: (x, y) into [out]. */
    fun onUpright(shot: Shot, px: Float, py: Float, widthPx: Float, heightPx: Float, planeZ: Float, out: FloatArray) {
        val d = FloatArray(3)
        ray(px, py, widthPx, heightPx, d)
        val t = shot.z - planeZ
        out[0] = shot.x + d[0] * t
        out[1] = shot.y + d[1] * t
    }

    /**
     * Where the ray through a point of the screen meets the plane through ([ox], [oy], [oz]) with
     * normal ([nx], [ny], [nz]): the point into [out]. False if it does not meet it in front.
     */
    fun onPlane(
        shot: Shot, px: Float, py: Float, widthPx: Float, heightPx: Float,
        ox: Float, oy: Float, oz: Float, nx: Float, ny: Float, nz: Float, out: FloatArray
    ): Boolean {
        val d = FloatArray(3)
        ray(px, py, widthPx, heightPx, d)
        val facing = d[0] * nx + d[1] * ny + d[2] * nz
        if (kotlin.math.abs(facing) < 1e-6f) return false
        val t = ((ox - shot.x) * nx + (oy - shot.y) * ny + (oz - shot.z) * nz) / facing
        if (t <= 0f) return false
        out[0] = shot.x + d[0] * t
        out[1] = shot.y + d[1] * t
        out[2] = shot.z + d[2] * t
        return true
    }

    /** Where a point of the room is on the screen, in pixels from its top left, into [out]. False behind the lens. */
    fun project(shot: Shot, x: Float, y: Float, z: Float, widthPx: Float, heightPx: Float, out: FloatArray): Boolean {
        val depth = shot.z - z
        if (depth <= 0f) return false
        val wide = TALL_PER_M * widthPx / heightPx
        out[0] = ((x - shot.x) / (depth * wide) + 0.5f) * widthPx
        out[1] = (0.5f - (y - shot.y) / (depth * TALL_PER_M)) * heightPx
        return true
    }
}
