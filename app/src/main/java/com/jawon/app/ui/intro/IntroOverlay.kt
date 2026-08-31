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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
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
            .background(CrateBlack)
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
