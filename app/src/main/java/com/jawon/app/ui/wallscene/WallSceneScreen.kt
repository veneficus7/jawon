package com.jawon.app.ui.wallscene

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import androidx.compose.ui.graphics.Color as ComposeColor

/** Fling coast. The prototype's friction of 28 is a ~36ms time constant â€” barely a coast; */
/** this is looser so a flick actually travels. Tuning knob. */
private const val FLING_FRICTION = 2f

/**
 * How the wall comes to rest on an album.
 *
 * Low stiffness and no bounce: this is a heavy rack sliding to a stop, not a UI element
 * springing into place. `DampingRatioNoBouncy` matters more than the stiffness — overshooting
 * past a record and coming back would undo the point of snapping to it at all.
 */
private val SETTLE_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * How long after a fling begins a release is assumed to belong to it.
 *
 * The release settle and the fling both want to move the wall, and they are driven from the
 * same finger lifting. Whether SceneView hands us the raw ACTION_UP before or after its
 * gesture detector has decided there was a fling is an ordering detail not worth depending
 * on — so the release waits one frame and then checks whether a fling claimed the gesture.
 */
private const val FLING_CLAIM_MS = 120L

/**
 * How close and how far the browse camera may stand, in metres.
 *
 * The limits are the feature. Unbounded zoom is what makes a 3D view read as a zoomed web
 * page — go far enough in and you are looking at a texture, far enough out and the room
 * becomes a diagram. These two are picked from what the room is *for*:
 *
 * - **Near**, one album fills the frame the way it does when you take a record off a shelf to
 *   look at it. Closer than this and the case's cover is bigger than its own texture.
 * - **Far**, about three bays across and three rows down — enough to find something, still
 *   close enough that a 10mm spine is legible. Further out and the wall stops being a room
 *   you are standing in.
 *
 * [WallDimens.CAMERA_Z] sits between them and is where the camera rests by default, and where
 * the open sequence returns it to.
 */
private const val ZOOM_NEAR = 0.46f
private const val ZOOM_FAR = 1.30f

/**
 * How fast a pinch travels, as a multiplier on the raw scale factor's departure from 1.
 *
 * Below 1 because a raw `ScaleGestureDetector` factor moves a dolly far too fast: the same
 * pinch that comfortably doubles a photo throws the camera through the whole room, and the
 * limits then do all the work, which feels like hitting walls rather than moving.
 */
private const val ZOOM_GAIN = 0.65f

/**
 * How fast the camera catches up to the pinch, per second.
 *
 * A compromise. Too low and the zoom lags the fingers, which feels broken; too high and the
 * filtering stops working and the finger jitter is back in the lens. At 6 the camera is ~95%
 * of the way there in half a second — fast enough to read as direct, slow enough to smooth a
 * touch sample's worth of noise.
 */
private const val ZOOM_EASE_RATE = 6f


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
/**
 * Bookkeeping for the wall's magnet, so a release and a fling do not both try to move it.
 * Not Compose state — nothing about it is drawn.
 */
/**
 * The browse camera's distance, and where it is heading.
 *
 * **Two values rather than one, because a pinch is not smooth input.** `ScaleGestureDetector`
 * reports a fresh `scaleFactor` per touch sample, computed from finger positions that jitter
 * by a pixel or two — writing that straight to the camera put the jitter directly into the
 * lens, which is what "the zoom doesn't look smooth" was. The gesture now moves [target] and
 * the frame loop eases [current] toward it, so finger noise is filtered out and the camera
 * only ever moves continuously.
 *
 * It also gives the open sequence its camera correction for free: `openCase` sets [target]
 * back to the canonical distance and the same easing carries it there, with no second
 * mechanism and no animation to keep in step with the beats.
 *
 * Not Compose state — the frame driver owns it and reads it every frame (ENGINEERING.md rule 3).
 */
private class WallZoom(start: Float) {
    var current = start
    var target = start

    /**
     * Ease toward [target], frame-rate independently.
     *
     * The `1 - exp(-rate * dt)` form matters: a plain `current += (target - current) * k`
     * converges at a speed that depends on how often it is called, so the same pinch would
     * feel different at 60fps and 120fps — and this app runs at both.
     */
    fun advance(dt: Float) {
        if (dt <= 0f) return
        current += (target - current) * (1f - exp(-ZOOM_EASE_RATE * dt))
    }
}

private class WallSettle {
    /**
     * When the gesture detector last called a fling, and how fast — recorded by `onFling`,
     * consumed by the single settle on release.
     *
     * **`onFling` deliberately does not animate anything itself.** It used to, and so did the
     * raw ACTION_UP handler, which gave the wall two settles racing for the same two
     * `Animatable`s. Their order is not guaranteed — SceneView feeds the gesture detector and
     * the touch hook from the same event stream — so whichever started second cancelled the
     * first, sometimes after it had already retargeted to a different album. Horizontal
     * survived it more often than vertical purely by luck, which is exactly what "snapping
     * works horizontally only" looked like from the outside.
     */
    var flungAt = 0L
    var flungVx = 0f
    var flungVy = 0f

