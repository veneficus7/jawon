package com.jawon.app.ui.wallscene

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What happens when an album is removed: the case leaves the shelf, tips, drops its disc, and
 * the disc hits the shelf edge and breaks apart.
 *
 * Pure maths, no Filament types — the same discipline as [OpenSequence], and for the same
 * reasons. Everything is a function of one scalar, so the whole thing is reproducible,
 * skippable by jumping the clock, and testable without a device.
 *
 * ---
 *
 * **The first version of this looked wrong, and it is worth recording why**, because all
 * three faults are the standard ones and none of them were about the rendering.
 *
 * 1. **Even pieces.** Eight identical wedges read as a pie chart coming apart. Real fracture
 *    makes pieces of visibly different sizes — the geometry is now 27 fragments spanning
 *    roughly 8× in area, cut on two axes.
 * 2. **Uniform speed.** Everything flew apart at about the same rate, and identical timing
 *    across identical masses is the fastest way to destroy the illusion of weight. Impact
 *    studies are specific about the real behaviour: *small fragments move fast, and the
 *    largest, slowest pieces end up opposite the impact.* [shardAt] now scales speed by
 *    `1 / mass` and by nearness to where the disc was struck.
 * 3. **No cause.** The disc simply came apart in mid-air. Nothing broke it, so it read as an
 *    effect rather than an event. It now falls and *hits the shelf edge*, and the fracture
 *    radiates from that point.
 *
 * The motion curve follows the same advice: a snap, not a swell. Pieces leave at their
 * maximum speed on the frame of impact and decay under drag — "fast action, end slow" —
 * rather than easing outward, which is what a spreading ring of debris never does.
 */
object CaseDiscard {

    /**
     * 1.15 seconds, in four beats.
     *
     * Long enough to read as: taken off the shelf, dropped, struck, gone. Short enough that
     * clearing a shelf is not a chore — `ENGINEERING.md` warns that an animation delightful once
     * is tiresome on the twentieth album, and a tap skips it.
     */
    const val DURATION_S = 1.15f

    /** Fragment count. Must match `SHARD_SPOKES * (len(SHARD_RINGS) - 1)` in the build script. */
    const val SHARDS = 27

    // Beat windows, overlapped so each blends into the next rather than starting from a
    // standstill. The impact is a moment, not a window.
    private const val PULL_FROM = 0.00f
    private const val PULL_TO = 0.26f
    private const val SLIDE_FROM = 0.20f
    private const val SLIDE_TO = 0.44f

    /**
     * When the disc strikes the shelf edge — the single most important number here.
     *
     * Everything before it is approach and everything after is aftermath, so it wants to land
     * early enough to leave room for the debris to travel and late enough that the fall reads
     * as a fall. There is a deliberate beat of nothing just before it: the disc is clear of
     * the case, falling, and briefly the only thing moving. Anticipation costs 60ms and is
     * what makes the break feel struck rather than triggered.
     */
    private const val IMPACT_AT = 0.46f

    /** How far the case comes off the wall toward the viewer, in metres. */
    private const val PULL_Z = 0.085f

    /** How far it tips while doing it, in degrees — enough to spill the disc out. */
    private const val TIP_DEG = 42f

    /** How far the disc travels clear of the case before it falls, in metres. */
    private const val SLIDE_Y = 0.048f

    /** Downward acceleration, in metres per second squared. Not earth's — this is a shot. */
    private const val GRAVITY = 2.4f

    /**
     * Peak speed a fragment leaves the impact with, in metres per second, before mass and
     * distance scale it down.
     */
    private const val BURST_SPEED = 0.62f

    /**
     * How quickly a fragment sheds that speed, per second.
     *
     * Drag is what turns a burst into debris. Displacement integrates to
     * `v0 * (1 - e^(-kt)) / k`, which starts at full speed and flattens — the "fast action,
     * end slow" shape. Easing outward instead, which the first version did, is the one thing
     * a spreading debris field never does.
     */
    private const val DRAG = 5.2f

    /** How long a fragment stays before it is gone, in seconds. */
    private const val SHARD_LIFE_S = 0.62f

    /** Where the rim meets the shelf, in disc-local metres — bottom of the disc. */
    private const val IMPACT_X = 0.012f
    private const val IMPACT_Y = -0.060f

    /**
     * Everything the scene needs for one frame.
     *
     * The disc is whole until [struck]; after it, the fragments take over.
     */
    data class Frame(
        val caseZ: Float,
        val caseY: Float,
        val caseTipDeg: Float,
        val caseScale: Float,
        val discY: Float,
        val discZ: Float,
        val discTiltDeg: Float,
        val discVisible: Boolean,
        val struck: Boolean,
        val done: Boolean
    )

    /** Where one fragment is, relative to where it sat when the disc was whole. */
    data class Shard(
        val offsetX: Float,
        val offsetY: Float,
        val offsetZ: Float,
        val spinX: Float,
        val spinY: Float,
        val spinZ: Float,
        val scale: Float
    )

