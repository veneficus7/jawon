@file:OptIn(ExperimentalTextApi::class)

package com.jawon.app.ui.pocketscene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.jawon.app.R

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

/**
 * The screen's type: Inter, at **every weight the screen actually sets**, each a real instance
 * of the variable font.
 *
 * The screen used the app's `InterFamily`, which declares only 400 and 500 — so every SemiBold
 * here (the title, the highlighted row, the times) quietly drew Medium, and every Bold (the song
 * on Now Playing, a slider's reading) drew Medium thickened by the rasteriser, Android's fake
 * bold, which smears counters shut at exactly the sizes this screen uses. Nothing in a build or
 * a test says so; only the letters do. The font file has the whole weight axis, so the screen
 * asks for what it means.
 */
internal val LcdFace = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

private val LcdWhite = Color(0xFFF3F5F7)
private val LcdInk = Color(0xFF15181B)
private val LcdDim = Color(0xFF5B636B)
private val TitleRule = Color(0xFFA9B2BB)
private val TrackTop = Color(0xFFC9CFD6)
private val TrackBottom = Color(0xFFF0F2F4)
/** The outline every bar's trough is drawn with. */
private val BarEdge = Color(0xFF9FA9B3)

/**
 * The title bar, as a backlit panel draws one: a bright hairline along its top, a light upper
 * half, and a crease at the waist into a slightly deeper lower half. Under it, a rule that is
 * one dark line and one light — engraved, not drawn.
 */
internal val TitleGloss = Brush.verticalGradient(
    0f to Color(0xFFF1F3F6),
    0.5f to Color(0xFFE3E7EB),
    0.5f to Color(0xFFD9DEE3),
    1f to Color(0xFFCDD3D9)
)
private val TitleShine = Color(0xFFFDFEFE)
private val RuleDark = Color(0xFF949EA8)
private val RuleLight = Color(0xFFE2E6EA)

/** A screen with its backlight off and nothing to show — what "switched off" looks like. */
private val LcdOff = Color(0xFF0C0E10)

/**
 * The selection bar. **Jawon's own colour, not the original's blue** — the structure of the
 * screen is the iPod's on purpose (the owner wants that experience back), but its exact look
 * is Apple's, and a teal drawn from the app's own palette keeps the one without borrowing
 * the other.
 *
 * **Lit from inside, the way the classic's bar was**: a bright hairline along the top where the
 * backlight catches its edge, a light upper half, a crease at the waist, a deeper lower half and
 * a dark lip under it. A plain two-stop gradient, which this was, reads as a flat stripe of
 * paint; the crease is what makes it read as light behind a panel. The same treatment carries to
 * everything that wears the colour — the scrollbar's thumb, the progress and volume fills, the
 * scrubber's diamond, the play glyph — so the screen has one material for "lit", not five fills.
 */
private val SelectShine = Color(0xFF7FD0C3)
private val SelectTop = Color(0xFF4FB2A3)
private val SelectUpper = Color(0xFF33998A)
private val SelectLower = Color(0xFF25867A)
private val SelectBottom = Color(0xFF1A6A5F)
private val SelectEdge = Color(0xFF13544B)

/** The gloss, top to bottom across whatever height it is drawn at. */
private fun selectGloss(top: Float = 0f, bottom: Float = Float.POSITIVE_INFINITY): Brush =
    Brush.verticalGradient(
        0f to SelectTop, 0.5f to SelectUpper, 0.5f to SelectLower, 1f to SelectBottom,
        startY = top, endY = bottom
    )

/** White type on the bar, with the faint dark edge above it that keeps it crisp on the light half. */
internal val OnSelectShadow = Shadow(color = Color(0x59002822), offset = Offset(0f, -1f), blurRadius = 0.5f)

/** The highlighted row's background — see [SelectShine]. */
internal fun Modifier.selectionBar(): Modifier = drawBehind {
    drawRect(selectGloss(0f, size.height))
    drawRect(SelectShine, size = Size(size.width, HAIRLINE_PX))
    drawRect(SelectEdge, topLeft = Offset(0f, size.height - HAIRLINE_PX), size = Size(size.width, HAIRLINE_PX))
}

