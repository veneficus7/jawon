package com.jawon.app.ui.wallscene

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
    private const val IMPACT_AT = 0.56f

    private const val SHATTER_FROM = 0.56f
    private const val SHATTER_TO = 0.76f
    private const val CLOSE_FROM = 0.68f
    private const val CLOSE_TO = 0.88f
    private const val FALL_FROM = 0.80f
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
     * **Millimetres, not centimetres.** The lid closes over these, so they have to stay
     * inside a 125mm tray. What sells the break is the *cracks opening* and the pieces
     * catching the light at slightly different angles, not distance travelled.
     */
    private const val SPREAD = 0.0135f

    /** How quickly the pieces stop moving. High: this settles, it does not fly. */
    private const val DRAG = 7.5f

    /** Degrees a fragment tips as it settles. Small — they are lying in a tray, not tumbling. */
    private const val SHARD_TILT_DEG = 34f

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
        val caseY: Float,
        val caseZ: Float,
        val caseRotX: Float,
        val caseRotY: Float,
        val caseRotZ: Float,
        val hingeRotY: Float,
        val discVisible: Boolean,
        val struck: Boolean,
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

    fun frameAt(t: Float): Frame {
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

        return Frame(
            caseY = RISE_Y * rise - drop,
            caseZ = RISE_Z * rise,
            // Tips forward only as it falls, so the fall reads as losing its footing rather
            // than as being pushed.
            caseRotX = FALL_TUMBLE_DEG * smootherStep(fall),
            caseRotY = TURN_FROM_DEG * (1f - turn),
            caseRotZ = 0.5f * FALL_TUMBLE_DEG * smootherStep(fall),
            // Opens, then shuts over the pieces. One channel minus the other, so the two
            // overlap naturally instead of needing a state machine.
            hingeRotY = -HINGE_DEG * (open - close).coerceAtLeast(0f),
            discVisible = open > 0.02f,
            struck = clamped >= IMPACT_AT,
            // A hard spike that decays — a strike, not a fade in and out.
            flash = if (sinceImpact <= 0f) 0f
            else exp(-sinceImpact / (FLASH_S * 0.34f)).coerceIn(0f, 1f),
            done = clamped >= 1f
        )
    }

    /** Seconds since the disc was struck, at sequence time [t]. */
    fun secondsSinceImpact(t: Float): Float =
        ((t - IMPACT_AT) * DURATION_S).coerceAtLeast(0f)

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
        // Full speed on the first frame, flattening after — the shape a settling break has,
        // and the opposite of easing outward.
        val travel = reach * (1f - exp(-DRAG * s))

        return Shard(
            offsetX = dx * travel,
            offsetY = dy * travel,
            // Lifted a little out of the disc's plane, so the cracks catch the light and the
            // pieces do not read as a flat decal. Alternating, so the surface breaks up.
            offsetZ = travel * 0.42f * sin(index * 1.117f),
            tiltX = SHARD_TILT_DEG * travel / SPREAD * sin(index * 0.913f) * byMass * 0.4f,
            tiltY = SHARD_TILT_DEG * travel / SPREAD * sin(index * 1.771f) * byMass * 0.4f,
            tiltZ = SHARD_TILT_DEG * travel / SPREAD * jitter * byMass * 0.3f
        )
    }
}
