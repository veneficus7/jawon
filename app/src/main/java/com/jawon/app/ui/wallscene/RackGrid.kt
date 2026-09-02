package com.jawon.app.ui.wallscene

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.data.playback.NativeEffect
import com.jawon.app.data.playback.NativeEffects
import com.jawon.app.playback.DjFilter
import com.jawon.app.playback.Drive
import com.jawon.app.playback.Echo
import com.jawon.app.playback.RackChannel
import com.jawon.app.playback.Varispeed
import com.jawon.app.ui.theme.DeckFinish
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalDeckFinish
import com.jawon.app.ui.theme.OxideTeal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One knob belonging to a cell — what the faders show when that cell is selected.
 *
 * @param onCommit called when the finger lifts. Only the pitch uses it, and it is the difference
 *   between a working control and a wedged player: see `RackState.pitchApplied`.
 */
data class RackParam(
    val name: String,
    val value: Float,
    val bipolar: Boolean,
    val onChange: (Float) -> Unit,
    val readout: (Float) -> String,
    val onCommit: (() -> Unit)? = null
)

/** One cell of the grid: an effect, its colour, and the one to three knobs it exposes. */
data class RackCell(
    val channel: RackChannel,
    val hue: Color,
    val params: List<RackParam>,
    val available: Boolean = true
)

/**
 * The rack: eight cells you switch in and out, over three faders that follow the selected one.
 *
 * **Effects first, parameters second** — which is the order you use them in when something is
 * already playing. Tapping a cell throws the effect in; the faders underneath then belong to
 * whichever cell is selected, so the echo gets time, feedback and mix at once while the drive
 * gets one fader and two blanks. Those blanks are the honest cost of the layout: half of these
 * effects have a single parameter.
 *
 * Every colour, radius, key shape and typeface comes from [LocalDeckFinish]. That is deliberate
 * and it is why this was worth rebuilding rather than recolouring — the pale-plate finishes
 * invert the whole thing, dark keys on a light face, which no amount of swapping constants on a
 * dark-first design would have reached.
 */
