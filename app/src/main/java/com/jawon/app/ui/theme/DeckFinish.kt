package com.jawon.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the deck's front is made of.
 *
 * **Every plate is translucent, and that is the whole correction.** The first attempt shipped an
 * opaque brushed-aluminium slab, and the owner's read was that the deck had stopped feeling like
 * it was in the same room as the disc. He was right, and putting the same rack on the old glass
 * scrim beside it showed why in one glance: through the scrim you can see the disc, the wall and
 * the covers behind the panel; the metal was a neutral sheet with no light on it at all. A
 * surface nothing illuminates cannot share a room with anything.
 *
 * So a plate is a *material*, never a wall. It lets the room through, and it takes part of its
 * colour from the lamp lighting that room — see [DeckFinish.of].
 */
enum class DeckPlate(
    /** A model number nobody ever read. Flavour, and free. */
    val model: String,
    /** The material's own colour, before the room's light is mixed into it. */
    val base: Color,
    /** How much of the room the plate hides. Glass barely any; metal most. */
    val opacity: Float,
    /** How strongly the lamp tints the material. Metal catches light; glass hardly does. */
    val litness: Float,
    /** Fine vertical grain, as on a machined front. Zero is a smooth plate. */
    val grain: Float,
    /** Hard highlight and shadow on every edge, the way a 1998 widget was drawn raised. */
    val bevelled: Boolean,
    /** True when the plate ends up lighter than its own lettering. */
    val pale: Boolean,
    val cornerRadius: Dp
) {
    /** The room's own darkness, continued. */
    GLASS(model = "", base = Color(0xFF0B0806), opacity = 0.86f, litness = 0.05f, grain = 0f, bevelled = false, pale = false, cornerRadius = 7.dp),

    /** Machined aluminium, and the lamp lands on it. */
    BRUSHED(model = "CDP-700", base = Color(0xFFBEC2C0), opacity = 0.80f, litness = 0.34f, grain = 0.09f, bevelled = false, pale = true, cornerRadius = 5.dp),

    /** Bevelled grey, and almost no radius at all. */
    CHROME(model = "v2.95", base = Color(0xFF3E3E3E), opacity = 0.86f, litness = 0.16f, grain = 0f, bevelled = true, pale = false, cornerRadius = 2.dp)
}

/**
 * What kind of glass the window is, and what glows behind it.
 *
 * Kept apart from the plate because the owner made two separate judgements about the first
 * period finish — he liked the display and disliked the slab it was bolted to. Two judgements,
 * two things.
 */
enum class DeckWindowGlass(
    val glass: Color,
    val edge: Color,
    val radius: Dp,
    val ink: Color,
    /** How far the display's own light bleeds past its segments. Zero for a reflective LCD. */
    val glow: Dp,
    val type: FontFamily,
    val size: TextUnit
) {
    /** The shop's own: warm mono on near-black. */
    AMBER(Color(0xFF0E0B09), Color(0xFF2A2119), 5.dp, MidnightPalette.lampAmber, 0.dp, JetBrainsMonoFamily, 13.sp),

    /**
     * A vacuum-fluorescent tube. Cyan-green, enormous, and legible at arm's length across a dark
     * room — which is exactly the situation this app is always in. The rare piece of retro that
     * is also genuinely better at the job.
     */
    TUBE(Color(0xFF0A100E), Color(0xFF2A302E), 4.dp, Color(0xFF5FF0E0), 7.dp, Vt323Family, 19.sp),

    /** A neon bar sign behind glass — the one display that is not a machine readout. */
    ROSE(Color(0xFF120A0E), Color(0xFF2E1A22), 5.dp, Color(0xFFFF5C8A), 6.dp, Vt323Family, 19.sp),

    /** Four-pixel green on black: the desktop player, not the hi-fi. */
    PIXEL(Color(0xFF050805), Color(0xFF1A1A1A), 2.dp, Color(0xFF25F06A), 5.dp, SilkscreenFamily, 11.sp)
}


/**
 * A style, resolved against the room that is lighting it.
 *
 * Everything the deck draws reads from here, so no composable has to know that a finish is
 * assembled from a plate, a glass and a room rather than picked off a list.
 */
