package com.jawon.app.ui.wallscene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.data.playback.EqBand
import com.jawon.app.data.playback.NativeEffect
import com.jawon.app.data.playback.NativeEffects
import com.jawon.app.playback.DjFilter
import com.jawon.app.playback.Drive
import com.jawon.app.playback.Echo
import com.jawon.app.playback.RackChannel
import com.jawon.app.playback.Varispeed
import com.jawon.app.ui.theme.DeckFinish
import com.jawon.app.ui.theme.RackLayout
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalDeckFinish
import com.jawon.app.ui.theme.OxideTeal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
    /** The equaliser's bands, drawn on this face rather than borrowed from Settings. */
    eqBands: List<EqBand>,
    eqRangeMb: Pair<Int, Int>,
    onEqBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current

    // **No readout of its own.** This used to carry a second window saying which cell was
    // selected, directly under the deck's own — two glowing displays a centimetre apart, which
    // read as a mistake rather than as a machine. A real component has one window, so the
    // selection went up into that one and this got the space back.
    // **The grid arrives rather than appearing.**
    //
    // Each key rises and fades in a beat after the one before it, left to right and top to
    // bottom, so opening the page reads as a machine powering up instead of a screen being
    // swapped. The stagger is what makes it feel authored — eight things moving together is a
    // transition, eight things moving in sequence is a sequence.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }

    // **Each mode gets its own machine, not its own palette.**
    //
    // Two attempts at giving the modes separate identities failed because they were
    // rearrangements of this grid, and eight keys in a row is still eight keys whichever way
    // they are pushed around. These are different objects: a ring of studs orbiting the record,
    // a rack of modules whose widths say how complicated each effect is.
    when (finish.layout) {
        RackLayout.RING -> {
            SegmentRing(
                cells = cells,
                engaged = engaged,
                selected = selected,
                arrived = arrived,
                onToggle = onToggle,
                onSelect = onSelect,
                eqBands = eqBands,
                eqRangeMb = eqRangeMb,
                onEqBandChange = onEqBandChange,
                modifier = modifier
            )
            return
        }

        RackLayout.MODULAR -> {
            ModularRack(
                cells = cells,
                engaged = engaged,
                selected = selected,
                arrived = arrived,
                onToggle = onToggle,
                onSelect = onSelect,
                eqBands = eqBands,
                eqRangeMb = eqRangeMb,
                onEqBandChange = onEqBandChange,
                modifier = modifier
            )
            return
        }

        // SHELF and RACK_UNIT both fall through to the grid below for now; the rack unit's own
        // arrangement is the next one to be built.
        else -> Unit
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // Two rows of four. A 4×2 grid keeps every cell about 74dp wide on a phone, which is
        // comfortably above a thumb's minimum, and it reads as a bank of keys rather than a list.
        cells.chunked(4).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = CELL_GAP.dp),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP.dp)
            ) {
                row.forEachIndexed { colIndex, cell ->
                    Cell(
                        order = rowIndex * 4 + colIndex,
                        arrived = arrived,
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
            EqBands(
                bands = eqBands,
                rangeMb = eqRangeMb,
                onBandChange = onEqBandChange,
                modifier = Modifier.padding(top = 6.dp)
            )
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
    /** Where this key sits in the power-up sequence. */
    order: Int,
    arrived: Boolean,
    engaged: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    val shape = RoundedCornerShape(finish.keyRadius)

    // **A key that does not move when you hit it is a picture of a key.**
    //
    // Pressed goes down and slightly dark, then springs back — low stiffness so it overshoots a
    // little, the way a real key rebounds past its rest position. Held in the draw phase via
    // `graphicsLayer`, so the spring redraws one layer instead of recomposing the grid.
    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press"
    )

    // Switching in fades the key up rather than snapping it, which is what makes throwing an
    // effect in and out read as one gesture instead of two states.
    val lit = animateFloatAsState(
        targetValue = if (engaged) 1f else 0f,
        animationSpec = tween(CELL_FADE_MS),
        label = "lit"
    )

    // The selected cell breathes its own colour, so which cell owns the faders is answerable
    // from the corner of your eye rather than by reading a border.
    // Rise and fade, delayed by this key's place in the sequence.
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(
            durationMillis = ENTRY_MS,
            delayMillis = order * ENTRY_STAGGER_MS,
            easing = FastOutSlowInEasing
        ),
        label = "entry"
    )

    val pulse = rememberInfiniteTransition(label = "cell").animateFloat(
        initialValue = SELECT_PULSE_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SELECT_PULSE_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .aspectRatio(CELL_ASPECT)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
                alpha = entry.value
                translationY = (1f - entry.value) * ENTRY_RISE_PX
            }
            .background(
                if (engaged) finish.key else Color.Transparent,
                shape
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    finish.accent.copy(alpha = pulse.value)
                } else {
                    finish.keyEdge
                },
                shape = shape
            )
            .then(if (finish.bevelled) Modifier.bevel(engaged) else Modifier)
            .alpha(if (cell.available) 1f else UNAVAILABLE_ALPHA)
            .then(
                if (cell.available) {
                    Modifier.pointerInput(cell.channel, engaged, selected) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = {
                                // Switch it in and select it; tapping the one already selected
                                // takes it out again. One gesture doing two jobs, but the two are
                                // never ambiguous: a cell that is out can only be switched in,
                                // and the selected one is the only cell a second tap can reach.
                                when {
                                    !engaged -> { onToggle(); onSelect() }
                                    selected -> onToggle()
                                    else -> onSelect()
                                }
                            }
                        )
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
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(BEAM_THICKNESS.dp)
                // **The beam, and this is the thing that took four attempts to get right.**
                //
                // The reference was always the room's own case underlight: bright, fully
                // saturated, and bleeding into the air around it. What was here instead was a
                // flat strip at reduced alpha — a drawn line, not a light. In between I built
                // two things that were not asked for: a wash behind the key, then a pool of
                // light on the plate below it.
                //
                // A light source is bright *and* it bleeds. So the strip goes to full colour,
                // and the bleed is an elongated radial centred on it, squashed flat so it hugs
                // the bar rather than ballooning into a circle. Drawn at exactly its own radius,
                // so it reaches zero alpha at the edge and has no boundary anywhere.
                .drawBehind {
                    val g = finish.glow * lit.value
                    if (g > 0f) {
                        val heart = Offset(size.width / 2f, size.height / 2f)
                        val reach = size.width * BEAM_BLOOM_REACH
                        scale(scaleX = 1f, scaleY = BEAM_BLOOM_SQUASH, pivot = heart) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        cell.hue.copy(alpha = BEAM_BLOOM_CORE * g),
                                        cell.hue.copy(alpha = BEAM_BLOOM_CORE * 0.4f * g),
                                        Color.Transparent
                                    ),
                                    center = heart,
                                    radius = reach
                                ),
                                radius = reach,
                                center = heart
                            )
                        }
                    }
                }
                .background(
                    // Full colour when it is in. A light that is 85% on is a line.
                    cell.hue.copy(alpha = lit.value),
                    RoundedCornerShape(BEAM_THICKNESS.dp / 2)
                )
        )
        Text(
            text = cell.channel.label,
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize,
            fontWeight = FontWeight.Bold,
            color = lerp(finish.keyDim, finish.keyInk, lit.value),
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
private const val HUE_ALPHA = 0.85f

// How hard a key is pushed, and how long a switched-in key takes to come up.
private const val PRESS_SCALE = 0.93f

// The power-up sequence: how far a key rises from, how long it takes, and the beat between one
// key and the next. Short enough that the whole bank is up in well under half a second.
private const val ENTRY_MS = 260
private const val ENTRY_STAGGER_MS = 34
private const val ENTRY_RISE_PX = 34f
private const val CELL_FADE_MS = 220

// The selected key breathes so it can be found without reading. Slower and shallower than the
// window's glow, because eight of these pulsing in time would be a fairground.
private const val SELECT_PULSE_LOW = 0.55f
private const val SELECT_PULSE_MS = 1900

/** How strongly a switched-in key bleeds its own colour. Scaled by the mode's glow. */
// The pool of light under a switched-in key. Reach is a fraction of the key's width, so the
// light spills well past it on both sides; the squash turns the circle into the ellipse a beam
// makes where it lands on a flat surface.
// The beam on a switched-in key: a bar of full colour, and the light it bleeds. Reach is a
// multiple of the key's width so the bloom spills past both ends; the squash flattens the
// circle so it hugs the bar instead of ballooning into a disc.
private const val BEAM_THICKNESS = 3
private const val BEAM_BLOOM_REACH = 0.78f
private const val BEAM_BLOOM_SQUASH = 0.14f
private const val BEAM_BLOOM_CORE = 0.85f

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
private val HUE_FILTER = Color(0xFFF0A93C)
private val HUE_SPEED = Color(0xFF7FB2E5)
private val HUE_PITCH = Color(0xFF7FB2E5)
private val HUE_DRIVE = Color(0xFFE84D7A)
private val HUE_ECHO = Color(0xFF2F7D71)
private val HUE_SPACE = Color(0xFFF0A93C)
private val HUE_TONE = Color(0xFFEFE3CC)
private val HUE_EQ = Color(0xFFEFE3CC)

/**
 * The equaliser as bands on the rack's own face.
 *
 * **Not the Settings panel squeezed in here.** That one is a stack of full-width horizontal
 * sliders, which is right on a scrolling settings page and wrong in a fixed slot: dropped into
 * the fader row it ran straight off the bottom of the deck and drew over the progress bar and
 * the transport. This is the same five bands built the way everything else on this face is
 * built — vertical, in the space three faders would have taken, resting at the centre line.
 *
 * Five columns rather than three, so each is narrower than a rack fader. That is acceptable
 * here and nowhere else: an equaliser is set by ear in one pass and then left alone, unlike the
 * filter or the echo, which are ridden.
 */
@Composable
private fun EqBands(
    bands: List<EqBand>,
    rangeMb: Pair<Int, Int>,
    onBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** How tall the bands are. The ring has less room in its hole than a panel has. */
    height: Int = FADER_HEIGHT
) {
    val finish = LocalDeckFinish.current
    val (minMb, maxMb) = rangeMb
    val span = (maxMb - minMb).coerceAtLeast(1)

    Row(
        modifier = modifier.fillMaxWidth().height(height.dp),
        horizontalArrangement = Arrangement.spacedBy(EQ_GAP.dp)
    ) {
        bands.forEach { band ->
            var height by remember(band.index) { mutableFloatStateOf(1f) }
            val shape = RoundedCornerShape(finish.keyRadius)
            val unit = ((band.levelMb - minMb).toFloat() / span).coerceIn(0f, 1f)
            val flat = kotlin.math.abs(band.levelMb) < FLAT_SLOP_MB

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(finish.groove, shape)
                        .border(1.dp, finish.keyEdge, shape)
                        .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                        .pointerInput(band.index) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    onBandChange(
                                        band.index,
                                        levelFor(it.y, height, minMb, span)
                                    )
                                }
                            ) { change, _ ->
                                onBandChange(
                                    band.index,
                                    levelFor(change.position.y, height, minMb, span)
                                )
                            }
                        }
                ) {
                    // Flat is the centre line, and it is drawn, because "no change" is a
                    // position a thumb aims at rather than a number anyone reads.
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(finish.keyEdge)
                    )
                    Box(
                        Modifier
                            .align(BiasAlignment(horizontalBias = 0f, verticalBias = 1f - unit * 2f))
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                            .height(CAP_HEIGHT.dp)
                            .background(
                                if (flat) finish.cap else finish.accent,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
                Text(
                    text = shortHz(band.centerHz),
                    fontFamily = finish.keyType,
                    fontSize = finish.keyTypeSize * 0.92f,
                    color = if (flat) finish.plateDim else finish.plateInk,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 3.dp).fillMaxWidth()
                )
            }
        }
    }
}

