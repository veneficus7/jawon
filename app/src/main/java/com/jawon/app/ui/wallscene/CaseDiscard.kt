package com.jawon.app.ui.wallscene

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Removing an album, as a sequence: the case levitates out of its slot toward you, opens, the
 * disc inside is shattered by something, the lid closes over the pieces, and the whole case
 * falls away into the dark.
 *
 * Pure maths, no Filament types — same discipline as [OpenSequence], and every value is a
 * function of one scalar so the sequence is reproducible, skippable by jumping the clock, and
 * testable without a device.
 *
 * ---
 *
 * **This drives the held case, not the shelved one.** Deletion used to animate the closed
 * case sitting on the wall, which meant it could never show a disc — there is no disc inside
 * `case_closed.glb`. Running it on the same tray/lid/disc the open sequence uses gets the
 * interior for free and, more importantly, makes removal read in the same physical language
 * as opening: an object is taken off the shelf and handled. A different-looking animation for
 * removal would be a second vocabulary for no reason.
 *
 * **The pieces stay in the case.** This is the constraint the whole shatter is shaped around,
 * and it is what makes it a mirror cracking rather than an explosion: the lid closes over the
 * disc afterwards, so fragments separate along their cracks, drift a few millimetres, and
 * settle into the tray. An outward burst — which is what this was before — would leave debris
 * outside a case that then shuts on nothing.
 *
 * **Everything overlaps.** No beat waits for the one before it to finish: the case is still
 * rising as it turns, still turning as the lid opens, the lid is closing before the pieces
 * have settled, and the fall begins before the lid is shut. Every curve is [smootherStep],
 * which leaves and arrives at zero velocity, so overlapping motions cross-fade instead of
 * fighting — the same lesson `OpenSequence` records.
 */
object CaseDiscard {

    /**
     * 1.9 seconds — the same as the open sequence, and for the same reason: six distinct
     * physical actions need room not to mask each other.
     *
     * Longer than the 1.15s this replaced, because that version was a single event and this
     * is a small story. A tap still skips it, which is what keeps clearing a shelf bearable.
     */
    const val DURATION_S = 1.9f

    /** Fragment count. Must match `SHARD_SPOKES * (len(SHARD_RINGS) - 1)` in the build script. */
    const val SHARDS = 27

    // ---- Beat windows. Deliberately overlapped; see the class KDoc. -------------------

    private const val RISE_FROM = 0.00f
    private const val RISE_TO = 0.34f
    private const val TURN_FROM = 0.08f
    private const val TURN_TO = 0.40f
    private const val OPEN_FROM = 0.26f
    private const val OPEN_TO = 0.54f

    /** The moment the disc is struck. Everything before is approach, everything after is loss. */
    const val impactAt = 0.56f

    /**
     * The gathering before the strike.
     *
     * Something has to be *about* to happen, or the flash is just a flash. Over this window
     * the disc takes on a faint cold glow and the case starts to shiver — small, fast, and
     * growing — so the break reads as the release of something that had been building rather
     * than an effect that fired.
     */
    private const val BUILD_FROM = 0.40f

    private const val SHATTER_FROM = 0.56f
    private const val SHATTER_TO = 0.76f

    // Close and fall now overlap almost entirely: the lid is coming down *as* the case
    // drops away, rather than shutting first and then being released. Two things happening
    // at once is what the whole sequence is built on.
    private const val CLOSE_FROM = 0.60f
    private const val CLOSE_TO = 0.84f
    private const val FALL_FROM = 0.64f
    private const val FALL_TO = 1.00f

    // ---- Motion, in metres and degrees ------------------------------------------------

    /** How far the case comes off the wall toward the viewer. */
    private const val RISE_Z = 0.185f

    /** And how far up, so it clears its neighbours rather than sliding through them. */
    private const val RISE_Y = 0.034f

    /** Cover-on by the time it opens, from spine-on in the rack — as the open sequence does. */
    private const val TURN_FROM_DEG = 90f

    /** How far the lid swings. Less than the open sequence's 123°: this one has to shut again. */
    private const val HINGE_DEG = 104f

    /** How far the case falls, and how fast it gets there. */
    private const val GRAVITY = 3.1f

    /** The tumble as it falls — enough to read as dropped, not enough to read as thrown. */
    private const val FALL_TUMBLE_DEG = 46f

