package com.jawon.app.ui.vinylscene

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.jawon.app.ui.common.ChromeAction
import com.jawon.app.ui.common.JawonMark
import com.jawon.app.ui.theme.LabelCream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.jawon.app.playback.ScratchAudio
import com.jawon.app.ui.common.LocalRoomParked
import com.jawon.app.ui.common.RoomCalm
import com.jawon.app.ui.common.displayRefreshHz
import com.jawon.app.ui.common.rememberRoomEngine
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.DeckState
import com.jawon.app.ui.wallscene.QualityTier
import com.jawon.app.ui.wallscene.RoomCadence
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.Scratch
import com.jawon.app.ui.wallscene.WallSceneViewModel
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

private const val ROOM_ASSET = "models/vinyl_room.glb"
private const val DECK_ASSET = "models/vinyl_deck.glb"
private const val PLATTER_ASSET = "models/vinyl_platter.glb"
private const val ARM_ASSET = "models/vinyl_arm.glb"
private const val RECORD_ASSET = "models/vinyl_record.glb"
private const val SLEEVE_ASSET = "models/vinyl_sleeve.glb"
private const val VINYL_MATERIAL_ASSET = "materials/vinyl.filamat"

/** Carbon-filled PVC, linear: black, and not quite. */
private const val VINYL_BLACK = 0.004f

/** How much longer than wide the lamp's streak across the grooves is: nineteen to one. */
private const val VINYL_ANISOTROPY = 0.9f

/** 33⅓ turns a minute. Clockwise from above, which about +y is the negative way round. */
private const val PLATTER_DEG_PER_S = 200f

/** How stiffly the camera goes between the crate and the deck: a spring that arrives in about three quarters of a second and does not come back. */
private const val PLACE_STIFFNESS = 7.5f

/** A record that has been chosen is seen to leave the crate before the camera does. */
private const val TAKE_LEAD_MS = 200L

/** How much of the way the camera follows the record being read toward the back of a crate nearly gone through. */
private const val CRATE_FOLLOW = 0.8f

/** How quickly the arm and the zoom close on where they are going: the fraction left after a second is e^-this. */
private const val ARM_EASE = 9f

/** How closely the arm keeps to the groove being heard, with the needle in it. */
private const val ARM_FOLLOW = 22f

/** A tap while a record is being put on: the same sequence, this many times as fast. */
private const val HURRY = 7f

/** How long a record that has been taken is waited for before the room stops expecting it. */
private const val GIVE_UP_S = 5f

/** A needle moved between two tracks lands more softly than one put down on a record. */
private const val HOP_LEVEL = 0.55f

/** After a needle is put somewhere, how long the player is given to say the music is there too. */
private const val AGREE_WITHIN_S = 1.5f

/** The motor runs on through a silence this long — a needle in the air between two tracks — without the platter slowing. */
private const val MOTOR_HOLD_MS = 2_500L
private const val ZOOM_EASE = 11f

private const val NEAR_PLANE_M = 0.08
private const val FAR_PLANE_M = 12.0

/** How rarely the stopped player is walked along behind a held record — the CD room's figure. */
private const val WALK_EVERY_MS = 250L

private const val TAP_LONGEST_MS = 280L
private const val DOUBLE_TAP_WITHIN_MS = 300L

/** The start · stop button on the deck's top plate: its middle, and half its size, with a thumb's margin. */
private const val START_X = -VinylDimens.DECK_W / 2 + 0.046f
private const val START_Z = VinylDimens.DECK_D / 2 - 0.036f
private const val START_HALF_W = 0.034f
private const val START_HALF_D = 0.026f

/**
 * **The vinyl room** (`docs/formats.md` §5) — a sibling of `WallSceneScreen` and `PocketRoomScreen`,
 * sharing their view model and their player and nothing of their scenes.
 *
 * Two places, and the camera goes between them: **the crate** on the floor, where the library
 * stands to be gone through (`Crate.kt`, `CrateScene.kt`), and **the deck** from straight above,
 * the record that is on, its sleeve laid out in front with its sides printed on the back. A
 * record touched in the crate is lifted out and put on — its sleeve laid on the table, the
 * record drawn out of it and lowered onto the spindle, the needle put down, and the music from
 * the moment it lands (`PutOn`). At the end of a side the needle goes home and the record waits
 * to be turned over — touched, or a track on its other side touched — unless Settings says it
 * turns itself (`TurnOver`). Back from the deck is back to the crate, where the way to the
 * other rooms and to Settings is.
 *
 * - **The platter turns** at 33⅓ while the music plays, and goes on turning under a record that
 *   is held: that is what a slipmat is for, and why the beads on its rim keep moving past a
 *   finger that has stopped the music.
 * - **The record is the scratch**, at a record's own gearing and nothing else — one turn is 1.8
 *   seconds because that is what one turn *is* (`Platter`, `ScratchAudio`; the same engine the
 *   CD's disc was rebuilt on, built there first so that this would be the same object).
 * - **The arm is where the music is**: it rides in from the edge to the label across a side, and
 *   the place it is over is the place being heard (`VinylArm`, `Side`).
 * - **The back of the sleeve is the tracklist.** Touch a line and the record goes there.
 * - **Pinch**, or tap the table twice, between the whole deck and the record under the thumb.
 */
