package com.jawon.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jawon.app.ui.addalbum.AddAlbumScreen
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
                onOpenSettings = { navController.navigate(JawonDestination.Settings.route) }
            )
        }
        composable(JawonDestination.AddAlbum.route) {
            AddAlbumScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(JawonDestination.ShelfManager.route) {
            ShelfManagerScreen(onBack = { navController.popBackStack() })
        }
        composable(JawonDestination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
