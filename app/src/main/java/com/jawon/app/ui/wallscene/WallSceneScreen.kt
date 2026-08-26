package com.jawon.app.ui.wallscene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.JavonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.wallscene.WallDimens.BOARD_D
import com.jawon.app.ui.wallscene.WallDimens.BOARD_H
import com.jawon.app.ui.wallscene.WallDimens.CAMERA_Z
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.FOCAL_LENGTH_MM
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.PLATE_W
import com.jawon.app.ui.wallscene.WallDimens.ROW_PITCH
import com.jawon.app.ui.wallscene.WallDimens.SLOT_PITCH
import com.jawon.app.ui.wallscene.WallDimens.TAN_HALF_FOV
import com.jawon.app.ui.wallscene.WallDimens.WALL_Z
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.safeDestroyGeometry
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import androidx.compose.ui.graphics.Color as ComposeColor

/** Fling coast. The prototype's friction of 28 is a ~36ms time constant — barely a coast; */
/** this is looser so a flick actually travels. Tuning knob. */
private const val FLING_FRICTION = 2f

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

@Composable
fun WallSceneScreen(
    onBack: () -> Unit,
    viewModel: WallSceneViewModel = hiltViewModel()
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
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
        val viewNodeManager = rememberViewNodeManager()
        val cameraNode = rememberCameraNode(engine) {
            focalLength = FOCAL_LENGTH_MM
            position = Position(0f, 0f, CAMERA_Z)
        }

        var selectedAlbumId by remember { mutableStateOf<String?>(null) }
        val selectedCase = remember(selectedAlbumId, layout) {
            layout.cases.firstOrNull { it.albumId == selectedAlbumId }
        }
        val sequence = remember { SequenceDriver() }
        val frame = remember { FrameContext() }
        val actNodes = remember { ActiveCaseNodes() }

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
        // on a real case. Without an environment map there is nothing for it to reflect yet —
        // convincing plastic and the disc's iridescence are M8/M9's job.
        val lidMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                ComposeColor(0.88f, 0.92f, 0.95f, 0.20f), metallic = 0f, roughness = 0.05f
            )
        }

        val discGeometry = remember(engine) { DiscGeometry.build(engine) }
        DisposableEffect(discGeometry) {
            onDispose { engine.safeDestroyGeometry(discGeometry) }
        }

        val fonts = remember(context) { CaseArt.Fonts(context) }
        val artCache = remember(fonts) { CaseArtCache(fonts) }

        // Push the open album's art onto the permanently-mounted active case, and hide the
        // whole group when nothing is open. Writing `bitmap` re-uploads into the node's
        // existing texture; rebuilding the node instead would abort the process.
        val activeArtwork = selectedAlbumId?.let { artCache[it] }
        LaunchedEffect(activeArtwork, actNodes) {
            activeArtwork?.spine?.let { actNodes.spineArt?.bitmap = it }
            activeArtwork?.cover?.let { actNodes.coverArt?.bitmap = it }
        }
        LaunchedEffect(selectedAlbumId, actNodes) {
            val visible = selectedAlbumId != null
            actNodes.root?.isVisible = visible
            actNodes.disc?.isVisible = visible
        }

        // Bare silvered polycarbonate, created once for the whole screen.
        //
        // The user's `discFaceUri` art is deliberately NOT applied yet. Swapping a texture
        // per selected album means destroying the old one, and its MaterialInstance is owned
        // by the MaterialLoader and outlives it — Filament then aborts the process on commit
        // with "Invalid texture still bound to MaterialInstance". Getting that ordering right
        // is fiddly enough that it belongs with M8, which replaces this material with the
        // real iridescence shader and owns the disc's surface properly.
        val discMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(
                ComposeColor(0xFFB9C0C8), metallic = 0.7f, roughness = 0.18f
            )
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

        val gestureListener = rememberOnGestureListener(
            onDown = { _, _ ->
                scope.launch { panX.stop() }
                scope.launch { panY.stop() }
            },
            onScroll = { _, _, _, distance ->
                // No panning while a case is out — the prototype does the same.
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
            // timeout, so a second tap arriving soon after the first — exactly what
            // interrupting a 1.5s sequence looks like — was being swallowed as a double tap.
            onSingleTapUp = { _, node ->
                if (selectedAlbumId != null) {
                    // Reversible from anywhere: a tap while closing sends it back out.
                    if (sequence.direction < 0) sequence.direction = 1 else closeCase()
                } else {
                    generateSequence(node) { it.parent }
                        .firstNotNullOfOrNull { it.name }
                        ?.let { openCase(it) }
                }
            }
        )

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
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
                        if (frame.isPlaying) {
                            sequence.spinAngle += OpenSequence.SPIN_DEG_PER_S * dt * beats.spin
                        }
                        disc.rotation = Rotation(0f, 0f, sequence.spinAngle)
                    }
                }
            }
        ) {
            CubeNode(
                size = Size(layout.wallWidth, layout.wallHeight, 0.01f),
                position = Position(0f, 0f, WALL_Z - 0.005f),
                materialInstance = wallMaterial,
                apply = { isTouchable = false }
            )
            visibleRows.forEach { row ->
                key(row.shelfId) {
                    CubeNode(
                        size = Size(layout.wallWidth, BOARD_H, BOARD_D),
                        position = Position(0f, row.boardY, 0f),
                        materialInstance = boardMaterial,
                        apply = { isTouchable = false }
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
            // While a case is out, its shelved counterpart is HIDDEN rather than removed.
            //
            // Destroying an ImageNode is not safe on the SceneView version we are pinned to:
            // ImageNode.destroy() enqueues its texture for destruction but hands
            // MaterialLoader.destroyMaterialInstance() a wrapper re-fetched via
            // getMaterialInstanceAt(0), which is not the object the loader is tracking — so
            // the instance is never destroyed and Filament aborts the process on the next
            // commit with "Invalid texture still bound to MaterialInstance". (SceneView's own
            // v4.30 release note, "Cached Filament handles that stop lying", reads like the
            // fix; we are on v4.18 because anything newer needs a Kotlin our AGP cannot read.)
            //
            // isVisible cascades to children, so one flag hides the body and both art plates.
            visibleCases.forEach { case ->
                key(case.albumId) {
                    val artwork = artCache[case.albumId]
                    Node(
                        position = Position(case.x, case.y, 0f),
                        isVisible = case.albumId != selectedAlbumId
                    ) {
                        CubeNode(
                            size = Size(CASE_W, CASE_H, CASE_D),
                            materialInstance = caseMaterial,
                            apply = { name = case.albumId }
                        ) {
                            if (artwork != null) {
                                ImageNode(
                                    bitmap = artwork.spine,
                                    size = Size(CASE_W, CASE_H, 0f),
                                    normal = Direction(z = 1f),
                                    position = Position(0f, 0f, CASE_D / 2f + PLATE_LIFT)
                                )
                                // The cover faces +X, matching where the opened case's lid
                                // ends up: its cover is on local +Z, and the turn starts at
                                // +90, which maps that to world +X. Facing -X here is what
                                // made the artwork jump sides the moment the case reshelved.
                                artwork.cover?.let { cover ->
                                    ImageNode(
                                        bitmap = cover,
                                        size = Size(CASE_D, CASE_H, 0f),
                                        normal = Direction(z = 1f),
                                        position = Position(CASE_W / 2f - 0.0012f, 0f, 0f),
                                        rotation = Rotation(0f, 90f, 0f)
                                    )
                                }
                            }
                            // The clear front, flush with the outer face — the same panel the
                            // opened case carries in its lid. Without it here, the plastic
                            // sheen vanished the instant the case returned to the shelf.
                            CubeNode(
                                size = Size(0.0008f, CASE_H, CASE_D),
                                materialInstance = lidMaterial,
                                position = Position(CASE_W / 2f - 0.0004f, 0f, 0f),
                                apply = { isTouchable = false }
                            )
                        }
                    }
                }
            }
            // Mounted once and hidden when idle, never torn down — see ActiveCaseNodes.
            ActiveCaseNodes(
                nodes = actNodes,
                caseMaterial = caseMaterial,
                lidMaterial = lidMaterial,
                discMaterial = discMaterial,
                discGeometry = discGeometry
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
            // whole subject — the browse label would only collide with it.
            FocusLabel(
                case = focusCase,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 24.dp, end = 24.dp)
            )
        }

        TextButton(
            onClick = { if (selectedAlbumId != null) closeCase() else onBack() },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) { Text("BACK", style = JavonTextStyle.mono) }
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

@Composable
private fun FocusLabel(case: CaseLayout?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = case?.title.orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
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
}
