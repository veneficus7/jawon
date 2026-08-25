package com.jawon.app.navigation

sealed class JavonDestination(val route: String) {
    data object Wall : JavonDestination("wall")
    data object Deck : JavonDestination("deck")
    data object ShelfManager : JavonDestination("shelf_manager")
    data object Settings : JavonDestination("settings")
}
