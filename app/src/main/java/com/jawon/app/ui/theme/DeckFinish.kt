package com.jawon.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the deck is *made of* — the faceplate that rises over the room when a case opens.
 *
 * ## Why this is separate from [RoomScheme]
 *
 * They answer different questions. A room scheme says **where you are standing**: the lamp, the
 * wall, the shelf boards, the light falling on the artwork. A deck finish says **what you are
 * playing the disc on**. Those are independent — a brushed aluminium component belongs in a
 * midnight shop just as happily as under a cold striplight — so they are two settings rather
 * than one list, and the combinations multiply instead of competing.
 *
 * Before this existed, "scheme" meant lighting and one accent colour, and every surface the user
 * actually touched stayed the same brown in all three schemes. Switching changed the room and
 * not the app.
 *
 * ## The period faces
 *
 * Two of the three finishes are recognisable almost entirely by their lettering, which is why
 * [display] and [keyType] are part of a finish rather than global constants. A vacuum-fluorescent
 * window rendered in a modern mono is just a mono; in [Vt323Family] it is a hi-fi. The type is
 * the finish.
 */
enum class DeckFinish(
    val displayName: String,
    /** What the front of the machine says about itself, silkscreened top-left. */
    val brand: String,
    /** A model number nobody ever read. Flavour, and free. */
    val model: String,

    // ---- the faceplate ----
    val plate: Color,
    /** The second stop of the plate's gradient — equal to [plate] for a flat finish. */
    val plateLow: Color,
    val plateEdge: Color,
    val plateInk: Color,
    val plateDim: Color,
    /** Fine vertical grain, as on a machined aluminium front. Zero means a smooth plate. */
    val grain: Float,
    /** Hard highlight and shadow on every edge, the way a 1998 desktop widget was drawn. */
    val bevelled: Boolean,

    // ---- the display window ----
    val window: Color,
    val windowEdge: Color,
    val windowRadius: Dp,
    val displayInk: Color,
    /** How far the display's own light bleeds past its segments. Zero for a reflective LCD. */
    val displayGlow: Dp,
    val display: FontFamily,
    val displaySize: TextUnit,

    // ---- keys and faders ----
    val key: Color,
    val keyEdge: Color,
    val keyInk: Color,
    val keyDim: Color,
    val keyRadius: Dp,
    val keyType: FontFamily,
    val keyTypeSize: TextUnit,
    val groove: Color,
    val cap: Color,

    val accent: Color
) {

    /**
     * The deck as it has always looked: the room's own materials, continued.
     *
     * **The default, and it stays the default.** The two period finishes are a choice someone
     * makes, not something that happens to them because a new version shipped.
     */
    SHOP(
        displayName = "Shop",
        brand = "JAWON",
        model = "",
        plate = CrateBlack,
        plateLow = CrateBlack,
        plateEdge = CrateEdge,
        plateInk = LabelCream,
        plateDim = LabelDim,
        grain = 0f,
        bevelled = false,
        window = Color(0xFF0E0B09),
        windowEdge = Color(0xFF2A2119),
        windowRadius = 5.dp,
        displayInk = LampAmber,
        displayGlow = 0.dp,
        display = JetBrainsMonoFamily,
        displaySize = 13.sp,
        key = Color(0x8C241C17),
        keyEdge = Color(0x6633291F),
        keyInk = LabelCream,
        keyDim = LabelDim,
        keyRadius = 7.dp,
        keyType = JetBrainsMonoFamily,
        keyTypeSize = 8.sp,
        groove = Color(0x73120E0C),
        cap = Color(0xFFCFC2AA),
        accent = LampAmber
    ),

    /**
     * 1996, a hi-fi component: brushed aluminium and a vacuum-fluorescent window.
     *
     * **The best of the period finishes, and not only for nostalgia.** A VFD is the rare piece of
     * retro that is also genuinely better at the job: cyan-green, enormous, and legible at arm's
     * length across a dark room — which is exactly the situation this app is always in. Every
     * thing the deck already shows is what those windows were built to show.
     *
     * The keys go *dark on a light plate*, which is the inversion the whole finish rests on and
     * the reason it cannot simply be a recolour of [SHOP].
     */
    SEPARATES(
        displayName = "Separates",
        brand = "JAWON",
        model = "CDP-700",
        plate = Color(0xFFC2C6C4),
        plateLow = Color(0xFF9A9E9C),
        plateEdge = Color(0xFF8A8E8C),
        plateInk = Color(0xFF1C201E),
        plateDim = Color(0xFF5A605E),
        grain = 0.10f,
        bevelled = false,
        window = Color(0xFF0A100E),
        windowEdge = Color(0xFF2A302E),
        windowRadius = 3.dp,
        displayInk = Color(0xFF5FF0E0),
        displayGlow = 7.dp,
        display = Vt323Family,
        displaySize = 19.sp,
        key = Color(0xFF2E3432),
        keyEdge = Color(0xFF1A201E),
        keyInk = Color(0xFFE4E8E6),
        keyDim = Color(0xFF8A9290),
        keyRadius = 5.dp,
        keyType = JetBrainsMonoFamily,
        keyTypeSize = 8.sp,
        groove = Color(0xFF2A302E),
        cap = Color(0xFFC8CCCA),
        accent = Color(0xFF5FF0E0)
    ),

    /**
     * 1998, the beige PC it all got ripped onto: bevelled grey chrome and four-pixel type.
     *
     * The other half of the decade — a *software* memory rather than a hardware one. Everything
     * is drawn with a hard white top-left edge and a hard black bottom-right one, because that is
     * how a widget was made to look raised before anyone had a shadow to spare.
     */
    SKIN(
        displayName = "Skin",
        brand = "JAWON",
        model = "v2.95",
        plate = Color(0xFF3E3E3E),
        plateLow = Color(0xFF333333),
        plateEdge = Color(0xFF1E1E1E),
        plateInk = Color(0xFFD8D8D8),
        plateDim = Color(0xFF909090),
        grain = 0f,
        bevelled = true,
        window = Color(0xFF050805),
        windowEdge = Color(0xFF1A1A1A),
        windowRadius = 1.dp,
        displayInk = Color(0xFF25F06A),
        displayGlow = 5.dp,
        display = SilkscreenFamily,
        displaySize = 11.sp,
        key = Color(0xFF4A4A4A),
        keyEdge = Color(0xFF242424),
        keyInk = Color(0xFFE8E8E8),
        keyDim = Color(0xFF9A9A9A),
        keyRadius = 1.dp,
        keyType = SilkscreenFamily,
        keyTypeSize = 6.sp,
        groove = Color(0xFF1E1E1E),
        cap = Color(0xFFC0C0C0),
        accent = Color(0xFF25F06A)
    );

    /** True when the plate is lighter than its own ink — the finishes that invert the app. */
    val isPale: Boolean get() = this == SEPARATES

    companion object {
        /** Tolerant of a stored name that no longer exists, e.g. after a finish is renamed. */
        fun fromName(name: String?): DeckFinish =
            entries.firstOrNull { it.name == name } ?: SHOP
    }
}