    /**
     * Whether the gesture currently in progress has actually panned the wall.
     *
     * **Without this the magnet fires on every tap.** The settle hangs off the raw ACTION_UP,
     * which arrives for taps just as much as for drags — so tapping a case to open it also
     * slid the whole wall sideways underneath it, and so did a tap on empty space. Verified on
     * device: one tap on the background moved a half-off-screen case to dead centre.
     *
     * Snapping is for browsing. A tap is not browsing, so it is only armed once `onScroll`
     * has reported real movement, and disarmed again as each new touch lands.
     */
    var panned = false
}

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
 * Scene nodes the frame driver moves but does not own.
 *
 * Only the lamp, so far. It used to be nailed to one spot in world space, which was fine
 * when the whole wall was 100mm across; once each album got a 150mm bay, panning past a few
 * records walked straight out of the lit pool and into black. The lamp now rides with the
 * browse position, so it is always the light over *what you are looking at*.
 *
 * This keeps DESIGN.md's "one warm lamp over the racks" reading true — there is still one
 * lamp and one pool of light — it just no longer requires the shop to be one metre wide.
 */
/**
 * A shelved album being turned by hand.
 *
 * Deliberately not Compose state, like the other gesture drivers here: turning a case writes
 * a rotation every frame and must not recompose anything (ENGINEERING.md rule 3).
 *
 * Its existence is also what stops the wall panning mid-turn. Horizontal drag means two
 * different things depending on what is under the finger — pan the shelf, or spin the record
 * — and `onScroll` fires regardless of what was picked. So the grab is resolved once in
 * `onMoveBegin`, and `onScroll` stands down for as long as [slot] is non-null.
 */
private class CaseDrag {
    var slot: CaseSlot? = null
    var lastX = 0f
}

private class RoomNodes {
    var keyLight: SceneNode? = null
    var rimLight: SceneNode? = null
}

/**
 * A scratch in progress. Deliberately not Compose state, for the same reason
 * [SequenceDriver] is not: touch arrives faster than the display refreshes and none of it
 * may recompose anything (ENGINEERING.md rule 3). The only Compose writes a scratch makes are the
 * readout's, at the throttled seek rate.
 */
private class ScratchDriver {
    var active = false

    /**
     * Whether letting go of the disc should start the music again — i.e. whether it was
     * playing when the finger landed. Holding the platter pauses; releasing restores what
     * was there rather than assuming play.
     */
    var resumeOnRelease = false
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
 * The shelved case — a shut jewel case at 768 triangles, against the held case's 2884.
 * Everything the tray carries inside it is enclosed when the lid is down, and the wall
 * draws hundreds of these at once.
 */
private const val CLOSED_CASE_MODEL_ASSET = "models/case_closed.glb"

/**
 * The underlight cone each album levitates in. Geometry rather than a real spot light —
 * Filament will not give every bay its own light, and this is one draw. See build_beam().
 */
private const val BEAM_MODEL_ASSET = "models/beam.glb"

/**
 * How many case instances the wall allocates, once, at startup.
 *
 * The pool is bounded by the viewport rather than by the library — `wantedPoolSize` below
 * works out to about 132 slots in portrait on a 1080x2340 screen, and about 468 in
 * landscape, where the wall is nearly four times wider in slot-pitch terms. 512 clears
 * both with room to spare.
 *
 * It has to be a fixed number decided before the first frame. `createInstancedModel` takes
 * its count up front, and the alternative — growing the instance list as the pool grows —
 * would mean re-creating the asset, which means re-keying the slot composables, which
 * destroys their `ImageNode`s and aborts the process. See [CaseSlots].
 *
 * Unused instances cost a transform each and are never added to the scene, so over-
 * allocating is cheap; running out would silently clip the edges of the wall in landscape.
 *
 * Measured rather than assumed, after SSR taught that lesson: cold start is ~934ms at 512
 * against ~944ms at 8, i.e. the allocation is free and run-to-run variance is far larger
 * than the difference.
 */
private const val MAX_CASE_SLOTS = 512

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

        /**
         * How far back the camera stands while browsing — the zoom.
         *
         * A camera dolly, not a scale. That is the difference between the request ("make it
         * more satisfying, not like a website page zoomed in/out") and the thing it was
         * warning against: scaling enlarges a flat image, while moving the lens through the
         * room changes the perspective, so the cases nearer the middle grow faster than the
         * ones at the edges and the shelf boards fan out. The room is 3D; the zoom should use
         * that rather than paper over it.
         *
         * An `Animatable` because the open sequence eases it back to the canonical distance —
         * see [OPEN_DURATION_MS] at the call site — and because it is a stable object the
         * gesture callbacks can read live, rather than a value they would capture once.
         */
        val zoom = remember { WallZoom(CAMERA_Z) }

