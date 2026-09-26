package com.jawon.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import com.jawon.app.Format
import com.jawon.app.ui.pocketscene.PocketRoomScreen
import com.jawon.app.ui.wallscene.WallSceneScreen

/**
 * Where the CD screen leaves the folder it ripped, for Add Album to pick up.
 *
 * A string rather than a `Uri`: saved state has to survive process death, and only the primitive
 * types are guaranteed to.
 */
private const val RIPPED_FOLDER = "ripped_folder"

/**
 * How long a panel takes to come up over the room, and to drop back off it.
 *
 * **Both were 700ms and neither was chosen.** `NavHost` defaults to a 700ms cross-fade when no
 * transition is given, and none was given anywhere in this file — so every trip out of the room
 * and back was that, which is slow enough to feel like the app thinking rather than the app
 * moving. Out is quicker than in, which is the usual asymmetry: arriving wants to be seen,
 * leaving wants to be over.
 *
 * There is a second reason to be brief here, and it is the one from trap 28. Both destinations
 * are composed for the whole transition, so a cross-fade keeps the Filament room rendering
 * underneath a 2D screen for the entire duration — and the room is the most expensive thing the
 * phone draws. 700ms of that on every navigation is heat for nothing.
 */
private const val PANEL_IN_MS = 260
private const val PANEL_OUT_MS = 200

/**
 * How far a panel travels, as a fraction of the screen.
 *
 * A tenth. Far enough that the eye reads a direction — this came *up* over the room, and goes
 * back *down* off it — and short enough that it is not a drawer being hauled about. The motion
 * is a hint of where the thing went, not a journey.
 */
private const val PANEL_TRAVEL = 10

/**
 * How long the room takes to fade back up, and how long the panel covers for first.
 *
 * Measured from a 30fps capture of a real back press: black until ~230ms, blank covers until
 * ~430ms, artwork at ~630ms. These are set to cover that — hold the panel briefly, then bring
 * the room up across the window where it is still assembling itself.
 */
private const val ROOM_RETURN_MS = 420
private const val ROOM_RETURN_DELAY_MS = 140

@Composable
fun JawonNavHost(
    /**
     * Which room the start destination is.
     *
     * **The seam for every format, and deliberately the only one.** A new format is a sibling
     * room here, not a branch inside `WallSceneScreen` — that file is 3,000 lines of finished
     * CD room and does not grow a `when` at every one of them (`ENGINEERING.md`, "Building a new
     * format room"). Switching costs nothing extra to apply either: leaving a room already
     * destroys its SceneView and coming back rebuilds it, so a format chosen in Settings is
     * simply what gets rebuilt.
     */
    format: Format = Format.CD,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = JawonDestination.Wall.route,
        // **The room holds still and the panel moves over it.**
        //
        // A cross-fade is the wrong gesture for this app: it dissolves one place into another,
        // and at the halfway point both are half-transparent over black, which is the mush the
        // default produced. The room is not a *screen* the app is showing, it is where the app
        // lives — so it stays exactly where it is, at full opacity, and Add Album or Settings
        // is drawn up in front of it like a panel being lifted into view.
        //
        // Hence `ExitTransition.None` going out and `EnterTransition.None` coming back: the
        // room is never animated at all, only covered and uncovered. That is also the cheapest
        // thing it can do, which matters for a surface this expensive.
        enterTransition = {
            slideInVertically(
                initialOffsetY = { it / PANEL_TRAVEL },
                animationSpec = tween(PANEL_IN_MS, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(PANEL_IN_MS, easing = FastOutSlowInEasing))
        },
        exitTransition = { ExitTransition.None },
        // **Coming back is a cross-fade, and that is deliberate — it is hiding a rebuild.**
        //
        // The first version slid the panel back down over a room that held still, which is the
        // symmetric gesture and was wrong for a reason only a device could show. Recorded at
        // 30fps and stepped through: the panel vanished in a single frame, then the room area
        // was **pure black for ~230ms** with only its 2D chrome on top, then the shelf appeared
        // with every cover a blank grey plate for ~200ms more, and the artwork did not land
        // until ~630ms after the back press.
        //
        // Navigating away disposes this destination, which destroys the SceneView and every
        // Filament resource in it, and `CaseArtCache` is a `remember` inside the composable so
        // the covers are re-decoded and re-baked too. Returning is not a transition, it is a
        // reconstruction. And the room is drawn *above* the outgoing panel and paints itself
        // opaque black before Filament has anything to show — which is why sliding the panel
        // out underneath it was invisible, and why fading the panel out cannot help either.
        //
        // So the room fades up *over* the panel instead. Settings stays put and fully opaque
        // underneath, and by the time the room is opaque it has geometry and artwork in it.
        // The black flash and the blank plates both happen behind a screen that is still there.
        //
        // This is a mask, not a fix. The fix is for the room not to be destroyed at all.
        popEnterTransition = {
            fadeIn(tween(ROOM_RETURN_MS, delayMillis = ROOM_RETURN_DELAY_MS, easing = FastOutSlowInEasing))
        },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(JawonDestination.Wall.route) {
            when (format) {
                Format.CD -> WallSceneScreen(
                    onAddAlbum = { navController.navigate(JawonDestination.AddAlbum.route) },
                    onOpenShelfManager = {
                        navController.navigate(JawonDestination.ShelfManager.route)
                    },
                    onOpenSettings = { navController.navigate(JawonDestination.Settings.route) },
                    onEditAlbum = { albumId ->
                        navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
                    }
                )

                Format.POCKET -> PocketRoomScreen(
                    onOpenSettings = { navController.navigate(JawonDestination.Settings.route) }
                )
            }
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
