package com.jawon.app.ui.showroom

/**
 * The showroom's stage, in its own coordinates (metres, Y up, the row along +X, the camera's side
 * of it +Z). **These must agree with `build_showroom()`** in `tools/models/build_models.py`.
 */
object ShowroomStage {
    /** Plinth centres along X, the first at 0 — `SHOWROOM_SPACING`. */
    const val SPACING_M = 0.42f

    /** How many plinths stand in the row — `SHOWROOM_PLINTHS`, one per [Exhibit]. */
    const val PLINTHS = 4

    /** The top of a plinth's cap — `SHOWROOM_PLINTH_H + SHOWROOM_CAP_T`. */
    const val CAP_TOP_Y = 0.968f

    /** How far above the cap an exhibit floats, to its underside. */
    const val HOVER_M = 0.045f

    /**
     * Where the camera aims on every plinth: about the middle of what floats there. One height for
     * all of them, so the framing does not jump as the row moves from a jewel case to the shorter
     * pocket player.
     */
    const val LOOK_Y = 1.075f

    fun plinthX(position: Float): Float = position * SPACING_M
}

/**
 * Where the showroom's camera stands and what it looks at (`docs/formats.md` §7, step 5b).
 *
 * Three framings of whichever plinth it is at, blended:
 *
 * - **Hero** — the shot the showroom rests on: the exhibit high in the frame, looked at from a
 *   little above so its plinth's cap reads as a lit plane beneath it, with its shadow on it.
 * - **Travel** — pulled back and up while the row is moving, so the plinths either side come into
 *   view and it reads as a row, not a slideshow.
 * - **Close** — an arm's length from the exhibit, filling the frame: where you arrive, pinched out
 *   of its room, and where you leave, going into one.
 *
 * Pure, so the framings can be tested; the screen owns the springs that drive it.
 */
object ShowroomCamera {

    /** Offsets from the aim point on the plinth: up, back toward the viewer, and where it aims. */
    const val HERO_UP_M = 0.10f
    const val HERO_BACK_M = 0.80f
    const val HERO_AIM_M = -0.02f

    const val TRAVEL_UP_M = 0.30f
    const val TRAVEL_BACK_M = 2.10f
    const val TRAVEL_AIM_M = -0.10f

    const val CLOSE_UP_M = 0.015f
    const val CLOSE_BACK_M = 0.30f
    const val CLOSE_AIM_M = 0f

    /**
     * Writes the eye into `out[0..2]` and the point it looks at into `out[3..5]`: at plinth
     * [position] (in plinths, as [ShowroomCarousel] has it), pulled back by [travel] (0 at rest,
     * 1 travelling) and drawn in by [close] (0 not at all, 1 an arm's length from the exhibit).
     */
    fun frame(position: Float, travel: Float, close: Float, out: FloatArray) {
        val x = ShowroomStage.plinthX(position)
        val t = travel.coerceIn(0f, 1f)
        val c = close.coerceIn(0f, 1f)
        val up = mix(mix(HERO_UP_M, TRAVEL_UP_M, t), CLOSE_UP_M, c)
        val back = mix(mix(HERO_BACK_M, TRAVEL_BACK_M, t), CLOSE_BACK_M, c)
        val aim = mix(mix(HERO_AIM_M, TRAVEL_AIM_M, t), CLOSE_AIM_M, c)
        out[0] = x
        out[1] = ShowroomStage.LOOK_Y + up
        out[2] = back
        out[3] = x
        out[4] = ShowroomStage.LOOK_Y + aim
        out[5] = 0f
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * The camera's rotation for looking from [from] toward [to], as SceneView's Euler angles in
 * degrees (x pitch, y yaw) — its rotation order puts the lens down −Z, turned by yaw and then
 * pitched. Written into [out] as (pitch, yaw).
 *
 * The pocket player's rest pose used it first; its room is a face square on to you now, and this
 * stayed with the camera that still moves.
 */
fun lookAngles(fromX: Float, fromY: Float, fromZ: Float, toX: Float, toY: Float, toZ: Float, out: FloatArray) {
    val dx = toX - fromX
    val dy = toY - fromY
    val dz = toZ - fromZ
    val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    if (len < 1e-6f) {
        out[0] = 0f
        out[1] = 0f
        return
    }
    out[0] = Math.toDegrees(kotlin.math.asin((dy / len).coerceIn(-1f, 1f)).toDouble()).toFloat()
    out[1] = Math.toDegrees(kotlin.math.atan2(-dx, -dz).toDouble()).toFloat()
}
