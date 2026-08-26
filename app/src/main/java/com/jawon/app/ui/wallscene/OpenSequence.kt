package com.jawon.app.ui.wallscene

import kotlin.math.sin

/**
 * The five-beat open sequence, ported from the prototype's active-case block.
 *
 * The whole thing is driven by a single scalar `progress` in 0..1. Each beat owns a
 * sub-range of it and is eased independently, so the beats overlap the way they do in the
 * prototype — the case starts turning before it has finished clearing the rack, the lid
 * starts opening before the turn settles, and so on.
 *
 * Reversal and interruption need no special handling: run `progress` backwards. Because
 * exactly one case is ever selected, ENGINEERING.md's "must never leave two cases mid-animation"
 * is structural rather than something this has to defend against.
 *
 * Pure maths, no Filament types — the timing stays reviewable on its own, and M8 can reuse
 * it for the disc.
 */
object OpenSequence {

    const val DURATION_MS = 1500f

    // Beat windows, verbatim from the prototype.
    private const val PULL_FROM = 0.00f
    private const val PULL_TO = 0.28f
    private const val TURN_FROM = 0.22f
    private const val TURN_TO = 0.50f
    private const val OPEN_FROM = 0.45f
    private const val OPEN_TO = 0.72f
    private const val LIFT_FROM = 0.60f
    private const val LIFT_TO = 0.90f
    private const val SPIN_FROM = 0.80f
    private const val SPIN_TO = 1.00f

    // Motion, in metres and degrees (prototype units x 0.1).
    private const val PULL_Z = 0.115f
    private const val FRONT_GAP = 0.21f
    const val CAMERA_DOLLY = 0.23f
    private const val RAISE_Y = 0.030f
    private const val LIFT_DROP_Y = 0.055f
    private const val LIFT_PUSH_Z = 0.042f
    /**
     * The camera also eases back as the disc comes up. At true scale the case alone fills
     * ~93% of the frame, so without this there is simply nowhere for a 120mm disc to be —
     * the prototype solved that by magnifying the disc instead, which is what stopped it
     * reading as a CD.
     */
    private const val LIFT_DOLLY = 0.45f
    private const val HINGE_DEG = 123.2f
    /** The disc sits on the spindle, just proud of the tray's front face. */
    private const val TRAY_DISC_Z = WallDimens.TRAY_FRONT_Z + 0.0014f

    // The idle float that keeps a held case from looking frozen.
    private const val FLOAT_Y = 0.0014f
    private const val FLOAT_X = 0.0010f
    private const val SWAY_Y_DEG = 1.03f
    private const val SWAY_X_DEG = 1.15f

    // Where the disc settles once it is up.
    //
    // The disc is never scaled: a CD is 120mm across and stays 120mm across, so it reads
    // correctly against the 142mm case it came out of (about 85% of the case's height, the
    // real ratio). The prototype magnified it to fill a deck panel; that made it read as a
    // grey circle of no particular size rather than as a compact disc.
    private const val DISC_VIEW_DIST = 0.55f
    /** Fraction of the half-height the disc sits above centre, leaving room below for M7 part 2's deck. */
    private const val DISC_BAND = 0.0f
    const val SPIN_DEG_PER_S = 78f

