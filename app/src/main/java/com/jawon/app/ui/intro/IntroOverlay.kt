package com.jawon.app.ui.intro

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What the system's own splash hands to the opening: where it drew the mark, and when it let go.
 *
 * Android 12 and later draw the mark themselves, before any of this app's code runs
 * (`ic_splash_mark_animated.xml`), and `MainActivity` takes that splash off the screen by hand so
 * that there is no cross-fade between it and this — the mark is simply still there, and now it is
 * this one. Older phones show the mark still, as the window's background, from the first instant.
 */
@Stable
internal class SplashHandOver(
    /** Whether a system splash is up and will say when it has gone. */
    waiting: Boolean,
    /** Whether the mark is already on the screen — drawn by the system, or as the window's background. */
    markShown: Boolean
) {
    /** Where the system drew the mark, in the window's pixels; null to use the size it draws by default. */
    var rect by mutableStateOf<Rect?>(null)

    /** Still under the system's splash. */
    var waiting by mutableStateOf(waiting)

    /** The system's splash has been handed to the app, which now decides when it goes. */
    var claimed by mutableStateOf(false)

    /** When the system's mark began to write itself, and when it finished, in `SystemClock.uptimeMillis`. */
    var startedAt = 0L
    var writtenAt = 0L

    /** Makes the system's splash see-through, so that what is under it is what is on the screen. */
    var uncover: (() -> Unit)? = null

    /** Takes the system's splash off the screen. Set with [claimed]. */
    var release: (() -> Unit)? = null

    /** False when the system showed no mark of its own, and this has to write it. */
    var markShown by mutableStateOf(markShown)
}

/** How much of the opening to play. */
internal enum class IntroKind {
    /** The whole sequence: a real launch. */
    FULL,

    /** The tube catches and the hole opens — Settings → Short intro, and waking a sleeping app. */
    QUICK
}

/**
 * **The opening**: one sequence, over the room while it builds — see [IntroTimeline].
 *
 * It is covering real work. The room is a Filament scene: a cubemap is prefiltered, the models load
 * and the first covers are baked, none of it instant. So the card holds the lit mark still until
 * the frames under it run evenly ([awaitCalm]) and only then starts to move, which is what keeps a
 * slow phone's opening from stuttering through its best second; and while the card is whole the
 * room is told to stop drawing ([onPark]), because a lit room behind an opaque card is heat for
 * nothing and was taking frames from the card.
 *
 * A tap, or Back, goes straight to the way out from wherever it has got to.
 */
