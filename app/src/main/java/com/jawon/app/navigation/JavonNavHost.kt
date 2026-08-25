package com.jawon.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jawon.app.ui.addalbum.AddAlbumScreen
import com.jawon.app.ui.deck.DeckScreen
import com.jawon.app.ui.settings.SettingsScreen
import com.jawon.app.ui.shelfmanager.ShelfManagerScreen
import com.jawon.app.ui.wall.WallScreen
import com.jawon.app.ui.wallscene.WallSceneScreen

@Composable
fun JavonNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = JavonDestination.Wall.route) {
        composable(JavonDestination.Wall.route) {
            WallScreen(
                onAddAlbum = { navController.navigate(JavonDestination.AddAlbum.route) },
                onOpenShelfManager = { navController.navigate(JavonDestination.ShelfManager.route) },
                onOpenSettings = { navController.navigate(JavonDestination.Settings.route) },
                onOpenAlbum = { albumId -> navController.navigate(JavonDestination.Deck.route(albumId)) },
                onOpenRoom = { navController.navigate(JavonDestination.WallScene.route) }
            )
        }
        composable(JavonDestination.AddAlbum.route) {
            AddAlbumScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            JavonDestination.Deck.route,
            arguments = listOf<NamedNavArgument>(navArgument("albumId") { type = NavType.StringType })
        ) {
            DeckScreen(onBack = { navController.popBackStack() })
        }
        composable(JavonDestination.ShelfManager.route) {
            ShelfManagerScreen(
                onBack = { navController.popBackStack() },
                onPlayAlbum = { album -> navController.navigate(JavonDestination.Deck.route(album.id)) }
            )
        }
        composable(JavonDestination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(JavonDestination.WallScene.route) {
            WallSceneScreen(
                onBack = { navController.popBackStack() },
                onOpenAlbum = { albumId -> navController.navigate(JavonDestination.Deck.route(albumId)) }
            )
        }
    }
}