/**
 * The finish in force, for everything the deck draws.
 *
 * A composition local rather than a parameter threaded through twenty composables: the rack is
 * eight cells, three faders, a window and a plate, and every one of them needs the same dozen
 * colours. `static` because it changes about once a month, when someone opens Settings — the
 * cheaper read is worth far more here than cheap invalidation.
 */
val LocalDeckFinish = staticCompositionLocalOf { DeckFinish.SHOP }

/**
 * The deck's own surface: a scrim over the room, or a solid faceplate laid on top of it.
 *
 * **The two behave differently on purpose.** [DeckFinish.SHOP] is a *scrim* — it fades in from
 * nothing so the room still reads through the panel, which is what makes the deck feel like part
 * of the same space as the shelves. A machine does not do that. Aluminium is opaque, so the
 * period finishes get a hard top edge and a solid plate, and the effect is of an object sliding
 * up over the room rather than a darkening of it.
 *
 * @param sheetFraction how tall the panel currently is, as a fraction of the screen. Only the
 *   scrim needs it: its fade is held to a fixed *screen* distance rather than a fixed fraction of
 *   the panel, so dragging the deck taller does not turn a narrow gradient into a wide one that
 *   sits over the spinning disc.
 */
fun Modifier.deckPlate(finish: DeckFinish, sheetFraction: Float): Modifier = when (finish) {
    DeckFinish.SHOP -> background(
        Brush.verticalGradient(
            0f to Color.Transparent,
            (SCRIM_FADE_FRACTION / sheetFraction.coerceAtLeast(0.05f))
                .coerceIn(0.08f, 0.32f) to Color(0xD1060403),
            1f to Color(0xF7060403)
        )
    )

    else -> background(Brush.verticalGradient(listOf(finish.plate, finish.plateLow)))
        .drawBehind {
            // A machined front: fine vertical hairlines, and a lit top edge where the light
            // catches the bevel. Both are cheap and both are most of what says "metal".
            if (finish.grain > 0f) {
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color.White.copy(alpha = finish.grain),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += GRAIN_SPACING_PX
                }
            }
            drawLine(
                color = Color.White.copy(alpha = if (finish.bevelled) 0.16f else 0.34f),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 2f
            )
        }
}

/** How far down the screen the shop scrim finishes fading in. Tuned, not derived. */
private const val SCRIM_FADE_FRACTION = 0.22f

/** Hairline spacing on a brushed plate. Close enough to read as grain, far enough to stay cheap. */
private const val GRAIN_SPACING_PX = 3f