@Composable
internal fun IntroOverlay(
    kind: IntroKind,
    handOver: SplashHandOver,
    onPark: (Boolean) -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val measurer = rememberTextMeasurer()

    // "Remove animations" in the phone's accessibility settings: the card is shown whole, held and
    // taken away, with nothing flickering, flying or swelling.
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // Milliseconds into the sequence; negative while the mark is being written by hand.
    val clock = remember { mutableFloatStateOf(if (handOver.markShown) 0f else -WRITE_MS) }
    val exitAt = remember {
        mutableFloatStateOf(if (kind == IntroKind.FULL) IntroTimeline.EXIT_AT else IntroTimeline.QUICK_EXIT_AT)
    }
    // The whole card's opacity: only the still version uses it.
    val card = remember { mutableFloatStateOf(1f) }
    // How bright the system's glow was when this took over from it.
    val glowFrom = remember { mutableFloatStateOf(1f) }
    var started by remember { mutableStateOf(false) }
    val park by rememberUpdatedState(onPark)
    val finished by rememberUpdatedState(onFinished)

    fun skip() {
        if (started) exitAt.floatValue = min(exitAt.floatValue, max(clock.floatValue, 0f))
        else exitAt.floatValue = 0f
    }

    LaunchedEffect(Unit) {
        // Under the system's splash until it is in hand — or for a few frames, on a launch the
        // system drew no splash for at all. Frames as well as time: a main thread busy building
        // the room draws none, and must not be mistaken for a splash that is not coming.
        var frames = 0
        val first = withFrameNanos { it }
        var now = first
        while (handOver.waiting && !handOver.claimed &&
            (frames < HAND_OVER_WAIT_FRAMES || now - first < HAND_OVER_WAIT_NS)
        ) {
            now = withFrameNanos { it }
            frames++
        }
        if (handOver.waiting && !handOver.claimed) {
            handOver.waiting = false
            handOver.markShown = false
        }

        // The room builds under the card, and the card stays as it is until that is done. On a
        // phone with a splash of the system's own, that splash is still what is on the screen:
        // its mark is drawn off the main thread, so its glow goes on breathing however busy the
        // room keeps this one.
        awaitCalm(view.display?.refreshRate ?: 60f)
        while (handOver.waiting && android.os.SystemClock.uptimeMillis() < handOver.writtenAt) {
            withFrameNanos { }
        }
        if (handOver.waiting) {
            // **In two steps, a few frames apart, or the screen goes black for one frame.** The
            // system draws its animated mark on a surface of its own, and removing the splash
            // lets go of that surface at once while the window still shows the splash's black
            // ground until its next frame: measured from a capture as exactly one frame with no
            // mark in it. So the splash is made see-through first — this card's mark, drawn in
            // the same place, is then what the window shows — and only taken away after that,
            // when there is nothing of it left to miss.
            if (handOver.startedAt > 0L) {
                glowFrom.floatValue = IntroTimeline.splashGlow(
                    (android.os.SystemClock.uptimeMillis() - handOver.startedAt).toFloat()
                )
            }
            handOver.uncover?.invoke()
            repeat(HAND_OVER_FRAMES) { withFrameNanos { } }
            handOver.release?.invoke()
            repeat(HAND_OVER_FRAMES) { withFrameNanos { } }
            handOver.waiting = false
        }
        if (!handOver.markShown) clock.floatValue = -WRITE_MS

        if (still) {
            clock.floatValue = IntroTimeline.EXIT_AT
            exitAt.floatValue = Float.MAX_VALUE
            started = true
            kotlinx.coroutines.delay(STILL_HOLD_MS)
            val from = withFrameNanos { it }
            while (card.floatValue > 0f) {
                card.floatValue = 1f - ((withFrameNanos { it } - from) / 1e6f / STILL_FADE_MS).coerceIn(0f, 1f)
            }
            finished()
            return@LaunchedEffect
        }

        started = true
        var parked = false
        val origin = withFrameNanos { it } - (clock.floatValue * 1e6f).toLong()
        while (true) {
            val t = (withFrameNanos { it } - origin) / 1e6f
            clock.floatValue = t
            // The room rests while the card is whole, and is drawing again in time for the hole.
            val wantParked = t > 0f && t < exitAt.floatValue - IntroTimeline.UNCOVER_LEAD_MS
            if (wantParked != parked) {
                parked = wantParked
                park(parked)
            }
            if (IntroTimeline.exit(t, exitAt.floatValue) >= 1f) break
        }
        if (parked) park(false)
        finished()
    }

    BackHandler { skip() }

    // The card's lettering, set in dp: it is artwork, laid out round the mark, and a phone set to
    // very large text must not push the name off the card.
    val fit = with(density) {
        (context.resources.displayMetrics.widthPixels / CARD_WIDTH_DP.dp.toPx()).coerceIn(0.82f, 1.3f)
    }
    val nameStyle = remember(density, fit) {
        TextStyle(
            fontFamily = ArchivoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = with(density) { (NAME_DP * fit).dp.toSp() },
            color = CREAM,
            // Tight and warm: what clings to the strokes. The spread is the wash drawn behind.
            shadow = Shadow(color = AMBER.copy(alpha = 0.55f), offset = Offset.Zero, blurRadius = 15f)
        )
    }
    val letters = remember(nameStyle) { NAME.map { measurer.measure(it.toString(), nameStyle) } }
    val byline = remember(density, fit) {
        measurer.measure(
            BYLINE,
            TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = with(density) { (BYLINE_DP * fit).dp.toSp() },
                letterSpacing = with(density) { (BYLINE_TRACK_DP * fit).dp.toSp() },
                color = AMBER,
                shadow = Shadow(color = AMBER.copy(alpha = 0.75f), offset = Offset.Zero, blurRadius = 10f)
            )
        )
    }
    val jPath = remember { Path() }
    val jPart = remember { Path() }
    val ground = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    val measure = remember { PathMeasure() }
    val point = remember { FloatArray(2) }

    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = card.floatValue }
            .semantics { contentDescription = "Jawon" }
            // No ripple and no indication: this is a title card, not a button, and it ends
            // without acknowledging that it was tapped.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Skip the intro"
            ) { skip() }
    ) {
        val dp = density.density
        val t = clock.floatValue
        val leaveAt = exitAt.floatValue
        val beat = IntroTimeline.beat(t, leaveAt)
        val exit = if (still) 0f else IntroTimeline.exit(t, leaveAt)
        val full = kind == IntroKind.FULL

        // Where the mark is: from where the system drew it to where it rests above the name.
        val drawn = handOver.rect
        val fromSize = drawn?.width?.times(SPLASH_ICON_OVERDRAW) ?: (SPLASH_MARK_DP * dp)
        val fromCentre = drawn?.center ?: Offset(size.width / 2f, size.height / 2f)
        val restSize = REST_MARK_DP * dp * fit
        val restCentre = Offset(size.width / 2f, size.height * REST_MARK_Y)
        val rise = if (full) IntroTimeline.rise(beat) else 0f
        val centre = Offset(
            fromCentre.x + (restCentre.x - fromCentre.x) * rise,
            fromCentre.y + (restCentre.y - fromCentre.y) * rise
        )
        val unit = (fromSize + (restSize - fromSize) * rise) / MarkPath.VIEWPORT

        // The way out, about the hole.
        val corner = max(
            max(hypot(centre.x, centre.y), hypot(size.width - centre.x, centre.y)),
            max(hypot(centre.x, size.height - centre.y), hypot(size.width - centre.x, size.height - centre.y))
        )
        val end = corner * 1.06f / (MarkPath.HOLE_RADIUS * unit)
        val zoom = IntroTimeline.zoom(exit, end)
        val open = IntroTimeline.open(exit)
        val u = unit * zoom
        val window = MarkPath.HOLE_RADIUS * u * open

        // The ground: black, with the hole open in it once the way out has begun. One path filled
        // even-odd rather than a layer cleared with a blend mode — nothing here needs compositing
        // off screen, over a room that is drawing again underneath.
        if (window > 0f) {
            ground.rewind()
            ground.fillType = PathFillType.EvenOdd
            ground.addRect(Rect(Offset.Zero, size))
            ground.addOval(Rect(centre, window))
            drawPath(ground, Color.Black)
        } else {
            drawRect(Color.Black)
        }

        // Until the system's splash is in hand nothing is drawn: on a launch it drew no mark for,
        // this one must not show whole and then be written. Once it is in hand the mark is drawn
        // under it, in its place, so that taking the splash away changes nothing on the screen.
        if (handOver.waiting && !handOver.claimed) return@Canvas

        // Written by hand, when the system showed no mark of its own: the splash's own half-second
        // round the rim, down the J, and the hole landing last.
        val write = if (t < 0f) 1f + t / WRITE_MS else 1f
        val rimDrawn = (write / 0.5f).coerceIn(0f, 1f).let { it * it }
        val jDrawn = IntroTimeline.easeOut(((write - 0.5f) / 0.34f).coerceIn(0f, 1f))
        val holeDrawn = ((write - 0.66f) / 0.34f).coerceIn(0f, 1f).let { x ->
            if (write >= 1f) 1f else IntroTimeline.easeOut(x) * (1f + 0.25f * (1f - x))
        }
        val glowDrawn = IntroTimeline.easeOut(((write - 0.2f) / 0.8f).coerceIn(0f, 1f))

        val level = if (still) 1f else IntroTimeline.flicker(beat)
        val strokes = level * IntroTimeline.strokesOut(exit)
        val bloom = (if (still) 1.4f else IntroTimeline.bloom(beat)) + 0.7f * kotlin.math.sin(Math.PI.toFloat() * (exit / 0.34f).coerceIn(0f, 1f))

        // The light on the wall behind the sign: wider and stronger as the tube swells, never a
        // disc with an edge.
        val glowRadius = MarkPath.GLOW_RADIUS * u * (1f + 0.42f * (bloom - 1f))
        val glow = (bloom * level * glowDrawn * IntroTimeline.strokesOut(exit) *
            IntroTimeline.glowSettle(glowFrom.floatValue, beat)).coerceAtLeast(0f)
        if (glow > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to AMBER.copy(alpha = (0.18f * glow).coerceAtMost(0.6f)),
                    0.62f to AMBER.copy(alpha = (0.12f * glow).coerceAtMost(0.4f)),
                    0.84f to AMBER.copy(alpha = (0.08f * glow).coerceAtMost(0.3f)),
                    1f to Color.Transparent,
                    center = centre,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = centre
            )
        }

        // The J's line, in this frame's place and size.
        jPath.rewind()
        val hook = MarkPath.HOOK_RADIUS * u
        val stemX = centre.x + (MarkPath.STEM_X - MarkPath.CENTRE) * u
        jPath.moveTo(stemX, centre.y + (MarkPath.STEM_TOP - MarkPath.CENTRE) * u)
        jPath.lineTo(stemX, centre.y)
        jPath.arcTo(
            rect = Rect(centre, hook),
            startAngleDegrees = 0f,
            sweepAngleDegrees = MarkPath.HOOK_SWEEP_DEG,
            forceMoveTo = false
        )
        val j = if (jDrawn >= 1f) jPath else {
            measure.setPath(jPath, false)
            jPart.rewind()
            measure.getSegment(0f, measure.length * jDrawn, jPart, true)
            jPart
        }
        val rimRadius = MarkPath.RIM_RADIUS * u
        val rimBox = Rect(centre, rimRadius)

        fun rim(color: Color, alpha: Float, width: Float) {
            if (alpha <= 0f || rimDrawn <= 0f) return
            if (rimDrawn >= 1f) {
                drawCircle(color, rimRadius, centre, alpha, Stroke(width * u))
            } else {
                drawArc(
                    color = color,
                    startAngle = MarkPath.RIM_START_DEG,
                    sweepAngle = 360f * rimDrawn,
                    useCenter = false,
                    topLeft = rimBox.topLeft,
                    size = rimBox.size,
                    alpha = alpha,
                    style = Stroke(width * u)
                )
            }
        }

        fun letterJ(color: Color, alpha: Float, width: Float) {
            if (alpha <= 0f || jDrawn <= 0f) return
            drawPath(j, color, alpha, Stroke(width * u, cap = StrokeCap.Round))
        }

        // The tube, as the splash draws it: a soft halo, the lit glass, a hot core.
        rim(AMBER, 0.22f * strokes, MarkPath.RIM_HALO)
        letterJ(AMBER, 0.22f * strokes, MarkPath.J_HALO)
        rim(AMBER, strokes, MarkPath.RIM_STROKE)
        letterJ(AMBER, strokes, MarkPath.J_STROKE)
        rim(CORE, strokes, MarkPath.RIM_CORE)
        letterJ(CORE, strokes, MarkPath.J_CORE)

        // The light's lap: a hot stretch running round the rim and down the J, fading behind.
        if (full && !still) {
            val head = IntroTimeline.lapHead(beat)
            val tail = IntroTimeline.lapTail(beat)
            if (head > tail && strokes > 0f) {
                val steps = LAP_STEPS
                MarkPath.point(tail, point)
                var px = centre.x + (point[0] - MarkPath.CENTRE) * u
                var py = centre.y + (point[1] - MarkPath.CENTRE) * u
                for (i in 1..steps) {
                    val f = i / steps.toFloat()
                    val s = tail + (head - tail) * f
                    MarkPath.point(s, point)
                    val x = centre.x + (point[0] - MarkPath.CENTRE) * u
                    val y = centre.y + (point[1] - MarkPath.CENTRE) * u
                    val onJ = MarkPath.onJ(s)
                    // The rim's line ends above the stem and the J's begins a little below it: the
                    // light crosses that gap without drawing a chord across it.
                    if (hypot(x - px, y - py) < 4f * u) {
                        val a = f * f * strokes
                        val w = (if (onJ) MarkPath.J_CORE * 1.5f else MarkPath.RIM_CORE * 2.1f) * u
                        drawLine(AMBER_HOT, Offset(px, py), Offset(x, y), w * 2.6f, StrokeCap.Butt, alpha = a * 0.28f)
                        drawLine(WHITE_HOT, Offset(px, py), Offset(x, y), w, StrokeCap.Butt, alpha = a)
                    }
                    px = x
                    py = y
                }
                // Its head: a small bright bead with light round it.
                val bead = (if (MarkPath.onJ(head)) 2.5f else 1.9f) * u
                val shine = strokes * (1f - ((head - 0.985f) / 0.015f).coerceIn(0f, 1f))
                if (shine > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to WHITE_HOT.copy(alpha = 0.55f * shine),
                            1f to Color.Transparent,
                            center = Offset(px, py),
                            radius = bead * 4.2f
                        ),
                        radius = bead * 4.2f,
                        center = Offset(px, py)
                    )
                    drawCircle(WHITE_HOT, bead, Offset(px, py), alpha = shine)
                }
            }
        }

        // The hole: lit, taking the light with a bounce, and then not a dot at all but the way out.
        val pulse = holeDrawn * (if (full && !still) IntroTimeline.holePulse(beat) else 1f)
        val dot = level * (1f - open)
        if (dot > 0f && pulse > 0f) {
            drawCircle(AMBER, MarkPath.HOLE_HALO * u * pulse, centre, alpha = 0.22f * dot)
            drawCircle(AMBER, MarkPath.HOLE_RADIUS * u * pulse, centre, alpha = dot)
            drawCircle(CORE, MarkPath.HOLE_CORE * u * pulse, centre, alpha = dot)
        }
        if (window > 0f) {
            val edge = IntroTimeline.edgeOut(exit) * open
            if (edge > 0f) {
                val width = max(2f * dp, 0.9f * unit * zoom.pow(0.72f))
                drawCircle(AMBER, window + width * 1.2f, centre, alpha = 0.22f * edge, style = Stroke(width * 3.4f))
                drawCircle(AMBER, window + width * 0.5f, centre, alpha = edge, style = Stroke(width))
                drawCircle(CORE, window + width * 0.5f, centre, alpha = edge, style = Stroke(width * 0.34f))
            }
        }

        // The name under it, the rule, and whose it is.
        if (full) {
            val words = if (still) 1f else IntroTimeline.wordsOut(exit)
            if (words > 0f) {
                val sink = (1f - words) * 10f * dp
                val tracking = NAME_TRACK_DP * dp * fit
                var nameWidth = tracking * (letters.size - 1)
                for (glyph in letters) nameWidth += glyph.size.width
                val nameTop = restCentre.y + NAME_BELOW_MARK_DP * dp * fit + sink
                val nameHeight = letters[0].size.height.toFloat()

                // The pool of light the name throws into the air round it: wide, low and without
                // edges — felt as the black not being quite black near the sign.
                var arrived = 0f
                for (i in 0 until IntroTimeline.LETTERS) arrived += IntroTimeline.letter(i, beat) / IntroTimeline.LETTERS
                val wash = (if (still) 1f else arrived) * words
                if (wash > 0f) {
                    val pool = Offset(size.width / 2f, nameTop + nameHeight / 2f)
                    val reach = nameWidth * 0.86f
                    scale(scaleX = 1f, scaleY = 0.56f, pivot = pool) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to AMBER.copy(alpha = 0.20f * wash),
                                1f to Color.Transparent,
                                center = pool,
                                radius = reach
                            ),
                            radius = reach,
                            center = pool
                        )
                    }
                }

                var x = (size.width - nameWidth) / 2f
                letters.forEachIndexed { i, glyph ->
                    val p = if (still) 1f else IntroTimeline.letter(i, beat)
                    if (p > 0f) {
                        drawText(
                            textLayoutResult = glyph,
                            topLeft = Offset(x, nameTop + (1f - p) * LETTER_DROP_DP * dp),
                            alpha = p * words
                        )
                    }
                    x += glyph.size.width + tracking
                }

                val rule = if (still) 1f else IntroTimeline.rule(beat)
                val ruleY = nameTop + nameHeight + RULE_GAP_DP * dp * fit
                if (rule > 0f) {
                    val half = nameWidth * 0.5f * rule
                    drawLine(
                        color = AMBER,
                        start = Offset(size.width / 2f - half, ruleY),
                        end = Offset(size.width / 2f + half, ruleY),
                        strokeWidth = max(1f, dp),
                        alpha = words
                    )
                }

                val credit = if (still) 1f else IntroTimeline.byline(beat)
                if (credit > 0f) {
                    drawText(
                        textLayoutResult = byline,
                        topLeft = Offset(
                            (size.width - byline.size.width) / 2f,
                            ruleY + RULE_GAP_DP * dp * fit
                        ),
                        alpha = credit * words
                    )
                }
            }
        }
    }
}

