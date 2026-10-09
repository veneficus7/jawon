package com.jawon.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jawon.app.ui.theme.LampAmber

/**
 * Jawon's monogram: **a J whose hook catches a disc's centre hole.**
 *
 * The one shape every format shares is the hole in the middle — a CD's, a record's spindle, a
 * cassette's hub, the click wheel's centre button — so the mark is a disc's rim with the letter
 * hanging from it and curling round that hole. It reads as a J at a glance and as a record a
 * moment later, and it survives being drawn at 16dp in a top bar.
 *
 * Drawn rather than shipped as an image so it is sharp at every size and takes whichever accent
 * the room has (`LocalPalette`). The same geometry is the app icon's foreground
 * (`res/drawable/ic_launcher_foreground.xml`) — keep the two in step: [Geometry] is the source.
 */
@Composable
fun JawonMark(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = LampAmber,
    contentDescription: String? = "Jawon"
) {
    Canvas(
        modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        drawJawonMark(color)
    }
}

/**
 * The mark's proportions, as fractions of its width, so every place that draws it agrees.
 * The rim is a thin ring; the J is heavier, because it is the letter and the ring is the object.
 */
object Geometry {
    const val RIM_RADIUS = 0.46f
    const val RIM_STROKE = 0.055f
    const val HOLE_RADIUS = 0.075f
    const val J_STROKE = 0.10f
    /**
     * Where the J's stem stands, right of centre, and where it starts: at the rim, so the letter
     * hangs from the disc's edge rather than floating inside it.
     */
    const val STEM_X = 0.17f
    const val STEM_TOP = 0.10f
    /** The hook's radius round the hole — the hole plus the stroke plus a gap you can see. */
    const val HOOK_RADIUS = 0.17f
    /**
     * How far round the hole the hook turns, in degrees. Short of a half-turn: a hook that came
     * all the way round and rose again read as a U holding a dot — a face, at 24dp — and not a J.
     */
    const val HOOK_SWEEP = 140f
}

fun DrawScope.drawJawonMark(color: Color) {
    val s = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)

    // The disc's rim.
    drawCircle(
        color = color,
        radius = s * Geometry.RIM_RADIUS,
        center = c,
        style = Stroke(width = s * Geometry.RIM_STROKE)
    )
    // The hole.
    drawCircle(color = color, radius = s * Geometry.HOLE_RADIUS, center = c)

    // The J: a stem from the rim down to the hole's level, and a hook most of the way under it.
    val hook = s * Geometry.HOOK_RADIUS
    val stemX = c.x + s * Geometry.STEM_X
    val j = Path().apply {
        moveTo(stemX, c.y - s * (0.5f - Geometry.STEM_TOP))
        lineTo(stemX, c.y)
        arcTo(
            rect = Rect(c.x - hook, c.y - hook, c.x + hook, c.y + hook),
            startAngleDegrees = 0f,
            sweepAngleDegrees = Geometry.HOOK_SWEEP,
            forceMoveTo = false
        )
    }
    drawPath(
        path = j,
        color = color,
        style = Stroke(width = s * Geometry.J_STROKE, cap = StrokeCap.Round)
    )
}
