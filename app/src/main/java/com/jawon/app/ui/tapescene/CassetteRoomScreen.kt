package com.jawon.app.ui.tapescene

import com.jawon.app.ui.common.TurnOverPrompt
import androidx.compose.foundation.layout.Column
import com.jawon.app.ui.common.BackToChip
import com.jawon.app.ui.wallscene.NowPlayingBar
import com.jawon.app.ui.wallscene.CoverThumb
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.jawon.app.ui.common.ChromeAction
import com.jawon.app.ui.common.EmptyNote
import com.jawon.app.ui.common.FirstRunHint
import com.jawon.app.ui.common.Hints
import com.jawon.app.ui.common.JawonMark
import com.jawon.app.ui.common.LocalRoomParked
import com.jawon.app.ui.common.RoomCalm
import com.jawon.app.ui.common.displayRefreshHz
import com.jawon.app.ui.common.rememberRoomEngine
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.vinylscene.Sides
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.CaseLayout
import com.jawon.app.ui.wallscene.QualityTier
import com.jawon.app.ui.wallscene.RoomCadence
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallSceneViewModel
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
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

private const val ROOM_ASSET = "models/tape_room.glb"
private const val DECK_ASSET = "models/tape_deck.glb"
private const val CASSETTE_ASSET = "models/tape_cassette.glb"
private const val HUB_ASSET = "models/tape_hub.glb"
private const val PACK_ASSET = "models/tape_pack.glb"
private const val TRAY_ASSET = "models/tape_case_tray.glb"
private const val LID_ASSET = "models/tape_case_lid.glb"

private const val NEAR_PLANE_M = 0.04
private const val FAR_PLANE_M = 12.0

/**
 * **The cassette room** (`docs/formats.md` §6) — a sibling of the CD, pocket and vinyl rooms,
 * sharing their view model and their player and nothing of their scenes.
 *
 * Two places, and the camera goes between them: **the racks** on the wall, a tower of tapes per
 * shelf (`TapeRack`, `TapeRackScene`), and **the deck**, face on, with the tape's box open on its
 * lid so the J-card's inside is the tracklist. A tape touched in a rack is taken out and put in
 * (`LoadTape`); then the deck's keys are the deck's (`TapeTransport`), the reels are where you are
 * in the side, the counter counts, the meters swing with the music, and at the end of a side the
 * head turns over — or the tape waits to be turned over by hand. Back from the deck is back to the
 * racks, where the way to the other rooms and to Settings is.
 */