/**
 * Waits for the frames to run evenly — the room under the card built, its first covers in — or for
 * long enough that waiting any longer would be the worse opening.
 *
 * Read from the frame clock itself: a main thread busy building a scene shows up as the gap between
 * two frames, whatever it is busy with, and no room has to say when it is ready.
 */
private suspend fun awaitCalm(refreshHz: Float) {
    val even = (1000f / refreshHz.coerceIn(30f, 240f)) * 1.6f
    val start = withFrameNanos { it }
    var last = start
    var calm = 0
    while (true) {
        val now = withFrameNanos { it }
        val gap = (now - last) / 1e6f
        last = now
        calm = if (gap <= even) calm + 1 else 0
        val waited = (now - start) / 1e6f
        if (waited >= CALM_MAX_MS || (waited >= CALM_MIN_MS && calm >= CALM_FRAMES)) return
    }
}

// ---- The brand's own colours, not the room's ------------------------------------------------
//
// Fixed, where every other flat screen takes its accent from the room's mode: this continues the
// system's splash, which is drawn from resources before any mode is known, and the icon on the
// home screen is amber whatever light the CD room is in.

private val AMBER = Color(0xFFFFB43C)
private val AMBER_HOT = Color(0xFFFFD88A)
private val CORE = Color(0xFFFFEBC7)
private val WHITE_HOT = Color(0xFFFFF8EA)
private val CREAM = Color(0xFFF3E9D8)

