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
 * Three, because the tester who asked for this said three is enough, and because each one
 * has to be tuned against real artwork on a real screen — a dozen half-considered palettes
 * would be worth less than three that were looked at.
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
    val accent: Color
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
        accent = LampAmber
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
        accent = Color(0xFF7FB2E5)
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
        accent = Color(0xFFE84D7A)
    );

    companion object {
        /** Tolerant of a stored name that no longer exists, e.g. after a scheme is renamed. */
        fun fromName(name: String?): RoomScheme =
            entries.firstOrNull { it.name == name } ?: MIDNIGHT_SHOP
    }
}
