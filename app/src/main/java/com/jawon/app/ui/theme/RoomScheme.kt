package com.jawon.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * What colour the room is lit and built in.
 *
 * **This is a recolour, not a second room.** `ENGINEERING.md` locks the room style to Midnight
 * Shop — "one room, done excellently" — and that stands: the geometry, the lamp's position,
 * the shelving, the dust and the whole open sequence are identical in every scheme. What
 * changes is the bulb and the finish on the wood, the way the same shop looks under a
 * different light. The locked decision rules out building *more rooms* (the vinyl and
 * cassette modes are three more rooms before one is finished); it does not require the one
 * room to be exactly one colour forever.
 *
 * Four, and each one is a whole look rather than a palette: the room, the light in it, and the
 * machine you play the record on, chosen together. They were briefly two settings and the
 * result was a deck that did not belong to its own room. Each has to be tuned against real
 * artwork on a real screen, so a dozen half-considered ones would be worth less than four that
 * were looked at.
 *
 * **Not everything can change while the app is running.** The lamp and rim colours are
 * written onto the live lights, and the wall and shelf materials rebuild with the scheme, so
 * those switch instantly. The room's image-based ambient light is a cubemap baked and
 * prefiltered into a mip chain before the first frame, and rebuilding *that* mid-session
 * would mean destroying Filament resources in a scene whose node lifetimes have already cost
 * this project three separate bugs. So the ambient follows on the next launch, and Settings
 * says so rather than leaving the user to notice.
 */
