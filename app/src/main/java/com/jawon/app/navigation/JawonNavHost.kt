package com.jawon.app.navigation

import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.showroom.ShowroomViewModel
import com.jawon.app.ui.common.ShakeToSwitch
import com.jawon.app.BuildConfig
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.jawon.app.ui.wallscene.QualityTier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jawon.app.Format
import com.jawon.app.ui.addalbum.AddAlbumScreen
import com.jawon.app.ui.archive.RecordShopScreen
import com.jawon.app.ui.cd.CdImportScreen
import com.jawon.app.ui.editalbum.EditAlbumScreen
import com.jawon.app.ui.listening.ListeningScreen
import com.jawon.app.ui.pocketscene.PocketRoomScreen
import com.jawon.app.ui.settings.GUIDE_CD_PAGE
import com.jawon.app.ui.settings.GUIDE_POCKET_PAGE
import com.jawon.app.ui.settings.GUIDE_ROOMS_PAGE
import com.jawon.app.ui.settings.GUIDE_VINYL_PAGE
import com.jawon.app.ui.settings.SettingsScreen
import com.jawon.app.ui.shelfmanager.ShelfManagerScreen
import com.jawon.app.ui.showroom.ShowroomScreen
import com.jawon.app.ui.vinylscene.VinylRoomScreen
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
 */
private const val PANEL_IN_MS = 260
private const val PANEL_OUT_MS = 200

/**
 * How far a panel travels, as a fraction of the screen.
 *
 * A tenth. Far enough that the eye reads a direction — this came *up* over the room, and goes
 * back *down* off it — and short enough that it is not a drawer being hauled about.
 */
private const val PANEL_TRAVEL = 10

/**
 * The curtain between the room and the showroom: down in this long, up in that.
 *
 * Only the hand-over is covered — the showroom's own camera does the arriving (it opens black and
 * eases back from the room's object) and its push into a room ends in black — so the curtain is
 * brief, and quicker down than up for the same reason a panel is.
 */
private const val CURTAIN_DOWN_MS = 120
private const val CURTAIN_UP_MS = 260

/**
 * Jawon's whole surface: **the stage**, where the rooms and the showroom live, and over it the 2D
 * panels — Settings, Add Album, the shelf list and the rest.
 *
 * **The rooms are not destinations any more (0.7.2).** Until then the room was the NavHost's start
 * destination, and Navigation disposes a destination that is covered: every trip to Settings or
 * the shelf list destroyed the SceneView, every Filament resource in it and every baked cover, and
 * coming back rebuilt them — measured from a 30fps capture as ~230ms of black, then ~200ms of blank
 * grey plates, artwork at ~630ms, all hidden behind a fade that the code itself called "a mask, not
 * a fix". Now the stage sits *under* the NavHost and is never disposed by it: a panel covers the
 * room, the covered room stops rendering ([RoomStage]'s `covered`, which parks SceneView's loop),
 * and taking the panel away uncovers a room that never went anywhere.
 *
 * The NavHost's start destination draws nothing, so the stage shows through it; every other
 * destination is a [Panel], which is opaque and keeps touches from reaching the room under it.
 */