    fun easeOut(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    fun easeInOut(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t
        else {
            val v = -2f * t + 2f
            1f - (v * v * v) / 2f
        }

    /** Maps global progress into one beat's own 0..1. */
    private fun sub(t: Float, from: Float, to: Float): Float =
        ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    data class Beats(
        val pull: Float,
        val turn: Float,
        val open: Float,
        val lift: Float,
        val spin: Float
    )

    fun beatsAt(progress: Float) = Beats(
        pull = easeOut(sub(progress, PULL_FROM, PULL_TO)),
        turn = easeInOut(sub(progress, TURN_FROM, TURN_TO)),
        open = easeInOut(sub(progress, OPEN_FROM, OPEN_TO)),
        lift = easeInOut(sub(progress, LIFT_FROM, LIFT_TO)),
        spin = easeOut(sub(progress, SPIN_FROM, SPIN_TO))
    )

    data class Transforms(
        val caseX: Float,
        val caseY: Float,
        val caseZ: Float,
        val caseRotX: Float,
        val caseRotY: Float,
        val hingeRotY: Float,
        val cameraZ: Float,
        val discX: Float,
        val discY: Float,
        val discZ: Float,
        val discScale: Float,
        val discVisible: Boolean
    )

    /**
     * @param slotX/slotY where the case sits on the wall
     * @param panX/panY   the camera's browse position — the case flies to the centre line
     * @param clock       seconds, for the idle float
     */
    fun transformsFor(
        beats: Beats,
        slotX: Float,
        slotY: Float,
        panX: Float,
        panY: Float,
        baseCameraZ: Float,
        tanHalfFov: Float,
        aspect: Float,
        clock: Float
    ): Transforms {
        // The case is framed against the turn-phase camera only; the later lift dolly then
        // eases the camera away from everything rather than dragging the case along with it.
        val turnCameraZ = baseCameraZ + CAMERA_DOLLY * beats.turn
        val cameraZ = turnCameraZ + LIFT_DOLLY * beats.lift

        // Pull clears the rack; turn then carries the case out to FRONT_GAP in front of the
        // lens. Kept as the prototype's two additive lerps because the beats overlap.
        val pulled = lerp(0f, PULL_Z, beats.pull)
        val caseZ = pulled + lerp(0f, (turnCameraZ - FRONT_GAP) - PULL_Z, beats.turn)

        val floatX = sin(clock * 0.43f + 1.7f) * FLOAT_X * beats.turn
        val floatY = sin(clock * 0.62f) * FLOAT_Y * beats.turn

        val caseX = lerp(slotX, panX, beats.turn) + floatX
        val caseY = lerp(slotY, panY + RAISE_Y, beats.turn) - beats.lift * LIFT_DROP_Y + floatY
        val caseZFinal = caseZ - beats.lift * LIFT_PUSH_Z

        // The active-case group is modelled cover-forward, so it starts turned edge-on.
        //
        // +90, not -90. The spine (and the hinge, which shares that edge on a real jewel
        // case) sits on the group's local -X. Rotating +90 about Y maps local -X to world
        // +Z, i.e. facing the camera — matching the shelved case it replaces. At -90 it
        // mapped to -Z, so the sequence showed the case's back the whole way out and the
        // silhouette flipped 180 degrees the instant it returned to the shelf.
        val caseRotY = lerp(90f, 0f, beats.turn) + sin(clock * 0.37f) * SWAY_Y_DEG * beats.turn
        val caseRotX = sin(clock * 0.51f + 0.9f) * SWAY_X_DEG * beats.turn

        // The disc rides from its tray up to a fixed distance in front of the lens, growing
        // to fill the free band of screen as it goes.
        val bandY = DISC_BAND * (DISC_VIEW_DIST * tanHalfFov)
        val viewX = panX
        val viewY = panY + bandY
        val viewZ = cameraZ - DISC_VIEW_DIST

        return Transforms(
            caseX = caseX,
            caseY = caseY,
            caseZ = caseZFinal,
            caseRotX = caseRotX,
            caseRotY = caseRotY,
            hingeRotY = -beats.open * HINGE_DEG,
            cameraZ = cameraZ,
            discX = lerp(caseX, viewX, beats.lift),
            discY = lerp(caseY, viewY, beats.lift),
            discZ = lerp(caseZ + TRAY_DISC_Z, viewZ, beats.lift),
            discScale = 1f,
            // Visible as soon as the lid starts to move, so you see it seated on the spindle
            // and then rise out — rather than it appearing from nowhere once already clear.
            discVisible = beats.open > 0.02f
        )
    }
}