private const val NAME = "JAWON"
private const val BYLINE = "BY VENEFICUS"

/**
 * The mark's box where nothing has measured it: what the window's background draws below Android
 * 12 (`splash_static.xml`), which is the size Android 12's own splash draws it at.
 */
private const val SPLASH_MARK_DP = 288f

/**
 * How much larger than its view the system's splash draws the mark.
 *
 * A splash icon with no background is a 288dp drawing shown through a 192dp view — the platform's
 * own rule, the reason the artwork keeps inside its middle two thirds — so the view's bounds
 * understate the mark by exactly this. Found by measuring: drawn at the view's size, this mark was
 * two thirds of the one it was taking over from, and the hand-over was a jump.
 */
private const val SPLASH_ICON_OVERDRAW = 288f / 192f

/** The screen width the card's sizes are written for; other phones scale it. */
private const val CARD_WIDTH_DP = 392f

/** The mark at rest above the name: its box, and how far down the screen its hole sits. */
private const val REST_MARK_DP = 216f
private const val REST_MARK_Y = 0.40f

private const val NAME_DP = 50f
private const val NAME_TRACK_DP = 8f
private const val NAME_BELOW_MARK_DP = 92f
private const val LETTER_DROP_DP = 16f
private const val RULE_GAP_DP = 18f
private const val BYLINE_DP = 12f
private const val BYLINE_TRACK_DP = 4f

/** How many short lines the light's hot stretch is drawn as. */
private const val LAP_STEPS = 44

/** Writing the mark by hand, on a launch the system drew none for: the splash's own 900ms. */
private const val WRITE_MS = 900f

/** How long to wait for the system's splash to be handed over before assuming there was none. */
private const val HAND_OVER_WAIT_NS = 300_000_000L
private const val HAND_OVER_WAIT_FRAMES = 12

/** Frames between making the system's splash see-through, removing it, and starting to move. */
private const val HAND_OVER_FRAMES = 3

/** The card holds the lit mark at least this long, and no longer than this, before it moves. */
private const val CALM_MIN_MS = 140f
private const val CALM_MAX_MS = 2400f
private const val CALM_FRAMES = 6

/** With animations removed: how long the whole card is shown, and how quickly it goes. */
private const val STILL_HOLD_MS = 1300L
private const val STILL_FADE_MS = 240f