/**
 * The finest line the screen draws, in LCD pixels. One and a half rather than one: the screen
 * reaches the eye through a texture sampled on a plane turned 15° away, and a single pixel line
 * comes through that as a grey smear.
 */
private const val HAIRLINE_PX = 1.5f

/**
 * One row as the screen draws it: a label, and at its right either a chevron (it leads on), a
 * current value (a setting), or a tick (the chosen one of a list of choices).
 */
data class LcdRow(
    val label: String,
    val leadsOn: Boolean,
    val value: String? = null,
    val checked: Boolean = false
)

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
        val emptyText: String = "",
        /** The main menu's right half: the library's covers drifting past. Null elsewhere. */
        val art: ArtSlides? = null
    ) : LcdScreen

    /**
     * The split screen's slideshow: the cover on show, the one fading in after it, and a number
     * that decides which way each drifts. Where in its life a slide is comes in separately, as
     * the room's clock — trap 33.
     */
    data class ArtSlides(
        val current: ImageBitmap?,
        val next: ImageBitmap?,
        val seed: Int
    )

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
        val scrubMs: Long,
        /** The words, when the lyrics page is up — in place of the cover and the names. */
        val lyrics: LyricsView? = null
    ) : LcdScreen {
        override val title: String get() = "Now Playing"
    }

    /**
     * The lyrics page: the lines on screen, which one is being sung (−1 for none, or for words
     * with no timing), and a note in place of the words while they are looked for or when
     * there are none.
     */
    data class LyricsView(
        val lines: List<String>,
        val current: Int,
        val note: String?,
        /** Where [lines] starts in the whole song — so a turn scrolls from what is on screen. */
        val top: Int = 0
    )

    /**
     * One effect's level, as the classic's Brightness screen showed one: the value large, a bar
     * under it, and what each end means. [position] is the fader, 0…1 or −1…+1 when [bipolar].
     */
    data class Slider(
        override val title: String,
        val position: Float,
        val bipolar: Boolean,
        val valueText: String,
        val lowEnd: String,
        val highEnd: String
    ) : LcdScreen

    /**
     * Cover Flow: the covers near the selection, the middle one's name, and — when a cover has
     * been turned over — its tracks on the back, windowed as any list is.
     */
    data class CoverFlow(
        val covers: List<LcdCover>,
        val albumTitle: String,
        val albumArtist: String,
        val tracks: Menu?
    ) : LcdScreen {
        override val title: String get() = "Cover Flow"
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
 * [awake] false is the player switched off: a real LCD is then a dark, dead rectangle under the
 * glass, and that is what is drawn. [unlit] is how far the backlight has faded, 0..1 — with the
 * lamp out the picture is still there, lit by nothing but the carriage, the way a transflective
 * panel reads.
 *
 * **Keep this stateless — no `animate*`, no `remember`ed state inside.** The in-world screen
 * only takes a new frame when the scope that invalidates its `ViewNode` recomposes (trap 33), so
 * anything that animates in here on its own is never seen on the glass. The backlight's fade was
 * written in here first and did exactly that. Animate in the room and pass the value in.
 */
@Composable
fun PocketLcd(
    screen: LcdScreen,
    playing: Boolean,
    awake: Boolean,
    unlit: Float = 0f,
    /** Cover Flow's position, as a fractional index, and how far a cover is turned over. */
    flow: Float = 0f,
    flip: Float = 0f,
    /** Seconds into the main menu's current slide — see [ArtPanel]. */
    slide: Float = SLIDE_FADE_S
) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }

    Box(
        modifier = Modifier
            .size(px(LCD_PX_W), px(LCD_PX_H))
            .background(if (awake) LcdWhite else LcdOff)
    ) {
        if (!awake) return@Box
        if (screen is LcdScreen.CoverFlow) {
            // The whole screen, as the classic gave it: no title bar over the covers.
            CoverFlowBody(screen, flow, flip)
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TitleBar(screen.title, playing)
                when (screen) {
                    is LcdScreen.Menu -> MenuBody(screen, slide)
                    is LcdScreen.NowPlaying -> NowPlayingBody(screen)
                    is LcdScreen.Slider -> SliderBody(screen)
                    is LcdScreen.CoverFlow -> Unit
                }
            }
        }
        LcdGlass()
        if (unlit > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LcdOff.copy(alpha = unlit))
            )
        }
    }
}

