package com.jawon.app.ui.pocketscene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.jawon.app.ui.theme.InterFamily

/**
 * The LCD's resolution, in pixels.
 *
 * **Twice the classic's 320×240, at its 4:3-and-a-bit aspect** — the modelled panel is
 * 44.3 × 32.9mm. Double so that at life size, 30cm from an eye, the type is as crisp as the
 * real one was; the pixel grid a real LCD shows is a job for the screen's material, not for
 * rendering the interface coarsely.
 *
 * Sized in raw pixels rather than dp for the reason `ShelfPlate` is: the world size of a
 * `ViewNode` is `viewSize / pxPerUnits`, so a density-dependent size would make the same
 * screen physically larger on one phone than another.
 */
const val LCD_PX_W = 640
const val LCD_PX_H = 475

/** Title bar and row heights, in LCD pixels. Eight rows would fill the classic's screen. */
private const val TITLE_PX = 52
private const val ROW_PX = 50

private val LcdWhite = Color(0xFFF3F5F7)
private val LcdInk = Color(0xFF15181B)
private val TitleTop = Color(0xFFEEF1F4)
private val TitleBottom = Color(0xFFD3D9DF)
private val TitleRule = Color(0xFFA9B2BB)

/**
 * The selection bar. **Jawon's own colour, not the original's blue** — the structure of the
 * screen is the iPod's on purpose (the owner wants that experience back), but its exact look
 * is Apple's, and a teal drawn from the app's own palette keeps the one without borrowing
 * the other.
 */
private val SelectTop = Color(0xFF4FA597)
private val SelectBottom = Color(0xFF276A5F)

/**
 * One screen of the player: a title, a list, one row selected.
 *
 * Deliberately dumb. What it shows is decided by whoever drives the wheel; this only draws a
 * state, which is what lets the same composable be every menu from Music down to a tracklist.
 */
@Composable
fun PocketLcd(title: String, rows: List<String>, selected: Int, playing: Boolean) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    val rowText = with(density) { 27.toSp() }
    val titleText = with(density) { 25.toSp() }

    Box(
        modifier = Modifier
            .size(px(LCD_PX_W), px(LCD_PX_H))
            .background(LcdWhite)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- the title bar -------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(TITLE_PX))
                    .background(Brush.verticalGradient(listOf(TitleTop, TitleBottom)))
            ) {
                if (playing) PlayGlyph(Modifier.align(Alignment.CenterStart).padding(start = px(18)))
                Text(
                    text = title,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = titleText,
                    color = LcdInk,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center)
                )
                BatteryGlyph(Modifier.align(Alignment.CenterEnd).padding(end = px(16)))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(2))
                    .background(TitleRule)
            )

            // ---- the list ------------------------------------------------------------
            rows.forEachIndexed { index, label ->
                val isSelected = index == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(px(ROW_PX))
                        .then(
                            if (isSelected) {
                                Modifier.background(
                                    Brush.verticalGradient(listOf(SelectTop, SelectBottom))
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = px(18)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontFamily = InterFamily,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = rowText,
                        color = if (isSelected) Color.White else LcdInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Chevron(if (isSelected) Color.White else LcdInk.copy(alpha = 0.55f))
                }
            }
        }
    }
}

/** The ">" at the end of a row that leads somewhere. Drawn, so it is not a font's idea of one. */
@Composable
private fun Chevron(color: Color) {
    val density = LocalDensity.current
    val w = with(density) { 14.toDp() }
    val h = with(density) { 22.toDp() }
    Canvas(modifier = Modifier.size(w, h)) {
        val path = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.12f)
            lineTo(size.width * 0.85f, size.height * 0.5f)
            lineTo(size.width * 0.15f, size.height * 0.88f)
        }
        drawPath(path, color, style = Stroke(width = size.width * 0.26f))
    }
}

@Composable
private fun PlayGlyph(modifier: Modifier) {
    val density = LocalDensity.current
    val s = with(density) { 18.toDp() }
    Canvas(modifier = modifier.size(s, s)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, Color(0xFF2B6F64))
    }
}

/** A battery, three-quarters full — not live yet; the phone's own level can drive it later. */
@Composable
private fun BatteryGlyph(modifier: Modifier) {
    val density = LocalDensity.current
    val w = with(density) { 40.toDp() }
    val h = with(density) { 20.toDp() }
    Canvas(modifier = modifier.size(w, h)) {
        val body = Size(size.width * 0.9f, size.height)
        drawRoundRect(
            color = LcdInk,
            size = body,
            cornerRadius = CornerRadius(3f, 3f),
            style = Stroke(width = 2.5f)
        )
        drawRoundRect(
            color = Color(0xFF3E9A55),
            topLeft = Offset(4f, 4f),
            size = Size((body.width - 8f) * 0.75f, body.height - 8f),
            cornerRadius = CornerRadius(1.5f, 1.5f)
        )
        drawRect(
            color = LcdInk,
            topLeft = Offset(body.width + 1f, size.height * 0.3f),
            size = Size(size.width * 0.08f, size.height * 0.4f)
        )
    }
}