        // Culling and pool sizing are computed at the WIDEST the camera can go, not at the
        // current zoom. They feed `remember` keys, and a value that changes continuously
        // through a pinch would rebuild the visible sets on every frame of the gesture. A
        // superset costs a few off-screen slots; recomputing costs the gesture's smoothness.
        val halfH = ZOOM_FAR * TAN_HALF_FOV
        val halfW = halfH * aspect

        val panX = remember { Animatable(0f) }
        val panY = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()

        /**
         * Metres of wall per pixel of finger, at the current zoom.
         *
         * A function rather than a value: panning and flinging read it from inside gesture
         * callbacks, which are remembered once, so a captured number would be whatever the
         * zoom happened to be when the listener was built. Zoomed out, a drag has to cover
         * more wall for the same finger travel or the wall feels glued down.
         */
        fun metresPerPx(): Float = (2f * zoom.current * TAN_HALF_FOV) / viewportHeightPx

        var alignedOnLoad by remember { mutableStateOf(false) }
        LaunchedEffect(layout) {
            panX.updateBounds(layout.minPanX, layout.maxPanX)
            panY.updateBounds(layout.minPanY, layout.maxPanY)

            // Open centred on a record, not mid-gap.
            //
            // Pan starts at 0, which is the wall's *origin* and not any album's position —
            // `colX` centres the widest shelf about zero, so with an even album count, or
            // with shelves of differing lengths, nothing sits at 0. On device the app was
            // opening with the first case half off the left edge.
            //
            // Snapped rather than animated, and only once: this is where the wall *starts*,
            // so there is nothing to animate from, and re-running it whenever `layout`
            // changes would yank the wall back every time an album was added or removed.
            if (!alignedOnLoad && layout.cases.isNotEmpty()) {
                alignedOnLoad = true
                layout.nearestCase(0f, 0f)?.let { first ->
                    panX.snapTo(first.x)
                    panY.snapTo(first.y)
                }
            }
        }

        val engine = rememberEngine()
        val materialLoader = rememberMaterialLoader(engine)
        val environmentLoader = rememberEnvironmentLoader(engine)
        val viewNodeManager = rememberViewNodeManager()

        // How much rendering this device gets. See RoomQuality for why this both guesses and
        // measures rather than only guessing.
        //
        // Two values, deliberately. `initialTier` is fixed for the session and drives the
        // things that are baked once — the IBL cubemap and the shadow map. `tier` is state
        // and can step down mid-session, driving everything on the Filament View, which is
        // where the per-frame cost actually is.
        val initialTier = remember(context) { QualityTier.detect(context) }
        var tier by remember { mutableStateOf(initialTier) }
        val frameWatch = remember { FrameWatch() }

        // The room's image-based lighting, generated rather than loaded â€” see RoomLighting.
        // Filament's own prefilter turns the procedural cubemap into a roughness mip chain.
        val environment = remember(engine, environmentLoader) {
            RoomLighting.createEnvironment(engine, initialTier.cubemapPx) { cubemap ->
                environmentLoader.iblPrefilter.specularFilter(cubemap)
            }
        }

        // Fog, bloom, depth of field and vignette all live on Filament's View. Named to
        // keep it apart from the Android View that LocalView supplies for haptics.
        val filamentView = rememberView(engine)
        // Applied during composition, NOT from a LaunchedEffect.
        //
        // A LaunchedEffect runs after composition commits, so the first frames rendered with
        // Filament's stock View options — no fog, no vignette, no ambient occlusion, bloom
        // unthresholded. Those four are most of what makes this room dark and grounded, so
        // launching the app flashed a flat, brightly-lit brown wall for a frame or two
        // before the atmosphere arrived and everything dropped into place.
        //
        // `remember` runs while composing, which is before the renderer's first frame.
        //
        // Keyed on the tier as well, so a mid-session step down re-applies the whole post
        // chain. `RoomAtmosphere.apply` replaces each option struct wholesale rather than
        // mutating it, so running it again is safe.
        remember(filamentView, tier) { RoomAtmosphere.apply(filamentView, tier) }

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
        val roomNodes = remember { RoomNodes() }
        val caseDrag = remember { CaseDrag() }
        val scratch = remember { ScratchDriver() }
        val settle = remember { WallSettle() }

        /** The album a long press has proposed removing, until it is confirmed or dismissed. */
        var pendingDelete by remember { mutableStateOf<CaseLayout?>(null) }
        // Goes to the vibrator directly rather than through the view's haptic feedback —
        // see RoomHaptics for why the disc had no vibration at all before this.
        val haptics = remember(context) { RoomHaptics(context) }

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
        // The case and disc, built by tools/models/build_models.py and loaded from assets.
        //
        // Three files rather than one, because ActiveCaseNodes already has the hierarchy
        // they belong in — tray under the case root, lid under the hinge — and one
        // instance per file drops straight into it with no reparenting.
        //
        // These arrive asynchronously: each is null for the first frames and then becomes
        // non-null once, which is what ActiveCaseNodes' mount guard is written against.
        val modelLoader = rememberModelLoader(engine)