/**
 * What makes the picture an LCD rather than an image of one: a backlit panel is not a perfect
 * white, and it falls off toward its corners. Multiplied over the content, so blacks stay black
 * and only light is tinted.
 *
 * **No window-lip shadow any more.** This used to darken the picture's top and left edges as
 * the plate's window lip would. The window now has a black surround between its edge and the
 * picture (`POCKET_SCREEN_MASK` in the model script), so the lip's shadow falls on that, as on
 * a real one — and drawn here it only read as grime down the left of every row.
 */
@Composable
private fun LcdGlass() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // The backlight: a touch cool, and never quite white.
        drawRect(color = BacklightTint, blendMode = BlendMode.Multiply)
        // Falloff toward the corners.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.White, Color(0xFFD9DCE0)),
                center = center,
                radius = size.maxDimension * 0.62f
            ),
            blendMode = BlendMode.Multiply
        )
    }
}

/** The classic's panel was a slightly cool, slightly grey white; this is the multiplier for it. */
private val BacklightTint = Color(0xFFEDF1F7)

/** How dark the picture goes with the backlight out: still legible up close, as the real one was. */
const val BACKLIGHT_OFF_DIM = 0.8f

@Composable
private fun TitleBar(title: String, playing: Boolean) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(TITLE_PX))
            .titleBar()
    ) {
        if (playing) PlayGlyph(Modifier.align(Alignment.CenterStart).padding(start = px(18)))
        Text(
            text = title,
            fontFamily = LcdFace,
            fontWeight = FontWeight.SemiBold,
            fontSize = lcdSp(25),
            color = LcdInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Pressed into the bar rather than printed on it: a white line under each letter.
            style = LocalTextStyle.current.copy(shadow = TitleEmboss),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = px(84))
        )
        BatteryGlyph(Modifier.align(Alignment.CenterEnd).padding(end = px(16)))
    }
    TitleRuleLine()
}

private val TitleEmboss = Shadow(color = Color(0xB3FFFFFF), offset = Offset(0f, 1f), blurRadius = 0.5f)

/** The title bar's background — see [TitleGloss]. Shared with the back of a turned cover. */
internal fun Modifier.titleBar(): Modifier = drawBehind {
    drawRect(TitleGloss)
    drawRect(TitleShine, size = Size(size.width, HAIRLINE_PX))
}

/** The rule under a title bar: one dark line over one light one. */
@Composable
internal fun TitleRuleLine() {
    val density = LocalDensity.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { 2.toDp() })
    ) {
        drawRect(RuleDark, size = Size(size.width, size.height / 2f))
        drawRect(RuleLight, topLeft = Offset(0f, size.height / 2f), size = Size(size.width, size.height / 2f))
    }
}

@Composable
private fun MenuBody(menu: LcdScreen.Menu, slide: Float) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    val scrolls = menu.total > PocketMenus.VISIBLE_ROWS
    val art = menu.art

    if (menu.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = menu.emptyText,
                fontFamily = LcdFace,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(24),
                color = LcdDim
            )
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = if (art != null) Modifier.width(px(SPLIT_MENU_PX)) else Modifier.weight(1f)
        ) {
            menu.rows.forEachIndexed { index, row ->
                val isSelected = index == menu.selected
                val onBar = if (isSelected) LocalTextStyle.current.copy(shadow = OnSelectShadow) else LocalTextStyle.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(px(ROW_PX))
                        .then(if (isSelected) Modifier.selectionBar() else Modifier)
                        .padding(start = px(18), end = px(if (scrolls) 10 else 18)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.label,
                        fontFamily = LcdFace,
                        // **One weight, highlighted or not.** It was Medium, going SemiBold under
                        // the bar — so every turn of the wheel re-set two rows at new widths and the
                        // words shuffled sideways as the highlight passed. The classic changed only
                        // the colour. SemiBold, because type this small reaches the eye through a
                        // texture, a cover glass and a lens, and Medium came through thin.
                        fontWeight = FontWeight.SemiBold,
                        fontSize = lcdSp(27),
                        color = if (isSelected) Color.White else LcdInk,
                        maxLines = 1,
                        // The highlighted row scrolls what does not fit, as the classic's did;
                        // the rest keep the ellipsis.
                        overflow = if (isSelected) TextOverflow.Clip else TextOverflow.Ellipsis,
                        style = onBar,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isSelected) Modifier.lcdMarquee() else Modifier)
                    )
                    if (row.value != null) {
                        Text(
                            text = row.value,
                            fontFamily = LcdFace,
                            fontWeight = FontWeight.Medium,
                            fontSize = lcdSp(24),
                            color = if (isSelected) Color.White.copy(alpha = 0.86f) else LcdDim,
                            maxLines = 1,
                            style = onBar,
                            modifier = Modifier.padding(start = px(12), end = px(if (row.leadsOn) 12 else 0))
                        )
                    }
                    if (row.checked) Tick(if (isSelected) Color.White else SelectBottom)
                    if (row.leadsOn) Chevron(if (isSelected) Color.White else LcdInk.copy(alpha = 0.42f))
                }
            }
        }
        if (scrolls) Scrollbar(menu.top, menu.total)
        if (art != null) ArtPanel(art, slide, Modifier.weight(1f).fillMaxHeight())
    }
}

