package com.jawon.app.ui.intro

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * **The opening, as one piece of time** (0.7.6).
 *
 * Until now Jawon opened twice: the system's splash drew the mark in 0.9s, cut to a different
 * black, and a second card faded a wordmark up and away. Testers saw exactly that — "two intros,
 * and both of them fast" — and one asked for a long one, made properly. So there is one now. The
 * mark the system splash draws is the same mark, in the same place, that this sequence picks up:
 * the tube catches, light runs once round the rim and down the J into the hole, the name arrives
 * under it, and the way out is *through the hole* — the one shape every format shares
 * (`JawonMark`) opens into the room.
 *
 * Everything here is a pure function of milliseconds, so the overlay only draws what it is told
 * and the timing can be tested without a screen. Times are measured from the moment the sequence
 * takes over; [beat] and [exit] split a frame's time into the two clocks everything reads, which
 * is what makes a tap reach the way out from any moment without anything jumping.
 */
internal object IntroTimeline {

    // ---- The beats, in ms ----------------------------------------------------------------

    /** The mark leaving the place the splash drew it for where it rests above the name. */
    const val RISE_START = 260f
    const val RISE_MS = 950f

    /** The light's lap: round the rim from where the J hangs, then down the J into the hole. */
    const val LAP_START = 420f
    const val LAP_MS = 1500f

    /** How far behind the light's head its tail runs, as a share of the lap's time. */
    const val LAP_TAIL = 0.17f

    /** The name, a letter at a time. */
    const val LETTERS_START = 1180f
    const val LETTER_STAGGER = 88f
    const val LETTER_MS = 560f
    const val LETTERS = 5

    const val RULE_START = 1960f
    const val RULE_MS = 440f

    const val BYLINE_START = 2400f

    /** When the finished card starts to leave, untouched. */
    const val EXIT_AT = 3750f

    /** The way out: through the hole. */
    const val EXIT_MS = 1250f

    /**
     * How long before the way out the room under the card starts drawing again. It is parked while
     * the card is whole — a lit room behind an opaque black card is heat for nothing, and it took
     * frames from the card — and needs a few frames in hand before the hole opens onto it.
     */
    const val UNCOVER_LEAD_MS = 320f

    /**
     * The short opening, for Settings → Short intro and for coming back to a phone that put Jawon
     * to sleep: the tube catches and the hole opens, with no name and no wait.
     */
    const val QUICK_EXIT_AT = 520f

    const val TOTAL_MS = EXIT_AT + EXIT_MS

    // ---- The two clocks --------------------------------------------------------------------

    /** Time for everything that builds the card: it stops where the way out began. */
    fun beat(t: Float, exitAt: Float): Float = t.coerceIn(0f, exitAt)

    /** 0..1 through the way out. */
    fun exit(t: Float, exitAt: Float): Float = ((t - exitAt) / EXIT_MS).coerceIn(0f, 1f)

    // ---- The tube --------------------------------------------------------------------------

    /**
     * The tube catching as the sequence takes over: two dropouts, short enough to read as
     * electrical rather than as a fault, then held. It also covers the hand-over itself — whatever
     * a pixel's difference there is between the system's drawing and this one happens in a flicker.
     */
    fun flicker(beat: Float): Float = piecewise(beat, FLICKER)

    private val FLICKER = floatArrayOf(
        0f, 1f,
        40f, 0.42f,
        92f, 1f,
        124f, 0.70f,
        196f, 1f
    )

    /**
     * How much light the sign throws on the wall behind it, as a multiple of the splash's glow:
     * a swell as the tube catches, a kick when the light lands in the hole, and then a slow breath
     * for as long as the card is held — a still card reads as waiting, a breathing one as lit.
     */
    fun bloom(beat: Float): Float {
        val swell = when {
            beat < 60f -> 1f
            beat < 340f -> 1f + 0.95f * easeOut((beat - 60f) / 280f)
            beat < 820f -> 1.95f - 0.55f * easeInOut((beat - 340f) / 480f)
            else -> 1.4f
        }
        val landed = beat - LAP_END
        // Fast up, slow down — but up, not there at once: written as a step it was a single frame
        // of the whole wall jumping brighter, which is a glitch and not a flash.
        val kick = if (landed > 0f) 0.6f * (1f - exp(-landed / 40f)) * exp(-landed / 300f) else 0f
        val breath = if (beat > 820f) 0.09f * sin(2f * PI.toFloat() * (beat - 820f) / 2600f) else 0f
        return swell + kick + breath
    }

    // ---- The splash this takes over from --------------------------------------------------

    /** The system's splash: its mark written in this long, and then its glow breathing. */
    const val SPLASH_WRITE_MS = 900f
    const val SPLASH_BREATH_MS = 1100f
    const val SPLASH_BREATH_LOW = 0.55f

