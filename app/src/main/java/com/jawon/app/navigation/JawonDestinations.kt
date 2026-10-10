package com.jawon.app.navigation

/**
 * The room is the app: it is both the wall you browse and the deck you play from, so there
 * is no separate Deck destination. The rest are 2D utility screens reached from its chrome.
 */
sealed class JawonDestination(val route: String) {
    /** The empty start: the stage under the NavHost shows through it (`JawonNavHost`). */
    data object Wall : JawonDestination("wall")
    data object AddAlbum : JawonDestination("add_album")
    data object ShelfManager : JawonDestination("shelf_manager")
    /**
     * Settings, optionally opened on one of its pages — the pocket player's Guide row opens it on
     * the Guide's pocket-player page (0.7.4). Navigate with [routeFor], never [route], which holds
     * the placeholder.
     */
    data object Settings : JawonDestination("settings?page={page}") {
        fun routeFor(page: String? = null) = if (page == null) "settings" else "settings?page=$page"
    }

    /**
     * What the collection has actually been played. Reached from the shelf list rather than
     * from the room's chrome: that is three even thirds by an explicit decision, and this
     * belongs with the collection anyway.
     */
    data object Listening : JawonDestination("listening")

    /**
     * Browsing the Internet Archive for records to take home.
     *
     * Reached from Add Album rather than from the room's chrome: whatever else it is, it is a
     * way of getting a record onto a shelf, and that screen is where the other two live. The
     * chrome's three even thirds stay three.
     */
    data object RecordShop : JawonDestination("record_shop")

    /**
     * Ripping a CD off a USB drive.
     *
     * Reached from Add Album for the same reason the record shop is: whatever else it is, it is
     * a way of getting a record onto a shelf. The room's chrome stays three even thirds.
     */
    data object ImportCd : JawonDestination("import_cd")

    /** Editing one album. Reached from the room's long-press and from the shelf list. */
    data object EditAlbum : JawonDestination("edit_album/{albumId}") {
        fun routeFor(albumId: String) = "edit_album/$albumId"
    }
}