/** A drag position on a band, as a gain in millibels. Up is more, so the y is inverted. */
private fun levelFor(y: Float, height: Float, minMb: Int, span: Int): Int =
    (minMb + (1f - (y / height).coerceIn(0f, 1f)) * span).roundToInt()

/** `60`, `910`, `3.6k`, `14k` — a frequency in the four characters a band label has room for. */
private fun shortHz(hz: Int): String = when {
    hz >= 10_000 -> "${hz / 1000}k"
    hz >= 1_000 -> String.format(Locale.US, "%.1fk", hz / 1000f)
    else -> hz.toString()
}

private const val EQ_GAP = 4

/** Within this of zero, a band counts as flat and stops being drawn as a change. */
private const val FLAT_SLOP_MB = 40

/**
 * The effects in orbit around the disc.
 *
 * **No grid, and that is the point.** Eight studs on an arc with the selected effect's controls
 * in the middle of it — the only layout here that could not belong to any other app, because it
 * is built around the record rather than around a screen. It is also the only one that leaves
 * the disc uncovered: the arc opens upward, so the thing you came to look at stays visible
 * through the gap.
 *
 * Laid out by hand rather than with a layout: a ring is polar and Compose's containers are not,
 * so each stud is placed by angle at a radius measured from the panel's own width. Everything is
 * a fraction of that width, so it holds together on any screen without a second set of numbers.
 */