@Composable
fun VinylRoomScreen(
    onOpenSettings: () -> Unit,
    onAddAlbum: () -> Unit,
    onOpenRooms: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    /** Back from the deck: the showroom between formats. */
    onOpenShowroom: () -> Unit = {},
    /** A 2D panel is up over the room: it stays composed, stops rendering, and its Back stands aside. */
    covered: Boolean = false,
    viewModel: WallSceneViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val engine = rememberRoomEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val filamentView = rememberView(engine)
    val renderer = rememberRenderer(engine)
    val tier = remember(context) { QualityTier.detect(context) }

    val environment = remember(engine, environmentLoader) {
        VinylLighting.createEnvironment(engine) { cubemap -> environmentLoader.iblPrefilter.specularFilter(cubemap) }
    }
    val cameraNode = rememberCameraNode(engine) {
        focalLength = VinylCamera.FOCAL_MM.toDouble()
        near = NEAR_PLANE_M.toFloat()
        far = FAR_PLANE_M.toFloat()
    }
    val cadence = remember(context) {
        RoomCadence().apply {
            refreshHz = context.displayRefreshHz(RoomCadence.DEFAULT_REFRESH_HZ)
            maxFps = tier.maxFps
        }
    }
    val haptics = remember(context) { RoomHaptics(context) }

    val roomModel = rememberModelInstance(modelLoader, ROOM_ASSET)
    val deckModel = rememberModelInstance(modelLoader, DECK_ASSET)
    val platterModel = rememberModelInstance(modelLoader, PLATTER_ASSET)
    val armModel = rememberModelInstance(modelLoader, ARM_ASSET)
    val recordModel = rememberModelInstance(modelLoader, RECORD_ASSET)
    val sleeveBackModel = rememberModelInstance(modelLoader, SLEEVE_ASSET)
    val sleeveFrontModel = rememberModelInstance(modelLoader, SLEEVE_ASSET)
    // The crate: its wood, and the pools its sleeves and cards are drawn from — a fixed number of
    // each, made once and never again (`CrateLayout.POOL`, and `ENGINEERING.md`'s first trap).
    val crateModel = rememberModelInstance(modelLoader, CRATE_ASSET)
    val crateSleeves = remember(modelLoader) { modelLoader.createInstancedModel(SLEEVE_ASSET, CrateLayout.POOL) }
    val crateCards = remember(modelLoader) { modelLoader.createInstancedModel(DIVIDER_ASSET, CrateLayout.CARDS) }

    // ---- the record that is on ---------------------------------------------------------------
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val nowPlayingAlbumId by viewModel.nowPlayingAlbumId.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.adoptPlayingAlbum() }

    val album = remember(layout, nowPlayingAlbumId) { layout.cases.firstOrNull { it.albumId == nowPlayingAlbumId } }
    val sides = remember(deck.tracks) { Sides.cut(deck.tracks.map { it.durationMs }) }
    val sideIndex = if (sides.isEmpty()) 0 else Sides.of(sides, deck.currentTrackIndex.coerceAtLeast(0))
    // A long album is more than one record; the one on the platter is the one this side is on.
    val recordIndex = sideIndex / 2

    val scope = rememberCoroutineScope()
    val crateTextures = remember(engine) { CrateTextures(engine) }
    val crate = remember(crateTextures) { VinylCrate(context.applicationContext, scope, crateTextures, haptics) }
    // Declared before the scene, so it is disposed after it (`ENGINEERING.md`, trap 35).
    DisposableEffect(crateTextures) {
        onDispose {
            crate.release()
            crateTextures.destroy()
        }
    }
    // What a needle sounds like coming down: see NeedleSound.
    val needle = remember(context) { NeedlePlayer(context) }
    DisposableEffect(needle) { onDispose { needle.close() } }
    // With a record already on, the room opens at the deck; with none, at the crate.
    val stage = remember(crate) { VinylStage(viewModel.scratchAudio, haptics, crate, needle, startAtDeck = viewModel.deck.value.tracks.isNotEmpty()) }
    // Left with a record half way onto the deck — the home button, another room — it is on:
    // nobody takes a record out to not hear it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stage.unseen() }
    // The same when the room stops being drawn with the app still up — a panel over it, another
    // room in front: the sequence is frames, and without frames it would wait for ever. And a
    // record that nobody is looking at is not waited on to be turned over.
    val unseen = covered || LocalRoomParked.current
    LaunchedEffect(unseen) { if (unseen) stage.unseen() }
    val turnsItself by viewModel.vinylTurnsItself.collectAsStateWithLifecycle()
    val crateItems = remember(layout) { Crate.items(layout) }
    val currentNowPlaying = rememberUpdatedState(nowPlayingAlbumId)
    SideEffect {
        crate.setItems(crateItems, nowPlayingAlbumId)
        crate.onTake = { albumId ->
            if (stage.isOn(albumId)) {
                // The record that is already on: up to it, and going.
                viewModel.playAlbum(albumId)
                stage.toDeck()
            } else {
                // Put on, and silent until its needle is down: what was playing stops as it is taken off.
                viewModel.playAlbum(albumId, play = false)
                stage.putOn(albumId)
            }
        }
        stage.onNeedleDown = { viewModel.setPlaying(true) }
        stage.onNeedleUp = { viewModel.setPlaying(false) }
        stage.onSelect = { index -> viewModel.onSelectTrack(index) }
        stage.turnsItself = turnsItself
        crate.onAdd = onAddAlbum
        crate.onDeck = { stage.toDeck() }
    }
    // A record put on from somewhere else — the car, the widget — while the crate is out of
    // sight: it is turned to that record, so that going back to it is going back to where it was.
    LaunchedEffect(nowPlayingAlbumId, crateItems) {
        if (stage.atDeck) crate.turnTo(nowPlayingAlbumId)
    }
    stage.touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val currentDeck = rememberUpdatedState(deck)
    val currentSides = rememberUpdatedState(sides)
    val currentPosition = rememberUpdatedState(positionMs)
    val currentCovered = rememberUpdatedState(covered)
    val currentLayout = rememberUpdatedState(layout)

    // ---- what is printed -------------------------------------------------------------------------
    //
    // One texture of each kind for the life of the scene, written again for every record — the
    // rule the CD room paid for: a texture destroyed while a material still samples it takes the
    // process with it.
    val art = remember(engine) { VinylTextures(engine) }
    // A record's two faces, in the one material here that is hand-written — see `vinyl.mat`.
    val vinylMaterial = remember(materialLoader) { materialLoader.createMaterial(VINYL_MATERIAL_ASSET) }
    val vinylA = remember(vinylMaterial) { materialLoader.createInstance(vinylMaterial).apply(::dressVinyl) }
    val vinylB = remember(vinylMaterial) { materialLoader.createInstance(vinylMaterial).apply(::dressVinyl) }
    // Declared before the scene, so it is disposed after it (`ENGINEERING.md`, trap 35): the textures
    // go only once nothing can be drawn with them.
    DisposableEffect(art) { onDispose { art.destroy() } }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(album?.albumId, album?.coverPath, sides, recordIndex) {
        val playing = album
        val baked = withContext(Dispatchers.Default) {
            val loaded = playing?.coverPath?.let { path -> withContext(Dispatchers.IO) { CaseArt.loadCover(path, VinylArt.SLEEVE_PX) } }
            val title = playing?.title.orEmpty()
            val artist = playing?.artist.orEmpty()
            val up = sides.getOrNull(recordIndex * 2)
            val under = sides.getOrNull(recordIndex * 2 + 1)
            VinylBaked(
                cover = loaded,
                labelA = VinylArt.label(context, loaded, Sides.letter(recordIndex * 2), title, artist),
                labelB = VinylArt.label(context, loaded, Sides.letter(recordIndex * 2 + 1), title, artist),
                bandsA = VinylArt.bands(up),
                bandsB = VinylArt.bands(under),
                front = VinylArt.sleeveFront(context, loaded, title, artist)
            )
        }
        art.write(baked)
        cover = baked.cover
        // What is printed, and for which record: a record being put on is not shown in the last one's sleeve.
        stage.printedAlbum = playing?.albumId
        stage.printedSides = sides
        stage.changed = true
    }
    // The back of the sleeve is printed again whenever the line that is lit moves, and when a
    // side has ended and the record is waiting to be turned to its next one.
    val turnTo = stage.awaiting
    LaunchedEffect(album?.albumId, sides, deck.currentTrackIndex, cover, turnTo) {
        val playing = album
        val titles = deck.tracks.map { it.title }
        val printed = cover
        val back = withContext(Dispatchers.Default) {
            VinylArt.sleeveBack(
                context, printed, playing?.title.orEmpty(), playing?.artist.orEmpty(), sides, titles,
                playing = if (turnTo >= 0) -1 else deck.currentTrackIndex, turnTo = turnTo
            )
        }
        art.writeBack(back)
        stage.changed = true
    }

    // Back is one step out each time: off the record, down to the crate, out to the showroom.
    BackHandler(enabled = !covered) {
        when {
            stage.zoomTarget > 0.5f -> stage.zoomTarget = 0f
            stage.atDeck -> stage.toCrate()
            else -> onOpenShowroom()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        stage.widthPx = constraints.maxWidth.toFloat()
        stage.heightPx = constraints.maxHeight.toFloat()

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            renderer = renderer,
            // Under a panel, behind another room, or with the app on its way out of sight: nothing is drawn.
            isRendering = !covered && !LocalRoomParked.current,
            environment = environment,
            mainLightNode = null,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            onTouchEvent = { event, _ ->
                if (!currentCovered.value) {
                    stage.onTouch(
                        event, currentDeck.value, currentSides.value, currentPosition.value,
                        onPlayPause = { viewModel.onPlayPause() },
                        onWalk = viewModel::onScrub,
                        // A deck with nothing on it: the crate is where a record comes from.
                        onEmptyDeck = { stage.toCrate() }
                    )
                }
                true
            },
            onFrame = {
                if (!VinylAtmosphere.isApplied(filamentView)) VinylAtmosphere.apply(filamentView, tier)
                val now = SystemClock.uptimeMillis()
                stage.onFrame(now, currentDeck.value, currentSides.value, currentPosition.value, currentNowPlaying.value)

                // The lens, written every frame: SceneView's camera re-aims itself at the origin
                // if it is left alone (`ENGINEERING.md`, trap 8).
                val shot = stage.shot
                cameraNode.position = Position(shot.x, shot.y, shot.z)
                cameraNode.rotation = Rotation(x = shot.pitchDeg)

                // Every vsync while a hand is on it or anything is on its way somewhere; sixty a
                // second while a record turns, which is under a pixel of difference at its rim;
                // and at rest, a still deck is drawn a few times a second and no more.
                val own = when {
                    stage.busy -> cadence.live
                    stage.turning -> cadence.divisorForFps(60f)
                    else -> cadence.divisorForFps(8f)
                }
                val arriving = RoomCalm.frameCap(now)
                val divisor = maxOf(own, if (arriving == Float.MAX_VALUE) 1 else cadence.divisorForFps(arriving))
                if (divisor != stage.publishedDivisor) {
                    stage.publishedDivisor = divisor
                    renderer.frameRateOptions = Renderer.FrameRateOptions().apply { interval = divisor.toFloat() }
                }
                if (!cadence.shouldDraw(divisor)) renderer.skipNextFrames(1)
            }
        ) {
            // **The lamp**: the one light that casts. The arm's shadow across the record is most
            // of what puts the arm above it.
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(VinylLighting.KEY_POSITION[0], VinylLighting.KEY_POSITION[1], VinylLighting.KEY_POSITION[2]),
                direction = Direction(
                    VinylLighting.KEY_AIM[0] - VinylLighting.KEY_POSITION[0],
                    VinylLighting.KEY_AIM[1] - VinylLighting.KEY_POSITION[1],
                    VinylLighting.KEY_AIM[2] - VinylLighting.KEY_POSITION[2]
                ),
                apply = {
                    color(VinylLighting.KEY_COLOUR[0], VinylLighting.KEY_COLOUR[1], VinylLighting.KEY_COLOUR[2])
                    intensityCandela(VinylLighting.KEY_CANDELA)
                    spotLightCone(VinylLighting.KEY_CONE_INNER_RAD, VinylLighting.KEY_CONE_OUTER_RAD)
                    falloff(VinylLighting.KEY_FALLOFF_M)
                    castShadows(tier.shadowsOnFixtures)
                    shadowOptions(
                        LightManager.ShadowOptions().apply {
                            mapSize = tier.shadowMapPx
                            shadowNearHint = VinylLighting.KEY_SHADOW_NEAR_M
                            shadowFarHint = VinylLighting.KEY_SHADOW_FAR_M
                        }
                    )
                }
            )
            // The deck's own two: the target light on the stylus, the strobe on the platter's rim.
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(VinylLighting.TARGET_POSITION[0], VinylLighting.TARGET_POSITION[1], VinylLighting.TARGET_POSITION[2]),
                direction = Direction(-0.02f, -0.15f, -1f),
                apply = {
                    color(VinylLighting.TARGET_COLOUR[0], VinylLighting.TARGET_COLOUR[1], VinylLighting.TARGET_COLOUR[2])
                    intensityCandela(VinylLighting.TARGET_CANDELA)
                    spotLightCone(VinylLighting.TARGET_CONE_INNER_RAD, VinylLighting.TARGET_CONE_OUTER_RAD)
                    falloff(VinylLighting.TARGET_FALLOFF_M)
                    castShadows(false)
                }
            )
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(VinylLighting.STROBE_POSITION[0], VinylLighting.STROBE_POSITION[1], VinylLighting.STROBE_POSITION[2]),
                apply = {
                    color(VinylLighting.STROBE_COLOUR[0], VinylLighting.STROBE_COLOUR[1], VinylLighting.STROBE_COLOUR[2])
                    intensityCandela(VinylLighting.STROBE_CANDELA)
                    falloff(VinylLighting.STROBE_FALLOFF_M)
                    castShadows(false)
                }
            )

            roomModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = false; isShadowReceiver = true }) }
            deckModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = true; isShadowReceiver = true }) }

            // Everything that turns: the platter under the motor, and the record on its mat —
            // two nodes, because a record that is held stands still on a platter that does not.
            platterModel?.let { model ->
                Node(
                    position = Position(VinylDimens.SPINDLE_X, VinylDimens.TOP_Y, VinylDimens.SPINDLE_Z),
                    apply = { stage.platterNode = this }
                ) {
                    ModelNode(model, apply = { isTouchable = false; isShadowCaster = true; isShadowReceiver = true })
                }
            }
            recordModel?.let { model ->
                Node(
                    position = Position(VinylDimens.SPINDLE_X, VinylDimens.RECORD_Y, VinylDimens.SPINDLE_Z),
                    apply = { stage.recordNode = this }
                ) {
                    // Two turns, one inside the other: the outer node is which side is up — a
                    // turn about the line from you to the back of the deck, the same line
                    // wherever the record has stopped — and the inner one is the record going
                    // round on whichever side that is.
                    Node(apply = { stage.recordFlip = this }) {
                        ModelNode(
                            model,
                            apply = {
                                isTouchable = false
                                isShadowCaster = false
                                isShadowReceiver = true
                                art.bindRecord(renderableNodes, vinylA, vinylB)
                                stage.recordModel = this
                            }
                        )
                    }
                }
            }
            armModel?.let { model ->
                Node(
                    position = Position(VinylDimens.PIVOT_X, VinylDimens.PIVOT_Y, VinylDimens.PIVOT_Z),
                    apply = { stage.armNode = this }
                ) {
                    Node(apply = { stage.armTilt = this }) {
                        ModelNode(model, apply = { isTouchable = false; isShadowCaster = true; isShadowReceiver = true })
                    }
                }
            }

            // The sleeve, laid out in front of the deck: its front, pushed out to one side, and
            // its back on top of that, square to you — the tracklist. Each is stood up by the
            // model and laid down here by two turns, one inside the other, so that neither
            // depends on the order a single rotation's angles are applied in.
            sleeveFrontModel?.let { model ->
                Node(
                    position = Position(-0.062f, VinylDimens.SLEEVE_T / 2, SLEEVE_FRONT_Z - 0.025f),
                    rotation = Rotation(y = 5f),
                    apply = { stage.sleeveFrontNode = this }
                ) {
                    ModelNode(
                        model,
                        // As a parameter, not in `apply`: the node writes its own rotation after
                        // `apply` has run, and a sleeve laid down there stood straight back up.
                        rotation = Rotation(x = -90f),
                        apply = {
                            isTouchable = false
                            isShadowCaster = true
                            isShadowReceiver = true
                            art.bindSleeve(renderableNodes, front = art.front, back = art.front)
                        }
                    )
                }
            }
            sleeveBackModel?.let { model ->
                Node(
                    position = Position(SLEEVE_BACK_X, VinylDimens.SLEEVE_T * 1.5f, SLEEVE_FRONT_Z),
                    rotation = Rotation(y = 180f),
                    apply = { stage.sleeveBackNode = this }
                ) {
                    ModelNode(
                        model,
                        rotation = Rotation(x = 90f),
                        apply = {
                            isTouchable = false
                            isShadowCaster = true
                            isShadowReceiver = true
                            art.bindSleeve(renderableNodes, front = art.front, back = art.back)
                        }
                    )
                }
            }

            // **The crate**, on the floor in front of the table: its wood, the sleeves standing
            // in it and the cards between the shelves. Every sleeve and card is one of a pool,
            // keyed by its place in the pool and never by the record it happens to be.
            Node(position = Position(0f, VinylDimens.FLOOR_Y, VinylDimens.CRATE_Z)) {
                crateModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = true; isShadowReceiver = true }) }
                crateSleeves.forEachIndexed { slot, instance ->
                    key(slot) {
                        ModelNode(
                            instance,
                            apply = {
                                isTouchable = false
                                isShadowCaster = true
                                isShadowReceiver = true
                                crate.adoptSleeve(slot, this)
                            }
                        )
                    }
                }
                crateCards.forEachIndexed { index, instance ->
                    key(index) {
                        ModelNode(
                            instance,
                            apply = {
                                isTouchable = false
                                isShadowCaster = true
                                isShadowReceiver = true
                                crate.adoptCard(index, this)
                            }
                        )
                    }
                }
            }
            // The crate's own lamp: in front of it and to the lamp's side, so that the faces of
            // the sleeves — which lean away from the lamp over the deck, and get nothing from it —
            // can be read. On its own carrier, which is how the frame loop finds it to turn it
            // down as the camera leaves.
            Node(apply = { stage.crateLamp = this }) {
                LightNode(
                    type = LightManager.Type.SPOT,
                    position = Position(VinylLighting.CRATE_POSITION[0], VinylLighting.CRATE_POSITION[1], VinylLighting.CRATE_POSITION[2]),
                    direction = Direction(
                        VinylLighting.CRATE_AIM[0] - VinylLighting.CRATE_POSITION[0],
                        VinylLighting.CRATE_AIM[1] - VinylLighting.CRATE_POSITION[1],
                        VinylLighting.CRATE_AIM[2] - VinylLighting.CRATE_POSITION[2]
                    ),
                    apply = {
                        color(VinylLighting.CRATE_COLOUR[0], VinylLighting.CRATE_COLOUR[1], VinylLighting.CRATE_COLOUR[2])
                        intensityCandela(VinylLighting.CRATE_CANDELA)
                        spotLightCone(VinylLighting.CRATE_CONE_INNER_RAD, VinylLighting.CRATE_CONE_OUTER_RAD)
                        falloff(VinylLighting.CRATE_FALLOFF_M)
                        castShadows(tier.shadowsOnFixtures)
                        shadowOptions(
                            LightManager.ShadowOptions().apply {
                                mapSize = 1024
                                shadowNearHint = 0.4f
                                shadowFarHint = 2.4f
                            }
                        )
                    }
                )
            }

            // Declared inside the scene so that it is disposed before the scene is (trap 35):
            // the materials stop sampling these before the textures go.
            DisposableEffect(art) { onDispose { stage.release() } }
        }

        // **The way out**, at the crate and nowhere else: to the other rooms, and to Settings.
        // The CD room's own two buttons, at the foot of the frame where the floor is — over the
        // deck there is nothing on the glass but the deck. (Adding a record is in the crate
        // itself: the card at the back of it.)
        AnimatedVisibility(
            visible = !stage.deckward && !covered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 48.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                ChromeAction(
                    icon = null,
                    label = "ROOMS",
                    onClick = onOpenRooms,
                    modifier = Modifier.weight(1f),
                    iconContent = { JawonMark(size = 24.dp, color = LabelCream.copy(alpha = 0.82f), contentDescription = null) }
                )
                ChromeAction(Icons.Default.Settings, "SETTINGS", onOpenSettings, Modifier.weight(1f))
            }
        }
    }
}

