package com.jawon.app.ui.wallscene

import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.JavonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.wallscene.WallDimens.BOARD_D
import com.jawon.app.ui.wallscene.WallDimens.BOARD_H
import com.jawon.app.ui.wallscene.WallDimens.CAMERA_Z
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.FOCAL_LENGTH_MM
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.PROUD_Z
import com.jawon.app.ui.wallscene.WallDimens.PLATE_W
import com.jawon.app.ui.wallscene.WallDimens.ROW_PITCH
import com.jawon.app.ui.wallscene.WallDimens.SLOT_PITCH
import com.jawon.app.ui.wallscene.WallDimens.TAN_HALF_FOV
import com.jawon.app.ui.wallscene.WallDimens.WALL_Z
import com.google.android.filament.LightManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.node.Node as SceneNode
import io.github.sceneview.safeDestroyGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import androidx.compose.ui.graphics.Color as ComposeColor

/** Fling coast. The prototype's friction of 28 is a ~36ms time constant â€” barely a coast; */
/** this is looser so a flick actually travels. Tuning knob. */
private const val FLING_FRICTION = 2f

/** Progress at which the deck fades in â€” as the disc squares up, not before. */
private const val DECK_REVEAL_AT = 0.75f

/** Label plate raster size. World size is these divided by [PLATE_PX_PER_UNIT]. */
private const val PLATE_PX_W = 400
private const val PLATE_PX_H = 76
private const val PLATE_PX_PER_UNIT = PLATE_PX_W / PLATE_W

/**
 * Mutable state the per-frame driver owns. Deliberately not Compose state: the open
 * sequence advances every frame and must not recompose anything (ENGINEERING.md rule 3).
 */
private class SequenceDriver {
    var progress = 0f
    var direction = 0
    var spinAngle = 0f
    var lastFrameNanos = 0L
    var clock = 0f

    /**
     * Whether depth of field is currently switched on. Lives here rather than in Compose
     * state precisely because the frame loop owns it â€” a `mutableStateOf` would recompose
     * the screen every time a case opened or closed.
     */
    var dofEnabled = false

    /**
     * The camera's focal plane, in metres.
     *
     * Held here and written to the camera every frame rather than eased from
     * `cameraNode.focusDistance`. Reading it back made the easing depend on the getter
     * returning what the setter stored; when it did not, the value never converged, the
     * focal plane sat a few centimetres from the lens, and the disc â€” the one thing that
     * must be sharp â€” was blurred along with everything else.
     */
    var focusDistance = RoomAtmosphere.FOCUS_BROWSING
}

/**
 * What the frame driver needs to read from composition. Held in a mutable box and refreshed
 * from a SideEffect so a frame callback captured once still sees current values.
 */
private class FrameContext {
    var selected: CaseLayout? = null
    var isPlaying = false
    var aspect = 1f
}

/**
 * A scratch in progress. Deliberately not Compose state, for the same reason
 * [SequenceDriver] is not: touch arrives faster than the display refreshes and none of it
 * may recompose anything (ENGINEERING.md rule 3). The only Compose writes a scratch makes are the
 * readout's, at the throttled seek rate.
 */
private class ScratchDriver {
    var active = false
    var lastAngle = 0f
    var cursor = Scratch.Cursor(0, 0L)
    var durations: List<Long> = emptyList()
    var lastSeekAt = 0L
    val haptics = Scratch.HapticAccumulator()
}

/** Mip levels for a [DiscArt.DISC_PX]-square texture: 512, 256, â€¦ 1. */
private const val DISC_MIP_LEVELS = 10

/** Built from `app/src/main/materials/dust.mat`; see `docs/materials.md`. */
private const val DUST_MATERIAL_ASSET = "materials/dust.filamat"

/**
 * The case and disc geometry, generated by `tools/models/build_models.py`.
 *
 * Regenerate with:
 *
 *     blender --background --factory-startup --python tools/models/build_models.py
 *
 * Both the script and its output are committed, for the same reason `dust.filamat` is:
 * nothing in the Gradle build can produce them, and a clone should not need Blender to
 * build the app. Edit the script, never the `.glb`.
 */
private const val TRAY_MODEL_ASSET = "models/case_tray.glb"
private const val LID_MODEL_ASSET = "models/case_lid.glb"
private const val DISC_MODEL_ASSET = "models/disc.glb"

/**
 * Master dust brightness. Tuning knob â€” dust should suggest air, not snow. At 0.55 the
 * motes read as distinct white specks; this is deliberately below the point where you
 * notice them individually.
 */
private const val DUST_OPACITY = 0.32f


/**
 * Does this node, or any of its parents, belong to the disc?
 *
 * Aliased to `SceneNode` because inside `SceneScope` the bare name `Node` is the composable
 * that builds one.
 */
private fun SceneNode?.isDisc(): Boolean =
    generateSequence(this) { it.parent }.any { it.name == DISC_NODE_NAME }