/** How much of the main menu's width the list keeps; the rest is the slideshow. */
private const val SPLIT_MENU_PX = 356

/** A slide's time fully on screen, and the cross-fade into the next. */
const val SLIDE_HOLD_S = 6.5f
const val SLIDE_FADE_S = 1.4f

/**
 * The classic's split screen: covers from the library, each slowly pushed in and drifted across
 * — a Ken Burns — then dissolved into the next.
 *
 * [slide] runs from the moment the current cover began to fade in, so its drift is continuous
 * across the hand-off: the room wraps it by [SLIDE_HOLD_S] exactly when the next cover takes
 * over, and the next cover's own clock at that moment is where it already was.
 *
 * Drawn with a `Matrix` on the native canvas rather than `drawImage`'s whole-pixel offsets: a
 * drift of a few pixels a second at twenty frames a second would otherwise move in visible
 * one-pixel steps.
 */
@Composable
private fun ArtPanel(art: LcdScreen.ArtSlides, slide: Float, modifier: Modifier) {
    val tools = remember { KenBurns() }
    // Clipped: a pushed-in cover is larger than the panel by design, and drawing on the native
    // canvas is not bounded by the composable — unclipped, it spilled over the title bar and
    // into the list the moment it drifted.
    Canvas(modifier = modifier.clipToBounds()) {
        drawRect(Color(0xFF0B0C0D))
        val life = SLIDE_HOLD_S + SLIDE_FADE_S
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            art.current?.let { tools.draw(native, it, size.width, size.height, slide / life, art.seed, 1f) }
            val fade = ((slide - SLIDE_HOLD_S) / SLIDE_FADE_S).coerceIn(0f, 1f)
            if (fade > 0f) {
                art.next?.let {
                    tools.draw(native, it, size.width, size.height, (slide - SLIDE_HOLD_S) / life, art.seed + 1, fade)
                }
            }
        }
        // The seam between the list and the pictures: a shade, as the classic's had.
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color(0x66000000), Color.Transparent), endX = size.width * 0.08f),
            size = Size(size.width * 0.08f, size.height)
        )
    }
}

