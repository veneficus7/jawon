package com.jawon.app.ui.pocketscene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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

/** Title bar and row heights, in LCD pixels. Eight rows fill the screen under the bar. */
private const val TITLE_PX = 52
private const val ROW_PX = 50
private const val SCROLLBAR_PX = 14

private val LcdWhite = Color(0xFFF3F5F7)
private val LcdInk = Color(0xFF15181B)
private val LcdDim = Color(0xFF5B636B)
private val TitleTop = Color(0xFFEEF1F4)
private val TitleBottom = Color(0xFFD3D9DF)
private val TitleRule = Color(0xFFA9B2BB)
private val TrackTop = Color(0xFFD9DEE3)
private val TrackBottom = Color(0xFFF1F3F5)

/** A screen with its backlight off and nothing to show — what "switched off" looks like. */
private val LcdOff = Color(0xFF0C0E10)

/**
 * The selection bar. **Jawon's own colour, not the original's blue** — the structure of the
 * screen is the iPod's on purpose (the owner wants that experience back), but its exact look
 * is Apple's, and a teal drawn from the app's own palette keeps the one without borrowing
 * the other.
 */
private val SelectTop = Color(0xFF4FA597)
private val SelectBottom = Color(0xFF276A5F)

/** One row as the screen draws it. */
data class LcdRow(val label: String, val leadsOn: Boolean)

/** What the screen is showing. Deliberately dumb: whoever drives the wheel decides it. */
sealed interface LcdScreen {
    val title: String

    /** A list, of which only the visible window is passed. */
    data class Menu(
        override val title: String,
        /** The rows on screen, at most [PocketMenus.VISIBLE_ROWS]. */
        val rows: List<LcdRow>,
        /** Which of [rows] is highlighted, or -1. */
        val selected: Int,
        /** The first visible row's index in the whole list, and the list's length — the scrollbar. */
        val top: Int,
        val total: Int,
        /** Shown when the list is empty, so an empty library says so rather than looking broken. */
        val emptyText: String = ""
    ) : LcdScreen

    data class NowPlaying(
        val trackTitle: String,
        val artist: String,
        val album: String,
        /** One-based. */
        val trackNumber: Int,
        val trackCount: Int,
        val positionMs: Long,
        val durationMs: Long,
        val cover: ImageBitmap?,
        val bar: Bar,
        /** 0..1, for [Bar.VOLUME]. */
        val volume: Float,
        /** Where the scrubber's playhead is, for [Bar.SCRUB]. */
        val scrubMs: Long
    ) : LcdScreen {
        override val title: String get() = "Now Playing"
    }

    /**
     * What the strip along the bottom of Now Playing is showing. The classic's: the song's
     * progress normally, the volume as soon as the wheel turns, and the scrubber after one press
     * of the centre.
     */
    enum class Bar { PROGRESS, VOLUME, SCRUB }
}

/**
 * The player's screen.
 *
 * [awake] false is the backlight off after the player has been switched off: a real LCD is
 * then a dark, dead rectangle under the glass, and that is what is drawn.
 */
@Composable
fun PocketLcd(screen: LcdScreen, playing: Boolean, awake: Boolean) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }

    Box(
        modifier = Modifier
            .size(px(LCD_PX_W), px(LCD_PX_H))
            .background(if (awake) LcdWhite else LcdOff)
    ) {
        if (!awake) return@Box
        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(screen.title, playing)
            when (screen) {
                is LcdScreen.Menu -> MenuBody(screen)
                is LcdScreen.NowPlaying -> NowPlayingBody(screen)
            }
        }
    }
}

@Composable
private fun TitleBar(title: String, playing: Boolean) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
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
            fontSize = lcdSp(25),
            color = LcdInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = px(84))
        )
        BatteryGlyph(Modifier.align(Alignment.CenterEnd).padding(end = px(16)))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(2))
            .background(TitleRule)
    )
}

