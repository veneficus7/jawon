package com.jawon.app.ui.pocketscene

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.LightManager
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelDim
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import kotlin.math.max

/** Where the device's model lives. Seven objects in one file — see `docs/models.md`. */
const val POCKET_MODEL_ASSET = "models/pocket.glb"

/**
 * The lens, in millimetres against Filament's 24mm sensor height.
 *
 * Long, and that is the point: a device in the hand is looked at, not walked around, and a
 * wide lens bends its edges outward the way a phone camera held close does.
 */
private const val FOCAL_LENGTH_MM = 50f
private const val SENSOR_HEIGHT_MM = 24f

/** The lens held level — see the `onFrame` note and trap 8. A value, so it is not reallocated per frame. */
private val LEVEL = Rotation(0f, 0f, 0f)

/**
 * The iPod mode's room: a pocket player at life size, lit by a carriage at night.
 *
 * **This is the first pass and it is deliberately still.** It exists to answer the questions
 * only the engine can: does the model load with its materials, is the anodising anodising and
 * the polish polish under a real IBL, and is the device the size it claims to be. The window,
 * the lights going past, the screen and the wheel each come after, and each gets looked at on
 * its own (`docs/formats.md` §7).
 *
 * A sibling of `WallSceneScreen`, never a branch inside it — `ENGINEERING.md`'s standard for a new
 * format room. What it shares, it shares by calling.
 */
@Composable
fun PocketRoomScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val filamentView = rememberView(engine)
    val renderer = rememberRenderer(engine)

    val environment = remember(engine, environmentLoader) {
        PocketLighting.createEnvironment(engine) { cubemap ->
            environmentLoader.iblPrefilter.specularFilter(cubemap)
        }
    }

    val cameraNode = rememberCameraNode(engine) {
        focalLength = FOCAL_LENGTH_MM.toDouble()
    }

    val deviceModel = rememberModelInstance(modelLoader, POCKET_MODEL_ASSET)
    val ydpi = remember(context) { max(context.resources.displayMetrics.ydpi, 1f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // **Life size, computed from the scene's own height rather than chosen by eye.**
        //
        // The frame is `24 * distance / focal` tall for Filament's 24mm sensor. Put the
        // *physical height of the surface being drawn into* there and every millimetre of
        // model is a millimetre of glass: a 103.5mm device is 103.5mm of phone.
        //
        // The surface's height, measured here — not `displayMetrics.heightPixels`. That is the
        // window without its system bars (2807px on a 3120px panel), while the room draws edge
        // to edge under them, so the first version was 11% short of life size and nothing on
        // screen said so. The instrumentation that caught it read the viewport at 1440x3120.
        val sceneHeightM = constraints.maxHeight / ydpi * 0.0254f
        cameraNode.position = Position(0f, 0f, sceneHeightM * FOCAL_LENGTH_MM / SENSOR_HEIGHT_MM)

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            renderer = renderer,
            environment = environment,
            // SceneView's default rig is a neutral studio pair — the flat lighting DESIGN.md's
            // anti-goals end on. This room brings its own.
            mainLightNode = null,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            // **Trap 8, again, and measured again at the same −8.28°.** SceneView's camera node
            // arrives already pitched down, and nothing in this room resets it, so the device
            // sat a quarter of a screen too high while the arithmetic above was right. The CD
            // room overwrites the rotation every frame (`LOOK_STRAIGHT_AHEAD`), and so does this:
            // a device held square in front of you is looked at square on.
            onFrame = { cameraNode.rotation = LEVEL }
        ) {
            // The carriage's own ceiling light: warm, above and slightly behind the viewer, so
            // the anodised front has a gradient down it rather than a flat wash.
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(-0.18f, 0.34f, 0.30f),
                apply = {
                    color(1.0f, 0.86f, 0.70f)
                    intensityCandela(2.6f)
                    falloff(1.4f)
                    castShadows(false)
                }
            )
            // The window, as a light and not only as a reflection: cool, low, from the right,
            // which is what puts the cold edge along the polished side.
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(0.42f, -0.06f, 0.22f),
                apply = {
                    color(0.62f, 0.76f, 1.0f)
                    intensityCandela(3.4f)
                    falloff(1.6f)
                    castShadows(false)
                }
            )
            deviceModel?.let { instance ->
                // Turned a few degrees off square: an object photographed dead-on shows none
                // of its thickness, and the thickness is where the polished back and the
                // chamfer live.
                Node(rotation = Rotation(x = -6f, y = 15f, z = 0f)) {
                    ModelNode(
                        instance,
                        apply = {
                            isTouchable = false
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "POCKET PLAYER · IN PROGRESS",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 10.sp)
            )
            Text(
                text = "SETTINGS",
                style = JawonTextStyle.mono.copy(color = LabelDim, fontSize = 12.sp),
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .padding(10.dp)
            )
        }
    }
}