private class KenBurns {
    private val matrix = android.graphics.Matrix()
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    fun draw(
        canvas: android.graphics.Canvas,
        image: ImageBitmap,
        width: Float,
        height: Float,
        t: Float,
        seed: Int,
        alpha: Float
    ) {
        val bmp = image.asAndroidBitmap()
        val cover = maxOf(width / bmp.width, height / bmp.height)
        // Pushing in on even slides, pulling out on odd ones, so the show does not only zoom.
        val zoom = if (seed % 2 == 0) 1.0f + 0.16f * t else 1.16f - 0.16f * t
        val scale = cover * zoom
        val spareX = bmp.width * scale - width
        val spareY = bmp.height * scale - height
        val across = if ((seed / 2) % 2 == 0) t else 1f - t
        val down = 0.35f + 0.3f * (((seed * 7) % 5) / 4f)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(-spareX * across, -spareY * down)
        paint.alpha = (alpha * 255f).toInt()
        canvas.drawBitmap(bmp, matrix, paint)
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
        // A trough, shaded from its left wall as an inset is.
        drawRect(Brush.horizontalGradient(0f to TrackTop, 0.45f to Color(0xFFE9ECEF), 1f to LcdWhite))
        drawLine(TitleRule, Offset(0f, 0f), Offset(0f, trackH), strokeWidth = 1.5f)
        val visible = PocketMenus.VISIBLE_ROWS
        val thumbH = (trackH * visible / total).coerceAtLeast(size.width * 1.5f)
        val span = (total - visible).coerceAtLeast(1)
        val y = (trackH - thumbH) * (top.toFloat() / span)
        // The thumb wears the selection's gloss, turned on its side.
        drawRoundRect(
            brush = Brush.horizontalGradient(
                0f to SelectTop, 0.5f to SelectUpper, 0.5f to SelectLower, 1f to SelectBottom,
                startX = 2.5f, endX = size.width - 2f
            ),
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
        val words = np.lyrics
        if (words != null) {
            LyricsPage(words, Modifier.align(Alignment.TopCenter))
        } else {
            CoverAndNames(np, art)
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

/**
 * The words, a few lines at a time: the one being sung in the selection's colour, a weight up.
 *
 * **The same size as the rest.** It used to grow two pixels as well, which re-set the whole line
 * wider every time the singing moved on; the weight and the colour are enough to find it by.
 */
@Composable
private fun LyricsPage(words: LcdScreen.LyricsView, modifier: Modifier) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = px(30), end = px(30), top = px(22))
    ) {
        if (words.note != null) {
            Text(
                text = words.note,
                fontFamily = LcdFace,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(23),
                color = LcdDim,
                modifier = Modifier.padding(top = px(90))
            )
            return@Column
        }
        words.lines.forEachIndexed { i, line ->
            val sung = i == words.current
            Text(
                text = line.ifBlank { "·" },
                fontFamily = LcdFace,
                fontWeight = if (sung) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = lcdSp(23),
                lineHeight = lcdSp(29),
                color = if (sung) SelectBottom else LcdInk.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = px(3))
            )
        }
    }
}

@Composable
private fun CoverAndNames(np: LcdScreen.NowPlaying, art: Int) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
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
                fontFamily = LcdFace,
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
                fontFamily = LcdFace,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(23),
                color = LcdInk,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.lcdMarquee()
            )
            Spacer(Modifier.height(px(4)))
            Text(
                text = np.album,
                fontFamily = LcdFace,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(23),
                color = LcdDim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.lcdMarquee()
            )
            Spacer(Modifier.height(px(14)))
            Text(
                text = "${np.trackNumber} of ${np.trackCount}",
                fontFamily = LcdFace,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(20),
                color = LcdDim,
                maxLines = 1,
                style = tabular()
            )
        }
    }
}

/**
 * A cover, with a hairline round it. **The hairline is for the pale ones**: a sleeve that is
 * mostly white on a screen that is mostly white had no edge at all, and its reflection below it
 * read as a smudge rather than a reflection of anything.
 */
@Composable
private fun Cover(cover: ImageBitmap?, modifier: Modifier) {
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.drawWithContent {
                drawContent()
                coverEdge(CoverEdge)
            }
        )
    } else {
        // No artwork: a plain sleeve with two quavers on it, the way the classic drew a record
        // without art — never an empty hole in the layout. **Drawn, not typed**: this was a "♪",
        // which Inter does not have, so the one glyph on the screen came out of whatever face
        // the phone fell back to.
        Canvas(modifier = modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFE3E7EA), Color(0xFFC3CAD0))))
            val s = size.minDimension * 0.42f
            translate(left = (size.width - s) / 2f, top = (size.height - s) / 2f) {
                scale(s / 100f, s / 100f, pivot = Offset.Zero) {
                    drawQuavers(Color(0xFF98A1AA))
                }
            }
            coverEdge(Color(0x1A000000))
        }
    }
}

private val CoverEdge = Color(0x24000000)

/** A hairline just inside a cover's edge — inside, so it is never clipped half away. */
private fun DrawScope.coverEdge(color: Color) {
    val half = HAIRLINE_PX / 2f
    drawRect(
        color,
        topLeft = Offset(half, half),
        size = Size(size.width - HAIRLINE_PX, size.height - HAIRLINE_PX),
        style = Stroke(width = HAIRLINE_PX)
    )
}

