package com.jawon.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jawon.app.ui.addalbum.AddAlbumScreen
import com.jawon.app.ui.archive.RecordShopScreen
import com.jawon.app.ui.editalbum.EditAlbumScreen
import com.jawon.app.ui.listening.ListeningScreen
import com.jawon.app.ui.settings.SettingsScreen
import com.jawon.app.ui.shelfmanager.ShelfManagerScreen
import com.jawon.app.ui.wallscene.WallSceneScreen

@Composable
fun JawonNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = JawonDestination.Wall.route) {
        composable(JawonDestination.Wall.route) {
            WallSceneScreen(
                onAddAlbum = { navController.navigate(JawonDestination.AddAlbum.route) },
                onOpenShelfManager = { navController.navigate(JawonDestination.ShelfManager.route) },
                onOpenSettings = { navController.navigate(JawonDestination.Settings.route) },
                onEditAlbum = { albumId ->
                    navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
                }
            )
        }
        composable(JawonDestination.AddAlbum.route) {
            AddAlbumScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                onBrowseArchive = { navController.navigate(JawonDestination.RecordShop.route) }
            )
        }
        composable(JawonDestination.RecordShop.route) {
            RecordShopScreen(onBack = { navController.popBackStack() })
        }
        composable(JawonDestination.ShelfManager.route) {
            ShelfManagerScreen(
                onBack = { navController.popBackStack() },
                onEditAlbum = { albumId ->
                    navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
                },
                onOpenListening = { navController.navigate(JawonDestination.Listening.route) }
            )
        }
        composable(JawonDestination.Listening.route) {
            ListeningScreen(
                onBack = { navController.popBackStack() },
                onOpenAlbum = { albumId ->
                    navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
                }
            )
        }
        composable(
            route = JawonDestination.EditAlbum.route,
            arguments = listOf(navArgument("albumId") { type = NavType.StringType })
        ) {
            EditAlbumScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(JawonDestination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
