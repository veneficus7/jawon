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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.MaterialInstance
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.JavonTextStyle
import com.jawon.app.ui.wallscene.WallDimens.BOARD_D
import com.jawon.app.ui.wallscene.WallDimens.BOARD_H
import com.jawon.app.ui.wallscene.WallDimens.CAMERA_Z
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.FOCAL_LENGTH_MM
import com.jawon.app.ui.wallscene.WallDimens.ROW_PITCH
import com.jawon.app.ui.wallscene.WallDimens.SLOT_PITCH
import com.jawon.app.ui.wallscene.WallDimens.TAN_HALF_FOV
import com.jawon.app.ui.wallscene.WallDimens.WALL_Z
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import androidx.compose.ui.graphics.Color as ComposeColor

/** Fling coast. The prototype's friction of 28 is a ~36ms time constant — barely a coast; */
/** this is looser so a flick actually travels. Tuning knob. */
private const val FLING_FRICTION = 2f

@Composable
fun WallSceneScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: WallSceneViewModel = hiltViewModel()
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val aspect = constraints.maxWidth.toFloat() / viewportHeightPx

        // Half-extents of the wall plane actually on screen, from the camera's frustum.
        val halfH = CAMERA_Z * TAN_HALF_FOV
        val halfW = halfH * aspect

        // 1:1 finger tracking: the point under the finger stays under the finger. Derived
        // rather than transcribing the prototype's fixed per-CSS-pixel gain, which was that
        // same idea approximated for one browser window — this stays correct across screen
        // densities and any camera-distance tuning.
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
        val cameraNode = rememberCameraNode(engine) {
            focalLength = FOCAL_LENGTH_MM
            position = Position(0f, 0f, CAMERA_Z)
        }

        // Drive the camera straight from the pan animatables. snapshotFlow keeps this out of
        // composition entirely, so panning never recomposes anything (ENGINEERING.md rule 3).
        LaunchedEffect(cameraNode) {
            snapshotFlow { panX.value to panY.value }.collect { (x, y) ->
                cameraNode.position = Position(x, y, CAMERA_Z)
            }
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
        // Created on demand and cached per album. MaterialLoader tracks every instance it
        // creates and destroys them all when the loader is disposed with this screen, and
        // nodes default to destroyMaterialsOnDispose=false — so a case scrolling out of the
        // frustum window never takes its material with it.
        val spineMaterials = remember(materialLoader) { mutableMapOf<String, MaterialInstance>() }
        fun spineMaterial(case: CaseLayout): MaterialInstance =
            spineMaterials.getOrPut(case.albumId) {
                materialLoader.createColorInstance(
                    case.spineColor(), metallic = 0.05f, roughness = 0.55f
                )
            }

        // Only instantiate cases near the frustum — never nodes for the whole library. The
        // pan position is quantised to whole slots/rows so this recomposes once per slot
        // crossed rather than once per frame.
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

        // Nearest case to screen centre — the prototype's spine label. derivedStateOf means
        // the overlay recomposes only when the focused album actually changes.
        val focusCase by remember(visibleCases) {
            derivedStateOf {
                visibleCases.minByOrNull { case ->
                    val dx = case.x - panX.value
                    val dy = case.y - panY.value
                    dx * dx + dy * dy
                }
            }
        }

        val gestureListener = rememberOnGestureListener(
            onDown = { _, _ ->
                scope.launch { panX.stop() }
                scope.launch { panY.stop() }
            },
            onScroll = { _, _, _, distance ->
                // Android's GestureDetector reports distance as (previous - current), so a
                // finger moving right gives a negative distanceX.
                scope.launch { panX.snapTo(panX.value + distance.x * metresPerPx) }
                scope.launch { panY.snapTo(panY.value - distance.y * metresPerPx) }
            },
            onFling = { _, _, _, velocity ->
                // Fling velocity, unlike scroll distance, points the way the finger moved.
                scope.launch {
                    panX.animateDecay(-velocity.x * metresPerPx, exponentialDecay(FLING_FRICTION))
                }
                scope.launch {
                    panY.animateDecay(velocity.y * metresPerPx, exponentialDecay(FLING_FRICTION))
                }
            },
            onSingleTapConfirmed = { _, node -> node?.name?.let(onOpenAlbum) }
        )

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            // Our pan drives the camera; the built-in orbit manipulator would fight it.
            cameraManipulator = null,
            // Required: otherwise the library re-centres our explicitly authored positions.
            autoCenterContent = false,
            onGestureListener = gestureListener
        ) {
            CubeNode(
                size = Size(layout.wallWidth, layout.wallHeight, 0.01f),
                position = Position(0f, 0f, WALL_Z - 0.005f),
                materialInstance = wallMaterial,
                apply = { isTouchable = false }
            )
            layout.rows.forEach { row ->
                key(row.shelfId) {
                    CubeNode(
                        size = Size(layout.wallWidth, BOARD_H, BOARD_D),
                        position = Position(0f, row.boardY, 0f),
                        materialInstance = boardMaterial,
                        apply = { isTouchable = false }
                    )
                }
            }
            // key() per album so each case owns its node: the `apply` block that stamps the
            // album id runs once at creation, and entering/leaving the window creates and
            // destroys that node rather than recycling a neighbour's.
            visibleCases.forEach { case ->
                key(case.albumId) {
                    CubeNode(
                        size = Size(CASE_W, CASE_H, CASE_D),
                        position = Position(case.x, case.y, 0f),
                        materialInstance = spineMaterial(case),
                        apply = { name = case.albumId }
                    )
                }
            }
        }

        if (layout.isEmpty) {
            Text(
                text = "Your shelves are waiting",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            FocusLabel(
                case = focusCase,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 24.dp, end = 24.dp)
            )
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) { Text("BACK", style = JavonTextStyle.mono) }
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