    /**
     * How bright the splash's glow is, [ms] after its mark began to write itself — the same curve
     * `res/animator/splash_glow_in.xml` runs, so the card can pick the glow up at the level the
     * splash had reached instead of snapping it back to full. Measured before this existed: the
     * hand-over was pixel-exact in place and size and still showed, as the wall behind the sign
     * jumping brighter in one frame.
     */
    fun splashGlow(ms: Float): Float {
        val since = ms - SPLASH_WRITE_MS
        if (since <= 0f) return 1f
        val cycle = since / SPLASH_BREATH_MS
        val whole = cycle.toInt()
        val part = if (whole % 2 == 0) cycle - whole else 1f - (cycle - whole)
        // `accelerate_decelerate`, the interpolator the animator names.
        val eased = 0.5f - 0.5f * cos(PI.toFloat() * part)
        return 1f + (SPLASH_BREATH_LOW - 1f) * eased
    }

    /** The glow coming back up from where the splash left it, as the tube catches. */
    fun glowSettle(from: Float, beat: Float): Float =
        from + (1f - from) * easeInOut((beat / GLOW_SETTLE_MS).coerceIn(0f, 1f))

    private const val GLOW_SETTLE_MS = 200f

    // ---- The mark's move ------------------------------------------------------------------

    /**
     * 0..1 from where the splash left the mark to where it rests: a critically damped spring's
     * closed form, so it leaves gently, never overshoots the name it is making room for, and is
     * within a hundredth of its place when [RISE_MS] is up.
     */
    fun rise(beat: Float): Float {
        val x = ((beat - RISE_START) / RISE_MS).coerceAtLeast(0f) * RISE_SETTLE
        return 1f - (1f + x) * exp(-x)
    }

    /** `(1 + x)·e⁻ˣ = 0.01` at x ≈ 6.64. */
    private const val RISE_SETTLE = 6.64f

    // ---- The light's lap ------------------------------------------------------------------

    /** When the head reaches the end of the J's hook, at the hole. */
    const val LAP_END = LAP_START + LAP_MS

    /** The head and the tail of the light, each 0..1 along [MarkPath]; equal when there is none. */
    fun lapHead(beat: Float): Float = easeInOut(((beat - LAP_START) / LAP_MS).coerceIn(0f, 1f))

    fun lapTail(beat: Float): Float =
        easeInOut(((beat - LAP_START) / LAP_MS - LAP_TAIL).coerceIn(0f, 1f))

    /** The hole taking the light: one bounce, the only beat in the opening allowed to land with one. */
    fun holePulse(beat: Float): Float {
        val x = beat - LAP_END
        if (x <= 0f) return 1f
        return 1f + 0.5f * exp(-x / 190f) * sin(2f * PI.toFloat() * x / 430f)
    }

    // ---- The name --------------------------------------------------------------------------

    /** 0..1 for letter [index] of the name: its opacity, and one minus how far it still has to settle. */
    fun letter(index: Int, beat: Float): Float =
        easeOut(((beat - LETTERS_START - index * LETTER_STAGGER) / LETTER_MS).coerceIn(0f, 1f))

    /** The rule under the name, drawn out from its middle. */
    fun rule(beat: Float): Float = easeInOut(((beat - RULE_START) / RULE_MS).coerceIn(0f, 1f))

    /**
     * The credit striking on the way a tube does — it catches, drops out, catches harder, gutters
     * once and holds. Opacity only: a light that is out is simply not there, where geometry moving
     * in steps is a fault and never an effect.
     */
    fun byline(beat: Float): Float = piecewise(beat - BYLINE_START, STRIKE)

    private val STRIKE = floatArrayOf(
        0f, 0f,
        55f, 0.85f,
        90f, 0.10f,
        145f, 1f,
        175f, 0.35f,
        330f, 1f
    )

    // ---- The way out -----------------------------------------------------------------------

    /** The name, the rule and the credit going first, quickly, so the mark is alone when it opens. */
    fun wordsOut(exit: Float): Float = 1f - easeInOut((exit / 0.22f).coerceIn(0f, 1f))

    /**
     * How large the mark is drawn about its hole, 1 at rest and [end] when the hole has grown past
     * the screen's furthest corner. It gathers itself first — a small step back, the breath before
     * a jump — and then falls in: the scale is exponential, which is what moving toward something
     * at a steady speed looks like, eased so it leaves from rest.
     */
    fun zoom(exit: Float, end: Float): Float {
        val gather = 1f - 0.06f * sin(PI.toFloat() * (exit / GATHER).coerceIn(0f, 1f))
        val fall = ((exit - GATHER * 0.5f) / (1f - GATHER * 0.5f)).coerceIn(0f, 1f)
        return gather * end.pow(fall.pow(FALL_EASE))
    }

