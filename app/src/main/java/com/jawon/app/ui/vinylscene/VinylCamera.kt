package com.jawon.app.ui.vinylscene

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the room is looked at from.
 *
 * Two views from straight above, and they are one zoom (`docs/formats.md` §5): **the whole deck**,
 * with the back of the record's sleeve laid out in front of it — its sides printed there, which
 * is the tracklist — and **the record under the thumb**, wider than the phone, which is the
 * largest thing a hand can be given to turn. And one from where you would stand to dig through
 * a crate.
 *
 * Pure: every shot is arithmetic on the display's shape, and a finger is put on the record by
 * the same arithmetic run backwards ([onPlane]). The CD room found that guessing a lens from a
 * formula disagreed with the screen by a fifth of it (`ActiveCase`); this is the pocket player's
 * formula, which was measured — a frame is `SENSOR_H_MM / FOCAL_MM` tall for every metre away.
 */
object VinylCamera {

    const val FOCAL_MM = 50f
    const val SENSOR_H_MM = 24f

    /** The camera: where it is, and how far below level it looks (−90 is straight down, the far edge of the deck up-screen). */
    class Shot(val x: Float, val y: Float, val z: Float, val pitchDeg: Float)

    // ---- From above ---------------------------------------------------------------------------

    /** What the whole-deck view has to hold: the deck's width with a finger's margin, and from its back edge to the sleeve's front. */
    const val DECK_WIDTH = 0.490f
    const val DECK_DEPTH = 0.86f
    private const val DECK_CENTRE_X = 0f
    private const val DECK_CENTRE_Z = (-VinylDimens.DECK_D / 2 + SLEEVE_FRONT_Z) / 2

    /** The record under the thumb: the frame's short side is this much of a 302mm record. */
    const val THUMB_WIDTH = 0.212f
    private const val THUMB_CENTRE_X = VinylDimens.SPINDLE_X + 0.014f
    private const val THUMB_CENTRE_Z = VinylDimens.SPINDLE_Z + 0.022f

    /** How wide the frame is at the surface looked at, [zoom] of the way from the whole deck (0) to the record (1). */
    fun width(zoom: Float, aspect: Float): Float {
        // On a phone held upright the deck's width decides; turned on its side, or on something
        // squarer, its depth does — nothing is ever cut off to fill a screen.
        val deck = max(DECK_WIDTH, DECK_DEPTH / aspect)
        val record = if (aspect >= 1f) THUMB_WIDTH else THUMB_WIDTH / aspect
        // A zoom is felt as a ratio, so it is one: equal pinches are equal factors.
        return deck * (record / deck).pow(zoom.coerceIn(0f, 1f))
    }

    /** The shot from above at [zoom], for a display [aspect] times as tall as it is wide. */
    fun play(zoom: Float, aspect: Float): Shot {
        val t = zoom.coerceIn(0f, 1f)
        // The frame slides as it closes in, and by the same curve: linearly it would leave the
        // record half out of an already-narrow frame before it had got there.
        val along = 1f - (width(t, aspect) - width(1f, aspect)) / (width(0f, aspect) - width(1f, aspect))
        val plane = VinylDimens.TOP_Y + (VinylDimens.RECORD_TOP_Y - VinylDimens.TOP_Y) * along
        return Shot(
            x = DECK_CENTRE_X + (THUMB_CENTRE_X - DECK_CENTRE_X) * along,
            y = plane + width(t, aspect) * aspect * FOCAL_MM / SENSOR_H_MM,
            z = DECK_CENTRE_Z + (THUMB_CENTRE_Z - DECK_CENTRE_Z) * along,
            pitchDeg = -90f
        )
    }

    /**
     * Where on the level surface at height [planeY] a point of the screen is, seen from [shot]
     * (which looks straight down): (x, z) into [out]. [px], [py] are pixels from the top left of
     * a frame [widthPx] by [heightPx].
     */
    fun onPlane(shot: Shot, px: Float, py: Float, widthPx: Float, heightPx: Float, planeY: Float, out: FloatArray) {
        val tall = (shot.y - planeY) * SENSOR_H_MM / FOCAL_MM
        val wide = tall * widthPx / heightPx
        out[0] = shot.x + (px / widthPx - 0.5f) * wide
        out[1] = shot.z + (py / heightPx - 0.5f) * tall
    }

    // ---- At the crate ----------------------------------------------------------------------------

