package com.jawon.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jawon.app.ui.addalbum.AddAlbumScreen
import com.jawon.app.ui.deck.DeckScreen
import com.jawon.app.ui.settings.SettingsScreen
import com.jawon.app.ui.shelfmanager.ShelfManagerScreen
import com.jawon.app.ui.wall.WallScreen

@Composable
fun JavonNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = JavonDestination.Wall.route) {
        composable(JavonDestination.Wall.route) {
            WallScreen(
                onAddAlbum = { navController.navigate(JavonDestination.AddAlbum.route) },
                onOpenDeck = { navController.navigate(JavonDestination.Deck.route) },
                onOpenShelfManager = { navController.navigate(JavonDestination.ShelfManager.route) },
                onOpenSettings = { navController.navigate(JavonDestination.Settings.route) }
            )
        }
        composable(JavonDestination.AddAlbum.route) {
            AddAlbumScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(JavonDestination.Deck.route) {
            DeckScreen(onBack = { navController.popBackStack() })
        }
        composable(JavonDestination.ShelfManager.route) {
            ShelfManagerScreen(onBack = { navController.popBackStack() })
        }
        composable(JavonDestination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
