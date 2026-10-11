package com.jawon.app.ui.vinylscene

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import kotlin.math.hypot
import kotlin.math.ln

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

/** The arm, lifted clear of a record: its tip a centimetre up. */
private const val ARM_LIFT_DEG = 3.2f

/** How quickly the arm and the zoom close on where they are going: the fraction left after a second is e^-this. */
private const val ARM_EASE = 9f
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
 * This is the half of it that plays: a deck from straight above, the record that is on, its
 * sleeve laid out in front with its sides printed on the back. The crate it came out of is next.
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

    val stage = remember { VinylStage(viewModel.scratchAudio, haptics) }
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
        stage.changed = true
    }
    // The back of the sleeve is printed again whenever the line that is lit moves.
    LaunchedEffect(album?.albumId, sides, deck.currentTrackIndex, cover) {
        val playing = album
        val titles = deck.tracks.map { it.title }
        val printed = cover
        val back = withContext(Dispatchers.Default) {
            VinylArt.sleeveBack(context, printed, playing?.title.orEmpty(), playing?.artist.orEmpty(), sides, titles, deck.currentTrackIndex)
        }
        art.writeBack(back)
        stage.changed = true
    }

    BackHandler(enabled = !covered) {
        if (stage.zoomTarget > 0.5f) stage.zoomTarget = 0f else onOpenShowroom()
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
                        onSelectTrack = viewModel::onSelectTrack,
                        onPlayPause = { viewModel.onPlayPause() },
                        onWalk = viewModel::onScrub,
                        // Until there is a crate to take one from: the first record on the shelves.
                        onEmptyDeck = { currentLayout.value.casesInWallOrder.firstOrNull()?.let { viewModel.playAlbum(it.albumId) } }
                    )
                }
                true
            },
            onFrame = {
                if (!VinylAtmosphere.isApplied(filamentView)) VinylAtmosphere.apply(filamentView, tier)
                val now = SystemClock.uptimeMillis()
                stage.onFrame(now, currentDeck.value, currentSides.value, currentPosition.value)

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
                    // Turned over about its front-to-back axis for a B side, and lifted by its
                    // own thickness so that it still lies on the mat.
                    Node(apply = { stage.recordFlip = this }) {
                        ModelNode(
                            model,
                            apply = {
                                isTouchable = false
                                isShadowCaster = false
                                isShadowReceiver = true
                                art.bindRecord(renderableNodes, vinylA, vinylB)
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

            // Declared inside the scene so that it is disposed before the scene is (trap 35):
            // the materials stop sampling these before the textures go.
            DisposableEffect(art) { onDispose { stage.release() } }
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
private class VinylStage(private val scratch: ScratchAudio, private val haptics: RoomHaptics) {

    var platterNode: Node? = null
    var recordNode: Node? = null
    var recordFlip: Node? = null
    var armNode: Node? = null
    var armTilt: Node? = null
    var sleeveFrontNode: Node? = null
    var sleeveBackNode: Node? = null

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
    private var armYaw = VinylArm.yawDegrees(VinylDimens.REST_RADIUS)
    private var armLift = ARM_LIFT_DEG
    private var lastFrameAt = 0L
    private var hasRecord = false

    /** A hand is on it, or the zoom or the arm is still on its way. */
    var busy = false
        private set

    /** The record is going round. */
    var turning = false
        private set

    fun onFrame(now: Long, deck: DeckState, sides: List<Side>, positionMs: Long) {
        val dt = if (lastFrameAt == 0L) 0f else ((now - lastFrameAt) / 1000f).coerceAtMost(0.1f)
        lastFrameAt = now
        hasRecord = deck.tracks.isNotEmpty() && sides.isNotEmpty()
        val aspect = heightPx / widthPx

        // While the record can be touched, the scratch keeps the audio round the playhead in hand.
        scratch.arm(hasRecord)

        // ---- the platter, and the record on it -------------------------------------------------
        val playing = deck.isPlaying
        if (playing || scratch.holding || scratch.turning) platterAngle -= PLATTER_DEG_PER_S * dt
        when {
            // In the hand: the record is wherever the finger has turned it, and that is written
            // by the touch itself.
            held -> Unit
            // Let go, and the engine is winding it back up, or spinning it back.
            scratch.turning -> recordAngle -= PLATTER_DEG_PER_S * dt * scratch.rate
            playing -> recordAngle -= PLATTER_DEG_PER_S * dt
        }
        platterAngle %= 360f
        recordAngle %= 360f
        platterNode?.rotation = Rotation(y = platterAngle)
        recordNode?.rotation = Rotation(y = recordAngle)
        recordNode?.isVisible = hasRecord
        sleeveBackNode?.isVisible = hasRecord
        sleeveFrontNode?.isVisible = hasRecord

        // ---- which side is up, and where on it the music is ------------------------------------------
        val track = deck.currentTrackIndex.coerceAtLeast(0)
        val sideIndex = if (hasRecord) Sides.of(sides, track) else 0
        val flipped = sideIndex % 2 == 1
        recordFlip?.rotation = Rotation(z = if (flipped) 180f else 0f)
        recordFlip?.position = Position(0f, if (flipped) VinylDimens.LP_H else 0f, 0f)

        val side = sides.getOrNull(sideIndex)
        val place = scratch.positionMs() ?: positionMs
        val radius = if (side != null) side.radiusAt(side.fraction(track, place)) else VinylDimens.REST_RADIUS
        val wantedYaw = VinylArm.yawDegrees(radius)
        val wantedLift = if (side != null) 0f else ARM_LIFT_DEG
        val armStep = 1f - exp(-ARM_EASE * dt)
        armYaw += (wantedYaw - armYaw) * armStep
        armLift += (wantedLift - armLift) * armStep
        armNode?.rotation = Rotation(y = armYaw)
        armTilt?.rotation = Rotation(x = -armLift)

        // ---- the lens ------------------------------------------------------------------------------------
        zoom += (zoomTarget - zoom) * (1f - exp(-ZOOM_EASE * dt))
        if (abs(zoomTarget - zoom) < 0.0005f) zoom = zoomTarget
        shot = VinylCamera.play(zoom, aspect)

        turning = hasRecord && (playing || scratch.turning)
        busy = held || pinching || changed || zoom != zoomTarget ||
            abs(wantedYaw - armYaw) > 0.02f || abs(wantedLift - armLift) > 0.02f
        changed = false
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
        onSelectTrack: (Int) -> Unit,
        onPlayPause: () -> Unit,
        onWalk: (Int, Long) -> Unit,
        onEmptyDeck: () -> Unit
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                downX = event.x
                downY = event.y
                moved = false
                if (hasRecord && onRecord(event.x, event.y)) grab(event, deck, positionMs)
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
                    if (hasRecord) tap(event, deck, sides, wasHeld, onSelectTrack, onPlayPause) else onEmptyDeck()
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
        onSelectTrack: (Int) -> Unit,
        onPlayPause: () -> Unit
    ) {
        // A touch on the record is a hand on the record — it has already slowed it — and is
        // never also a tap.
        if (onRecordItself) return
        // The start · stop button, on the top plate.
        VinylCamera.onPlane(shot, event.x, event.y, widthPx, heightPx, VinylDimens.TOP_Y, spot)
        if (abs(spot[0] - START_X) <= START_HALF_W && abs(spot[1] - START_Z) <= START_HALF_D) {
            haptics.detent()
            onPlayPause()
            return
        }
        // A line on the back of the sleeve.
        if (hasRecord) {
            VinylCamera.onPlane(shot, event.x, event.y, widthPx, heightPx, VinylDimens.SLEEVE_T * 2, spot)
            val u = (spot[0] - SLEEVE_BACK_X) / VinylDimens.SLEEVE + 0.5f
            val v = (spot[1] - (SLEEVE_FRONT_Z - VinylDimens.SLEEVE)) / VinylDimens.SLEEVE
            val track = SleeveBackLayout.of(sides).trackAt(u, v)
            if (track >= 0) {
                if (track != deck.currentTrackIndex) {
                    haptics.detent()
                    onSelectTrack(track)
                }
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

    /** The room is going: a record in the hand is put back, and nothing is left decoding. */
    fun release() {
        if (held) scratch.cancel()
        held = false
        scratch.arm(false)
    }

    private companion object {
        const val PINCH_FOR_WHOLE_ZOOM = 0.85f
    }
}