        // The wall's shelved cases: ONE asset, MAX_CASE_SLOTS instances sharing its
        // geometry, material and textures — each instance is little more than a transform.
        //
        // Loaded synchronously, unlike the three above, and that is deliberate. `bind()`
        // writes through the slot handles from a LaunchedEffect keyed on the pool size; if
        // the instances arrived a few frames late those handles would still be null when it
        // first ran, which is exactly the bug the active case hit. The asset is 20KB and the
        // dust material already loads this way.
        //
        // Allocated once at MAX_CASE_SLOTS and never re-created. The pool grows at runtime,
        // and re-keying anything on its size would drop every slot composable out of
        // composition and destroy its ImageNodes — the process abort this file exists to
        // prevent. So the count is fixed up front and the pool is clamped to it.
        val caseInstances = remember(modelLoader) {
            modelLoader.createInstancedModel(CLOSED_CASE_MODEL_ASSET, MAX_CASE_SLOTS)
        }
        val beamInstances = remember(modelLoader) {
            modelLoader.createInstancedModel(BEAM_MODEL_ASSET, MAX_CASE_SLOTS)
        }

        val trayModel = rememberModelInstance(modelLoader, TRAY_MODEL_ASSET)
        val lidModel = rememberModelInstance(modelLoader, LID_MODEL_ASSET)
        val discModel = rememberModelInstance(modelLoader, DISC_MODEL_ASSET)

        // Sized to the wall so density holds as the library grows, and rebuilt only when
        // those dimensions actually change â€” which is when albums are added, not per frame.
        val dustGeometry = remember(engine, layout.wallWidth, layout.wallHeight) {
            // Density from the *initial* tier, not the current one: rebuilding this geometry
            // destroys and recreates a Filament buffer, which is the last thing a device
            // that has just been caught dropping frames needs.
            DustGeometry.build(
                engine,
                layout.wallWidth,
                layout.wallHeight,
                initialTier.dustDensity
            )
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
        val artCache = remember(fonts) {
            CaseArtCache(fonts, trackTitles = { viewModel.trackTitles(it) })
        }

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
            // Clamped to the instances actually allocated — indexing past them would throw.
            (across * down).coerceAtMost(layout.cases.size).coerceAtMost(MAX_CASE_SLOTS)
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
            // The open sequence's framing is tuned against the canonical CAMERA_Z — see
            // OpenSequence.FRONT_GAP — so a case opened from a zoomed-out wall has to arrive
            // back there. Just retargeting the zoom does it: the same per-frame easing that
            // smooths a pinch carries the camera home, well inside the sequence, with no
            // separate animation to keep in step with the beats.
            zoom.target = CAMERA_Z
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
            // Let the platter go: pick playback back up exactly where the scrub left it, and
            // only if it was running when the disc was grabbed.
            if (scratch.resumeOnRelease) {
                scratch.resumeOnRelease = false
                viewModel.setPlaying(true)
            }
        }

