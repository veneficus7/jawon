package com.jawon.app.ui.wallscene

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import com.jawon.app.data.playback.NativeEffect
import com.jawon.app.playback.DjFilter
import com.jawon.app.playback.Drive
import com.jawon.app.playback.RackChannel
import com.jawon.app.playback.Varispeed
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber

/**
 * One channel of the rack's face, as the face needs to draw it.
 *
 * @param bipolar whether the fader rests in the middle (a filter, a tempo) or on the floor (an
 *   amount). It decides where neutral is, which decides both where the fill is drawn from and
 *   whether the channel counts as doing anything.
 */
data class RackChannelUi(
    val channel: RackChannel,
    val label: String,
    val value: Float,
    val bipolar: Boolean,
    val onChange: (Float) -> Unit,
    val readout: (Float) -> String,
    val available: Boolean = true
)

/**
 * The rack's face: six pads over six faders.
 *
 * **The pad says whether, the fader says how much,** and keeping those two questions on separate
 * controls is the whole idea. A fader alone can only reach zero by being dragged there, which
 * takes a second and loses your setting on the way; a pad drops the effect in and out instantly
 * and lands back on exactly the amount you had. That is the difference between an effect you
 * *set* and one you *play*, and this rack is meant to be played.
 *
 * Everything is on the face at once, with nothing selected and nothing to open, because the
 * moves this exists for use two hands: sweeping the filter while pushing the echo's feedback is
 * one gesture with two thumbs here. A layout that showed one module at a time — which is the
 * more obviously handsome way to fit sixteen controls on a phone — cannot express that at all.
 *
 * Six is the ceiling, and it is set by thumbs rather than taste: across a phone that is about
 * 52dp a channel, and a seventh would put every one of them under the smallest target a thumb
 * can reliably hit. Everything the rack can do beyond these six is something you set rather than
 * something you play, and it lives in the drawer.
 */
@Composable
fun RackFace(
    channels: List<RackChannelUi>,
    engaged: Set<RackChannel>,
    onToggle: (RackChannel) -> Unit,
    modifier: Modifier = Modifier,
    faderHeight: Int = FADER_HEIGHT_DP
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CHANNEL_GAP_DP.dp)
    ) {
        channels.forEach { ch ->
            Channel(
                ui = ch,
                engaged = ch.channel in engaged,
                onToggle = { onToggle(ch.channel) },
                faderHeight = faderHeight,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Channel(
    ui: RackChannelUi,
    engaged: Boolean,
    onToggle: () -> Unit,
    faderHeight: Int,
    modifier: Modifier = Modifier
) {
    // "Doing something" is not the same as "in circuit". A channel can be switched in and still
    // be inaudible because its fader is sitting at neutral, and the lamp reports the second
    // question — which is the one you actually want answered at a glance while playing.
    val neutral = if (ui.bipolar) NEUTRAL_BIPOLAR else 0f
    val working = engaged && kotlin.math.abs(ui.value - neutral) > NEUTRAL_SLOP

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Pad(
            label = ui.label,
            engaged = engaged,
            working = working,
            enabled = ui.available,
            onToggle = onToggle
        )

        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(faderHeight.dp)
                // A bypassed channel dims rather than disappearing: where its fader is sitting
                // is exactly what you need to see to decide whether to throw it back in.
                .alpha(if (ui.available) (if (engaged) 1f else BYPASSED_ALPHA) else UNAVAILABLE_ALPHA)
        ) {
            Fader(
                value = ui.value,
                bipolar = ui.bipolar,
                working = working,
                onChange = ui.onChange,
                enabled = ui.available
            )
        }

        Text(
            text = if (ui.available) ui.readout(ui.value) else "—",
            style = JawonTextStyle.mono.copy(
                color = if (working) LampAmber else LabelDim,
                fontSize = 8.sp
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
        )
    }
}

/**
 * The switch half of a channel: tap to put the effect in or take it out.
 *
 * Deliberately not a toggle that looks like a checkbox. It is the channel's name plate, so the
 * label doubles as the control and no width is spent on a separate switch — on a face this
 * narrow, a control that costs no room is the only kind there is space for.
 */
@Composable
private fun Pad(
    label: String,
    engaged: Boolean,
    working: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PAD_HEIGHT_DP.dp)
            .background(if (engaged) CrateRaised else Color.Transparent, RoundedCornerShape(2.dp))
            .border(
                width = 1.dp,
                color = if (engaged) LabelDim else CrateEdge,
                shape = RoundedCornerShape(2.dp)
            )
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            // A pad is a tap, but it sits directly above a fader people drag.
                            // Claiming only the tap and letting a drag fall through would put
                            // two handlers on one gesture, which is the mistake that made the
                            // disc's release fight its own scratch. So the pad owns everything
                            // that starts on it, and acts on the press.
                            onDragStart = { onToggle() }
                        ) { _, _ -> }
                    }
                } else {
                    Modifier
                }
            )
            .alpha(if (enabled) 1f else UNAVAILABLE_ALPHA),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(if (working) LampAmber else CrateEdge, CircleShape)
            )
            Text(
                text = label,
                style = JawonTextStyle.mono.copy(
                    color = if (engaged) LabelCream else LabelDim,
                    fontSize = 8.sp,
                    letterSpacing = 0.4.sp
                ),
                maxLines = 1,
                modifier = Modifier.padding(start = 3.dp)
            )
        }
    }
}