    /** The record being read in the crate: what the eye is on. */
    private const val CRATE_LOOK_Y = VinylDimens.FLOOR_Y + 0.185f
    private const val CRATE_LOOK_Z = VinylDimens.CRATE_Z - 0.035f

    /** From above and in front, as you would stand over one: about fifty degrees down. */
    private const val CRATE_UP = 0.78f
    private const val CRATE_BACK = 0.62f
    private const val CRATE_WIDTH = 0.40f

    /**
     * The shot at the crate; [along] metres further back in it than its middle, for following the
     * record being read to the back of a crate that is nearly gone through.
     */
    fun crate(aspect: Float, along: Float = 0f): Shot {
        val away = max(1.2f, CRATE_WIDTH * aspect * FOCAL_MM / SENSOR_H_MM)
        val length = sqrt(CRATE_UP * CRATE_UP + CRATE_BACK * CRATE_BACK)
        return Shot(
            x = 0f,
            y = CRATE_LOOK_Y + away * CRATE_UP / length,
            z = CRATE_LOOK_Z + along + away * CRATE_BACK / length,
            pitchDeg = -Math.toDegrees(atan2(CRATE_UP, CRATE_BACK).toDouble()).toFloat()
        )
    }

    // ---- A finger, from any shot ---------------------------------------------------------------------

    /**
     * Where a point of the screen is on a sleeve standing in the crate: how far across it from
     * its middle, and how far up it from its foot, in metres, into [out]. The sleeve stands on
     * its bottom edge at height [footY] and distance [footZ], leaning [leanDeg] with its top
     * toward you. False if the sleeve's plane is not in front of the lens at that point.
     *
     * The same lens as [onPlane], for a camera that is not looking straight down: a ray through
     * the point, met with the plane the sleeve lies in.
     */
    fun onSleeve(
        shot: Shot, px: Float, py: Float, widthPx: Float, heightPx: Float,
        footY: Float, footZ: Float, leanDeg: Float, out: FloatArray
    ): Boolean {
        val pitch = Math.toRadians(shot.pitchDeg.toDouble())
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        val tall = SENSOR_H_MM / FOCAL_MM
        val wide = tall * widthPx / heightPx
        val sx = (px / widthPx - 0.5f) * wide
        val sy = (0.5f - py / heightPx) * tall
        // Forward is (0, sin, -cos) of the pitch and up is (0, cos, sin): straight down, the far edge of the table is up-screen.
        val dx = sx
        val dy = sp + sy * cp
        val dz = -cp + sy * sp
        val lean = Math.toRadians(leanDeg.toDouble())
        val ny = -sin(lean).toFloat()
        val nz = cos(lean).toFloat()
        val facing = dy * ny + dz * nz
        if (abs(facing) < 1e-6f) return false
        val t = ((footY - shot.y) * ny + (footZ - shot.z) * nz) / facing
        if (t <= 0f) return false
        val hy = shot.y + t * dy - footY
        val hz = shot.z + t * dz - footZ
        out[0] = shot.x + t * dx
        out[1] = hy * cos(lean).toFloat() + hz * sin(lean).toFloat()
        return true
    }

    /** Where a point of the room is on the screen, in pixels from its top left, into [out]. False behind the lens. */
    fun project(shot: Shot, x: Float, y: Float, z: Float, widthPx: Float, heightPx: Float, out: FloatArray): Boolean {
        val pitch = Math.toRadians(shot.pitchDeg.toDouble())
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        val vx = x - shot.x
        val vy = y - shot.y
        val vz = z - shot.z
        val depth = vy * sp - vz * cp
        if (depth <= 0f) return false
        val up = vy * cp + vz * sp
        val tall = SENSOR_H_MM / FOCAL_MM
        val wide = tall * widthPx / heightPx
        out[0] = (vx / (depth * wide) + 0.5f) * widthPx
        out[1] = (0.5f - up / (depth * tall)) * heightPx
        return true
    }

    /** [t] of the way from [a] to [b]: the camera rising from the crate to stand over the deck. */
    fun between(a: Shot, b: Shot, t: Float): Shot = Shot(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t,
        a.pitchDeg + (b.pitchDeg - a.pitchDeg) * t
    )
}

/**
 * Where the sleeve of the record that is playing lies: on the table in front of the deck, its
 * back up and square to you, its front pushed out from under it to one side.
 */
const val SLEEVE_FRONT_Z = 0.580f
const val SLEEVE_BACK_X = 0.030f