data class DeckFinish(
    val brand: String,
    val model: String,

    val plate: Color,
    val plateLow: Color,
    val plateEdge: Color,
    val plateInk: Color,
    val plateDim: Color,
    val texture: SurfaceTexture,
    val sheen: Float,
    val glow: Float,
    val bevelled: Boolean,
    val plateOpacity: Float,

    val window: Color,
    val windowEdge: Color,
    val windowRadius: Dp,
    val displayInk: Color,
    val displayGlow: Dp,
    val display: FontFamily,
    val displaySize: TextUnit,

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
    companion object {

        /**
         * Build the finish the deck will actually wear.
         *
         * **The room is an ingredient.** A plate's colour is its own material mixed with the
         * colour of the lamp shining on it, in the proportion the material deserves: metal
         * catches a great deal of the room and glass almost none. That one mix is what makes a
         * brushed front sit *in* the midnight shop rather than on top of it, and it is why the
         * same plate is a warm pewter under tungsten and a cold one under a striplight.
         *
         * The keys stay translucent in every combination. A key you can see the record through
         * is the difference between a panel in a room and a panel over a picture of one.
         */
        fun of(scheme: RoomScheme): DeckFinish {
            val plate = scheme.plate
            val glass = scheme.glass

            val lamp = Color(scheme.key[0], scheme.key[1], scheme.key[2])
            val lit = lerp(plate.base, lamp, plate.litness)

            // The plate falls off toward the bottom, away from the lamp above it — the same
            // reason the room's own wall darkens toward the floor.
            val low = lerp(lit, Color.Black, if (plate.pale) 0.18f else 0.34f)
            // On a dark plate the deck simply uses the mode's own lettering; a pale one has to
            // invert, because cream on aluminium is unreadable.
            val ink = if (plate.pale) Color(0xFF1C201E) else scheme.palette.labelCream
            val dim = if (plate.pale) Color(0xFF5A605E) else scheme.palette.labelDim

            // Keys are wells cut into the plate: darker than it, and see-through, so the room
            // reads faintly through the whole rack.
            val keyBase = lerp(lit, Color.Black, if (plate.pale) 0.80f else 0.40f)

            return DeckFinish(
                brand = "JAWON",
                model = plate.model,

                plate = lit,
                plateLow = low,
                plateEdge = lerp(lit, Color.Black, 0.34f),
                plateInk = ink,
                plateDim = dim,
                texture = scheme.palette.texture,
                sheen = scheme.palette.sheen,
                glow = scheme.palette.glow,
                bevelled = plate.bevelled,
                plateOpacity = plate.opacity,

                window = glass.glass,
                windowEdge = glass.edge,
                windowRadius = glass.radius,
                displayInk = glass.ink,
                displayGlow = glass.glow,
                display = glass.type,
                displaySize = glass.size,

                key = keyBase.copy(alpha = KEY_ALPHA),
                keyEdge = lerp(lit, Color.Black, 0.55f).copy(alpha = KEY_EDGE_ALPHA),
                keyInk = if (plate.pale) Color(0xFFE8ECEA) else scheme.palette.labelCream,
                keyDim = if (plate.pale) Color(0xFF9AA2A0) else scheme.palette.labelDim,
                // The mode says how soft an edge is — neon is round, a workbench is square — and the
                // plate only gets a say when it is a period object with corners of its own.
                keyRadius = if (plate == DeckPlate.GLASS) scheme.palette.corner else plate.cornerRadius,
                keyType = if (plate.bevelled) SilkscreenFamily else JetBrainsMonoFamily,
                keyTypeSize = if (plate.bevelled) 6.sp else 8.sp,
                groove = lerp(keyBase, Color.Black, 0.35f).copy(alpha = GROOVE_ALPHA),
                cap = if (plate.pale) Color(0xFF2B2520) else Color(0xFFCFC2AA),

                // The accent follows the glass, so the lit tab, the transport and the display
                // are all one colour. Two accents on one panel is what "assembled" looks like.
                accent = glass.ink
            )
        }

        /** What the deck looks like before anyone has chosen anything. */
        val Default: DeckFinish get() = of(RoomScheme.MIDNIGHT_SHOP)
    }
}