@Composable
fun JawonNavHost(
    /**
     * Which room the stage holds.
     *
     * **The seam for every format, and deliberately the only one.** A new format is a sibling
     * room in [RoomStage], not a branch inside `WallSceneScreen` (`ENGINEERING.md`, "Building a new
     * format room").
     */
    format: Format = Format.CD,
    /**
     * What Jawon was opened to do, when it was not simply opened — a shortcut's "Add music", a
     * file handed over by another app. Taken once: [onRequestTaken] clears it.
     */
    request: com.jawon.app.LaunchRequest? = null,
    onRequestTaken: () -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    androidx.compose.runtime.LaunchedEffect(request) {
        when (request) {
            null -> return@LaunchedEffect
            // Both end at the same screen: Add music either asks where from, or finds what was
            // handed over already waiting for it (`OpenedFiles`).
            com.jawon.app.LaunchRequest.AddMusic, is com.jawon.app.LaunchRequest.Open ->
                navController.navigate(JawonDestination.AddAlbum.route) { launchSingleTop = true }
        }
        onRequestTaken()
    }
    val entry by navController.currentBackStackEntryAsState()
    // A panel is up whenever the NavHost is anywhere but its empty start.
    val route = entry?.destination?.route
    val covered = route != null && route != JawonDestination.Wall.route
    val activity = LocalContext.current.findActivity()

    // Room or showroom. Saved, so a rotation or a trip through the background returns to the same
    // one; never a Navigation route, so its Back is the stage's to decide and nothing can stack two.
    var showroomWanted by rememberSaveable { mutableStateOf(false) }

    // **The rooms, a tap or a shake away** (0.7.2). The switcher stays open after a pick, so rooms
    // can be flicked through behind it; a shake flips to the next room wherever you are in one.
    val rooms: ShowroomViewModel = hiltViewModel()
    val shakeOn by rooms.shakeToSwitch.collectAsStateWithLifecycle()
    var roomsOpen by rememberSaveable { mutableStateOf(false) }
    val showUnbuilt = BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS
    val offered = Format.entries.filter { it.built || showUnbuilt }
    val switchTo: (Format) -> Unit = { if (it != format) rooms.choose(it) }
    ShakeToSwitch(
        enabled = shakeOn && offered.size > 1 && !covered && !roomsOpen && !showroomWanted
    ) {
        val here = offered.indexOf(format)
        switchTo(offered[(here + 1) % offered.size])
    }

    Box(Modifier.fillMaxSize()) {
        RoomStage(
            format = format,
            showroomWanted = showroomWanted,
            covered = covered,
            onOpenShowroom = { showroomWanted = true },
            onOpenRooms = { roomsOpen = true },
            onLeaveShowroom = { showroomWanted = false },
            // The showroom is the app's top (a room's Back comes here), so its Back leaves: to the
            // background, as Back from a launcher activity's root does since Android 12, with the
            // room still under it for when the app is opened again.
            onExit = { activity?.moveTaskToBack(true) },
            onAddAlbum = { navController.navigate(JawonDestination.AddAlbum.route) },
            onOpenShelfManager = { navController.navigate(JawonDestination.ShelfManager.route) },
            onOpenSettings = { navController.navigate(JawonDestination.Settings.routeFor()) },
            onOpenGuide = { page -> navController.navigate(JawonDestination.Settings.routeFor(page)) },
            onEditAlbum = { albumId ->
                navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
            }
        )

        NavHost(
            navController = navController,
            startDestination = JawonDestination.Wall.route,
            // **A panel comes up over the room and goes back down off it.** The room under it is
            // never animated at all — it is still there, so there is nothing to hide any more:
            // until 0.7.2 the way back was a fade *up* of the room over the panel, because the room
            // was being rebuilt from nothing behind it.
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it / PANEL_TRAVEL },
                    animationSpec = tween(PANEL_IN_MS, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(PANEL_IN_MS, easing = FastOutSlowInEasing))
            },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it / PANEL_TRAVEL },
                    animationSpec = tween(PANEL_OUT_MS, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(PANEL_OUT_MS, easing = FastOutSlowInEasing))
            }
        ) {
            // The stage shows through: nothing here, and nothing to catch a touch.
            composable(JawonDestination.Wall.route) { }

            composable(JawonDestination.AddAlbum.route) { entry ->
                // The CD screen hands its finished folder back through this entry's saved state,
                // which is Navigation's own way of returning a result — the alternative is a shared
                // ViewModel scoped above both screens, which is a lot of machinery for one Uri.
                val ripped by entry.savedStateHandle
                    .getStateFlow<String?>(RIPPED_FOLDER, null)
                    .collectAsStateWithLifecycle()

                Panel {
                    AddAlbumScreen(
                        onDone = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                        onBrowseArchive = { navController.navigate(JawonDestination.RecordShop.route) },
                        onImportCd = { navController.navigate(JawonDestination.ImportCd.route) },
                        rippedFolder = ripped?.toUri(),
                        onRippedFolderConsumed = { entry.savedStateHandle[RIPPED_FOLDER] = null }
                    )
                }
            }
            composable(JawonDestination.ImportCd.route) {
                Panel {
                    CdImportScreen(
                        onBack = { navController.popBackStack() },
                        onImportFolder = { uri ->
                            navController.previousBackStackEntry
                                ?.savedStateHandle?.set(RIPPED_FOLDER, uri.toString())
                            navController.popBackStack()
                        }
                    )
                }
            }
            composable(JawonDestination.RecordShop.route) {
                Panel { RecordShopScreen(onBack = { navController.popBackStack() }) }
            }
            composable(JawonDestination.ShelfManager.route) {
                Panel {
                    ShelfManagerScreen(
                        onBack = { navController.popBackStack() },
                        onEditAlbum = { albumId ->
                            navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
                        },
                        onOpenListening = { navController.navigate(JawonDestination.Listening.route) }
                    )
                }
            }
            composable(JawonDestination.Listening.route) {
                Panel {
                    ListeningScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId ->
                            navController.navigate(JawonDestination.EditAlbum.routeFor(albumId))
                        }
                    )
                }
            }
            composable(
                route = JawonDestination.EditAlbum.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
            ) {
                Panel {
                    EditAlbumScreen(
                        onDone = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() }
                    )
                }
            }
            composable(
                JawonDestination.Settings.route,
                arguments = listOf(navArgument("page") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                Panel {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        startPage = entry.arguments?.getString("page")
                    )
                }
            }
        }

        if (roomsOpen) {
            RoomSwitcher(
                current = format,
                offered = offered,
                shakeOn = shakeOn,
                onPick = switchTo,
                onDismiss = { roomsOpen = false }
            )
        }
    }
}

