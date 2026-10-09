package com.jawon.app.ui.pocketscene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.InterFamily
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/** The height Cover Flow is laid out in — the LCD's — so the same drawing serves both. */
private const val LAYOUT_H = 475f

/** Covers drawn either side of the middle one, sideways: the wider screen shows more of the row. */
private const val SIDE_WINDOW = 6

/**
 * **Cover Flow, sideways** (0.7.3) — the iPod nano's own gesture: turn the phone on its side and
 * the covers fill the screen; turn it back and you are where you were on the player.
 *
 * The activity stays in portrait (turning it would rebuild every room), so this is drawn turned
 * to meet the phone: a box the size of the screen with its sides swapped, rotated a quarter turn
 * the way the phone went ([holding]). Compose carries touches through that rotation, so a swipe
 * along the covers is a swipe along the covers whichever way the phone is held.
 *
 * The covers are the device's own drawing — [CoverFlowTools], the same perspective, floor and
 * reflections — laid out at the LCD's height and scaled up to the screen's, from covers decoded
 * at a size that stays sharp there ([art]).
 *
 * **Swiped**, with the throw carrying it on and settling on a cover; **a tap on the middle cover
 * turns it over** to its tracks, and a tap on a track plays it; a tap on a side cover brings it to
 * the middle. Every cover that passes the middle clicks and ticks, as the wheel does.
 */
