package com.jawon.app.ui.tapescene

/**
 * The cassette room, in metres, in the room's own axes: **x to the right, y up, z toward you**.
 * The origin is the middle of the deck's front edge, on the sideboard.
 *
 * Every number has a twin in `tools/models/build_models.py` (the `T_*` constants), which models
 * in Blender's axes — z up, y away — so a y there is a −z here. The app puts the cassette in the
 * door, the box on the deck, a tape in its rack's slot and a finger on every key by these, and a
 * mismatch does not crash: a key simply goes down a few millimetres from where it is drawn.
 * `docs/models.md` has the bounds the files were built to.
 */
object TapeDimens {

    // ---- The cassette -------------------------------------------------------------------------------

    const val CAS_W = 0.1004f
    const val CAS_H = 0.0638f

    /** Its thickness over the ridges round the head openings: what it takes up lying in a box. */
    const val CAS_T = 0.0120f

    /** The hubs: their centres above the cassette's middle, and how far apart. */
    const val HUB_Z = 0.0015f
    const val HUB_PITCH = Tape.HUB_PITCH

    /** The window both packs are seen through: how far across and up from the cassette's middle it reaches. */
    const val WINDOW_HALF_W = 0.034f
    const val WINDOW_HALF_H = 0.011f

    // ---- The box -----------------------------------------------------------------------------------

    const val BOX_L = 0.109f
    const val BOX_W = 0.070f
    const val BOX_T = 0.017f
    const val TRAY_FLOOR = 0.0012f

    /** The lid's hinge: up from the box's underside, and back from its middle. The lid model's origin. */
    const val HINGE_UP = 0.0015f
    const val HINGE_BACK = 0.0335f

    /** How far the lid opens, standing on the deck: past upright, so the J-card leans back a little. */
    const val LID_OPEN_DEG = -100f

    /** The J-card's inside, in a shut box: its height off the underside, and its size. */
    const val JCARD_Y = 0.01538f
    const val JCARD_L = 0.101f
    const val JCARD_W = 0.064f

    // ---- The deck ------------------------------------------------------------------------------------

    const val DECK_W = 0.220f
    const val DECK_TOP = 0.136f
    const val DECK_D = 0.260f
    const val FASCIA_T = 0.004f

    /** The door's hinge: along its foot, at its front face. It opens by tipping its top toward you. */
    const val DOOR_HINGE_Y = 0.031f
    const val DOOR_HINGE_Z = 0.005f
    const val DOOR_OPEN_DEG = 22f
    const val DOOR_W = 0.114f
    const val DOOR_H = 0.074f

    /** Where the cassette's middle is, in the shut door. */
    const val SEAT_X = 0f
    const val SEAT_Y = 0.0675f
    const val SEAT_Z = -0.012f

    /** The keys: EJECT · REW · PLAY · FF · STOP · PAUSE, left to right; their size, how far they go down. */
    const val KEY_PITCH = 0.022f
    const val KEY_W = 0.020f
    const val KEY_H = 0.011f
    const val KEY_Y = 0.019f
    const val KEY_TRAVEL = 0.0035f

    fun keyX(index: Int): Float = (index - 2.5f) * KEY_PITCH

    /** The small REV button beside the counter. */
    const val REV_X = 0.0215f
    const val REV_Y = 0.1205f
    const val REV_R = 0.0031f

    /** The meters: each one's middle, and its needle's pivot, hidden under the window. */
    const val METER_X = 0.046f
    const val METER_Y = 0.120f
    const val NEEDLE_PIVOT_Y = 0.104f

    /** The counter's drums turn about a line along x, here. */
    const val DRUM_Y = 0.120f
    const val DRUM_Z = -0.004f

    // ---- The box on the deck ---------------------------------------------------------------------------

    /** The box lies on the deck's top with its spine away from you: its middle's distance in from the front. */
    const val CASE_Z = -0.041f
    const val CASE_Y = DECK_TOP + 0.0005f

    /** The cassette lying face up in the box on the deck: its middle. */
    const val LYING_Y = CASE_Y + TRAY_FLOOR + CAS_T / 2

    // ---- The racks ----------------------------------------------------------------------------------

    const val RACK_SLOTS = 24
    const val RACK_PITCH = 0.019f
    const val RACK_W = 0.136f
    const val RACK_GAP = 0.030f
    const val RACK_DEPTH = 0.082f
    const val RACK_BOTTOM_BOARD = 0.012f
    const val RACK_TOP_BOARD = 0.026f
    const val RACK_H = RACK_BOTTOM_BOARD + RACK_SLOTS * RACK_PITCH + RACK_TOP_BOARD

    /** The wall the racks hang on, and the height of their feet. */
    const val WALL_Z = -0.320f
    const val RACKS_BOTTOM = 0.300f
    const val RACK_FRONT_Z = WALL_Z + RACK_DEPTH

    /** A box in a slot: how far its spine stands back from the rack's front, and so where its middle is. */
    const val SPINE_BACK = 0.004f
    const val SLOT_CASE_Z = RACK_FRONT_Z - SPINE_BACK - BOX_W / 2

    /** The card with the shelf's name, on the rack's top board. */
    const val RACK_LABEL_Y = RACK_BOTTOM_BOARD + RACK_SLOTS * RACK_PITCH + RACK_TOP_BOARD / 2

    fun rackX(rack: Int): Float = rack * (RACK_W + RACK_GAP)

    /** The underside of a box in [slot] (0 is the top) of a rack. */
    fun slotY(slot: Int): Float = RACKS_BOTTOM + RACK_BOTTOM_BOARD + (RACK_SLOTS - 1 - slot) * RACK_PITCH + 0.0014f
}