/** Two beamed quavers in a 100-unit box: heads tipped as printed ones are, stems, and the beam. */
private fun DrawScope.drawQuavers(color: Color) {
    for ((cx, cy) in listOf(27f to 78f, 77f to 68f)) {
        rotate(-22f, pivot = Offset(cx, cy)) {
            drawOval(color, topLeft = Offset(cx - 15f, cy - 11f), size = Size(30f, 22f))
        }
    }
    drawRect(color, topLeft = Offset(37.5f, 16f), size = Size(6f, 62f))
    drawRect(color, topLeft = Offset(87.5f, 6f), size = Size(6f, 62f))
    val beam = Path().apply {
        moveTo(37.5f, 16f)
        lineTo(93.5f, 6f)
        lineTo(93.5f, 20f)
        lineTo(37.5f, 30f)
        close()
    }
    drawPath(beam, color)
}

/**
 * A bar as every one on the screen is drawn: an inset trough across the canvas, lit fill from
 * [from] to [to], and an outline. [heightFraction] is the bar's share of the canvas's height.
 */
private fun DrawScope.drawBar(heightFraction: Float, from: Float, to: Float) {
    val h = size.height * heightFraction
    val top = (size.height - h) / 2f
    val corner = CornerRadius(h / 2f, h / 2f)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(TrackTop, TrackBottom), startY = top, endY = top + h),
        topLeft = Offset(0f, top),
        size = Size(size.width, h),
        cornerRadius = corner
    )
    if (to - from > 0.5f) {
        drawRoundRect(
            brush = selectGloss(top, top + h),
            topLeft = Offset(from, top),
            size = Size(to - from, h),
            cornerRadius = corner
        )
        // The fill's lit top edge, kept clear of its rounded ends.
        val shine = to - from - h
        if (shine > 0f) {
            drawRect(SelectShine, topLeft = Offset(from + h / 2f, top + 1f), size = Size(shine, HAIRLINE_PX))
        }
    }
    drawRoundRect(
        color = BarEdge,
        topLeft = Offset(0f, top),
        size = Size(size.width, h),
        cornerRadius = corner,
        style = Stroke(width = 1.5f)
    )
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
            drawBar(0.5f, 0f, if (diamond) 0f else (size.width * fraction).coerceAtLeast(h))
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
                drawPath(path, selectGloss(cy - r, cy + r))
                drawPath(path, SelectEdge, style = Stroke(width = 1.5f, join = StrokeJoin.Round))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = px(4))) {
            // Figures of one width, so the time does not twitch sideways as it counts — and a
            // true minus on the time left, the length of the figures, where a hyphen sat short.
            Text(
                text = formatTime(positionMs),
                fontFamily = LcdFace,
                fontWeight = FontWeight.SemiBold,
                fontSize = lcdSp(21),
                color = LcdInk,
                style = tabular()
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "−" + formatTime((durationMs - positionMs).coerceAtLeast(0L)),
                fontFamily = LcdFace,
                fontWeight = FontWeight.SemiBold,
                fontSize = lcdSp(21),
                color = LcdInk,
                style = tabular()
            )
        }
    }
}

