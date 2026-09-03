package com.jawon.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a mode arranges its rack.
 *
 * **A mode is not a palette on one layout.** Two attempts at giving each mode its own look
 * failed because they were rearrangements of the same grid — eight keys in a row is still eight
 * keys, whichever way they are pushed around. These start from an object instead, and the
 * controls fall out of it.
 */
enum class RackLayout {
    /** Window across the top, a bank of keys, faders beneath. The shop counter. */
    SHELF,

    /** Turned on its side: a tall window down the left, keys stacked beside it. A component. */
    RACK_UNIT,

    /**
     * The effects orbit the disc.
     *
     * No grid at all — eight lit studs in an arc, with the selected effect's controls in the
     * middle. The only layout here that could not be any other app, and the only one that stops
     * the panel covering the record.
     */
    RING,

    /**
     * A rack of modules, each as wide as it is complicated.
     *
     * The echo is wide because it has three knobs; the drive is a sliver because it has one. The
     * panel shows you where the depth is instead of hiding it behind a uniform grid.
     */
    MODULAR
}

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
    val corner: Dp,

    /** How this mode arranges its rack. See [RackLayout]. */
    val layout: RackLayout
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
    corner = 7.dp,
    layout = RackLayout.SHELF
)

/**
 * Carbonite: cold slate, and the only warmth in the room coming off the process itself.
 *
 * **It used to be blue, and that was the problem.** This mode ran a bright cyan signal at
 * `#5FF0E0` while After Hours runs one at `#22E6FF` — two cyans a few degrees apart, which does
 * not read as two rooms. It reads as one idea at two brightnesses, and it wasted the fact that
 * these are supposed to be four different places.
 *
 * So it stays cold and stops being blue *about* it. The ground is a near-black blue-grey — the
 * temperature of the room is in the slate, where a cold room's temperature actually lives — and
 * everything that means something is molten orange, which is the furthest hue on the wheel from
 * both of After Hours' two and the one that blooms best on an OLED panel.
 *
 * Deliberately still the least cosy of the four: warm *light* flatters a sleeve by pushing it
 * toward orange, and here the light is not warm, only the readouts are. Covers stay near their
 * printed colours, which is what you want when you are looking at the artwork rather than
 * sitting in the room.
 */
val ColdPalette = Palette(
    // Lifted off pure black, though less than it was: pure black flattens the elevation between
    // a ground and the things standing on it, and that elevation is the one thing this mode has
    // instead of atmosphere.
    crateBlack = Color(0xFF0C0E11),
    crateRaised = Color(0xFF161B21),
    crateEdge = Color(0xFF1E242B),
    labelCream = Color(0xFFB8C2CE),
    // **Lifted, because the first value did not have the contrast to be read.**
    //
    // Measured against this mode's own ground rather than guessed at. On `#0C0E11`, the
    // original `#5A6674` came out at 3.3:1 — under the 4.5:1 that ordinary text needs, and this
    // colour carries the explanatory lines under every setting, which is exactly the text
    // somebody is reading carefully when they read it at all. `#6E7C8C` lands at 4.5:1 and is
    // still clearly secondary to `labelCream`, which sits at 10.6:1.
    //
    // The other two were already fine and are left alone: the amber is 6.7:1 on the same
    // ground, and the cream 10.6:1.
    labelDim = Color(0xFF6E7C8C),
    lampAmber = Color(0xFFFF6B1A),
    oxideTeal = Color(0xFF8A5A3C),
    // Hotter than it was. The old 0.45 was a striplight travelling across brushed metal; this is
    // a single hot process in a cold room, and the one warm thing in shot has to actually burn.
    glow = 0.7f,
    sheen = 0.55f,
    texture = SurfaceTexture.GRAIN,
    corner = 4.dp,
    layout = RackLayout.RACK_UNIT
)

/**
 * Two in the morning, in the rain, in 1984.
 *
 * **Cyan and magenta on wet black.** Not scarlet on oxblood — that was one hue lighting one
 * room, and cyberpunk is two hues fighting: a cold one you read by and a hot one that means
 * something is happening. The ground goes almost to black so both of them can be the only light
 * in the frame, which is the entire look.
 *
 * The second signal colour earns its keep here for the first time. Everywhere else it is a
 * near-unused teal; here it is the magenta, and the two together are the mode.
 */
val AfterHoursPalette = Palette(
    crateBlack = Color(0xFF05050A),
    crateRaised = Color(0xFF0E0E1A),
    crateEdge = Color(0xFF22223A),
    labelCream = Color(0xFFE6E9F5),
    labelDim = Color(0xFF7A7F9E),
    lampAmber = Color(0xFF22E6FF),
    oxideTeal = Color(0xFFFF2D95),
    // All bleed, no surface. Neon is only light — nothing here is machined and every lit thing
    // spills onto its neighbours. The corners go hard: a rounded rectangle is a nineties idea,
    // and this decade cut its corners at forty-five degrees or left them square.
    glow = 1f,
    sheen = 0f,
    texture = SurfaceTexture.NONE,
    corner = 2.dp,
    layout = RackLayout.RING
)

/**
 * A terminal in the dark: absolute black, and emerald.
 *
 * **The one mode that uses true black, and the only place this app should.** Everywhere else
 * `DESIGN.md` §2 is right that pure black flattens depth and hides elevation — but that rule is
 * written for surfaces lit by something. Nothing here is lit. The ground is void, the only light
 * in the frame is the phosphor, and on an OLED panel the black pixels are genuinely off, which
 * is what makes the green look like it is floating rather than printed.
 *
 * Depth comes from the glow instead of from elevation, which is why this mode's glow is high
 * where it used to be zero.
 */
val WorkshopPalette = Palette(
    crateBlack = Color(0xFF000000),
    crateRaised = Color(0xFF050C07),
    crateEdge = Color(0xFF10331A),
    labelCream = Color(0xFFB9FFC9),
    labelDim = Color(0xFF3E8A50),
    lampAmber = Color(0xFF00FF41),
    oxideTeal = Color(0xFF00A82B),
    // Phosphor on void. The glow went from zero to nearly full when this stopped being a
    // concrete workbench and became a terminal: with a black ground and no elevation, the bleed
    // is the only thing giving the panel any depth at all. Square corners, because a module is
    // a milled aluminium panel and those have never been round.
    glow = 0.9f,
    sheen = 0.05f,
    texture = SurfaceTexture.NONE,
    corner = 0.dp,
    layout = RackLayout.MODULAR
)