    // ---- The shatter ------------------------------------------------------------------

    /**
     * How far a fragment drifts from its place in the whole disc, in metres, before mass
     * scales it.
     *
     * **A few millimetres, and that is deliberate.** The disc has broken *where it lies* —
     * it is still in the tray and the lid is about to close over it. What sells the break is
     * the cracks opening and the pieces sitting at slightly different angles so the light
     * catches each one differently, not distance travelled. At 13.5mm it read as the disc
     * coming apart and drifting; the pieces are supposed to stay put.
     */
    private const val SPREAD = 0.0038f

    /**
     * How quickly the pieces stop moving. High: this settles, it does not fly.
     *
     * Raised from 7.5. The distance was never the problem — [SPREAD] is deliberately a few
     * millimetres — but at 7.5 the pieces took about a fifth of a second to reach it, which is
     * a *drift* apart. A break is violent and then over: the fragments should arrive almost
     * at once and then stop dead, which is what a short time constant gives.
     */
    private const val DRAG = 13.5f

    /**
     * How long the pieces stay exactly where they broke, in seconds after the strike.
     *
     * **This is the frame that was missing.** The whole disc was swapped for the fragment set
     * on the impact frame and the fragments began travelling on that same frame, at their
     * fastest — so the disc went from intact to coming apart with nothing in between, and the
     * eye never got to see it *cracked*. Real glass fails in that order: the cracks arrive
     * first, the piece holds itself together for a moment, and then it lets go.
     *
     * Short in sequence terms, but [timeScaleAt] is running at a third of speed here, so it
     * lands as roughly 130ms on screen — enough to register the cracks, not enough to feel
     * like a pause.
     */
    const val CRACK_HOLD_S = 0.045f

    /** The crack hold, for tests and for anything that needs to know when pieces move. */
    val impactHoldSeconds: Float get() = CRACK_HOLD_S

    /** Degrees a fragment tips where it lies. Small: they are settling in a tray, not tumbling. */
    private const val SHARD_TILT_DEG = 11f

    /** How far a suspended fragment drifts from where it broke, in metres. Sub-millimetre. */
    private const val HOVER_MM = 0.00085f

    /** And how far it turns as it hangs, in degrees. */
    private const val HOVER_TILT_DEG = 1.6f

    /** How long the drift takes to reach full strength, so it begins at the break, not before. */
    private const val HOVER_IN_S = 0.35f

    /** Where the impact lands, in disc-local metres. Off-centre, so the break is not symmetric. */
    private const val IMPACT_X = 0.016f
    private const val IMPACT_Y = 0.021f

    /** How long the impact's flash lasts, in seconds. Brief — it is a strike, not a lamp. */
    private const val FLASH_S = 0.26f

    /**
     * Everything the scene needs for one frame.
     *
     * The case's own transform, the lid's hinge, and the strike's flash. Fragment positions
     * come from [shardAt], one call per piece.
     */
    data class Frame(
        val caseX: Float,
        val caseY: Float,
        val caseZ: Float,
        val caseRotX: Float,
        val caseRotY: Float,
        val caseRotZ: Float,
        val hingeRotY: Float,
        /**
         * The intact disc, shown from the lid opening until the strike.
         *
         * **The fragment set cannot stand in for a whole disc**, which is what it was asked
         * to do at first: the pieces are bevelled and their cut edges are jittered
         * independently, so even sitting at zero offset there are visible seams between them
         * and the disc reads as *already broken* before anything hits it. So the real disc
         * is shown until the strike and the fragments replace it on that frame.
         */
        val wholeDiscVisible: Boolean,
        /** The fragments, from the strike onward. Never on screen at the same time as above. */
        val shardsVisible: Boolean,
        val struck: Boolean,
        /**
         * The album's underlight, 1 at full and 0 once it is gone.
         *
         * Fades across the whole removal rather than being cut on the first frame. The light
         * belongs to the album, so it should leave with it — snapping it off the instant the
         * case moves reads as a bug in the shelf rather than as the record going.
         */
        val beamGlow: Float,
        /** 0..1, peaking at the strike and gone shortly after. Drives the flash light. */
        val flash: Float,
        val done: Boolean
    )