@Composable
private fun RingRack(
    cells: List<RackCell>,
    engaged: Set<RackChannel>,
    selected: RackChannel?,
    arrived: Boolean,
    onToggle: (RackChannel) -> Unit,
    onSelect: (RackChannel) -> Unit,
    /**
     * The equaliser's bands.
     *
     * The ring had no idea the equaliser existed, so selecting its stud handed the middle three
     * empty faders — the effect looked broken rather than unimplemented. Five bands also need
     * more room than three, so the hole widens for them.
     */
    eqBands: List<EqBand>,
    eqRangeMb: Pair<Int, Int>,
    onEqBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    val params = cells.firstOrNull { it.channel == selected }?.params.orEmpty()

    // **One box for the ring, and everything measured from its middle.**
    //
    // The first version placed the studs against the panel and centred the core against the
    // panel *separately*, which is two frames of reference for one circle — the core came out
    // sitting over the left-hand studs rather than in the hole. Giving the ring its own square
    // box makes the maths local: the studs are offsets from this box's centre, and the core is
    // simply centred in it, so the two cannot disagree.
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(RING_HEIGHT.dp),
        contentAlignment = Alignment.Center
    ) {
        val diameter = minOf(maxWidth, maxHeight) * RING_DIAMETER_FRACTION
        val radius = diameter / 2

        Box(
            modifier = Modifier
                .size(diameter)
                // **Every stud's glow is painted here, on the ring's own canvas.**
                //
                // Twice now the bloom has been drawn inside the stud and twice it has come out
                // with a visible rectangle around it, because something in that node's modifier
                // chain renders to an offscreen layer the size of the stud and clips whatever
                // reaches past it. Moving the draw earlier in the chain was not enough.
                //
                // So it stops being the stud's business. The ring is one large node, the blooms
                // are drawn into it at the same polar positions the studs are placed at, and
                // there is no small box anywhere for them to be cut against. The glows land
                // under the studs because this is `drawBehind` on their common parent.
                .drawBehind {
                    if (finish.glow <= 0f) return@drawBehind
                    val r = size.minDimension / 2f
                    val reach = RING_STUD.dp.toPx() * RING_BLOOM_REACH

                    cells.forEachIndexed { i, cell ->
                        if (cell.channel !in engaged) return@forEachIndexed
                        val a = -90.0 - RING_SWEEP_DEGREES / 2.0 +
                            RING_SWEEP_DEGREES * i / (cells.size - 1).coerceAtLeast(1)
                        val rad = Math.toRadians(a + 180.0)
                        val heart = Offset(
                            size.width / 2f + r * cos(rad).toFloat(),
                            size.height / 2f + r * sin(rad).toFloat()
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    cell.hue.copy(alpha = RING_BLOOM_CORE * finish.glow),
                                    cell.hue.copy(alpha = RING_BLOOM_CORE * 0.3f * finish.glow),
                                    Color.Transparent
                                ),
                                center = heart,
                                radius = reach
                            ),
                            radius = reach,
                            center = heart
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            cells.forEachIndexed { i, cell ->
                // The arc runs from lower-left round the bottom to lower-right, leaving the top
                // open so the record above is never behind a stud.
                val sweep = RING_SWEEP_DEGREES
                val angle = -90.0 - sweep / 2.0 + sweep * i / (cells.size - 1).coerceAtLeast(1)
                val rad = Math.toRadians(angle + 180.0)

                RingStud(
                    cell = cell,
                    order = i,
                    arrived = arrived,
                    engaged = cell.channel in engaged,
                    selected = cell.channel == selected,
                    onToggle = { onToggle(cell.channel) },
                    onSelect = { onSelect(cell.channel) },
                    modifier = Modifier.offset(
                        x = radius * cos(rad).toFloat(),
                        y = radius * sin(rad).toFloat()
                    )
                )
            }

            // The middle of the ring: what is selected, and its controls. Kept narrow so it
            // never reaches the studs — the hole in a ring is the point of a ring. Centred in
            // the ring's own box, so it lands in the hole by construction.
            val onEq = selected == RackChannel.EQ
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    // Five bands need more of the hole than three faders do, so the core widens
                    // for the equaliser rather than squeezing five controls into three's room.
                    .fillMaxWidth(if (onEq) RING_CORE_WIDTH_EQ else RING_CORE_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = selected?.label ?: "—",
                    fontFamily = finish.keyType,
                    fontSize = finish.keyTypeSize * 1.5f,
                    fontWeight = FontWeight.Bold,
                    color = finish.plateInk,
                    maxLines = 1
                )
                if (onEq) {
                    EqBands(
                        bands = eqBands,
                        rangeMb = eqRangeMb,
                        onBandChange = onEqBandChange,
                        height = RING_FADER_HEIGHT,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    // Stacked rather than side by side: the inside of a circle is wide and
                    // short, so each control gets the whole width of the hole for its travel
                    // instead of a third of it.
                    Column(
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(RING_PARAM_GAP.dp)
                    ) {
                        repeat(FADER_SLOTS) { slot ->
                            RingParam(
                                param = params.getOrNull(slot),
                                hue = cells.firstOrNull { it.channel == selected }?.hue
                                    ?: finish.accent,
                                live = selected != null && selected in engaged
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One stud on the ring: a lit disc rather than a key, because a ring has no corners. */
@Composable
private fun RingStud(
    cell: RackCell,
    order: Int,
    arrived: Boolean,
    engaged: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current

    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "press"
    )
    val lit = animateFloatAsState(
        targetValue = if (engaged) 1f else 0f,
        animationSpec = tween(CELL_FADE_MS),
        label = "lit"
    )
    // The studs light in sequence around the arc, so the ring switches on like a sign rather
    // than appearing all at once.
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(ENTRY_MS, order * ENTRY_STAGGER_MS, FastOutSlowInEasing),
        label = "entry"
    )
    val pulse = rememberInfiniteTransition(label = "stud").animateFloat(
        initialValue = SELECT_PULSE_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SELECT_PULSE_MS, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier.width(RING_STUD.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(RING_STUD.dp)
                .graphicsLayer {
                    scaleX = press.value * (0.6f + 0.4f * entry.value)
                    scaleY = press.value * (0.6f + 0.4f * entry.value)
                    alpha = entry.value
                }
                .background(
                    if (engaged) cell.hue.copy(alpha = lit.value) else Color.Transparent,
                    CircleShape
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = when {
                        selected -> finish.accent.copy(alpha = pulse.value)
                        engaged -> Color.Transparent
                        else -> finish.keyEdge
                    },
                    shape = CircleShape
                )
                .then(
                    if (cell.available) {
                        Modifier.pointerInput(cell.channel, engaged, selected) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    tryAwaitRelease()
                                    pressed = false
                                },
                                onTap = {
                                    when {
                                        !engaged -> { onToggle(); onSelect() }
                                        selected -> onToggle()
                                        else -> onSelect()
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .alpha(if (cell.available) 1f else UNAVAILABLE_ALPHA)
        )
        Text(
            text = cell.channel.label,
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize * 0.86f,
            color = if (engaged) finish.plateInk else finish.plateDim,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp).graphicsLayer { alpha = entry.value }
        )
    }
}

/**
 * A rack of modules, each as wide as it is complicated.
 *
 * **The panel tells you where the depth is.** The echo is three times the width of the drive
 * because it has three knobs and the drive has one — which is how a real rack looks, and the
 * opposite of a uniform grid, where a one-knob effect and a three-knob effect occupy identical
 * squares and the face tells you nothing.
 *
 * Widths come from the cell's own parameter count, so adding a ninth effect sizes itself.
 */
@Composable
private fun ModularRack(
    cells: List<RackCell>,
    engaged: Set<RackChannel>,
    selected: RackChannel?,
    arrived: Boolean,
    onToggle: (RackChannel) -> Unit,
    onSelect: (RackChannel) -> Unit,
    eqBands: List<EqBand>,
    eqRangeMb: Pair<Int, Int>,
    onEqBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(MODULE_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(MODULE_GAP.dp)
        ) {
            cells.forEachIndexed { i, cell ->
                Module(
                    cell = cell,
                    order = i,
                    arrived = arrived,
                    engaged = cell.channel in engaged,
                    selected = cell.channel == selected,
                    onToggle = { onToggle(cell.channel) },
                    onSelect = { onSelect(cell.channel) },
                    // One horizontal unit per knob, with a floor so a single-knob module is a
                    // sliver rather than a hairline.
                    modifier = Modifier.weight(
                        (cell.params.size.coerceAtLeast(1)).toFloat()
                            .coerceAtLeast(MODULE_MIN_UNITS)
                    )
                )
            }
        }

        // The selected module's controls, on the rail beneath the case.
        if (selected == RackChannel.EQ) {
            EqBands(
                bands = eqBands,
                rangeMb = eqRangeMb,
                onBandChange = onEqBandChange,
                modifier = Modifier.padding(top = MODULE_GAP.dp)
            )
        } else {
            val params = cells.firstOrNull { it.channel == selected }?.params.orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MODULE_GAP.dp)
                    .height(FADER_HEIGHT.dp),
                horizontalArrangement = Arrangement.spacedBy(MODULE_GAP.dp)
            ) {
                repeat(FADER_SLOTS) { slot ->
                    Fader(
                        param = params.getOrNull(slot),
                        hue = cells.firstOrNull { it.channel == selected }?.hue ?: finish.accent,
                        live = selected != null && selected in engaged,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** One module: a milled panel with screws, a lit strip, its name set vertically, and jacks. */
@Composable
private fun Module(
    cell: RackCell,
    order: Int,
    arrived: Boolean,
    engaged: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current

    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "press"
    )
    val lit = animateFloatAsState(
        targetValue = if (engaged) 1f else 0f,
        animationSpec = tween(CELL_FADE_MS),
        label = "lit"
    )
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(ENTRY_MS, order * ENTRY_STAGGER_MS, FastOutSlowInEasing),
        label = "entry"
    )
    val pulse = rememberInfiniteTransition(label = "module").animateFloat(
        initialValue = SELECT_PULSE_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SELECT_PULSE_MS, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleY = press.value
                alpha = entry.value
                translationY = (1f - entry.value) * ENTRY_RISE_PX
            }
            .background(if (engaged) finish.key else Color.Transparent)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) finish.accent.copy(alpha = pulse.value) else finish.keyEdge
            )
            .alpha(if (cell.available) 1f else UNAVAILABLE_ALPHA)
            .then(
                if (cell.available) {
                    Modifier.pointerInput(cell.channel, engaged, selected) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = {
                                when {
                                    !engaged -> { onToggle(); onSelect() }
                                    selected -> onToggle()
                                    else -> onSelect()
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Screws(finish)

        // The lit strip, the same beam every other layout uses.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(BEAM_THICKNESS.dp)
                .drawBehind {
                    val g = finish.glow * lit.value
                    if (g > 0f) {
                        val heart = Offset(size.width / 2f, size.height / 2f)
                        val reach = size.width * BEAM_BLOOM_REACH * 2f
                        scale(scaleX = 1f, scaleY = BEAM_BLOOM_SQUASH * 2f, pivot = heart) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        cell.hue.copy(alpha = BEAM_BLOOM_CORE * g),
                                        Color.Transparent
                                    ),
                                    center = heart,
                                    radius = reach
                                ),
                                radius = reach,
                                center = heart
                            )
                        }
                    }
                }
                .background(cell.hue.copy(alpha = lit.value))
        )

        Text(
            text = cell.channel.label,
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize * 0.9f,
            fontWeight = FontWeight.Bold,
            color = if (engaged) finish.keyInk else finish.keyDim,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // Patch jacks along the base — one per knob, because that is what the module carries.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(cell.params.size.coerceAtLeast(1).coerceAtMost(MODULE_MAX_JACKS)) {
                Box(
                    modifier = Modifier
                        .size(MODULE_JACK.dp)
                        .background(finish.groove, CircleShape)
                        .border(1.dp, finish.keyEdge, CircleShape)
                )
            }
        }

        Screws(finish)
    }
}

/** The two screws at each end of a module's panel. Free, and most of what says "rack". */
@Composable
private fun Screws(finish: DeckFinish) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .size(MODULE_SCREW.dp)
                    .background(finish.keyEdge, CircleShape)
            )
        }
    }
}