    private const val GATHER = 0.22f

    /**
     * How much the fall holds back at its start. Close to 1 on purpose: an exponential scale is
     * already slow to begin, and eased any harder the hole stayed a dot for most of the way out and
     * the whole room arrived in its last fifth of a second — a cut with a preamble.
     */
    private const val FALL_EASE = 1.15f

    /** 0..1: the hole turning from a lit dot into an opening. */
    fun open(exit: Float): Float = easeInOut(((exit - 0.10f) / 0.20f).coerceIn(0f, 1f))

    /** The rim and the J thinning out as they come past: 1 until they are large, gone before the end. */
    fun strokesOut(exit: Float): Float = 1f - easeInOut(((exit - 0.34f) / 0.32f).coerceIn(0f, 1f))

    /** The lit edge of the opening, fading as it leaves the screen. */
    fun edgeOut(exit: Float): Float = 1f - easeInOut(((exit - 0.62f) / 0.36f).coerceIn(0f, 1f))

    // ---- Easing ----------------------------------------------------------------------------

    fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)

    fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)

    /** Straight lines between `(time, value)` pairs; held at the first before it and the last after. */
    private fun piecewise(x: Float, points: FloatArray): Float {
        if (x <= points[0]) return points[1]
        var i = 2
        while (i < points.size) {
            if (x <= points[i]) {
                val x0 = points[i - 2]
                val f = (x - x0) / (points[i] - x0)
                return points[i - 1] + (points[i + 1] - points[i - 1]) * f
            }
            i += 2
        }
        return points[points.size - 1]
    }
}

/**
 * The mark's own line — the rim, then the J — as one path a light can run along.
 *
 * In the units of the mark's artwork (`res/drawable/ic_splash_mark.xml`, 108 across, centred on
 * 54,54), and in the order the splash draws it: round the rim clockwise from where the J hangs,
 * back to that point, down the stem and round the hole.
 */
internal object MarkPath {
    const val VIEWPORT = 108f
    const val CENTRE = 54f

    const val RIM_RADIUS = 27.6f
    const val RIM_STROKE = 3.3f
    const val RIM_HALO = 7.5f
    const val RIM_CORE = 1.1f

    const val J_STROKE = 6f
    const val J_HALO = 11f
    const val J_CORE = 2.2f

    const val STEM_X = 64.2f
    const val STEM_TOP = 30f
    const val HOOK_RADIUS = 10.2f
    const val HOOK_SWEEP_DEG = 140f

    const val HOLE_HALO = 7f
    const val HOLE_RADIUS = 4.5f
    const val HOLE_CORE = 2f

    const val GLOW_RADIUS = 34f

    /** Where the rim's line starts: the point on it above the J's stem. */
    val RIM_START_DEG: Float =
        Math.toDegrees(kotlin.math.atan2((28.35f - CENTRE).toDouble(), (STEM_X - CENTRE).toDouble())).toFloat()

    private val RIM_LENGTH = 2f * PI.toFloat() * RIM_RADIUS
    private val STEM_LENGTH = CENTRE - STEM_TOP
    private val HOOK_LENGTH = HOOK_RADIUS * Math.toRadians(HOOK_SWEEP_DEG.toDouble()).toFloat()

    val LENGTH: Float = RIM_LENGTH + STEM_LENGTH + HOOK_LENGTH

    /** Whether the point [s] (0..1 along the line) is on the J rather than the rim. */
    fun onJ(s: Float): Boolean = s * LENGTH > RIM_LENGTH

    /** The point [s] of the way along, 0..1, as `x` and `y` written into [out]. */
    fun point(s: Float, out: FloatArray) {
        var d = s.coerceIn(0f, 1f) * LENGTH
        if (d <= RIM_LENGTH) {
            val a = Math.toRadians((RIM_START_DEG + 360f * d / RIM_LENGTH).toDouble())
            out[0] = CENTRE + RIM_RADIUS * cos(a).toFloat()
            out[1] = CENTRE + RIM_RADIUS * sin(a).toFloat()
            return
        }
        d -= RIM_LENGTH
        if (d <= STEM_LENGTH) {
            out[0] = STEM_X
            out[1] = STEM_TOP + d
            return
        }
        d -= STEM_LENGTH
        val a = Math.toRadians((HOOK_SWEEP_DEG * d / HOOK_LENGTH).toDouble())
        out[0] = CENTRE + HOOK_RADIUS * cos(a).toFloat()
        out[1] = CENTRE + HOOK_RADIUS * sin(a).toFloat()
    }
}