enum class RoomScheme(
    /**
     * What the mode is called on screen — **Latin, and one word each**.
     *
     * The names were "Midnight Shop", "Cold Storage", "After Hours" and "Workshop": accurate
     * descriptions, and descriptions are the problem. Each one explains the room instead of
     * naming it, two of them are the same length as the card they sit on, and together they read
     * as a list of settings rather than as four places you could be.
     *
     * Latin does what is wanted here for a reason that is not decoration: a single word that
     * carries a whole scene without describing it. Every one of these is an ordinary Latin noun
     * meaning exactly what the room is, so the name is precise as well as short —
     *
     * * **Vesper** — evening; the evening star. The warm amber hour after closing.
     * * **Glacies** — ice.
     * * **Vigilia** — a watch of the night. The Romans divided the night into four of them, and
     *   this is the small hours, which is what "After Hours" was reaching for.
     * * **Officina** — a workshop. The literal word, and the room is lit like a bench.
     *
     * **This is the label only.** The enum constant is what gets written to preferences, so the
     * names above can be changed freely without resetting anybody's chosen room — which is the
     * whole reason the two are separate fields rather than one.
     */
    val displayName: String,
    /** The lamp. Linear-ish RGB, matching `RoomLighting.KEY_COLOR`'s scale. */
    val key: FloatArray,
    /**
     * **The strip light's colour — this room's own, and no other room's.**
     *
     * The tube along the top of every shelf is the room's light now, and it was the one thing
     * that did not change with the room: a fixed amber in all four, while the light it threw
     * came from [key]. On the owner's phone, in Vigilia, that was an orange tube over a cool
     * lavender panel — the tube plainly not being what lit the wall, which was his standing
     * complaint about the fixture. So the tube and its light are one value now, and each room
     * has its own: amber in Vesper, ice in Glacies, cyan in Vigilia, phosphor in Officina.
     *
     * This is the **emissive**, in the unlit material's linear units, and two rules shape
     * every entry:
     *
     * - **One channel dominant and the rest held well below it.** Three channels comfortably
     *   over 1.0 all arrive at the same place on the ACES shoulder, and the tube renders as a
     *   white bar — the first version of the amber, (2.1, 1.65, 1.15), did exactly that. The
     *   ratio is what survives tonemapping as a colour.
     * - **The same brightness in every room**, about 1.9 in luminance (Rec. 709 weights). A green
     *   at the amber's channel values would be a third brighter, because the eye is most
     *   sensitive to green; equal luminance means switching rooms changes the colour of the
     *   light and not how much of it there is.
     *
     * The light the tube throws is this with its brightest channel scaled to 1 — see
     * [tubeLight] — so the panel's gradient is always the tube's colour.
     */
    val tube: FloatArray,
    /** The coloured bounce, low and to the left. */
    val rim: FloatArray,
    /** Ambient fill — baked into the IBL, so this one waits for a relaunch. */
    val ambient: FloatArray,
    /** What the room falls off to at its edges: fog and the void behind everything. */
    val void: FloatArray,
    /** The panel behind the shelves. */
    val wall: Color,
    /** The shelf boards themselves. */
    val board: Color,
    /** The 2D accent — labels, the version stamp, confirm buttons. */
    val accent: Color,
    /**
     * What the deck in this room is made of, and what glows in its window.
     *
     * **The deck is part of the scheme, not a second setting.** They were separate for exactly
     * one release, and the result was a machine that did not belong to the room it was standing
     * in — a cold grey front in a warm shop. A room and the thing you play records on in it are
     * one design decision, so this is one choice.
     */
    val plate: DeckPlate,
    val glass: DeckWindowGlass,
    /**
     * The whole app in this mode, not just the room.
     *
     * Settings, Add Album, the shelf manager and the deck all read from here, so picking a mode
     * changes every screen rather than only the lamp. That was the gap: a scheme used to recolour
     * the room and one accent and leave the rest of the app the same brown.
     */
    val palette: Palette
) {
    /**
     * The original, and the one everything else was tuned against: a warm tungsten spot in a
     * dark shop, on old brown shelving. `DESIGN.md` §3's palette exactly.
     */
    MIDNIGHT_SHOP(
        displayName = "Vesper",
        key = floatArrayOf(1.0f, 0.824f, 0.627f),
        // Tungsten amber — the tube this room has always had, unchanged.
        tube = floatArrayOf(3.10f, 1.62f, 0.62f),
        rim = floatArrayOf(0.941f, 0.663f, 0.235f),
        ambient = floatArrayOf(0.251f, 0.192f, 0.165f),
        void = floatArrayOf(0.071f, 0.055f, 0.043f),
        // **Lifted from 0xFF1A1410, which was about one per cent reflectance.**
        //
        // The owner's target photograph is a bookcase with a concealed strip in each bay: the
        // back panel is light oak and carries a warm gradient, hottest where the fixture is and
        // dying toward the shelf below, and that gradient is the whole picture. A one per cent
        // panel cannot show it. Light falling on a surface that returns a hundredth of it is
        // not a lighting problem with a lighting fix — at that albedo the wash would have to be
        // a hundred times stronger to read, and everything else in the room would blow out
        // first.
        //
        // The room stays dark, because darkness now comes from *falloff* rather than from the
        // surface having nothing to give back — which is reference note 3, "light falls off
        // fast and visibly", and is how the photograph works too: the lit bays glow and the
        // wood between them goes black.
        wall = Color(0xFF56422F),
        board = Color(0xFF2F2318),
        accent = MidnightPalette.lampAmber,
        plate = DeckPlate.GLASS,
        glass = DeckWindowGlass.AMBER,
        palette = MidnightPalette
    ),

    /**
     * Carbonite: a cold slate room with one hot process running in it.
     *
     * **This was blue and it should not have been.** It ran a bright cyan against After Hours'
     * bright cyan, a few degrees apart, so the two modes read as one idea at two brightnesses
     * rather than as two different places. Cold is a *temperature*, and a room can be cold in
     * its slate while everything that means something in it burns orange — which also happens
     * to be the furthest hue from both of After Hours' two.
     *
     * Still deliberately the least *cosy* of the four. Warm light flatters artwork by pushing
     * everything toward orange; here the light is not warm, only the readouts are, so covers
     * stay closer to their printed colours.
     */
    COLD_STORAGE(
        displayName = "Glacies",
        // **A cold key and a hot rim, and no cyan anywhere.**
        //
        // The key stays a near-white with the faintest blue in it, which is what a cold room
        // actually looks like and what leaves a sleeve nearest its printed colours. The warmth
        // is entirely in the rim, where there is no artwork to ruin — so a case is lit cold from
        // the front and caught orange down one edge, which is the whole carbonite idea in one
        // object: something cold with a hot process running through it.
        //
        // Same discipline as After Hours, opposite temperatures.
        key = floatArrayOf(0.878f, 0.906f, 0.949f),
        // Ice: a cold white with the blue held just far enough ahead to survive the shoulder
        // as a tint. Deliberately pale, and that paleness is what keeps it apart from Vigilia's
        // cyan — this room's own history is two modes reading as one idea in two blues. The
        // light here is cold; only the readouts burn.
        tube = floatArrayOf(1.45f, 1.95f, 3.10f),
        rim = floatArrayOf(0.800f, 0.376f, 0.118f),
        ambient = floatArrayOf(0.114f, 0.133f, 0.157f),
        void = floatArrayOf(0.031f, 0.039f, 0.051f),
        // Lifted with the others — see MIDNIGHT_SHOP's wall for the reasoning.
        wall = Color(0xFF2C333B),
        // The board is the one surface the hot rim actually grazes, and at the wall's own value
        // it had nothing to catch the light with — a rim light needs something to land on or it
        // is only a colour in the air. Lifted just far enough off the wall behind it that the
        // shelf edge reads as a lit surface rather than as a line.
        board = Color(0xFF1A2028),
        // Molten orange, so the room and the machine in it agree about what colour the app is.
        accent = Color(0xFFFF6B1A),
        plate = DeckPlate.BRUSHED,
        glass = DeckWindowGlass.PLASMA,
        palette = ColdPalette
    ),

    /**
     * A late-night bar back room: deep magenta key, red bounce, plum shelving.
     *
     * The kept warning about this one is that a strongly coloured lamp fights the artwork it
     * is lighting — so the key is pulled well back from saturated pink toward a dusty rose,
     * and the *rim* carries the colour instead. A cover still reads; the room around it does
     * not look grey.
     */
    AFTER_HOURS(
        displayName = "Vigilia",
        // A cold key and a hot bounce, which is the whole cyberpunk trick: you read the room by
        // the cyan and you feel it by the magenta. The key stays near-white for the reason kept
        // below — a strongly coloured lamp fights the artwork it is lighting — so the colour
        // lives in the rim, where there is no album cover to ruin.
        key = floatArrayOf(0.800f, 0.949f, 1.0f),
        // Electric cyan, the room's own accent: fully saturated where Glacies is pale. You read
        // this room by the cyan, and now the cyan is the light the shelves are lit by.
        tube = floatArrayOf(0.12f, 2.26f, 3.60f),
        rim = floatArrayOf(1.0f, 0.176f, 0.584f),
        ambient = floatArrayOf(0.043f, 0.055f, 0.110f),
        void = floatArrayOf(0.020f, 0.020f, 0.039f),
        // Lifted with the others, and least of the three: this room is the darkest by
        // design and the lit bays have to stay the only thing in it.
        wall = Color(0xFF15151F),
        board = Color(0xFF12121F),
        accent = Color(0xFF22E6FF),
        plate = DeckPlate.GLASS,
        glass = DeckWindowGlass.CRT,
        palette = AfterHoursPalette
    ),

    /**
     * A terminal room: absolute black, emerald phosphor, and modules on a bench.
     *
     * The one scheme that abandons the shop entirely. There is no lamp in the fiction here, only
     * the light the equipment makes, which is why the void is true black and the bounce is pure
     * green — and why this is the only place in the app where a pure black is correct rather
     * than lazy.
     */
    WORKSHOP(
        displayName = "Officina",
        // Phosphor, not daylight. The key is a cold green-white and the bounce is pure emerald,
        // so a sleeve on the shelf reads the way a photograph does on a monitor in a dark room.
        key = floatArrayOf(0.784f, 1.0f, 0.847f),
        // Phosphor green. Lower than the others in its dominant channel because green is the
        // brightest thing the eye sees: at the amber's 3.1 this tube would be a third brighter
        // than every other room's.
        tube = floatArrayOf(0.33f, 2.48f, 0.76f),
        rim = floatArrayOf(0.0f, 0.902f, 0.294f),
        ambient = floatArrayOf(0.055f, 0.129f, 0.078f),
        void = floatArrayOf(0.0f, 0.0f, 0.0f),
        // **Lifted off true black, and only the panel.** The void and the fog stay black —
        // that is still this room. But the panel is what the tube lights, and a surface with
        // zero albedo returns nothing whatever lands on it: the strip would have glowed over a
        // wall that stayed dead black, which is the tube failing to emit in the one room built
        // around emitted light. Near-black green, about as dark as Vigilia's panel, which is the
        // darkest a panel has been and still shown its gradient on the owner's phone.
        wall = Color(0xFF141C17),
        board = Color(0xFF07140B),
        accent = Color(0xFF00FF41),
        plate = DeckPlate.CHROME,
        glass = DeckWindowGlass.PIXEL,
        palette = WorkshopPalette
    );

    /**
     * The colour of the light the tube throws: [tube] with its brightest channel scaled to 1.
     *
     * Everything the strip lights takes its colour from here — the diodes on the panel and the
     * strip's reflection baked into the room's cubemap — so the gradient on the wall and the
     * line in every glossy case are the tube's colour by construction, never a second value
     * that can drift away from it. How *bright* the light is stays `RoomLighting.WASH_CANDELA`.
     */
    val tubeLight: FloatArray
        get() {
            val peak = maxOf(tube[0], tube[1], tube[2])
            return floatArrayOf(tube[0] / peak, tube[1] / peak, tube[2] / peak)
        }

    companion object {
        /** Tolerant of a stored name that no longer exists, e.g. after a scheme is renamed. */
        fun fromName(name: String?): RoomScheme =
            entries.firstOrNull { it.name == name } ?: MIDNIGHT_SHOP
    }
}