@Composable
fun RackGrid(
    cells: List<RackCell>,
    engaged: Set<RackChannel>,
    selected: RackChannel?,
    onToggle: (RackChannel) -> Unit,
    onSelect: (RackChannel) -> Unit,
    modifier: Modifier = Modifier,
    eqContent: @Composable () -> Unit
) {
    val finish = LocalDeckFinish.current

    // **No readout of its own.** This used to carry a second window saying which cell was
    // selected, directly under the deck's own — two glowing displays a centimetre apart, which
    // read as a mistake rather than as a machine. A real component has one window, so the
    // selection went up into that one and this got the space back.
    Column(modifier = modifier.fillMaxWidth()) {

        // Two rows of four. A 4×2 grid keeps every cell about 74dp wide on a phone, which is
        // comfortably above a thumb's minimum, and it reads as a bank of keys rather than a list.
        cells.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = CELL_GAP.dp),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP.dp)
            ) {
                row.forEach { cell ->
                    Cell(
                        cell = cell,
                        engaged = cell.channel in engaged,
                        selected = cell.channel == selected,
                        onToggle = { onToggle(cell.channel) },
                        onSelect = { onSelect(cell.channel) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // The equaliser cannot ride three faders, so when it is the selected cell it takes the
        // row over entirely rather than pretending to be an effect with three knobs.
        if (selected == RackChannel.EQ) {
            Box(modifier = Modifier.padding(top = 6.dp)) { eqContent() }
        } else {
            val params = cells.firstOrNull { it.channel == selected }?.params.orEmpty()
            // The faders take whatever height is left rather than a fixed slab, so the panel has
            // no dead water under it and the throw is as long as the phone can give — which is
            // the whole difference between a control you can place and one you can only nudge.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(FADER_HEIGHT.dp),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP.dp)
            ) {
                repeat(FADER_SLOTS) { i ->
                    Fader(
                        param = params.getOrNull(i),
                        hue = cells.firstOrNull { it.channel == selected }?.hue ?: finish.accent,
                        live = selected != null && selected in engaged,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}



@Composable
private fun Cell(
    cell: RackCell,
    engaged: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    val shape = RoundedCornerShape(finish.keyRadius)

    Box(
        modifier = modifier
            .aspectRatio(CELL_ASPECT)
            .background(if (engaged) finish.key else Color.Transparent, shape)
            .border(
                width = 1.dp,
                color = if (selected) finish.accent else finish.keyEdge,
                shape = shape
            )
            .then(if (finish.bevelled) Modifier.bevel(engaged) else Modifier)
            .alpha(if (cell.available) 1f else UNAVAILABLE_ALPHA)
            .then(
                if (cell.available) {
                    Modifier.pointerInput(cell.channel, engaged, selected) {
                        detectTapGestures {
                            // Switch it in and select it; tapping the one already selected takes
                            // it out again. One gesture doing two jobs, but the two are never
                            // ambiguous: a cell that is out can only be switched in, and the
                            // selected one is the only cell a second tap can reach.
                            when {
                                !engaged -> { onToggle(); onSelect() }
                                selected -> onToggle()
                                else -> onSelect()
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // The colour bar: how a thumb finds the filter without reading the label.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (engaged) cell.hue.copy(alpha = HUE_ALPHA) else Color.Transparent,
                    RoundedCornerShape(1.dp)
                )
        )
        Text(
            text = cell.channel.label,
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize,
            fontWeight = FontWeight.Bold,
            color = if (engaged) finish.keyInk else finish.keyDim,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Fader(
    param: RackParam?,
    hue: Color,
    live: Boolean,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    val shape = RoundedCornerShape(finish.keyRadius)
    var height by remember { mutableFloatStateOf(1f) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // A slot with no knob behind it is drawn as an outline rather than a filled
                // groove. Three dark slabs sitting under a one-parameter effect read as three
                // broken controls; three faint rectangles read as room the effect does not need.
                .background(if (param == null) ComposeTransparent else finish.groove, shape)
                .border(1.dp, finish.keyEdge, shape)
                .alpha(if (param == null) BLANK_ALPHA else if (live) 1f else BYPASSED_ALPHA)
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .then(
                    if (param != null) {
                        Modifier.pointerInput(param.name) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    param.onChange((1f - it.y / height).coerceIn(0f, 1f))
                                },
                                onDragEnd = { param.onCommit?.invoke() },
                                onDragCancel = { param.onCommit?.invoke() }
                            ) { change, _ ->
                                param.onChange((1f - change.position.y / height).coerceIn(0f, 1f))
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            if (param == null) return@Box
            val v = param.value.coerceIn(0f, 1f)
            val neutral = if (param.bipolar) 0.5f else 0f
            val working = live && kotlin.math.abs(v - neutral) > NEUTRAL_SLOP

            if (param.bipolar) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(finish.keyEdge)
                )
            }
            Box(
                Modifier
                    .align(BiasAlignment(horizontalBias = 0f, verticalBias = 1f - v * 2f))
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
                    .height(CAP_HEIGHT.dp)
                    .background(if (working) hue else finish.cap, RoundedCornerShape(1.dp))
            )
        }

        Text(
            text = param?.let { "${it.name} ${it.readout(it.value)}" } ?: "—",
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize * 1.05f,
            color = if (param == null) finish.plateDim.copy(alpha = 0.4f) else finish.plateInk,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp).fillMaxWidth()
        )
    }
}

/**
 * The hard two-tone edge a 1998 widget used to look raised.
 *
 * White down the top and left, black down the bottom and right, one pixel each and no blur —
 * which is exactly how it was done before anyone could afford a real shadow, and why it reads as
 * that decade instantly.
 */
private fun Modifier.bevel(raised: Boolean): Modifier = drawBehind {
    val light = Color.White.copy(alpha = if (raised) 0.18f else 0.10f)
    val dark = Color.Black.copy(alpha = 0.45f)
    val w = size.width
    val h = size.height
    drawLine(light, Offset(0f, 0f), Offset(w, 0f), 2f)
    drawLine(light, Offset(0f, 0f), Offset(0f, h), 2f)
    drawLine(dark, Offset(0f, h), Offset(w, h), 2f)
    drawLine(dark, Offset(w, 0f), Offset(w, h), 2f)
}

private val ComposeTransparent = Color.Transparent

/**
 * How loud a cell's colour bar is.
 *
 * Eight fully saturated bars across two rows read as a toy rather than an instrument — the room
 * behind this panel is a dark shop with one warm lamp, and eight primaries shouting over it do
 * not belong to it. Pulled well back so the bar tells a thumb where the filter is without being
 * the first thing the eye lands on.
 */
private const val HUE_ALPHA = 0.62f

private const val GLOW_SPREAD = 0.16f
private const val GLOW_ALPHA = 0.30f
private const val CELL_GAP = 5
private const val CELL_ASPECT = 1.3f
private const val FADER_SLOTS = 3
private const val FADER_HEIGHT = 150
private const val CAP_HEIGHT = 9
private const val NEUTRAL_SLOP = 0.02f
private const val BYPASSED_ALPHA = 0.4f
private const val BLANK_ALPHA = 0.13f
private const val UNAVAILABLE_ALPHA = 0.25f
private const val READOUT_SECONDARY = 0.62f

/**
 * The eight cells, wired to the state the rest of the app already keeps.
 *
 * A translation layer and nothing else, but it is where three awkward facts get reconciled once
 * rather than at eight call sites: two controls are bipolar and live on −1…+1 while every fader
 * here works in 0…1; the reverb is a list of rooms pretending to be a continuous knob; and the
 * platform effects may simply not exist on a given phone, in which case their cell is drawn
 * dead rather than left to fail quietly when tapped.
 */
fun rackCells(
    filter: Float,
    speed: Float,
    pitch: Float,
    drive: Float,
    echoTime: Float,
    echoFeedback: Float,
    echoMix: Float,
    reverbPreset: Int,
    loudness: Float,
    bass: Float,
    width: Float,
    available: Set<NativeEffect>,
    onFilter: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onPitchCommit: () -> Unit,
    onDrive: (Float) -> Unit,
    onEchoTime: (Float) -> Unit,
    onEchoFeedback: (Float) -> Unit,
    onEchoMix: (Float) -> Unit,
    onReverb: (Int) -> Unit,
    onLoudness: (Float) -> Unit,
    onBass: (Float) -> Unit,
    onWidth: (Float) -> Unit
): List<RackCell> {
    val rooms = NativeEffects.REVERB_PRESETS
    val lastRoom = (rooms.size - 1).coerceAtLeast(1)

    return listOf(
        RackCell(
            channel = RackChannel.FILTER,
            hue = HUE_FILTER,
            params = listOf(
                RackParam(
                    name = "POS",
                    value = toUnit(filter),
                    bipolar = true,
                    onChange = { onFilter(toBipolar(it)) },
                    readout = { v ->
                        val p = toBipolar(v)
                        when {
                            p < -DjFilter.DEAD_ZONE -> "LP" + pct(-p)
                            p > DjFilter.DEAD_ZONE -> "HP" + pct(p)
                            else -> "OFF"
                        }
                    }
                )
            )
        ),
        RackCell(
            channel = RackChannel.SPEED,
            hue = HUE_SPEED,
            params = listOf(
                RackParam(
                    name = "RATE",
                    value = toUnit(speed),
                    bipolar = true,
                    onChange = { onSpeed(toBipolar(it)) },
                    readout = { twoDp(Varispeed.speedFor(toBipolar(it))) + "x" }
                )
            )
        ),
        RackCell(
            channel = RackChannel.PITCH,
            hue = HUE_PITCH,
            params = listOf(
                RackParam(
                    name = "KEY",
                    value = toUnit(pitch),
                    bipolar = true,
                    onChange = { onPitch(toBipolar(it)) },
                    readout = { v ->
                        val st = (toBipolar(v) * Varispeed.SEMITONES).roundToInt()
                        if (st > 0) "+$st" else st.toString()
                    },
                    // The one control that must not tell the player until the finger lifts.
                    onCommit = onPitchCommit
                )
            )
        ),
        RackCell(
            channel = RackChannel.DRIVE,
            hue = HUE_DRIVE,
            params = listOf(
                RackParam(
                    name = "AMT",
                    value = drive,
                    bipolar = false,
                    onChange = onDrive,
                    readout = { oneDp(it * Drive.MAX_DRIVE) }
                )
            )
        ),
        RackCell(
            channel = RackChannel.ECHO,
            hue = HUE_ECHO,
            params = listOf(
                RackParam("TIME", echoTime, false, onEchoTime, { Echo.timeMsFor(it).roundToInt().toString() }),
                RackParam("FDBK", echoFeedback, false, onEchoFeedback, { pct(it) }),
                RackParam("MIX", echoMix, false, onEchoMix, { pct(it) })
            )
        ),
        RackCell(
            channel = RackChannel.SPACE,
            hue = HUE_SPACE,
            available = NativeEffect.REVERB in available || NativeEffect.LOUDNESS in available,
            params = listOf(
                // A list of rooms, ridden as a knob. Stepped on the way out and rounded on the
                // way in, so the fader always sits exactly on a room rather than between two.
                RackParam(
                    name = "ROOM",
                    value = reverbPreset.toFloat() / lastRoom,
                    bipolar = false,
                    onChange = { onReverb((it * lastRoom).roundToInt()) },
                    readout = { v ->
                        rooms.getOrElse((v * lastRoom).roundToInt()) { rooms.first() }
                            .substringBefore(' ')
                    }
                ),
                RackParam("LOUD", loudness, false, onLoudness, { oneDp(it * MAX_LOUD_DB) })
            )
        ),
        RackCell(
            channel = RackChannel.TONE,
            hue = HUE_TONE,
            available = NativeEffect.BASS in available || NativeEffect.WIDTH in available,
            params = listOf(
                RackParam("BASS", bass, false, onBass, { pct(it) }),
                RackParam("WIDE", width, false, onWidth, { pct(it) })
            )
        ),
        // No params: the equaliser's five bands take the fader row over entirely.
        RackCell(channel = RackChannel.EQ, hue = HUE_EQ, params = emptyList())
    )
}

/** −1…+1 as a fader's 0…1, and back. */
private fun toUnit(bipolar: Float): Float = ((bipolar + 1f) / 2f).coerceIn(0f, 1f)

private fun toBipolar(unit: Float): Float = (unit * 2f - 1f).coerceIn(-1f, 1f)

private fun pct(v: Float): String = (v.coerceIn(0f, 1f) * 100f).roundToInt().toString()

private fun oneDp(v: Float): String = String.format(Locale.US, "%.1f", v)

private fun twoDp(v: Float): String = String.format(Locale.US, "%.2f", v)

/** Loudness is capped well below what the effect accepts. Mirrors NativeEffects' own ceiling. */
private const val MAX_LOUD_DB = 12f

// The cell colours. Enough of a spread that a thumb finds a cell by its bar rather than by
// reading it, and drawn from the palette the app already owns rather than a new set.
private val HUE_FILTER = LampAmber
private val HUE_SPEED = Color(0xFF7FB2E5)
private val HUE_PITCH = Color(0xFF7FB2E5)
private val HUE_DRIVE = Color(0xFFE84D7A)
private val HUE_ECHO = OxideTeal
private val HUE_SPACE = LampAmber
private val HUE_TONE = LabelCream
private val HUE_EQ = LabelCream
