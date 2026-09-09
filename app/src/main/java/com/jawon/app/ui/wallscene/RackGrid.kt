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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LocalDeckFinish
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
            PatchbayRack(
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

        RackLayout.RACK_UNIT -> {
            RackUnit(
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

        // SHELF is the grid below — the original, and still Midnight Shop's.
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
                        // **`available &&` is load-bearing, not defensive.**
                        //
                        // The rack engages every channel at startup, including ones this phone
                        // cannot provide — TONE needs the platform's bass and virtualiser
                        // effects, and those are frequently absent, most reliably when a USB DAC
                        // is attached and the effect cannot bind to that output. An unavailable
                        // cell also has no tap handler at all, because `Cell` only attaches
                        // `pointerInput` when `cell.available`.
                        //
                        // Together those made a switch that showed as ON and could never be
                        // turned off: not a control that failed, a control that was never
                        // wired, drawn as though it were. Deriving the lit state from
                        // availability makes that state unrepresentable rather than merely
                        // unlikely.
                        engaged = cell.available && cell.channel in engaged,
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

    val pulse = selectionPulse(selected)

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
                    finish.accent.copy(alpha = pulse)
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
                // **A slot with no knob behind it draws nothing at all.**
                //
                // This used to draw a faint outline, on the reasoning that an empty rectangle
                // reads as "room the effect does not need" where a filled slab would read as a
                // broken control. It does not. `keyEdge` is `lerp(lit, Black, 0.55f)` — a near
                // black — so under the one-parameter effects it rendered as two dark horizontal
                // lines beneath the fader, and what a person sees there is two controls that are
                // switched off or missing, not absence of a control.
                //
                // The slot still occupies its width, so selecting an effect with fewer
                // parameters does not make the row jump. It simply is not drawn.
                .background(if (param == null) ComposeTransparent else finish.groove, shape)
                .then(
                    if (param == null) Modifier else Modifier.border(1.dp, finish.keyEdge, shape)
                )
                .alpha(if (param == null) 0f else if (live) 1f else BYPASSED_ALPHA)
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

/**
 * The breathing edge of whichever control is selected — and *only* while one is.
 *
 * **An infinite transition runs whether or not anything reads it.** It holds a frame callback
 * open for as long as it is in composition, so the previous shape of this — one declared
 * unconditionally in every key — had a still panel asking Compose for sixty frames a second to
 * animate a value that eight of the nine keys were not looking at. The panel never idled.
 *
 * Declaring it inside the branch is what stops that: Compose discards the group when `selected`
 * goes false and cancels the animation with it, so the clock runs while a key is lit and stops
 * the moment it is not. There is only ever one selected key, so at most one of these is alive.
 */
@Composable
private fun selectionPulse(selected: Boolean): Float =
    if (!selected) 1f else rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = SELECT_PULSE_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SELECT_PULSE_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    ).value

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
        // **Nothing is drawn here, and the previous attempt is why.**
        //
        // This used to draw a hairline, reasoning that a thin rule reads as "no control" where an
        // empty groove would read as a broken one. Both readings were wrong. `keyEdge` is
        // `lerp(lit, Black, 0.55f)` — very nearly black — and at 45% alpha across the full width
        // the result is a hard dark line. Under the filter, which has a single parameter against
        // three slots, that is two black bars sitting beneath the slider, and the owner reported
        // them as exactly that.
        //
        // A slot with no control in it should look like panel. The box stays so the rows below do
        // not move when an effect with fewer parameters is selected; it simply is not painted.
        Box(modifier = modifier.fillMaxWidth().height(1.dp))
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

    // Laid out once per set of labels rather than per frame. Only the typeface and size affect
    // the layout, so switching an effect in or out does not rebuild these — the colour is
    // applied when they are drawn.
    val labels = remember(cells, finish.keyType, finish.keyTypeSize) {
        cells.map { cell ->
            measurer.measure(
                text = AnnotatedString(cell.channel.label),
                style = TextStyle(
                    fontFamily = finish.keyType,
                    fontSize = finish.keyTypeSize,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    // The band lights in sequence when the page opens, one wedge after the next round the arc.
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(SEG_ENTRY_MS, easing = FastOutSlowInEasing),
        label = "entry"
    )

    // **What the gesture reads, without the gesture being restarted to deliver it.**
    //
    // The handler below used to be keyed on `cells`, `engaged`, `selected` and `params`, which
    // looks like the careful thing to do and is the opposite. [RackParam] is a data class
    // holding lambdas, and lambdas allocated fresh each composition never compare equal — so
    // `params` was a new key on *every* recomposition, and turning the band recomposes this on
    // every pointer move by design, since the value being turned is what the wedges are drawn
    // from.
    //
    // A `pointerInput` whose keys change is cancelled and relaunched. Mid-turn that throws away
    // the `awaitEachGesture` currently tracking the finger, and the replacement waits on
    // `awaitFirstDown` — which a finger already touching the glass will never send again. The
    // turn dies and only a fresh press revives it.
    //
    // Keyed on `Unit` and read through `rememberUpdatedState`, the handler is set up once, lives
    // for as long as the ring is on screen, and still sees the newest value on every move.
    val liveKnob = rememberUpdatedState(knob)
    val liveCells = rememberUpdatedState(cells)
    val liveEngaged = rememberUpdatedState(engaged)
    val liveSelected = rememberUpdatedState(selected)
    val liveToggle = rememberUpdatedState(onToggle)
    val liveSelect = rememberUpdatedState(onSelect)

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
                    .pointerInput(Unit) {
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
                                    liveKnob.value?.let {
                                        it.onChange(
                                            (it.value + step / SEG_SWEEP).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                            }

                            if (turning) {
                                liveKnob.value?.onCommit?.invoke()
                            } else {
                                // Never travelled far enough to be a turn, so it was a press.
                                val hit = segmentAt(
                                    pos = down.position,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                    inner = inner
                                )
                                val cell = liveCells.value.getOrNull(hit)
                                if (cell != null && cell.available) {
                                    val isIn = cell.channel in liveEngaged.value
                                    val toggle = liveToggle.value
                                    val select = liveSelect.value
                                    when {
                                        !isIn -> { toggle(cell.channel); select(cell.channel) }
                                        cell.channel == liveSelected.value -> toggle(cell.channel)
                                        else -> select(cell.channel)
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

                    val isIn = cell.available && cell.channel in engaged
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
                    // **No pulse in here, deliberately.**
                    //
                    // The selected wedge used to breathe, which meant reading an animated value
                    // inside this draw — and that invalidates the whole band sixty or a hundred
                    // and twenty times a second for ever, redrawing eight paths, their blooms
                    // and their labels even when nothing is happening. It is the difference
                    // between a panel that costs something while you use it and one that costs
                    // something the entire time it is open.
                    //
                    // Selection is already unmistakable from the brighter rim and the name in
                    // the hole; the breathing moved to that name, where it animates one small
                    // layer instead of the whole instrument.
                    val heat = if (isIn) finish.glow * lit else 0f
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
                    // Laid out once and reused, not measured here. Eight labels measured inside
                    // a draw that runs every frame is around a thousand text layouts a second,
                    // and text layout is one of the more expensive things in the toolkit — it
                    // was most of what made this page cost two and a half times the wall.
                    // The colour is applied at draw time so the cache does not have to be
                    // rebuilt when an effect is switched in or out.
                    val laid = labels[i]
                    drawText(
                        textLayoutResult = laid,
                        color = if (isIn) finish.keyInk else finish.keyDim,
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
            //
            // This is where the breathing lives now. It is one short string in its own layer, so
            // the animation costs a layer's alpha per frame instead of redrawing eight wedges,
            // their blooms and their labels — and read inside `graphicsLayer` it recomposes
            // nothing at all. Only while something is selected: an idle panel should be still.
            // Declared inside the branch rather than gated at the point of use. An infinite
            // transition holds a frame callback open for as long as it is in composition, so
            // one sitting here unconditionally kept Compose ticking over a still panel with
            // nothing selected and nothing to animate. Null when idle; the clock stops with it.
            //
            // Not [selectionPulse], because that returns the value and reading a value in the
            // body is the thing this must not do — see the note below about the layer.
            val alive = if (selected == null) null else {
                rememberInfiniteTransition(label = "alive").animateFloat(
                    initialValue = SELECT_PULSE_LOW,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(SELECT_PULSE_MS, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "alive"
                )
            }
            Text(
                text = selected?.label ?: "—",
                fontFamily = finish.display,
                fontSize = finish.displaySize,
                color = finish.displayInk,
                letterSpacing = finish.displayTracking,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = alive?.value ?: 1f }
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

// ---- the rack unit -------------------------------------------------------------------

/**
 * One wide machine, bolted into a frame.
 *
 * **This layout was specified and never built.** The dispatcher only had cases for the ring and
 * the modular, so Cold Storage asked for `RACK_UNIT` and silently got Midnight Shop's grid — it
 * has been wearing another mode's face for its whole life, which is the real reason it read as
 * the least cared-for of the four.
 *
 * Where the ring is round and the modular is a row of tall narrow boxes, this is a single piece
 * of studio outboard: ears and screws at both ends, the maker's marks silkscreened across the
 * top, switches banked in the middle and the selected effect's parameters as *horizontal* travel
 * underneath. Horizontal is the whole tell — every other layout in this app runs its faders up
 * and down, and a rack unit is the one machine shape where they lie flat.
 *
 * **Two rows of four rather than one row of eight**, which is the one place the drawing lost an
 * argument with the hand. Eight across a phone leaves each switch under 40dp, and this project
 * has already made the controls-too-small mistake once, on the ring. A 2U unit with two banks is
 * just as real a piece of gear and keeps every switch at a thumb's width.
 */
@Composable
private fun RackUnit(
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
    val params = cells.firstOrNull { it.channel == selected }?.params.orEmpty()
    val hue = cells.firstOrNull { it.channel == selected }?.hue ?: finish.accent

    // **`IntrinsicSize.Min`, and the ears do not work without it.**
    //
    // They ask for `fillMaxHeight`, which resolves against the incoming maximum — and inside a
    // wrap-content Row that maximum is unbounded, so the request means nothing and the flanges
    // collapse to whatever their two screws need. Measuring the row's intrinsic height first
    // gives them a real number to fill, and the ears then run the full depth of the panel the
    // way a mounting flange has to.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(finish.plateLow)
            .border(1.dp, finish.plateEdge)
    ) {
        RackEar(finish)

        Column(modifier = Modifier.weight(1f).padding(vertical = UNIT_PAD.dp)) {

            // The maker's marks. A rack unit is the one piece of gear whose front panel is
            // mostly print, and putting the model number where a real one carries it does more
            // for the illusion than any amount of shading.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = finish.brand,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = UNIT_MARK_SIZE,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = finish.plateDim
                )
                Text(
                    text = UNIT_MODEL,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = UNIT_MARK_SIZE,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = finish.plateDim
                )
            }

            cells.chunked(4).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = UNIT_GAP.dp),
                    horizontalArrangement = Arrangement.spacedBy(UNIT_GAP.dp)
                ) {
                    row.forEachIndexed { colIndex, cell ->
                        UnitSwitch(
                            cell = cell,
                            order = rowIndex * 4 + colIndex,
                            arrived = arrived,
                            engaged = cell.available && cell.channel in engaged,
                            selected = cell.channel == selected,
                            onToggle = { onToggle(cell.channel) },
                            onSelect = { onSelect(cell.channel) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // A rule under the switches, the way a panel is divided into a switching section and
            // a setting section. One line, and it does the work a gap of twice the size would.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp, bottom = 10.dp)
                    .height(1.dp)
                    .background(finish.plateEdge)
            )

            if (selected == RackChannel.EQ) {
                // The equaliser is a shape, not three numbers — same argument as everywhere else
                // it appears, and the curve is already built.
                EqCurve(
                    bands = eqBands,
                    rangeMb = eqRangeMb,
                    onBandChange = onEqBandChange
                )
            } else {
                repeat(UNIT_PARAM_SLOTS) { i ->
                    UnitParam(
                        param = params.getOrNull(i),
                        hue = hue,
                        live = selected != null && selected in engaged
                    )
                }
            }
        }

        RackEar(finish)
    }
}

/** The mounting flange: a narrow strip with a screw top and bottom, at both ends of the unit. */
@Composable
private fun RackEar(finish: DeckFinish) {
    Column(
        modifier = Modifier
            .width(UNIT_EAR.dp)
            .fillMaxHeight()
            .background(finish.plate)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        repeat(2) {
            // A slotted head, not a dot. One line across a five-point circle is almost nothing
            // to draw and it is the difference between a screw and a hole — the eye reads the
            // slot as a fastener without ever being asked to look at it, which is the whole
            // point of detail at this size.
            Canvas(modifier = Modifier.size(UNIT_SCREW.dp)) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = finish.plateEdge, radius = r, center = c)
                drawLine(
                    color = finish.plateLow,
                    start = Offset(c.x - r * 0.55f, c.y),
                    end = Offset(c.x + r * 0.55f, c.y),
                    strokeWidth = SCREW_SLOT.toPx()
                )
            }
        }
    }
}

/**
 * One channel switch. Flatter and harder than the grid's key, because this panel is metal.
 */
@Composable
private fun UnitSwitch(
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
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press"
    )
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(ENTRY_MS, order * ENTRY_STAGGER_MS, FastOutSlowInEasing),
        label = "entry"
    )
    val pulse = selectionPulse(selected)

    Box(
        modifier = modifier
            .height(UNIT_SWITCH_H.dp)
            .graphicsLayer {
                scaleY = press.value
                alpha = entry.value
                translationY = (1f - entry.value) * ENTRY_RISE_PX
            }
            // A lamp behind the legend, which is how a switched-in channel announces itself on
            // real gear: the button does not change colour, the light behind it comes on.
            //
            // A gradient rather than a block of flat alpha. The first version filled the bottom
            // 55% at a constant strength, which draws a hard horizontal line straight across the
            // middle of every lit switch — the same mistake the patchbay's first bloom made, and
            // just as visible once you know to look. A lamp sitting under the legend falls off
            // upward and has no edge anywhere.
            .drawBehind {
                if (engaged && finish.glow > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                cell.hue.copy(alpha = UNIT_LAMP_ALPHA * finish.glow)
                            ),
                            startY = size.height * 0.25f,
                            endY = size.height
                        )
                    )
                }
            }
            .background(if (engaged) finish.key else finish.plateLow)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) cell.hue.copy(alpha = pulse) else finish.keyEdge
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
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = cell.channel.label,
            fontFamily = finish.keyType,
            fontSize = finish.keyTypeSize,
            fontWeight = FontWeight.Bold,
            color = if (engaged) finish.keyInk else finish.keyDim,
            maxLines = 1
        )
    }
}

/**
 * One parameter, lying flat.
 *
 * The horizontal travel is the rack unit's signature — everything else in this app runs its
 * faders vertically — and it is also the most comfortable control in the app by a distance,
 * because it gets the full width of the panel for its throw instead of a third of it.
 *
 * One gesture handler for press and drag together, so a tap sets the value where you touched
 * rather than requiring the cap to be picked up first. Same shape as the transport groove.
 */
@Composable
private fun UnitParam(param: RackParam?, hue: Color, live: Boolean) {
    val finish = LocalDeckFinish.current

    if (param == null) {
        // A blank slot still occupies its height, so selecting an effect with fewer controls
        // does not make the whole panel jump up the screen.
        Box(modifier = Modifier.fillMaxWidth().height(UNIT_PARAM_H.dp))
        return
    }

    val liveParam = rememberUpdatedState(param)

    Column(modifier = Modifier.fillMaxWidth().height(UNIT_PARAM_H.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = param.name.uppercase(Locale.ROOT),
                fontFamily = JetBrainsMonoFamily,
                fontSize = UNIT_MARK_SIZE,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = finish.plateDim
            )
            Text(
                text = param.readout(param.value),
                fontFamily = JetBrainsMonoFamily,
                fontSize = UNIT_MARK_SIZE,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = if (live) hue else finish.plateDim
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(UNIT_TRACK_TOUCH.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        liveParam.value.onChange((down.position.x / width).coerceIn(0f, 1f))
                        down.consume()
                        drag(down.id) { change ->
                            liveParam.value.onChange(
                                (change.position.x / width).coerceIn(0f, 1f)
                            )
                            change.consume()
                        }
                        liveParam.value.onCommit?.invoke()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val midY = size.height / 2f
                val h = UNIT_TRACK.toPx()
                val x = size.width * param.value.coerceIn(0f, 1f)

                drawRect(
                    color = finish.groove,
                    topLeft = Offset(0f, midY - h / 2f),
                    size = Size(size.width, h)
                )

                // Bipolar controls fill out from the middle rather than from the left, because
                // that is where their zero is and a bar growing from the wrong end lies about
                // what the control is doing.
                val from = if (param.bipolar) size.width / 2f else 0f
                if (live) {
                    if (finish.glow > 0f) {
                        drawRect(
                            color = hue.copy(alpha = UNIT_TRACK_BLOOM * finish.glow),
                            topLeft = Offset(minOf(from, x), midY - h * 1.9f),
                            size = Size(abs(x - from), h * 3.8f)
                        )
                    }
                    drawRect(
                        color = hue,
                        topLeft = Offset(minOf(from, x), midY - h / 2f),
                        size = Size(abs(x - from), h)
                    )
                }

                // The cap, with the moulded line across it that every fader knob has. It is the
                // detail that says which way the control travels, and without it the cap is a
                // rectangle that happens to be sitting on a groove.
                val capW = UNIT_CAP_W.toPx()
                val capH = UNIT_CAP_H.toPx()
                val capX = (x - capW / 2f).coerceIn(0f, size.width - capW)
                drawRect(
                    color = if (live) finish.cap else finish.keyEdge,
                    topLeft = Offset(capX, midY - capH / 2f),
                    size = Size(capW, capH)
                )
                drawLine(
                    color = finish.plateLow,
                    start = Offset(capX, midY),
                    end = Offset(capX + capW, midY),
                    strokeWidth = CAP_LINE.toPx()
                )
            }
        }
    }
}

/** Square, because a rack panel is punched and folded rather than moulded. */
private const val UNIT_EAR = 16
private const val UNIT_SCREW = 5
private const val UNIT_PAD = 12
private const val UNIT_GAP = 4
private const val UNIT_SWITCH_H = 42
private const val UNIT_PARAM_H = 52
private const val UNIT_TRACK_TOUCH = 30
private const val UNIT_PARAM_SLOTS = 3
private val UNIT_MARK_SIZE = 8.sp
private val UNIT_TRACK = 4.dp
private val UNIT_CAP_W = 5.dp
private val UNIT_CAP_H = 18.dp
private val CAP_LINE = 1.dp
private val SCREW_SLOT = 1.dp
private const val UNIT_LAMP_ALPHA = 0.30f
private const val UNIT_TRACK_BLOOM = 0.20f

/** Printed where a real unit carries its part number. Eight channels, one rack space. */
private const val UNIT_MODEL = "CS-8 EIGHT CHANNEL"

// ---- the patchbay --------------------------------------------------------------------

/**
 * Sockets on top, a console underneath.
 *
 * **The modular was replaced rather than tuned.** Eurorack modules of differing widths is a good
 * idea on a rack and a bad one on a phone: the widths that were supposed to say how complicated
 * each effect is came out as a row of narrow boxes, all of them too small to say anything, and
 * on absolute black with square corners there was nothing left but outlines.
 *
 * This is two ideas that each work at phone size, doing separate jobs. Eight jack points choose
 * what you are working on — a socket is a *hole*, which is the one shape that reads instantly on
 * a black panel, and a patched one lights its ring and blooms, which is exactly what a mode with
 * `glow` at 0.9 is built to do. Underneath, the selected effect prints itself as terminal
 * output: its state, then a line per control with a bar made of discrete cells.
 *
 * Discrete is the point. Every other layout in this app slides continuously; a console steps,
 * character by character, because that is what a console can do — and it is the one place where
 * a coarse control is *more* satisfying rather than less, because the stepping is the machine
 * showing its resolution rather than failing to have any.
 *
 * The equaliser is the stated exception and stays a curve. Five numbers printed as five bars is
 * the least useful possible view of a filter shape, and the curve already exists.
 */
@Composable
private fun PatchbayRack(
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
    val cell = cells.firstOrNull { it.channel == selected }
    val params = cell?.params.orEmpty()
    val live = selected != null && selected in engaged

    // **The mode's colours, not the grid's eight.**
    //
    // `RackCell.hue` is the palette the original grid used — eight unrelated colours, one per
    // effect — and reading it here is what made Workshop's sockets come out orange and blue and
    // violet in a room whose entire premise is that the only light in it is phosphor. The ring
    // solved this already: ramp between the mode's own two accents across the bank, so the
    // channels stay distinguishable without any of them leaving the mode.
    //
    // On Workshop those two are `#00FF41` and `#00A82B`, so the whole patchbay is green and only
    // its brightness varies — which is what a rack of identical jacks lit by one phosphor
    // actually looks like.
    fun hueAt(index: Int): Color = lerp(
        finish.accent,
        finish.accentB,
        index / (cells.size - 1).coerceAtLeast(1).toFloat()
    )

    val hue = selected
        ?.let { s -> hueAt(cells.indexOfFirst { it.channel == s }.coerceAtLeast(0)) }
        ?: finish.accent

    // **Where the light is drawn, settled once and for all.**
    //
    // Three attempts failed the same way. A bloom drawn inside the socket's own canvas is cut at
    // the socket's edge; moved to the row's canvas it is cut at the row's edge, which is worse
    // vertically than horizontally because a row is only about seventy points tall and the halo
    // is nearly eighty. Padding the row buys room at a cost this page cannot pay — it does not
    // scroll, and the third control of a three-knob effect was already going over the side.
    //
    // So the geometry is measured instead of assumed. Each socket reports where it actually
    // landed, and every bloom is drawn on one canvas spanning the entire rack — header, both
    // banks and the console. The light then has the whole panel to fall off in, spills across
    // the divider and under the console text the way light in a dark box does, and the nearest
    // edge is far enough away that the shaped ramp has nothing left when it arrives.
    //
    // It also costs no layout height at all, which is what every previous attempt got wrong.
    val bank = remember { mutableStateMapOf<RackChannel, Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {

    Canvas(modifier = Modifier.matchParentSize()) {
        if (finish.glow <= 0f) return@Canvas
        val r = PATCH_SOCKET.dp.toPx() / 2f
        cells.forEachIndexed { index, c ->
            if (!c.available || c.channel !in engaged) return@forEachIndexed
            val centre = bank[c.channel] ?: return@forEachIndexed
            drawNeon(centre = centre, radius = r, hue = hueAt(index), strength = finish.glow)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        Text(
            text = PATCH_HEADER,
            fontFamily = finish.display,
            fontSize = PATCH_TYPE,
            color = finish.plateDim,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 5.dp)
        )

        cells.chunked(4).forEachIndexed { rowIndex, row ->
            // **Every bloom in the row is drawn on one canvas behind all of them.**
            //
            // Drawing a socket's glow inside the socket's own 46dp canvas is what put the hard
            // vertical edges down the left and right of the bank: a Canvas clips to its bounds,
            // and a halo two and a half times the rim's radius is simply cut off where its cell
            // ends. Widening the cell only moves the edge.
            //
            // This project has solved this before — the ring's studs had the identical fault and
            // the answer was the same one: light belongs to the panel, not to the control, so it
            // is drawn on a surface the size of the panel. The sockets on top then draw only the
            // things that really are theirs, the rim and the hole.
            // Sideways inset only: left and right is where a socket is genuinely against the
            // edge of the panel. Vertically there is now the whole rack to spill into.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PATCH_INSET.dp)
                    .padding(bottom = PATCH_ROW_GAP.dp),
                horizontalArrangement = Arrangement.spacedBy(PATCH_GAP.dp)
            ) {
                row.forEachIndexed { colIndex, c ->
                    val order = rowIndex * 4 + colIndex
                    Socket(
                        cell = c,
                        hue = hueAt(order),
                        order = order,
                        arrived = arrived,
                        engaged = c.available && c.channel in engaged,
                        selected = c.channel == selected,
                        onToggle = { onToggle(c.channel) },
                        onSelect = { onSelect(c.channel) },
                        onCentre = { bank[c.channel] = it - origin },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 9.dp)
                .height(1.dp)
                .background(finish.keyEdge)
        )

        if (selected == null) {
            ConsoleLine(text = PATCH_IDLE, color = finish.plateDim)
            return@Column
        }

        // The status line, and the on/off switch. Printed the way a console prints state, and
        // tappable, so the thing that says whether an effect is in circuit is also the thing
        // that puts it there.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .clickable { onToggle(selected) }
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ConsoleLine(
                text = "> " + selected.label.uppercase(Locale.ROOT),
                color = if (live) hue else finish.plateDim,
                glow = live
            )
            ConsoleLine(
                text = if (live) "[ ON  ]" else "[ OFF ]",
                color = if (live) hue else finish.plateDim,
                glow = live
            )
        }

        if (selected == RackChannel.EQ) {
            EqCurve(
                bands = eqBands,
                rangeMb = eqRangeMb,
                onBandChange = onEqBandChange,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            params.forEach { param ->
                ConsoleParam(param = param, hue = hue, live = live)
            }
        }
    }

    }
}

/**
 * One jack point.
 *
 * A ring, a hole, and a legend under it. Unpatched it is a dark circle with a dim rim — a hole
 * in the panel. Patched, the rim lights and throws a bloom past itself, which on absolute black
 * is the entire difference between a socket and a lamp, and is why this layout belongs to the
 * mode whose glow is turned nearly all the way up.
 *
 * Round holes against square corners is also the contrast the modular never had: that panel was
 * rectangles inside rectangles, so nothing on it had a silhouette.
 */
@Composable
private fun Socket(
    cell: RackCell,
    hue: Color,
    order: Int,
    arrived: Boolean,
    engaged: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    /** Reports this jack's centre in root coordinates, so the panel can light it. */
    onCentre: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press"
    )
    // **Workshop's own entry, rather than the one the other modes use.**
    //
    // Every other layout arrives by rising a few points and fading in, which is a *physical*
    // gesture: an object being set down. It is right for a shop and a rack unit, and it is
    // wrong here, because nothing in this room is an object. There is no lamp in the fiction of
    // Workshop and no daylight — only equipment making its own light — so the honest entry is
    // not something being placed, it is a channel being energised.
    //
    // So the sockets do not move at all. They come up as phosphor comes up: a hard strike, a
    // gutter, then the settle, staggered down the bank so it reads as eight channels coming
    // online in sequence rather than a panel appearing. Same language as the intro's byline,
    // which is deliberate — this app has exactly one idea about how light switches on and it
    // should be recognisable wherever it happens.
    //
    // `keyframes` rather than a chain of `animateTo`, because this is declarative state rather
    // than a coroutine: `arrived` can go false again and the animation has to be able to run
    // backwards without a script half-finished somewhere.
    val entry = animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = keyframes {
            durationMillis = STRIKE_MS
            0f at 0
            0.9f at 70
            0.15f at 110
            1f at 165
            0.45f at 200
            1f at STRIKE_MS
            delayMillis = order * STRIKE_STAGGER_MS
        },
        label = "strike"
    )
    val pulse = selectionPulse(selected)

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
                alpha = entry.value
                // No rise. See the note above: this panel switches on, it does not arrive.
            }
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
                } else Modifier
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(PATCH_SOCKET.dp)
                .onGloballyPositioned {
                    onCentre(
                        it.positionInRoot() +
                            Offset(it.size.width / 2f, it.size.height / 2f)
                    )
                }
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension / 2f - PATCH_RING.toPx() / 2f

            // The bloom is not drawn here — it belongs to the panel behind the whole bank, so a
            // halo wider than one cell is not sliced off at that cell's edge. See the parent
            // canvas in [PatchbayRack].

            // The hole itself. Lit from inside when patched, because a socket with a glow around
            // it and a dead black centre reads as a ring rather than as a light source.
            drawCircle(color = finish.groove, radius = outer, center = c)
            drawCircle(
                color = if (engaged) hue.copy(alpha = PATCH_HOLE_ALPHA) else finish.plateLow,
                radius = outer * PATCH_HOLE,
                center = c
            )

            // The rim. Thicker and breathing when this is the one being worked on.
            drawCircle(
                color = when {
                    selected -> hue.copy(alpha = pulse)
                    engaged -> hue
                    else -> finish.keyEdge
                },
                radius = outer,
                center = c,
                style = Stroke(
                    width = if (selected) PATCH_RING.toPx() * 1.7f else PATCH_RING.toPx()
                )
            )
        }

        Text(
            text = cell.channel.label,
            fontFamily = finish.keyType,
            fontSize = PATCH_TYPE,
            fontWeight = FontWeight.Bold,
            color = if (engaged) finish.keyInk else finish.keyDim,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

/** A line of console output, in the mode's display face. */
@Composable
private fun ConsoleLine(
    text: String,
    color: Color,
    glow: Boolean = false,
    modifier: Modifier = Modifier
) {
    val finish = LocalDeckFinish.current
    Text(
        text = text,
        fontFamily = finish.display,
        fontSize = PATCH_TYPE,
        letterSpacing = 1.sp,
        color = color,
        maxLines = 1,
        modifier = modifier,
        style = if (glow && finish.glow > 0f) {
            TextStyle(
                shadow = Shadow(
                    color = color.copy(alpha = CONSOLE_GLOW_ALPHA * finish.glow),
                    offset = Offset.Zero,
                    blurRadius = CONSOLE_GLOW_RADIUS
                )
            )
        } else TextStyle()
    )
}

/**
 * One control, printed as a console bar.
 *
 * The bar is [CONSOLE_CELLS] discrete cells rather than a continuous fill, which is what makes
 * this read as output from a machine instead of as a slider wearing a costume. Dragging still
 * maps to the whole width — the value underneath is continuous and only the drawing steps — so
 * this is no coarser to *use* than any other control in the app.
 *
 * One gesture handler taking press and drag together, so a tap lands the value where you touched.
 */
@Composable
private fun ConsoleParam(param: RackParam, hue: Color, live: Boolean) {
    val finish = LocalDeckFinish.current
    val liveParam = rememberUpdatedState(param)
    val ink = if (live) hue else finish.plateDim

    // **One line, which is both the point and the fix.**
    //
    // A console prints a control as a single row — name, bar, value — and the first version
    // stacked them, which cost twenty-two points of height per control for no gain in
    // legibility. Three controls of that is sixty-six points, and this page does not scroll:
    // `RackGrid` sits in a centred column with no overflow handling, so anything taller than
    // the page bleeds out of both ends and the bottom control ends up under the transport.
    // That is the overlap, and this is the reason it is gone.
    //
    // The name and value are fixed widths so every row's bar starts and ends on the same two
    // columns. Ragged bars would undo the whole illusion — a console is monospaced all the way
    // down or it is not a console.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CONSOLE_ROW_GAP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(CONSOLE_NAME_W.dp)) {
            ConsoleLine(text = param.name.uppercase(Locale.ROOT), color = finish.plateDim)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(CONSOLE_TOUCH.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        liveParam.value.onChange((down.position.x / width).coerceIn(0f, 1f))
                        down.consume()
                        drag(down.id) { change ->
                            liveParam.value.onChange(
                                (change.position.x / width).coerceIn(0f, 1f)
                            )
                            change.consume()
                        }
                        liveParam.value.onCommit?.invoke()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gap = CONSOLE_CELL_GAP.toPx()
                val cellW = (size.width - gap * (CONSOLE_CELLS - 1)) / CONSOLE_CELLS
                val h = CONSOLE_BAR.toPx()
                val midY = size.height / 2f
                val value = param.value.coerceIn(0f, 1f)

                // Bipolar controls fill from the middle out, because that is where their zero is.
                val lit = (value * CONSOLE_CELLS).toInt().coerceIn(0, CONSOLE_CELLS)
                val centre = CONSOLE_CELLS / 2
                for (i in 0 until CONSOLE_CELLS) {
                    val on = if (param.bipolar) {
                        if (lit >= centre) i in centre until lit else i in lit until centre
                    } else {
                        i < lit
                    }
                    val x = i * (cellW + gap)
                    if (on && live && finish.glow > 0f) {
                        drawRect(
                            color = hue.copy(alpha = CONSOLE_BLOOM * finish.glow),
                            topLeft = Offset(x - gap, midY - h * 1.6f),
                            size = Size(cellW + gap * 2f, h * 3.2f)
                        )
                    }
                    drawRect(
                        color = if (on) ink else finish.groove,
                        topLeft = Offset(x, midY - h / 2f),
                        size = Size(cellW, h)
                    )
                }
            }
        }

        // Right-aligned in a fixed column, so the numbers form a straight edge down the panel
        // however many digits each of them happens to need.
        Box(modifier = Modifier.width(CONSOLE_VALUE_W.dp)) {
            ConsoleLine(
                text = param.readout(param.value),
                color = ink,
                glow = live,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

private const val PATCH_SOCKET = 46
private const val PATCH_GAP = 6
private const val PATCH_ROW_GAP = 2

/** Room around the socket bank for its light to fall off in, before any edge. */
private const val PATCH_INSET = 14
private val PATCH_RING = 2.dp
private val PATCH_TYPE = 10.sp

private const val PATCH_HOLE = 0.52f
private const val PATCH_HOLE_ALPHA = 0.30f

private const val CONSOLE_CELLS = 24
private val CONSOLE_CELL_GAP = 2.dp
private val CONSOLE_BAR = 9.dp
private const val CONSOLE_TOUCH = 30
private const val CONSOLE_ROW_GAP = 6
private const val CONSOLE_NAME_W = 62
private const val CONSOLE_VALUE_W = 52
private const val CONSOLE_BLOOM = 0.20f
private const val CONSOLE_GLOW_ALPHA = 0.7f
private const val CONSOLE_GLOW_RADIUS = 11f

private const val PATCH_HEADER = "PATCH - 8 POINT"
private const val PATCH_IDLE = "  no point selected"

/**
 * A lit tube, in four layers.
 *
 * **The thing that was missing is the white.** Two radial ramps of the effect's own colour is a
 * better glow than two flat discs, but it is still not neon, and the reason is physical: a tube
 * bright enough to throw light is *over* the display's range at its centre, so the middle of it
 * clips to white and only the falloff carries the hue. A glow that is the same green all the way
 * to its centre reads as a green sticker no matter how nicely it fades — the eye takes
 * desaturation-toward-white as the signal for "very bright", and nothing else substitutes.
 *
 * So, outward from the middle:
 *
 * 1. a small near-white core, which is the part that is blowing out,
 * 2. the saturated hue at full strength around it, which is the tube's own colour,
 * 3. a wide halo ramping to nothing, which is the light in the air,
 * 4. and a faint outer bloom far past all of it, which is what stops the halo having an end.
 *
 * They are drawn back to front and translucent, so the overlaps accumulate the way real light
 * does — brightest where every layer coincides, and falling away in steps too soft to count.
 */
private fun DrawScope.drawNeon(
    centre: Offset,
    radius: Float,
    hue: Color,
    strength: Float
) {
    // **The falloff is shaped, not linear, and that is what kills the visible edge.**
    //
    // A two-stop radial gradient ramps straight from full to nothing, so at three quarters of
    // its radius it is still at a quarter of its strength — and wherever the drawing surface
    // ends, that quarter becomes a hard line. Insetting the sockets and widening the canvas only
    // moves where the line falls; it cannot remove it, because there is always an edge somewhere
    // and the light is still bright when it arrives there.
    //
    // Extra stops bend the ramp so almost all of the light is spent near the middle and the tail
    // is imperceptible long before the radius runs out. That is also closer to what light does —
    // intensity falls off with the square of distance, not linearly — so it reads better *and*
    // it cannot be clipped visibly, whatever it is drawn on.
    fun ramp(r: Float, a: Float) = Brush.radialGradient(
        colorStops = arrayOf(
            0.00f to hue.copy(alpha = a),
            0.30f to hue.copy(alpha = a * 0.34f),
            0.60f to hue.copy(alpha = a * 0.08f),
            0.82f to hue.copy(alpha = a * 0.015f),
            1.00f to Color.Transparent
        ),
        center = centre,
        radius = r
    )

    // The outer air. Very faint and very wide, so the glow has no edge anywhere.
    val outer = radius * NEON_OUTER
    drawCircle(brush = ramp(outer, NEON_OUTER_A * strength), radius = outer, center = centre)

    // The halo. This is the layer doing most of the visible work.
    val halo = radius * NEON_HALO
    drawCircle(brush = ramp(halo, NEON_HALO_A * strength), radius = halo, center = centre)

    // The tube's own colour, at its own strength, close in.
    val body = radius * NEON_BODY
    drawCircle(brush = ramp(body, NEON_BODY_A * strength), radius = body, center = centre)

    // The blown-out centre. Mixed toward white rather than set to it, so a green tube still
    // reads as green at its heart and merely as *too bright to have a colour* at the very middle.
    val core = radius * NEON_CORE
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                lerp(hue, Color.White, NEON_WHITEOUT).copy(alpha = NEON_CORE_A * strength),
                hue.copy(alpha = NEON_CORE_A * 0.5f * strength),
                Color.Transparent
            ),
            center = centre,
            radius = core
        ),
        radius = core,
        center = centre
    )
}

/** Radii, as multiples of the socket's own. */
private const val NEON_OUTER = 3.4f
private const val NEON_HALO = 2.3f
private const val NEON_BODY = 1.25f
private const val NEON_CORE = 0.72f

/**
 * Alphas. Low individually and cumulative in practice: four translucent layers over one another
 * is what builds the ramp, and any single one of them at a strength that looked right alone
 * would flatten the others out.
 */
private const val NEON_OUTER_A = 0.17f
private const val NEON_HALO_A = 0.36f
private const val NEON_BODY_A = 0.44f
private const val NEON_CORE_A = 0.85f

/** How far the core is pulled toward white. Most of the way, but not all — see [drawNeon]. */
private const val NEON_WHITEOUT = 0.62f

/**
 * How a Workshop channel strikes on, and how far apart the eight of them are.
 *
 * The stagger is wide enough that the bank reads as a sequence rather than a shimmer — eight
 * channels coming online one after another, which is what the whole layout is pretending to be —
 * and the total for the last of them still lands under a second.
 */
private const val STRIKE_MS = 260
private const val STRIKE_STAGGER_MS = 55
