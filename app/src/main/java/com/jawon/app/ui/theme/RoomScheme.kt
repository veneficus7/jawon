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
    val displayName: String,
    /** The lamp. Linear-ish RGB, matching `RoomLighting.KEY_COLOR`'s scale. */
    val key: FloatArray,
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
        displayName = "Midnight Shop",
        key = floatArrayOf(1.0f, 0.824f, 0.627f),
        rim = floatArrayOf(0.941f, 0.663f, 0.235f),
        ambient = floatArrayOf(0.251f, 0.192f, 0.165f),
        void = floatArrayOf(0.071f, 0.055f, 0.043f),
        wall = Color(0xFF1A1410),
        board = Color(0xFF2F2318),
        accent = MidnightPalette.lampAmber,
        plate = DeckPlate.GLASS,
        glass = DeckWindowGlass.AMBER,
        palette = MidnightPalette
    ),

    /**
     * The same shop under a cold striplight — pale blue-white, grey-blue shelving.
     *
     * Deliberately the least *cosy* of the three. Warm light flatters artwork by pushing
     * everything toward orange; this one leaves covers closer to their printed colours, which
     * some people prefer for actually looking at a sleeve.
     */
    COLD_STORAGE(
        displayName = "Cold Storage",
        key = floatArrayOf(0.784f, 0.878f, 1.0f),
        rim = floatArrayOf(0.353f, 0.588f, 0.788f),
        ambient = floatArrayOf(0.157f, 0.192f, 0.235f),
        void = floatArrayOf(0.043f, 0.055f, 0.071f),
        wall = Color(0xFF10141A),
        board = Color(0xFF1E262F),
        // The cyan of the tube, so the room and the machine in it agree about what colour
        // the app is. A striplight over brushed aluminium is the one pairing here that is
        // historically exact: this is what a hi-fi separate actually sat in.
        accent = Color(0xFF5FF0E0),
        plate = DeckPlate.BRUSHED,
        glass = DeckWindowGlass.TUBE,
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
        displayName = "After Hours",
        key = floatArrayOf(1.0f, 0.706f, 0.784f),
        rim = floatArrayOf(0.878f, 0.235f, 0.451f),
        ambient = floatArrayOf(0.235f, 0.149f, 0.196f),
        void = floatArrayOf(0.078f, 0.043f, 0.059f),
        wall = Color(0xFF1A1015),
        board = Color(0xFF2E1B26),
        accent = Color(0xFFFF5C8A),
        plate = DeckPlate.GLASS,
        glass = DeckWindowGlass.ROSE,
        palette = AfterHoursPalette
    ),

    /**
     * A bench under neutral daylight: concrete, steel shelving, and a green terminal.
     *
     * The only scheme with no colour cast at all, which is its whole reason for existing — every
     * sleeve reads exactly as printed instead of being pushed warm or cold, and the artwork
     * becomes the only coloured thing in the room. The deck to match is the bevelled grey one
     * with four-pixel type: not the hi-fi, the machine it all got ripped onto.
     */
    WORKSHOP(
        displayName = "Workshop",
        key = floatArrayOf(0.949f, 0.949f, 0.941f),
        rim = floatArrayOf(0.400f, 0.420f, 0.443f),
        ambient = floatArrayOf(0.196f, 0.196f, 0.208f),
        void = floatArrayOf(0.047f, 0.047f, 0.055f),
        wall = Color(0xFF26262A),
        board = Color(0xFF3A3A3E),
        accent = Color(0xFF25F06A),
        plate = DeckPlate.CHROME,
        glass = DeckWindowGlass.PIXEL,
        palette = WorkshopPalette
    );

    companion object {
        /** Tolerant of a stored name that no longer exists, e.g. after a scheme is renamed. */
        fun fromName(name: String?): RoomScheme =
            entries.firstOrNull { it.name == name } ?: MIDNIGHT_SHOP
    }
}
