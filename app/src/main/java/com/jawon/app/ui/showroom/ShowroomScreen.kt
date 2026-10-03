package com.jawon.app.ui.showroom

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.utils.TextureType
import com.jawon.app.BuildConfig
import com.jawon.app.ui.pocketscene.LCD_PX_H
import com.jawon.app.ui.pocketscene.LCD_PX_W
import com.jawon.app.ui.pocketscene.LcdPanel
import com.jawon.app.ui.pocketscene.POCKET_MODEL_ASSET
import com.jawon.app.ui.pocketscene.PocketFinish
import com.jawon.app.ui.pocketscene.PocketLcd
import com.jawon.app.ui.pocketscene.SCREEN_CENTRE_Y
import com.jawon.app.ui.pocketscene.SCREEN_W_M
import com.jawon.app.ui.pocketscene.SCREEN_Z
import com.jawon.app.ui.pocketscene.mainMenuAtRest
import com.jawon.app.ui.pocketscene.POLISHED_STEEL_MATERIAL_ASSET
import com.jawon.app.ui.pocketscene.STEEL_BASE_COLOUR
import com.jawon.app.ui.pocketscene.STEEL_FOOTPRINT
import com.jawon.app.ui.pocketscene.STEEL_ROUGHNESS
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.RoomCadence
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.TRAY_FRONT_Z
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.RenderableNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** The gallery: its floor and its row of plinths (`build_showroom()`). */
private const val SHOWROOM_ASSET = "models/showroom.glb"

/** The jewel case on the CD's plinth: the held case's own two halves, shut. */
private const val CASE_TRAY_ASSET = "models/case_tray.glb"
private const val CASE_LID_ASSET = "models/case_lid.glb"

/**
 * The lens. Shorter than the pocket room's 50mm: this is a room you stand in and look along, not
 * an object held at arm's length.
 */
private const val FOCAL_LENGTH_MM = 40f

/**
 * **A spot over every plinth**, on a track in front of the row, aimed back and down at what floats
 * there — from the front, because a light straight above a vertical face contributes nothing to it,
 * and the faces are the point; and from the right, so the shadow each exhibit throws onto its cap
 * falls back and to the left of it. Straight in front, the shadow landed directly under the object
 * as the camera sees it and read as a black stand it was sitting on, not the shadow of something
 * floating. The plinths' only light, and the room's only shadow casters.
 */
private const val SPOT_X_M = 0.35f
private const val SPOT_Y_M = 2.60f
private const val SPOT_Z_M = 0.55f
private const val SPOT_AIM_Y_M = 1.02f
private const val SPOT_CANDELA = 120_000f
private const val SPOT_CONE_INNER_RAD = 0.09f
private const val SPOT_CONE_OUTER_RAD = 0.16f

/**
 * **A wash on the wall behind each plinth**: a wide, soft spot from the ceiling behind the row, laying
 * a glow on the plaster that the exhibit stands out against. From behind and above, it also draws a
 * thin rim round the exhibit's top edges — the kicker every product photograph has. No shadows.
 */
private const val WASH_Y_M = 2.9f
private const val WASH_Z_M = -0.6f
private const val WASH_AIM_Y_M = 1.15f
private const val WASH_AIM_Z_M = -2.2f
private const val WASH_CANDELA = 22_000f
private const val WASH_CONE_INNER_RAD = 0.25f
private const val WASH_CONE_OUTER_RAD = 0.60f

/** Enough for an exhibit's edge to be an edge on its cap. */
private const val SHADOW_MAP_PX = 1024

/** A plinth still waiting for its format: the same spot, turned most of the way down. */
private const val COMING_CANDELA = 14_000f

/** The environment standing in while the real one is worked out: a few texels a face. */
private const val STAND_IN_PX = 8

/** How finely the case's cover is kept: the case fills the frame as you arrive. */
private const val COVER_PX = 1024

/** The same cover on the pocket player's screen, which is 640 pixels across. */
private const val SCREEN_COVER_PX = 256