// ---- the ring ------------------------------------------------------------------------
/** How tall the ring's panel is. Taller than a grid, because a ring is round. */
private const val RING_HEIGHT = 284

/** The studs sit this far from the centre, as a fraction of the panel's width. */
/** The ring fills this much of the shorter side of its panel. */
// Sized so the studs, which stick out half their own width past the arc, still clear the panel.
// At 0.94 with 46dp studs the ring ran off both sides and collided with the progress bar.
private const val RING_DIAMETER_FRACTION = 0.76f

/** How much of a full circle the arc covers. Open at the top, so the disc stays visible. */
private const val RING_SWEEP_DEGREES = 232.0

// **Sized for a thumb, not for a diagram.** The first ring drew 30dp studs and a 54dp throw,
// which looked right in a screenshot and was fiddly to actually use — well under the 48dp a
// touch target wants. The studs are the primary control on this layout, so they are the biggest
// ones in the app.
private const val RING_STUD = 46
private const val RING_CORE_WIDTH = 0.58f

/** The hole opens up for the equaliser, which needs five columns where the rest need three. */
private const val RING_CORE_WIDTH_EQ = 0.72f

/** A known height, so the core can be placed against the ring centre instead of guessed at. */
private const val RING_CORE_HEIGHT = 96
private const val RING_FADER_HEIGHT = 78
private const val RING_BLOOM_REACH = 1.5f
private const val RING_BLOOM_CORE = 0.75f

// ---- the modular case ----------------------------------------------------------------
private const val MODULE_HEIGHT = 132
private const val MODULE_GAP = 3
private const val MODULE_SCREW = 3
private const val MODULE_JACK = 6
private const val MODULE_MAX_JACKS = 3

/** A one-knob module is still wide enough to hit, so widths have a floor. */
private const val MODULE_MIN_UNITS = 1.15f

/**
 * One control inside the ring, lying down.
 *
 * **Vertical faders are the wrong shape for a hole in a circle.** Three of them side by side in
 * the middle of a ring gives each about thirty points of width and a short throw, which looked
 * fine in a screenshot and was genuinely unpleasant to use — the owner said so twice. The
 * interior of a circle is *wide and short*, so the controls lie down to match it: each one gets
 * the full width of the hole for its travel instead of a third of it, which is roughly four
 * times the precision per control.
 *
 * The name and the value sit on the same line above the track, because a label under every row
 * would spend the vertical room the ring does not have.
 */