/**
 * The room, or the showroom — whichever is wanted — under a curtain for the moment between them.
 *
 * **Spam-proof by construction.** A change of [showroomWanted] restarts one effect, which carries
 * the curtain from wherever it is: down, swap which place is active, up. Changing your mind halfway
 * is the same curtain turning round, never a second transition queued behind the first, and
 * flicking back and forth as fast as a thumb can only ever leave the stage on the last thing asked
 * for.
 *
 * **And a place, once built, is kept (0.7.2).** Building a room is the expensive part of arriving —
 * a Filament engine, its models, its light, every cover — and, measured on the owner's phone, each
 * build also left ~35MB of GPU memory behind that nothing was ever going to return: twelve switches
 * between the CD room and the pocket player took GL memory from 153 to 393MB. So the CD room, the
 * pocket player and the showroom are each built the first time they are wanted and then *parked*
 * when another is on screen: still composed, moved off-screen, not rendering, deaf to touches and to
 * Back. Switching back is a cut, not a construction, and switching a hundred times builds nothing
 * after the first visit to each.
 *
 * Phones on the lowest quality tier keep only the place on screen: there the memory is worth more
 * than the speed.
 */
@Composable
private fun RoomStage(
    format: Format,
    showroomWanted: Boolean,
    covered: Boolean,
    onOpenShowroom: () -> Unit,
    onOpenRooms: () -> Unit,
    onLeaveShowroom: () -> Unit,
    onExit: () -> Unit,
    onAddAlbum: () -> Unit,
    onOpenShelfManager: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Settings opened on a page of its Guide — see `SettingsScreen`'s `startPage`. */
    onOpenGuide: (String) -> Unit,
    onEditAlbum: (String) -> Unit
) {
    val context = LocalContext.current
    val keepPlaces = remember(context) { QualityTier.detect(context) != QualityTier.LOW }

    // The place on stage, which follows the place wanted once the curtain is down.
    val wanted = if (showroomWanted) Place.SHOWROOM else Place.of(format)
    var shown by rememberSaveable { mutableStateOf(wanted) }
    val haptics = remember(context) { RoomHaptics(context) }
    // Each visit to the showroom is a new arrival (ShowroomScreen's `visit`).
    var showroomVisit by rememberSaveable { mutableIntStateOf(0) }
    val curtain = remember { Animatable(0f) }
    // Any change of place — into the showroom, out of it, or from one room to another by the
    // switcher or a shake — is the same short dip, and lands with a click you can feel.
    LaunchedEffect(wanted) {
        if (wanted != shown) {
            curtain.animateTo(1f, tween(CURTAIN_DOWN_MS, easing = LinearOutSlowInEasing))
            if (wanted == Place.SHOWROOM) showroomVisit++
            shown = wanted
            haptics.detent()
            // One frame for the place to take the stage under the curtain.
            withFrameNanos { }
        }
        curtain.animateTo(0f, tween(CURTAIN_UP_MS, easing = FastOutSlowInEasing))
    }

    val active = shown
    // Every place built so far, in the order they were first wanted.
    var built by remember { mutableStateOf(listOf(active)) }
    val places = if (!keepPlaces) listOf(active) else if (active in built) built else built + active
    SideEffect { if (places != built) built = places }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Under a panel the room is out of sight, and out of TalkBack's reach with it.
            .then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)
    ) {
        // A parked place stands a screen and a half to the right: laid out, its surface alive and
        // its last frame kept, but nowhere the eye or a finger can reach.
        val parkX = (constraints.maxWidth * 3) / 2
        places.forEach { place ->
            key(place) {
                val isActive = place == active
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(if (isActive) 0 else parkX, 0) }
                        .then(if (isActive) Modifier else Modifier.clearAndSetSemantics { })
                ) {
                    when (place) {
                        Place.SHOWROOM -> ShowroomScreen(
                            onLeave = onLeaveShowroom,
                            onExit = onExit,
                            onOpenGuide = { onOpenGuide(GUIDE_ROOMS_PAGE) },
                            active = isActive,
                            visit = showroomVisit
                        )
                        Place.CD -> WallSceneScreen(
                            onAddAlbum = onAddAlbum,
                            onOpenShelfManager = onOpenShelfManager,
                            onOpenSettings = onOpenSettings,
                            onOpenGuide = { onOpenGuide(GUIDE_CD_PAGE) },
                            onEditAlbum = onEditAlbum,
                            onOpenShowroom = onOpenShowroom,
                            onOpenRooms = onOpenRooms,
                            covered = covered || !isActive
                        )
                        Place.POCKET -> PocketRoomScreen(
                            onOpenSettings = onOpenSettings,
                            onOpenGuide = { onOpenGuide(GUIDE_POCKET_PAGE) },
                            onAddAlbum = onAddAlbum,
                            onOpenShowroom = onOpenShowroom,
                            onOpenRooms = onOpenRooms,
                            covered = covered || !isActive
                        )
                        Place.VINYL -> VinylRoomScreen(
                            onOpenSettings = onOpenSettings,
                            onOpenGuide = { onOpenGuide(GUIDE_VINYL_PAGE) },
                            onAddAlbum = onAddAlbum,
                            onOpenShowroom = onOpenShowroom,
                            onOpenRooms = onOpenRooms,
                            covered = covered || !isActive
                        )
                    }
                }
            }
        }

        // Composed only while it is showing anything (trap 14: a full-screen translucent layer
        // still costs the compositor every frame its alpha changes).
        if (curtain.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = curtain.value }
                    .background(Color.Black)
            )
        }
    }
}

/** The places the stage can hold: a room per format, and the showroom between them. */
private enum class Place {
    CD, POCKET, VINYL, SHOWROOM;

    companion object {
        fun of(format: Format): Place = when (format) {
            Format.CD -> CD
            Format.POCKET -> POCKET
            Format.VINYL -> VINYL
        }
    }
}

/**
 * A 2D screen over the stage: opaque (every screen paints its own ground), and **a wall for
 * touches**. A background alone catches nothing in Compose, so a tap on an empty stretch of a panel
 * would otherwise fall through to the room under it — and the room opens a case on a tap.
 * Registering a pointer handler here is what makes the panel the thing that was hit; the screen's
 * own buttons and lists sit inside it and are hit first, as before.
 */
@Composable
private fun Panel(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        content = content
    )
}

/** The activity behind a composition's context, through any wrappers a theme put round it. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