    private fun sub(t: Float, from: Float, to: Float): Float =
        ((t - from) / (to - from)).coerceIn(0f, 1f)

    /** Smooth start and end, zero velocity at both. */
    private fun ease(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    fun frameAt(t: Float): Frame {
        val clamped = t.coerceIn(0f, 1f)
        val pull = ease(sub(clamped, PULL_FROM, PULL_TO))
        val slide = ease(sub(clamped, SLIDE_FROM, SLIDE_TO))
        val struck = clamped >= IMPACT_AT

        // The disc's own fall, from the moment it is clear of the case until it lands.
        val fallT = sub(clamped, SLIDE_TO, IMPACT_AT)
        val fallSeconds = fallT * (IMPACT_AT - SLIDE_TO) * DURATION_S
        val discDrop = 0.5f * GRAVITY * fallSeconds * fallSeconds * 6f

        // The case keeps going after the disc leaves it, and out of the lamp's pool.
        val caseFallT = sub(clamped, SLIDE_FROM, 1f)
        val caseSeconds = caseFallT * (1f - SLIDE_FROM) * DURATION_S
        val caseDrop = 0.5f * GRAVITY * caseSeconds * caseSeconds

        return Frame(
            caseZ = PULL_Z * pull,
            caseY = -caseDrop,
            caseTipDeg = TIP_DEG * pull,
            // Shrunk only at the very end, and only a little: the room's darkness below the
            // lamp does most of the work of taking it away.
            caseScale = 1f - 0.35f * sub(clamped, 0.80f, 1f),
            discY = SLIDE_Y * slide - discDrop,
            discZ = PULL_Z * pull,
            // Tips as it falls, so it meets the shelf edge-on rather than flat.
            discTiltDeg = 26f * slide,
            discVisible = slide > 0.01f,
            struck = struck,
            done = clamped >= 1f
        )
    }

    /** Seconds since the disc was struck, at sequence time [t]. */
    fun secondsSinceImpact(t: Float): Float =
        ((t - IMPACT_AT) * DURATION_S).coerceAtLeast(0f)

    /**
     * Where fragment [index] is, [seconds] after the impact.
     *
     * @param restX/restY where the piece sat in the whole disc — read from its own glTF node,
     *   because the build script puts each shard's origin at its centroid rather than at the
     *   disc's centre.
     * @param mass the fragment's share of the disc's area, 0..1, from its node name. **This
     *   is what makes it read as broken rather than scattered:** speed goes as `1 / mass`, so
     *   splinters snap away and the big pieces barely move, which is what impact studies
     *   describe and what uniform speed destroyed.
     *
     * Deterministic, allocation-free, and called 27 times a frame.
     */
    fun shardAt(
        index: Int,
        restX: Float,
        restY: Float,
        mass: Float,
        seconds: Float
    ): Shard {
        val s = seconds.coerceAtLeast(0f)

        // Away from where the disc was struck, not away from its centre. A fracture throws
        // mass along the direction the energy travelled.
        var dx = restX - IMPACT_X
        var dy = restY - IMPACT_Y
        val dist = hypot(dx, dy).coerceAtLeast(1e-4f)
        dx /= dist
        dy /= dist

        // Nearest the impact gets the most energy, and it falls off with distance.
        val proximity = 1f / (1f + dist * 14f)

        // Small pieces fast, large pieces slow. The 0.25 floor stops the smallest splinters
        // going to infinity.
        val byMass = 1f / (0.25f + mass)

        // Per-piece variation from the index alone — no randomness at runtime, and the same
        // album always breaks the same way.
        val jitter = 0.72f + 0.42f * sin(index * 2.399f)

        val speed = BURST_SPEED * (0.30f + proximity) * byMass * jitter * 0.25f

        // Drag integral: full speed on the first frame, flattening after. This is the shape
        // the whole effect turns on.
        val travel = speed * (1f - exp(-DRAG * s)) / DRAG

        // A little out of the disc's plane, so the debris has depth instead of staying on the
        // flat it came from.
        val outOfPlane = sin(index * 1.117f) * 0.35f

        val life = (1f - s / SHARD_LIFE_S).coerceIn(0f, 1f)

        return Shard(
            offsetX = dx * travel,
            offsetY = dy * travel - 0.5f * GRAVITY * s * s,
            offsetZ = outOfPlane * travel,
            // Spun about its own centroid, and faster the smaller it is.
            spinX = 260f * s * byMass * sin(index * 0.913f),
            spinY = 340f * s * byMass * jitter,
            spinZ = 190f * s * byMass * sin(index * 1.771f),
            // Shrunk away at the end rather than faded: the material is opaque, and blending
            // 27 tumbling fragments would put a depth-sorted mess into the frame for a fade
            // the darkness already provides.
            scale = ease(life)
        )
    }
}
