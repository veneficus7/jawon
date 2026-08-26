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

    /**
     * 2400ms, up from the prototype's 1500.
     *
     * Five distinct physical actions — a case sliding out, turning, a lid swinging, a disc
     * rising, a platter spinning up — were being run in a second and a half. Each got about
     * 400ms, which is roughly the time a UI takes to slide a sheet in, and the whole thing
     * read as one blurred event rather than as a series of things happening to an object.
     *
     * Slower is not automatically better and this is not arbitrary: the ceiling is how long
     * someone will wait to hear music. 2400 overshot that — it read as sluggish rather than
     * as weighty — so this is the middle ground between the prototype's rushed 1500 and
     * that. What the extra 400ms over the original buys is the room for the beats to stop
     * masking each other; past about 2000 it stops buying anything.
     */
    const val DURATION_MS = 1900f

    /**
     * Beat windows. **The overlaps are much smaller than the prototype's.**
     *
     * They used to run four deep — the turn began before the pull finished, the lid before
     * the turn settled, the disc before the lid did. Overlap is what makes a sequence feel
     * continuous rather than robotic, so some is wanted, but at that density every beat was
     * masked by the next and none of them read. Now each hands over near its own end, with
     * roughly a fifth of a beat of overlap: continuous, but you can see each thing happen.
     *
     * The lid and the lift get the most room, because they are what the owner is watching —
     * "the case opens smoothly, and the disc comes out slowly and smoothly".
     */
    private const val PULL_FROM = 0.00f
    private const val PULL_TO = 0.19f
    private const val TURN_FROM = 0.15f
    private const val TURN_TO = 0.38f
    private const val OPEN_FROM = 0.34f
    private const val OPEN_TO = 0.63f
    private const val LIFT_FROM = 0.58f
    private const val LIFT_TO = 0.90f
    private const val SPIN_FROM = 0.84f
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
     *
     * **Do not trim this to smooth the camera move.** Tried, at 0.24, on the reasoning that
     * 0.68m of camera travel felt lurchy now the lens rests at 0.78m rather than 0.5m. The
     * result is above: the case fills the frame, the disc rises behind it, and all you see
     * at the end of the sequence is an empty tray with a sliver of disc above it. The
     * paragraph above this is not decoration — it is the reason the number is what it is.
     * Smooth the *timing* instead.
     */
    private const val LIFT_DOLLY = 0.45f
    /**
     * How far the lid swings — the prototype's value.
     *
     * Briefly reduced to 97° to keep the lid inside a portrait frame, and put back: the
     * fuller pose is what makes the case read as properly open rather than stiff, and the
     * owner chose it. The lid running past the frame edge is the intended look.
     */
    private const val HINGE_DEG = 123.2f
    /** The disc sits on the spindle, just proud of the tray's front face. */
    private const val TRAY_DISC_Z = WallDimens.TRAY_FRONT_Z + 0.0014f

    // The idle float that keeps a held case from looking frozen.
    private const val FLOAT_Y = 0.0014f
    private const val FLOAT_X = 0.0010f
    private const val SWAY_Y_DEG = 1.03f
    private const val SWAY_X_DEG = 1.15f

    // ---- Overshoot, per beat ------------------------------------------------------
    //
    // Read against [easeOutBack]'s table. Each is chosen from how far the beat actually
    // travels, because the same percentage means very different things across a 115mm
    // slide and a 123° swing.

    /** 2.3% of 115mm ≈ 2.6mm past the mark. The case clears the rack and rocks back. */
    private const val PULL_BACK = 0.8f

    /** 2.3% of the 90° turn ≈ 2°. The case turns square and settles onto square. */
    private const val TURN_BACK = 0.8f

    /**
     * 3.0% of 123.2° ≈ 3.7° past the hinge stop.
     *
     * The most important of the four: a lid that stops dead at its limit is the single most
     * obviously fake moment in the sequence. Strictly a hinge cannot travel past its own
     * stop, so the honest motion would rebound *short* of the mark rather than past it —
     * but real jewel-case lids flex, the discrepancy lasts under a tenth of a second, and
     * reading as alive beats being right about it. If it ever looks like flapping rather
     * than settling, this is the number.
     */
    private const val OPEN_BACK = 0.9f

    /** 1.8%, ≈2.7mm along the disc's ~160mm rise — the gentlest settle of the four. */
    private const val LIFT_BACK = 0.7f

    /**
     * How the lift beat is split between rising and coming forward.
     *
     * They overlap between [FORWARD_FROM] and [RISE_TO], which is deliberate: with no
     * overlap the disc would stop dead at the top of its rise and then set off toward the
     * lens, a visible corner. Sharing a third of the beat rounds that corner into an arc.
     */
    private const val RISE_TO = 0.72f
    private const val FORWARD_FROM = 0.38f

    /**
     * Anticipation: how far the case sinks back into its slot before pulling out, and over
     * what fraction of the pull beat.
     *
     * 1.8mm into roughly 6mm of clearance behind the case, so it stays inside the rack.
     * Small, and worth more than its size — the counter-move before a move is most of what
     * separates something being animated from something being driven.
     */
    private const val ANTICIPATE_Z = 0.0018f
    private const val ANTICIPATE_WINDOW = 0.34f

    // Where the disc settles once it is up.
    //
    // The disc is never scaled: a CD is 120mm across and stays 120mm across, so it reads
    // correctly against the 142mm case it came out of (about 85% of the case's height, the
    // real ratio). The prototype magnified it to fill a deck panel; that made it read as a
    // grey circle of no particular size rather than as a compact disc.
    private const val DISC_VIEW_DIST = 0.55f
    /** Fraction of the half-height the disc sits above centre, leaving room below for M7 part 2's deck. */
    private const val DISC_BAND = 0.08f
    const val SPIN_DEG_PER_S = 78f

    // ---- Easing -------------------------------------------------------------------
    //
    // Every curve here is a **pure function of t**, and that is a hard requirement rather
    // than a stylistic preference. Closing runs `progress` backwards through these same
    // curves, which is what makes interruption from any point free and what makes
    // ENGINEERING.md's "never leave two cases mid-animation" structural. A stateful spring
    // integrating velocity would be more physical and would break all of that — it cannot
    // be run backwards, and an interrupted spring has to be caught and re-tensioned. So
    // overshoot is analytic here, not simulated.
    //
    // A pleasant consequence: overshoot run backwards *is* anticipation. The case that
    // settles into place on the way out draws back slightly before it leaves on the way in,
    // and both read correctly without a second set of curves.

    /**
     * Ease out past the target, then settle back onto it.
     *
     * This is the curve the sequence was missing. Every beat used to decelerate onto its
     * target and stop dead, which nothing physical does — a case sliding out of a rack, a
     * lid reaching its hinge stop and a disc coming to rest all carry momentum past the
     * mark and come back to it.
     *
     * [overshoot] is the classic "back" parameter, and its relationship to how far the
     * motion actually travels past the target is not obvious — the peak works out to
     * `4·c³ / 27·(c+1)²`. Some values worth knowing, because the per-beat constants below
     * are chosen from this table rather than by eye:
     *
     * | `overshoot` | travels past target by |
     * |---|---|
     * | 0.5 | 0.8% |
     * | 0.8 | 2.3% |
     * | 0.9 | 3.0% |
     * | 1.0 | 3.7% |
     * | 1.70158 (the CSS default) | 10% |
     *
     * The CSS default is far too much here: 10% of the lid's 123° swing is 12° of flap.
     */
    fun easeOutBack(t: Float, overshoot: Float): Float {
        val c1 = overshoot
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    /**
     * Perlin's smootherstep — zero first *and* second derivative at both ends.
     *
     * Reserved for the camera and for the disc's spin-up, where what matters is the absence
     * of a jerk rather than the presence of a settle. A cubic ease-in-out leaves a step
     * change in acceleration at t=0 and t=1; on a camera that is felt as a small kick at the
     * start and end of every move, and it was part of why the camera work read as
     * mechanical.
     *
     * **The camera must never share a curve with the objects.** It used to be driven
     * straight off the turn and lift beats, so now that those overshoot, reusing them would
     * bounce the camera along with the case — the one place overshoot is always wrong.
     */
    fun smootherStep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    /**
     * A smooth 0 → 1 → 0 bump over the first [window] of t, zero everywhere after.
     *
     * Anticipation, kept as a separate additive term rather than folded into the pull's
     * curve. The obvious way to get a counter-move is an ease-in-out-back, but that ties
     * the size of the dip to the size of the overshoot, and here they want to be very
     * different — a couple of millimetres back into the slot against a settle measured
     * across 115mm of travel. Separating them makes each tunable on its own.
     *
     * Both value and slope are zero at each end, so it joins the main curve invisibly.
     */
    fun bump(t: Float, window: Float): Float {
        if (t <= 0f || t >= window) return 0f
        val x = t / window
        val v = x * (1f - x)
        return 16f * v * v
    }

    /** Maps global progress into one beat's own 0..1. */
    private fun sub(t: Float, from: Float, to: Float): Float =
        ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * One beat's eased value, plus the two channels that deliberately do not overshoot.
     *
     * [turnCamera] and [liftCamera] carry the same beats as [turn] and [lift] on a
     * jerk-free curve, for the camera alone. Keeping them as separate fields rather than
     * re-easing at the call site is what stops someone later "simplifying" the camera back
     * onto the object beats and reintroducing the bounce.
     */
    data class Beats(
        val pull: Float,
        val turn: Float,
        val open: Float,
        val lift: Float,
        /**
         * The lift, split into two staggered channels so the disc travels an **arc** rather
         * than a straight line.
         *
         * One eased value driving both axes moves the disc diagonally from the spindle to
         * its resting place — which sends it straight through the lid and the tray wall, and
         * for a few frames the disc and the case visibly occupy the same space. Nothing
         * physical does that, and no amount of easing fixes it, because the problem is the
         * *path*, not the timing along it.
         *
         * [liftRise] leads and drives height; [liftForward] follows and drives the move
         * toward the lens. The disc therefore clears the case before it starts coming
         * forward, which is both what a hand does and what any animator would key.
         */
        val liftRise: Float,
        val liftForward: Float,
        val spin: Float,
        val turnCamera: Float,
        val liftCamera: Float,
        /** The anticipation bump, 0..1, peaking early in the pull. */
        val pullNudge: Float
    )

    fun beatsAt(progress: Float): Beats {
        val pullT = sub(progress, PULL_FROM, PULL_TO)
        val turnT = sub(progress, TURN_FROM, TURN_TO)
        val openT = sub(progress, OPEN_FROM, OPEN_TO)
        val liftT = sub(progress, LIFT_FROM, LIFT_TO)
        val spinT = sub(progress, SPIN_FROM, SPIN_TO)
        return Beats(
            pull = easeOutBack(pullT, PULL_BACK),
            turn = easeOutBack(turnT, TURN_BACK),
            open = easeOutBack(openT, OPEN_BACK),
            lift = easeOutBack(liftT, LIFT_BACK),
            // Rise leads, forward follows, with a generous cross-fade so the corner between
            // them is a curve rather than a dog-leg.
            liftRise = easeOutBack(sub(liftT, 0f, RISE_TO), LIFT_BACK),
            liftForward = smootherStep(sub(liftT, FORWARD_FROM, 1f)),
            // Smootherstep, not ease-out: this scales the disc's angular *velocity*, so a
            // curve with a steep start would step the acceleration and the disc would
            // snap into motion. Ramping in and out is how a motor comes up to speed.
            spin = smootherStep(spinT),
            turnCamera = smootherStep(turnT),
            liftCamera = smootherStep(liftT),
            pullNudge = bump(pullT, ANTICIPATE_WINDOW)
        )
    }

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
        //
        // Both dollies run on the camera-only channels. Driving them off `beats.turn` and
        // `beats.lift`, as this used to, now that those overshoot would rock the camera
        // back and forth with the case — which reads as a wobbling lens rather than as a
        // settling object, and undoes the very thing the overshoot is there to do.
        val turnCameraZ = baseCameraZ + CAMERA_DOLLY * beats.turnCamera
        val cameraZ = turnCameraZ + LIFT_DOLLY * beats.liftCamera

        // Pull clears the rack; turn then carries the case out to FRONT_GAP in front of the
        // lens. Kept as the prototype's two additive lerps because the beats overlap.
        //
        // The nudge is subtracted, so the case sinks a little deeper into its slot before
        // it comes out. Run in reverse it lands at the end of the close instead, where it
        // reads as the case seating itself home.
        val pulled = lerp(0f, PULL_Z, beats.pull) - ANTICIPATE_Z * beats.pullNudge
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
            // Height on the leading channel, depth on the trailing one: the disc lifts clear
            // of the case and only then travels to the lens. Driving both from `lift` sends
            // it diagonally through the lid.
            discX = lerp(caseX, viewX, beats.liftForward),
            discY = lerp(caseY, viewY, beats.liftRise),
            discZ = lerp(caseZ + TRAY_DISC_Z, viewZ, beats.liftForward),
            discScale = 1f,
            // Visible as soon as the lid starts to move, so you see it seated on the spindle
            // and then rise out — rather than it appearing from nowhere once already clear.
            discVisible = beats.open > 0.02f
        )
    }
}