private fun dressVinyl(vinyl: MaterialInstance) {
    vinyl.setParameter("baseColor", VINYL_BLACK, VINYL_BLACK, VINYL_BLACK * 1.1f)
    vinyl.setParameter("anisotropy", VINYL_ANISOTROPY)
    vinyl.setParameter("reflectance", 0.5f)
}

/** What a record is printed with, baked off the main thread. */
private class VinylBaked(
    val cover: Bitmap?,
    val labelA: Bitmap,
    val labelB: Bitmap,
    val bandsA: Bitmap,
    val bandsB: Bitmap,
    val front: Bitmap
)

/**
 * The room's textures: allocated once, written for every record, and bound to the models' own
 * materials by the names the model script gave them (`docs/models.md`) — never replacing a
 * material, which would throw away the anisotropy that makes a record a record.
 */
private class VinylTextures(private val engine: Engine) {

    private fun colour(px: Int): Texture = Texture.Builder()
        .width(px).height(px)
        .levels(32 - Integer.numberOfLeadingZeros(px))
        .format(Texture.InternalFormat.SRGB8_A8)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
        .build(engine)

    private fun line(): Texture = Texture.Builder()
        .width(VinylArt.BAND_PX).height(4)
        .levels(1)
        // Not colour: roughness, read as the number it is.
        .format(Texture.InternalFormat.RGBA8)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .build(engine)