    /** Where one fragment sits, relative to its place in the whole disc. */
    data class Shard(
        val offsetX: Float,
        val offsetY: Float,
        val offsetZ: Float,
        val tiltX: Float,
        val tiltY: Float,
        val tiltZ: Float
    )

    private fun sub(t: Float, from: Float, to: Float): Float =
        ((t - from) / (to - from)).coerceIn(0f, 1f)

    /** Zero velocity at both ends — what lets overlapping beats cross-fade. */
    private fun smootherStep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    /**
     * The case's shiver as the strike builds, in degrees.
     *
     * Grows with the square of the build-up so it stays imperceptible for most of the window
     * and only becomes obvious just before the break — which is what makes it read as
     * something straining rather than as a wobble. Ends exactly at the strike: the shiver is
     * the cause, so it must not outlive its effect.
     *
     * Each axis passes a different [rate] so they never line up into one clean oscillation,
     * which would read as rocking rather than shaking.
     */
    private fun shiver(t: Float, rate: Float): Float {
        if (t >= impactAt) return 0f
        val build = sub(t, BUILD_FROM, impactAt)
        return sin(t * rate * 60f) * SHIVER_DEG * build * build
    }

    /** How hard the case shakes at the very peak of the build-up, in degrees. */
    private const val SHIVER_DEG = 1.15f

    /**
     * The case's kick when the disc is struck, in degrees.
     *
     * **Nothing used to happen to the case at the strike.** The shiver stopped dead on the
     * impact frame and the rotation carried on through its curves as though the disc had come
     * apart on its own — so the break had a flash and a shatter, but no *force*. Whatever
     * struck the disc had to strike the case holding it.
     *
     * A struck object rings: it jumps hardest on the first swing and settles quickly. This is
     * a decaying oscillation rather than a shake, so it reads as a single blow being absorbed
     * rather than as more of the shiver that preceded it — the two must not look alike or the
     * strike is just the moment the wobble got bigger.
     */
    private const val RECOIL_DEG = 5.4f

    /** How fast the ring dies away, per second. Quick: this is a hit, not a vibration. */
    private const val RECOIL_DECAY = 11f

    /** Ring frequency, in Hz. */
    private const val RECOIL_HZ = 13f

    /** How far the case is driven back by the blow, in metres. */
    private const val RECOIL_Z = 0.011f

    /**
     * The ring after the blow, `1` at the strike and decaying to nothing.
     *
     * Separate from [shiver] and deliberately a different shape: the shiver grows into the
     * strike and this falls out of it, so the two never overlap and the impact is the hinge
     * between them.
     */
    private fun recoil(sinceImpact: Float): Float {
        if (sinceImpact <= 0f) return 0f
        return exp(-RECOIL_DECAY * sinceImpact) *
            cos(sinceImpact * RECOIL_HZ * 2f * PI.toFloat())
    }

    /** How brightly the disc glows just before the strike, as a fraction of the flash. */
    private const val BUILD_GLOW = 0.26f

