package com.jawon.app.ui.wallscene

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * **The record that is on, at the foot of the wall** (0.7.3).
 *
 * It began (0.4) as a 44dp disc and two lines of 9–10sp mono, and it was rarely seen at all:
 * closing a case paused the music and forgot the record, so the bar existed only for music that
 * had been started somewhere else. Closing a case now leaves the record playing (Settings →
 * Playback can make it pause instead, as a disc put back in its box would), so this is the room's
 * one standing control, and it is built as one:
 *
 * - **The track, not just the album.** The song that is playing, in the body face at a size a
 *   6.1" phone can read, with the artist and the record under it.
 * - **Where you are in it**: a hairline along the foot of the card, filling in amber.
 * - **Play/pause and next** as their own targets, so neither opens the case.
 * - **Gestures, as every bar like this has taught people to expect**: tap to open the case (the
 *   wall pans to it first), swipe sideways to change track — the card follows the thumb and the
 *   new title slides in from the side it was flung to — and swipe down to put the record away,
 *   which pauses it and lets its case settle back into its slot.
 *
 * The disc is [DiscArt.bakeFace]'s own bake, the one the room mounts on the spindle, turning at
 * [OpenSequence.SPIN_DEG_PER_S] — the record seen small, not an icon standing in for it.
 *
 * **Cost.** The angle and the playback position are read only inside `graphicsLayer` and
 * `drawWithContent`, so neither recomposes anything (architecture rule 3), and the animated layer
 * is the 46dp disc — ENGINEERING.md trap 14 is about large translucent layers, and this keeps the one
 * that moves every frame small. The drag offsets are read in `graphicsLayer` too.
 */