@Composable
fun CassetteRoomScreen(
    onOpenSettings: () -> Unit,
    onAddAlbum: () -> Unit,
    onOpenRooms: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenShowroom: () -> Unit = {},
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
        TapeLighting.createEnvironment(engine) { cubemap -> environmentLoader.iblPrefilter.specularFilter(cubemap) }
    }
    val cameraNode = rememberCameraNode(engine) {
        focalLength = TapeCamera.FOCAL_MM.toDouble()
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
    val cassetteModel = rememberModelInstance(modelLoader, CASSETTE_ASSET)
    val hubLeftModel = rememberModelInstance(modelLoader, HUB_ASSET)
    val hubRightModel = rememberModelInstance(modelLoader, HUB_ASSET)
    val packLeftModel = rememberModelInstance(modelLoader, PACK_ASSET)
    val packRightModel = rememberModelInstance(modelLoader, PACK_ASSET)
    val trayModel = rememberModelInstance(modelLoader, TRAY_ASSET)
    val lidModel = rememberModelInstance(modelLoader, LID_ASSET)
    // The racks and the boxes in them: a fixed number of each, made once (`ENGINEERING.md`'s first trap).
    val rackFrames = remember(modelLoader) { modelLoader.createInstancedModel(RACK_ASSET, TapeRackScene.RACK_POOL) }
    val rackBoxes = remember(modelLoader) { modelLoader.createInstancedModel(CLOSED_CASE_ASSET, TapeRackScene.CASE_POOL) }

    // ---- The tape that is in -------------------------------------------------------------------
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val nowPlayingAlbumId by viewModel.nowPlayingAlbumId.collectAsStateWithLifecycle()
    val autoReverse by viewModel.tapeAutoReverse.collectAsStateWithLifecycle()
    val hintsSeen by viewModel.hintsSeen.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.adoptPlayingAlbum() }

    val album = remember(layout, nowPlayingAlbumId) { layout.cases.firstOrNull { it.albumId == nowPlayingAlbumId } }
    val set = remember(deck.tracks) { TapeSet.of(deck.tracks.map { it.durationMs }) }
    val towers = remember(layout) { TapeRack.towers(layout) }

    val scope = rememberCoroutineScope()
    val rackTextures = remember(engine) { RackTextures(engine) }
    val rack = remember(rackTextures) { TapeRackScene(context.applicationContext, scope, rackTextures, haptics) }
    // Declared before the scene, so it is disposed after it (`ENGINEERING.md`, trap 35).
    DisposableEffect(rackTextures) {
        onDispose {
            rack.release()
            rackTextures.destroy()
        }
    }
    val sound = remember(context) { DeckSound(context) }
    DisposableEffect(sound) { onDispose { sound.close() } }
    // What the cue reads from: told when the tracks change, never every frame.
    LaunchedEffect(sound, deck.tracks) {
        sound.setSources(deck.tracks.map { DeckSound.Source(android.net.Uri.parse(it.fileUri), it.durationMs) })
    }
    val stage = remember(rack, sound) {
        TapeStage(viewModel.scratchAudio, haptics, rack, sound, viewModel.tapeState, startAtDeck = viewModel.deck.value.tracks.isNotEmpty())
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stage.unseen() }
    val unseen = covered || LocalRoomParked.current
    LaunchedEffect(unseen) { if (unseen) stage.unseen() }

    val currentNowPlaying = rememberUpdatedState(nowPlayingAlbumId)
    SideEffect {
        rack.onTake = { tape ->
            if (stage.isIn(tape.albumId)) {
                stage.toDeck()
            } else {
                // Put in, and silent until PLAY goes down: whatever was playing stops as it comes out.
                viewModel.playAlbum(tape.albumId, play = false)
                stage.take(tape.albumId, TapeDimens.rackX(tape.tower), TapeDimens.slotY(tape.slot))
            }
        }
        stage.onPlay = { viewModel.setPlaying(it) }
        stage.onSeek = { t, ms -> viewModel.onScrub(t, ms) }
        stage.onAutoReverse = { viewModel.setTapeAutoReverse(it) }
    }
    stage.touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val currentDeck = rememberUpdatedState(deck)
    val currentSet = rememberUpdatedState(set)
    val currentCovered = rememberUpdatedState(covered)
    val currentAutoReverse = rememberUpdatedState(autoReverse)
    val currentUnseen = rememberUpdatedState(unseen)

    // A tape put in from somewhere else while the racks were out of sight: they are turned to it.
    LaunchedEffect(nowPlayingAlbumId, towers) {
        if (stage.atDeck) rack.turnTo(nowPlayingAlbumId)
    }

    // ---- What is printed -------------------------------------------------------------------------
    val art = remember(engine) { TapeTextures(engine) }
    DisposableEffect(art) { onDispose { art.destroy() } }
    val tape = stage.shownTape
    LaunchedEffect(album?.albumId, album?.coverPath, album?.title, set, tape) {
        val on = album ?: return@LaunchedEffect
        // Only for the album whose tracks the player has: a label printed with somebody else's sides is wrong.
        if (deck.tracks.firstOrNull()?.albumId != on.albumId || set.sides.isEmpty()) return@LaunchedEffect
        val baked = withContext(Dispatchers.Default) { bake(context, on, set, tape) }
        art.write(baked)
        stage.printedAlbum = on.albumId
        stage.printedTape = tape
        stage.changed = true
    }
    val awaiting = stage.awaitingSide
    LaunchedEffect(album?.albumId, set, tape, deck.currentTrackIndex, awaiting) {
        val on = album ?: return@LaunchedEffect
        if (deck.tracks.firstOrNull()?.albumId != on.albumId) return@LaunchedEffect
        val titles = deck.tracks.map { it.title }
        val inside = withContext(Dispatchers.Default) {
            TapeArt.jcardInside(
                context, on.title, on.artist, set.sides, tape, titles,
                playing = if (awaiting >= 0) -1 else deck.currentTrackIndex, awaiting = awaiting, tapes = set.tapes
            )
        }
        art.writeInside(inside)
        stage.changed = true
    }

    // Back is one step out each time: off the tape, down from the deck to the racks, out to the showroom.
    BackHandler(enabled = !covered && !com.jawon.app.ui.common.LocalRoomsCardOpen.current) {
        when {
            stage.zoomTarget > 0.5f -> stage.zoomTarget = 0f
            stage.atDeck -> stage.toRacks()
            else -> onOpenShowroom()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        stage.widthPx = constraints.maxWidth.toFloat()
        stage.heightPx = constraints.maxHeight.toFloat()
        val tall = TapeCamera.rackTall(stage.heightPx / stage.widthPx.coerceAtLeast(1f))
        SideEffect { rack.setTowers(towers, nowPlayingAlbumId, tall) }

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            renderer = renderer,
            isRendering = !covered && !LocalRoomParked.current,
            environment = environment,
            mainLightNode = null,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            onTouchEvent = { event, _ ->
                if (!currentCovered.value) stage.onTouch(event, currentDeck.value)
                true
            },
            onFrame = {
                if (!TapeAtmosphere.isApplied(filamentView)) TapeAtmosphere.apply(filamentView, tier)
                val now = SystemClock.uptimeMillis()
                stage.onFrame(now, currentDeck.value, currentSet.value, currentAutoReverse.value, seen = !currentUnseen.value)
                // The lens, written every frame: SceneView's camera re-aims itself at the origin if
                // it is left alone (`ENGINEERING.md`, trap 8). Level, always.
                val shot = stage.shot
                cameraNode.position = Position(shot.x, shot.y, shot.z)
                cameraNode.rotation = io.github.sceneview.math.Rotation(0f, 0f, 0f)

                // Every vsync while anything moves; thirty while a tape only plays; a few a second at rest.
                // Thirty, not sixty (2026-11-14): at play speed a reel turns under a turn a second and a
                // needle swings in three tenths of one, and drawing them sixty times a second kept the
                // owner's GPU 80% busy and the phone warm for nothing anyone could see.
                val own = when {
                    stage.busy -> cadence.live
                    stage.turning -> cadence.divisorForFps(30f)
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
            // **The lamp**, over your left shoulder: the key, and the one light that casts.
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(TapeLighting.KEY_POSITION[0], TapeLighting.KEY_POSITION[1], TapeLighting.KEY_POSITION[2]),
                direction = Direction(
                    TapeLighting.KEY_AIM[0] - TapeLighting.KEY_POSITION[0],
                    TapeLighting.KEY_AIM[1] - TapeLighting.KEY_POSITION[1],
                    TapeLighting.KEY_AIM[2] - TapeLighting.KEY_POSITION[2]
                ),
                apply = {
                    color(TapeLighting.KEY_COLOUR[0], TapeLighting.KEY_COLOUR[1], TapeLighting.KEY_COLOUR[2])
                    intensityCandela(TapeLighting.KEY_CANDELA)
                    spotLightCone(TapeLighting.KEY_CONE_INNER_RAD, TapeLighting.KEY_CONE_OUTER_RAD)
                    falloff(TapeLighting.KEY_FALLOFF_M)
                    castShadows(tier.shadowsOnFixtures)
                    shadowOptions(
                        LightManager.ShadowOptions().apply {
                            mapSize = tier.shadowMapPx
                            shadowNearHint = TapeLighting.KEY_SHADOW_NEAR_M
                            shadowFarHint = TapeLighting.KEY_SHADOW_FAR_M
                        }
                    )
                }
            )
            // **The racks' lamp**, on a carrier the stage moves along the wall with the camera.
            Node(apply = { stage.racksLamp = this }) {
                LightNode(
                    type = LightManager.Type.SPOT,
                    position = Position(TapeLighting.RACKS_POSITION[0], TapeLighting.RACKS_POSITION[1], TapeLighting.RACKS_POSITION[2]),
                    direction = Direction(
                        TapeLighting.RACKS_AIM[0] - TapeLighting.RACKS_POSITION[0],
                        TapeLighting.RACKS_AIM[1] - TapeLighting.RACKS_POSITION[1],
                        TapeLighting.RACKS_AIM[2] - TapeLighting.RACKS_POSITION[2]
                    ),
                    apply = {
                        color(TapeLighting.RACKS_COLOUR[0], TapeLighting.RACKS_COLOUR[1], TapeLighting.RACKS_COLOUR[2])
                        intensityCandela(TapeLighting.RACKS_CANDELA)
                        spotLightCone(TapeLighting.RACKS_CONE_INNER_RAD, TapeLighting.RACKS_CONE_OUTER_RAD)
                        falloff(TapeLighting.RACKS_FALLOFF_M)
                        castShadows(false)
                    }
                )
            }
            // **The bulb in the well**, over the cassette's label behind the door.
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(TapeLighting.WELL_POSITION[0], TapeLighting.WELL_POSITION[1], TapeLighting.WELL_POSITION[2]),
                apply = {
                    color(TapeLighting.WELL_COLOUR[0], TapeLighting.WELL_COLOUR[1], TapeLighting.WELL_COLOUR[2])
                    intensityCandela(TapeLighting.WELL_CANDELA)
                    falloff(TapeLighting.WELL_FALLOFF_M)
                    castShadows(false)
                }
            )

            roomModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = false; isShadowReceiver = true }) }
            deckModel?.let {
                ModelNode(it, apply = {
                    isTouchable = false
                    isShadowCaster = true
                    isShadowReceiver = true
                    stage.bindDeck(this)
                })
            }

            // **The cassette**: its shell, and the two hubs and two packs that turn and grow in it.
            Node(apply = { stage.cassetteNode = this; isVisible = false }) {
                cassetteModel?.let { model ->
                    ModelNode(model, apply = {
                        isTouchable = false
                        isShadowCaster = false
                        isShadowReceiver = true
                        art.bindCassette(renderableNodes)
                    })
                }
                for ((side, hubModel, packModel) in listOf(
                    Triple(-1f, hubLeftModel, packLeftModel),
                    Triple(1f, hubRightModel, packRightModel)
                )) {
                    val at = Position(side * TapeDimens.HUB_PITCH / 2, TapeDimens.HUB_Z, 0f)
                    Node(position = at, apply = { if (side < 0) stage.hubLeft = this else stage.hubRight = this }) {
                        hubModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = false }) }
                    }
                    Node(position = at, apply = { if (side < 0) stage.packLeft = this else stage.packRight = this }) {
                        packModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = false }) }
                    }
                }
            }

            // **The box**: its tray, and the lid hung from its hinge.
            Node(apply = { stage.caseNode = this; isVisible = false }) {
                trayModel?.let { ModelNode(it, apply = { isTouchable = false; isShadowCaster = true; isShadowReceiver = true }) }
                Node(
                    position = Position(0f, TapeDimens.HINGE_UP, -TapeDimens.HINGE_BACK),
                    apply = { stage.lidNode = this }
                ) {
                    lidModel?.let { model ->
                        ModelNode(model, apply = {
                            isTouchable = false
                            isShadowCaster = true
                            isShadowReceiver = true
                            art.bindJCard(renderableNodes)
                        })
                    }
                }
            }

            // **The racks**, and the boxes in them: every one of a pool, keyed by its place in it.
            rackFrames.forEachIndexed { index, instance ->
                key(index) {
                    ModelNode(instance, apply = {
                        isTouchable = false
                        isShadowCaster = true
                        isShadowReceiver = true
                        rack.adoptRack(index, this)
                    })
                }
            }
            rackBoxes.forEachIndexed { index, instance ->
                key(index) {
                    ModelNode(instance, apply = {
                        isTouchable = false
                        isShadowCaster = false
                        isShadowReceiver = true
                        rack.adoptCase(index, this)
                    })
                }
            }

            // Inside the scene, so it is disposed before the scene is (trap 35).
            DisposableEffect(art) { onDispose { stage.release() } }
        }

        // **The room, as TalkBack meets it.**
        CassetteRoomSemantics(stage, rack, album, deck.tracks.map { it.title }, deck.currentTrackIndex, set, stage.modeShown, onOpenAdd = onAddAlbum)

        if (layout.loaded && towers.isEmpty() && !covered) {
            EmptyNote(
                title = "No tapes yet",
                body = "Add an album and it is here, in a rack on the wall, ready to go in the deck.",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // **The first time at the racks, and the first time at the deck**: what a finger does in each.
        if (!covered && !stage.deckward && Hints.TAPE !in hintsSeen && towers.isNotEmpty()) {
            FirstRunHint(
                title = "These are your tapes",
                body = "Drag up and down a rack, and sideways for the next shelf. Touch a tape to put it in the deck.",
                onGotIt = { viewModel.onHintSeen(Hints.TAPE) },
                onGuide = {
                    viewModel.onHintSeen(Hints.TAPE)
                    onOpenGuide()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 24.dp)
            )
        }
        if (!covered && stage.deckward && album != null && Hints.TAPE_DECK !in hintsSeen) {
            FirstRunHint(
                title = "The tape is in",
                body = "Tap ◀◀ or ▶▶ for the next song, hold them to wind. Touch a song on the J-card " +
                    "to wind to it. With REV's lamp out, touch the tape at the end of a side to turn it over.",
                onGotIt = { viewModel.onHintSeen(Hints.TAPE_DECK) },
                onGuide = {
                    viewModel.onHintSeen(Hints.TAPE_DECK)
                    onOpenGuide()
                },
                // Over the wall at the top, not over the keys it is telling you about.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
            )
        }

        // **A side over, and the tape waiting for a hand** (REV's lamp out): said where it can be seen.
        val waitingFor = stage.awaitingSide
        val tapeOver = stage.over
        AnimatedVisibility(
            visible = stage.deckward && !covered && (waitingFor >= 0 || tapeOver) && set.sides.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 76.dp)
        ) {
            val change = waitingFor >= 0 && set.tapeOf(waitingFor) != set.tapeOf(waitingFor - 1)
            TurnOverPrompt(
                label = when {
                    tapeOver -> "Play it again"
                    change -> "Put in the next tape"
                    else -> "Turn the tape over"
                },
                onClick = { if (tapeOver) stage.transport.tapTape() else stage.transport.turnOverByHand() }
            )
        }

        // **The way back to the racks**, from the deck: on screen, not only Back (2026-11-14).
        AnimatedVisibility(
            // Not under the first-run card, which sits over the same wall.
            visible = stage.deckward && !covered && (Hints.TAPE_DECK in hintsSeen || album == null),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
        ) {
            BackToChip("tapes", onClick = { stage.toRacks() })
        }

        // **The way out**, at the racks: the tape that is in — and the way back to the deck — then
        // the other rooms, adding an album, and Settings.
        AnimatedVisibility(
            visible = !stage.deckward && !covered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // Over the racks there is no floor to stand them on, only spines: a shadow to read them against.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.40f to Color.Black.copy(alpha = 0.86f),
                            1f to Color.Black.copy(alpha = 0.94f)
                        )
                    )
                    .navigationBarsPadding()
                    .padding(top = 64.dp, bottom = 6.dp)
            ) {
                // The tape in the deck — before, it only stood a little proud of its rack, and the
                // owner could not find the way back to it (2026-11-14). A tap is the deck again.
                val playing = nowPlaying
                if (playing != null && album != null && playing.albumId == album.albumId && stage.modeShown != TapeTransport.Mode.EMPTY) {
                    NowPlayingBar(
                        nowPlaying = playing,
                        trackTitle = deck.currentTrack?.title,
                        isPlaying = deck.isPlaying,
                        durationMs = deck.durationMs,
                        position = viewModel.positionMs.collectAsStateWithLifecycle(),
                        haptics = haptics,
                        onOpen = { stage.toDeck() },
                        onPlayPause = { viewModel.onPlayPause() },
                        onNext = { viewModel.onNext() },
                        onPrevious = { viewModel.onPrevious() },
                        // Pulled down: paused, the tape left in the deck — it is still a tape in a deck.
                        onPutAway = { if (deck.isPlaying) viewModel.onPlayPause() },
                        art = { CoverThumb(album.coverPath) }
                    )
                }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                ChromeAction(
                    icon = null,
                    label = "ROOMS",
                    onClick = onOpenRooms,
                    modifier = Modifier.weight(1f),
                    iconContent = { JawonMark(size = 24.dp, color = LabelCream.copy(alpha = 0.82f), contentDescription = null) }
                )
                ChromeAction(Icons.Default.Add, "ADD", onAddAlbum, Modifier.weight(1f))
                ChromeAction(Icons.Default.Settings, "SETTINGS", onOpenSettings, Modifier.weight(1f))
            }
            }
        }
    }
}