@Composable
fun WallSceneScreen(
    onAddAlbum: () -> Unit,
    onOpenShelfManager: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WallSceneViewModel = hiltViewModel()
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val nowPlayingAlbumId by viewModel.nowPlayingAlbumId.collectAsStateWithLifecycle()
    val isPlaying = deck.isPlaying
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val aspect = constraints.maxWidth.toFloat() / viewportHeightPx

        val halfH = CAMERA_Z * TAN_HALF_FOV
        val halfW = halfH * aspect
        val metresPerPx = (2f * halfH) / viewportHeightPx

        val panX = remember { Animatable(0f) }
        val panY = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(layout) {
            panX.updateBounds(layout.minPanX, layout.maxPanX)
            panY.updateBounds(layout.minPanY, layout.maxPanY)
        }

        val engine = rememberEngine()
        val materialLoader = rememberMaterialLoader(engine)
        val environmentLoader = rememberEnvironmentLoader(engine)
        val viewNodeManager = rememberViewNodeManager()

        // The room's image-based lighting, generated rather than loaded â€” see RoomLighting.
        // Filament's own prefilter turns the procedural cubemap into a roughness mip chain.
        val environment = remember(engine, environmentLoader) {
            RoomLighting.createEnvironment(engine) { cubemap ->
                environmentLoader.iblPrefilter.specularFilter(cubemap)
            }
        }

        // Fog, bloom, depth of field and vignette all live on Filament's View. Named to
        // keep it apart from the Android View that LocalView supplies for haptics.
        val filamentView = rememberView(engine)
        LaunchedEffect(filamentView) { RoomAtmosphere.apply(filamentView) }

        // DESIGN.md Â§4: "Respect reduced-motion settings: drop ambient rotation, parallax,
        // and dust; keep press feedback and the open sequence." Android reports this as an
        // animator duration scale of zero.
        val reduceMotion = remember(context) {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }
        val cameraNode = rememberCameraNode(engine) {
            focalLength = FOCAL_LENGTH_MM
            position = Position(0f, 0f, CAMERA_Z)
            // Seeded before the first frame, not just eased toward in onFrame. Filament's
            // depth-of-field pass asserts a positive focus distance, and a camera starts at
            // zero â€” enabling DOF without this aborts the process on the first frame.
            focusDistance = RoomAtmosphere.FOCUS_BROWSING
            camera.setFocusDistance(RoomAtmosphere.FOCUS_BROWSING)
        }

        var selectedAlbumId by remember { mutableStateOf<String?>(null) }
        var deckRevealed by remember { mutableStateOf(false) }
        val selectedCase = remember(selectedAlbumId, layout) {
            layout.cases.firstOrNull { it.albumId == selectedAlbumId }
        }
        val sequence = remember { SequenceDriver() }
        val frame = remember { FrameContext() }
        val actNodes = remember { ActiveCaseNodes() }
        val scratch = remember { ScratchDriver() }
        val view = LocalView.current

        // Read only inside the readout composable, so its ~12Hz updates during a scratch
        // recompose one line of text and nothing else.
        val scrubReadout = remember { mutableStateOf<ScrubReadout?>(null) }

        SideEffect {
            frame.selected = selectedCase
            frame.isPlaying = isPlaying
            frame.aspect = aspect
        }


        val wallMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                ComposeColor(0xFF1A1410), metallic = 0f, roughness = 0.95f
            )
        }
        val boardMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                ComposeColor(0xFF2F2318), metallic = 0.04f, roughness = 0.82f
            )
        }
        // One shared body material now that the spine plate carries the album's colour: the
        // case body is just dark jewel-case plastic, like the prototype's non-spine faces.
        val caseMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                ComposeColor(0xFF2A2622), metallic = 0.05f, roughness = 0.45f
            )
        }
        // Clear polycarbonate. An alpha below 1 makes createColorInstance pick the
        // transparent material, so the inlay behind it reads through the lid the way it does
        // on a real case. Without an environment map there is nothing for it to reflect yet â€”
        // convincing plastic and the disc's iridescence are M8/M9's job.
        val lidMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                ComposeColor(0.88f, 0.92f, 0.95f, 0.20f), metallic = 0f, roughness = 0.05f
            )
        }

        // The case and disc, built by tools/models/build_models.py and loaded from assets.
        //
        // Three files rather than one, because ActiveCaseNodes already has the hierarchy
        // they belong in — tray under the case root, lid under the hinge — and one
        // instance per file drops straight into it with no reparenting.
        //
        // These arrive asynchronously: each is null for the first frames and then becomes
        // non-null once, which is what ActiveCaseNodes' mount guard is written against.
        val modelLoader = rememberModelLoader(engine)
        val trayModel = rememberModelInstance(modelLoader, TRAY_MODEL_ASSET)
        val lidModel = rememberModelInstance(modelLoader, LID_MODEL_ASSET)
        val discModel = rememberModelInstance(modelLoader, DISC_MODEL_ASSET)

        // Sized to the wall so density holds as the library grows, and rebuilt only when
        // those dimensions actually change â€” which is when albums are added, not per frame.
        val dustGeometry = remember(engine, layout.wallWidth, layout.wallHeight) {
            DustGeometry.build(engine, layout.wallWidth, layout.wallHeight)
        }
        DisposableEffect(dustGeometry) {
            onDispose { engine.safeDestroyGeometry(dustGeometry) }
        }
        val dustMaterial = remember(materialLoader) {
            val material = materialLoader.createMaterial(DUST_MATERIAL_ASSET)
            materialLoader.createInstance(material).apply {
                setParameter(
                    "tint",
                    RoomLighting.KEY_COLOR[0],
                    RoomLighting.KEY_COLOR[1],
                    RoomLighting.KEY_COLOR[2]
                )
                setParameter("opacity", if (reduceMotion) 0f else DUST_OPACITY)
                setParameter("time", 0f)
            }
        }

        val fonts = remember(context) { CaseArt.Fonts(context) }
        val artCache = remember(fonts) { CaseArtCache(fonts) }

        // Push the open album's art onto the permanently-mounted active case, and hide the
        // whole group when nothing is open. Writing `bitmap` re-uploads into the node's
        // existing texture; rebuilding the node instead would abort the process.
        val activeArtwork = selectedAlbumId?.let { artCache[it] }
        // Keyed on the models as well as the artwork, because the nodes these write to do
        // not exist until the models land. Without that key the effect fires once against
        // null handles, does nothing, and never runs again — leaving a case that opens
        // blank whenever the art resolved faster than the geometry.
        LaunchedEffect(activeArtwork, actNodes, trayModel, lidModel) {
            activeArtwork?.spine?.let { actNodes.spineArt?.bitmap = it }
            activeArtwork?.cover?.let { actNodes.coverArt?.bitmap = it }
        }
        LaunchedEffect(selectedAlbumId, actNodes, trayModel, lidModel, discModel) {
            val visible = selectedAlbumId != null
            actNodes.root?.isVisible = visible
            actNodes.disc?.isVisible = visible
            // Hidden is not the same as unpickable. The disc is mounted for the life of
            // the scene, so when it is hidden it stays parked in front of whichever case
            // was last open â€” and SceneView still hit-tests it. Left touchable it both
            // swallowed taps meant for that case and, worse, answered them with its own
            // node name, which the browse path took for an album id.
            actNodes.discMesh?.isTouchable = visible
        }

        // The disc's printed face.
        //
        // ONE texture, allocated once for the life of the scene, re-uploaded per album â€” the
        // rule M7 paid for. Creating a texture per album would mean destroying the old one,
        // and a texture still bound to a MaterialInstance aborts the process on the next
        // commit. Every face is baked to exactly DISC_PX square precisely so this one
        // allocation can serve every album.
        val discTexture = remember(engine) {
            Texture.Builder()
                .width(DiscArt.DISC_PX)
                .height(DiscArt.DISC_PX)
                .levels(DISC_MIP_LEVELS)
                .format(Texture.InternalFormat.RGBA8)
                .sampler(Texture.Sampler.SAMPLER_2D)
                // GEN_MIPMAPPABLE is not in DEFAULT, and generateMipmaps throws without it.
                // The mips matter here: the disc is small on screen for most of the lift,
                // and a 512px label minified without them crawls badly.
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
                .build(engine)
        }
        // Bind the album art onto the disc model's OWN material, rather than replacing that
        // material with a stock textured one.
        //
        // The distinction matters. The glb's material is generated by gltfio and carries
        // KHR_materials_clearcoat — the lacquer over the print, which is most of what makes
        // the face read as a disc rather than as a sticker. SceneView's stock material has
        // no clearcoat parameter at all (see docs/materials.md), so calling
        // `setMaterialInstance` here would silently throw the coat away.
        //
        // `baseColorMap` is gltfio's ubershader name for the base colour sampler. It only
        // does anything because the model ships a placeholder baseColorTexture: gltfio keys
        // the shader variant on which slots the glTF material declares, and a material
        // exported without one compiles to a shader that never samples. See
        // `attach_placeholder_basecolor` in the build script.
        LaunchedEffect(discModel, discTexture, actNodes) {
            val sampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE
            )
            actNodes.discMesh?.materialInstances?.forEach { primitives ->
                primitives.forEach { it.setParameter("baseColorMap", discTexture, sampler) }
            }
        }

        // The disc face, finally: `Album.discFaceUri` has been collected since M1 and never
        // shown. Baked off the main thread (ENGINEERING.md rule 5), then uploaded into the
        // texture that already exists rather than building a new one.
        //
        // Only the open album is baked â€” unlike spine and cover art there is no reason to
        // pre-load the neighbours, because exactly one disc is ever on screen.
        LaunchedEffect(selectedCase, discTexture) {
            val case = selectedCase ?: return@LaunchedEffect
            val face = withContext(Dispatchers.IO) {
                DiscArt.bakeFace(
                    face = CaseArt.loadCover(case.discFacePath, DiscArt.DISC_PX),
                    fallback = artCache[case.albumId]?.cover
                )
            }
            TextureHelper.setBitmap(engine, discTexture, 0, face)
            discTexture.generateMipmaps(engine)
        }

        val quantX by remember { derivedStateOf { floor(panX.value / SLOT_PITCH).toInt() } }
        val quantY by remember { derivedStateOf { floor(panY.value / ROW_PITCH).toInt() } }
        val visibleCases = remember(layout, quantX, quantY, halfW, halfH) {
            val centerX = quantX * SLOT_PITCH
            val centerY = quantY * ROW_PITCH
            val reachX = halfW + SLOT_PITCH * 3f
            val reachY = halfH + ROW_PITCH
            layout.cases.filter {
                abs(it.x - centerX) <= reachX && abs(it.y - centerY) <= reachY
            }
        }
        val visibleRows = remember(layout, quantY, halfH) {
            val centerY = quantY * ROW_PITCH
            layout.rows.filter { abs(it.centerY - centerY) <= halfH + ROW_PITCH }
        }

        LaunchedEffect(visibleCases) {
            visibleCases.forEach { artCache.load(it) }
            artCache.trim(visibleCases.mapTo(mutableSetOf()) { it.albumId })
        }

        // The case pool. Sized to the same window `visibleCases` uses — about 21 slots
        // across by 5 rows at this camera — and capped to the library so a three-album
        // shelf does not mount a hundred hidden slots and their textures.
        //
        // It only ever GROWS. Adding slots is free; removing them would destroy their
        // ImageNodes, which is the abort this whole pool exists to avoid.
        val caseSlots = remember { CaseSlots() }
        var casePoolSize by remember { mutableIntStateOf(0) }
        val wantedPoolSize = remember(layout, halfW, halfH) {
            val across = ((halfW + SLOT_PITCH * 3f) * 2f / SLOT_PITCH).toInt() + 2
            val down = ((halfH + ROW_PITCH) * 2f / ROW_PITCH).toInt() + 2
            (across * down).coerceAtMost(layout.cases.size)
        }
        if (wantedPoolSize > casePoolSize) casePoolSize = wantedPoolSize

        // Point the slots at whatever is on screen. Recomposes when the visible set changes
        // or when art finishes loading — CaseArtCache is state-backed, so reading it here
        // is what makes late-arriving spines reach their slot.
        val slotBindings = visibleCases.map { it to artCache[it.albumId] }
        LaunchedEffect(slotBindings, selectedAlbumId, nowPlayingAlbumId, casePoolSize) {
            caseSlots.bind(slotBindings, selectedAlbumId, nowPlayingAlbumId)
        }

        val focusCase by remember(visibleCases) {
            derivedStateOf {
                visibleCases.minByOrNull { case ->
                    val dx = case.x - panX.value
                    val dy = case.y - panY.value
                    dx * dx + dy * dy
                }
            }
        }

        fun openCase(albumId: String) {
            selectedAlbumId = albumId
            sequence.progress = 0f
            sequence.direction = 1
            sequence.spinAngle = 0f
        }

        fun closeCase() {
            sequence.direction = -1
            viewModel.stop()
        }

        /**
         * Where the disc's centre lands on screen, right now.
         *
         * The prototype measures the scratch angle in screen space about the disc's
         * projected centre rather than casting a ray, and `worldToScreenPoint` is the exact
         * equivalent of its `project(camera)`. Deriving this from the FOV constants instead
         * was tried and came out a fifth of a screen wrong.
         */
        fun discScreenCenter(): Vector3? {
            val disc = actNodes.disc ?: return null
            val p = disc.worldPosition
            return cameraNode.worldToScreenPoint(Vector3(p.x, p.y, p.z))
        }

        fun endScratch() {
            if (!scratch.active) return
            scratch.active = false
            viewModel.onScrub(scratch.cursor.trackIndex, scratch.cursor.offsetMs)
            scrubReadout.value = null
        }

        val gestureListener = rememberOnGestureListener(
            onDown = { _, _ ->
                scope.launch { panX.stop() }
                scope.launch { panY.stop() }
            },
            onScroll = { _, _, _, distance ->
                // No panning while a case is out â€” the prototype does the same.
                if (selectedAlbumId == null) {
                    scope.launch { panX.snapTo(panX.value + distance.x * metresPerPx) }
                    scope.launch { panY.snapTo(panY.value - distance.y * metresPerPx) }
                }
            },
            onFling = { _, _, _, velocity ->
                if (selectedAlbumId == null) {
                    scope.launch {
                        panX.animateDecay(
                            -velocity.x * metresPerPx, exponentialDecay(FLING_FRICTION)
                        )
                    }
                    scope.launch {
                        panY.animateDecay(
                            velocity.y * metresPerPx, exponentialDecay(FLING_FRICTION)
                        )
                    }
                }
            },
            // onSingleTapUp, not onSingleTapConfirmed: the latter waits out the double-tap
            // timeout, so a second tap arriving soon after the first â€” exactly what
            // interrupting a 1.5s sequence looks like â€” was being swallowed as a double tap.
            onSingleTapUp = { _, node ->
                if (selectedAlbumId != null) {
                    // A tap that landed on the disc belongs to the platter â€” the prototype
                    // consumes it the same way. Without this, grabbing the disc and letting
                    // go without turning it would close the case.
                    if (!node.isDisc()) {
                        // Reversible from anywhere: a tap while closing sends it back out.
                        if (sequence.direction < 0) sequence.direction = 1 else closeCase()
                    }
                } else {
                    // Only ever open something that is actually an album. Taking the first
                    // named ancestor on trust meant any named node in the scene could be
                    // opened as a "case" â€” which is exactly what the hidden disc did,
                    // leaving it stranded on screen with a deck that had no album behind it.
                    generateSequence(node) { it.parent }
                        .firstNotNullOfOrNull { it.name }
                        ?.takeIf { name -> layout.cases.any { it.albumId == name } }
                        ?.let { openCase(it) }
                }
            },
            // The scratch uses the move-gesture trio rather than onScroll because
            // OnGestureListener has no onUp, and a platter needs a reliable "finger lifted".
            // Panning stays on onScroll, which is already inert while a case is open, so the
            // two never contend.
            onMoveBegin = { _, e, node ->
                if (selectedAlbumId != null &&
                    sequence.progress > Scratch.ARM_AT &&
                    node.isDisc()
                ) {
                    discScreenCenter()?.let { c ->
                        scratch.active = true
                        scratch.lastAngle = atan2(e.y - c.y, e.x - c.x)
                        scratch.durations = deck.tracks.map { it.durationMs }
                        scratch.cursor = Scratch.Cursor(
                            trackIndex = deck.currentTrackIndex.coerceAtLeast(0),
                            offsetMs = viewModel.positionMs.value
                        )
                        scratch.haptics.reset()
                        scratch.lastSeekAt = 0L
                    }
                }
            },
            onMove = { _, e, _ ->
                if (scratch.active) {
                    discScreenCenter()?.let { c ->
                        val angle = atan2(e.y - c.y, e.x - c.x)
                        val delta = Scratch.angleDelta(scratch.lastAngle, angle)
                        scratch.lastAngle = angle
                        val degrees = Math.toDegrees(delta.toDouble()).toFloat()

                        // The disc tracks the finger exactly â€” this is the whole illusion.
                        sequence.spinAngle -= degrees

                        scratch.cursor = Scratch.advance(
                            scratch.cursor, Scratch.radiansToMs(delta), scratch.durations
                        )

                        val now = SystemClock.uptimeMillis()
                        if (scratch.haptics.accept(degrees, now)) {
                            // CLOCK_TICK needs no permission and honours the user's system
                            // haptics setting, unlike a raw Vibrator call.
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if (now - scratch.lastSeekAt >= Scratch.SEEK_THROTTLE_MS) {
                            scratch.lastSeekAt = now
                            viewModel.onScrub(
                                scratch.cursor.trackIndex, scratch.cursor.offsetMs
                            )
                            scrubReadout.value = ScrubReadout(
                                trackIndex = scratch.cursor.trackIndex,
                                positionMs = scratch.cursor.offsetMs,
                                // A positive delta is the direction audio advances, so it
                                // is what the arrows must follow.
                                forward = degrees >= 0f
                            )
                        }
                    }
                }
            },
            onMoveEnd = { _, _, _ -> endScratch() }
        )

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            // Replaces SceneView's default main and fill lights. Those are a neutral studio
            // rig â€” exactly the "flatly-lit scene" DESIGN.md's Do-not list ends on.
            environment = environment,
            mainLightNode = null,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            viewNodeWindowManager = viewNodeManager,
            onGestureListener = gestureListener,
            onFrame = { frameNanos ->
                val dt = if (sequence.lastFrameNanos == 0L) 0f
                else ((frameNanos - sequence.lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 24f)
                sequence.lastFrameNanos = frameNanos
                sequence.clock += dt

                // The dust's whole animation, in one uniform write per frame â€” no CPU work
                // per mote, which is what DESIGN.md asks for.
                if (!reduceMotion) dustMaterial.setParameter("time", sequence.clock)

                // Depth of field exists only while a case is open â€” on the flat wall it
                // erased the spines outright. One write per crossing, not per frame.
                val wantsDof = frame.selected != null
                if (wantsDof != sequence.dofEnabled) {
                    sequence.dofEnabled = wantsDof
                    RoomAtmosphere.setDepthOfField(filamentView, wantsDof)
                }

                // Focus follows the subject, eased rather than snapped so opening a case
                // pulls focus instead of cutting. The disc sits exactly DISC_VIEW_DIST in
                // front of the lens once it is up, so focusing there is what keeps it sharp
                // while the case and the shelves behind it fall away.
                val focusTarget =
                    if (wantsDof) RoomAtmosphere.FOCUS_DISC
                    else RoomAtmosphere.FOCUS_BROWSING
                sequence.focusDistance += (focusTarget - sequence.focusDistance).coerceIn(
                    -RoomAtmosphere.FOCUS_RATE * dt,
                    RoomAtmosphere.FOCUS_RATE * dt
                )
                // Written to the Filament camera itself, not CameraNode.focusDistance:
                // the node's property is SceneView's own, and the DoF pass reads the
                // camera's.
                cameraNode.camera.setFocusDistance(sequence.focusDistance)

                if (sequence.direction != 0) {
                    sequence.progress = (
                        sequence.progress +
                            sequence.direction * dt * (1000f / OpenSequence.DURATION_MS)
                        ).coerceIn(0f, 1f)

                    if (sequence.progress >= 1f && sequence.direction > 0) {
                        sequence.direction = 0

                        frame.selected?.albumId?.let(viewModel::playAlbum)
                    }
                    if (sequence.progress <= 0f && sequence.direction < 0) {
                        sequence.direction = 0
                        selectedAlbumId = null
                    }
                    // One state write per crossing, not per frame â€” the panel then fades in
                    // through an ordinary animation without the sequence recomposing anything.
                    val settled = sequence.progress > DECK_REVEAL_AT
                    if (settled != deckRevealed) deckRevealed = settled

                    // A case closing out from under a finger still on the disc: drop the
                    // scratch rather than leaving it armed against a disc going away.
                    if (scratch.active && sequence.progress <= Scratch.ARM_AT) {
                        scratch.active = false
                        scrubReadout.value = null
                    }
                }

                val selected = frame.selected
                if (selected == null) {
                    cameraNode.position = Position(panX.value, panY.value, CAMERA_Z)
                } else {
                    val beats = OpenSequence.beatsAt(sequence.progress)
                    val t = OpenSequence.transformsFor(
                        beats = beats,
                        slotX = selected.x,
                        slotY = selected.y,
                        panX = panX.value,
                        panY = panY.value,
                        baseCameraZ = CAMERA_Z,
                        tanHalfFov = TAN_HALF_FOV,
                        aspect = frame.aspect,
                        clock = sequence.clock
                    )
                    cameraNode.position = Position(panX.value, panY.value, t.cameraZ)
                    actNodes.root?.let { root ->
                        root.position = Position(t.caseX, t.caseY, t.caseZ)
                        root.rotation = Rotation(t.caseRotX, t.caseRotY, 0f)
                    }
                    actNodes.hinge?.rotation = Rotation(0f, t.hingeRotY, 0f)
                    actNodes.disc?.let { disc ->
                        disc.isVisible = t.discVisible
                        disc.position = Position(t.discX, t.discY, t.discZ)
                        disc.scale = Scale(t.discScale)
                        // Free spin yields to the hand on the platter â€” the prototype's
                        // `if (playing && !scratching)`. The finger's own rotation is
                        // already being added in onMove.
                        //
                        // Subtracted, so idle spin runs the same way round as a forward
                        // scrub. Dragging the disc clockwise moves the audio forward, and
                        // it would read as a fault if letting go then span it backwards.
                        // DESIGN.md Â§4 also lists the idle spin under reduced motion.
                        if (frame.isPlaying && !scratch.active && !reduceMotion) {
                            sequence.spinAngle -= OpenSequence.SPIN_DEG_PER_S * dt * beats.spin
                        }
                        disc.rotation = Rotation(0f, 0f, sequence.spinAngle)
                    }
                }
            }
        ) {
            // The shop lamp. A focused spot rather than a directional light, because
            // DESIGN.md wants "one warm lamp over the racks, deep shadow everywhere else" â€”
            // a directional light has no falloff and would light the whole wall evenly.
            LightNode(
                type = LightManager.Type.FOCUSED_SPOT,
                position = RoomLighting.KEY_POSITION,
                // Aimed back down at the wall's centre from up and to the right.
                direction = normalize(-RoomLighting.KEY_POSITION),
                apply = {
                    color(
                        RoomLighting.KEY_COLOR[0],
                        RoomLighting.KEY_COLOR[1],
                        RoomLighting.KEY_COLOR[2]
                    )
                    intensityCandela(RoomLighting.KEY_CANDELA)
                    spotLightCone(RoomLighting.KEY_CONE_INNER, RoomLighting.KEY_CONE_OUTER)
                    falloff(RoomLighting.KEY_FALLOFF)
                    castShadows(true)
                    // Until now this light ran on Filament's stock shadow settings, which
                    // is what CaseSlots' "a 1024px shadow map is too coarse" complaint was
                    // really about. Quadrupling the map and sizing the near/far hints to
                    // the room lets a 10mm spine cast an edge you can actually read.
                    shadowOptions(
                        LightManager.ShadowOptions().apply {
                            mapSize = RoomLighting.KEY_SHADOW_MAP_PX
                            // Drives the PCSS penumbra set in RoomAtmosphere: the larger
                            // the emitter, the faster its shadow softens with distance.
                            // A shop spot is small, so the softening is gentle and contact
                            // stays sharp.
                            shadowBulbRadius = RoomLighting.KEY_BULB_RADIUS
                            // The whole room is inside a 1.5m sphere around the lamp;
                            // without this the map's depth range is spent on empty space
                            // and the usable precision collapses.
                            shadowNearHint = RoomLighting.KEY_SHADOW_NEAR
                            shadowFarHint = RoomLighting.KEY_SHADOW_FAR
                            // Millimetre geometry needs a proportionally small bias, or
                            // the lid's shadow detaches from the lid.
                            constantBias = RoomLighting.KEY_SHADOW_CONSTANT_BIAS
                            normalBias = RoomLighting.KEY_SHADOW_NORMAL_BIAS
                        }
                    )
                }
            )
            // The coloured bounce, low and left â€” DESIGN.md's "one coloured bounce light".
            // It does not cast shadows: a second shadow-casting light doubles the cost and
            // muddies the single-lamp story the key light is telling.
            LightNode(
                type = LightManager.Type.POINT,
                position = RoomLighting.RIM_POSITION,
                apply = {
                    color(
                        RoomLighting.RIM_COLOR[0],
                        RoomLighting.RIM_COLOR[1],
                        RoomLighting.RIM_COLOR[2]
                    )
                    intensityCandela(RoomLighting.RIM_CANDELA)
                    falloff(RoomLighting.RIM_FALLOFF)
                    castShadows(false)
                }
            )

            CubeNode(
                size = Size(layout.wallWidth, layout.wallHeight, 0.01f),
                position = Position(0f, 0f, WALL_Z - 0.005f),
                materialInstance = wallMaterial,
                apply = {
                    isTouchable = false
                    // Takes shadows, casts none â€” there is nothing behind it.
                    isShadowReceiver = true
                    isShadowCaster = false
                }
            )
            visibleRows.forEach { row ->
                key(row.shelfId) {
                    CubeNode(
                        size = Size(layout.wallWidth, BOARD_H, BOARD_D),
                        position = Position(0f, row.boardY, 0f),
                        materialInstance = boardMaterial,
                        apply = {
                            isTouchable = false
                            isShadowReceiver = true
                            isShadowCaster = true
                        }
                    )

                    ViewNode(
                        windowManager = viewNodeManager,
                        position = Position(row.plateX, row.boardY, BOARD_D / 2f + PLATE_LIFT),
                        apply = {
                            pxPerUnits = PLATE_PX_PER_UNIT
                            isTouchable = false
                        }
                    ) {
                        ShelfPlate(name = row.name, accentHue = row.accentHue)
                    }
                }
            }
            // The wall's cases come from a POOL, keyed by slot index rather than album id.
            //
            // Mounting a node per visible album and dropping it when it left the frustum
            // window destroyed its ImageNodes, and ImageNode.destroy() is broken on this
            // SceneView version — the process aborts with "Invalid texture still bound to
            // MaterialInstance". A hard vertical fling pulls whole rows out at once and
            // killed the app within a few swipes. See CaseSlots for the full story; do not
            // "simplify" this back into a keyed loop over visibleCases.
            CaseSlotNodes(
                slots = caseSlots,
                size = casePoolSize,
                caseMaterial = caseMaterial,
                lidMaterial = lidMaterial
            )
            // Dust hangs in front of the wall, animated entirely on the GPU. Not touchable
            // and casts nothing â€” it is glow, and a shadow-casting mote would be absurd.
            MeshNode(
                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer = dustGeometry.vertexBuffer,
                indexBuffer = dustGeometry.indexBuffer,
                boundingBox = dustGeometry.boundingBox,
                materialInstance = dustMaterial,
                apply = {
                    isTouchable = false
                    isShadowCaster = false
                    isShadowReceiver = false
                    isVisible = !reduceMotion
                }
            )

            // Mounted once and hidden when idle, never torn down â€” see ActiveCaseNodes.
            ActiveCaseNodes(
                nodes = actNodes,
                trayModel = trayModel,
                lidModel = lidModel,
                discModel = discModel
            )
        }

        if (layout.isEmpty) {
            Text(
                text = "Your shelves are waiting",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (selectedAlbumId == null) {
            // While a case is open the disc occupies this band, and the album is already the
            // whole subject â€” the browse label would only collide with it.
            FocusLabel(
                case = focusCase,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // The room is the home screen now, so back has nowhere to pop to â€” it closes an open
        // case, and is left alone while browsing so it exits the app normally.
        BackHandler(enabled = selectedAlbumId != null) { closeCase() }

        // Chrome, only while browsing: with a case open the deck owns the screen.
        if (selectedAlbumId == null) {
            // Centred along the bottom: the top belongs to the focus label (a long title ran
            // straight under the icons when they lived up there), and on a phone this tall
            // the bottom is the only comfortable reach. navigationBarsPadding keeps it off
            // the system nav, which it was sitting on.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Its own ground. Bare on the room, the dim labels landed on whatever
                    // shelf plank happened to be behind them and the shelf's label plate
                    // beside them read as a fourth chrome item.
                    .background(
                        Brush.verticalGradient(
                            0f to ComposeColor.Transparent,
                            0.5f to ComposeColor(0xE0060403),
                            1f to ComposeColor(0xFA060403)
                        )
                    )
                    .navigationBarsPadding()
                    .padding(top = 44.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Even thirds rather than a huddle in the middle: three wide targets across
                // the bottom is the easiest thing to hit one-handed on a phone this size.
                ChromeAction(Icons.Default.Add, "ADD", onAddAlbum, Modifier.weight(1f))
                ChromeAction(
                    Icons.AutoMirrored.Filled.List, "SHELVES", onOpenShelfManager,
                    Modifier.weight(1f)
                )
                ChromeAction(
                    Icons.Default.Settings, "SETTINGS", onOpenSettings, Modifier.weight(1f)
                )
            }
        }

        val deckAlpha by animateFloatAsState(
            targetValue = if (deckRevealed) 1f else 0f,
            label = "deckAlpha"
        )
        if (deckAlpha > 0.01f) {
            val openCase = selectedCase
            DeckOverlay(
                title = openCase?.title.orEmpty(),
                artist = openCase?.artist.orEmpty(),
                deck = deck,
                positionFlow = viewModel.positionMs,
                onPlayPause = viewModel::onPlayPause,
                onNext = viewModel::onNext,
                onPrevious = viewModel::onPrevious,
                onSeek = viewModel::onSeek,
                onSelectTrack = viewModel::onSelectTrack,
                scrubReadout = scrubReadout,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxHeight(0.52f)
                    .graphicsLayer { alpha = deckAlpha }
            )
        }
    }
}

/** One room-chrome action: a real 48dp target with a quiet mono label under it. */
@Composable
private fun ChromeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, tint = LabelDim)
        }
        Text(text = label, style = JavonTextStyle.mono.copy(color = LabelDim))
    }
}

