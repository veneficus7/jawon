package com.jawon.app.ui.showroom

import com.jawon.app.Format

/**
 * What stands on the showroom's plinths, in order along the row (`docs/formats.md` §7, step 5b).
 *
 * **Every format the app is going to have, built or not.** An empty plinth under a dim light says
 * *coming* better than a list with gaps in it, and it is the row's length that makes the showroom
 * a place rather than a menu. [format] is null for those: there is no room behind them to open.
 * That is also why this is not [Format] itself — a `Format` is a room that exists in code, and
 * Settings' picker offers every one of them.
 */
enum class Exhibit(val format: Format?, val title: String, val line: String) {
    CD(Format.CD, "CD", "A wall of records"),
    POCKET(Format.POCKET, "Pocket player", "A night train"),
    VINYL(null, "Vinyl", "Coming"),
    CASSETTE(null, "Cassette", "Coming");

    companion object {
        /** The plinth a room's own object stands on — where you arrive, pinched out of it. */
        fun of(format: Format): Exhibit = entries.first { it.format == format }

        /**
         * Whether a tap on [exhibit] opens its room: one that exists, and is either finished or in
         * a build that offers unfinished ones — the rule Settings' picker has always followed.
         */
        fun opens(exhibit: Exhibit, showUnbuilt: Boolean): Boolean {
            val format = exhibit.format ?: return false
            return format.built || showUnbuilt
        }

        /**
         * Whether the showroom can be reached at all: only once there is somewhere else to go. A
         * release build with one finished room pinches out to exactly what it did before.
         */
        fun reachable(showUnbuilt: Boolean): Boolean =
            showUnbuilt || Format.entries.count { it.built } > 1
    }
}