    val labelA = colour(VinylArt.LABEL_PX)
    val labelB = colour(VinylArt.LABEL_PX)
    val bandsA = line()
    val bandsB = line()
    val front = colour(VinylArt.SLEEVE_PX)
    val back = colour(VinylArt.SLEEVE_PX)

    private val mipped by lazy {
        TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            .apply { anisotropy = 8f }
    }
    private val plain by lazy {
        TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    }
    private var alive = true

    fun write(baked: VinylBaked) {
        if (!alive) return
        put(labelA, baked.labelA, mips = true)
        put(labelB, baked.labelB, mips = true)
        put(bandsA, baked.bandsA, mips = false)
        put(bandsB, baked.bandsB, mips = false)
        put(front, baked.front, mips = true)
    }

    fun writeBack(bitmap: Bitmap) {
        if (alive) put(back, bitmap, mips = true)
    }

    private fun put(texture: Texture, bitmap: Bitmap, mips: Boolean) {
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        if (mips) texture.generateMipmaps(engine)
    }

    fun bindRecord(parts: List<Node>, vinylA: MaterialInstance, vinylB: MaterialInstance) {
        vinylA.setParameter("bands", bandsA, plain)
        vinylB.setParameter("bands", bandsB, plain)
        for (part in parts) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            when (part.name) {
                "label_a" -> material.setParameter("baseColorMap", labelA, mipped)
                "label_b" -> material.setParameter("baseColorMap", labelB, mipped)
                // The playing surfaces take the room's own material in place of the model's:
                // gltfio's has nowhere to put a record's highlight (`vinyl.mat`).
                "vinyl_a" -> (part as RenderableNode).materialInstance = vinylA
                "vinyl_b" -> (part as RenderableNode).materialInstance = vinylB
            }
        }
    }

    fun bindSleeve(parts: List<Node>, front: Texture, back: Texture) {
        for (part in parts) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            when (part.name) {
                "sleeve_front" -> material.setParameter("baseColorMap", front, mipped)
                "sleeve_back" -> material.setParameter("baseColorMap", back, mipped)
            }
        }
    }

    fun destroy() {
        if (!alive) return
        alive = false
        for (texture in listOf(labelA, labelB, bandsA, bandsB, front, back)) runCatching { engine.destroyTexture(texture) }
    }
}

/**
 * What moves, and what a finger does: the frame loop's side of the room.
 *
 * A plain object the scene writes its nodes into, in the way `PocketDevice` and the showroom's
 * director are: nothing here is Compose state, because nothing here should recompose anything —
 * a platter's angle changes a hundred and twenty times a second.
 */
