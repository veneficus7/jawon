package com.jawon.app.ui.cd

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber

/**
 * **What to connect to what** (0.7.6): a phone, a cable, a CD drive with its own power, drawn in
 * a few lines — and alive to what is actually happening.
 *
 * "Connect a USB CD drive" is a sentence; most people have never plugged one into a phone, and
 * the thing that goes wrong (a drive the phone cannot power) is the thing a picture shows best:
 * the drive has a second lead, to a plug. So the drawing is the instruction, and it is also the
 * status. The cable is a dashed, dim line until a drive answers, and then it is lit; the tray is
 * empty until there is a disc; the disc turns while it is read; and while it is being copied,
 * the music runs down the cable into the phone.
 *
 * Line art in the room's amber, on the screen's own dark. It moves only when something is
 * happening — still at every step that is waiting on a person.
 */
@Composable
internal fun CdDrawing(stage: CdSteps.Stage, modifier: Modifier = Modifier) {
    val lit by animateFloatAsState(
        targetValue = if (CdSteps.connected(stage)) 1f else 0f,
        animationSpec = tween(420),
        label = "cable"
    )
    val disc by animateFloatAsState(
        targetValue = if (CdSteps.hasDisc(stage)) 1f else 0f,
        animationSpec = tween(520),
        label = "disc"
    )
    val turning = CdSteps.spinning(stage)
    val copying = stage == CdSteps.Stage.RIPPING

    // Only composed while something moves, so a waiting screen asks the display for nothing.
    val turn: State<Float> = if (turning) {
        rememberInfiniteTransition(label = "disc").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "turn"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val flow: State<Float> = if (copying) {
        rememberInfiniteTransition(label = "flow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "flow"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val amber = LampAmber
    val dim = LabelDim
    val cable = remember { Path() }
    val measure = remember { PathMeasure() }
    val describing = when {
        !CdSteps.connected(stage) -> "A phone and a USB CD drive, not yet connected. The drive has its own power lead."
        !CdSteps.hasDisc(stage) -> "The drive is connected to the phone. Its tray is empty."
        copying -> "The disc is turning and its music is running down the cable into the phone."
        turning -> "The drive is connected and the disc is turning."
        else -> "The drive is connected, with a disc in it."
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(VIEW_W / VIEW_H)
            .semantics { contentDescription = describing }
    ) {
        val u = size.width / VIEW_W
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val thin = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val line = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val drive = lerp(dim, amber, lit)

        // ---- The phone -------------------------------------------------------------------
        drawRoundRect(amber, p(22f, 14f), Size(50f * u, 96f * u), CornerRadius(8f * u), style = line)
        drawRoundRect(amber.copy(alpha = 0.35f), p(27f, 22f), Size(40f * u, 78f * u), CornerRadius(3f * u), style = thin)
        // Jawon on its screen: the disc's rim and hole.
        drawCircle(amber.copy(alpha = 0.55f + 0.45f * lit), 9f * u, p(47f, 61f), style = thin)
        drawCircle(amber.copy(alpha = 0.55f + 0.45f * lit), 1.8f * u, p(47f, 61f))

        // ---- The cable: out of the phone's foot, along the bottom, up into the drive ---------
        cable.rewind()
        cable.moveTo(47f * u, 110f * u)
        cable.lineTo(47f * u, 124f * u)
        cable.quadraticTo(47f * u, 134f * u, 57f * u, 134f * u)
        cable.lineTo(150f * u, 134f * u)
        cable.quadraticTo(160f * u, 134f * u, 160f * u, 124f * u)
        cable.lineTo(160f * u, 96f * u)
        cable.quadraticTo(160f * u, 88f * u, 168f * u, 88f * u)
        cable.lineTo(178f * u, 88f * u)
        // Dashed and dim until a drive answers; then one lit line.
        if (lit < 1f) {
            drawPath(
                cable,
                dim.copy(alpha = 1f - lit),
                style = Stroke(
                    2f * u,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * u, 6f * u))
                )
            )
        }
        if (lit > 0f) drawPath(cable, amber.copy(alpha = lit), style = line)
        // The plug at the phone's foot.
        drawRoundRect(lerp(dim, amber, lit), p(42f, 110f), Size(10f * u, 6f * u), CornerRadius(1.5f * u))

        // The music, on its way down the cable while the disc is copied.
        if (copying) {
            measure.setPath(cable, false)
            val length = measure.length
            for (i in 0 until FLOW_DOTS) {
                // From the drive to the phone: the path is drawn the other way round.
                val at = 1f - ((flow.value + i / FLOW_DOTS.toFloat()) % 1f)
                val where = measure.getPosition(length * at)
                drawCircle(BRIGHT, 2.6f * u, where)
            }
        }

        // ---- The drive, seen from above: a slim case, its tray, its own power lead ---------
        drawRoundRect(drive, p(178f, 30f), Size(120f * u, 92f * u), CornerRadius(7f * u), style = line)
        // The front edge, where the tray comes out, and its button.
        drawLine(drive.copy(alpha = 0.6f), p(186f, 112f), p(290f, 112f), 1.4f * u, StrokeCap.Round)
        drawCircle(drive, 1.8f * u, p(282f, 117f))
        // Its own power: a second lead, to a plug. The one thing a phone cannot do for it.
        val power = Path().apply {
            moveTo(298f * u, 52f * u)
            lineTo(308f * u, 52f * u)
            quadraticTo(316f * u, 52f * u, 316f * u, 44f * u)
            lineTo(316f * u, 30f * u)
        }
        drawPath(power, drive, style = thin)
        drawRoundRect(drive, p(310f, 16f), Size(12f * u, 14f * u), CornerRadius(2.5f * u), style = thin)
        drawLine(drive, p(313.5f, 16f), p(313.5f, 11f), 1.6f * u, StrokeCap.Round)
        drawLine(drive, p(318.5f, 16f), p(318.5f, 11f), 1.6f * u, StrokeCap.Round)

        // ---- The disc --------------------------------------------------------------------
        val hub = p(238f, 70f)
        val radius = 33f * u
        // An empty tray says so: the well a disc sits in, and nothing in it.
        drawCircle(
            drive.copy(alpha = 0.45f * (1f - disc)),
            radius,
            hub,
            style = Stroke(1.4f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * u, 5f * u)))
        )
        if (disc > 0f) {
            drawCircle(amber.copy(alpha = disc), radius, hub, style = line)
            drawCircle(amber.copy(alpha = 0.5f * disc), 12f * u, hub, style = thin)
            drawCircle(amber.copy(alpha = disc), 3.2f * u, hub)
            // Light across its face: two arcs opposite each other, turning when the disc does.
            rotate(turn.value, hub) {
                sheen(hub, radius * 0.78f, amber.copy(alpha = 0.85f * disc), 1.8f * u)
                sheen(hub, radius * 0.55f, amber.copy(alpha = 0.5f * disc), 1.4f * u)
            }
        }
    }
}

/** A disc's sheen at one radius: a short arc, and the same arc across the hub from it. */
private fun DrawScope.sheen(hub: Offset, radius: Float, color: Color, width: Float) {
    val box = Size(radius * 2f, radius * 2f)
    val corner = Offset(hub.x - radius, hub.y - radius)
    val stroke = Stroke(width, cap = StrokeCap.Round)
    drawArc(color, -62f, 44f, false, corner, box, style = stroke)
    drawArc(color, 118f, 44f, false, corner, box, style = stroke)
}

private fun lerp(from: Color, to: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(from, to, t)

/** The drawing's own units: every coordinate above is in a 340 × 146 picture. */
private const val VIEW_W = 340f
private const val VIEW_H = 146f

private const val FLOW_DOTS = 4
private val BRIGHT = Color(0xFFFFEBC7)