@Composable
private fun RingParam(
    param: RackParam?,
    hue: Color,
    live: Boolean,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    var width by remember { mutableFloatStateOf(1f) }

    if (param == null) {
        // A slot with nothing behind it is a hairline, not an empty control. The ring shows one
        // to three of these and an empty groove would read as three broken sliders.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(finish.keyEdge.copy(alpha = RING_BLANK_ALPHA))
        )
        return
    }

    val v = param.value.coerceIn(0f, 1f)
    val neutral = if (param.bipolar) 0.5f else 0f
    val working = live && kotlin.math.abs(v - neutral) > NEUTRAL_SLOP

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = param.name,
                fontFamily = finish.keyType,
                fontSize = finish.keyTypeSize * 1.15f,
                color = finish.plateDim,
                maxLines = 1
            )
            Text(
                text = param.readout(v),
                fontFamily = finish.keyType,
                fontSize = finish.keyTypeSize * 1.15f,
                color = if (working) hue else finish.plateDim,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .fillMaxWidth()
                .height(RING_PARAM_TRACK.dp)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(param.name) {
                    detectHorizontalDragGestures(
                        onDragStart = { param.onChange((it.x / width).coerceIn(0f, 1f)) },
                        onDragEnd = { param.onCommit?.invoke() },
                        onDragCancel = { param.onCommit?.invoke() }
                    ) { change, _ ->
                        param.onChange((change.position.x / width).coerceIn(0f, 1f))
                    }
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(finish.keyEdge, RoundedCornerShape(2.dp))
            )
            // A bipolar control fills out from its centre, so "off" is a position rather than a
            // number — the same rule the vertical faders follow.
            if (param.bipolar) {
                Box(
                    Modifier
                        .align(BiasAlignment(horizontalBias = 0f, verticalBias = 0f))
                        .width(1.dp)
                        .height(RING_PARAM_TRACK.dp)
                        .background(finish.keyEdge)
                )
            }
            Box(
                Modifier
                    .align(BiasAlignment(horizontalBias = v * 2f - 1f, verticalBias = 0f))
                    .size(RING_PARAM_KNOB.dp)
                    .background(if (working) hue else finish.cap, CircleShape)
            )
        }
    }
}

// The controls in the hole lie down, so the hole needs to be wider than it is tall — the ring
// opens up and the studs move out to make room for them.
private const val RING_PARAM_GAP = 7
private const val RING_PARAM_TRACK = 40
private const val RING_PARAM_KNOB = 22
private const val RING_BLANK_ALPHA = 0.45f

/**
 * The ring as a band cut into eight wedges.
 *
 * **The gaps became the buttons.** Eight 46dp studs on a 240dp arc use about a third of the ring
 * and leave the rest as air, which is what made the old ring feel both fiddly and empty. A
 * segment is four or five times the area of the circle it replaces, there is nothing left over
 * between them, and a band that lights up in eight places is a far better picture of what the
 * rack does than eight separate lamps.
 *
 * **Drawn as one canvas, and hit-tested by angle.** Eight rotated nodes would each be a box with
 * its own layer, which is exactly what kept clipping the glow into rectangles — three separate
 * attempts. A wedge is not a rectangle and cannot be one, so it is a path; and once the whole
 * band is a single node, a tap is answered by turning the touch point into polar coordinates and
 * asking which slice it fell in. No per-button geometry anywhere, and nothing left to clip.
 */