@Composable
internal fun NowPlayingBar(
    nowPlaying: WallSceneViewModel.NowPlaying,
    trackTitle: String?,
    isPlaying: Boolean,
    durationMs: Long,
    position: State<Long>,
    haptics: RoomHaptics,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPutAway: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What stands at its left in place of the turning disc — the cassette and vinyl rooms show
     * the album's cover, since a CD turning is the CD room's picture of its own record. The
     * vinyl and cassette rooms show this bar at the crate and the racks (2026-11-14): with the
     * record or tape that is on standing only "a little proud" there, nobody found the way back
     * to the deck.
     */
    art: (@Composable () -> Unit)? = null
) {
    val angle = remember { mutableFloatStateOf(0f) }

    // Advances only while the record is actually turning, and keeps its angle when it stops —
    // resuming picks up where it left off rather than snapping to zero, which is what a platter
    // does. Not `rememberInfiniteTransition`, which would go on turning a paused record.
    //
    // **Thirty steps a second, not every vsync** (0.7.5). Each step repaints the app's window and
    // has the whole screen composited again, and at 120Hz that was the busiest thing on a settled
    // wall that the room itself had slowed to a heartbeat. A 46dp disc turning at 78°/s moves 2.6°
    // a step at 30, which reads as the same smooth turn.
    LaunchedEffect(isPlaying, art == null) {
        if (!isPlaying || art != null) return@LaunchedEffect
        var previous = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (previous == 0L) {
                previous = now
                continue
            }
            val elapsed = now - previous
            if (elapsed >= BAR_STEP_NS) {
                angle.floatValue =
                    (angle.floatValue + elapsed / 1_000_000_000f * OpenSequence.SPIN_DEG_PER_S) % 360f
                previous = now
            }
        }
    }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    val widthPx = remember { mutableFloatStateOf(1f) }
    // Which way the last track change went, so the new title arrives from the side the old one
    // left by: +1 forward (it leaves to the left), -1 back.
    val lastStep = remember { mutableIntStateOf(1) }
    val duration by rememberUpdatedState(durationMs)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnPutAway by rememberUpdatedState(onPutAway)

    val changeAtPx = with(density) { CHANGE_TRACK_AT.toPx() }
    val putAwayAtPx = with(density) { PUT_AWAY_AT.toPx() }
    val flingPx = with(density) { FLING_DP_PER_S.dp.toPx() }

    fun step(forward: Boolean) {
        lastStep.intValue = if (forward) 1 else -1
        haptics.detent()
        if (forward) currentOnNext() else currentOnPrevious()
    }

    // The theme's colours are read in composition — they follow the room's light — and the draw
    // lambda below is not composable, so they are taken here.
    val railColor = LabelDim.copy(alpha = 0.18f)
    val fillColor = LampAmber
    val corner = minOf(LocalPalette.current.corner, 16.dp)
    val shape = RoundedCornerShape(corner)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx.floatValue = it.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    translationX = dragX.value
                    translationY = dragY.value
                    alpha = 1f - (dragY.value / (putAwayAtPx * 2.2f)).coerceIn(0f, 0.85f)
                }
                .clip(shape)
                .background(CrateRaised.copy(alpha = 0.96f))
                .border(1.dp, CrateEdge, shape)
                // Where the track has got to: a hairline along the foot of the card. Read here,
                // in the draw phase, so the 2Hz position ticks redraw a line and recompose nothing.
                .drawWithContent {
                    drawContent()
                    val h = 2.dp.toPx()
                    val y = size.height - h
                    drawRect(railColor, Offset(0f, y), Size(size.width, h))
                    val total = duration
                    if (total > 0L) {
                        val f = (position.value.toFloat() / total).coerceIn(0f, 1f)
                        drawRect(fillColor, Offset(0f, y), Size(size.width * f, h))
                    }
                }
                .pointerInput(Unit) {
                    var axis = 0 // 0 undecided, 1 sideways, 2 down
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            axis = 0
                            tracker.resetTracking()
                        },
                        onDrag = { change, amount ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (axis == 0) {
                                axis = if (abs(amount.x) >= abs(amount.y)) 1 else 2
                            }
                            change.consume()
                            scope.launch {
                                if (axis == 1) {
                                    dragX.snapTo(dragX.value + amount.x)
                                } else {
                                    // Only down: up has nothing to give.
                                    dragY.snapTo((dragY.value + amount.y).coerceAtLeast(0f))
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { dragX.animateTo(0f, SETTLE) }
                            scope.launch { dragY.animateTo(0f, SETTLE) }
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            if (axis == 1) {
                                val x = dragX.value
                                val far = abs(x) > changeAtPx || abs(v.x) > flingPx && sign(v.x) == sign(x)
                                scope.launch {
                                    if (far && x != 0f) {
                                        val dir = sign(x)
                                        // Out the side it was flung to, the change, then in from
                                        // the other side — a card dealt, not a list scrolled.
                                        dragX.animateTo(dir * widthPx.floatValue * 0.5f, tween(110))
                                        step(forward = dir < 0f)
                                        dragX.snapTo(-dir * widthPx.floatValue * 0.25f)
                                        dragX.animateTo(0f, SETTLE)
                                    } else {
                                        dragX.animateTo(0f, SETTLE)
                                    }
                                }
                            } else if (axis == 2) {
                                val y = dragY.value
                                scope.launch {
                                    if (y > putAwayAtPx || v.y > flingPx) {
                                        haptics.detent()
                                        dragY.animateTo(putAwayAtPx * 2.2f, tween(140))
                                        currentOnPutAway()
                                        // The bar leaves composition once the record is away;
                                        // this only matters if it does not.
                                        dragY.snapTo(0f)
                                    } else {
                                        dragY.animateTo(0f, SETTLE)
                                    }
                                }
                            }
                        }
                    )
                }
                .clickable(onClick = onOpen)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Next track") { currentOnNext(); true },
                        CustomAccessibilityAction("Previous track") { currentOnPrevious(); true },
                        CustomAccessibilityAction("Put the record away") { currentOnPutAway(); true }
                    )
                }
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val disc = nowPlaying.disc
            if (art != null) {
                Box(Modifier.size(DISC_SIZE).clip(RoundedCornerShape(6.dp))) { art() }
            } else if (disc != null) {
                Image(
                    bitmap = disc.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(DISC_SIZE)
                        .graphicsLayer { rotationZ = angle.floatValue }
                )
            } else {
                // Artwork can still be baking, and a row that changed height when it arrived
                // would shove the chrome down a frame after appearing.
                Spacer(
                    Modifier
                        .size(DISC_SIZE)
                        .clip(CircleShape)
                        .background(CrateEdge)
                )
            }

            AnimatedContent(
                targetState = trackTitle,
                transitionSpec = {
                    val dir = lastStep.intValue
                    (slideInHorizontally(tween(220)) { w -> dir * w / 3 } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(160)) { w -> -dir * w / 3 } + fadeOut(tween(120)))
                },
                modifier = Modifier.weight(1f),
                label = "nowPlayingTitle"
            ) { title ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title ?: nowPlaying.title,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = LabelCream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (title != null) "${nowPlaying.artist} · ${nowPlaying.title}" else nowPlaying.artist,
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = LabelDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Their own targets, deliberately not the row's: pausing or skipping without losing
            // your place on the wall is what a bar like this is for, and making the whole row
            // open the deck would put a case in the way of it.
            IconButton(onClick = onPlayPause, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = LampAmber,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(
                onClick = { step(forward = true) },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next track",
                    tint = LabelCream.copy(alpha = 0.86f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

private val DISC_SIZE = 46.dp

/** The bar's disc steps at most this often: 30 a second. */
private const val BAR_STEP_NS = 33_000_000L

/** How far the card has to travel sideways to change track, if it was not flung. */
private val CHANGE_TRACK_AT = 72.dp

/** How far down it has to be pulled to put the record away. */
private val PUT_AWAY_AT = 44.dp

/** A flick this fast counts however short it was. */
private const val FLING_DP_PER_S = 700f

private val SETTLE = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * An album's cover, small, for [NowPlayingBar]'s `art`: decoded off the main thread (architecture
 * rule 9), a plain square of the bar's edge colour until it is.
 */
@Composable
internal fun CoverThumb(path: String?) {
    val cover by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CaseArt.loadCover(path, COVER_THUMB_PX) }
    }
    val bitmap = cover
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(DISC_SIZE)
        )
    } else {
        Spacer(Modifier.size(DISC_SIZE).background(CrateEdge))
    }
}

private const val COVER_THUMB_PX = 160