/**
 * **The room, as TalkBack meets it**: where you are, which tape is in front or what the deck is
 * doing, and the moves a finger makes, by name. Read here from Compose state, because a
 * description written inside a semantics block is not watched (the vinyl room's lesson).
 */
@Composable
private fun CassetteRoomSemantics(
    stage: TapeStage,
    rack: TapeRackScene,
    album: CaseLayout?,
    titles: List<String>,
    current: Int,
    set: TapeSet,
    mode: TapeTransport.Mode,
    onOpenAdd: () -> Unit
) {
    val atRacks = !stage.deckward
    // Read so that this recomposes when the rack or tape in front changes.
    val shown = rack.frontShown
    val front = if (atRacks && shown.first >= 0) rack.inFront() else null
    val towerName = rack.towers.getOrNull(rack.front)?.name
    val waiting = stage.awaitingSide
    val description = if (atRacks) {
        if (rack.towers.isEmpty()) "Your tape racks. There are no tapes in them yet."
        else "Your tape racks, ${towerName ?: ""}. " + (front?.let { "In front: ${it.title}, by ${it.artist}." } ?: "")
    } else if (album == null || mode == TapeTransport.Mode.EMPTY) {
        "The cassette deck, with no tape in it."
    } else buildString {
        val side = if (set.sides.isEmpty()) 0 else Sides.of(set.sides, current.coerceAtLeast(0))
        append("The cassette deck. ${album.title}, by ${album.artist}. ")
        if (waiting >= 0) {
            append("Side ${Sides.letter(waiting - 1)} has ended. Turn the tape over for side ${Sides.letter(waiting)}.")
        } else {
            append("Side ${Sides.letter(side)}, track ${current + 1}")
            titles.getOrNull(current)?.let { append(", $it") }
            append(
                when (mode) {
                    TapeTransport.Mode.PLAY -> ", playing."
                    TapeTransport.Mode.SEARCH, TapeTransport.Mode.WIND, TapeTransport.Mode.CUE -> ", winding."
                    TapeTransport.Mode.EJECTED -> ", ejected."
                    else -> ", stopped."
                }
            )
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
                customActions = buildList {
                    if (atRacks) {
                        add(CustomAccessibilityAction("Next tape") { rack.step(1); true })
                        add(CustomAccessibilityAction("Previous tape") { rack.step(-1); true })
                        add(CustomAccessibilityAction("Next shelf") { rack.shelf(true); true })
                        add(CustomAccessibilityAction("Previous shelf") { rack.shelf(false); true })
                        if (front != null) add(CustomAccessibilityAction("Put it in") { rack.takeInFront(); true })
                        add(CustomAccessibilityAction("Add albums") { onOpenAdd(); true })
                        if (album != null) add(CustomAccessibilityAction("Go to the deck") { stage.toDeck(); true })
                    } else {
                        if (album != null && mode != TapeTransport.Mode.EMPTY) {
                            add(CustomAccessibilityAction("Play") { stage.pressAndRelease(TapeKey.PLAY); true })
                            add(CustomAccessibilityAction("Stop") { stage.pressAndRelease(TapeKey.STOP); true })
                            add(CustomAccessibilityAction("Pause") { stage.pressAndRelease(TapeKey.PAUSE); true })
                            add(CustomAccessibilityAction("Next song") { stage.pressAndRelease(TapeKey.FF); true })
                            add(CustomAccessibilityAction("Back a song") { stage.pressAndRelease(TapeKey.REW); true })
                            add(CustomAccessibilityAction("Turn the tape over") { stage.transport.turnOverByHand(); true })
                            add(CustomAccessibilityAction("Auto-reverse on or off") { stage.pressAndRelease(TapeKey.REV); true })
                        }
                        add(CustomAccessibilityAction("Back to the racks") { stage.toRacks(); true })
                    }
                }
            }
    )
}

