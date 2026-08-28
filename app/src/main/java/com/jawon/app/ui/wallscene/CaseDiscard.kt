package com.jawon.app.ui.wallscene

import kotlin.math.cos
import kotlin.math.sin

/**
 * What happens when an album is removed: the case leaves the shelf, tips, lets its disc slide
 * out, and the disc cracks apart and falls away into the dark under the shelves.
 *
 * Pure maths, no Filament types — the same discipline as [OpenSequence], and for the same
 * reasons. Every value is a function of one scalar `t` in 0..1, so the whole thing is
 * reproducible, skippable by jumping the clock, and testable without a device.
 *
 * **Why the disc has to be revealed first.** Removal starts from the wall, where a *closed*
 * case is all that is on screen — there is no disc to break. The first two beats exist to put
 * one there. Skipping them and simply shattering the case was the alternative; breaking the
 * disc is what was asked for, and it is also the more legible image, because a cracked disc
 * reads instantly and a cracked case reads as a cracked box.
 *
 * **Nothing here is allowed to be slow.** `ENGINEERING.md` warns that an animation delightful once
 * is tiresome on the twentieth album, so the whole sequence is [DURATION_S] and a tap jumps
 * the clock past the end. Under reduced motion the caller skips it entirely.
 */
object CaseDiscard {

    /**
     * 1.05 seconds for four beats.
     *
     * Long enough to read as: taken off the shelf, opened, broken, gone. Short enough that
     * clearing out a shelf does not become a chore. The open sequence gets 1.9s because it is
     * an invitation; this is a dismissal and should not linger.
     */
    const val DURATION_S = 1.05f

    /** Shard count. Must match `SHARD_COUNT` in `tools/models/build_models.py`. */
    const val SHARDS = 8

    // Beat windows, overlapped the way OpenSequence's are so each blends into the next
    // instead of starting from a standstill.
    private const val PULL_FROM = 0.00f
    private const val PULL_TO = 0.30f
    private const val SLIDE_FROM = 0.24f
    private const val SLIDE_TO = 0.55f
    private const val CRACK_AT = 0.52f
    private const val FALL_FROM = 0.55f
    private const val FALL_TO = 1.00f

    /** How far the case comes off the wall toward the viewer, in metres. */
    private const val PULL_Z = 0.085f

    /** How far it tips while doing it, in degrees — enough to spill the disc out. */
    private const val TIP_DEG = 38f

    /** How far the disc slides clear of the case before it breaks, in metres. */
    private const val SLIDE_Y = 0.055f

    /** Downward acceleration once things are falling, in metres per second squared. */
    private const val GRAVITY = 1.9f

    /** How fast shards fly apart from the break, in metres per second. */
    private const val BURST_SPEED = 0.115f

    /** Degrees per second a shard tumbles. Scaled per shard so none match. */
    private const val TUMBLE_DEG_PER_S = 420f

    /**
     * Everything the scene needs for one frame of a discard.
     *
     * The case and the disc are whole until [cracked]; after it the disc is hidden and the
     * eight shards take over.
     */
    data class Frame(
        val caseZ: Float,
        val caseY: Float,
        val caseTipDeg: Float,
        val caseAlpha: Float,
        val discY: Float,
        val discZ: Float,
        val discVisible: Boolean,
        val cracked: Boolean,
        val done: Boolean
    )

    /** Where one shard is, relative to the disc's centre at the moment it broke. */
    data class Shard(
        val x: Float,
        val y: Float,
        val z: Float,
        val spinDeg: Float,
        val alpha: Float
    )

    /**
     * Saturating sub-range, so a beat is 0 before its window and 1 after it. Identical in
     * spirit to `OpenSequence.sub` — kept local rather than shared because these two
     * sequences should stay free to diverge.
     */
    private fun sub(t: Float, from: Float, to: Float): Float =
        ((t - from) / (to - from)).coerceIn(0f, 1f)

    /** Smooth start and end, zero velocity at both — Ken Perlin's smootherstep. */
    private fun ease(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    fun frameAt(t: Float): Frame {
        val clamped = t.coerceIn(0f, 1f)
        val pull = ease(sub(clamped, PULL_FROM, PULL_TO))
        val slide = ease(sub(clamped, SLIDE_FROM, SLIDE_TO))
        val cracked = clamped >= CRACK_AT
        val fall = sub(clamped, FALL_FROM, FALL_TO)

        // Gravity is integrated over the fall's own seconds, not over `t`, so changing
        // DURATION_S changes how long it falls rather than how hard it is pulled.
        val fallSeconds = fall * (FALL_TO - FALL_FROM) * DURATION_S
        val drop = 0.5f * GRAVITY * fallSeconds * fallSeconds

        return Frame(
            caseZ = PULL_Z * pull,
            caseY = -drop,
            caseTipDeg = TIP_DEG * pull,
            // The case fades as it leaves the lamp's pool rather than vanishing on a
            // frame — DESIGN.md's darkness does most of the work, this finishes it.
            caseAlpha = 1f - sub(clamped, 0.72f, 1f),
            discY = SLIDE_Y * slide,
            discZ = PULL_Z * pull,
            discVisible = slide > 0.01f && !cracked,
            cracked = cracked,
            done = clamped >= 1f
        )
    }

    /**
     * Where shard [index] is, [secondsSinceCrack] after the disc came apart.
     *
     * Each shard flies outward along its own wedge's bisector, which is what makes the break
     * read as the disc coming apart rather than as pieces being scattered — a real fracture
     * throws mass away from the centre. On top of that it tumbles and falls.
     *
     * Deterministic in [index], so the same album always breaks the same way. No randomness
     * at runtime: this is called every frame for eight shards and must not allocate or
     * surprise.
     */
    fun shardAt(index: Int, secondsSinceCrack: Float): Shard {
        val s = secondsSinceCrack.coerceAtLeast(0f)
        val bisector = (index + 0.5f) / SHARDS * TAU

        // Uneven speeds, from the index rather than a random source. Without this the eight
        // pieces stay on a perfect expanding circle, which reads as a mechanism.
        val speed = BURST_SPEED * (0.72f + 0.28f * sin(index * 2.4f))
        val outward = speed * s

        // A little forward as well as outward, so the break has depth on screen instead of
        // happening entirely within one plane.
        val toward = 0.35f * speed * s

        return Shard(
            x = cos(bisector) * outward,
            y = sin(bisector) * outward - 0.5f * GRAVITY * s * s,
            z = toward,
            spinDeg = TUMBLE_DEG_PER_S * s * (0.55f + 0.45f * cos(index * 1.7f)),
            // Gone by the time they would reach the shelf below.
            alpha = (1f - s / SHARD_LIFE_S).coerceIn(0f, 1f)
        )
    }

    /** How long a shard stays visible after the break, in seconds. */
    private const val SHARD_LIFE_S = 0.5f

    private const val TAU = 6.2831855f

    /** Seconds of fall a shard has had at sequence time [t]. */
    fun secondsSinceCrack(t: Float): Float =
        ((t - CRACK_AT) * DURATION_S).coerceAtLeast(0f)
}