/** Keys are wells you can see the room through, never solid tiles. */
private const val KEY_ALPHA = 0.55f
private const val KEY_EDGE_ALPHA = 0.40f
private const val GROOVE_ALPHA = 0.45f

/**
 * The deck's own surface: a material laid over the room, never a wall built across it.
 *
 * Two things make it belong. It is **translucent**, so the disc and the covers read through it —
 * which is exactly what was missing when the plate was opaque. And it carries the same
 * **fall-off** the room does, fading in from nothing at the top and settling darker at the
 * bottom, which is the cheapest possible cue that a light source exists somewhere above it.
 *
 * @param sheetFraction how tall the panel currently is, as a fraction of the screen. The fade is
 *   held to a fixed *screen* distance rather than a fraction of the panel, so dragging the deck
 *   taller does not turn a narrow gradient into a wide one sitting over the spinning disc.
 */
fun Modifier.deckPlate(finish: DeckFinish, sheetFraction: Float): Modifier =
    background(
        Brush.verticalGradient(
            0f to Color.Transparent,
            (SCRIM_FADE_FRACTION / sheetFraction.coerceAtLeast(0.05f))
                .coerceIn(0.06f, 0.30f) to finish.plate.copy(alpha = finish.plateOpacity * 0.9f),
            1f to finish.plateLow.copy(alpha = finish.plateOpacity)
        )
    ).drawBehind {
        when (finish.texture) {
            // A machined front: fine vertical hairlines. Cheap, and most of what says "metal".
            SurfaceTexture.GRAIN -> {
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color.White.copy(alpha = GRAIN_ALPHA * finish.plateOpacity),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += GRAIN_SPACING_PX
                }
            }

            // A printed dot grid. The workbench mode refuses to gleam, so its surface has to
            // say something by being *printed* instead of by catching light.
            SurfaceTexture.DOTS -> {
                var y = DOT_SPACING_PX
                while (y < size.height) {
                    var x = DOT_SPACING_PX
                    while (x < size.width) {
                        drawCircle(
                            color = Color.White.copy(alpha = DOT_ALPHA),
                            radius = 1.2f,
                            center = Offset(x, y)
                        )
                        x += DOT_SPACING_PX
                    }
                    y += DOT_SPACING_PX
                }
            }

            SurfaceTexture.NONE -> Unit
        }

        // A raking band of light travelling down the plate. Only metal does this, so on every
        // other mode the sheen is zero and this costs one comparison.
        if (finish.sheen > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0f),
                    SHEEN_CENTRE to Color.White.copy(alpha = finish.sheen * SHEEN_PEAK),
                    SHEEN_CENTRE + SHEEN_WIDTH to Color.White.copy(alpha = 0f),
                    1f to Color.White.copy(alpha = 0f)
                )
            )
        }
    }

/** How far down the screen the plate finishes fading in. Tuned, not derived. */
private const val SCRIM_FADE_FRACTION = 0.22f

/** Hairline spacing on a brushed plate. Close enough to read as grain, far enough to stay cheap. */
private const val GRAIN_SPACING_PX = 3f
private const val GRAIN_ALPHA = 0.20f

/** The printed grid on a workbench surface. Wide enough to read as paper, not as noise. */
private const val DOT_SPACING_PX = 20f
private const val DOT_ALPHA = 0.13f

// Where the raking light crosses the plate, and how strong it is at its brightest.
private const val SHEEN_CENTRE = 0.14f
private const val SHEEN_WIDTH = 0.34f
private const val SHEEN_PEAK = 0.26f

/**
 * The finish in force, for everything the deck draws.
 *
 * A composition local rather than a parameter threaded through twenty composables: the rack is
 * eight cells, three faders, a window and a plate, and every one of them needs the same dozen
 * colours. `static` because it changes about once a month, when someone opens Settings — the
 * cheaper read is worth far more here than cheap invalidation.
 */
val LocalDeckFinish = staticCompositionLocalOf { DeckFinish.Default }