/** What a tape is printed with, baked off the main thread. */
private class TapeBaked(val labelA: Bitmap, val labelB: Bitmap, val front: Bitmap, val spine: Bitmap)

private fun bake(context: android.content.Context, album: CaseLayout, set: TapeSet, tape: Int): TapeBaked {
    val cover = CaseArt.loadCover(album.coverPath, 512)
    val hue = album.storedHue.takeIf { it != 0f } ?: cover?.let { CaseArt.dominantHue(it) } ?: album.fallbackHue
    val stock = set.stock(tape).name
    val first = set.first(tape)
    val second = set.second(tape)
    return TapeBaked(
        labelA = TapeArt.label(context, cover, hue, Sides.letter(first), album.title, album.artist, stock),
        labelB = TapeArt.label(context, cover, hue, if (second >= 0) Sides.letter(second) else Sides.letter(first + 1), album.title, album.artist, stock),
        front = TapeArt.jcardFront(context, cover, hue, album.title, album.artist),
        spine = TapeArt.spine(context, hue, album.title, album.artist)
    )
}

/**
 * The tape's textures: allocated once, written for every tape, and bound to the models' own
 * materials by the names the model script gave them (`docs/models.md`).
 */
private class TapeTextures(private val engine: Engine) {

    private fun colour(w: Int, h: Int): Texture = Texture.Builder()
        .width(w).height(h)
        .levels(32 - Integer.numberOfLeadingZeros(maxOf(w, h)))
        .format(Texture.InternalFormat.SRGB8_A8)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
        .build(engine)

