package com.jawon.app.ui.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LampAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The title card, over the room while it builds.
 *
 * **This is covering real work, not padding time.** The room is a Filament scene: an IBL
 * cubemap is generated and prefiltered, the models load, and the first albums' artwork is
 * baked — none of which is instant, and all of which used to happen behind a static icon the
 * platform painted. So the seconds exist either way; this is what they look like.
 *
 * Which is also the constraint on how long it can be. An intro that outlasts the loading is an
 * intro that stands between someone and their music every single launch, so it is short, it
 * never blocks input, and a tap anywhere ends it immediately.
 *
 * The order is deliberate: the name arrives first and alone, the rule draws under it, and the
 * byline follows once there is something to be credited for. Then the whole card lifts away
 * rather than cutting, so the room is revealed rather than switched to.
 */
@Composable
fun IntroOverlay(onFinished: () -> Unit) {
    val wordmark = remember { Animatable(0f) }
    val rule = remember { Animatable(0f) }
    val byline = remember { Animatable(0f) }
    val card = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // Guarded so a tap during the exit does not start a second one, which would restart the
    // fade from wherever it had got to and look like a stutter.
    var leaving = remember { booleanArrayOf(false) }

    suspend fun leave() {
        if (leaving[0]) return
        leaving[0] = true
        card.animateTo(0f, tween(EXIT_MS, easing = FastOutSlowInEasing))
        onFinished()
    }

    LaunchedEffect(Unit) {
        wordmark.animateTo(1f, tween(NAME_MS, easing = FastOutSlowInEasing))
        rule.animateTo(1f, tween(RULE_MS, easing = FastOutSlowInEasing))

        // **The credit strikes rather than fades.**
        //
        // A linear fade is the one motion that says nothing — it is what a thing does when
        // nobody decided what it should do. This is a room lit by a lamp, and the honest
        // gesture for a small amber line appearing under a sign is the way a tube actually
        // comes on: it catches, drops out, catches harder, gutters once, and then holds.
        //
        // The values and durations matter more than the idea. The stutters are short enough to
        // read as electrical rather than as a bug (a dropout you can count is a fault; one you
        // can only feel is character), the final rise is long enough to settle rather than
        // snap, and the whole sequence lands within a few milliseconds of the fade it replaces
        // so the card is not on screen any longer than it was.
        for ((level, ms) in STRIKE) {
            byline.animateTo(level, tween(ms, easing = LinearEasing))
        }

        delay(HOLD_MS)
        leave()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = card.value }
            // **Pure black, not the crate's black.**
            //
            // Everywhere else in the app a near-black is the right answer — it keeps a surface
            // reading as a lit object in a room rather than as a hole. The title card is the one
            // place that logic reverses: there is no room yet, nothing is lit, and on this OLED
            // panel true black is not a colour at all but pixels that never come on. The
            // wordmark then appears to be floating in front of the phone rather than printed on
            // it, and the bloom below has something to bloom into.
            .background(Color.Black)
            // No ripple and no indication: this is a title card, not a button, and it should
            // end without acknowledging that it was tapped.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { scope.launch { leave() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // **Light in the air, then light on the glyphs — two things, not one.**
            //
            // The first attempt was a single shadow at a large radius and high alpha, and it
            // looked cheap for a reason worth writing down: one blurred copy of a word is a
            // *smear* of that word, and at any strength high enough to read as glow it reads as
            // the letters being out of focus. Real signage does not work like that. There is a
            // pool of light thrown into the air around the sign, which has no shape at all, and
            // a much tighter halo clinging to the strokes themselves.
            //
            // So: a radial wash behind the text carrying the spread, and a small tight shadow on
            // the text carrying the edge. Exactly the pairing the patchbay sockets use, and the
            // same one the case underlights use on the wall — the wash is shapeless and wide,
            // the core is bright and close.
            //
            // Still no stacked copies of the text. That is the ghosting trap in ENGINEERING.md, and
            // it is avoided here by there being nothing to misalign.
            // Read out here rather than inside the draw: the palette colours are composable
            // properties now that a mode owns them, and a draw lambda is not a composition.
            val lamp = LampAmber

            Box(contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .size(WASH_W.dp, WASH_H.dp)
                        .graphicsLayer { alpha = wordmark.value }
                ) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                lamp.copy(alpha = WASH_ALPHA),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension * WASH_SPREAD
                        ),
                        radius = size.minDimension * WASH_SPREAD,
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }

                Text(
                    text = "JAWON",
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 52.sp,
                    letterSpacing = 6.sp,
                    color = LabelCream,
                    // Tight and warm rather than wide and washy. The spread is the Canvas
                    // above; this is only what clings to the strokes.
                    style = TextStyle(
                        shadow = Shadow(
                            color = LampAmber.copy(
                                alpha = WORDMARK_BLOOM_ALPHA * wordmark.value
                            ),
                            offset = Offset.Zero,
                            blurRadius = WORDMARK_BLOOM_RADIUS
                        )
                    ),
                    modifier = Modifier.graphicsLayer {
                        alpha = wordmark.value
                        // Settles down onto the line rather than appearing on it. Small enough
                        // to read as weight rather than as a zoom.
                        translationY = (1f - wordmark.value) * 18f
                    }
                )
            }

            // Drawn out from the centre, the width of the wordmark.
            Box(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth(0.52f * rule.value)
                    .height(1.dp)
                    .background(LampAmber)
            )

            Text(
                text = "BY VENEFICUS",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                // **Fixed, and it must not be animated from the strike.**
                //
                // This was tracked open as the byline came up, reading `byline.value` — which is
                // the flicker. So the letter-spacing followed the gutter: 0.85, 0.10, 1.0, 0.35,
                // 1.0, and the line visibly snapped wider and narrower on every step, jumping
                // its own width four times in a third of a second. That is the bug, and it is a
                // good argument for keeping a flicker to the one property that can carry it.
                //
                // Alpha can flicker because a light that is out is simply not there. Layout
                // cannot: geometry moving in steps is a fault, never an effect.
                letterSpacing = BYLINE_TRACK,
                color = LampAmber,
                // The glow rides the strike, so the gutter is visible in the light around the
                // letters and not only in the letters themselves.
                style = TextStyle(
                    shadow = Shadow(
                        color = LampAmber.copy(alpha = BYLINE_GLOW_ALPHA * byline.value),
                        offset = Offset.Zero,
                        blurRadius = BYLINE_GLOW_RADIUS
                    )
                ),
                modifier = Modifier
                    .padding(top = 18.dp)
                    .alpha(byline.value)
            )
        }
    }
}

