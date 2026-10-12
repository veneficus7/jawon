package com.jawon.app.ui.vinylscene

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * **The arm going somewhere: up off the record, across, and down.**
 *
 * A needle is never dragged across a record. Whatever moves it — a record being put on, a track
 * chosen from the sleeve, a seek from a car's screen — it is lifted first, swung while it is
 * clear, and lowered when it is over where it is going; and the moment it lands is the moment
 * there is sound. This is that, as three stretches of time, so that the moment is a number and
 * not something a spring gets round to.
 *
 * Pure. Angles in degrees, [yaw] about the pivot as [VinylArm.yawDegrees] gives it, [lift] with
 * the head up; time in seconds from when the move began.
 */
class ArmMove(
    val fromYaw: Float,
    val fromLift: Float,
    val toYaw: Float,
    val liftS: Float,
    val swingS: Float,
    val lowerS: Float,
    /** Where it ends: down on the record, or up on its rest. */
    val endsDown: Boolean
) {
    /** When it is where it is going: the needle in the groove, or the arm on its rest. */
    val landsAt: Float get() = liftS + swingS + lowerS

    fun yaw(t: Float): Float {
        if (swingS <= 0f) return if (t >= liftS) toYaw else fromYaw
        return fromYaw + (toYaw - fromYaw) * easeInOut((t - liftS) / swingS)
    }

    /** How high the head is while it is carried across: all the way up, unless it never had to leave where it was. */
    private val top: Float get() = if (liftS > 0f || swingS > 0f) Needle.UP_DEG else fromLift

    fun lift(t: Float): Float = when {
        t < liftS -> fromLift + (Needle.UP_DEG - fromLift) * easeOut(t / liftS)
        t < liftS + swingS || !endsDown -> top
        lowerS <= 0f -> 0f
        // A damped cue: it sets off gently and touches gently.
        else -> top * (1f - easeInOut((t - liftS - swingS) / lowerS))
    }

    private fun easeOut(x: Float): Float {
        val u = 1f - x.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun easeInOut(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
}

object Needle {

    /** The arm, lifted clear of a record: its tip a centimetre up. */
    const val UP_DEG = 3.2f

    /** Where the arm rests, off the record. */
    val REST_YAW: Float = VinylArm.yawDegrees(VinylDimens.REST_RADIUS)

    /** Further from the groove being heard than this, and the needle is not followed there: it is lifted and put there. */
    const val HOP_DEG = 0.45f

    /**
     * The move from where the arm is to [toYaw]. [quick] is a needle put somewhere else on a
     * record that is already playing — a track chosen — where a record being put on takes its time.
     * To the rest ([down] false), it is lifted and swung and stays up.
     */
    fun move(fromYaw: Float, fromLift: Float, toYaw: Float, quick: Boolean, down: Boolean = true): ArmMove {
        val turn = abs(toYaw - fromYaw)
        val up = ((UP_DEG - fromLift) / UP_DEG).coerceIn(0f, 1f)
        if (turn < 0.02f) {
            return if (down) {
                // Already over it: only the lowering is left — or, already down on it, nothing.
                val lower = (if (quick) LOWER_QUICK_S else LOWER_S) * (fromLift / UP_DEG).coerceIn(0f, 1f)
                ArmMove(fromYaw, fromLift, toYaw, 0f, 0f, lower, true)
            } else {
                ArmMove(fromYaw, fromLift, toYaw, LIFT_S * up, 0f, 0f, false)
            }
        }
        val lift = (if (quick) LIFT_QUICK_S else LIFT_S) * up
        val swing = if (quick) (SWING_QUICK_S + turn * SWING_QUICK_PER_DEG).coerceAtMost(SWING_QUICK_LONGEST_S)
        else (SWING_S + turn * SWING_PER_DEG).coerceAtMost(SWING_LONGEST_S)
        val lower = if (!down) 0f else if (quick) LOWER_QUICK_S else LOWER_S
        return ArmMove(fromYaw, fromLift, toYaw, lift, swing, lower, down)
    }

    const val LIFT_S = 0.22f
    const val SWING_S = 0.30f
    const val SWING_PER_DEG = 0.010f
    const val SWING_LONGEST_S = 0.62f
    const val LOWER_S = 0.42f

    /**
     * A needle put somewhere else on a record that is playing: half a second of silence at the
     * most, from one edge of a side to the other. It is the one thing in this room that is slower
     * than it could be made, on purpose — a needle is not somewhere else at once — and it is as
     * short as still reads as an arm being lifted and put down rather than a picture changing.
     */
    const val LIFT_QUICK_S = 0.10f
    const val SWING_QUICK_S = 0.12f
    const val SWING_QUICK_PER_DEG = 0.006f
    const val SWING_QUICK_LONGEST_S = 0.26f
    const val LOWER_QUICK_S = 0.17f

    /** From the needle touching the lead-in to the music: the breath of surface noise before a record starts. */
    const val LEAD_IN_S = 0.26f
}

/**
 * **A record being put on**: out of the crate, onto the table, out of its sleeve, onto the
 * platter, and the needle down (`docs/formats.md` §5).
 *
 * Five things, overlapping, each a stretch of one clock that starts when the record is touched
 * in the crate:
 *
 * - **The sleeve** comes in from the crate's side of the table and is laid down in front of the
 *   deck ([SLEEVE_FROM]), as the camera gets there.
 * - **The record** is drawn out of the sleeve's mouth along the table ([SLIDE_FROM]), then
 *   carried — up first, across above the arm and the spindle, and straight down onto the mat
 *   once it is over the spindle and not before ([CARRY_FROM]).
 * - **The card** with the sides printed on it is drawn out after it and laid on the sleeve,
 *   where it is the tracklist ([CARD_FROM]).
 * - **The motor** starts when the record is on the mat ([SEATED_AT]).
 * - **The arm** is lifted as the record comes down and is over the groove as the platter comes
 *   up to speed ([ARM_AT]); when it lands is [ArmMove.landsAt] after that, and that is when
 *   there is music.
 *
 * Pure: every place is a function of the clock, so that "the record never goes through the
 * arm" and "it is not lowered until it is over the spindle" are things a test can hold, and so
 * that a tap that hurries it only runs the same clock faster.
 */
object PutOn {

    const val SLEEVE_FROM = 0.45f
    const val SLEEVE_S = 0.65f

    /** Not before the sleeve is down: a record is drawn out of a sleeve that is lying on the table. */
    const val SLIDE_FROM = SLEEVE_FROM + SLEEVE_S
    const val SLIDE_S = 0.45f

    const val CARRY_FROM = SLIDE_FROM + SLIDE_S
    const val CARRY_S = 0.75f

    /** How much of the carry is spent getting over the spindle; the rest is coming straight down onto it. */
    const val CARRY_ACROSS = 0.72f

    /** It is lifted before it is taken anywhere: this much of the carry goes by before it moves across, and by this much it is all the way up. */
    private const val CARRY_HOLD = 0.12f
    private const val CARRY_RISE = 0.34f

    const val SEATED_AT = CARRY_FROM + CARRY_S

    const val CARD_FROM = CARRY_FROM + 0.10f
    const val CARD_OUT_S = 0.40f
    const val CARD_LAY_S = 0.45f

    /** The arm sets off a moment before the record is down: it is only being lifted, and it is clear of the record's edge. */
    const val ARM_AT = SEATED_AT - 0.12f

    /** After this nothing but the arm is still moving. */
    const val SETTLED_AT = CARD_FROM + CARD_OUT_S + CARD_LAY_S

    // ---- Where things end up: the sleeve laid in front of the deck, the card on it ----------------

    /** The sleeve's node — the middle of its bottom edge — and how it is turned. */
    const val SLEEVE_X = -0.062f
    const val SLEEVE_Y = VinylDimens.SLEEVE_T / 2
    const val SLEEVE_Z = SLEEVE_FRONT_Z - 0.025f
    const val SLEEVE_YAW = 5f

    /** The card's, lying on the sleeve, square to you. */
    const val CARD_X = SLEEVE_BACK_X
    const val CARD_Y = VinylDimens.SLEEVE_T * 1.5f
    const val CARD_Z = SLEEVE_FRONT_Z
    const val CARD_YAW = 180f

    /** How high a record is carried: over the arm on its rest and over the spindle, with room. */
    const val CARRY_Y = VinylDimens.PIVOT_Y + 0.055f

    /** Where the sleeve comes from: the crate's side, beyond the front of the table, in the hand. */
    private const val SLEEVE_IN_X = 0.035f
    private const val SLEEVE_IN_Y = 0.13f
    private const val SLEEVE_IN_Z = 0.56f
    private const val SLEEVE_IN_YAW = -11f

    /** How far along the mouth a record has to come to be out of its sleeve. */
    const val CLEAR_M = VinylDimens.SLEEVE / 2 + VinylDimens.LP_R
    private const val SLIDE_M = CLEAR_M + 0.004f

    /** In the sleeve: under the cover, by less than the cover is thick. */
    private const val INSIDE_Y = 0.0006f

    class Pose {
        var sleeveX = 0f
        var sleeveY = 0f
        var sleeveZ = 0f
        var sleeveYaw = 0f
        var sleeveSeen = false

        /** The middle of the record, and the height of its underside. */
        var recordX = 0f
        var recordY = 0f
        var recordZ = 0f
        var recordSeen = false

        /** On the mat: it turns with the platter from here. */
        var seated = false

        var cardX = 0f
        var cardY = 0f
        var cardZ = 0f
        var cardYaw = 0f
        var cardSeen = false

        var motor = false
    }

    private val sleeveSin = sin(Math.toRadians(SLEEVE_YAW.toDouble())).toFloat()
    private val sleeveCos = cos(Math.toRadians(SLEEVE_YAW.toDouble())).toFloat()

    /** The middle of the sleeve where it lies, and the way its mouth faces: to your right as you read the cover. */
    val SLEEVE_MIDDLE_X = SLEEVE_X - VinylDimens.SLEEVE / 2 * sleeveSin
    val SLEEVE_MIDDLE_Z = SLEEVE_Z - VinylDimens.SLEEVE / 2 * sleeveCos
    val MOUTH_X = sleeveCos
    val MOUTH_Z = -sleeveSin

    /** Everything where it is [t] seconds after the record was touched. */
    fun at(t: Float, out: Pose) {
        // ---- the sleeve -------------------------------------------------------------------------
        val land = easeOut((t - SLEEVE_FROM) / SLEEVE_S)
        out.sleeveSeen = t >= SLEEVE_FROM
        out.sleeveX = SLEEVE_X + SLEEVE_IN_X * (1f - land)
        out.sleeveZ = SLEEVE_Z + SLEEVE_IN_Z * (1f - land)
        // Carried level, and set down at the last: most of the height goes in the final third.
        out.sleeveY = SLEEVE_Y + SLEEVE_IN_Y * (1f - easeInOut((t - SLEEVE_FROM) / SLEEVE_S))
        out.sleeveYaw = SLEEVE_YAW + SLEEVE_IN_YAW * (1f - land)

        // ---- the record --------------------------------------------------------------------------
        val slide = easeInOut((t - SLIDE_FROM) / SLIDE_S)
        val outX = SLEEVE_MIDDLE_X + MOUTH_X * SLIDE_M
        val outZ = SLEEVE_MIDDLE_Z + MOUTH_Z * SLIDE_M
        val carry = ((t - CARRY_FROM) / CARRY_S).coerceIn(0f, 1f)
        val across = easeInOut((carry - CARRY_HOLD) / (CARRY_ACROSS - CARRY_HOLD))
        val rise = easeInOut(carry / CARRY_RISE)
        val fall = easeInOut((carry - CARRY_ACROSS) / (1f - CARRY_ACROSS))
        out.recordSeen = t >= SLIDE_FROM
        out.seated = t >= SEATED_AT
        if (carry <= 0f) {
            out.recordX = SLEEVE_MIDDLE_X + MOUTH_X * SLIDE_M * slide
            out.recordZ = SLEEVE_MIDDLE_Z + MOUTH_Z * SLIDE_M * slide
            out.recordY = INSIDE_Y
        } else {
            out.recordX = outX + (VinylDimens.SPINDLE_X - outX) * across
            out.recordZ = outZ + (VinylDimens.SPINDLE_Z - outZ) * across
            val up = INSIDE_Y + (CARRY_Y - INSIDE_Y) * rise
            out.recordY = up + (VinylDimens.RECORD_Y - up) * fall
        }

        // ---- the card ----------------------------------------------------------------------------
        val drawn = easeInOut((t - CARD_FROM) / CARD_OUT_S)
        val laid = easeInOut((t - CARD_FROM - CARD_OUT_S) / CARD_LAY_S)
        out.cardSeen = t >= CARD_FROM
        out.cardYaw = (CARD_YAW + SLEEVE_YAW) + (CARD_YAW - (CARD_YAW + SLEEVE_YAW)) * laid
        // Its middle: out of the mouth along the table, then over and onto the sleeve.
        val cardOut = VinylDimens.SLEEVE + 0.004f
        val fromX = SLEEVE_MIDDLE_X + MOUTH_X * cardOut * drawn
        val fromZ = SLEEVE_MIDDLE_Z + MOUTH_Z * cardOut * drawn
        val restX = CARD_X
        val restZ = CARD_Z - VinylDimens.SLEEVE / 2
        val midX = fromX + (restX - fromX) * laid
        val midZ = fromZ + (restZ - fromZ) * laid
        val yaw = Math.toRadians(out.cardYaw.toDouble())
        // The node is the middle of the card's bottom edge, which is half a card from its middle, the way it is turned.
        out.cardX = midX - VinylDimens.SLEEVE / 2 * sin(yaw).toFloat()
        out.cardZ = midZ - VinylDimens.SLEEVE / 2 * cos(yaw).toFloat()
        // In the sleeve it is under the cover; laid, it is on it, and lifted a little on the way over.
        val inside = SLEEVE_Y - 0.0008f
        out.cardY = inside + (CARD_Y - inside) * easeOut(laid * 3f) + 0.012f * sin(Math.PI * laid).toFloat()

        out.motor = t >= SEATED_AT
    }

    /** Everything where it ends up: a record that is on, with nothing on its way anywhere. */
    fun rest(out: Pose) = at(SETTLED_AT + 10f, out)

    private fun easeOut(x: Float): Float {
        val u = 1f - x.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun easeInOut(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
}

/**
 * **A record being turned over**: lifted off the spindle, turned like a page about the line
 * that runs from you to the back of the deck, and set down on its other side.
 *
 * A record is thirty centimetres across and turns about its middle, so half of it swings down
 * as the other half swings up: it has to be lifted by more than its own radius before it is
 * edge on, or it goes through the platter. [LIFT_M] is that, and the turn waits for the lift and
 * the lowering waits for the turn — overlapped, because a hand does not do one and then the
 * other, but never by more than the height it has ([at]'s three eases, and the test that holds
 * them to it).
 *
 * Pure. [Pose.lift] is how far above the mat the record's middle is, [Pose.turned] how far over
 * it is, 0 to 1 of the half turn.
 */
object TurnOver {

    const val LIFT_S = 0.30f
    const val TURN_FROM = 0.16f
    const val TURN_S = 0.66f
    const val LOWER_FROM = 0.74f
    const val LOWER_S = 0.36f
    const val TOTAL_S = LOWER_FROM + LOWER_S

    /** A record's radius and four centimetres: clear of the mat, and of the spindle when it is edge on. */
    const val LIFT_M = VinylDimens.LP_R + 0.039f

    /** How long a side that has ended is left before it is turned over, where it is not waiting for a hand. */
    const val UNASKED_AFTER_S = 0.55f

    class Pose {
        var lift = 0f
        var turned = 0f
        var seated = true
    }

    fun at(t: Float, out: Pose) {
        val up = ease(t / LIFT_S)
        val down = ease((t - LOWER_FROM) / LOWER_S)
        out.lift = LIFT_M * up * (1f - down)
        out.turned = ease((t - TURN_FROM) / TURN_S)
        out.seated = t <= 0f || t >= TOTAL_S
    }

    private fun ease(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
}

/**
 * A platter under a motor: it comes up to speed and it runs down, and neither is at once.
 * [speed] is 0 at rest and 1 at thirty-three and a third.
 */
class PlatterSpin {
    var speed = 0f
        private set

    fun step(dt: Float, on: Boolean) {
        val to = if (on) 1f else 0f
        val tau = if (on) UP_TAU_S else DOWN_TAU_S
        speed += (to - speed) * (1f - exp(-dt.coerceAtLeast(0f) / tau))
        if (abs(to - speed) < 0.002f) speed = to
    }

    /** At once: a room opened on a record that is already going round. */
    fun set(on: Boolean) {
        speed = if (on) 1f else 0f
    }

    companion object {
        /** A direct drive is at speed in under a second, and brakes a little slower than it starts. */
        const val UP_TAU_S = 0.20f
        const val DOWN_TAU_S = 0.32f
    }
}