/**
 * The printed card behind the case's clear front: matte paper, and the gloss left to the lid in
 * front of it. At `ActiveCase`'s 0.16 the card reflected the gallery a second time under the lid's
 * own reflection, and the cover read through a grey veil.
 */
private const val COVER_ROUGHNESS = 0.42f
private const val PAPER_REFLECTANCE = 0.5f

/** The lid's plastic in the showroom: its own tint, at a fraction of the CD room's 22% alpha. */
private val LID_TINT = floatArrayOf(0.86f, 0.90f, 0.93f)
private const val LID_ALPHA = 0.05f

/** The inlay's place inside the lid, in the hinge's frame — as `ActiveCaseNodes` hangs it. */
private const val INLAY_Z_M = 0.0026f

/** The rate the room is drawn at when nothing in it is moving. */
private const val SHOWROOM_IDLE_FPS = 60f

/** A full swipe across the screen carries the row this much further than one plinth. */
private const val SWIPE_PER_PLINTH = 0.85f

/** How far apart the captions sit, as a fraction of the screen's width. */
private const val CAPTION_SPACING = 0.62f

/**
 * **The showroom: the way between formats** (`docs/formats.md` §7, step 5b).
 *
 * A dark gallery with a row of plinths, each under its own spot, and on each a format's object
 * floating and slowly turning — the jewel case holding the record you played last, the pocket
 * player, and for the formats still to come an empty plinth under a dim light. Reached by pinching
 * out of a room: the camera arrives close on that room's object and eases back. A drag carries it
 * along the row, a tap on a room's plinth flies into it, and back returns to where you were.
 *
 * A destination of its own, and a scene of its own — a sibling of the rooms, sharing only what it
 * calls: `RoomHaptics`, `RoomCadence`, `CaseArt`'s decoder, the pocket player's steel.
 */
