package com.jawon.app

/**
 * The medium the library is handled as — and therefore which room the app opens in.
 *
 * **A format is not a mode.** A *mode* is the CD room's light (`RoomScheme`: Vesper, Glacies,
 * Vigilia, Officina). A *format* is the whole room: its object, its sequence, its play surface
 * and its own lighting, per `docs/formats.md`. Putting both under one word is how a bug report
 * saying "I changed the mode" stops meaning anything.
 *
 * One switch for the whole library (the owner, 2026-09-21): choose vinyl and every album is an
 * LP, choose CD and they are all jewel cases again. Nothing about an album is stored per
 * format, so switching back and forth costs nothing and loses nothing.
 *
 * [built] is what the picker offers. An unbuilt room is reachable only from a debug build, so
 * a half-finished room can be worked on for weeks without ever being one tap away for someone
 * with a release build.
 */
enum class Format(val displayName: String, val built: Boolean) {
    CD("CD", built = true),
    // Built since 0.7.1 (2026-10-09): the face, the wheel, its menus and Settings, on the owner's
    // phone and shipped to testers on his call.
    POCKET("Pocket player", built = true),
    // Under way since 2026-10-11 (`docs/formats.md` §5): the deck plays; the crate is next.
    VINYL("Vinyl", built = false);

    companion object {
        /**
         * What was stored, or the CD room.
         *
         * Deliberately does **not** filter on [built]: a debug build can select an unbuilt
         * room and must still be in it after a relaunch, which is most of what working on one
         * looks like. Release builds never offer one, so nothing can store what they cannot
         * reach.
         */
        fun fromName(name: String?): Format = entries.firstOrNull { it.name == name } ?: CD

        /**
         * The room to open for what was [stored]: itself, unless it is unbuilt and this build
         * does not offer unbuilt rooms, in which case the CD room.
         *
         * **"Nothing can store what it cannot reach" stopped being true** once release builds
         * could be cut with `-Pjawon.showFormats=true` for the owner's phone: those stored
         * POCKET, and a published build reading it would open a room it offers no way out of —
         * no showroom, no picker. The stored value is left alone, so a later test build still
         * returns to the room it was on.
         */
        fun openable(stored: Format, showUnbuilt: Boolean): Format =
            if (stored.built || showUnbuilt) stored else CD
    }
}