        val gestureListener = rememberOnGestureListener(
            onDown = { e, node ->
                scope.launch { panX.stop() }
                scope.launch { panY.stop() }
                // Each new touch starts as "not a browse" until onScroll says otherwise.
                settle.panned = false

                // Grabbing the platter happens HERE, on the touch landing — not in
                // onMoveBegin, where it used to live.
                //
                // onMoveBegin fires only once a drag has travelled past touch slop, so
                // "press and hold the disc to pause" did not pause on a press at all: it
                // waited until the finger moved a few millimetres, which is exactly what
                // testers described. A hand laid flat on a turntable stops it immediately,
                // and the code has to do the same.
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

                        // A hand on the platter stops the platter. Hold the disc and the
                        // music holds with it, for as long as the finger is down — which is
                        // both what a turntable does and what makes scrubbing legible,
                        // because you are no longer hearing playback fight the scrub.
                        //
                        // Remembered rather than assumed: releasing must restore what was
                        // true before the grab, or grabbing a paused disc would start it.
                        scratch.resumeOnRelease = frame.isPlaying
                        if (frame.isPlaying) viewModel.setPlaying(false)
                    }
                }
            },
            onScroll = { _, _, _, distance ->
                // No panning while a case is out â€” the prototype does the same â€” and none
                // while an album is being turned by hand, or the shelf would slide away
                // under the record you are trying to look at.
                if (selectedAlbumId == null && caseDrag.slot == null) {
                    // Arms the magnet: this gesture is a browse, so its release should land
                    // on an album. See WallSettle.panned.
                    settle.panned = true
                    val scale = metresPerPx()
                    scope.launch { panX.snapTo(panX.value + distance.x * scale) }
                    scope.launch { panY.snapTo(panY.value - distance.y * scale) }
                }
            },
            // Pinch to walk the camera in and out of the room. Browsing only: once a case is
            // out, the open sequence owns the camera and a pinch would fight it for the same
            // channel.
            onScale = { detector, _, _ ->
                if (selectedAlbumId == null) {
                    // Damped, and about 1.0 rather than multiplied raw — see ZOOM_GAIN.
                    val factor = 1f + (detector.scaleFactor - 1f) * ZOOM_GAIN
                    // Moves the TARGET. The camera itself is eased toward it in the frame
                    // loop, which is what filters out the per-sample jitter that made the
                    // zoom judder — see WallZoom.
                    zoom.target = (zoom.target / factor).coerceIn(ZOOM_NEAR, ZOOM_FAR)
                }
            },
            // Records the throw; does NOT animate. The single settle on release reads this
            // and projects from it — see WallSettle for why there is only one settle site.
            onFling = { _, _, _, velocity ->
                if (selectedAlbumId == null) {
                    val scale = metresPerPx()
                    settle.flungAt = SystemClock.uptimeMillis()
                    settle.flungVx = -velocity.x * scale
                    settle.flungVy = velocity.y * scale
                }
            },
            // Long-press an album on the wall to take it off the shelf.
            //
            // Chosen over a button in the deck deliberately: the deck is open while music is
            // playing and its controls are pressed constantly, which is the worst possible
            // neighbourhood for a destructive action. A long press on the wall is a gesture
            // you cannot arrive at by accident, and it acts on the record you are pointing
            // at rather than on "the current album".
            onLongPress = { _, node ->
                if (selectedAlbumId == null) {
                    generateSequence(node) { it.parent }
                        .mapNotNull { it.name }
                        .firstOrNull { name -> layout.cases.any { it.albumId == name } }
                        ?.let { albumId ->
                            haptics.detent()
                            pendingDelete = layout.cases.firstOrNull { it.albumId == albumId }
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
                    //
                    // Walk for the first ancestor whose name IS an album, rather than taking
                    // the first named one and testing it afterwards. Since the shelved cases
                    // became glTF models, picking lands on a node the asset named itself
                    // ("case_closed"), which sits below the slot node carrying the album id
                    // — so the old order found the model's own name, failed the test, and
                    // silently dropped every tap on the wall.
                    generateSequence(node) { it.parent }
                        .mapNotNull { it.name }
                        .firstOrNull { name -> layout.cases.any { it.albumId == name } }
                        ?.let { openCase(it) }
                }
            },
            // Turning a shelved album uses the move-gesture trio rather than onScroll,
            // because it wants touch slop first — a case should not spin on a stray tap.
            // The disc is the opposite and is handled in onDown. Panning stays on onScroll,
            // which is already inert while a case is open, so the two never contend.
            onMoveBegin = { _, e, node ->
                // Browsing: grabbing an album turns it in place. Resolved once, here, using
                // the same walk the tap handler does — picking lands on a node the glTF
                // asset named itself, below the slot node carrying the album id.
                if (selectedAlbumId == null) {
                    val albumId = generateSequence(node) { it.parent }
                        .mapNotNull { it.name }
                        .firstOrNull { name -> layout.cases.any { it.albumId == name } }
                    if (albumId != null) {
                        caseDrag.slot = caseSlots.slots.firstOrNull { it.body?.name == albumId }
                        caseDrag.lastX = e.x
                        // Catch it: a spinning case stops dead under the finger.
                        caseDrag.slot?.spinVelocity = 0f
                    }
                }
                // The disc is already armed by onDown, which fires before this. Re-arming
                // here would reset the cursor to the live playback position and throw away
                // whatever the first few millimetres of the drag had already scrubbed.
            },
            onMove = { _, e, _ ->
                caseDrag.slot?.let { slot ->
                    val dx = e.x - caseDrag.lastX
                    caseDrag.lastX = e.x
                    val degrees = dx / ShelfHover.DRAG_PX_PER_DEG
                    slot.manualTurn += degrees
                    // Remembered so releasing mid-drag carries the throw. Divided by a
                    // nominal frame rather than real dt: this is a feel constant, and
                    // sampling dt here would make a flick's strength depend on frame rate.
                    slot.spinVelocity = degrees * 60f
                }
                if (scratch.active) {
                    discScreenCenter()?.let { c ->
                        val angle = atan2(e.y - c.y, e.x - c.x)
                        val delta = Scratch.angleDelta(scratch.lastAngle, angle)
                        scratch.lastAngle = angle
                        val degrees = Math.toDegrees(delta.toDouble()).toFloat()

                        // The disc tracks the finger exactly â€” this is the whole illusion.
                        sequence.spinAngle -= degrees

                        val before = scratch.cursor.trackIndex
                        scratch.cursor = Scratch.advance(
                            scratch.cursor, Scratch.radiansToMs(delta), scratch.durations
                        )

                        val now = SystemClock.uptimeMillis()
                        if (scratch.cursor.trackIndex != before) {
                            // A track boundary went past under the finger. Heavier than the
                            // scrub texture and deliberately a different effect, so a skip
                            // can be felt without looking — this is the "vibration on skip"
                            // testers asked for.
                            //
                            // Fired ahead of the tick and clearing its debt, so a boundary
                            // crossed mid-notch is one detent rather than a detent and a
                            // tick landing on top of each other.
                            haptics.detent()
                            scratch.haptics.reset()
                        } else if (scratch.haptics.accept(degrees, now)) {
                            haptics.tick()
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
            onMoveEnd = { _, _, _ ->
                // Let go of the record; its momentum carries on in the frame driver.
                caseDrag.slot = null
                endScratch()
            }
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
            // Releasing the platter, from the one place that reliably knows the finger is
            // gone: the raw touch stream, before any gesture detector has interpreted it.
            //
            // `OnGestureListener` has no `onUp` — checked against the interface, it has
            // nineteen callbacks and not one of them is a plain release. That did not matter
            // while the grab began at `onMoveBegin`, because it then always ended at
            // `onMoveEnd`. Moving the grab to `onDown` breaks that pairing: press the disc,
            // hold it still, and no move gesture ever begins, so none ever ends — the music
            // would stop and never start again.
            //
            // `onSingleTapUp` does not cover it either. Once `onLongPress` has fired, which
            // it does after half a second, the platform stops delivering it — and half a
            // second is well inside what "press and hold" means.
            //
            // Returns false always: this only observes. SceneView's own gesture handling
            // runs exactly as before, and `endScratch` is idempotent, so `onMoveEnd` calling
            // it too is harmless.
            onTouchEvent = { motionEvent, _ ->
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        caseDrag.slot = null
                        endScratch()

                        // THE settle — the only one. Both a slow drag and a fling end here.
                        //
                        // `settle.panned` gates it to gestures that actually browsed: a tap is
                        // not a browse, and without the gate every tap slid the wall, including
                        // the tap meant to open a case.
                        //
                        // One frame of delay so a fling, if the detector is about to call one,
                        // has recorded its velocity by the time this reads it. That is all
                        // `onFling` does now.
                        if (selectedAlbumId == null && settle.panned) {
                            scope.launch {
                                withFrameNanos { }

                                // A fling gets projected to where its decay would have run
                                // out, so the wall lands on the record the throw was aimed at
                                // rather than the one it happened to be passing. A slow
                                // release just settles from where it is.
                                val flung =
                                    SystemClock.uptimeMillis() - settle.flungAt < FLING_CLAIM_MS
                                val decay = exponentialDecay<Float>(FLING_FRICTION)
                                val fromX = if (flung) {
                                    decay.calculateTargetValue(panX.value, settle.flungVx)
                                } else panX.value
                                val fromY = if (flung) {
                                    decay.calculateTargetValue(panY.value, settle.flungVy)
                                } else panY.value

                                layout.nearestCase(fromX, fromY)?.let { target ->
                                    // Both axes driven from ONE album, and no initial velocity
                                    // handed to the springs: with bounds set, a spring given a
                                    // fling's velocity can reach a bound and terminate there
                                    // instead of at the target, which leaves the wall parked
                                    // somewhere no album is.
                                    //
                                    // NOTE: pan lands on these values exactly — logged and
                                    // confirmed on device — and horizontally the album ends up
                                    // dead centre. Vertically it still sits high in frame, so
                                    // something between `panY` and the projected image adds a
                                    // constant offset that is NOT in the snap and NOT a parent
                                    // transform (world and local position were measured equal).
                                    // A camera pitch of a few degrees would produce exactly
                                    // this, and is where to look next.
                                    launch {
                                        panX.animateTo(target.x, SETTLE_SPRING)
                                        haptics.tick()
                                    }
                                    launch { panY.animateTo(target.y, SETTLE_SPRING) }
                                }
                            }
                        }
                    }
                }
                false
            },
            onFrame = { frameNanos ->
                val dt = if (sequence.lastFrameNanos == 0L) 0f
                else ((frameNanos - sequence.lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 24f)
                sequence.lastFrameNanos = frameNanos
                sequence.clock += dt

                // Walk the browse camera toward wherever the pinch left it. Every frame,
                // whether or not anything is pinching — it is a no-op once it has arrived,
                // and this is also what carries the camera home when a case opens.
                zoom.advance(dt)

                // Was the opening guess about this device optimistic? `dt` is clamped above
                // at 1/24s, which is fine here — anything at the clamp is already far past
                // the slow-frame threshold and counts the same either way.
                //
                // Writing Compose state here is safe: it schedules a recomposition rather
                // than running one, so the `remember` that re-applies the View options fires
                // between frames rather than in the middle of this one. `FrameWatch` latches
                // after firing, so this happens at most once per session.
                if (dt > 0f && frameWatch.onFrame(dt)) {
                    tier.below?.let { stepped ->
                        Log.i(
                            "Jawon.Quality",
                            "Frame times are missing the mark; quality $tier -> $stepped"
                        )
                        tier = stepped
                    }
                }

                // The dust's whole animation, in one uniform write per frame â€” no CPU work
                // per mote, which is what DESIGN.md asks for.
                if (!reduceMotion) dustMaterial.setParameter("time", sequence.clock)

                // Depth of field exists only while a case is open â€” on the flat wall it
                // erased the spines outright. One write per crossing, not per frame.
                val wantsDof = frame.selected != null
                if (wantsDof != sequence.dofEnabled) {
                    sequence.dofEnabled = wantsDof
                    RoomAtmosphere.setDepthOfField(filamentView, wantsDof, tier)
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
                    //
                    // This path deliberately bypasses endScratch (there is no release to
                    // honour), so it has to undo the hold-pause itself. Without that the
                    // flag stays set, nothing ever calls setPlaying(true), and closing a
                    // case mid-scrub leaves the album silently paused for good.
                    if (scratch.active && sequence.progress <= Scratch.ARM_AT) {
                        scratch.active = false
                        scrubReadout.value = null
                        if (scratch.resumeOnRelease) {
                            scratch.resumeOnRelease = false
                            viewModel.setPlaying(true)
                        }
                    }
                }

                // Every shelved case hovers off its board and turns slowly, so a wall of
                // albums reads as a rack of objects presenting themselves rather than a row
                // of spines. Driven here rather than from `bind` because it is per-frame:
                // binding decides which album sits where, this decides how it is floating.
                //
                // Written straight to the nodes, never through Compose state — ENGINEERING.md
                // rule 3. No allocation in this loop either; it runs for every visible slot.
                // Walk the lamp along with the browse position. Panning is frozen while a
                // case is open, so during the open sequence this holds the light still.
                // The group's offset IS the pan; the lamp keeps its own offset inside it.
                roomNodes.keyLight?.position = Position(panX.value, panY.value, 0f)

                // This loop ALWAYS runs, and only the motion is conditional. `bind` no longer
                // writes root.position at all, so skipping it entirely under reduced motion
                // would leave every case stacked at the wall's origin.
                run {
                    val t = if (reduceMotion) 0f else sequence.clock
                    caseSlots.slots.forEach { slot ->
                        if (!slot.occupied) return@forEach
                        val root = slot.root ?: return@forEach
                        // The beam stands on the board and never turns, so it only needs
                        // placing — at the foot of the bay, under wherever the case is
                        // hanging. CASE_H/2 down from the album's centre is the board top.
                        slot.beam?.position =
                            Position(slot.baseX, slot.baseY - CASE_H / 2f, slot.baseZ)

                        if (reduceMotion) {
                            // Seated on the shelf, square on, exactly as before.
                            root.position = Position(slot.baseX, slot.baseY, slot.baseZ)
                            root.rotation = Rotation(0f, 0f, 0f)
                        } else {
                            root.position = Position(
                                slot.baseX,
                                slot.baseY + ShelfHover.lift(t, slot.phase),
                                slot.baseZ
                            )
                            // Carry any throw the user gave this case, shedding it over
                            // about a second. Skipped while the finger is down, because
                            // onMove is already writing manualTurn directly.
                            if (slot !== caseDrag.slot && slot.spinVelocity != 0f) {
                                slot.manualTurn += slot.spinVelocity * dt
                                slot.spinVelocity *= ShelfHover.SPIN_RETAIN_PER_S.pow(dt)
                                if (abs(slot.spinVelocity) < 1f) slot.spinVelocity = 0f
                            }
                            root.rotation = Rotation(
                                ShelfHover.tilt(t, slot.phase),
                                ShelfHover.turn(t, slot.phase) + slot.manualTurn,
                                0f
                            )
                        }
                    }
                }

                val selected = frame.selected
                if (selected == null) {
                    cameraNode.position = Position(panX.value, panY.value, zoom.current)
                } else {
                    val beats = OpenSequence.beatsAt(sequence.progress)
                    val t = OpenSequence.transformsFor(
                        beats = beats,
                        slotX = selected.x,
                        slotY = selected.y,
                        panX = panX.value,
                        panY = panY.value,
                        // NOT the CAMERA_Z constant. openCase() starts zoomZ easing to
                        // CAMERA_Z the instant a tap lands, in lockstep with sequence.progress
                        // (same DURATION_MS, both linear in time) — so reading it live here is
                        // what makes a case opened from a zoomed-out wall arrive at the
                        // sequence's tuned framing smoothly. Hardcoding CAMERA_Z here would
                        // make the correction above dead code: the camera would jump to it the
                        // instant the tap registered, before the pull even begins.
                        baseCameraZ = zoom.current,
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
            // Wrapped in a bare Node so the frame driver has something to move: the lamp
            // rides with the browse position, and `LightNode` exposes no handle of its own
            // that this file can reach. The light keeps its offset inside the group, so
            // moving the group is exactly moving the lamp.
            Node(apply = { roomNodes.keyLight = this }) {
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
                    // The frame driver walks this along with the browse position.
                    // Until now this light ran on Filament's stock shadow settings, which
                    // is what CaseSlots' "a 1024px shadow map is too coarse" complaint was
                    // really about. Quadrupling the map and sizing the near/far hints to
                    // the room lets a 10mm spine cast an edge you can actually read.
                    shadowOptions(
                        LightManager.ShadowOptions().apply {
                            // From the *initial* tier: the map is allocated when the light is
                            // built, so unlike the View options it does not follow a
                            // mid-session step down. A device that steps down still gets the
                            // cheaper shadow *filter* immediately, which is the larger of the
                            // two costs; the map size takes effect on the next launch.
                            mapSize = initialTier.shadowMapPx
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
            }
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

            // Nothing loaded, no wall. This is what caused the brown flash on launch.
            //
            // `layout` arrives from the database, so for the first frames after the splash
            // it is the placeholder — no rows, no cases — while this backing panel was drawn
            // regardless, at its default size, filling the frame. With nothing in front of it
            // to give the eye scale or contrast, a near-black wall under the room's ambient
            // reads as a flat lit brown field, which is exactly what it was.
            //
            // Gated on `loaded` rather than on having content, which is what it used to be.
            // That older test also caught the genuinely-empty library, so a new user's first
            // screen had no wall either — see WallLayout.loaded.
            if (layout.loaded) {
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
            }
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
                caseInstances = caseInstances,
                beamInstances = beamInstances
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

        if (layout.isWaiting) {
            // Sits low, under the empty board rather than over the middle of it, so the shelf
            // itself is what the eye lands on first. The room is the pitch; this is the
            // caption.
            WaitingForRecords(
                onAddAlbum = onAddAlbum,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else if (selectedAlbumId == null) {
            // While a case is open the disc occupies this band, and the album is already the
            // whole subject â€” the browse label would only collide with it.
            FocusLabel(
                case = focusCase,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        pendingDelete?.let { case ->
            RemoveAlbumDialog(
                case = case,
                onConfirm = {
                    viewModel.deleteAlbum(case.albumId)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null }
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
        Text(text = label, style = JawonTextStyle.mono.copy(color = LabelDim))
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
/**
 * Confirms taking an album off the shelf.
 *
 * **The second line is the whole point of this dialog.** Jawon's albums are records *about*
 * files that belong to the user and live in the user's own folders, and no confirmation that
 * just says "are you sure?" tells them whether their music is about to be deleted. It is not,
 * and it never can be — Jawon holds a read-only grant on those files — so the dialog says so
 * plainly rather than leaving the user to find out by checking afterwards.
 */
@Composable
private fun RemoveAlbumDialog(
    case: CaseLayout,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${case.title}?") },
        text = {
            Text(
                "It comes off the shelf, along with the artwork you gave it. " +
                    "Your audio files stay exactly where they are — Jawon never deletes " +
                    "your music."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = LampAmber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep it") }
        },
        containerColor = CrateBlack
    )
}

/**
 * The first thing anyone sees, and per `PLAN.md` §4 the most important screen in the app: it
 * "must read as 'your shelves, waiting' rather than 'broken app'".
 *
 * What used to be here was one line of centred text over black — and the room behind it was
 * genuinely empty, because the wall and its boards were suppressed whenever there were no
 * albums. So a new user's first impression of a product whose whole pitch is *the library is
 * a room* was a black screen. `WallLayout.waitingRow` supplies the lit, labelled board; this
 * is the invitation that goes with it.
 *
 * Deliberately in the upper third, in the slot the browse label occupies once there are
 * records. The shelf sits low in frame, so the room stays the subject and this reads as a
 * caption on it rather than a dialog over it.
 */
@Composable
private fun WaitingForRecords(onAddAlbum: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 32.dp)
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "YOUR SHELVES ARE WAITING",
            fontFamily = ArchivoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.8.sp,
            textAlign = TextAlign.Center
        )
        Text(
            // Says what to bring and what happens to it. "Add an album" alone does not
            // explain that Jawon wants a folder of the user's own files, which is the one
            // thing a new user has to understand before anything else works.
            text = "Point Jawon at a folder of your music and it takes a place on the shelf.",
            style = JawonTextStyle.mono.copy(color = LabelDim),
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )
        TextButton(
            onClick = onAddAlbum,
            modifier = Modifier.padding(top = 6.dp)
        ) {
            Text(
                text = "ADD YOUR FIRST ALBUM",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.6.sp,
                color = LampAmber
            )
        }
    }
}

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
            style = JawonTextStyle.mono,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