/**
 * The amount half: a vertical throw, because six of them fit side by side and six horizontal
 * ones do not.
 */
@Composable
private fun Fader(
    value: Float,
    bipolar: Boolean,
    working: Boolean,
    onChange: (Float) -> Unit,
    enabled: Boolean
) {
    var height by remember { mutableFloatStateOf(1f) }
    // Up is negative on screen and more here, hence the subtraction.
    val fromY = { y: Float -> (1f - (y / height)).coerceIn(0f, 1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateRaised, RoundedCornerShape(2.dp))
            .border(1.dp, CrateEdge, RoundedCornerShape(2.dp))
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { onChange(fromY(it.y)) }
                        ) { change, _ -> onChange(fromY(change.position.y)) }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        val v = value.coerceIn(0f, 1f)

        // A bipolar fader fills out from its centre line, a unipolar one up from the floor —
        // so a filter reads as "how far from off", not "how far from the bottom".
        if (bipolar) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CrateEdge)
            )
        }

        Box(
            Modifier
                .align(BiasAlignment(horizontalBias = 0f, verticalBias = 1f - v * 2f))
                .fillMaxWidth()
                .height(CAP_HEIGHT_DP.dp)
                .padding(horizontal = 2.dp)
                .background(
                    if (working) LampAmber else LabelDim,
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

/**
 * The six channels, wired to the values the rest of the app already keeps.
 *
 * A translation layer and nothing more. Two of the six are bipolar and live on −1…+1, the face
 * works entirely in 0…1, and the readouts have about five characters of room each — so this is
 * where those three facts are reconciled, once, instead of at six call sites.
 */
fun rackChannels(
    filter: Float,
    speed: Float,
    drive: Float,
    echoMix: Float,
    bass: Float,
    width: Float,
    available: Set<NativeEffect>,
    onFilterChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDriveChange: (Float) -> Unit,
    onEchoMix: (Float) -> Unit,
    onBassChange: (Float) -> Unit,
    onWidthChange: (Float) -> Unit
): List<RackChannelUi> = listOf(
    RackChannelUi(
        channel = RackChannel.FILTER,
        label = "FILT",
        value = toUnit(filter),
        bipolar = true,
        onChange = { onFilterChange(toBipolar(it)) },
        readout = { v ->
            val p = toBipolar(v)
            when {
                p < -DjFilter.DEAD_ZONE -> "LP " + pct(-p)
                p > DjFilter.DEAD_ZONE -> "HP " + pct(p)
                else -> "OFF"
            }
        }
    ),
    RackChannelUi(
        channel = RackChannel.SPEED,
        label = "SPD",
        value = toUnit(speed),
        bipolar = true,
        onChange = { onSpeedChange(toBipolar(it)) },
        readout = { v -> twoDp(Varispeed.speedFor(toBipolar(v))) + "x" }
    ),
    RackChannelUi(
        channel = RackChannel.DRIVE,
        label = "DRV",
        value = drive,
        bipolar = false,
        onChange = onDriveChange,
        readout = { v -> oneDp(v * Drive.MAX_DRIVE) }
    ),
    RackChannelUi(
        channel = RackChannel.ECHO,
        label = "ECHO",
        value = echoMix,
        bipolar = false,
        onChange = onEchoMix,
        readout = { v -> pct(v) + "%" }
    ),
    RackChannelUi(
        channel = RackChannel.BASS,
        label = "BASS",
        value = bass,
        bipolar = false,
        onChange = onBassChange,
        readout = { v -> pct(v) + "%" },
        available = NativeEffect.BASS in available
    ),
    RackChannelUi(
        channel = RackChannel.WIDTH,
        label = "WIDE",
        value = width,
        bipolar = false,
        onChange = onWidthChange,
        readout = { v -> pct(v) + "%" },
        available = NativeEffect.WIDTH in available
    )
)

/** −1…+1 as the fader's 0…1, and back. */
private fun toUnit(bipolar: Float): Float = ((bipolar + 1f) / 2f).coerceIn(0f, 1f)

private fun toBipolar(unit: Float): Float = (unit * 2f - 1f).coerceIn(-1f, 1f)

private fun pct(v: Float): String = (v.coerceIn(0f, 1f) * 100f).roundToInt().toString()

private fun oneDp(v: Float): String = String.format(Locale.US, "%.1f", v)

private fun twoDp(v: Float): String = String.format(Locale.US, "%.2f", v)

/** How tall a fader is by default — enough throw to be playable, short enough for the panel. */
const val FADER_HEIGHT_DP = 104

private const val PAD_HEIGHT_DP = 22
private const val CAP_HEIGHT_DP = 10
private const val CHANNEL_GAP_DP = 5

/** Where a bipolar fader rests. */
private const val NEUTRAL_BIPOLAR = 0.5f

/** How far from neutral counts as doing something, for the lamp. */
private const val NEUTRAL_SLOP = 0.02f

private const val BYPASSED_ALPHA = 0.35f
private const val UNAVAILABLE_ALPHA = 0.2f