@Composable
fun ShowroomScreen(
    /** Leave the showroom for the room the app is now in — which [ShowroomViewModel.choose] set. */
    onLeave: () -> Unit,
    viewModel: ShowroomViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val filamentView = rememberView(engine)
    val renderer = rememberRenderer(engine)

    // **The gallery's light, worked out off the main thread**: at full size it is a quarter of a
    // second of the frame this screen is composed on, which is time the room being left has to sit
    // in black for. A cubemap a few texels across stands in, under the black, until it is ready —
    // SceneView applies whatever environment it is handed at each composition.
    var environment by remember(engine, environmentLoader) {
        mutableStateOf(
            ShowroomLighting.createEnvironment(engine, ShowroomLighting.faces(STAND_IN_PX), STAND_IN_PX) { cubemap ->
                environmentLoader.iblPrefilter.specularFilter(cubemap)
            }
        )
    }
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(engine, environmentLoader) {
        val faces = withContext(Dispatchers.Default) { ShowroomLighting.faces() }
        environment = ShowroomLighting.createEnvironment(engine, faces) { cubemap ->
            environmentLoader.iblPrefilter.specularFilter(cubemap)
        }
        lit = true
    }
    val cameraNode = rememberCameraNode(engine) {
        focalLength = FOCAL_LENGTH_MM.toDouble()
    }

    val format by viewModel.format.collectAsStateWithLifecycle()
    val pocketFinish by viewModel.pocketFinish.collectAsStateWithLifecycle()
    // The room you came from: where you arrive, and where back goes. Read once — choosing another
    // room changes the preference before this screen has gone.
    val from = remember { Exhibit.of(viewModel.format.value).ordinal }
    val showUnbuilt = BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS
    val haptics = remember(context) { RoomHaptics(context) }
    val director = remember { ShowroomDirector(haptics, from, showUnbuilt) }
    DisposableEffect(director) { onDispose { director.close() } }

    // **Leaving**: the push into a room ends in black, and only then does the app change rooms —
    // and it waits to see the change arrive before going back, so the room it goes back to is
    // composed once, as the new one, never for a frame as the old.
    var leavingFor by remember { mutableStateOf<Exhibit?>(null) }
    director.onDeparted = { leavingFor = it }
    LaunchedEffect(leavingFor) {
        val chosen = leavingFor?.format ?: return@LaunchedEffect
        if (chosen != viewModel.format.value) {
            viewModel.choose(chosen)
            snapshotFlow { format }.first { it == chosen }
            withFrameNanos { }
        }
        onLeave()
    }
    BackHandler { if (!director.back()) onLeave() }

    val cadence = remember(context) {
        RoomCadence().apply {
            refreshHz = context.display?.refreshRate ?: RoomCadence.DEFAULT_REFRESH_HZ
        }
    }
    val publishedDivisor = remember { intArrayOf(0) }
    val lastFrameAt = remember { longArrayOf(0L) }

    val showroom = rememberModelInstance(modelLoader, SHOWROOM_ASSET)
    val tray = rememberModelInstance(modelLoader, CASE_TRAY_ASSET)
    val lid = rememberModelInstance(modelLoader, CASE_LID_ASSET)
    val pocket = rememberModelInstance(modelLoader, POCKET_MODEL_ASSET)
    val steel = remember(materialLoader) {
        materialLoader.createInstance(materialLoader.createMaterial(POLISHED_STEEL_MATERIAL_ASSET)).apply {
            setParameter("baseColor", STEEL_BASE_COLOUR[0], STEEL_BASE_COLOUR[1], STEEL_BASE_COLOUR[2])
            setParameter("roughness", STEEL_ROUGHNESS)
            setParameter("footprint", STEEL_FOOTPRINT)
        }
    }

    // The case's cover: the record played last, decoded off the main thread. Its texture outlives
    // the plane that samples it — this effect is declared before the scene, so it is disposed after.
    var cover by remember { mutableStateOf<Texture?>(null) }
    // The same record on the pocket player's screen, beside its main menu.
    var screenCover by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(engine) {
        val path = viewModel.caseCoverPath() ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            CaseArt.loadCover(path, maxPx = COVER_PX)?.let { square(it, COVER_PX) }
        } ?: return@LaunchedEffect
        cover = ImageTexture.Builder().bitmap(bitmap, TextureType.COLOR).build(engine)
        screenCover = withContext(Dispatchers.Default) {
            Bitmap.createScaledBitmap(bitmap, SCREEN_COVER_PX, SCREEN_COVER_PX, true).asImageBitmap()
        }
    }

    // **The pocket player's screen, switched on**: its main menu, drawn by the pocket room's own
    // LCD — `LcdPanel` and `PocketLcd` — so it is the device's real screen, not a picture of one.
    // Drawn once and again only if the cover arrives: nothing on it moves.
    val viewNodeManager = rememberViewNodeManager()
    val lcdPanel = remember(engine) { LcdPanel(context, engine, LCD_PX_W, LCD_PX_H) }
    DisposableEffect(lcdPanel, viewNodeManager) {
        viewNodeManager.addView(lcdPanel.host)
        onDispose { viewNodeManager.removeView(lcdPanel.host) }
    }
    val lcdScreen = remember { mutableStateOf(mainMenuAtRest(null)) }
    LaunchedEffect(screenCover) { lcdScreen.value = mainMenuAtRest(screenCover) }
    remember(lcdPanel) {
        lcdPanel.composeView.setContent {
            PocketLcd(screen = lcdScreen.value, playing = false, awake = true)
        }
    }
    DisposableEffect(cover) {
        val texture = cover
        onDispose { texture?.let { engine.destroyTexture(it) } }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        director.pxPerPlinth = widthPx * SWIPE_PER_PLINTH
        director.touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        director.cameraNode = cameraNode

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            renderer = renderer,
            environment = environment,
            // The gallery brings its own light: a spot per plinth, and the room as reflections.
            mainLightNode = null,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            viewNodeWindowManager = viewNodeManager,
            onTouchEvent = { event, _ -> director.onTouch(event) },
            onFrame = {
                if (!ShowroomAtmosphere.isApplied(filamentView)) ShowroomAtmosphere.apply(filamentView)
                val now = SystemClock.uptimeMillis()
                val dt = if (lastFrameAt[0] == 0L) 0f else (now - lastFrameAt[0]) / 1000f
                lastFrameAt[0] = now
                // Shown once the room you came from's object is in, the gallery round it and its light.
                director.loaded = lit && showroom != null && director.exhibitNodes[from] != null
                director.onFrame(dt)
                // Drawn at 60 while it stands still, every vsync while anything moves — the pocket
                // room's rule, and for the same reason: the clocks advance every vsync regardless.
                val divisor = if (director.isMoving) RoomCadence.LIVE else cadence.divisorForFps(SHOWROOM_IDLE_FPS)
                if (divisor != publishedDivisor[0]) {
                    publishedDivisor[0] = divisor
                    renderer.frameRateOptions = Renderer.FrameRateOptions().apply {
                        interval = divisor.toFloat()
                    }
                }
                if (!cadence.shouldDraw(divisor)) renderer.skipNextFrames(1)
            }
        ) {
            showroom?.let { gallery ->
                ModelNode(
                    gallery,
                    apply = {
                        isTouchable = false
                        isShadowCaster = true
                        isShadowReceiver = true
                    }
                )
            }

            Exhibit.entries.forEachIndexed { i, exhibit ->
                val x = ShowroomStage.plinthX(i.toFloat())
                val coming = exhibit.format == null
                LightNode(
                    type = LightManager.Type.SPOT,
                    position = Position(x + SPOT_X_M, SPOT_Y_M, SPOT_Z_M),
                    direction = Direction(-SPOT_X_M, SPOT_AIM_Y_M - SPOT_Y_M, -SPOT_Z_M),
                    apply = {
                        // Warm for what is here; a little warmer and far dimmer for what is not yet.
                        if (coming) color(1.0f, 0.80f, 0.60f) else color(1.0f, 0.93f, 0.84f)
                        intensityCandela(if (coming) COMING_CANDELA else SPOT_CANDELA)
                        spotLightCone(SPOT_CONE_INNER_RAD, SPOT_CONE_OUTER_RAD)
                        falloff(6f)
                        castShadows(true)
                        // Sized for this: an exhibit a metre and a half from its spot, nothing
                        // nearer than a metre, nothing further than the floor behind the plinth.
                        shadowOptions(
                            LightManager.ShadowOptions().apply {
                                mapSize = SHADOW_MAP_PX
                                shadowNearHint = 1.0f
                                shadowFarHint = 4.0f
                            }
                        )
                    }
                )
            }

            Exhibit.entries.forEachIndexed { i, exhibit ->
                val x = ShowroomStage.plinthX(i.toFloat())
                LightNode(
                    type = LightManager.Type.SPOT,
                    position = Position(x, WASH_Y_M, WASH_Z_M),
                    direction = Direction(0f, WASH_AIM_Y_M - WASH_Y_M, WASH_AIM_Z_M - WASH_Z_M),
                    apply = {
                        color(1.0f, 0.86f, 0.70f)
                        intensityCandela(if (exhibit.format == null) WASH_CANDELA * 0.35f else WASH_CANDELA)
                        spotLightCone(WASH_CONE_INNER_RAD, WASH_CONE_OUTER_RAD)
                        falloff(6f)
                        castShadows(false)
                    }
                )
            }

            // **The CD**: the held case's tray and lid, shut, with the record you played last
            // behind the clear front — assembled as `ActiveCaseNodes` assembles it.
            if (tray != null && lid != null) {
                Node(apply = { director.exhibitNodes[Exhibit.CD.ordinal] = this }) {
                    ModelNode(
                        tray,
                        apply = {
                            isTouchable = false
                            isShadowCaster = true
                            isShadowReceiver = true
                        }
                    )
                    Node(position = Position(-CASE_D / 2f, 0f, TRAY_FRONT_Z)) {
                        cover?.let { texture ->
                            val paper = remember(texture) {
                                materialLoader.createTextureInstance(
                                    texture,
                                    metallic = 0f,
                                    roughness = COVER_ROUGHNESS,
                                    reflectance = PAPER_REFLECTANCE
                                )
                            }
                            PlaneNode(
                                size = Size(CASE_D, CASE_H, 0f),
                                normal = Direction(z = 1f),
                                materialInstance = paper,
                                position = Position(CASE_D / 2f, 0f, INLAY_Z_M),
                                apply = {
                                    isTouchable = false
                                    isShadowCaster = false
                                    isShadowReceiver = true
                                }
                            )
                        }
                        ModelNode(
                            lid,
                            apply = {
                                isTouchable = false
                                isShadowCaster = true
                                isShadowReceiver = false
                                // **Clear, not milky, under a spot this strong.** The lid is authored
                                // for the CD room at 22% alpha over a pale base; lit by a gallery
                                // spot, that base became a lit white layer over the cover. Clear
                                // plastic has no colour of its own — blended as `transparent`, its
                                // reflections are kept whatever the alpha, so only the veil goes.
                                for (part in renderableNodes) {
                                    part.materialInstance.setParameter(
                                        "baseColorFactor", LID_TINT[0], LID_TINT[1], LID_TINT[2], LID_ALPHA
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // **The pocket player**, its screen dark, its steel back the room's own material.
            pocket?.let { device ->
                Node(apply = { director.exhibitNodes[Exhibit.POCKET.ordinal] = this }) {
                    ModelNode(
                        device,
                        apply = {
                            isTouchable = false
                            isShadowCaster = true
                            isShadowReceiver = true
                            (renderableNodes.firstOrNull { it.name == "pocket_back" } as? RenderableNode)
                                ?.materialInstance = steel
                            // In the colour its own Settings chose.
                            fun material(name: String) =
                                (renderableNodes.firstOrNull { it.name == name } as? RenderableNode)?.materialInstance
                            PocketFinish.fromName(pocketFinish).applyTo(
                                material("pocket_front"), material("pocket_wheel"),
                                material("pocket_button"), material("pocket_print")
                            )
                        }
                    )
                    // The screen, where the pocket room hangs it: the panel's release declared before
                    // the plane, so the plane and the material sampling the texture go first.
                    DisposableEffect(lcdPanel) { onDispose { lcdPanel.release(engine) } }
                    val screenMaterial = remember(lcdPanel) {
                        materialLoader.createViewInstance(lcdPanel.texture, true, false)
                    }
                    PlaneNode(
                        size = Size(SCREEN_W_M, SCREEN_W_M * LCD_PX_H / LCD_PX_W, 0f),
                        normal = Direction(z = 1f),
                        materialInstance = screenMaterial,
                        position = Position(0f, SCREEN_CENTRE_Y, SCREEN_Z),
                        apply = {
                            isTouchable = false
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )
                }
            }
        }

        // **The captions**: each format's name under its plinth, in the app's own type, sliding
        // with the row — drawn from the row's position in the draw phase, so nothing recomposes.
        Exhibit.entries.forEachIndexed { i, exhibit ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 64.dp)
                    .graphicsLayer {
                        val d = i - director.position.floatValue
                        translationX = d * widthPx * CAPTION_SPACING
                        alpha = (1f - abs(d) * 0.8f).coerceIn(0f, 1f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = exhibit.title.uppercase(Locale.ROOT),
                    style = TextStyle(
                        fontFamily = ArchivoFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        letterSpacing = 2.sp,
                        color = if (exhibit.format == null) LabelDim else LabelCream
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = exhibit.line.uppercase(Locale.ROOT),
                    style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
                )
                // The room you are in, marked the way Settings marks a choice: in the lamp's amber.
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .size(width = 18.dp, height = 2.dp)
                        .background(if (exhibit.format == format) LampAmber else Color.Transparent)
                )
            }
        }

        // The black the showroom is revealed from, and the black a departure ends in. Only there
        // while it shows: a screen-wide layer over the scene costs a blend every frame whether it
        // shows or not (trap 14).
        if (director.darkShown.value) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = director.dark.floatValue }
                    .background(Color.Black)
            )
        }
    }
}

/** A square of [px], centre-cropped: every cover is square on a case, whatever it was scanned at. */
private fun square(source: Bitmap, px: Int): Bitmap {
    val side = minOf(source.width, source.height)
    val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    return if (side == px) cropped else Bitmap.createScaledBitmap(cropped, px, px, true)
}