    val labelA = colour(TapeArt.LABEL_W, TapeArt.LABEL_H)
    val labelB = colour(TapeArt.LABEL_W, TapeArt.LABEL_H)
    val front = colour(TapeArt.FRONT_W, TapeArt.FRONT_H)
    val inside = colour(TapeArt.INSIDE_W, TapeArt.INSIDE_H)
    val spine = colour(TapeArt.SPINE_W, TapeArt.SPINE_H)

    private val sampler by lazy {
        TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            .apply { anisotropy = 8f }
    }
    private var alive = true

    fun write(baked: TapeBaked) {
        if (!alive) return
        put(labelA, baked.labelA)
        put(labelB, baked.labelB)
        put(front, baked.front)
        put(spine, baked.spine)
    }

    fun writeInside(bitmap: Bitmap) {
        if (alive) put(inside, bitmap)
    }

    private fun put(texture: Texture, bitmap: Bitmap) {
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        texture.generateMipmaps(engine)
    }

    fun bindCassette(parts: List<Node>) {
        for (part in parts) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            when (part.name) {
                "label_a" -> material.setParameter("baseColorMap", labelA, sampler)
                "label_b" -> material.setParameter("baseColorMap", labelB, sampler)
            }
        }
    }

    fun bindJCard(parts: List<Node>) {
        for (part in parts) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            when (part.name) {
                "jcard_front" -> material.setParameter("baseColorMap", front, sampler)
                "jcard_inside" -> material.setParameter("baseColorMap", inside, sampler)
                "jcard_spine" -> material.setParameter("baseColorMap", spine, sampler)
            }
        }
    }

    fun destroy() {
        if (!alive) return
        alive = false
        for (t in listOf(labelA, labelB, front, inside, spine)) runCatching { engine.destroyTexture(t) }
    }
}