private class VinylStage(
    private val scratch: ScratchAudio,
    private val haptics: RoomHaptics,
    private val crate: VinylCrate,
    private val needle: NeedlePlayer,
    startAtDeck: Boolean
) {

    var platterNode: Node? = null
    var recordNode: Node? = null
    var recordFlip: Node? = null
    var armNode: Node? = null
    var armTilt: Node? = null
    var sleeveFrontNode: Node? = null
    var sleeveBackNode: Node? = null
    var crateLamp: Node? = null
    var recordModel: ModelNode? = null

    // ---- A record being put on, and the needle ------------------------------------------------------

    /** One record on its way from the crate to under the needle: `PutOn`'s clock, and what has happened on it. */
    private class Run(val albumId: String) {
        var t = 0f
        var rate = 1f
        var waited = 0f
        var seated = false
        var armSent = false
        var landed = false
        var musicAt = -1f
        var musicAsked = false
    }

    private var run: Run? = null
    private val pose = PutOn.Pose()
    private val spin = PlatterSpin()
    private var armMove: ArmMove? = null
    private var armMoveT = 0f
    private var armLanding: (() -> Unit)? = null

    /** The needle is in the groove, and follows it. */
    private var needleDown = false

    /** Put somewhere by a hand, and waiting — this long so far — for the player to say the music is there too. */
    private var agreeing = -1f
    private var motorUntil = 0L
    private var frames = 0
    private var snapDown = false
    private var seatedNow = false
    private var castsShadow = false

    // ---- The record's other side ------------------------------------------------------------------

    /** One turning-over: which track the needle goes down on afterwards, and from which side up to which. */
    private class Turn(val toTrack: Int, val fromDeg: Float, val toDeg: Float) {
        /** `TurnOver`'s clock; below zero while the arm is still on its way home. */
        var t = -1f
        var rate = 1f
        var armSent = false
    }

    private var turnRun: Turn? = null
    private val turnPose = TurnOver.Pose()

    /** How far over the record is — 0 with its first side up, 180 with its second — and how far off the mat. */
    private var turnedDeg = 0f
    private var liftM = 0f

    /** A side has ended and the needle has gone home; how long ago; and where the needle goes when the record is turned. */
    private var ended = false
    private var endedFor = 0f
    private var awaitedTrack = -1

    /**
     * Which side the record is waiting to be turned to, −1 when it is not waiting. Compose state,
     * unlike nearly everything else here, because the card is printed from it — and it changes
     * twice a side, not a hundred and twenty times a second.
     */
    var awaiting by mutableStateOf(-1)
        private set

    /** Settings → Turn records over for me. */
    var turnsItself = false

    /** What the sleeve and the labels are printed with, and for which record: set by the room when it has printed them. */
    var printedAlbum: String? = null
    var printedSides: List<Side>? = null
    private var deckAlbum: String? = null

    /** The needle has landed and there should be music; it has been lifted and there should not; it has been put on a track. */
    var onNeedleDown: () -> Unit = {}
    var onNeedleUp: () -> Unit = {}
    var onSelect: (Int) -> Unit = {}

    /** [albumId] is the record on the platter, with nothing on its way there. */
    fun isOn(albumId: String): Boolean = run == null && hasRecord && deckAlbum == albumId

    /** A record has been taken from the crate: up to the deck, and everything `PutOn` says, from now. */
    fun putOn(albumId: String) {
        if (held) letGo()
        run = Run(albumId)
        zoomTarget = 0f
        agreeing = -1f
        // Whatever was on comes off: the arm goes home with its head up, and the motor stops.
        sendArm(Needle.move(armYaw, armLift, Needle.REST_YAW, quick = false, down = false), null)
        motorUntil = 0L
        leaveCrate()
    }

    /** A record half way on is on, now, and one half way over is over: the room is going, or the app is. */
    fun finish() {
        val turning = turnRun
        if (turning != null) {
            turnRun = null
            armMove = null
            armLanding = null
            turnedDeg = turning.toDeg
            liftM = 0f
            snapDown = true
            agreeing = 0f
            clearEnded()
            onSelect(turning.toTrack)
        }
        val putting = run ?: return
        run = null
        armMove = null
        armLanding = null
        snapDown = true
        if (!putting.musicAsked) onNeedleDown()
    }

    /**
     * Nobody is looking: whatever was half done is done, and the player is no longer asked to
     * stop at the end of a side — a record is turned over by a hand, and waiting for one that is
     * not there is only silence in a pocket.
     */
    fun unseen() {
        finish()
        scratch.stopAtEndOfTrack(false)
    }

    private fun clearEnded() {
        ended = false
        endedFor = 0f
        awaitedTrack = -1
        if (awaiting != -1) awaiting = -1
    }

    /**
     * Turn the record over — or, for a side on another record of the set, change it, which for
     * now is drawn the same way — and put the needle down on [toTrack].
     */
    private fun turnOver(toTrack: Int, sides: List<Side>) {
        if (turnRun != null || run != null || toTrack < 0) return
        haptics.tick()
        zoomTarget = 0f
        if (awaiting != -1) awaiting = -1
        Log.i(TAG, "turning the record over for track ${toTrack + 1}" + if (ended && turnsItself) ", unasked" else "")
        turnRun = Turn(toTrack, turnedDeg, if (Sides.of(sides, toTrack) % 2 == 1) 180f else 0f)
    }

    /** Where the needle goes if the record is turned over with nothing having asked for a track. */
    private fun otherSide(deck: DeckState, sides: List<Side>): Int {
        if (awaitedTrack >= 0) return awaitedTrack
        val now = Sides.of(sides, deck.currentTrackIndex.coerceAtLeast(0))
        // The other face of the same record; a record with one side is played again.
        return (sides.getOrNull(now xor 1) ?: sides.getOrNull(now))?.firstTrack ?: -1
    }

    private fun sendArm(move: ArmMove, then: (() -> Unit)?) {
        armMove = move
        armMoveT = 0f
        armLanding = then
    }

    // ---- Where the camera is: at the crate (0), over the deck (1), or on its way ----------------

    private var place = if (startAtDeck) 1f else 0f
    private var placeTarget = place
        set(value) {
            field = value
            if (deckward != value > 0.5f) deckward = value > 0.5f
        }

    /** [atDeck], for Compose: what is drawn over the room depends on which of the two places it is. */
    var deckward by mutableStateOf(startAtDeck)
        private set
    private var placeVelocity = 0f
    private var leaveAt = 0L
    private var lampAt = -1f

    /** The deck is where the camera is, or is going. */
    val atDeck: Boolean get() = placeTarget > 0.5f

    /** Down to the crate, from wherever the camera is; a record in the hand is let go first. */
    fun toCrate() {
        if (held) letGo()
        zoomTarget = 0f
        leaveAt = 0L
        placeTarget = 0f
    }

    /** Up to the deck — if there is a record on it to go up to. */
    fun toDeck() {
        if (hasRecord) placeTarget = 1f
    }

    /** A record has been taken out of the crate: up to the deck, a moment after it is seen to go. */
    fun leaveCrate() {
        leaveAt = SystemClock.uptimeMillis() + TAKE_LEAD_MS
    }

    var widthPx = 1f
    var heightPx = 1f
    var touchSlopPx = 24f
    var publishedDivisor = 0

    /** Something was printed or put somewhere: draw, whatever the cadence was about to do. */
    var changed = true

    var zoom = 0f
    var zoomTarget = 0f
    var shot = VinylCamera.play(0f, 2f)
        private set

    private var platterAngle = 0f
    private var recordAngle = 0f
    private var armYaw = Needle.REST_YAW
    private var armLift = Needle.UP_DEG
    private var lastFrameAt = 0L
    private var hasRecord = false

    /** A hand is on it, or the zoom or the arm is still on its way. */
    var busy = false
        private set

    /** The record is going round. */
    var turning = false
        private set

    fun onFrame(now: Long, deck: DeckState, sides: List<Side>, positionMs: Long, nowPlaying: String?) {
        val dt = if (lastFrameAt == 0L) 0f else ((now - lastFrameAt) / 1000f).coerceAtMost(0.1f)
        lastFrameAt = now
        hasRecord = deck.tracks.isNotEmpty() && sides.isNotEmpty()
        val aspect = heightPx / widthPx

        // ---- between the crate and the deck -------------------------------------------------------
        if (leaveAt != 0L && now >= leaveAt) {
            leaveAt = 0L
            placeTarget = 1f
        }
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            placeVelocity += (PLACE_STIFFNESS * PLACE_STIFFNESS * (placeTarget - place) - 2f * PLACE_STIFFNESS * placeVelocity) * h
            place += placeVelocity * h
            left -= h
        }
        if (abs(placeTarget - place) < 0.0006f && abs(placeVelocity) < 0.004f) {
            place = placeTarget
            placeVelocity = 0f
        }
        val risen = place.coerceIn(0f, 1f)

        // ---- which side is up, and where on it the music is ------------------------------------------
        deckAlbum = deck.tracks.firstOrNull()?.albumId
        val playing = deck.isPlaying
        // The player's own track and place, asked every frame: what the room is told arrives
        // twice a second, and an arm drawn from that crosses a short track in steps — each one
        // far enough to be taken for the music having gone somewhere else.
        val track = scratch.playerTrack().takeIf { it in deck.tracks.indices } ?: deck.currentTrackIndex.coerceAtLeast(0)
        val sideIndex = if (hasRecord) Sides.of(sides, track) else 0
        val flipped = sideIndex % 2 == 1

        val side = sides.getOrNull(sideIndex)
        val heard = scratch.positionMs() ?: scratch.playerPositionMs() ?: positionMs
        val radius = if (side != null) side.radiusAt(side.fraction(track, heard)) else VinylDimens.REST_RADIUS
        val grooveYaw = VinylArm.yawDegrees(radius)

        // ---- a record being put on ---------------------------------------------------------------------
        val putting = run
        var rate = 1f
        // The record this is about exists, and is printed: its tracks are the deck's, its sleeve is its own.
        var here = hasRecord
        if (putting != null) {
            here = deckAlbum == putting.albumId && side != null && printedAlbum == putting.albumId && printedSides === sides
            // Started from somewhere else while it was on its way — a car, the widget: it is on, and this catches up.
            if (here && playing && !putting.landed) putting.rate = HURRY
            rate = putting.rate
            if (here || putting.t < PutOn.SLEEVE_FROM - 0.05f) {
                putting.t += dt * rate
            } else {
                // The camera can be on its way, but nothing is laid on the table before it exists.
                putting.waited += dt
                if (putting.waited > GIVE_UP_S) run = null
            }
            PutOn.at(putting.t, pose)
            if (pose.seated && !putting.seated) {
                putting.seated = true
                haptics.tick()
            }
            if (here && !putting.armSent && putting.t >= PutOn.ARM_AT) {
                putting.armSent = true
                sendArm(Needle.move(armYaw, armLift, grooveYaw, quick = false)) {
                    needle.drop(1f)
                    haptics.detent()
                    putting.landed = true
                    putting.musicAt = putting.t + Needle.LEAD_IN_S
                    Log.i(TAG, "put on: needle down ${"%.2f".format(putting.t)}s after the touch${if (putting.rate > 1f) ", hurried" else ""}")
                }
            }
            if (putting.landed && !putting.musicAsked && putting.t >= putting.musicAt) {
                putting.musicAsked = true
                motorUntil = now + MOTOR_HOLD_MS
                onNeedleDown()
            }
            if (putting.musicAsked && putting.t >= PutOn.SETTLED_AT) run = null
        } else {
            PutOn.rest(pose)
        }
        // ---- the end of a side, and the record turned over ------------------------------------------
        val lastOfSide = side != null && track == side.lastTrack
        // Asked for every frame this is drawn, and only then: see `unseen`.
        scratch.stopAtEndOfTrack(putting == null && lastOfSide && track + 1 < deck.tracks.size)
        if (!ended) {
            if (putting == null && turnRun == null && needleDown && armMove == null && !held &&
                !playing && lastOfSide && scratch.endedTrack()
            ) {
                // The side has run out: the needle goes home, the motor stops, and the card says which side is next.
                ended = true
                Log.i(TAG, "side ${Sides.letter(sideIndex)} ended at track ${track + 1}; " +
                    if (track + 1 < deck.tracks.size) "waiting to be turned over${if (turnsItself) ", and will be" else ""}" else "the record is over")
                endedFor = 0f
                awaitedTrack = if (track + 1 < deck.tracks.size) track + 1 else -1
                awaiting = if (awaitedTrack >= 0) Sides.of(sides, awaitedTrack) else -1
                motorUntil = 0L
                sendArm(Needle.move(armYaw, armLift, Needle.REST_YAW, quick = false, down = false), null)
            }
        } else if (turnRun == null) {
            if (playing || !lastOfSide || !scratch.endedTrack()) {
                // Going again, or sent somewhere else — the notification, a car: it is not waiting any more.
                clearEnded()
            } else {
                endedFor += dt
                if (turnsItself && awaitedTrack >= 0 && endedFor >= TurnOver.UNASKED_AFTER_S) turnOver(awaitedTrack, sides)
            }
        }
        val flip = turnRun
        if (flip != null) {
            rate = maxOf(rate, flip.rate)
            if (flip.t < 0f) {
                // Not from under a needle: the arm is home before the record leaves the mat.
                if (armMove == null) {
                    if (needleDown) {
                        if (playing) onNeedleUp()
                        sendArm(Needle.move(armYaw, armLift, Needle.REST_YAW, quick = true, down = false), null)
                    } else if (abs(armYaw - Needle.REST_YAW) < 0.4f) {
                        flip.t = 0f
                    }
                }
            } else {
                if (flip.t < TurnOver.TOTAL_S) flip.t = (flip.t + dt * flip.rate).coerceAtMost(TurnOver.TOTAL_S)
                TurnOver.at(flip.t, turnPose)
                liftM = turnPose.lift
                turnedDeg = flip.fromDeg + (flip.toDeg - flip.fromDeg) * turnPose.turned
                if (flip.t >= TurnOver.TOTAL_S && !flip.armSent) {
                    // Down on its other side: the motor, and the needle to the track that was asked for.
                    flip.armSent = true
                    haptics.tick()
                    motorUntil = now + MOTOR_HOLD_MS
                    val to = sides.getOrNull(Sides.of(sides, flip.toTrack))
                    val yaw = VinylArm.yawDegrees(to?.radiusAt(to.fraction(flip.toTrack, 0L)) ?: VinylDimens.GROOVE_OUT)
                    sendArm(Needle.move(armYaw, armLift, yaw, quick = false)) {
                        needle.drop(1f)
                        haptics.detent()
                        agreeing = 0f
                        motorUntil = SystemClock.uptimeMillis() + MOTOR_HOLD_MS
                        turnRun = null
                        Log.i(TAG, "turned over: needle down on track ${flip.toTrack + 1}")
                        onSelect(flip.toTrack)
                    }
                }
            }
        } else {
            liftM = 0f
            // With nothing flip it and nothing just having turned it, the side that is up is
            // the side the music is on — a record started on its second side, a seek from a car.
            val wanted = if (flipped) 180f else 0f
            if (agreeing < 0f && turnedDeg != wanted) turnedDeg = wanted
        }
        val aloft = flip != null && flip.t > 0f && !turnPose.seated
        val seated = here && pose.seated && !aloft
        seatedNow = seated

        // ---- the arm --------------------------------------------------------------------------------------
        val move = armMove
        if (move != null) {
            armMoveT += dt * rate
            armYaw = move.yaw(armMoveT)
            armLift = move.lift(armMoveT)
            if (armMoveT >= move.landsAt) {
                armMove = null
                needleDown = move.endsDown
                val then = armLanding
                armLanding = null
                then?.invoke()
            }
        } else if (needleDown) {
            val off = abs(grooveYaw - armYaw)
            when {
                // Nothing under it: home.
                !seated || side == null -> sendArm(Needle.move(armYaw, armLift, Needle.REST_YAW, quick = false, down = false), null)
                // Put here by a hand: it stays until the player says the music is here too.
                agreeing >= 0f -> {
                    agreeing += dt
                    if (off <= Needle.HOP_DEG || agreeing > AGREE_WITHIN_S) agreeing = -1f
                }
                // The music has gone somewhere else — a seek from the notification, a car — and a
                // needle is not dragged there: it is lifted and put there.
                off > Needle.HOP_DEG && !held && !scratch.holding && !scratch.turning ->
                    sendArm(Needle.move(armYaw, armLift, grooveYaw, quick = true), null)
                else -> armYaw += (grooveYaw - armYaw) * (1f - exp(-ARM_FOLLOW * dt))
            }
        } else if (seated && side != null && putting == null && !ended && flip == null) {
            // A record is on and the needle is not down — a room opened on a record that is
            // playing: it is where the music is, at once. Later than that, it is put there.
            if (frames < 4 || snapDown) {
                armYaw = grooveYaw
                armLift = 0f
                needleDown = true
            } else {
                sendArm(Needle.move(armYaw, armLift, grooveYaw, quick = false), null)
            }
            snapDown = false
        } else {
            val ease = 1f - exp(-ARM_EASE * dt)
            armYaw += (Needle.REST_YAW - armYaw) * ease
            armLift += (Needle.UP_DEG - armLift) * ease
        }
        armNode?.rotation = Rotation(y = armYaw)
        armTilt?.rotation = Rotation(x = -armLift)

        // While the record can be touched, the scratch keeps the audio round the playhead in hand.
        scratch.arm(seated && needleDown && atDeck)

        // ---- the platter, and the record on it -------------------------------------------------
        // Under a motor: it comes up to speed and it runs down. The motor is on while there is
        // music, while a hand holds the record on a slipping mat, from when a record is seated,
        // and through the silence of a needle in the air.
        val motor = seated && (playing || scratch.holding || scratch.turning || (putting != null && pose.motor) || now < motorUntil)
        if (frames == 0) spin.set(motor)
        spin.step(dt, motor)
        platterAngle -= PLATTER_DEG_PER_S * dt * spin.speed
        when {
            // In the hand: the record is wherever the finger has turned it, and that is written
            // by the touch itself.
            held -> Unit
            // Let go, and the engine is winding it back up, or spinning it back.
            scratch.turning -> recordAngle -= PLATTER_DEG_PER_S * dt * scratch.rate
            // On the mat, it goes round with the platter — up to speed with it, and down.
            seated -> recordAngle -= PLATTER_DEG_PER_S * dt * spin.speed
        }
        platterAngle %= 360f
        recordAngle %= 360f
        platterNode?.rotation = Rotation(y = platterAngle)

        // ---- the record, its sleeve and the card: where `PutOn` has them -------------------------------
        // Turned about its own middle, not its underside: half its thickness, taken round with it.
        val over = Math.toRadians(turnedDeg.toDouble())
        val half = VinylDimens.LP_H / 2
        recordNode?.position = Position(
            pose.recordX + half * sin(over).toFloat(),
            pose.recordY + liftM + half * (1f - cos(over).toFloat()),
            pose.recordZ
        )
        recordNode?.rotation = Rotation(z = turnedDeg)
        // On its second side the record's own up is down, and the same way round is the other way about.
        recordFlip?.rotation = Rotation(y = if (turnedDeg > 90f && turnedDeg < 270f) -recordAngle else recordAngle)
        recordNode?.isVisible = here && pose.recordSeen
        sleeveFrontNode?.position = Position(pose.sleeveX, pose.sleeveY, pose.sleeveZ)
        sleeveFrontNode?.rotation = Rotation(y = pose.sleeveYaw)
        sleeveFrontNode?.isVisible = here && pose.sleeveSeen
        sleeveBackNode?.position = Position(pose.cardX, pose.cardY, pose.cardZ)
        sleeveBackNode?.rotation = Rotation(y = pose.cardYaw)
        sleeveBackNode?.isVisible = here && pose.cardSeen
        // In the air a record has a shadow, and the shadow closing on it is how it is seen to
        // come down from straight above; on the mat it has none to speak of, and asking for one
        // only speckles the mat.
        val shadow = here && pose.recordSeen && (!pose.seated || aloft)
        if (shadow != castsShadow) {
            castsShadow = shadow
            recordModel?.isShadowCaster = shadow
        }
        frames++

        // ---- the lens ------------------------------------------------------------------------------------
        zoom += (zoomTarget - zoom) * (1f - exp(-ZOOM_EASE * dt))
        if (abs(zoomTarget - zoom) < 0.0005f) zoom = zoomTarget
        val above = VinylCamera.play(zoom, aspect)
        // The crate is gone through only while it can be seen.
        val digging = risen < 1f && crate.onFrame(dt, now, nowPlaying)
        shot = if (risen >= 1f) above else {
            val down = VinylCamera.crate(aspect, crate.readsAt * CRATE_FOLLOW)
            if (risen <= 0f) down else VinylCamera.between(down, above, risen)
        }
        // The crate's lamp goes down as the camera leaves it: nothing it lights is in the frame from above.
        val lamp = VinylLighting.CRATE_CANDELA * (1f - risen)
        if (abs(lamp - lampAt) > 0.5f) {
            (crateLamp?.childNodes?.firstOrNull { it is LightNode } as? LightNode)?.let {
                it.setIntensityCandela(lamp)
                lampAt = lamp
            }
        }

        turning = spin.speed > 0.001f
        busy = held || pinching || changed || zoom != zoomTarget || place != placeTarget || leaveAt != 0L ||
            digging || crate.busy || crate.changed ||
            run != null || turnRun != null || armMove != null || agreeing >= 0f || (spin.speed > 0f && spin.speed < 1f) ||
            (ended && turnsItself && awaitedTrack >= 0) ||
            (!needleDown && (abs(Needle.REST_YAW - armYaw) > 0.02f || abs(Needle.UP_DEG - armLift) > 0.02f))
        changed = false
        crate.changed = false
    }

    // ---- A finger ------------------------------------------------------------------------------------

    private var held = false
    private var pinching = false
    private var pinchSpan = 0f
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L
    private var lastAngle = 0f
    private var turned = 0f
    private var grabTrack = 0
    private var grabOffsetMs = 0L
    private var cursor = Scratch.Cursor(0, 0L)
    private var durations: List<Long> = emptyList()
    private var lastWalkAt = 0L
    private val hapticStep = Scratch.HapticAccumulator()
    private val spot = FloatArray(2)

    fun onTouch(
        event: MotionEvent,
        deck: DeckState,
        sides: List<Side>,
        positionMs: Long,
        onPlayPause: () -> Unit,
        onWalk: (Int, Long) -> Unit,
        onEmptyDeck: () -> Unit
    ) {
        // At the crate, a finger is the crate's; on the way between the two, nobody's.
        if (place < 0.04f && placeTarget == 0f) {
            crate.onTouch(event, shot, widthPx, heightPx, touchSlopPx)
            return
        }
        // A record on its way to the platter: a touch hurries it, and is nothing else.
        run?.let { putting ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) putting.rate = HURRY
            return
        }
        turnRun?.let { turning ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) turning.rate = HURRY
            return
        }
        if (place < 0.96f || placeTarget != 1f) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                downX = event.x
                downY = event.y
                moved = false
                // A record is a hand's to turn once it is on the mat with the needle in it.
                if (seatedNow && needleDown && armMove == null && onRecord(event.x, event.y)) grab(event, deck, positionMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: this is a pinch, and whatever the first was doing is over.
                if (held) letGo()
                pinching = true
                moved = true
                pinchSpan = span(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlopPx) moved = true
                if (pinching && event.pointerCount >= 2) {
                    val now = span(event)
                    if (pinchSpan > 1f && now > 1f) {
                        // Spreading closes in. A whole zoom is a pinch of about two and a third times.
                        zoomTarget = (zoomTarget + ln(now / pinchSpan) / PINCH_FOR_WHOLE_ZOOM).coerceIn(0f, 1f)
                    }
                    pinchSpan = now
                } else if (held) {
                    turn(event, onWalk)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) pinching = false
            MotionEvent.ACTION_UP -> {
                val wasHeld = held
                if (held) letGo()
                pinching = false
                val quick = event.eventTime - downAt <= TAP_LONGEST_MS
                if (!moved && quick) {
                    if (hasRecord) tap(event, deck, sides, wasHeld, onPlayPause) else onEmptyDeck()
                } else if (ended && moved && onRecord(downX, downY) && abs(event.x - downX) > touchSlopPx * 3f) {
                    // A hand drawn across a record that is waiting: that is turning it over.
                    turnOver(otherSide(deck, sides), sides)
                }
                // Left between the two: come to rest at whichever is nearer.
                zoomTarget = if (zoomTarget >= 0.5f) 1f else 0f
            }
            MotionEvent.ACTION_CANCEL -> {
                if (held) letGo()
                pinching = false
            }
        }
    }

    private fun span(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    /** Whether a point of the screen is on the record: found on the record's own surface, not guessed. */
    private fun onRecord(px: Float, py: Float): Boolean {
        VinylCamera.onPlane(shot, px, py, widthPx, heightPx, VinylDimens.RECORD_TOP_Y, spot)
        return hypot(spot[0] - VinylDimens.SPINDLE_X, spot[1] - VinylDimens.SPINDLE_Z) <= VinylDimens.LP_R
    }

    /** The bearing of a point of the screen from the spindle, clockwise on screen: the way a record turns. */
    private fun bearing(px: Float, py: Float): Float {
        VinylCamera.onPlane(shot, px, py, widthPx, heightPx, VinylDimens.RECORD_TOP_Y, spot)
        return atan2(spot[1] - VinylDimens.SPINDLE_Z, spot[0] - VinylDimens.SPINDLE_X)
    }

    private fun grab(event: MotionEvent, deck: DeckState, positionMs: Long) {
        val index = deck.currentTrackIndex.coerceAtLeast(0)
        val track = deck.tracks.getOrNull(index) ?: return
        held = true
        lastAngle = bearing(event.x, event.y)
        turned = 0f
        durations = deck.tracks.map { it.durationMs }
        grabTrack = index
        grabOffsetMs = positionMs
        cursor = Scratch.Cursor(index, positionMs)
        lastWalkAt = 0L
        hapticStep.reset()
        scratch.grab(
            uri = Uri.parse(track.fileUri),
            trackIndex = index,
            trackDurationMs = track.durationMs,
            playing = deck.isPlaying,
            positionMs = positionMs
        )
    }

    private fun turn(event: MotionEvent, onWalk: (Int, Long) -> Unit) {
        val angle = bearing(event.x, event.y)
        val delta = Scratch.angleDelta(lastAngle, angle)
        lastAngle = angle
        turned += delta
        // The record goes round with the finger — exactly, because here the gearing is not a
        // choice: one turn of a record is 1.8 seconds of it.
        recordAngle -= Math.toDegrees(delta.toDouble()).toFloat()
        val seconds = turned.toDouble() / (2.0 * Math.PI) * Scratch.SEC_PER_REV
        scratch.hand(seconds, event.eventTime * 1_000_000L)
        val place = scratch.positionMs() ?: (grabOffsetMs + (seconds * 1000).toLong())
        cursor = Scratch.advance(Scratch.Cursor(grabTrack, 0L), place, durations)
        val now = SystemClock.uptimeMillis()
        if (hapticStep.accept(Math.toDegrees(delta.toDouble()).toFloat(), now)) haptics.tick()
        if (scratch.holding && now - lastWalkAt >= WALK_EVERY_MS) {
            lastWalkAt = now
            if (!scratch.walk(cursor.trackIndex, cursor.offsetMs)) onWalk(cursor.trackIndex, cursor.offsetMs)
        }
    }

    private fun letGo() {
        held = false
        scratch.release(cursor.trackIndex, cursor.offsetMs)
    }

    private fun tap(
        event: MotionEvent,
        deck: DeckState,
        sides: List<Side>,
        onRecordItself: Boolean,
        onPlayPause: () -> Unit
    ) {
        // A touch on the record is a hand on the record — it has already slowed it — and is
        // never also a tap.
        if (onRecordItself) return
        // A side has ended and the needle is home: a touch on the record turns it over.
        if (ended && onRecord(event.x, event.y)) {
            turnOver(otherSide(deck, sides), sides)
            return
        }
        // The start · stop button, on the top plate.
        VinylCamera.onPlane(shot, event.x, event.y, widthPx, heightPx, VinylDimens.TOP_Y, spot)
        if (abs(spot[0] - START_X) <= START_HALF_W && abs(spot[1] - START_Z) <= START_HALF_D) {
            haptics.detent()
            // With a side waiting to be turned, starting it is turning it: there is nothing on this side left to start.
            if (ended && awaitedTrack >= 0) turnOver(awaitedTrack, sides) else onPlayPause()
            return
        }
        // A line on the back of the sleeve.
        if (hasRecord) {
            VinylCamera.onPlane(shot, event.x, event.y, widthPx, heightPx, VinylDimens.SLEEVE_T * 2, spot)
            val u = (spot[0] - SLEEVE_BACK_X) / VinylDimens.SLEEVE + 0.5f
            val v = (spot[1] - (SLEEVE_FRONT_Z - VinylDimens.SLEEVE)) / VinylDimens.SLEEVE
            val track = SleeveBackLayout.of(sides).trackAt(u, v)
            if (track >= 0) {
                if (track != deck.currentTrackIndex && armMove == null) hop(track, deck, sides)
                return
            }
        }
        // Anywhere else, twice: in to the record, or back out to the whole deck.
        if (event.eventTime - lastTapAt <= DOUBLE_TAP_WITHIN_MS) {
            zoomTarget = if (zoomTarget >= 0.5f) 0f else 1f
            haptics.detent()
            lastTapAt = 0L
        } else {
            lastTapAt = event.eventTime
        }
    }

    /**
     * The needle, put on [index] by a hand: lifted — and with it the music — swung over, and
     * lowered at the start of that track, where the music is from the moment it lands.
     */
    private fun hop(index: Int, deck: DeckState, sides: List<Side>) {
        // A track on the other side: the record is turned over to get to it.
        if (ended || Sides.of(sides, index) != Sides.of(sides, deck.currentTrackIndex.coerceAtLeast(0))) {
            turnOver(index, sides)
            return
        }
        val side = sides.getOrNull(Sides.of(sides, index)) ?: return
        val to = VinylArm.yawDegrees(side.radiusAt(side.fraction(index, 0L)))
        haptics.tick()
        if (deck.isPlaying) onNeedleUp()
        // The motor does not stop for a needle in the air.
        motorUntil = SystemClock.uptimeMillis() + MOTOR_HOLD_MS
        agreeing = -1f
        sendArm(Needle.move(armYaw, armLift, to, quick = true)) {
            needle.drop(HOP_LEVEL)
            haptics.detent()
            agreeing = 0f
            motorUntil = SystemClock.uptimeMillis() + MOTOR_HOLD_MS
            onSelect(index)
        }
    }

    /** The room is going: a record in the hand is put back, and nothing is left decoding. */
    fun release() {
        unseen()
        if (held) scratch.cancel()
        held = false
        scratch.arm(false)
    }

    private companion object {
        /** `adb logcat -s Jawon.Vinyl`: what the deck did and when, for reading off a phone. */
        const val TAG = "Jawon.Vinyl"
        const val PINCH_FOR_WHOLE_ZOOM = 0.85f
    }
}