/** The name, alone, before anything else happens. */
private const val NAME_MS = 620

/** The rule drawing out under it. */
private const val RULE_MS = 420

/**
 * The credit striking on, as level-and-duration pairs run in order.
 *
 * Totals 330ms, within a few milliseconds of the plain fade it replaces, so the card is on
 * screen for exactly as long as it was. Two dropouts rather than one — a single flicker reads as
 * a dropped frame, two read as a tube — and the last rise is by far the longest so it settles
 * into place instead of arriving at it.
 */
private val STRIKE = listOf(
    0.85f to 55,
    0.10f to 35,
    1.00f to 55,
    0.35f to 30,
    1.00f to 155
)

/**
 * How long the finished card sits before it lifts.
 *
 * Short on purpose. Everything before this is motion, which reads as progress; a still card is
 * the part that reads as waiting, and it is the first thing that would annoy someone on their
 * fifth launch of the day.
 */
private const val HOLD_MS = 520L

private const val EXIT_MS = 520

/**
 * The halo clinging to the strokes — tight, because the spread is the wash's job.
 *
 * The first version ran this at 34px and 0.85 alpha with nothing behind it, and a blurred copy
 * of a word at that strength does not read as light, it reads as the word being out of focus.
 * Small and warm here; wide and shapeless below.
 */
private const val WORDMARK_BLOOM_RADIUS = 15f
private const val WORDMARK_BLOOM_ALPHA = 0.55f

/**
 * The pool of light the name throws into the air around it.
 *
 * Wider than the word and taller than it needs to be, because light in the air has no edges and
 * anything that stops where the text stops is a rectangle of glow rather than a glow. The alpha
 * is low on purpose: this is meant to be felt as the black not being quite black near the sign,
 * not seen as a shape in its own right.
 */
private const val WASH_W = 300
private const val WASH_H = 190
private const val WASH_SPREAD = 0.62f
private const val WASH_ALPHA = 0.22f

/** The byline's own glow, and how far its tracking opens as it strikes. */
private const val BYLINE_GLOW_RADIUS = 10f
private const val BYLINE_GLOW_ALPHA = 0.75f
private val BYLINE_TRACK = 4.sp