@Composable
private fun SliderBody(slider: LcdScreen.Slider) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = px(34)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(px(92)))
        Text(
            text = slider.valueText,
            fontFamily = LcdFace,
            fontWeight = FontWeight.Bold,
            fontSize = lcdSp(44),
            color = LcdInk,
            style = tabular()
        )
        Spacer(Modifier.height(px(40)))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(30))
        ) {
            val p = slider.position
            // A centred control fills from the middle outward, so "off" reads as nothing lit.
            val from = if (slider.bipolar) size.width / 2f + minOf(p, 0f) * size.width / 2f else 0f
            val to = if (slider.bipolar) size.width / 2f + maxOf(p, 0f) * size.width / 2f else size.width * p
            drawBar(0.56f, from, to)
            if (slider.bipolar) {
                drawLine(LcdInk.copy(alpha = 0.45f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 2f)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = px(8))) {
            Text(slider.lowEnd, fontFamily = LcdFace, fontSize = lcdSp(20), color = LcdDim)
            Spacer(Modifier.weight(1f))
            Text(slider.highEnd, fontFamily = LcdFace, fontSize = lcdSp(20), color = LcdDim)
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
            drawBar(0.5f, 0f, (size.width * volume.coerceIn(0f, 1f)).coerceAtLeast(size.height * 0.5f))
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
                style = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Text too long for its line scrolls to show the rest, after a moment's pause — the classic's
 * way with a long title, where Jawon's first screen cut it off with an ellipsis. Only text that
 * overflows moves; it runs at about 70 of the screen's own pixels a second.
 *
 * Possible at all because the screen is drawn by `LcdPanel`, which redraws on any change beneath
 * it: under SceneView's `ViewNode` an animation inside the screen never reached the glass
 * (trap 33).
 */
@Composable
internal fun Modifier.lcdMarquee(): Modifier {
    val speed = with(LocalDensity.current) { 70.toDp() }
    return basicMarquee(
        iterations = Int.MAX_VALUE,
        initialDelayMillis = 1_400,
        repeatDelayMillis = 1_800,
        velocity = speed
    )
}

/** LCD pixels as text size, so type is sized in the screen's own pixels like everything else. */
@Composable
private fun lcdSp(px: Int): TextUnit = with(LocalDensity.current) { px.toSp() }

/** Figures all of one width — for anything that counts, so it does not shift as it changes. */
@Composable
internal fun tabular(): TextStyle = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

/** The tick against the chosen one of a list of choices. Drawn, like the chevron. */
@Composable
private fun Tick(color: Color) {
    val density = LocalDensity.current
    val s = with(density) { 24.toDp() }
    Canvas(modifier = Modifier.size(s, s)) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.55f)
            lineTo(size.width * 0.40f, size.height * 0.82f)
            lineTo(size.width * 0.90f, size.height * 0.20f)
        }
        drawPath(path, color, style = Stroke(width = size.width * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The ">" at the end of a row that leads somewhere. Drawn, so it is not a font's idea of one —
 * with rounded ends, because a mitred point at this size comes through the texture as a spike.
 */
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
        drawPath(path, color, style = Stroke(width = size.width * 0.26f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The title bar's "playing" mark, in the selection's lit teal with its dark edge. */
@Composable
private fun PlayGlyph(modifier: Modifier) {
    val density = LocalDensity.current
    val s = with(density) { 18.toDp() }
    Canvas(modifier = modifier.size(s, s)) {
        val u = size.width / 18f
        val path = Path().apply {
            moveTo(1.5f * u, 1.2f * u)
            lineTo(17f * u, 9f * u)
            lineTo(1.5f * u, 16.8f * u)
            close()
        }
        drawPath(path, selectGloss(0f, size.height))
        drawPath(path, SelectEdge, style = Stroke(width = u, join = StrokeJoin.Round))
    }
}

/**
 * A battery, three-quarters full — not live yet; the phone's own level can drive it later. A
 * pale cell in a dark outline, its charge the same lit glass as the selection, in green.
 */
@Composable
private fun BatteryGlyph(modifier: Modifier) {
    val density = LocalDensity.current
    val w = with(density) { 40.toDp() }
    val h = with(density) { 20.toDp() }
    Canvas(modifier = modifier.size(w, h)) {
        val u = size.width / 40f
        val ink = Color(0xFF3A4148)
        drawRoundRect(
            color = Color(0xFFF7F9FA),
            topLeft = Offset(u, u),
            size = Size(34f * u, 18f * u),
            cornerRadius = CornerRadius(4f * u, 4f * u)
        )
        drawRoundRect(
            color = ink,
            topLeft = Offset(u, u),
            size = Size(34f * u, 18f * u),
            cornerRadius = CornerRadius(4f * u, 4f * u),
            style = Stroke(width = 2f * u)
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF8FDCA6), 0.5f to Color(0xFF5DBE79),
                0.5f to Color(0xFF3FA25B), 1f to Color(0xFF2E8A49),
                startY = 4f * u, endY = 16f * u
            ),
            topLeft = Offset(4f * u, 4f * u),
            size = Size(28f * u * 0.75f, 12f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u)
        )
        drawRoundRect(
            color = ink,
            topLeft = Offset(36f * u, 6.5f * u),
            size = Size(3.4f * u, 7f * u),
            cornerRadius = CornerRadius(1.2f * u, 1.2f * u)
        )
    }
}
