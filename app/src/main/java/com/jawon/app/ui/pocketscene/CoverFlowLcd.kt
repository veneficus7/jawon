package com.jawon.app.ui.pocketscene

import android.graphics.Camera
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.jawon.app.ui.theme.InterFamily
import kotlin.math.abs
import kotlin.math.sign

/** One cover in Cover Flow: which album, where it is in the whole list, and its art if loaded. */
class LcdCover(val albumId: String, val index: Int, val art: ImageBitmap?)

/** Cover size and placement, in LCD pixels. */
private const val COVER_PX = 250f
private const val COVER_TOP_PX = 62f
/** How far the first cover either side sits from the middle, and how tight the rest stack. */
private const val SIDE_OFFSET_PX = 188f
private const val STACK_PX = 54f
/** How far a side cover is turned to face the middle, and how far it is pushed back. */
private const val SIDE_ANGLE_DEG = 68f
private const val SIDE_DEPTH_PX = 130f
/** The floor's reflection: how strong at the covers' feet. */
private const val REFLECTION_ALPHA = 0.34f

/**
 * Cover Flow, as the screen draws it: the albums standing on a black, glossy floor, the one in
 * the middle facing you and the rest turned in on either side; when a cover is turned over,
 * its tracks on the back.
 *
 * **Drawn with `android.graphics.Camera` and a `Matrix` straight onto the canvas**, not with
 * Compose's `graphicsLayer`: the in-world screen is copied into its texture on a *software*
 * canvas (trap 33), and a perspective bitmap draw is a thing a software canvas does for certain.
 *
 * Stateless, like the rest of the screen: [flow] — where the covers are, as a fractional index —
 * and [flip] are eased by the room and handed in (trap 33, again).
 */
@Composable
internal fun CoverFlowBody(screen: LcdScreen.CoverFlow, flow: Float, flip: Float) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    val tools = remember { CoverFlowTools() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF15171A), Color(0xFF000000))))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                tools.draw(canvas.nativeCanvas, size.width, size.height, screen.covers, flow, flip)
            }
        }

        // The name under the middle cover, fading as it turns over.
        if (flip < 0.5f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = px(16))
                    .graphicsLayer { alpha = 1f - flip * 2f },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = screen.albumTitle,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = coverSp(25),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = px(24))
                        .lcdMarquee()
                )
                Text(
                    text = screen.albumArtist,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = coverSp(21),
                    color = Color(0xFFA7AEB5),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = px(24))
                )
            }
        }

        // The back of the turned cover: its tracks. It opens from edge-on — the second half of
        // the turn — which is a scale on one axis and therefore safe on a software canvas.
        if (flip > 0.5f && screen.tracks != null) {
            val open = ((flip - 0.5f) * 2f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(px(560), px(420))
                    .graphicsLayer { scaleX = open.coerceAtLeast(0.01f) }
                    .background(Color(0xFFF3F5F7))
            ) {
                TrackBack(screen.tracks)
            }
        }
    }
}

@Composable
private fun TrackBack(tracks: LcdScreen.Menu) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(52))
                .background(Brush.verticalGradient(listOf(Color(0xFFEEF1F4), Color(0xFFD3D9DF)))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tracks.title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = coverSp(23),
                color = Color(0xFF15181B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = px(20))
            )
        }
        if (tracks.rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tracks.emptyText, fontFamily = InterFamily, fontSize = coverSp(21), color = Color(0xFF5B636B))
            }
            return@Column
        }
        tracks.rows.forEachIndexed { i, row ->
            val selected = i == tracks.selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(45))
                    .then(
                        if (selected) {
                            Modifier.background(
                                Brush.verticalGradient(listOf(Color(0xFF4FA597), Color(0xFF276A5F)))
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = px(18)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tracks.top + i + 1}",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = coverSp(20),
                    color = if (selected) Color.White else Color(0xFF7A838C),
                    modifier = Modifier.size(px(40), px(28))
                )
                Text(
                    text = row.label,
                    fontFamily = InterFamily,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = coverSp(23),
                    color = if (selected) Color.White else Color(0xFF15181B),
                    maxLines = 1,
                    overflow = if (selected) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (selected) Modifier.lcdMarquee() else Modifier
                )
            }
        }
    }
}