@Composable
private fun MenuBody(menu: LcdScreen.Menu) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    val scrolls = menu.total > PocketMenus.VISIBLE_ROWS

    if (menu.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = menu.emptyText,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(24),
                color = LcdDim
            )
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            menu.rows.forEachIndexed { index, row ->
                val isSelected = index == menu.selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(px(ROW_PX))
                        .then(
                            if (isSelected) {
                                Modifier.background(Brush.verticalGradient(listOf(SelectTop, SelectBottom)))
                            } else {
                                Modifier
                            }
                        )
                        .padding(start = px(18), end = px(if (scrolls) 10 else 18)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.label,
                        fontFamily = InterFamily,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = lcdSp(27),
                        color = if (isSelected) Color.White else LcdInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (row.leadsOn) Chevron(if (isSelected) Color.White else LcdInk.copy(alpha = 0.55f))
                }
            }
        }
        if (scrolls) Scrollbar(menu.top, menu.total)
    }
}

/**
 * The classic's scrollbar: a trough down the right-hand side and a thumb whose length is the
 * share of the list on screen. Drawn only when the list is longer than the screen.
 */
@Composable
private fun Scrollbar(top: Int, total: Int) {
    val density = LocalDensity.current
    val w = with(density) { SCROLLBAR_PX.toDp() }
    Canvas(modifier = Modifier.width(w).fillMaxHeight()) {
        val trackH = size.height
        drawRect(Brush.horizontalGradient(listOf(TrackTop, TrackBottom)))
        drawLine(TitleRule, Offset(0f, 0f), Offset(0f, trackH), strokeWidth = 1.5f)
        val visible = PocketMenus.VISIBLE_ROWS
        val thumbH = (trackH * visible / total).coerceAtLeast(size.width * 1.5f)
        val span = (total - visible).coerceAtLeast(1)
        val y = (trackH - thumbH) * (top.toFloat() / span)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(SelectTop, SelectBottom)),
            topLeft = Offset(2.5f, y + 2f),
            size = Size(size.width - 4.5f, thumbH - 4f),
            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
        )
    }
}

@Composable
private fun NowPlayingBody(np: LcdScreen.NowPlaying) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    val art = 206

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- the cover, and its reflection in the screen's own floor ---------------------
        Column(
            modifier = Modifier
                .offset(px(28), px(30))
                .width(px(art))
        ) {
            Cover(np.cover, Modifier.size(px(art)))
            Box(
                modifier = Modifier
                    .size(px(art), px(52))
                    .clipToBounds()
            ) {
                // Flipped about its own centre, so the cover's bottom edge meets itself at the
                // top of this strip and the rest of it falls away below — clipped to the strip.
                Cover(np.cover, Modifier.size(px(art)).graphicsLayer { scaleY = -1f })
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(LcdWhite.copy(alpha = 0.62f), LcdWhite))
                        )
                )
            }
        }

        // ---- the words -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .offset(px(258), px(40))
                .width(px(LCD_PX_W - 258 - 22))
        ) {
            Text(
                text = np.trackTitle,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = lcdSp(27),
                lineHeight = lcdSp(32),
                color = LcdInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(px(8)))
            Text(
                text = np.artist,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(23),
                color = LcdInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(px(4)))
            Text(
                text = np.album,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(23),
                color = LcdDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(px(14)))
            Text(
                text = "${np.trackNumber} of ${np.trackCount}",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(20),
                color = LcdDim,
                maxLines = 1
            )
        }

        // ---- the strip along the bottom -------------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(px(92))
                .padding(horizontal = px(28))
        ) {
            when (np.bar) {
                LcdScreen.Bar.PROGRESS -> TimeBar(np.positionMs, np.durationMs, diamond = false)
                LcdScreen.Bar.SCRUB -> TimeBar(np.scrubMs, np.durationMs, diamond = true)
                LcdScreen.Bar.VOLUME -> VolumeBar(np.volume)
            }
        }
    }
}

