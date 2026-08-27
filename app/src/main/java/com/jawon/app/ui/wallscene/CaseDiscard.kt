package com.jawon.app.ui.wallscene

import kotlin.math.pow
import kotlin.math.sin

/**
 * The motion for taking an album off the shelf for good — testers asked for "its own
 * animation where it breaks the CD and throws it at trash can."
 *
 * Pure arithmetic, no Android or Filament types, for the same reason [OpenSequence], [Scratch]
 * and [ShelfHover] are: the feel lives in these numbers and they should be reviewable without
 * a device attached.
 *
 * **What this deliberately is not.** A literal trash can is not part of `DESIGN.md`'s Midnight
 * Shop, and modelling one — plus real fracture geometry for the case — is an asset-pipeline
 * undertaking on the scale of M11 Pass 2, not a bug-fix-sized addition. What testers actually
 * asked for is a *sensation*: the case is gone, and it went out badly. This gets there with
 * the node the shelf already has — no new model, no new material, nothing for
 * [CaseSlots]'s never-destroy pool to worry about — driven by two beats:
 *
 * 1. **Crack** (0 → [CRACK_TO]): a fast, non-uniform squash and a hard rotational snap. There
 *    is no fracture geometry, so the *sudden, wrong* deformation is what reads as "broken"
 *    rather than "bounced".
 * 2. **Discard** (overlapping, from [DISCARD_FROM]): flung down and to one side, tumbling and
 *    shrinking, off the visible frame. "Toward a bin" without asserting where one is.
 *
 * **Brisk on purpose.** ENGINEERING.md's own note on this feature warns that "an animation that is
 * delightful once is tiresome on the twentieth album" — this runs in well under a second, and
 * the caller (`WallSceneScreen`) lets a second tap on a discarding case skip straight to
 * completion by jumping its own elapsed clock past [DURATION_S], rather than making the user
 * sit through it on every deletion. [at] saturates past the end, so that is all skipping is.
 */
object CaseDiscard {

    /** Total run, in seconds. Brief by design — see the class doc. */
    const val DURATION_S = 0.62f

    private const val CRACK_TO = 0.28f
    private const val DISCARD_FROM = 0.16f

    /**
     * How far the case falls and slides before it's off-frame, in metres. Generous against
     * [WallSceneScreen]'s zoom range on purpose: the display only has to clear the *widest*
     * the camera can ever be (`ZOOM_FAR`), and it is cheaper to overshoot than to under-throw
     * and leave the case visibly clipping out mid-flight.
     */
    private const val FALL_Y = 0.85f
    private const val SLIDE_X = 0.55f

    /** Full turns the case tumbles through on its way down. Not a clean number on purpose. */
    private const val TUMBLE_TURNS = 2.35f

    /** The crack's squash, as a fraction of the case's own size. */
    private const val CRACK_SQUASH = 0.34f
    private const val CRACK_SNAP_DEG = 22f

    data class Transforms(
        val offsetX: Float,
        val offsetY: Float,
        val spinDeg: Float,
        val scaleX: Float,
        val scaleY: Float,
        /** True once the case has fully left the frame and the slot can be freed. */
        val done: Boolean
    )

    /**
     * @param t elapsed time since the discard began, in seconds. Values at or past
     *   [DURATION_S] all return the same settled [end] state, so the caller can keep calling
     *   this every frame without clamping `t` itself first.
     * @param direction ±1, which way the case slides as it falls. Alternated per album (see
     *   `WallSceneScreen`'s call site) purely so removing several in a row doesn't look like
     *   the same clip playing on repeat.
     */
    fun at(t: Float, direction: Float): Transforms {
        if (t <= 0f) return Transforms(0f, 0f, 0f, 1f, 1f, done = false)
        val p = t / DURATION_S
        if (p >= 1f) {
            return Transforms(
                offsetX = SLIDE_X * direction,
                offsetY = -FALL_Y,
                spinDeg = 360f * TUMBLE_TURNS * direction,
                scaleX = 0f,
                scaleY = 0f,
                done = true
            )
        }

        val crack = (p / CRACK_TO).coerceIn(0f, 1f)
        // A snap has no ease-in — it is already moving at the first instant, which is what a
        // slow-in curve can never sell. u(1-u) peaks mid-beat and returns cleanly to zero at
        // both ends, so the squash resolves exactly as the discard beat picks up.
        val crackEnvelope = crack * (1f - crack) * 4f
        val squashX = 1f + CRACK_SQUASH * crackEnvelope
        val squashY = 1f - CRACK_SQUASH * crackEnvelope
        // Two quick opposed kicks rather than one, so it reads as a jolt rather than a lean.
        val snapDeg = sin(crack * Math.PI.toFloat() * 2.4f) * CRACK_SNAP_DEG * (1f - crack)

        val discard = ((p - DISCARD_FROM) / (1f - DISCARD_FROM)).coerceIn(0f, 1f)
        // Accelerating fall, not eased — this is a thing being dropped, not settling.
        val fall = discard * discard
        val slide = 1f - (1f - discard).pow(2)
        val shrink = 1f - discard.pow(3)

        return Transforms(
            offsetX = SLIDE_X * direction * slide,
            offsetY = -FALL_Y * fall,
            spinDeg = snapDeg + 360f * TUMBLE_TURNS * direction * discard,
            scaleX = squashX * shrink,
            scaleY = squashY * shrink,
            done = false
        )
    }
}
