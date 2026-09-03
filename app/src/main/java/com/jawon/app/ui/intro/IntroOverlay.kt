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
import androidx.compose.ui.graphics.Color
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
        byline.animateTo(1f, tween(BYLINE_MS, easing = LinearEasing))
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
            Text(
                text = "JAWON",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 52.sp,
                letterSpacing = 6.sp,
                color = LabelCream,
                // **The name comes on like the underlight does.**
                //
                // A shadow at zero offset, which is this project's one reliable way to bloom
                // text: drawing blurred copies behind a crisp one is the obvious approach and
                // it is what produced the doubled, smeared wordmark in the deck window, because
                // any copy that fails to line up exactly reads as a second word rather than as
                // light. A shadow cannot drift, because it is the same glyphs.
                //
                // The radius grows with the entry rather than sitting at full strength, so the
                // name does not merely fade up — it warms up, the way a sign does. Recomposing
                // a single line of text for the six hundred milliseconds this runs is not a
                // cost worth avoiding; the card is gone before anything else has started.
                style = TextStyle(
                    shadow = Shadow(
                        color = LampAmber.copy(alpha = WORDMARK_BLOOM_ALPHA * wordmark.value),
                        offset = Offset.Zero,
                        blurRadius = WORDMARK_BLOOM_RADIUS * wordmark.value
                    )
                ),
                modifier = Modifier.graphicsLayer {
                    alpha = wordmark.value
                    // Settles down onto the line rather than appearing on it. Small enough
                    // to read as weight rather than as a zoom.
                    translationY = (1f - wordmark.value) * 18f
                }
            )

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
                letterSpacing = 4.sp,
                color = LampAmber,
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

/** The credit, once there is something to credit. */
private const val BYLINE_MS = 340

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
 * How far the name's light spreads, in pixels, once it is fully up.
 *
 * Generous. This is the one screen with nothing else on it to compete with, and a timid glow on
 * a title card reads as a rendering artefact rather than as a decision.
 */
private const val WORDMARK_BLOOM_RADIUS = 34f
private const val WORDMARK_BLOOM_ALPHA = 0.85f