    /**
     * @param slotX/slotY where the album sits on the wall — where the case starts.
     * @param panX/panY where the camera is looking. **The case is carried here as it rises**,
     *   exactly as `OpenSequence` does, and for a reason found the hard way: without it the
     *   whole removal plays wherever that album happens to hang on the wall, which is very
     *   often not where the user is looking. The first build of this sequence ran entirely
     *   off-screen — the album being removed was on a different shelf from the one in frame,
     *   so all that was visible was its neighbours carrying on as normal.
     */
    fun frameAt(
        t: Float,
        slotX: Float,
        slotY: Float,
        panX: Float,
        panY: Float
    ): Frame {
        val clamped = t.coerceIn(0f, 1f)

        val rise = smootherStep(sub(clamped, RISE_FROM, RISE_TO))
        val turn = smootherStep(sub(clamped, TURN_FROM, TURN_TO))
        val open = smootherStep(sub(clamped, OPEN_FROM, OPEN_TO))
        val close = smootherStep(sub(clamped, CLOSE_FROM, CLOSE_TO))
        val fall = sub(clamped, FALL_FROM, FALL_TO)

        // Gravity over the fall's own seconds, so changing DURATION_S changes how long it
        // falls rather than how hard it is pulled.
        val fallSeconds = fall * (FALL_TO - FALL_FROM) * DURATION_S
        val drop = 0.5f * GRAVITY * fallSeconds * fallSeconds * 9f

        val sinceImpact = secondsSinceImpact(clamped)
        val ring = recoil(sinceImpact)

        return Frame(
            // Carried from its slot to in front of the lens as it rises. This is what puts
            // the removal on screen at all — see the parameter docs.
            caseX = slotX + (panX - slotX) * rise,
            caseY = slotY + (panY + RISE_Y - slotY) * rise - drop,
            // Driven back by the blow, on top of the rise. The push decays with the ring, so
            // the case is knocked toward the viewer and recovers rather than staying there.
            caseZ = RISE_Z * rise + RECOIL_Z * ring,
            // Tips forward only as it falls, so the fall reads as losing its footing rather
            // than as being pushed — plus the shiver of the build-up, which grows as the
            // strike approaches and stops dead on it, and the ring of the blow after it.
            //
            // Unequal weights across the three axes: a blow that rocked all three the same
            // amount would read as a zoom rather than as an impact with a direction, and the
            // strike lands off-centre (IMPACT_X/Y) so it ought to twist as well as tip.
            caseRotX = FALL_TUMBLE_DEG * smootherStep(fall) + shiver(clamped, 13.7f) +
                RECOIL_DEG * ring,
            caseRotY = TURN_FROM_DEG * (1f - turn) + shiver(clamped, 17.3f) +
                RECOIL_DEG * 0.45f * ring,
            caseRotZ = 0.5f * FALL_TUMBLE_DEG * smootherStep(fall) + shiver(clamped, 11.1f) +
                RECOIL_DEG * 0.7f * ring,
            // Opens, then shuts over the pieces. One channel minus the other, so the two
            // overlap naturally instead of needing a state machine.
            hingeRotY = -HINGE_DEG * (open - close).coerceAtLeast(0f),
            wholeDiscVisible = open > 0.02f && clamped < impactAt,
            shardsVisible = clamped >= impactAt,
            struck = clamped >= impactAt,
            // Eased out over the approach, gone by the time the lid shuts.
            beamGlow = beamGlowAt(clamped),
            // Two shapes joined at the strike. Before it, a slow swell to a fraction of full
            // — enough to see something coming, not enough to pre-empt it. After it, a hard
            // spike that decays. The discontinuity at the join is the point: the climax has
            // to arrive, not be eased into.
            flash = if (sinceImpact <= 0f) {
                BUILD_GLOW * smootherStep(sub(clamped, BUILD_FROM, impactAt))
            } else {
                exp(-sinceImpact / (FLASH_S * 0.34f)).coerceIn(0f, 1f)
            },
            done = clamped >= 1f
        )
    }

    /**
     * How brightly the album's underlight is still burning at sequence time [t].
     *
     * Its own function because the shelf slot needs it during the slot pass, which runs
     * before the removal is driven — building a whole [Frame] there just to read one number
     * would be wasteful and would need four position arguments it does not have.
     */
    fun beamGlowAt(t: Float): Float =
        1f - smootherStep(sub(t.coerceIn(0f, 1f), 0.04f, CLOSE_TO))

    /**
     * How fast the sequence runs at [t], as a multiple of real time.
     *
     * **The break is worth slowing down for.** Everything up to the strike plays at speed;
     * around it the sequence drops to [SLOW_SCALE] for a moment and then recovers. It is the
     * oldest trick there is for making an impact land — the eye gets time to register that
     * the disc has come apart, instead of the whole thing being over before it has read — and
     * it costs one multiplier on `dt`.
     *
     * The ramp in and out is [smootherStep] rather than a step, because time itself jerking
     * is far more obvious than any object jerking. Total real duration comes out around 2.2s
     * for a nominal 1.9.
     */
    fun timeScaleAt(t: Float): Float {
        val d = kotlin.math.abs(t - impactAt)
        if (d >= SLOW_HALF_WIDTH) return 1f
        // 1 at the edges of the window, SLOW_SCALE at its centre.
        val edge = smootherStep(d / SLOW_HALF_WIDTH)
        return SLOW_SCALE + (1f - SLOW_SCALE) * edge
    }

    /** How far either side of the strike time is dilated, in sequence units. */
    private const val SLOW_HALF_WIDTH = 0.14f

