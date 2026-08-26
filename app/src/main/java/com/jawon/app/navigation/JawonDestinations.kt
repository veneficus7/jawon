package com.jawon.app.navigation

/**
 * The room is the app: it is both the wall you browse and the deck you play from, so there
 * is no separate Deck destination. The rest are 2D utility screens reached from its chrome.
 */
sealed class JawonDestination(val route: String) {
    data object Wall : JawonDestination("wall")
    data object AddAlbum : JawonDestination("add_album")
    data object ShelfManager : JawonDestination("shelf_manager")
    data object Settings : JawonDestination("settings")
}