@Composable
private fun SegmentRing(
    cells: List<RackCell>,
    engaged: Set<RackChannel>,
    selected: RackChannel?,
    arrived: Boolean,
    onToggle: (RackChannel) -> Unit,
    onSelect: (RackChannel) -> Unit,
    eqBands: List<EqBand>,
    eqRangeMb: Pair<Int, Int>,
    onEqBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    val measurer = rememberTextMeasurer()
    val params = cells.firstOrNull { it.channel == selected }?.params.orEmpty()

    /** What turning the band actually turns: the selected effect's first control. */
    val knob = params.firstOrNull()

    // The band lights in sequence when the page opens, one wedge after the next round the arc.
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(SEG_ENTRY_MS, easing = FastOutSlowInEasing),
        label = "entry"
    )
    val pulse = rememberInfiniteTransition(label = "band").animateFloat(
        initialValue = SELECT_PULSE_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SELECT_PULSE_MS, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // **The controls come out of the hole.**
    //
    // A ring's hole is small by definition, and five equaliser bands will not go in it — three
    // sliders were already cramped there. So the middle keeps only the name of what is selected,
    // which is what a hole in a dial is for, and the controls move to a full-width row beneath.
    // That buys twice over: the sliders get the whole panel instead of half of it, and with
    // nothing left to fit inside it the ring itself can grow to the full width.
    Column(modifier = modifier.fillMaxWidth()) {

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(SEG_RING_HEIGHT.dp),
        contentAlignment = Alignment.Center
    ) {
        val diameter = minOf(maxWidth, maxHeight)

        Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cells, engaged, selected, params) {
                        // **One detector for both gestures, deliberately.**
                        //
                        // The band is a bank of switches you tap *and* a wheel you turn, on the
                        // same pixels. Handing that to `detectTapGestures` and
                        // `detectDragGestures` as two modifiers is the mistake this project has
                        // already paid for once: two handlers on one gesture, with the winner
                        // depending on which one consumes first, is what made the disc's release
                        // fight its own scratch. So the press is taken once and this decides
                        // what it was.
                        //
                        // A press becomes a turn only after the finger has travelled a few
                        // degrees around the arc. Below that it is a tap, so a smudged press
                        // switches an effect rather than quietly nudging its filter.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val inner = minOf(size.width, size.height) / 2f - SEG_BAND.dp.toPx()

                            var last = angleOf(down.position, centre)
                            var travelled = 0f
                            var turning = false

                            drag(down.id) { change ->
                                // **Consumed from the very first movement, before deciding what
                                // the gesture is.**
                                //
                                // This page sits inside a HorizontalPager, and turning the band
                                // is mostly a sideways drag. The pager's own slop is a few
                                // pixels — far less than four degrees of arc — so while this was
                                // still making up its mind the pager had already claimed the
                                // gesture and swiped to the tracklist. Turning the band did
                                // nothing except change the page, which is exactly what the
                                // owner saw.
                                //
                                // A child sees the Main pass before its parents, so consuming
                                // every change here keeps the pager out of it from the start.
                                // Deciding tap-versus-turn is then this function's business
                                // alone, and it still decides it by total travel below.
                                change.consume()

                                val now = angleOf(change.position, centre)
                                val step = angleDelta(last, now)
                                last = now
                                travelled += abs(step)

                                if (!turning && travelled >= SEG_TURN_SLOP_DEGREES) turning = true
                                if (turning) {
                                    // A full sweep of the band is the control's full range, so
                                    // the mapping is the arc itself rather than an invented
                                    // sensitivity.
                                    knob?.let {
                                        it.onChange(
                                            (it.value + step / SEG_SWEEP).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                            }

                            if (turning) {
                                knob?.onCommit?.invoke()
                            } else {
                                // Never travelled far enough to be a turn, so it was a press.
                                val hit = segmentAt(
                                    pos = down.position,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                    inner = inner
                                )
                                val cell = cells.getOrNull(hit)
                                if (cell != null && cell.available) {
                                    val isIn = cell.channel in engaged
                                    when {
                                        !isIn -> { onToggle(cell.channel); onSelect(cell.channel) }
                                        cell.channel == selected -> onToggle(cell.channel)
                                        else -> onSelect(cell.channel)
                                    }
                                }
                            }
                        }
                    }
            ) {
                val outer = size.minDimension / 2f
                val inner = outer - SEG_BAND.dp.toPx()
                val step = SEG_SWEEP / cells.size

                cells.forEachIndexed { i, cell ->
                    // **The band wears the mode, not eight unrelated colours.**
                    //
                    // The grid gives every effect its own hue so a thumb can find the filter
                    // without reading, which is right for a bank of keys and wrong here: eight
                    // arbitrary colours in a continuous ring read as a rainbow laid over a room
                    // that only owns two. So the wedges ramp between the mode's own pair, cold
                    // at one end and hot at the other, which still tells them apart along the
                    // arc while belonging to the scheme.
                    val hue = lerp(
                        finish.accent,
                        finish.accentB,
                        i / (cells.size - 1).coerceAtLeast(1).toFloat()
                    )

                    // Each wedge lights a beat after the one before it, so the band comes up
                    // round the arc instead of all at once.
                    val lit = ((entry.value * cells.size) - i).coerceIn(0f, 1f)
                    if (lit <= 0f) return@forEachIndexed

                    val isIn = cell.channel in engaged
                    val isSel = cell.channel == selected
                    val a0 = SEG_START + step * i + SEG_GAP_DEGREES
                    val sweep = step - SEG_GAP_DEGREES * 2

                    val wedge = wedgePath(center, outer, inner, a0, sweep)

                    // Dark plate first, so a switched-out wedge still reads as a physical thing
                    // rather than a hole in the band.
                    drawPath(
                        path = wedge,
                        color = if (isIn) {
                            hue.copy(alpha = SEG_FILL_ALPHA * lit)
                        } else {
                            finish.key.copy(alpha = SEG_OFF_ALPHA * lit)
                        }
                    )

                    // The rim, and its bloom: three strokes of falling alpha and rising width
                    // rather than a blur, because a blur needs API 31 and this app starts at 28.
                    val rim = if (isIn) hue else finish.keyEdge
                    val heat = if (isIn) finish.glow * lit * (if (isSel) pulse.value else 1f) else 0f
                    if (heat > 0f) {
                        listOf(
                            SEG_BLOOM_WIDE to 0.10f,
                            SEG_BLOOM_MID to 0.20f,
                            SEG_BLOOM_TIGHT to 0.42f
                        ).forEach { (w, a) ->
                            drawPath(
                                path = wedge,
                                color = hue.copy(alpha = a * heat),
                                style = Stroke(width = w.dp.toPx())
                            )
                        }
                    }
                    drawPath(
                        path = wedge,
                        color = rim.copy(alpha = lit),
                        style = Stroke(width = if (isSel) 2.dp.toPx() else 1.dp.toPx())
                    )

                    // The name, level rather than curved: a label following the arc is a lovely
                    // drawing and slower to read, and this one is read at a glance mid-track.
                    val mid = Math.toRadians((a0 + sweep / 2).toDouble())
                    val r = (outer + inner) / 2f
                    val laid = measurer.measure(
                        text = AnnotatedString(cell.channel.label),
                        style = TextStyle(
                            fontFamily = finish.keyType,
                            fontSize = finish.keyTypeSize,
                            fontWeight = FontWeight.Bold,
                            color = if (isIn) finish.keyInk else finish.keyDim
                        )
                    )
                    drawText(
                        textLayoutResult = laid,
                        topLeft = Offset(
                            center.x + r * cos(mid).toFloat() - laid.size.width / 2f,
                            center.y + r * sin(mid).toFloat() - laid.size.height / 2f
                        ),
                        alpha = lit
                    )
                }

                // **The value, drawn around the outside of the band.**
                //
                // Turning the ring worked and felt broken, which is worse than not working: the
                // only thing that moved was a small readout under the panel, so the ring itself
                // sat there like a picture while the filter swept. A knob you turn has to show
                // you the turn. This is a bright arc riding just outside the wedges, filling
                // from the start of the arc to wherever the selected control is set — so the
                // ring reads as a dial with a position rather than as eight lit buttons.
                knob?.let { k ->
                    val ind = outer + SEG_VALUE_OFFSET.dp.toPx()
                    val rect = Rect(
                        center.x - ind, center.y - ind,
                        center.x + ind, center.y + ind
                    )
                    val hot = lerp(finish.accent, finish.accentB, 0.5f)
                    // The empty travel first, so the arc has a scale to be read against.
                    drawArc(
                        color = finish.keyEdge,
                        startAngle = SEG_START,
                        sweepAngle = SEG_SWEEP,
                        useCenter = false,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = SEG_VALUE_TRACK, cap = StrokeCap.Round)
                    )
                    // A bipolar control fills out from the middle of the arc, so its rest
                    // position is somewhere you can see rather than a number you have to read.
                    val from = if (k.bipolar) SEG_START + SEG_SWEEP / 2f else SEG_START
                    val to = SEG_START + SEG_SWEEP * k.value.coerceIn(0f, 1f)
                    if (abs(to - from) > 0.5f) {
                        if (finish.glow > 0f) {
                            drawArc(
                                color = hot.copy(alpha = SEG_VALUE_GLOW * finish.glow),
                                startAngle = minOf(from, to),
                                sweepAngle = abs(to - from),
                                useCenter = false,
                                topLeft = rect.topLeft,
                                size = rect.size,
                                style = Stroke(
                                    width = SEG_VALUE_TRACK * 3.4f,
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                        drawArc(
                            color = hot,
                            startAngle = minOf(from, to),
                            sweepAngle = abs(to - from),
                            useCenter = false,
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = Stroke(width = SEG_VALUE_TRACK, cap = StrokeCap.Round)
                        )
                    }
                    // And a bead at the value, which is the thing the eye actually follows.
                    val a = Math.toRadians(to.toDouble())
                    drawCircle(
                        color = hot,
                        radius = SEG_VALUE_BEAD,
                        center = Offset(
                            center.x + ind * cos(a).toFloat(),
                            center.y + ind * sin(a).toFloat()
                        )
                    )
                }
            }

            // The hole holds the name and nothing else — a dial's window, set in the display
            // face so it reads as part of the machine rather than as a caption.
            Text(
                text = selected?.label ?: "—",
                fontFamily = finish.display,
                fontSize = finish.displaySize,
                color = finish.displayInk,
                letterSpacing = finish.displayTracking,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

        // The controls, across the whole panel. Five equaliser bands fit here and did not fit in
        // the hole, which is the entire reason they moved.
        val onEq = selected == RackChannel.EQ
        if (onEq) {
            EqCurve(
                bands = eqBands,
                rangeMb = eqRangeMb,
                onBandChange = onEqBandChange,
                modifier = Modifier.padding(top = SEG_CONTROL_GAP.dp)
            )
        } else {
            // **Big, direct, and shaped like the equaliser.**
            //
            // The knobs here were 58dp of drawing with a 420px drag behind them, and the owner's
            // verdict on the whole panel was that the equaliser curve was the only comfortable
            // thing on it. That is the lesson: what works is a wide target you put your finger
            // straight onto, with the number next to it, rather than a small dial you have to
            // find and then stroke in some invented direction. So these are full-width bars with
            // a touch row taller than a fingertip, drawn like the curve and read the same way.
            Column(
                modifier = Modifier.padding(top = SEG_CONTROL_GAP.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SEG_BAR_GAP.dp)
            ) {
                repeat(FADER_SLOTS) { slot ->
                    RingParam(
                        param = params.getOrNull(slot),
                        hue = selected?.let { s ->
                            val i = cells.indexOfFirst { it.channel == s }.coerceAtLeast(0)
                            lerp(
                                finish.accent,
                                finish.accentB,
                                i / (cells.size - 1).coerceAtLeast(1).toFloat()
                            )
                        } ?: finish.accent,
                        live = selected != null && selected in engaged
                    )
                }
            }
        }
    }
}

/** One wedge of the band: out along the outer arc, in, and back along the inner one. */
private fun wedgePath(
    centre: Offset,
    outer: Float,
    inner: Float,
    startDeg: Float,
    sweepDeg: Float
): Path = Path().apply {
    arcTo(
        rect = Rect(centre.x - outer, centre.y - outer, centre.x + outer, centre.y + outer),
        startAngleDegrees = startDeg,
        sweepAngleDegrees = sweepDeg,
        forceMoveTo = true
    )
    arcTo(
        rect = Rect(centre.x - inner, centre.y - inner, centre.x + inner, centre.y + inner),
        startAngleDegrees = startDeg + sweepDeg,
        sweepAngleDegrees = -sweepDeg,
        forceMoveTo = false
    )
    close()
}

/**
 * Which wedge a tap landed in, or −1 for the hole and the air outside the band.
 *
 * The whole reason the band can be one node: a wedge is describable in polar coordinates and a
 * touch point converts into them in three lines, so there is no need for eight hit areas.
 */
internal fun segmentAt(
    pos: Offset,
    width: Float,
    height: Float,
    /**
     * The inner edge of the band, in pixels.
     *
     * **Passed in rather than derived.** It was a fraction of the radius here while the drawing
     * used a fixed thickness in dp, so the two disagreed about where the band ended and a tap
     * near the inner edge could hit nothing at all. One number, computed once, handed to both.
     */
    inner: Float,
    count: Int = 8
): Int {
    val cx = width / 2f
    val cy = height / 2f
    val outer = minOf(width, height) / 2f

    val dx = pos.x - cx
    val dy = pos.y - cy
    val r = hypot(dx, dy)
    if (r < inner || r > outer) return -1

    // Normalised so the band's own range is contiguous rather than wrapping through ±180°,
    // which is where an off-by-one at the two ends of the arc would otherwise live.
    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (deg < SEG_START) deg += 360f

    val into = deg - SEG_START
    if (into < 0f || into > SEG_SWEEP) return -1
    return (into / (SEG_SWEEP / count)).toInt().coerceIn(0, count - 1)
}

// The band runs from lower-left, round the bottom, to lower-right — open at the top so the record
// above it is never behind a wedge. Canvas degrees, so zero is east and they increase downward.
private const val SEG_START = -26f
private const val SEG_SWEEP = 232f

/** How thick the band is. The rest of the radius is the hole, and the hole holds the controls. */
private const val SEG_BAND = 38

/** A hairline of air between wedges, so eight buttons read as eight. */
private const val SEG_GAP_DEGREES = 1.4f

private const val SEG_FILL_ALPHA = 0.22f
private const val SEG_OFF_ALPHA = 0.55f
private const val SEG_BLOOM_WIDE = 9
private const val SEG_BLOOM_MID = 5
private const val SEG_BLOOM_TIGHT = 2
private const val SEG_ENTRY_MS = 620

/** How tall the ring itself is, now that nothing has to fit inside it but a word. */
private const val SEG_RING_HEIGHT = 214

/** Air between the band and the controls under it. */
private const val SEG_CONTROL_GAP = 10

/** Air between the control bars. */
private const val SEG_BAR_GAP = 4

// The value arc riding outside the band: how far out it sits, how thick it is, and the bead that
// marks the position — which is the part the eye actually follows while turning.
private const val SEG_VALUE_OFFSET = 7
private const val SEG_VALUE_TRACK = 5f
private const val SEG_VALUE_GLOW = 0.28f
private const val SEG_VALUE_BEAD = 7f

/**
 * A knob, turned by dragging straight up and down.
 *
 * **It looks rotary and it is not driven rotationally, and that is deliberate.** A control you
 * turn by tracing an arc is the wrong thing on a touchscreen twice over: the arc is invisible, so
 * there is nothing to follow, and your finger sits exactly on top of the pointer you are trying
 * to read. Every audio application that ships a knob solves it the same way — the gesture is a
 * vertical drag, the *drawing* is a rotation — and that is what this does.
 *
 * It earns its place next to the segmented band because the whole layout is circular: three
 * straight sliders under a ring look like a different instrument bolted to the bottom of it,
 * where three knobs read as the same machine.
 */
@Composable
private fun RingKnob(
    param: RackParam?,
    hue: Color,
    live: Boolean,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current

    if (param == null) {
        // An unused slot is an empty collar rather than a dead knob — a knob you cannot turn
        // reads as broken, an empty ring reads as room this effect does not need.
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(KNOB_SIZE.dp)
                    .alpha(KNOB_BLANK_ALPHA)
                    .border(1.dp, finish.keyEdge, CircleShape)
            )
        }
        return
    }

    val v = param.value.coerceIn(0f, 1f)
    val neutral = if (param.bipolar) 0.5f else 0f
    val working = live && kotlin.math.abs(v - neutral) > NEUTRAL_SLOP

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(KNOB_SIZE.dp)
                .pointerInput(param.name) {
                    detectVerticalDragGestures(
                        onDragEnd = { param.onCommit?.invoke() },
                        onDragCancel = { param.onCommit?.invoke() }
                    ) { change, dragAmount ->
                        // Up is more. The travel is a fixed distance rather than the knob's own
                        // size, so a small knob is not a twitchy one.
                        param.onChange((param.value - dragAmount / KNOB_TRAVEL_PX).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
        ) {
            val r = size.minDimension / 2f
            val ringR = r - KNOB_RING_INSET
            val stroke = Stroke(width = KNOB_STROKE, cap = StrokeCap.Round)
            val arcRect = Rect(
                center.x - ringR, center.y - ringR,
                center.x + ringR, center.y + ringR
            )

            // The empty travel, so the knob reads as a dial even at rest.
            drawArc(
                // Bright enough to see. This was `keyEdge`, which on a near-black cyberpunk
                // ground is invisible — the knobs were drawing correctly and looked absent.
                color = finish.plateDim.copy(alpha = KNOB_TRACK_ALPHA),
                startAngle = KNOB_START,
                sweepAngle = KNOB_SWEEP,
                useCenter = false,
                topLeft = arcRect.topLeft,
                size = arcRect.size,
                style = stroke
            )

            // A bipolar control fills from its centre, so "off" is a position rather than a
            // number — the same rule every other control in the rack follows.
            val from = if (param.bipolar) KNOB_START + KNOB_SWEEP / 2f else KNOB_START
            val to = KNOB_START + KNOB_SWEEP * v
            if (kotlin.math.abs(to - from) > 0.5f) {
                val glow = finish.glow * (if (working) 1f else 0f)
                if (glow > 0f) {
                    drawArc(
                        color = hue.copy(alpha = KNOB_BLOOM_ALPHA * glow),
                        startAngle = minOf(from, to),
                        sweepAngle = kotlin.math.abs(to - from),
                        useCenter = false,
                        topLeft = arcRect.topLeft,
                        size = arcRect.size,
                        style = Stroke(width = KNOB_STROKE * 3f, cap = StrokeCap.Round)
                    )
                }
                drawArc(
                    color = if (working) hue else finish.cap,
                    startAngle = minOf(from, to),
                    sweepAngle = kotlin.math.abs(to - from),
                    useCenter = false,
                    topLeft = arcRect.topLeft,
                    size = arcRect.size,
                    style = stroke
                )
            }

            // The pointer: the one part that actually says which way the knob is facing.
            val a = Math.toRadians((KNOB_START + KNOB_SWEEP * v).toDouble())
            drawLine(
                color = if (working) hue else finish.cap,
                start = Offset(
                    center.x + (ringR - KNOB_POINTER) * cos(a).toFloat(),
                    center.y + (ringR - KNOB_POINTER) * sin(a).toFloat()
                ),
                end = Offset(
                    center.x + (ringR - KNOB_STROKE) * cos(a).toFloat(),
                    center.y + (ringR - KNOB_STROKE) * sin(a).toFloat()
                ),
                strokeWidth = KNOB_STROKE,
                cap = StrokeCap.Round
            )
        }

        Text(
            text = param.name,
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize * 0.9f,
            color = finish.plateDim,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = param.readout(v),
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize * 0.9f,
            color = if (working) hue else finish.plateDim,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/** Classic three-quarter turn: seven o'clock round to five, with the gap at the bottom. */
private const val KNOB_START = 135f
private const val KNOB_SWEEP = 270f

private const val KNOB_SIZE = 58
private const val KNOB_STROKE = 5f
private const val KNOB_RING_INSET = 5f
private const val KNOB_POINTER = 16f
private const val KNOB_BLOOM_ALPHA = 0.34f
private const val KNOB_BLANK_ALPHA = 0.22f
private const val KNOB_TRACK_ALPHA = 0.55f

/**
 * How far the finger travels for the knob's whole range, in pixels.
 *
 * Fixed rather than proportional to the knob: tying it to the drawing would make a small knob
 * twitchy and a large one sluggish, when what the hand actually wants is the same distance every
 * time.
 */
private const val KNOB_TRAVEL_PX = 420f

/**
 * The equaliser as a response curve.
 *
 * **Five vertical bands were never the right picture of an equaliser.** They show five separate
 * numbers when what an equaliser actually is is one shape — the line the sound is bent along —
 * and that shape is the thing you are listening for. A curve says "scooped mids" or "lifted top"
 * at a glance, where five bars say five numbers you have to read and assemble yourself.
 *
 * It also fits where the bands did not. A ring's hole cannot hold five columns, and a full-width
 * row of five is a lot of panel; a curve is happy at any width, gets wider rather than more
 * cramped as the phone grows, and looks like the instrument it belongs to rather than like a
 * settings screen.
 *
 * Dragged directly: the finger picks up the nearest band and moves it, so the gesture is "bend
 * the line here" rather than "find the slider for 910Hz".
 */
@Composable
private fun EqCurve(
    bands: List<EqBand>,
    rangeMb: Pair<Int, Int>,
    onBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    val measurer = rememberTextMeasurer()
    val (minMb, maxMb) = rangeMb
    val span = (maxMb - minMb).coerceAtLeast(1)

    if (bands.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(EQ_CURVE_HEIGHT.dp)
                .pointerInput(bands.size) {
                    detectDragGestures(
                        onDragStart = { }
                    ) { change, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        // Whichever band's column the finger is nearest, so the curve is bent
                        // where it is touched rather than where a hit area happens to be.
                        val i = ((change.position.x / w) * (bands.size - 1))
                            .roundToInt()
                            .coerceIn(0, bands.lastIndex)
                        val unit = (1f - (change.position.y / h)).coerceIn(0f, 1f)
                        onBandChange(bands[i].index, (minMb + unit * span).roundToInt())
                        change.consume()
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val pts = bands.mapIndexed { i, band ->
                val x = w * i / (bands.size - 1).coerceAtLeast(1)
                val unit = ((band.levelMb - minMb).toFloat() / span).coerceIn(0f, 1f)
                Offset(x, h - unit * h)
            }

            // Flat, drawn so the curve has something to be measured against.
            drawLine(
                color = finish.keyEdge,
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 1f
            )

            // A smooth line through the points rather than a dot-to-dot: the ear hears a curve,
            // and straight segments between five samples imply corners that are not there.
            val curve = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val midX = (a.x + b.x) / 2f
                    cubicTo(midX, a.y, midX, b.y, b.x, b.y)
                }
            }

            // Filled down to the flat line, so a lift reads as a hill and a cut as a trough.
            val under = Path().apply {
                addPath(curve)
                lineTo(w, h / 2f)
                lineTo(0f, h / 2f)
                close()
            }
            drawPath(
                path = under,
                brush = Brush.verticalGradient(
                    listOf(
                        finish.accent.copy(alpha = EQ_FILL_ALPHA),
                        finish.accent.copy(alpha = 0f)
                    )
                )
            )

            if (finish.glow > 0f) {
                drawPath(
                    path = curve,
                    color = finish.accent.copy(alpha = EQ_GLOW_ALPHA * finish.glow),
                    style = Stroke(width = EQ_GLOW_WIDTH, cap = StrokeCap.Round)
                )
            }
            drawPath(
                path = curve,
                color = finish.accent,
                style = Stroke(width = EQ_LINE_WIDTH, cap = StrokeCap.Round)
            )

            // A handle on each band, so it is obvious the line can be grabbed.
            pts.forEachIndexed { i, p ->
                drawCircle(color = finish.window, radius = EQ_HANDLE + 1.5f, center = p)
                drawCircle(color = finish.accent, radius = EQ_HANDLE, center = p)

                val laid = measurer.measure(
                    text = AnnotatedString(shortHz(bands[i].centerHz)),
                    style = TextStyle(
                        fontFamily = finish.keyType,
                        fontSize = finish.keyTypeSize * 0.86f,
                        color = finish.plateDim
                    )
                )
                drawText(
                    textLayoutResult = laid,
                    topLeft = Offset(
                        (p.x - laid.size.width / 2f).coerceIn(0f, w - laid.size.width),
                        h - laid.size.height
                    )
                )
            }
        }
    }
}

private const val EQ_CURVE_HEIGHT = 92
private const val EQ_LINE_WIDTH = 3f
private const val EQ_GLOW_WIDTH = 10f
private const val EQ_GLOW_ALPHA = 0.30f
private const val EQ_FILL_ALPHA = 0.22f
private const val EQ_HANDLE = 5f

/** Where a point sits around the ring, in degrees, measured from the ring's centre. */
internal fun angleOf(pos: Offset, centre: Offset): Float =
    Math.toDegrees(atan2((pos.y - centre.y).toDouble(), (pos.x - centre.x).toDouble())).toFloat()

/**
 * How far the finger turned between two angles, signed, taking the short way round.
 *
 * **The whole gesture rests on this and it has exactly one hard case.** Angles come back from
 * `atan2` in −180…180, so a finger crossing the left of the ring goes from 179° to −179° — a
 * step of two degrees that subtracts to −358. Without the wrap the control would slam from one
 * end of its range to the other every time the drag crossed that line, which is the sort of
 * fault that only appears on one side of the dial and looks like a hardware glitch.
 */
internal fun angleDelta(from: Float, to: Float): Float {
    var d = to - from
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return d
}

/** How far round the arc a press has to travel before it counts as a turn rather than a tap. */
private const val SEG_TURN_SLOP_DEGREES = 4f