    /** How slowly time runs at the strike itself. */
    private const val SLOW_SCALE = 0.34f

    /** Seconds since the disc was struck, at sequence time [t]. */
    fun secondsSinceImpact(t: Float): Float =
        ((t - impactAt) * DURATION_S).coerceAtLeast(0f)

    /**
     * Where fragment [index] has moved to, [seconds] after the strike.
     *
     * @param restX/restY where the piece sat in the whole disc — read from its own glTF node,
     *   because the build script puts each shard's origin at its centroid.
     * @param mass the fragment's share of the disc's area, from its node name. Small pieces
     *   move further and tip more; the large ones barely shift. That difference is what reads
     *   as *broken* rather than as a pattern of pieces, and identical motion across different
     *   masses is the fastest way to lose it.
     *
     * Deterministic, allocation-free, called 27 times a frame.
     */
    fun shardAt(
        index: Int,
        restX: Float,
        restY: Float,
        mass: Float,
        seconds: Float
    ): Shard {
        val s = seconds.coerceAtLeast(0f)

        // Away from the strike, not from the disc's centre: a fracture moves mass along the
        // direction the energy went.
        var dx = restX - IMPACT_X
        var dy = restY - IMPACT_Y
        val dist = hypot(dx, dy).coerceAtLeast(1e-4f)
        dx /= dist
        dy /= dist

        // Nearest the strike moves most, and it falls away with distance.
        val proximity = 1f / (1f + dist * 18f)
        // Small fragments shift, big ones hold their place.
        val byMass = 1f / (0.55f + mass)
        // Per-piece variation from the index alone, so no two move alike and the same album
        // always breaks the same way.
        val jitter = 0.66f + 0.5f * sin(index * 2.399f)

        val reach = SPREAD * (0.35f + proximity) * byMass * jitter
        // Held at zero for [CRACK_HOLD_S] — the disc is cracked but has not let go yet — then
        // full speed on the first moving frame, flattening after. That is the shape a break
        // has, and the opposite of easing outward.
        val moving = (s - CRACK_HOLD_S).coerceAtLeast(0f)
        val travel = reach * (1f - exp(-DRAG * moving))

        // Each piece hangs on its own once it has broken — drifting, turning a little,
        // never settling. It is what keeps the shattered disc *alive* in the frame instead
        // of becoming a static cracked texture, and it is why the pieces read as suspended
        // rather than as fallen.
        //
        // Every fragment has its own period and phase, taken from its index, so no two are
        // ever in step and the group never pulses together. The amplitudes are tiny — under
        // a millimetre — because the disc has to keep its shape: the pieces are a broken
        // disc floating, not a cloud of debris that used to be one. Lighter fragments drift
        // further, which is the same mass relationship the break itself uses.
        val drift = HOVER_MM * (0.45f + 0.55f * byMass)
        val hx = sin(s * (0.9f + 0.21f * index) + index * 1.7f) * drift
        val hy = sin(s * (1.1f + 0.17f * index) + index * 2.9f) * drift
        val hz = sin(s * (0.7f + 0.13f * index) + index * 0.6f) * drift * 1.4f
        val hover = smootherStep((s / HOVER_IN_S).coerceIn(0f, 1f))

        return Shard(
            offsetX = dx * travel + hx * hover,
            offsetY = dy * travel + hy * hover,
            // Lifted a little out of the disc's plane, so the cracks catch the light and the
            // pieces do not read as a flat decal. Alternating, so the surface breaks up.
            offsetZ = travel * 0.42f * sin(index * 1.117f) + hz * hover,
            tiltX = SHARD_TILT_DEG * travel / SPREAD * sin(index * 0.913f) * byMass * 0.4f +
                sin(s * 0.8f + index) * HOVER_TILT_DEG * hover,
            tiltY = SHARD_TILT_DEG * travel / SPREAD * sin(index * 1.771f) * byMass * 0.4f +
                sin(s * 0.6f + index * 2.1f) * HOVER_TILT_DEG * hover,
            tiltZ = SHARD_TILT_DEG * travel / SPREAD * jitter * byMass * 0.3f +
                sin(s * 0.5f + index * 3.3f) * HOVER_TILT_DEG * hover
        )
    }
}
