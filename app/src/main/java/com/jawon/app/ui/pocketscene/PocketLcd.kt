package com.jawon.app.ui.pocketscene

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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

/**
 * How long a page takes to slide across when one opens or closes. The classic's was quick enough
 * never to be waited for and slow enough to be seen — it is what makes a menu feel *deeper*
 * rather than merely different.
 *
 * **It leaves at once and slows into place.** It eased in at first (fast-out-slow-in), and frame by
 * frame on the owner's phone the title had changed while the list sat still for 50ms — the press
 * looked unanswered for three frames. A page pushed by a button moves the instant it is pushed.
 */
private const val PAGE_SLIDE_MS = 260

/** Title bar and row heights, in LCD pixels. Eight rows fill the screen under the bar. */
private const val TITLE_PX = 52
private const val TITLE_RULE_PX = 2
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
 * **Pages slide**, as the classic's did: a new one ([page] changing, [depth] deeper) comes in from
 * the right and pushes the old one off to the left, and going back runs the other way. The title
 * bar stays where it is and only its words change. A highlight moving within a page is the same
 * page and does not slide.
 *
 * **Driven from outside, animated inside.** What the screen shows is decided by the room and
 * passed in; how it moves between two of those can live here, because `LcdPanel` redraws the
 * glass on any change beneath it. (Under SceneView's `ViewNode` nothing animating in here was
 * ever seen — trap 33 — which is why the backlight's fade and Cover Flow's motion are still
 * animated in the room.)
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
    slide: Float = SLIDE_FADE_S,
    /** Which page this is, and how deep — see `PocketNav.page`. Unchanged, nothing slides. */
    page: Any = Unit,
    depth: Int = 0
) {
    val density = LocalDensity.current
    fun px(v: Int): Dp = with(density) { v.toDp() }

    Box(
        modifier = Modifier
            .size(px(LCD_PX_W), px(LCD_PX_H))
            .background(if (awake) LcdWhite else LcdOff)
    ) {
        if (!awake) return@Box
        // **The slide animates the page, never the screen.** Each page draws its own latest
        // screen from [lastShown]. The first version animated the whole screen as its state, and
        // since the main menu's screen changes on every tick of its slideshow and every turn of
        // the wheel, AnimatedContent — which decides what is visible by comparing states — would
        // now and then find the page going out equal to neither end of the slide, and drop it:
        // a blank white page leaving where the menu should have been.
        val lastShown = remember { RecentPages() }
        SideEffect { lastShown[page] = screen }
        AnimatedContent(
            targetState = LcdPage(page, depth),
            transitionSpec = {
                val deeper = targetState.depth >= initialState.depth
                val spec = tween<IntOffset>(PAGE_SLIDE_MS, easing = LinearOutSlowInEasing)
                slideInHorizontally(spec) { w -> if (deeper) w else -w } togetherWith
                    slideOutHorizontally(spec) { w -> if (deeper) -w else w }
            },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            label = "page"
        ) { shown ->
            val pageScreen = if (shown.page == page) screen else lastShown[shown.page] ?: screen
            when (val s = pageScreen) {
                // The whole screen, as the classic gave it: no title bar over the covers.
                is LcdScreen.CoverFlow -> CoverFlowBody(s, flow, flip)
                else -> Column(modifier = Modifier.fillMaxSize().background(LcdWhite)) {
                    Spacer(modifier = Modifier.height(px(TITLE_PX + TITLE_RULE_PX)))
                    when (s) {
                        is LcdScreen.Menu -> MenuBody(s, slide)
                        is LcdScreen.NowPlaying -> NowPlayingBody(s)
                        is LcdScreen.Slider -> SliderBody(s)
                        is LcdScreen.CoverFlow -> Unit
                    }
                }
            }
        }
        // Over the sliding pages, fixed: only its words change. Cover Flow has none, so the bar
        // fades with the slide into it and out of it, rather than vanishing from over the page
        // that is leaving and leaving a blank strip where it was.
        val bar by animateFloatAsState(
            targetValue = if (screen is LcdScreen.CoverFlow) 0f else 1f,
            animationSpec = tween(PAGE_SLIDE_MS),
            label = "titleBar"
        )
        if (bar > 0f) {
            Column(modifier = Modifier.graphicsLayer { alpha = bar }) { TitleBar(screen.title, playing) }
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

/** A page, as the slide sees it: which one, and how deep. What it shows is looked up. */
private data class LcdPage(val page: Any, val depth: Int)

/**
 * Each page's latest screen, for the slide: the one showing and the few it may still be sliding
 * away from. Least recently shown goes first.
 */
private class RecentPages : LinkedHashMap<Any, LcdScreen>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, LcdScreen>?): Boolean =
        size > MAX_PAGES_KEPT
}

private const val MAX_PAGES_KEPT = 6

/**
 * What makes the picture an LCD rather than an image of one: a backlit panel is not a perfect
 * white, it falls off toward its corners, and it sits a millimetre under the plate's window —
 * whose lip, lit from above, leaves a thin shadow along the panel's top and left edges.
 * Multiplied over the content, so blacks stay black and only light is tinted.
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
        // The window's lip, casting down onto the panel from the lamp above and to the left.
        val lip = size.height * 0.045f
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent), startY = 0f, endY = lip),
            size = Size(size.width, lip)
        )
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color(0x44000000), Color.Transparent), startX = 0f, endX = lip * 0.8f),
            size = Size(lip * 0.8f, size.height)
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
            .height(px(TITLE_RULE_PX))
            .background(TitleRule)
    )
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
                fontFamily = InterFamily,
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
                        // The highlighted row scrolls what does not fit, as the classic's did;
                        // the rest keep the ellipsis.
                        overflow = if (isSelected) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isSelected) Modifier.lcdMarquee() else Modifier)
                    )
                    if (row.value != null) {
                        Text(
                            text = row.value,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = lcdSp(24),
                            color = if (isSelected) Color.White.copy(alpha = 0.86f) else LcdDim,
                            maxLines = 1,
                            modifier = Modifier.padding(start = px(12), end = px(if (row.leadsOn) 12 else 0))
                        )
                    }
                    if (row.checked) Tick(if (isSelected) Color.White else SelectBottom)
                    if (row.leadsOn) Chevron(if (isSelected) Color.White else LcdInk.copy(alpha = 0.55f))
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

/** The words, a few lines at a time: the one being sung bold and in the selection's colour. */
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
                fontFamily = InterFamily,
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
                fontFamily = InterFamily,
                fontWeight = if (sung) FontWeight.Bold else FontWeight.Medium,
                fontSize = lcdSp(if (sung) 25 else 23),
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
                overflow = TextOverflow.Clip,
                modifier = Modifier.lcdMarquee()
            )
            Spacer(Modifier.height(px(4)))
            Text(
                text = np.album,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(23),
                color = LcdDim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.lcdMarquee()
            )
            Spacer(Modifier.height(px(14)))
            Text(
                // Blank while the player has yet to say which song an album resumed on.
                text = if (np.trackNumber > 0) "${np.trackNumber} of ${np.trackCount}" else "",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = lcdSp(20),
                color = LcdDim,
                maxLines = 1
            )
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
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = lcdSp(44),
            color = LcdInk
        )
        Spacer(Modifier.height(px(40)))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(30))
        ) {
            val h = size.height * 0.56f
            val top = (size.height - h) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(TrackTop, TrackBottom), startY = top, endY = top + h),
                topLeft = Offset(0f, top),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            val p = slider.position
            // A centred control fills from the middle outward, so "off" reads as nothing lit.
            val from = if (slider.bipolar) size.width / 2f + minOf(p, 0f) * size.width / 2f else 0f
            val to = if (slider.bipolar) size.width / 2f + maxOf(p, 0f) * size.width / 2f else size.width * p
            if (to - from > 0.5f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(SelectTop, SelectBottom), startY = top, endY = top + h),
                    topLeft = Offset(from, top),
                    size = Size(to - from, h),
                    cornerRadius = CornerRadius(h / 2f, h / 2f)
                )
            }
            if (slider.bipolar) {
                drawLine(LcdInk.copy(alpha = 0.45f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 2f)
            }
            drawRoundRect(
                color = TitleRule,
                topLeft = Offset(0f, top),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f),
                style = Stroke(width = 1.5f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = px(8))) {
            Text(slider.lowEnd, fontFamily = InterFamily, fontSize = lcdSp(20), color = LcdDim)
            Spacer(Modifier.weight(1f))
            Text(slider.highEnd, fontFamily = InterFamily, fontSize = lcdSp(20), color = LcdDim)
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
        drawPath(path, color, style = Stroke(width = size.width * 0.13f))
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
