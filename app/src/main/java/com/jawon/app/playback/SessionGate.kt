package com.jawon.app.playback

/**
 * Who may connect to the player from outside the app.
 *
 * Until 0.7.6 nobody could: the playback service was not exported. Android Auto, the assistant and
 * the system's own media controls all reach a player by binding to its service, so it is exported
 * now — and an exported service is one **any** installed app can bind to, browse the library
 * through and drive. Media3 lets everyone in by default.
 *
 * So the door is narrower than the default. Jawon itself; a car; and whatever the phone already
 * trusts with media — the system, and apps the owner has given notification access (a watch's
 * companion, the lock screen), which could see what is playing and press pause regardless. An
 * ordinary app that merely knows the service's name is turned away, and with it the only way to
 * read the shelves' titles from outside.
 *
 * Pure, so the rule can be read and tested without a session.
 */
object SessionGate {
    fun admits(
        /** Jawon's own controller — the rooms, the widget, the tile. */
        ownApp: Boolean,
        /** Android Auto on the phone, or the car itself when the car runs Android. */
        car: Boolean,
        /** Holds the platform's media-control permission, or has notification access. */
        trusted: Boolean
    ): Boolean = ownApp || car || trusted
}