/**
 * The drawing itself, with its scratch objects held so nothing is allocated per frame — this
 * runs every frame the covers are moving.
 */
private class CoverFlowTools {
    private val camera = Camera().apply { setLocation(0f, 0f, -14f) }
    private val matrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2E33.toInt() }
    private val floorPaint = Paint()
    private var floorFor = -1f
    private val order = ArrayList<LcdCover>(16)

    fun draw(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        covers: List<LcdCover>,
        flow: Float,
        flip: Float
    ) {
        val cx = width / 2f
        val cy = COVER_TOP_PX + COVER_PX / 2f
        val floorY = COVER_TOP_PX + COVER_PX

        // Far covers first, so the nearer ones stand in front of them.
        order.clear()
        order.addAll(covers)
        order.sortByDescending { abs(it.index - flow) }

        // The reflections, then the floor fading them out, then the covers standing on it.
        for (cover in order) drawCover(canvas, cover, cx, cy, flow, flip, reflected = true)
        if (floorFor != floorY) {
            floorPaint.shader = LinearGradient(
                0f, floorY, 0f, floorY + COVER_PX * 0.55f,
                0x33000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
            )
            floorFor = floorY
        }
        canvas.drawRect(0f, floorY, width, height, floorPaint)
        for (cover in order) drawCover(canvas, cover, cx, cy, flow, flip, reflected = false)
    }

    private fun drawCover(
        canvas: android.graphics.Canvas,
        cover: LcdCover,
        cx: Float,
        cy: Float,
        flow: Float,
        flip: Float,
        reflected: Boolean
    ) {
        val offset = cover.index - flow
        val near = offset.coerceIn(-1f, 1f)
        val x = if (abs(offset) <= 1f) {
            offset * SIDE_OFFSET_PX
        } else {
            sign(offset) * (SIDE_OFFSET_PX + (abs(offset) - 1f) * STACK_PX)
        }
        val depth = abs(near) * SIDE_DEPTH_PX
        var angle = -near * SIDE_ANGLE_DEG
        var alpha = 1f
        val isCentre = abs(offset) < 0.5f
        if (flip > 0f) {
            if (isCentre) {
                // The first half of the turn: the middle cover goes edge-on, then the back opens.
                angle += 90f * (flip * 2f).coerceAtMost(1f)
                if (flip >= 0.5f) return
            } else {
                alpha = (1f - flip * 1.6f).coerceIn(0f, 1f)
                if (alpha <= 0f) return
            }
        }

        camera.save()
        camera.translate(0f, 0f, depth)
        camera.rotateY(angle)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-COVER_PX / 2f, -COVER_PX / 2f)
        if (reflected) {
            // Mirrored about the cover's own foot, so it meets itself on the floor.
            matrix.preTranslate(0f, COVER_PX * 2f)
            matrix.preScale(1f, -1f)
        }
        matrix.postTranslate(cx + x, cy)

        val art = cover.art
        val a = (alpha * if (reflected) REFLECTION_ALPHA else 1f)
        if (art != null) {
            val bmp = art.asAndroidBitmap()
            matrix.preScale(COVER_PX / bmp.width, COVER_PX / bmp.height)
            bitmapPaint.alpha = (a * 255f).toInt()
            canvas.drawBitmap(bmp, matrix, bitmapPaint)
        } else {
            // A record with no art: a plain dark sleeve, never a hole in the row.
            canvas.save()
            canvas.concat(matrix)
            blankPaint.alpha = (a * 255f).toInt()
            canvas.drawRect(0f, 0f, COVER_PX, COVER_PX, blankPaint)
            canvas.restore()
        }
        if (!reflected && !isCentre) {
            // Side covers a shade darker, turned away from the light.
            canvas.save()
            canvas.concat(matrix)
            shadePaint.color = android.graphics.Color.argb((abs(near) * 90f * alpha).toInt(), 0, 0, 0)
            val w = art?.width?.toFloat() ?: COVER_PX
            val h = art?.height?.toFloat() ?: COVER_PX
            canvas.drawRect(0f, 0f, w, h, shadePaint)
            canvas.restore()
        }
    }
}

@Composable
private fun coverSp(px: Int): TextUnit = with(LocalDensity.current) { px.toSp() }
