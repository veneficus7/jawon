package com.jawon.app.navigation

sealed class JavonDestination(val route: String) {
    data object Wall : JavonDestination("wall")
    data object AddAlbum : JavonDestination("add_album")
    data object Deck : JavonDestination("deck/{albumId}") {
        fun route(albumId: String) = "deck/$albumId"
    }
    data object ShelfManager : JavonDestination("shelf_manager")
    data object Settings : JavonDestination("settings")
}
