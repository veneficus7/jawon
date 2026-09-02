package com.jawon.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What a mode's surfaces are *made of*, past their colour.
 *
 * **Four hex values is not a design.** The first cut of the modes changed the palette and nothing
 * else, and they read as one app tinted four ways — which is exactly what they were. A mode is a
 * material: how much its lit things bleed, whether light travels across its surfaces, and what
 * the surface itself is made of. Those three differ far more between a neon bar and a concrete
 * workbench than any colour does.
 */
enum class SurfaceTexture {
    /** Nothing. Light falls on a smooth surface and that is all. */
    NONE,

    /** Fine vertical machining, as on an aluminium front. */
    GRAIN,

    /** A printed dot grid — drafting paper, or the silkscreen on a workbench. */
    DOTS
}

/**
 * Every colour and material the app's flat screens are built from.
 *
 * **This used to be seven constants, and that was the whole problem.** A scheme could recolour
 * the room's lamp and one accent, and then Settings, Add Album, the shelf manager and the deck
 * all stayed the same brown no matter which one you picked — so choosing a scheme changed the
 * *room* and not the app. A mode is supposed to be a design, not a bulb.
 *
 * Never pure black or pure white in any of them, per `DESIGN.md` §2. A shop lit by one lamp has
 * no true black in it and nothing in it is paper.
 */
data class Palette(
    /** The ground everything sits on. */
    val crateBlack: Color,
    /** A surface lifted off that ground: cards, rows, wells. */
    val crateRaised: Color,
    /** Hairlines, borders, the edge of everything. */
    val crateEdge: Color,
    /** Lettering. */
    val labelCream: Color,
    /** Lettering that is not the point. */
    val labelDim: Color,
    /** The one colour that means *this*: a lit tab, a confirm, the lamp itself. */
    val lampAmber: Color,
    /** A second, cooler signal colour. Used sparingly — mostly the echo. */
    val oxideTeal: Color,

    // ---- the material ----------------------------------------------------------------

    /**
     * How far a lit thing bleeds into what is around it, 0 to 1.
     *
     * A neon sign in a bar spills across everything near it; a printed label on a workbench
     * spills nothing at all. This is the single biggest reason two modes with the same layout
     * do not look like the same app.
     */
    val glow: Float,

    /**
     * A band of light travelling across a surface, 0 to 1.
     *
     * What a raking light does to machined metal and does not do to concrete. This is also the
     * only glass cue available here: real backdrop blur needs [android.graphics.RenderEffect],
     * which blurs a composable's *own* content — and the thing behind this panel is a Filament
     * surface rather than Compose content, so there is nothing for it to blur. A sheen and a lit
     * edge is what the research calls the low-end fallback; here it is the only route.
     */
    val sheen: Float,

    /** What the surface itself is made of. */
    val texture: SurfaceTexture,

    /** How soft the corners are. Neon is round; a workbench is square. */
    val corner: Dp
)

/**
 * The palette in force.
 *
 * `static` because it changes when somebody opens Settings and picks another mode, which is
 * roughly never — so the cheaper read everywhere else is worth far more than cheap invalidation.
 */
val LocalPalette = staticCompositionLocalOf { MidnightPalette }

// The names below are read by about three hundred call sites across ten screens. They stay
// spelled exactly as they always were, and are now *composable properties* that resolve against
// the mode rather than top-level constants — which is what let the whole app become themeable
// without touching those three hundred lines.

val CrateBlack: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.crateBlack
val CrateRaised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.crateRaised
val CrateEdge: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.crateEdge
val LabelCream: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.labelCream
val LabelDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.labelDim
val LampAmber: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.lampAmber
val OxideTeal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.oxideTeal

/**
 * A warm shop at midnight: tungsten on old brown shelving.
 *
 * `DESIGN.md` §3's palette exactly, and the one every other mode was tuned against.
 */
val MidnightPalette = Palette(
    crateBlack = Color(0xFF17120F),
    crateRaised = Color(0xFF241C17),
    crateEdge = Color(0xFF33291F),
    labelCream = Color(0xFFEFE3CC),
    labelDim = Color(0xFF9A8B76),
    lampAmber = Color(0xFFF0A93C),
    oxideTeal = Color(0xFF2F7D71),
    // Lamplight: a warm bulb bleeds a little, and old wood has no sheen to speak of.
    glow = 0.38f,
    sheen = 0.10f,
    texture = SurfaceTexture.NONE,
    corner = 7.dp
)

/**
 * The same shop under a cold striplight, and the one mode where the app goes properly blue.
 *
 * Deliberately the least cosy: warm light flatters a sleeve by pushing it toward orange, and this
 * leaves covers nearer their printed colours — which is what you want when you are actually
 * looking at the artwork rather than sitting in the room.
 */
val ColdPalette = Palette(
    // Lifted off near-black on purpose: pure black flattens depth and hides the elevation
    // between a ground and the things standing on it, which is the one thing this mode has.
    crateBlack = Color(0xFF121820),
    crateRaised = Color(0xFF1C2632),
    crateEdge = Color(0xFF26323E),
    labelCream = Color(0xFFDCE6F0),
    labelDim = Color(0xFF7F8C99),
    lampAmber = Color(0xFF5FF0E0),
    oxideTeal = Color(0xFF3E7F96),
    // Cold and hard: a striplight travels right across brushed metal, and the machining is
    // visible. The most *material* of the four, and the least atmospheric.
    glow = 0.45f,
    sheen = 0.55f,
    texture = SurfaceTexture.GRAIN,
    corner = 4.dp
)

/** A late-night bar back room: plum surfaces and a neon sign for an accent. */
val AfterHoursPalette = Palette(
    crateBlack = Color(0xFF1A1017),
    crateRaised = Color(0xFF2A1A26),
    crateEdge = Color(0xFF3A2430),
    labelCream = Color(0xFFF0DCE4),
    labelDim = Color(0xFF9C808C),
    lampAmber = Color(0xFFFF5C8A),
    oxideTeal = Color(0xFF7A4C8C),
    // All bleed, no surface. A neon sign is only light — nothing here is machined, nothing
    // catches a raking beam, and every lit thing spills onto its neighbours.
    glow = 1f,
    sheen = 0f,
    texture = SurfaceTexture.NONE,
    corner = 12.dp
)

/**
 * A bench under neutral daylight: concrete, steel, and a green terminal.
 *
 * The only mode with no colour cast at all, which is its entire reason for existing — every
 * sleeve reads exactly as printed, and the artwork becomes the only coloured thing on screen.
 */
val WorkshopPalette = Palette(
    crateBlack = Color(0xFF1C1C1F),
    crateRaised = Color(0xFF26262A),
    crateEdge = Color(0xFF38383E),
    labelCream = Color(0xFFE8E8E6),
    labelDim = Color(0xFF8A8A90),
    lampAmber = Color(0xFF25F06A),
    oxideTeal = Color(0xFF5A8A9A),
    // The anti-glass mode. Nothing glows, nothing gleams, the corners are square and the
    // surface is printed. Everything the other three do, this one refuses to do.
    glow = 0f,
    sheen = 0f,
    texture = SurfaceTexture.DOTS,
    corner = 1.dp
)
