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
     * 1900ms, up from the prototype's 1500.
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
     * Beat windows. **Deeply overlapped — each beat starts around halfway through the one
     * before it**, so the case is still sliding out as it begins to turn, still turning as
     * the lid starts to swing, and the disc is already rising before the lid has settled.
     *
     * Overlapping action is the whole reason this reads as one object being handled rather
     * than as five animations queued up. It was tried at this density before and looked like
     * mush, so the beats were spaced out — but the density was never the problem. The curve
     * was: a plain ease-out-back starts at full speed, so a beat beginning mid-way through another
     * snapped on instead of blending, and the more they overlapped the more of those jolts
     * you got.
     *
     * With [easeSmoothBack] every beat now starts and ends at zero velocity, so overlapping
     * motions cross-fade instead of fighting. That is what makes this density safe.
     *
     * The lid and the lift still get the longest windows, because they are the two being
     * watched — "the case opens smoothly, and the disc comes out slowly and smoothly".
     */
    private const val PULL_FROM = 0.00f
    private const val PULL_TO = 0.26f
    private const val TURN_FROM = 0.13f
    private const val TURN_TO = 0.46f
    private const val OPEN_FROM = 0.30f
    private const val OPEN_TO = 0.68f
    private const val LIFT_FROM = 0.52f
    private const val LIFT_TO = 0.92f
    private const val SPIN_FROM = 0.76f
    private const val SPIN_TO = 1.00f

    // Motion, in metres and degrees (prototype units x 0.1).
    private const val PULL_Z = 0.115f

    /**
     * How close the case gets to the lens at the end of the turn — its closest approach, and
     * the moment testers described as the case being "very close to the camera".
     *
     * **Was 0.21m, and that was too close by arithmetic rather than by taste.** The lens is
     * 33mm against Filament's 24mm sensor height, so `tan(halfFov) = 12/33`. At 0.21m the
     * frame is 153mm tall and, on a 1080×2340 phone, only 70mm wide:
     *
     * | | at 0.21m | at 0.46m |
     * |---|---|---|
     * | frame | 153 × 70mm | 335 × 154mm |
     * | case, cover-on (125 × 142mm) | 93% tall, **178% wide** | 42% tall, 81% wide |
     *
     * The case was nearly twice as wide as the frame. Height was the number quoted when
     * these were tuned, and height was the forgiving axis — a portrait phone's horizontal
     * field of view is less than half its vertical one, and the case turns cover-on, so it
     * presents its 125mm width exactly when it is closest.
     *
     * **[LIFT_DOLLY] moves the opposite way by the same amount, deliberately.** The disc is
     * pinned at [DISC_VIEW_DIST] from the lens, so nothing here changes how the disc is
     * framed; all these two constants decide between them is how large the case is *relative
     * to it*, which is set by the sum `FRONT_GAP + LIFT_PUSH_Z + LIFT_DOLLY`. That sum is
     * unchanged at 0.702m, so the settled shot is identical to before — only the approach is
     * less extreme.
     */
    private const val FRONT_GAP = 0.46f
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
     * **Do not trim this on its own.** Tried, at 0.24, on the reasoning that 0.68m of camera
     * travel felt lurchy now the lens rests at 0.78m rather than 0.5m. The result is above:
     * the case fills the frame, the disc rises behind it, and all you see at the end of the
     * sequence is an empty tray with a sliver of disc above it.
     *
     * It is down to 0.20 now, and that is not a reversal of the warning — it is the one case
     * the warning allows. What decides the settled shot is the *sum* of [FRONT_GAP],
     * [LIFT_PUSH_Z] and this, because the disc is pinned at [DISC_VIEW_DIST] from the lens
     * and cannot move relative to it. That sum is still 0.702m. The earlier attempt cut this
     * while leaving `FRONT_GAP` at 0.21, which shortened the sum to 0.49 and put the case
     * almost level with the disc; here `FRONT_GAP` grew by the same amount this shrank.
     *
     * So: change these two together, keep the sum, and check the sum before trusting either
     * number alone.
     */
    private const val LIFT_DOLLY = 0.20f
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
    // Read against [easeSmoothBack]'s measured table. Each is chosen from how far the beat actually
    // travels, because the same percentage means very different things across a 115mm
    // slide and a 123° swing.

    /** 2.1% of 115mm = 2.5mm past the mark. The case clears the rack and rocks back. */
    private const val PULL_BACK = 0.12f

    /** 2.1% of the 90 degree turn = 1.9 degrees. It turns square and settles onto square. */
    private const val TURN_BACK = 0.12f

    /**
     * 2.9% of 123.2 degrees = 3.6 degrees past the hinge stop.
     *
     * The most important of the four: a lid that stops dead at its limit is the single most
     * obviously fake moment in the sequence. Strictly a hinge cannot travel past its own
     * stop, so the honest motion would rebound *short* of the mark rather than past it —
     * but real jewel-case lids flex, the discrepancy lasts under a tenth of a second, and
     * reading as alive beats being right about it. If it ever looks like flapping rather
     * than settling, this is the number.
     */
    private const val OPEN_BACK = 0.14f

    /** 1.4%, 2.2mm along the disc's ~160mm rise - the gentlest settle of the four. */
    private const val LIFT_BACK = 0.10f

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
    /** Fraction of the half-height the disc sits above centre, above the deck below it. */
    private const val DISC_BAND = 0.08f

    /**
     * How far the camera drops once a case is out, in metres, lifting the whole tableau in
     * frame.
     *
     * **This replaces something that used to happen by accident.** The camera used to re-aim
     * at the world origin as the wall panned, and that tilt lifted everything in the open
     * shot by roughly 40% of a half-frame. Nothing was composed for the untilted view, so
     * locking the lens square on (`LOOK_STRAIGHT_AHEAD`) dropped the disc onto the tracklist
     * and buried the case behind the album title.
     *
     * Correcting it here rather than in [DISC_BAND] is the difference between fixing the shot
     * and fixing one object in it. A camera move lifts the disc *and* the case *and* the lid
     * together, which is what "back where it was" means; raising the disc alone left the case
     * sitting under the text.
     *
     * Ramped on the turn's camera channel, so it eases in with the pull and is exactly zero
     * while browsing — the wall itself must stay square in frame.
     *
     * Unlike the tilt it replaces, this does not vary with pan position. The old shot sat at
     * a different height depending which shelf the record came off.
     */
    private const val OPEN_CAMERA_DROP = 0.088f
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
     * Smootherstep with an overshoot laid on top — **the curve the overlapping beats need.**
     *
     * A plain ease-out-back starts fast: its slope at t=0 is `overshoot + 3`, so a beat using it
     * jumps to speed the instant it begins. That is fine for a beat starting from rest and
     * wrong for one starting while another is still moving, because the two motions do not
     * blend — the new one snaps on. It is exactly why the beats had to be spaced apart to
     * read cleanly, and why heavy overlap looked like mush.
     *
     * This starts and ends at **zero velocity** (from smootherstep) while still carrying
     * past its mark and settling back (from the added bump), so beats can overlap as deeply
     * as the choreography wants and still blend into one another. In value-graph terms: no
     * corners anywhere, and a gentle hump before the curve flattens onto its target.
     *
     * The bump is `t³(1-t)²`, normalised to peak at 1. Both exponents are load-bearing:
     * `t³` makes its slope zero at the start, and `(1-t)²` makes it zero at the *end* too.
     * The obvious `t³(1-t)` fails the second — it arrives at t=1 still moving at about
     * -0.2, and since a beat clamps and holds after t=1, that lands a velocity step exactly
     * where the motion is supposed to be at rest. A corner in the value graph, in the one
     * place it is most visible.
     *
     * [overshoot] is **not** the resulting overshoot — smootherstep is already climbing
     * steeply where the bump peaks, so most of it is absorbed into the approach. Measured:
     *
     * | `overshoot` | actually travels past by | peak at |
     * |---|---|---|
     * | 0.03 | 0.07% | t=0.95 |
     * | 0.06 | 0.43% | t=0.91 |
     * | 0.09 | 1.12% | t=0.88 |
     * | 0.12 | 2.14% | t=0.85 |
     * | 0.16 | 3.91% | t=0.83 |
     *
     * Measured, not derived — the relationship is steeply non-linear and an estimate of it
     * was wrong by a factor of five at the low end. The per-beat constants below are read
     * off this table.
     */
    fun easeSmoothBack(t: Float, overshoot: Float): Float {
        val u = 1f - t
        val bump = t * t * t * u * u / 0.03456f
        return smootherStep(t) + overshoot * bump
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
            pull = easeSmoothBack(pullT, PULL_BACK),
            turn = easeSmoothBack(turnT, TURN_BACK),
            open = easeSmoothBack(openT, OPEN_BACK),
            lift = easeSmoothBack(liftT, LIFT_BACK),
            // Rise leads, forward follows, with a generous cross-fade so the corner between
            // them is a curve rather than a dog-leg.
            liftRise = easeSmoothBack(sub(liftT, 0f, RISE_TO), LIFT_BACK),
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
        /** Where the lens sits vertically — see [OPEN_CAMERA_DROP]. */
        val cameraY: Float,
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
            cameraY = panY - OPEN_CAMERA_DROP * beats.turnCamera,
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
