package com.jawon.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jawon.app.ui.addalbum.AddAlbumScreen
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.ui.cd.CdImportScreen
import com.jawon.app.ui.archive.RecordShopScreen
import com.jawon.app.ui.editalbum.EditAlbumScreen
import com.jawon.app.ui.listening.ListeningScreen
import com.jawon.app.ui.settings.SettingsScreen
import com.jawon.app.ui.shelfmanager.ShelfManagerScreen
import com.jawon.app.ui.wallscene.WallSceneScreen

/**
 * Where the CD screen leaves the folder it ripped, for Add Album to pick up.
 *
 * A string rather than a `Uri`: saved state has to survive process death, and only the primitive
 * types are guaranteed to.
 */
private const val RIPPED_FOLDER = "ripped_folder"

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
        composable(JawonDestination.AddAlbum.route) { entry ->
            // The CD screen hands its finished folder back through this entry's saved state,
            // which is Navigation's own way of returning a result — the alternative is a shared
            // ViewModel scoped above both screens, which is a lot of machinery for one Uri.
            val ripped by entry.savedStateHandle
                .getStateFlow<String?>(RIPPED_FOLDER, null)
                .collectAsStateWithLifecycle()

            AddAlbumScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                onBrowseArchive = { navController.navigate(JawonDestination.RecordShop.route) },
                onImportCd = { navController.navigate(JawonDestination.ImportCd.route) },
                rippedFolder = ripped?.toUri(),
                onRippedFolderConsumed = { entry.savedStateHandle[RIPPED_FOLDER] = null }
            )
        }
        composable(JawonDestination.ImportCd.route) {
            CdImportScreen(
                onBack = { navController.popBackStack() },
                onImportFolder = { uri ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(RIPPED_FOLDER, uri.toString())
                    navController.popBackStack()
                }
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