@Composable
internal fun SidewaysCoverFlow(
    holding: Holding,
    albums: List<PocketLibrary.Album>,
    startAt: Int,
    art: (String) -> ImageBitmap?,
    trackTitles: Map<String, List<String>>,
    playingAlbumId: String?,
    playingIndex: Int,
    onCentre: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String, Int) -> Unit,
    onTick: () -> Unit
) {
    val last = (albums.size - 1).coerceAtLeast(0)
    val flow = remember { Animatable(startAt.coerceIn(0, last).toFloat()) }
    val flip = remember { Animatable(0f) }
    var flipped by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tools = remember { CoverFlowTools() }
    val currentOnCentre by rememberUpdatedState(onCentre)
    val currentOnTick by rememberUpdatedState(onTick)

    // A click and a tick for every cover that crosses the middle, and the room told where it is
    // so it can decode the covers around it.
    LaunchedEffect(Unit) {
        var shown = flow.value.roundToInt()
        currentOnCentre(shown)
        snapshotFlow { flow.value.roundToInt() }.collect { i ->
            if (i != shown) {
                shown = i
                currentOnTick()
                currentOnCentre(i)
            }
        }
    }
    LaunchedEffect(flipped) {
        flip.animateTo(if (flipped != null) 1f else 0f, tween(460, easing = FastOutSlowInEasing))
    }

    // A quarter turn the way the phone went; straight from one side to the other is a half turn.
    val turn by animateFloatAsState(
        targetValue = if (holding == Holding.RIGHT) -90f else 90f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 220f),
        label = "sidewaysTurn"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(width = screenH, height = screenW)
                .graphicsLayer { rotationZ = turn }
                .background(Brush.verticalGradient(listOf(Color(0xFF15171A), Color(0xFF000000))))
        ) {
            val density = LocalDensity.current
            val contentW = with(density) { screenH.toPx() }
            val contentH = with(density) { screenW.toPx() }
            val s = contentH / LAYOUT_H
            val layoutW = contentW / s
            // A finger's travel for one cover: about a third of a cover's width on screen.
            val perCoverPx = COVER_PX * s * 0.42f

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(albums.size) {
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                scope.launch { flow.stop() }
                            },
                            onHorizontalDrag = { change, dx ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                if (flipped != null) return@detectHorizontalDragGestures
                                scope.launch {
                                    flow.snapTo((flow.value - dx / perCoverPx).coerceIn(-0.35f, last + 0.35f))
                                }
                            },
                            onDragEnd = {
                                if (flipped != null) return@detectHorizontalDragGestures
                                val v = tracker.calculateVelocity().x
                                // A throw carries on about a third of a second's worth, then settles.
                                val target = (flow.value - v / perCoverPx * 0.32f).roundToInt().coerceIn(0, last)
                                scope.launch { flow.animateTo(target.toFloat(), spring(dampingRatio = 0.86f, stiffness = 120f)) }
                            },
                            onDragCancel = {
                                scope.launch { flow.animateTo(flow.value.roundToInt().coerceIn(0, last).toFloat(), spring()) }
                            }
                        )
                    }
                    .pointerInput(albums.size) {
                        detectTapGestures { pos ->
                            if (flipped != null) {
                                flipped = null
                                return@detectTapGestures
                            }
                            val dx = pos.x / s - layoutW / 2f
                            val y = pos.y / s
                            val centre = flow.value.roundToInt().coerceIn(0, last)
                            if (abs(dx) <= COVER_PX / 2f && y in COVER_TOP_PX..(COVER_TOP_PX + COVER_PX)) {
                                val album = albums.getOrNull(centre) ?: return@detectTapGestures
                                onOpen(album.id)
                                flipped = album.id
                                onTick()
                            } else {
                                // Which side cover: the first sits SIDE_OFFSET out, the rest stack STACK apart.
                                val out = abs(dx)
                                val n = if (out < SIDE_OFFSET_PX + COVER_PX / 2f) 1 else
                                    1 + floor((out - SIDE_OFFSET_PX - COVER_PX / 2f) / STACK_PX).toInt() + 1
                                val target = (centre + sign(dx).toInt() * n).coerceIn(0, last)
                                scope.launch { flow.animateTo(target.toFloat(), spring(dampingRatio = 0.86f, stiffness = 140f)) }
                            }
                        }
                    }
            ) {
                if (albums.isEmpty()) return@Canvas
                val centre = flow.value.roundToInt().coerceIn(0, last)
                val from = (centre - SIDE_WINDOW).coerceAtLeast(0)
                val to = (centre + SIDE_WINDOW).coerceAtMost(last)
                val covers = (from..to).map { i -> LcdCover(albums[i].id, i, art(albums[i].id)) }
                drawIntoCanvas { canvas ->
                    val c = canvas.nativeCanvas
                    c.save()
                    c.scale(s, s)
                    tools.draw(c, layoutW, LAYOUT_H, covers, flow.value, flip.value)
                    c.restore()
                }
            }

            // The middle cover's name, fading as it turns over.
            val centre = flow.value.roundToInt().coerceIn(0, last)
            val album = albums.getOrNull(centre)
            if (album != null && flip.value < 0.5f) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 14.dp, start = 48.dp, end = 48.dp)
                        .graphicsLayer { alpha = 1f - flip.value * 2f },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = album.title,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = album.artist,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = Color(0xFFA7AEB5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // The back of the turned cover: its tracks, opening from edge-on as the cover turns.
            val back = flipped
            if (back != null && flip.value > 0.5f) {
                val open = ((flip.value - 0.5f) * 2f).coerceIn(0f, 1f)
                val backAlbum = albums.firstOrNull { it.id == back }
                val titles = trackTitles[back]
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = with(density) { (COVER_PX * s * 1.7f).toDp() }, height = with(density) { (COVER_PX * s * 1.06f).toDp() })
                        .graphicsLayer { scaleX = open.coerceAtLeast(0.01f) }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF3F5F7))
                        // Taps on the back stay on the back; a tap anywhere else turns it over again.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xFFEEF1F4), Color(0xFFD3D9DF))))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = backAlbum?.title.orEmpty(),
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = Color(0xFF15181B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = backAlbum?.artist.orEmpty(),
                            fontFamily = InterFamily,
                            fontSize = 14.sp,
                            color = Color(0xFF5B636B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (titles == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("…", fontFamily = InterFamily, fontSize = 18.sp, color = Color(0xFF5B636B))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(titles) { index, title ->
                                val on = back == playingAlbumId && index == playingIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (on) {
                                                Modifier.background(
                                                    Brush.verticalGradient(listOf(Color(0xFF4FA597), Color(0xFF276A5F)))
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable {
                                            onTick()
                                            onPlay(back, index)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontFamily = InterFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        color = if (on) Color.White else Color(0xFF7A838C),
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Text(
                                        text = title,
                                        fontFamily = InterFamily,
                                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                        fontSize = 16.sp,
                                        color = if (on) Color.White else Color(0xFF15181B),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