@Composable
private fun Cover(cover: ImageBitmap?, modifier: Modifier) {
    if (cover != null) {
        Image(bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        // No artwork: a plain sleeve with a note on it, the way the classic drew a record
        // without art — never an empty hole in the layout.
        Box(
            modifier = modifier.background(Brush.verticalGradient(listOf(Color(0xFFD5DADF), Color(0xFFB9C0C7)))),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "♪", fontSize = lcdSp(90), color = Color(0xFF8A939C))
        }
    }
}

/** Progress, or the scrubber: a bar, and the time gone and the time left beneath it. */
@Composable
private fun TimeBar(positionMs: Long, durationMs: Long, diamond: Boolean) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(30))
        ) {
            val h = size.height * 0.5f
            val top = (size.height - h) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(TrackTop, TrackBottom), startY = top, endY = top + h),
                topLeft = Offset(0f, top),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            if (!diamond) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(SelectTop, SelectBottom), startY = top, endY = top + h),
                    topLeft = Offset(0f, top),
                    size = Size((size.width * fraction).coerceAtLeast(h), h),
                    cornerRadius = CornerRadius(h / 2f, h / 2f)
                )
            }
            drawRoundRect(
                color = TitleRule,
                topLeft = Offset(0f, top),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f),
                style = Stroke(width = 1.5f)
            )
            if (diamond) {
                // The scrubber's playhead: the classic drew a diamond riding on the bar.
                val x = size.width * fraction
                val cy = size.height / 2f
                val r = size.height * 0.46f
                val path = Path().apply {
                    moveTo(x, cy - r)
                    lineTo(x + r * 0.8f, cy)
                    lineTo(x, cy + r)
                    lineTo(x - r * 0.8f, cy)
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(SelectTop, SelectBottom), startY = cy - r, endY = cy + r))
                drawPath(path, LcdInk.copy(alpha = 0.6f), style = Stroke(width = 1.5f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = px(4))) {
            Text(
                text = formatTime(positionMs),
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = lcdSp(21),
                color = LcdInk
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "-" + formatTime((durationMs - positionMs).coerceAtLeast(0L)),
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = lcdSp(21),
                color = LcdInk
            )
        }
    }
}

/** The volume: a quiet speaker, the bar, a loud one — shown while the wheel is turning it. */
@Composable
private fun VolumeBar(volume: Float) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(44)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpeakerGlyph(waves = 0)
        Spacer(Modifier.width(px(14)))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(px(30))
        ) {
            val h = size.height * 0.5f
            val top = (size.height - h) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(TrackTop, TrackBottom), startY = top, endY = top + h),
                topLeft = Offset(0f, top),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(SelectTop, SelectBottom), startY = top, endY = top + h),
                topLeft = Offset(0f, top),
                size = Size((size.width * volume.coerceIn(0f, 1f)).coerceAtLeast(h), h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            drawRoundRect(
                color = TitleRule,
                topLeft = Offset(0f, top),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f),
                style = Stroke(width = 1.5f)
            )
        }
        Spacer(Modifier.width(px(14)))
        SpeakerGlyph(waves = 2)
    }
}

@Composable
private fun SpeakerGlyph(waves: Int) {
    val density = LocalDensity.current
    val s = with(density) { 34.toDp() }
    Canvas(modifier = Modifier.size(s, s)) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f)
            lineTo(w * 0.28f, h * 0.36f)
            lineTo(w * 0.50f, h * 0.16f)
            lineTo(w * 0.50f, h * 0.84f)
            lineTo(w * 0.28f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.64f)
            close()
        }
        drawPath(body, LcdInk)
        for (i in 1..waves) {
            val r = w * (0.14f + 0.16f * i)
            drawArc(
                color = LcdInk,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(w * 0.46f - r, h / 2f - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = w * 0.07f)
            )
        }
    }
}

/** LCD pixels as text size, so type is sized in the screen's own pixels like everything else. */
@Composable
private fun lcdSp(px: Int): TextUnit = with(LocalDensity.current) { px.toSp() }

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
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