/**
 * The label plate's content, rendered to a texture by [ViewNode]. Sized in raw pixels rather
 * than dp: the plate's world size is `viewSize / pxPerUnits`, so a density-dependent size
 * would make the same shelf label physically bigger on one device than another.
 */
@Composable
private fun ShelfPlate(name: String, accentHue: Float) {
    val density = LocalDensity.current
    val widthDp = with(density) { PLATE_PX_W.toDp() }
    val heightDp = with(density) { PLATE_PX_H.toDp() }
    val textSize = with(density) { 34.toSp() }

    Box(
        modifier = Modifier
            .size(widthDp, heightDp)
            .background(ComposeColor.hsl(accentHue * 360f, 0.18f, 0.86f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.uppercase(),
            color = ComposeColor(0xFF17120F),
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = textSize,
            letterSpacing = with(density) { 3.toSp() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The placard for whichever case is centred, built to the prototype's `#spine` rule rather
 * than to a Material Typography slot: uppercase Archivo at `clamp(17px,4vw,25px)` with
 * `letter-spacing:.03em`, not `displaySmall`'s 36sp. At display size it swallowed the top of
 * the frame and ellipsised most titles; the prototype wants a caption over the room, not a
 * headline. Two lines are allowed so a long title reads in full instead of being cut.
 *
 * The scrim matters as much as the size. The focused case is centred by definition, so its
 * spine â€” the one piece of the room carrying its own vertical text â€” is always directly
 * behind this. Without something to sit on, the two sets of words interleave.
 */
@Composable
private fun FocusLabel(case: CaseLayout?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to ComposeColor(0xF2060403),
                    0.62f to ComposeColor(0xC4060403),
                    1f to ComposeColor.Transparent
                )
            )
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(
            text = case?.title.orEmpty().uppercase(),
            fontFamily = ArchivoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            lineHeight = 25.sp,
            letterSpacing = 0.7.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = case?.artist.orEmpty().uppercase(),
            style = JavonTextStyle.mono,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
