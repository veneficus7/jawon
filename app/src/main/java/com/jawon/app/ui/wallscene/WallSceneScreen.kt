package com.jawon.app.ui.wallscene

import com.jawon.app.ui.common.displayRefreshHz
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.safeDestroyEnvironment
import kotlinx.coroutines.flow.first
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import com.jawon.app.BuildConfig
import com.jawon.app.ui.showroom.Exhibit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.common.ChromeAction
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.RoomScheme
import com.jawon.app.ui.wallscene.WallDimens.BOARD_D
import com.jawon.app.ui.wallscene.WallDimens.BOARD_H
import com.jawon.app.ui.wallscene.WallDimens.CAMERA_Z
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.FOCAL_LENGTH_MM
import com.jawon.app.ui.wallscene.WallDimens.FAR_PLANE
import com.jawon.app.ui.wallscene.WallDimens.NEAR_PLANE
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.PLATE_W
import com.jawon.app.ui.wallscene.WallDimens.FLOOR_REACH
import com.jawon.app.ui.wallscene.WallDimens.FLOOR_T
import com.jawon.app.ui.wallscene.WallDimens.ROW_PITCH
import com.jawon.app.ui.wallscene.WallDimens.SLOT_PITCH
import com.jawon.app.ui.wallscene.WallDimens.TAN_HALF_FOV
import com.jawon.app.ui.wallscene.WallDimens.WALL_Z
import com.google.android.filament.LightManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import com.google.android.filament.Renderer
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
import com.jawon.app.ui.common.rememberRoomEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import dev.romainguy.kotlin.math.Float4
import com.google.android.filament.utils.TextureType
import io.github.sceneview.texture.ImageTexture
import io.github.sceneview.texture.TextureSampler2D
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node as SceneNode
import io.github.sceneview.safeDestroyGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import java.util.Locale

/** Fling coast. The prototype's friction of 28 is a ~36ms time constant — barely a coast; */
/** this is looser so a flick actually travels. Tuning knob. */
private const val FLING_FRICTION = 2f

/**
 * How the wall comes to rest on an album.
 *
 * Low stiffness and no bounce: this is a heavy rack sliding to a stop, not a UI element
 * springing into place. `DampingRatioNoBouncy` matters more than the stiffness � overshooting
 * past a record and coming back would undo the point of snapping to it at all.
 */
private val SETTLE_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * The crate flip's chase, stiffer than [SETTLE_SPRING].
 *
 * A settle is the end of a gesture and can afford to be unhurried. The flip is *during* one —
 * the thumb is still moving and the wall has to stay under it — so at StiffnessLow the wall
 * lags far enough behind the rail that the letter under your thumb and the record on screen
 * are different albums.
 */
private val FLIP_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

/**
 * How long after a fling begins a release is assumed to belong to it.
 *
 * The release settle and the fling both want to move the wall, and they are driven from the
 * same finger lifting. Whether SceneView hands us the raw ACTION_UP before or after its
 * gesture detector has decided there was a fling is an ordering detail not worth depending
 * on � so the release waits one frame and then checks whether a fling claimed the gesture.
 */
private const val FLING_CLAIM_MS = 120L

/**
 * How close and how far the browse camera may stand, in metres.
 *
 * The limits are the feature. Unbounded zoom is what makes a 3D view read as a zoomed web
 * page � go far enough in and you are looking at a texture, far enough out and the room
 * becomes a diagram. These two are picked from what the room is *for*:
 *
 * - **Near**, one album fills the frame the way it does when you take a record off a shelf to
 *   look at it. Closer than this and the case's cover is bigger than its own texture.
 * - **Far**, about three bays across and three rows down � enough to find something, still
 *   close enough that a 10mm spine is legible. Further out and the wall stops being a room
 *   you are standing in.
 *
 * [WallDimens.CAMERA_Z] sits between them and is where the camera rests by default, and where
 * the open sequence returns it to.
 */
internal const val ZOOM_NEAR = 0.46f
internal const val ZOOM_FAR = 1.30f

// **The pinch only ever zooms.** It used to go on past ZOOM_FAR out of the room into the
// showroom, and the owner found that zooming out to see more of the wall threw him out of the
// room instead (2026-10-03). The way out is Back now, or a tap on the neon sign
// (NEON_SIGN_NODE) — neither of which a hand reaching for something else can do by accident.

/** The neon sign's node name: a tap that lands on it goes to the showroom. */
private const val NEON_SIGN_NODE = "neon_sign"

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
 * of the way there in half a second � fast enough to read as direct, slow enough to smooth a
 * touch sample's worth of noise.
 */
private const val ZOOM_EASE_RATE = 6f

/**
 * How close the camera has to be to its zoom target before the room stops calling itself busy.
 *
 * Only [RoomCadence] reads this — it does not stop the easing, which is deliberate. `WallZoom`
 * approaches exponentially and never quite arrives, and giving it a snap would change a tuned
 * gesture to save a float multiply. This just decides when the difference has stopped being
 * something worth spending a frame on: a hundredth of a millimetre of dolly, against a room
 * 400mm across and a camera standing 780mm back.
 */
private const val ZOOM_SETTLED_EPS = 1e-5f

/**
 * The same, for the focal plane. Rate-limited rather than eased, so it does land exactly and
 * this only has to survive the last step landing a ulp short of the target.
 */
private const val FOCUS_SETTLED_EPS = 1e-5f

/**
 * The camera's orientation: square on to the wall, always.
 *
 * **Written every frame because something else keeps changing it.** SceneView's camera node
 * re-aims itself at the world origin as it moves, so panning away from the wall's centre
 * tilted the lens back toward it � measured at -8.28� after a modest pan, which is exactly
 * `atan(0.113 / 0.78)` for a camera 113mm below the origin at 780mm back.
 *
 * That tilt is why an album settled high in the frame even when the magnet had put the camera
 * *exactly* on the album's own Y: at 8.28� the whole view shifts up by 627px of a 1560px half
 * frame, which dwarfs the 107px the album's real offset accounted for. Everything moved
 * together � cases, boards, underlight pools � which is what pointed at the camera rather
 * than at the wall.
 *
 * A wall is a flat plane and this room is looked at square on; a lens that yaws and pitches
 * toward the origin is never what this scene wants, at any pan position.
 */
private val LOOK_STRAIGHT_AHEAD = Rotation(0f, 0f, 0f)

/**
 * How the disc's printed face behaves under the room's light.
 *
 * **Tuned to stop the lamp bleaching the artwork, which took three attempts.** The first two
 * � baking more contrast into the texture, then dropping metallic � were aimed at the right
 * idea and were not enough on their own. Dumping the material's parameter list settled it:
 * every name being set does exist, so nothing was silently failing; there was simply more
 * than one route for light to come back off this surface, and killing metallic only closed
 * one of them.
 *
 * The three that matter, in order of how much they were costing:
 *
 * - **[DISC_LABEL_REFLECTANCE]**, now 0.06, is the one that was missed twice. The second
 *   time was worse, because the geometry had just changed underneath it: moving the disc's
 *   light onto its axis cured an orbiting hotspot and created a stationary one, since a
 *   source on the axis of a face you view head-on puts its specular reflection exactly in
 *   the middle of that face — and at roughness 0.78 the lobe is broad, so what landed was
 *   a soft white blob over a quarter of the label. Measured: 26.7% of the disc at or above
 *   235 of 255, on artwork nobody could read through it. The diffuse ratio across the face
 *   is 1.22:1, so the blob was never the lighting; it was the sheen. A printed paper label
 *   under lacquer returns very little, and 0.06 is that. The original note follows.
 * - **[DISC_LABEL_REFLECTANCE]** is the one that was missed. On a non-metal, Filament takes
 *   specular from `reflectance`, not from `metallicFactor` � so zeroing metallic left the
 *   surface still throwing back the default 4% of a lamp burned into the IBL at intensity
 *   26. Halving it halves the highlight the print is competing with.
 * - **[DISC_LABEL_ROUGHNESS]** at 0.78 spreads what is left over the whole face instead of
 *   concentrating it into a blown-out patch. A CD label is printed paper under lacquer; it
 *   is much closer to matte than to a mirror.
 * - **[DISC_LABEL_CLEARCOAT]** off. It is a second specular lobe stacked on top of the
 *   first, and it was adding a white sheen over everything for a lacquer effect nobody can
 *   see once the print underneath is legible. Worth restoring at a low value if the disc
 *   ever reads as too papery.
 */
/**
 * How reflective the room's flat surfaces are, in Filament's own units.
 *
 * **Not a percentage.** `reflectance` is remapped as `F0 = 0.16 * reflectance^2`, so the 4% that
 * an ordinary dielectric reflects is written here as **0.5**, and 0.04 would be 0.026% - a
 * material that does not exist and that returns nothing from the lamp. Getting this wrong once
 * turned the wall black; the constant exists so the next person reads the note instead of the
 * number.
 */
/**
 * How wide the flat chrome is allowed to get, whatever the window does.
 *
 * **Landscape is not a different layout here, it is the same layout unclamped.** Turn the phone
 * and the wall gets better — five records across instead of one, which is what a shelf is for —
 * while every 2D thing over it gets worse in the same motion: the three chrome actions spread to
 * thirds of a 3120px screen and end up further apart than a thumb can travel, the focus title
 * stretches into a headline, and the deck becomes a tracklist a metre wide with a 44dp row in it.
 *
 * A ceiling fixes all three without an orientation branch, and that is the point of doing it this
 * way. A phone in portrait is about 411dp across, which is under every number below, so **none of
 * this changes portrait at all** — there is no second layout to keep in step, and no `if
 * (landscape)` to get wrong. The controls simply stop growing once they are as wide as a control
 * should be, and the room keeps the rest.
 *
 * The backgrounds are deliberately *not* clamped: the ground under the chrome still spans the
 * window, so the controls read as sitting on a surface rather than floating in a raft.
 */
internal val CHROME_MAX_W = 460.dp

/** The focus label. Wider than the chrome — it is a title, and titles wrap badly when pinched. */
internal val FOCUS_MAX_W = 620.dp

/**
 * The deck.
 *
 * A tracklist is a column of one-line rows; past about this width the eye has to travel from a
 * track number on the far left to a duration on the far right across empty space, and the rack's
 * eight keys become slabs. Both want a column, not a field.
 */
internal val DECK_MAX_W = 600.dp

internal const val WALL_REFLECTANCE = 0.5f

/** The back panel's roughness, in either of its materials. */
private const val PANEL_ROUGHNESS = 0.78f

/** Fixed, so the rack is made of the same timber on every launch. */
private const val WALL_TILE_SEED = 0x5A17

/** A different cut of the same timber, so shelf and panel are not identical. */
private const val BOARD_TILE_SEED = 0x2C41

/** How thick the rack's end panels are. Shelving stock, same as a board. */
private const val SIDE_T = 0.018f

/** Fixed, so the room's brick is laid the same way every launch. */
private const val BRICK_TILE_SEED = 0x6B1C

/**
 * The room's wall sits just behind the rack's back panel — whose tiles are 10mm deep, their
 * front face at WALL_Z — so the rack stands against it rather than inside it.
 */
private const val ROOM_WALL_T = 0.010f
private const val ROOM_WALL_Z = WALL_Z - 0.012f - ROOM_WALL_T / 2f

/**
 * How far past the camera's reach the room goes, so no edge of wall or floor can ever come
 * into frame, at any zoom or any pan.
 */
private const val ROOM_MARGIN = 0.10f

/**
 * Painted brick: rough, and well under a plain dielectric's reflectance (trap 3 — 0.5 is the 4%
 * an ordinary surface reflects) because paint over brick is matt.
 *
 * It was 0.35, and the wall came out striped: bright bands of the tube's colour, one course
 * deep, a shelf-height apart, across brick nothing was lighting. They were the room's
 * environment map — which carries a bright band per strip on purpose, for the glossy cases to
 * reflect (RoomLighting.STRIP_BAND_LEVEL) — reflected off the brick. Matt paint shows none of
 * that; at 0.12 (F0 ≈ 0.2%) it is gone, and the brick is lit by what actually lights it.
 */
private const val BRICK_REFLECTANCE = 0.12f

/**
 * The clear shell of every jewel case, as the glTF names it (tools/models/build_models.py).
 */
private const val SHELL_MATERIAL = "JewelCaseClear"

/**
 * **What clear plastic actually does: transmit almost everything and reflect the rest.**
 *
 * The model's shell is a near-white (0.90, 0.94, 1.0) at 16% opacity (22% on the held lid).
 * Lit, that is a milky film over the artwork, and once the ceiling spots were bright enough to
 * see, the top row's covers came out behind a grey-white wash — with a dark band across the top
 * of each where the board above shadowed the film. Real polystyrene scatters next to nothing:
 * it passes ~92% of the light behind it and adds a reflection on top. So the shell's own colour
 * goes to near black, and its opacity to 8% — the 8% a clear surface loses — and what it shows
 * is only its reflections: the strips' lines in the glass and a spot's glint, which are what
 * made it read as plastic in the first place. Applied at load, so the model files are untouched.
 */
private const val SHELL_TINT = 0.03f
private const val SHELL_ALPHA = 0.08f

/** Makes a model's clear shell clear. See [SHELL_TINT]. */
private fun clearTheShell(instances: Array<MaterialInstance>) {
    instances.forEach { instance ->
        if (instance.name == SHELL_MATERIAL && instance.material.hasParameter("baseColorFactor")) {
            instance.setParameter("baseColorFactor", SHELL_TINT, SHELL_TINT, SHELL_TINT, SHELL_ALPHA)
        }
    }
}

/**
 * How the haze sheets sample their gradient: filtered both ways so its 256 rows blend into one
 * smooth fade, and clamped so the fade's first row cannot wrap round to meet its last.
 *
 * **Lazy, and it has to be.** A `TextureSampler` is made in native code, and a top-level `val`
 * here is initialised in this file's static initialiser — which runs the first time anything in
 * the file is touched, before Filament has loaded its library. As a plain `val` this crashed the
 * app on launch with `UnsatisfiedLinkError: … TextureSampler.nCreateSampler`, on the owner's
 * phone, while every unit test passed (none of them loads Filament). See ENGINEERING.md trap 23,
 * "never construct a Filament object at file or object level".
 */
private val HAZE_SAMPLER by lazy {
    TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
}

/** A little above the wall: a floor is smoother, and takes the lamp at a grazing angle. */
internal const val FLOOR_REFLECTANCE = 0.55f

internal const val DISC_LABEL_METALLIC = 0f
internal const val DISC_LABEL_ROUGHNESS = 0.78f
internal const val DISC_LABEL_CLEARCOAT = 0f
internal const val DISC_LABEL_REFLECTANCE = 0.06f

/**
 * How much of the disc's printed artwork survives to the screen.
 *
 * Not a lighting value — a multiplier on the texture itself, and a **light touch now that it
 * is no longer doing a lamp's job for it.** It went to 0.72 and then 0.62 on the reasoning
 * that the disc measured a mean of 139 against a cover at 98 and the whites in a sleeve are
 * what bloom feeds on. The measurement was right and the conclusion was backwards: the disc
 * was not brighter because its artwork was brighter, it was brighter because
 * [RoomLighting.DISC_UNDER_CANDELA] was throwing 24,000 lux at it in an 11,000-lux room.
 * Dimming the texture to hide that is painting over a window to fix the glare — it costs the
 * artwork its own contrast, which is the one thing on this surface worth keeping.
 *
 * **This is the disc's exposure control, and it turns out to be the only one there is.**
 * Three separate delete tests on the device say so: its own lamp switched fully off moved the
 * face by 0.2 of a count, taking the disc off the room's light channel moved it by 0.6, and
 * dropping this to 0.05 moved it from 240 to 91. The disc is lit by the ambient IBL, which
 * Filament does not channel-gate, so nothing aimed at a *lamp* can expose it — which is why
 * five rounds of aiming at lamps measured as nothing.
 *
 * 0.48 is read off the curve those probes fit, `out = 194 * factor^0.349` on the printed
 * band: it puts the artwork near 150, out of the tonemapper's shoulder, where the label keeps
 * its own contrast and colour instead of being compressed toward white. Which is what
 * "washed" was the whole time — not a highlight sitting on the disc, but the top third of
 * the disc's range folded flat. 0.72 and 0.62 were both tried and both rejected by the owner,
 * and the curve says why: they render 173 and 164, still on the shoulder.
 */
internal const val DISC_LABEL_PRINT = 0.48f


/** Progress at which the deck fades in — as the disc squares up, not before. */
/**
 * Where in the open sequence the deck starts to arrive.
 *
 * **0.995, so it arrives *after* the record does rather than on top of it.** It was 0.75, and
 * a per-frame timing probe through the sequence is what found the cost. The open is a 120Hz
 * animation for three quarters of its length and then falls apart:
 *
 * | beat | mean frame | with the deck moved |
 * |---|---|---|
 * | pull | 8.6ms | 8.5ms |
 * | turn | 8.3ms | 8.3ms |
 * | open | 8.3ms | 8.3ms |
 * | lift | 10.7ms | 8.3ms |
 * | **spin** | **18.3ms** | **8.3ms** |
 *
 * Whole sequence: 103fps with 7.1% of frames missing a vsync and a 41.7ms worst frame,
 * against **119.6fps, 0.9% and 16.7ms** once the deck is out of the way. That second column is
 * identical to deleting the deck outright, so the deck's entrance accounted for all of it.
 *
 * The cause is trap 14, "an animation that recomposes nothing can still be the most expensive
 * thing on screen": the deck rises on a single `graphicsLayer` with an animated `alpha`, and a
 * large translucent layer over the Filament surface has to be re-blended by the compositor on
 * every frame its alpha changes. Starting that at 0.75 ran it across 1425-1865ms of a 1900ms
 * sequence — which is precisely the disc lifting out and spinning up, the part of this app
 * anyone actually watches.
 *
 * Nothing about the entrance itself changed: same 440ms, same rise, same easing. It is only
 * sequenced after the record settles instead of over it, which is also the better staging —
 * the record comes out, and *then* the controls arrive. The cost is that the deck is fully
 * present about 475ms later than it was.
 *
 * **The close is not measured.** `deckRevealed` goes false as soon as progress leaves 1.0, so
 * the 440ms fade-out now runs over the first part of the close, which is the same overlap
 * mirrored. The probe only dumped on open, so that is an open question rather than a claim.
 */
private const val DECK_REVEAL_AT = 0.995f

/**
 * How long the deck takes to arrive, in milliseconds, and how far it rises doing it.
 *
 * Unhurried on purpose: it is the last beat of a 1.9s sequence that has been deliberate the
 * whole way, and a panel snapping in at the end undoes some of that.
 */
private const val DECK_FADE_MS = 440
private const val DECK_RISE_PX = 34f

/** Label plate raster size. World size is these divided by [PLATE_PX_PER_UNIT]. */
private const val PLATE_PX_W = 400
private const val PLATE_PX_H = 76
private const val PLATE_PX_PER_UNIT = PLATE_PX_W / PLATE_W

/**
 * The label plate's card, the same on every shelf.
 *
 * It was tinted by the shelf's own hue until the owner had that choice removed (2026-09-21) —
 * the underlights it was chosen for had gone a week earlier, so it had become a colour picker
 * for a small card and nothing else. This is exactly the colour a shelf that was never
 * recoloured already showed (hue 0), so a wall of default shelves does not change at all.
 * A Compose colour, not a Filament object, so a top-level val is safe here (trap 23).
 */
private val PLATE_CARD = ComposeColor.hsl(0f, 0.22f, 0.13f)

/**
 * Mutable state the per-frame driver owns. Deliberately not Compose state: the open
 * sequence advances every frame and must not recompose anything (ENGINEERING.md rule 3).
 */
/**
 * Bookkeeping for the wall's magnet, so a release and a fling do not both try to move it.
 * Not Compose state � nothing about it is drawn.
 */
/**
 * The browse camera's distance, and where it is heading.
 *
 * **Two values rather than one, because a pinch is not smooth input.** `ScaleGestureDetector`
 * reports a fresh `scaleFactor` per touch sample, computed from finger positions that jitter
 * by a pixel or two � writing that straight to the camera put the jitter directly into the
 * lens, which is what "the zoom doesn't look smooth" was. The gesture now moves [target] and
 * the frame loop eases [current] toward it, so finger noise is filtered out and the camera
 * only ever moves continuously.
 *
 * It also gives the open sequence its camera correction for free: `openCase` sets [target]
 * back to the canonical distance and the same easing carries it there, with no second
 * mechanism and no animation to keep in step with the beats.
 *
 * Not Compose state � the frame driver owns it and reads it every frame (ENGINEERING.md rule 3).
 */
private class WallZoom(start: Float) {
    var current = start
    var target = start

    /**
     * Ease toward [target], frame-rate independently.
     *
     * The `1 - exp(-rate * dt)` form matters: a plain `current += (target - current) * k`
     * converges at a speed that depends on how often it is called, so the same pinch would
     * feel different at 60fps and 120fps � and this app runs at both.
     */
    fun advance(dt: Float) {
        if (dt <= 0f) return
        current += (target - current) * (1f - exp(-ZOOM_EASE_RATE * dt))
    }
}

private class WallSettle {
    /**
     * When the gesture detector last called a fling, and how fast � recorded by `onFling`,
     * consumed by the single settle on release.
     *
     * **`onFling` deliberately does not animate anything itself.** It used to, and so did the
     * raw ACTION_UP handler, which gave the wall two settles racing for the same two
     * `Animatable`s. Their order is not guaranteed � SceneView feeds the gesture detector and
     * the touch hook from the same event stream � so whichever started second cancelled the
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
     * which arrives for taps just as much as for drags � so tapping a case to open it also
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
     * state precisely because the frame loop owns it — a `mutableStateOf` would recompose
     * the screen every time a case opened or closed.
     */
    var dofEnabled = false

    /**
     * The camera's focal plane, in metres.
     *
     * Held here and written to the camera every frame rather than eased from
     * `cameraNode.focusDistance`. Reading it back made the easing depend on the getter
     * returning what the setter stored; when it did not, the value never converged, the
     * focal plane sat a few centimetres from the lens, and the disc — the one thing that
     * must be sharp — was blurred along with everything else.
     */
    var focusDistance = RoomAtmosphere.FOCUS_BROWSING

    /** Drawn frames left before the render scale is sampled again. See [SCALE_SAMPLE_FRAMES]. */
    var scaleSampleIn = 0

    /**
     * The cadence divisor Filament has been told about, so it is told again only when it moves.
     *
     * Zero rather than one so the first frame always publishes, whatever the cadence picks.
     * `setFrameRateOptions` is a JNI call and the divisor changes a handful of times a second at
     * most — writing it every vsync would be the same waste the render-scale sampling avoids.
     */
    var publishedDivisor = 0

    /**
     * When the last *drawn* frame landed, and at which divisor.
     *
     * Both, because the interval between drawn frames only means anything if the promise it is
     * being judged against did not change underneath it. See the `FrameWatch` call.
     */
    var lastDrawnNanos = 0L
    var lastDrawnDivisor = 0
}

/**
 * How many drawn frames between samples of Filament's live render scale.
 *
 * About a second at any of [RoomCadence]'s cadences, which is far finer than the value moves:
 * Filament ramps the scale at its own `scaleRate` over several frames, and this only feeds a
 * readout somebody has to open Settings to look at.
 */
private const val SCALE_SAMPLE_FRAMES = 60

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
 * This keeps DESIGN.md's "one warm lamp over the racks" reading true � there is still one
 * lamp and one pool of light � it just no longer requires the shop to be one metre wide.
 */
/**
 * A shelved album being turned by hand.
 *
 * Deliberately not Compose state, like the other gesture drivers here: turning a case writes
 * a rotation every frame and must not recompose anything (ENGINEERING.md rule 3).
 *
 * Its existence is also what stops the wall panning mid-turn. Horizontal drag means two
 * different things depending on what is under the finger � pan the shelf, or spin the record
 * � and `onScroll` fires regardless of what was picked. So the grab is resolved once in
 * `onMoveBegin`, and `onScroll` stands down for as long as [slot] is non-null.
 */
private class CaseDrag {
    var slot: CaseSlot? = null
    var lastX = 0f
}

private class RoomNodes {
    /**
     * The *carriers* the lights hang from, which is what the frame driver walks along with
     * the browse position. Not the lights themselves — see [keyBulb].
     */
    var keyLight: SceneNode? = null
    var rimLight: SceneNode? = null

    /** The ribbon's per-row carriers, beneath [keyLight]. See [placeRibbon]. */
    val ribbonRows = arrayOfNulls<SceneNode>(RoomLighting.SHELF_LIGHT_COUNT)

    /**
     * The shelf-edge rail's per-row carriers, beside [ribbonRows] and switched with them — the
     * fixture that lights the records themselves. See RoomLighting.RAIL_CANDELA.
     */
    val railRows = arrayOfNulls<SceneNode>(RoomLighting.SHELF_LIGHT_COUNT)

    /**
     * The ceiling spots' carrier. Only its X is written here; its height and depth come from the
     * layout, on a node beneath it, so composition and the frame driver never write the same
     * field. Rebuilt with the rack, which is why [placeSpots] compares identity as well as X.
     */
    var spotRig: SceneNode? = null
    private var placedSpotRig: SceneNode? = null
    private var placedSpotX = Float.NaN

    /**
     * Keeps the ceiling spots over the view — on the ceiling's own grid, never at the raw pan.
     * Trap 5, "a light that is part of the furniture must be positioned by the furniture": the
     * fittings sit every [RoomLighting.CEILING_SPOT_SPACING] along the track, and a pan only
     * swaps which three of them are live, a whole fitting at a time.
     */
    fun placeSpots(panX: Float) {
        val rig = spotRig ?: return
        val pitch = RoomLighting.CEILING_SPOT_SPACING
        val x = kotlin.math.round(panX / pitch) * pitch
        if (rig !== placedSpotRig || x != placedSpotX) {
            rig.position = Position(x, 0f, 0f)
            placedSpotRig = rig
            placedSpotX = x
        }
    }

    private var placedCarrier: SceneNode? = null
    private var placedX = Float.NaN
    private var placedY = Float.NaN

    /**
     * Parks the ribbon on the rack, never on the camera.
     *
     * Horizontally the carrier snaps to the diode pitch, so each diode sits at a fixed place on
     * the shelf and a pan only changes which diodes exist at the far edges of a 51cm window —
     * about 12cm outside a 26cm frame. Vertically it snaps to the midpoint between the two
     * rows either side of the view, so the four ribbon rows are always four real tubes.
     *
     * Rows of the rack that do not exist have their ribbon switched off: above the top shelf it
     * would light the top of the cap, below the bottom one the floor. Hiding a carrier reaches
     * its lights — SceneView's `updateVisibility` walks the children and takes their entities
     * out of the scene, checked in the bytecode.
     *
     * Both writes are skipped when nothing changed, which is almost every frame at rest.
     */
    fun placeRibbon(panX: Float, panY: Float, rowCount: Int) {
        val carrier = keyLight ?: return
        val pitch = RoomLighting.WASH_COLUMN_SPACING
        val x = kotlin.math.round(panX / pitch) * pitch

        // Row centres are ((rowCount - 1) / 2 - k) * ROW_PITCH; see WallLayout.rowCenterY.
        val top = (rowCount - 1) / 2f * ROW_PITCH
        val pair = kotlin.math.floor((top - panY) / ROW_PITCH).toInt()
            .coerceIn(-1, (rowCount - 1).coerceAtLeast(0))
        val y = top - (pair + 0.5f) * ROW_PITCH

        if (carrier !== placedCarrier || x != placedX || y != placedY) {
            carrier.position = Position(x, y, 0f)
            placedCarrier = carrier
            placedX = x
            placedY = y
        }
        // Ribbon row i hangs (1.5 - i) pitches above the carrier: rack row pair - 1 + i.
        //
        // **Switched off by intensity, because hiding never switched a light off** — trap 24,
        // "hiding a node does not switch off a light in it". This wrote `isVisible` and nothing
        // else from the day it was written; SceneView's visibility reaches renderables only, so
        // the ribbon for a row that does not exist kept shining all along. Nobody could tell
        // until the room had a brick wall above the rack for it to land on: a bare line of light
        // one shelf above the cap, a strip's gradient under it, and no strip.
        //
        // Checked every frame against the light's actual intensity rather than a cache of what
        // was last written, because a room change rebuilds these nodes and their lights come
        // back at full. One JNI read per row a frame; the writes only when a row is wrong.
        for (i in 0 until RoomLighting.SHELF_LIGHT_COUNT) {
            val wanted = (pair - 1 + i) in 0 until rowCount
            switchRow(ribbonRows[i], wanted, RoomLighting.WASH_CANDELA)
            switchRow(railRows[i], wanted, RoomLighting.RAIL_CANDELA)
        }
    }

    /** One row of one fixture, on or off — see the note in [placeRibbon] about trap 24. */
    private fun switchRow(node: SceneNode?, wanted: Boolean, candela: Float) {
        node ?: return
        if (node.isVisible != wanted) node.isVisible = wanted
        val probe = node.childNodes.firstOrNull { it is LightNode } as? LightNode ?: return
        if ((probe.intensity > 0f) != wanted) {
            val on = if (wanted) candela else 0f
            node.childNodes.forEach { (it as? LightNode)?.setIntensityCandela(on) }
        }
    }

    /** The scheme these lights are currently showing, so the write is skipped when unchanged. */
    private var appliedScheme: RoomScheme? = null

    /**
     * Writes a colour scheme onto the lights that already exist.
     *
     * This used to recolour the key and the rim, which were the two lights carrying the room's
     * colour. Both are gone; the backlights hanging from [keyLight]'s carrier are what carry it
     * now, and there are `SHELF_LIGHT_COUNT * WASH_COLUMNS` of them rather than one.
     *
     * All of them, not the first one found. An earlier version of this resolved a single bulb
     * per carrier, which was right when there was a single bulb and would silently recolour one
     * fifteenth of the shelf now.
     *
     * Recolouring in place rather than rebuilding is deliberate: rebuilding means destroying and
     * recreating scene nodes, and this is the file where node lifetimes have already cost three
     * separate bugs — trap 1 makes a wholesale scene rebuild a process abort, not a glitch.
     */
    fun applyScheme(scheme: RoomScheme) {
        if (appliedScheme == scheme) return
        // As the room ships, the ribbon has no lights to recolour: its colour is a parameter of
        // the panel's material, set where the panel is built and rebuilt with the scheme. Marked
        // applied, or the walk below would run every frame looking for lights that never come.
        if (!RoomSwitches.ribbon.lights) {
            appliedScheme = scheme
            return
        }
        // Nothing to write to yet means the lights are still being built — the frame driver can
        // run before the first composition of the scene lands — so this returns without marking
        // the scheme applied, and the next frame tries again.
        // Two levels down now: the carrier holds a node per ribbon row, and the rows hold the
        // diodes. A walk of the carrier's direct children finds no lights at all, and would
        // leave the scheme unapplied forever without a word.
        val bulbs = keyLight?.childNodes.orEmpty()
            .flatMap { row -> row.childNodes.filterIsInstance<LightNode>() }
        if (bulbs.isEmpty()) return
        // The tube's colour, as where the diodes are built. The fourth component is unused for a
        // light's colour; SceneView's setter simply takes a Float4 because the rest of its maths
        // does.
        val light = scheme.tubeLight
        val colour = Float4(light[0], light[1], light[2], 1f)
        bulbs.forEach { it.color = colour }
        appliedScheme = scheme
    }
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

    /** How much music the disc has been turned through since the finger landed, in seconds; forwards is positive. */
    var moved = 0.0

    /** What each turn is worth: a record's while scratching, more while travelling. */
    val gearing = Scratch.Gearing()

    /** Where the finger landed: which track, and where in it the room took it to be. */
    var grabIndex = 0
    var grabOffsetMs = 0L

    var cursor = Scratch.Cursor(0, 0L)
    var durations: List<Long> = emptyList()
    var lastSeekAt = 0L
    var lastPlayerSeekAt = 0L
    val haptics = Scratch.HapticAccumulator()
}

/** Mip levels for a [DiscArt.DISC_PX]-square texture: 512, 256, … 1. */
private const val DISC_MIP_LEVELS = 10

/** Built from `app/src/main/materials/dust.mat`; see `docs/materials.md`. */
private const val DUST_MATERIAL_ASSET = "materials/dust.filamat"

/** The volumetric fog. Hand-compiled like dust.filamat; see fog.mat and docs/materials.md. */
private const val FOG_MATERIAL_ASSET = "materials/fog.filamat"

/** The neon sign's tubes. Hand-compiled; see neon.mat and NeonSign. */
private const val NEON_MATERIAL_ASSET = "materials/neon.filamat"

/** The back panel with the ribbon's light in it — see panel.mat and RibbonLight. */
private const val PANEL_MATERIAL_ASSET = "materials/panel.filamat"

/**
 * The sign's mask, filtered and clamped. Lazy for trap 23, like every sampler in this file.
 * Plain LINEAR, no mip filter: the mask is never minified on screen (1024 texels across 24cm,
 * shown at about that), and a mip filter on a texture without mips samples black on some GPUs.
 */
private val NEON_SAMPLER by lazy {
    TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
}

/**
 * The fog samples its noise with wrapping on, so it can scroll forever. Lazy for trap 23 —
 * "never construct a Filament object at file or object level" — a plain `val` here is built
 * before Filament's library loads and crashes the app on launch.
 */
private val FOG_SAMPLER by lazy {
    TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT
    )
}

/**
 * Tells a self-lit material — dust.mat and fog.mat both are — where the room's fittings are and
 * what colour they shine: the strips (one per compartment, repeating every row pitch) and the
 * ceiling spots (repeating every fitting pitch along the track, all aimed the same way). The
 * very numbers the real LightNodes are built from, so a lit mote or a lit wisp of fog is lit by
 * the light that is actually there.
 */
private fun MaterialInstance.setFittingLight(scheme: RoomScheme, rows: List<RowLayout>) {
    val firstRow = rows.firstOrNull()
    val stripY = (firstRow?.centerY ?: 0f) + WallDimens.CASE_H / 2f +
        RoomLighting.SHELF_LIGHT_LIFT - RoomLighting.WASH_DROP
    val rackTopY = (firstRow?.boardY ?: 0f) + ROW_PITCH
    val rackBottomY = (rows.lastOrNull()?.boardY ?: 0f) - BOARD_H / 2f
    val spotY = rackTopY + RoomLighting.CEILING_SPOT_RISE
    // The same axis the spots are built with.
    val aim = normalize(
        Direction(0f, (rackTopY + rackBottomY) / 2f - spotY, WALL_Z - RoomLighting.CEILING_SPOT_Z)
    )
    val strip = scheme.tubeLight
    val lamp = scheme.key
    setParameter("stripColor", strip[0], strip[1], strip[2])
    setParameter("stripYPitch", stripY, ROW_PITCH)
    setParameter("spotColor", lamp[0], lamp[1], lamp[2])
    setParameter("spotYZPitch", spotY, RoomLighting.CEILING_SPOT_Z, RoomLighting.CEILING_SPOT_SPACING)
    setParameter("spotDir", aim.x, aim.y, aim.z)
    setParameter(
        "spotCone",
        kotlin.math.cos(RoomLighting.CEILING_SPOT_OUTER),
        kotlin.math.cos(RoomLighting.CEILING_SPOT_INNER)
    )
}

/**
 * The fog volume's front face: one quad at [RoomAir.FOG_FRONT_Z] facing the camera, the
 * surface every fog ray starts from. uv0 runs 0..1 across it so fog.mat can fade its edges.
 */
private fun buildFogVolume(
    engine: Engine,
    left: Float,
    right: Float,
    bottom: Float,
    top: Float
): Geometry {
    val z = RoomAir.FOG_FRONT_Z
    val facing = Direction(z = 1f)
    val corners = listOf(
        Position(left, bottom, z) to UvCoordinate(0f, 0f),
        Position(right, bottom, z) to UvCoordinate(1f, 0f),
        Position(right, top, z) to UvCoordinate(1f, 1f),
        Position(left, top, z) to UvCoordinate(0f, 1f)
    )
    return Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
        .vertices(corners.map { (p, uv) -> Geometry.Vertex(position = p, normal = facing, uvCoordinate = uv) })
        .indices(listOf(0, 1, 2, 0, 2, 3))
        .build(engine)
}

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

/** The disc pre-cracked into eight wedges, for removing an album. See `DiscardScene`. */
private const val DISC_SHARDS_MODEL_ASSET = "models/disc_shards.glb"

/**
 * The shelved case � a shut jewel case at 768 triangles, against the held case's 2884.
 * Everything the tray carries inside it is enclosed when the lid is down, and the wall
 * draws hundreds of these at once.
 */
private const val CLOSED_CASE_MODEL_ASSET = "models/case_closed.glb"

/**
 * The underlight cone each album levitates in. Geometry rather than a real spot light �
 * Filament will not give every bay its own light, and this is one draw. See build_beam().
 */

/**
 * How many case instances the wall allocates, once, at startup.
 *
 * The pool is bounded by the viewport rather than by the library � `wantedPoolSize` below
 * works out to about 132 slots in portrait on a 1080x2340 screen, and about 468 in
 * landscape, where the wall is nearly four times wider in slot-pitch terms. 512 clears
 * both with room to spare.
 *
 * It has to be a fixed number decided before the first frame. `createInstancedModel` takes
 * its count up front, and the alternative � growing the instance list as the pool grows �
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

/** The glow every album floats over. Emissive geometry, not a light: see `CaseSlot.beam`. */
private const val BEAM_MODEL_ASSET = "models/beam.glb"

/**
 * How long the crate rail stays up after the wall stops moving.
 *
 * **It used to be up permanently**, down the right edge, over whatever artwork happened to be
 * behind it. The owner asked for it to appear on touch instead: "instead being there on the
 * screen all time it appears when u touch the screen slide right or left". Long enough that a
 * pause mid-flip does not blink it away, short enough that it is gone by the time anyone is
 * looking at a record rather than looking *for* one.
 */
private const val RAIL_DWELL_MS = 1_400L

/** How often the rail's own fade clock ticks while it is up. Four a second is plenty. */
private const val RAIL_TICK_MS = 250L

/**
 * Master dust brightness. Tuning knob — dust should suggest air, not snow. At 0.55 the
 * motes read as distinct white specks; this is deliberately below the point where you
 * notice them individually.
 */
private const val DUST_OPACITY = 0.32f

/**
 * How the motes are lit (dust.mat): a faint catch of the lamp colour everywhere, a third of
 * the old constant glow so the lit ones stand out, and then what the fittings add. A mote right
 * under a strip is three times the old glow in the strip's colour; one in the middle of a spot's
 * beam, close to the fitting, is a few times brighter again and crosses bloom's threshold — dust
 * sparkling in a spot, which is what the owner described.
 */
private const val DUST_UNLIT = 0.35f
private const val DUST_STRIP_GAIN = 3.0f
private const val DUST_SPOT_GAIN = 2.2f


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
    onEditAlbum: (String) -> Unit,
    /** Back while browsing, or a tap on the neon sign: the showroom between formats. */
    onOpenShowroom: () -> Unit = {},
    /** ROOMS in the bar: the room switcher (JawonNavHost). */
    onOpenRooms: () -> Unit = {},
    /** Settings' Guide, on this room's page — from the first-run hint. */
    onOpenGuide: () -> Unit = {},
    /**
     * A 2D panel is up over the room (`JawonNavHost`). The room stays composed under it — that is
     * the point, it is not rebuilt on the way back — but it is out of sight, so it stops rendering
     * and its Back handlers stand aside for the panel's.
     */
    covered: Boolean = false,
    viewModel: WallSceneViewModel = hiltViewModel()
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val removed by viewModel.removed.collectAsStateWithLifecycle()
    val hintsSeen by viewModel.hintsSeen.collectAsStateWithLifecycle()
    val roomScheme by viewModel.roomScheme.collectAsStateWithLifecycle()
    val caseSpin by viewModel.caseSpin.collectAsStateWithLifecycle()
    val eqAvailable by viewModel.eqAvailable.collectAsStateWithLifecycle()
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqBands by viewModel.eqBands.collectAsStateWithLifecycle()
    val eqRangeMb by viewModel.eqRangeMb.collectAsStateWithLifecycle()
    val eqPresets by viewModel.eqPresets.collectAsStateWithLifecycle()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val nowPlayingAlbumId by viewModel.nowPlayingAlbumId.collectAsStateWithLifecycle()
    val isPlaying = deck.isPlaying
    val context = LocalContext.current

    // A room rebuilt while a record played on without it — the activity recreated, the service
    // still going — should still know what is on: its case proud, its bar at the foot (0.7.3).
    LaunchedEffect(Unit) { viewModel.adoptPlayingAlbum() }
    // For handlers set up once, which must read whether the room is on screen as it is now.
    val currentCovered = androidx.compose.runtime.rememberUpdatedState(covered)
    // Under the opening card the room is built and then rests until the card is about to open.
    val parked = com.jawon.app.ui.common.LocalRoomParked.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CrateBlack)
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val aspect = constraints.maxWidth.toFloat() / viewportHeightPx

        /**
         * How far back the camera stands while browsing � the zoom.
         *
         * A camera dolly, not a scale. That is the difference between the request ("make it
         * more satisfying, not like a website page zoomed in/out") and the thing it was
         * warning against: scaling enlarges a flat image, while moving the lens through the
         * room changes the perspective, so the cases nearer the middle grow faster than the
         * ones at the edges and the shelf boards fan out. The room is 3D; the zoom should use
         * that rather than paper over it.
         *
         * An `Animatable` because the open sequence eases it back to the canonical distance �
         * see [OPEN_DURATION_MS] at the call site � and because it is a stable object the
         * gesture callbacks can read live, rather than a value they would capture once.
         */
        val zoom = remember { WallZoom(CAMERA_Z) }
        // Whether there is a showroom to go to — see the pinch's note above ZOOM_GAIN.
        val showroomReachable = remember { Exhibit.reachable(BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS) }

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
            // Bounds are widened to include wherever the camera already is.
            //
            // `updateBounds` clamps the current value on the spot, so tightening them under
            // the user yanks the wall sideways � which is what happened after removing an
            // album near either end: the shelf got shorter and the view jumped. Nothing
            // should move the wall except the user's own hand and the magnet.
            //
            // Letting it sit briefly outside the content is harmless: the magnet settles onto
            // a real record on the next release, which brings it back on its own.
            panX.updateBounds(
                minOf(layout.minPanX, panX.value),
                maxOf(layout.maxPanX, panX.value)
            )
            panY.updateBounds(
                minOf(layout.minPanY, panY.value),
                maxOf(layout.maxPanY, panY.value)
            )

            // Open centred on a record, not mid-gap.
            //
            // Pan starts at 0, which is the wall's *origin* and not any album's position �
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

        val engine = rememberRoomEngine() // not SceneView's: see RoomEngine, its teardown leaked the GL context
        val materialLoader = rememberMaterialLoader(engine)
        val environmentLoader = rememberEnvironmentLoader(engine)
        val viewNodeManager = rememberViewNodeManager()

        // How much rendering this device gets. See RoomQuality for why this both guesses and
        // measures rather than only guessing.
        //
        // Two values, deliberately. `initialTier` is fixed for the session and drives the
        // things that are baked once � the IBL cubemap and the shadow map. `tier` is state
        // and can step down mid-session, driving everything on the Filament View, which is
        // where the per-frame cost actually is.
        val sleepMode by viewModel.sleepMode.collectAsStateWithLifecycle()
        val sleepRemainingMs by viewModel.sleepRemainingMs.collectAsStateWithLifecycle()
        val diagnostics = viewModel.diagnostics
        val scratchAudio = viewModel.scratchAudio
        val initialTier = remember(context) { QualityTier.detect(context) }
        // Reported so a tester can photograph what the room actually chose � see
        // RoomDiagnostics for why that is worth a Settings section.
        remember(initialTier) { diagnostics.onTierDetected(initialTier); initialTier }
        var tier by remember { mutableStateOf(initialTier) }
        val frameWatch = remember { FrameWatch() }

        // The room's image-based lighting, generated rather than loaded — see RoomLighting.
        // Filament's own prefilter turns the procedural cubemap into a roughness mip chain.
        //
        // **Keyed on the light too (0.7.2).** It was made once, from the light the room was built
        // in, and that never mattered while the room was rebuilt behind every visit to Settings.
        // Kept alive, the room went on reflecting Officina's green after the owner had switched to
        // another mode. The one it replaces is destroyed half a second later, by when the scene
        // has taken the new one — never while the scene might still be sampling it.
        val environment = remember(engine, environmentLoader, roomScheme) {
            RoomLighting.createEnvironment(engine, initialTier.cubemapPx, roomScheme) { cubemap ->
                environmentLoader.iblPrefilter.specularFilter(cubemap)
            }
        }
        DisposableEffect(environment) {
            onDispose {
                val retired = environment
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { runCatching { engine.safeDestroyEnvironment(retired) } },
                    ENVIRONMENT_RETIRE_MS
                )
            }
        }

        // Fog, bloom, depth of field and vignette all live on Filament's View. Named to
        // keep it apart from the Android View that LocalView supplies for haptics.
        val filamentView = rememberView(engine)

        // The renderer, held here rather than left to SceneView's own default, for one reason:
        // the frame driver needs a handle on it to decide whether a given vsync is drawn at all.
        // See RoomCadence, and the tail of `onFrame` where the two meet. Constructed exactly as
        // SceneView would construct it, and disposed by the same `remember`.
        val renderer = rememberRenderer(engine)
        // Told what the panel is actually doing, rather than assuming the test device's 120.
        // Every interval in the cadence is derived from this; see RoomCadence.refreshHz for what
        // went wrong while it was assumed.
        val displayRefreshHz = LocalContext.current.displayRefreshHz(RoomCadence.DEFAULT_REFRESH_HZ)
        val cadence = remember { RoomCadence() }
        // The wall's idle life, on a clock that winds down when nothing happens. See RoomSettle.
        val idleLife = remember { RoomSettle() }
        cadence.refreshHz = displayRefreshHz
        cadence.maxFps = initialTier.maxFps
        cadence.turningFps = if (GraphicsChoice.current == Graphics.BEST) RoomCadence.TURNING_FPS
        else RoomCadence.TURNING_BALANCED_FPS

        // **Tell Filament what a frame is actually worth, because nothing else does.**
        //
        // Filament's dynamic resolution scales the room's render target to hold a frame-time
        // budget, and it derives that budget from `DisplayInfo.refreshRate` and
        // `FrameRateOptions.interval`. Neither was ever set, so it ran on the defaults — 60Hz
        // and "the app renders every display frame" — in an app that asks for 120 and then
        // deliberately draws one vsync in eight (see [RoomCadence]).
        //
        // The consequence is not a small mis-tuning. `interval` *is* the cadence's divisor by
        // another name, and with it stuck at 1 the deliberate skipping is indistinguishable
        // from being late: the room has sat pinned at `minRenderScale` and never once reached
        // the 0.75 ceiling `RoomQuality.maxRenderScale` calls "the one number to turn".
        //
        // The refresh rate is read from the display rather than assumed, because
        // `preferredRefreshRate` in `MainActivity` is a *request* the platform may refuse —
        // asking the panel what it settled on is the only honest source.
        val panelRefreshHz = LocalView.current.display?.refreshRate ?: 60f
        remember(renderer, panelRefreshHz) {
            renderer.displayInfo = Renderer.DisplayInfo().apply { refreshRate = panelRefreshHz }
            panelRefreshHz
        }

        // Filament writes the room's last render scale into this rather than returning it, so
        // it is allocated once and reused — the frame driver must not allocate. Two entries,
        // x and y; `homogeneousScaling` keeps them equal, so only [0] is ever read.
        val renderScaleOut = remember { FloatArray(2) }
        // Applied during composition, NOT from a LaunchedEffect.
        //
        // A LaunchedEffect runs after composition commits, so the first frames rendered with
        // Filament's stock View options � no fog, no vignette, no ambient occlusion, bloom
        // unthresholded. Those four are most of what makes this room dark and grounded, so
        // launching the app flashed a flat, brightly-lit brown wall for a frame or two
        // before the atmosphere arrived and everything dropped into place.
        //
        // `remember` runs while composing, which is before the renderer's first frame.
        //
        // Keyed on the tier as well, so a mid-session step down re-applies the whole post
        // chain. `RoomAtmosphere.apply` replaces each option struct wholesale rather than
        // mutating it, so running it again is safe.
        //
        // The block returns the tier it applied, and that is not decoration. Lint rejects a
        // `remember` returning Unit, on the grounds that composition can fail or restart and
        // leave an object mutated by a composition that never committed. The objection is
        // right in general and does not bite here: the target is a long-lived Filament View
        // rather than composition state, and `apply` is idempotent — an abandoned composition
        // leaves the same settings a committed one would. Returning the tier says what the
        // remembered value actually is instead of silencing the rule with a discard.
        // Owns the room's colour grading for the life of this scene — see [RoomGrading] for why
        // it is a holder rather than a value, and why the old grade is released only after the
        // new one is bound.
        val grading = remember { RoomGrading() }

        // The lens dirt, built off the main thread — a few million falloff evaluations is too
        // much work to put in front of the first frame — and uploaded here once it exists.
        // State rather than a plain value so the frame driver below reads the texture that is
        // current, not the null it was composed with. See RoomAir.dirt.
        var lensDirt by remember { mutableStateOf<Texture?>(null) }
        LaunchedEffect(engine) {
            val dirt = withContext(Dispatchers.Default) { RoomAir.dirt() }
            lensDirt = ImageTexture.Builder()
                .bitmap(dirt, TextureType.DATA)
                .build(engine)
        }

        remember(filamentView, tier, lensDirt) {
            RoomAtmosphere.apply(filamentView, tier, lensDirt)
            tier
        }

        // Keyed on the scheme as well, because the grade is part of the mode: Vesper is graded
        // warm and Officina is not, and a single grade would break three of the four. Applied
        // during composition for the same reason the atmosphere above is — a LaunchedEffect
        // lands after the first frames are already on screen, and an ungraded flash is exactly
        // the flat brown wall that note is about.
        remember(filamentView, engine, roomScheme) {
            grading.bind(filamentView, engine, roomScheme)
            roomScheme
        }


        // DESIGN.md §4: "Respect reduced-motion settings: drop ambient rotation, parallax,
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
            // **Clip planes, set explicitly — this is a depth-precision fix, not tuning.**
            //
            // The reported symptom was that cover art vanished at grazing angles and that the
            // angle at which it vanished got *wider the further you zoomed out*. That second
            // half is the diagnosis: a culling or facing problem has a fixed angle, and only
            // depth precision changes with distance.
            //
            // The artwork is a plate sitting 0.6mm behind the lid's outer face. Whether it or
            // the lid wins the depth test at that separation depends entirely on how finely
            // the depth buffer can resolve at that distance — and a depth buffer's resolution
            // is hyperbolic in the near:far ratio, so it collapses as things get further away.
            // Left at the default near plane the ratio is in the thousands, which cannot
            // reliably separate two surfaces 0.6mm apart at arm's length, let alone across a
            // room. At grazing angles the separation along the view ray shrinks further, which
            // is why the two symptoms arrived together.
            //
            // This room is measured in centimetres and the camera never gets closer than about
            // 0.3m to anything: the disc sits 0.55m away at its nearest, and a case rises only
            // RISE_Z toward the lens. A 0.12m near plane is therefore free, and it buys back
            // more than an order of magnitude of precision exactly where it was being lost.
            //
            // As *properties*, not `setProjection`. `WallDimens.FOCAL_LENGTH_MM` records that
            // SceneView re-runs `updateProjection()` on every surface resize and discards a
            // directly-set projection; these two are the inputs that call reads, so they
            // survive a rotation or a window resize where a direct call would silently revert.
            near = NEAR_PLANE
            far = FAR_PLANE
            // Seeded before the first frame, not just eased toward in onFrame. Filament's
            // depth-of-field pass asserts a positive focus distance, and a camera starts at
            // zero — enabling DOF without this aborts the process on the first frame.
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
        // One tick per album the wall moves past. See PanDetents.
        val panDetents = remember { PanDetents() }
        // `elapsedRealtime` until which the crate rail stays on screen. The owner asked for it
        // to stop living over the artwork: "instead being there on the screen all time it
        // appears when u touch the screen slide right or left".
        var railVisibleUntil by remember { mutableLongStateOf(0L) }
        val discardNodes = remember { DiscardNodes() }
        /** Compose-visible mirror of `discardNodes.albumId`, so artwork effects can key on it. */
        var discardingAlbumId by remember { mutableStateOf<String?>(null) }

        /** The album a long press has proposed removing, until it is confirmed or dismissed. */
        var pendingDelete by remember { mutableStateOf<CaseLayout?>(null) }

        // Which page of the deck is showing. Held here rather than inside DeckOverlay because
        // the panel's height depends on it. Reset when the deck closes, so opening the next
        // record lands on its tracklist rather than wherever the last one was left.
        var deckPage by remember { mutableIntStateOf(PAGE_TRACKS) }
        LaunchedEffect(selectedAlbumId) { if (selectedAlbumId == null) deckPage = PAGE_TRACKS }

        // How tall the deck panel is: three stops, dragged by its header. See DeckDetent.
        var detent by remember { mutableStateOf(DeckDetent.DECK) }
        val sheetFraction = remember { Animatable(DeckDetent.DECK.fraction) }
        val screenHeightPx = with(LocalDensity.current) {
            LocalConfiguration.current.screenHeightDp.dp.toPx()
        }
        // Settles to the chosen stop once the finger is off. Slightly underdamped, so it arrives
        // with a suggestion of weight instead of easing politely to a halt — the same reason the
        // case's open sequence has beats rather than a single curve.
        LaunchedEffect(detent) {
            sheetFraction.animateTo(
                detent.fraction,
                spring(dampingRatio = 0.82f, stiffness = 620f)
            )
        }
        // Opening the next record should not inherit the height the last one was left at.
        LaunchedEffect(selectedAlbumId) { if (selectedAlbumId == null) detent = DeckDetent.DECK }
        // Goes to the vibrator directly rather than through the view's haptic feedback �
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


        // The case and disc, built by tools/models/build_models.py and loaded from assets.
        //
        // Three files rather than one, because ActiveCaseNodes already has the hierarchy
        // they belong in � tray under the case root, lid under the hinge � and one
        // instance per file drops straight into it with no reparenting.
        //
        // These arrive asynchronously: each is null for the first frames and then becomes
        // non-null once, which is what ActiveCaseNodes' mount guard is written against.
        val modelLoader = rememberModelLoader(engine)

        // The wall's shelved cases: ONE asset, MAX_CASE_SLOTS instances sharing its
        // geometry, material and textures � each instance is little more than a transform.
        //
        // Loaded synchronously, unlike the three above, and that is deliberate. `bind()`
        // writes through the slot handles from a LaunchedEffect keyed on the pool size; if
        // the instances arrived a few frames late those handles would still be null when it
        // first ran, which is exactly the bug the active case hit. The asset is 20KB and the
        // dust material already loads this way.
        //
        // Allocated once at MAX_CASE_SLOTS and never re-created. The pool grows at runtime,
        // and re-keying anything on its size would drop every slot composable out of
        // composition and destroy its ImageNodes � the process abort this file exists to
        // prevent. So the count is fixed up front and the pool is clamped to it.
        val beamInstances = remember(modelLoader) {
            modelLoader.createInstancedModel(BEAM_MODEL_ASSET, MAX_CASE_SLOTS)
        }
        val caseInstances = remember(modelLoader) {
            modelLoader.createInstancedModel(CLOSED_CASE_MODEL_ASSET, MAX_CASE_SLOTS)
        }

        val trayModel = rememberModelInstance(modelLoader, TRAY_MODEL_ASSET)
        val lidModel = rememberModelInstance(modelLoader, LID_MODEL_ASSET)
        val discModel = rememberModelInstance(modelLoader, DISC_MODEL_ASSET)
        // The props. One instance each: three objects that never move, so nothing here needs
        // the pooling the wall's cases do. See RoomProps.
        val turntableModel = rememberModelInstance(modelLoader, RoomProps.TURNTABLE_ASSET)
        val clockModel = rememberModelInstance(modelLoader, RoomProps.CLOCK_ASSET)
        val plantModel = rememberModelInstance(modelLoader, RoomProps.PLANT_ASSET)
        val shardsModel = rememberModelInstance(modelLoader, DISC_SHARDS_MODEL_ASSET)

        // Every jewel case's shell made clear — the shelf's and the held lid's. See SHELL_TINT.
        // Once each: the instances live for the scene, and the held lid arrives when it loads.
        remember(caseInstances) {
            caseInstances.forEach { clearTheShell(it.materialInstances) }
            caseInstances
        }
        LaunchedEffect(lidModel) {
            lidModel?.let { clearTheShell(it.materialInstances) }
        }

        // Sized to the wall so density holds as the library grows, and rebuilt only when
        // those dimensions actually change — which is when albums are added, not per frame.
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

        // **What lights the dust**: the strips and the ceiling spots, in this room's colours and
        // at the rack's own positions, so a mote glints where a real one would — under a strip,
        // or crossing a spot's beam. See dust.mat for why it is worked out there rather than by
        // Filament's lights. Rewritten when the room or the rack's shape changes, never per frame.
        remember(dustMaterial, roomScheme, layout.rows) {
            val lamp = roomScheme.key
            dustMaterial.setFittingLight(roomScheme, layout.rows)
            dustMaterial.setParameter(
                "tint",
                lamp[0] * DUST_UNLIT,
                lamp[1] * DUST_UNLIT,
                lamp[2] * DUST_UNLIT
            )
            dustMaterial.setParameter("gain", DUST_STRIP_GAIN, DUST_SPOT_GAIN)
            roomScheme
        }

        // **The volumetric fog** — see fog.mat. One material instance for the life of the
        // scene, fed the same fittings as the dust, and one quad at the front of the fog volume
        // covering everywhere the camera can reach. Nothing at all on LOW, which has no steps.
        // Zero steps is no fog: the node stays hidden and the frame driver writes nothing.
        val fogSteps = if (RoomAir.FOG_ENABLED) initialTier.fogSteps else 0
        val fogNoise = remember(engine) {
            ImageTexture.Builder()
                .bitmap(RoomAir.fogNoise(), TextureType.DATA)
                .build(engine)
        }
        val fogMaterial = remember(materialLoader) {
            val material = materialLoader.createMaterial(FOG_MATERIAL_ASSET)
            materialLoader.createInstance(material).apply {
                setParameter("noise", fogNoise, FOG_SAMPLER)
                setParameter("time", 0f)
                setParameter("wind", 0f, 0f)
                setParameter("stir", 0f)
                setParameter("opacity", if (fogSteps > 0) RoomAir.FOG_DENSITY else 0f)
                setParameter("stepsBack", fogSteps.toFloat(), RoomAir.FOG_BACK_Z)
                setParameter(
                    "gain",
                    RoomAir.FOG_STRIP_GAIN,
                    RoomAir.FOG_SPOT_GAIN,
                    RoomAir.FOG_EXTINCTION
                )
            }
        }
        remember(fogMaterial, roomScheme, layout.rows) {
            val lamp = roomScheme.key
            fogMaterial.setFittingLight(roomScheme, layout.rows)
            fogMaterial.setParameter(
                "base",
                lamp[0] * RoomAir.FOG_AMBIENT,
                lamp[1] * RoomAir.FOG_AMBIENT,
                lamp[2] * RoomAir.FOG_AMBIENT
            )
            roomScheme
        }
        val fogAir = remember { FogAir() }
        val fogGeometry = remember(engine, layout.minPanX, layout.maxPanX, layout.minPanY, layout.maxPanY, halfW, halfH) {
            buildFogVolume(
                engine,
                left = layout.minPanX - halfW - RoomAir.FOG_FRONT_Z,
                right = layout.maxPanX + halfW + RoomAir.FOG_FRONT_Z,
                bottom = layout.floorY,
                top = layout.maxPanY + halfH + RoomAir.FOG_FRONT_Z
            )
        }
        DisposableEffect(fogGeometry) {
            onDispose { engine.safeDestroyGeometry(fogGeometry) }
        }

        val fonts = remember(context) { CaseArt.Fonts(context) }

        // **The neon JAWON sign** — see NeonSign. Its material is loaded once; its mask is drawn
        // off the main thread from the app's own display face and uploaded when it is ready,
        // and the sign simply is not there until then.
        val neonMaterial = remember(materialLoader) {
            materialLoader.createMaterial(NEON_MATERIAL_ASSET)
        }
        // **The records that hang framed on the brick** — the ones he plays, by the same wear
        // the wall already ages the cases by. Their covers are decoded off the main thread (a
        // file read and a bitmap decode; architecture rule 9) and uploaded once. See WallDecor.
        val framedChoice = remember(layout.cases) { WallDecor.chooseAlbums(layout.cases) }
        var framedAlbums by remember { mutableStateOf<List<FramedAlbum>>(emptyList()) }
        LaunchedEffect(engine, framedChoice) {
            framedAlbums = framedChoice.mapNotNull { case ->
                val cover = withContext(Dispatchers.IO) {
                    CaseArt.loadCoverSquare(case.coverPath)
                } ?: return@mapNotNull null
                FramedAlbum(
                    case.albumId,
                    ImageTexture.Builder().bitmap(cover, TextureType.COLOR).build(engine)
                )
            }
        }

        // **The paper taped to the brick beside them.** Deliberately a different set of
        // albums from the framed ones — a shop's wall is not six copies of the same six
        // records — so this takes the next lot down the same wear ordering. Baked off the main
        // thread: a decode, a duotone pass over 180k pixels and a few thousand grain specks is
        // not work for the frame this is composed on (architecture rule 9). See GigPoster.
        val posterChoice = remember(layout.cases) { WallDecor.choosePosters(layout.cases) }
        var posters by remember { mutableStateOf<List<Poster>>(emptyList()) }
        LaunchedEffect(engine, posterChoice, fonts) {
            posters = posterChoice.map { case ->
                val sheet = withContext(Dispatchers.IO) {
                    val cover = CaseArt.loadCoverSquare(case.coverPath)
                    GigPoster.bake(
                        artist = case.artist,
                        title = case.title,
                        hue = case.storedHue.takeIf { it > 0f } ?: case.fallbackHue,
                        cover = cover,
                        display = fonts.display,
                        mono = fonts.mono
                    )
                }
                Poster(
                    case.albumId,
                    ImageTexture.Builder().bitmap(sheet, TextureType.COLOR).build(engine)
                )
            }
        }

        var neonMask by remember { mutableStateOf<Texture?>(null) }
        LaunchedEffect(engine, fonts) {
            val face = fonts.display ?: return@LaunchedEffect
            val mask = withContext(Dispatchers.Default) { NeonSign.mask(face) }
            neonMask = ImageTexture.Builder()
                .bitmap(mask, TextureType.DATA)
                .build(engine)
        }
        // The view model's, so a rebuilt room finds its art already baked (see caseArtCache).
        val artCache = remember(fonts) {
            viewModel.caseArtCache?.takeIf { it.fonts == fonts }
                ?: CaseArtCache(fonts, backInlay = { viewModel.backInlay(it) })
                    .also { viewModel.caseArtCache = it }
        }

        // Push the open album's art onto the permanently-mounted active case, and hide the
        // whole group when nothing is open. Writing `bitmap` re-uploads into the node's
        // existing texture; rebuilding the node instead would abort the process.
        // Which album the held case is currently showing.
        //
        // Not just the open one any more: removing an album also drives this case � see
        // `DiscardScene` � and it has to be wearing that album's cover and spine while it is
        // taken off the shelf, not the last-opened album's.
        val heldAlbumId = selectedAlbumId ?: discardingAlbumId
        val activeArtwork = heldAlbumId?.let { artCache[it] }
        // Keyed on the models as well as the artwork, because the nodes these write to do
        // not exist until the models land. Without that key the effect fires once against
        // null handles, does nothing, and never runs again � leaving a case that opens
        // blank whenever the art resolved faster than the geometry.
        LaunchedEffect(activeArtwork, actNodes, trayModel, lidModel) {
            activeArtwork?.spine?.let { actNodes.spineArt?.bitmap = it }
            activeArtwork?.cover?.let {
                actNodes.coverArt?.bitmap = it
                // The inside of the lid wears the same art. See ActiveCaseNodes.coverBack.
                actNodes.coverBack?.bitmap = it
            }
            // The held record's underlight takes its album's colour, exactly as the shelf's do.
            activeArtwork?.hue?.let { actNodes.setUnderHue(it) }
        }
        LaunchedEffect(heldAlbumId, selectedAlbumId, actNodes, trayModel, lidModel, discModel) {
            val visible = heldAlbumId != null
            actNodes.root?.isVisible = visible
            // The whole disc belongs to the open sequence only. A removal shows the fragment
            // set in its place from the moment the lid opens, so an intact disc here would
            // sit inside the broken one.
            actNodes.disc?.isVisible = selectedAlbumId != null
            // And their lights with them — visibility alone never reached a light (trap 24).
            actNodes.setLightsOn(case = visible, disc = selectedAlbumId != null)
            // Hidden is not the same as unpickable. The disc is mounted for the life of
            // the scene, so when it is hidden it stays parked in front of whichever case
            // was last open — and SceneView still hit-tests it. Left touchable it both
            // swallowed taps meant for that case and, worse, answered them with its own
            // node name, which the browse path took for an album id.
            actNodes.discMesh?.isTouchable = visible
        }

        // The disc's printed face.
        //
        // ONE texture, allocated once for the life of the scene, re-uploaded per album — the
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
        // KHR_materials_clearcoat � the lacquer over the print, which is most of what makes
        // the face read as a disc rather than as a sticker. SceneView's stock material has
        // no clearcoat parameter at all (see docs/materials.md), so calling
        // `setMaterialInstance` here would silently throw the coat away.
        //
        // `baseColorMap` is gltfio's ubershader name for the base colour sampler. It only
        // does anything because the model ships a placeholder baseColorTexture: gltfio keys
        // the shader variant on which slots the glTF material declares, and a material
        // exported without one compiles to a shader that never samples. See
        // `attach_placeholder_basecolor` in the build script.
        // Hoisted, because the discard's shards need the same one � they carry the disc's UV
        // convention and sample the same texture.
        val sampler = remember {
            TextureSampler(
                TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE
            )
        }
        LaunchedEffect(discModel, discTexture, actNodes) {
            actNodes.discMesh?.materialInstances?.forEach { primitives ->
                primitives.forEach { instance ->
                    instance.setParameter("baseColorMap", discTexture, sampler)

                    // Make the label read as PRINT, not as polished metal.
                    //
                    // The disc ships as a glossy, metallic-ish material � reasonable for a CD
                    // � and that is why the artwork looked washed out and whitish however
                    // much contrast was baked into it. A metal has no diffuse response: its
                    // base colour only tints what it reflects, and what this disc reflects is
                    // an IBL with the shop's lamp burned in at RoomLighting.LAMP_HOT_LEVEL,
                    // which is 26. Any picture painted onto that surface loses to the lamp.
                    //
                    // The label side of a real CD is ink on plastic under lacquer, so it is
                    // not metal. Dropping metallic gives the artwork its diffuse response
                    // back; the roughness lift spreads the lamp's highlight instead of
                    // letting it blow out one patch, and the clearcoat stays on but much
                    // lower � enough to read as lacquer, not enough to whiten the print.
                    //
                    // Guarded by name because these come from the glTF asset rather than a
                    // .mat in this repo, and Filament logs and ignores an unknown parameter
                    // rather than failing, which would leave this silently doing nothing.
                    // Names verified against the material's own parameter list rather than
                    // guessed: it is gltfio's `base_lit_opaque`, and all four of these exist
                    // on it. Worth knowing, because Filament ignores an unknown parameter
                    // silently, so a typo here would look exactly like a setting that had no
                    // effect � which is how two rounds of this went by.
                    val material = instance.material
                    if (material.hasParameter("metallicFactor")) {
                        instance.setParameter("metallicFactor", DISC_LABEL_METALLIC)
                    }
                    if (material.hasParameter("roughnessFactor")) {
                        instance.setParameter("roughnessFactor", DISC_LABEL_ROUGHNESS)
                    }
                    if (material.hasParameter("clearCoatFactor")) {
                        instance.setParameter("clearCoatFactor", DISC_LABEL_CLEARCOAT)
                    }
                    // The one that was missing. On a non-metal, specular comes from here and
                    // not from metallicFactor at all.
                    if (material.hasParameter("reflectance")) {
                        instance.setParameter("reflectance", DISC_LABEL_REFLECTANCE)
                    }
                    // **The print is ink on plastic, not a lightbox.**
                    //
                    // The owner, twice: the spinning disc looks washed. Two guesses at the
                    // lighting were both wrong and both measured wrong — cutting its light by
                    // a third and stripping the label's sheen moved the disc's mean from 139
                    // to 134, which is nothing. What is actually bright is the ARTWORK: a
                    // sleeve printed on a disc keeps its whites, a quarter of this one sits
                    // above 235 of 255, and bloom spreads out of those whites across the
                    // detail beside them. No lighting change can fix a texture that is
                    // already near white, which is why neither did.
                    //
                    // So the label is multiplied down. A CD label is ink on a silvered disc
                    // and is never brighter than the paper sleeve in the rack beside it.
                    if (material.hasParameter("baseColorFactor")) {
                        instance.setParameter(
                            "baseColorFactor",
                            DISC_LABEL_PRINT, DISC_LABEL_PRINT, DISC_LABEL_PRINT, 1f
                        )
                    }
                }
            }
        }

        // The disc face, finally: `Album.discFaceUri` has been collected since M1 and never
        // shown. Baked off the main thread (ENGINEERING.md rule 5), then uploaded into the
        // texture that already exists rather than building a new one.
        //
        // Only the open album is baked — unlike spine and cover art there is no reason to
        // pre-load the neighbours, because exactly one disc is ever on screen.
        // Keyed on the *held* album, not the open one: a removal puts this album's fragments
        // on screen, and they share this texture. Without it the pieces would wear whichever
        // album happened to be opened last.
        val heldCase = remember(layout, heldAlbumId) {
            heldAlbumId?.let { id -> layout.cases.firstOrNull { it.albumId == id } }
        }
        LaunchedEffect(heldCase, discTexture) {
            val case = heldCase ?: return@LaunchedEffect
            val face = withContext(Dispatchers.IO) {
                DiscArt.bakeFace(
                    face = CaseArt.loadCover(case.discFacePath, DiscArt.DISC_PX),
                    // Loaded fresh at the disc's own resolution, NOT taken from the art cache.
                    //
                    // The cache holds the *case* cover, baked at CaseArt.COVER_PX � 256px,
                    // which is right for a case 10mm wide on a shelf and badly wrong for a
                    // disc filling the screen. Reusing it meant that every album without its
                    // own uploaded disc face � now the common case, since covers arrive
                    // automatically from the file tags � had its label upscaled 4� from a
                    // thumbnail. That is what "the quality on the CD art is bad" was.
                    fallback = CaseArt.loadCover(case.coverPath, DiscArt.DISC_PX)
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

        // The case pool. Sized to the same window `visibleCases` uses � about 21 slots
        // across by 5 rows at this camera � and capped to the library so a three-album
        // shelf does not mount a hundred hidden slots and their textures.
        //
        // It only ever GROWS. Adding slots is free; removing them would destroy their
        // ImageNodes, which is the abort this whole pool exists to avoid.
        val caseSlots = remember { CaseSlots() }
        // The case plates' own material for the shelf-edge rail's light, when they carry it
        // rather than its heads — see RailPlates and RoomSwitches.Rail.
        val railScope = rememberCoroutineScope()
        val railPlates = remember(engine, materialLoader) { RailPlates(engine, materialLoader, railScope) }
        var casePoolSize by remember { mutableIntStateOf(0) }
        val wantedPoolSize = remember(layout, halfW, halfH) {
            val across = ((halfW + SLOT_PITCH * 3f) * 2f / SLOT_PITCH).toInt() + 2
            val down = ((halfH + ROW_PITCH) * 2f / ROW_PITCH).toInt() + 2
            // Clamped to the instances actually allocated � indexing past them would throw.
            (across * down).coerceAtMost(layout.cases.size).coerceAtMost(MAX_CASE_SLOTS)
        }
        if (wantedPoolSize > casePoolSize) casePoolSize = wantedPoolSize

        // Point the slots at whatever is on screen. Recomposes when the visible set changes
        // or when art finishes loading � CaseArtCache is state-backed, so reading it here
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
            // The open sequence's framing is tuned against the canonical CAMERA_Z � see
            // OpenSequence.FRONT_GAP � so a case opened from a zoomed-out wall has to arrive
            // back there. Just retargeting the zoom does it: the same per-frame easing that
            // smooths a pinch carries the camera home, well inside the sequence, with no
            // separate animation to keep in step with the beats.
            zoom.target = CAMERA_Z
        }

        fun closeCase() {
            sequence.direction = -1
            // Not a stop since 0.7.3: the record plays on (or pauses, by the owner's choice) and
            // the bar at the foot of the wall carries it. See NowPlayingBar.
            viewModel.onCaseClosed()
        }

        /**
         * Starts taking [case] off the shelf: hands its pool slot to [CaseDiscard] and defers
         * the actual database delete until that finishes.
         *
         * **Why deferred rather than immediate.** See `CaseSlot.discardClock`'s KDoc � deleting
         * up front would drop the album from `layout` on the next recomposition and let `bind`
         * re-home this exact pool index to a different, still-living record while the case was
         * still mid-flight.
         */
        fun discardCase(case: CaseLayout) {
            val slot = caseSlots.slots.firstOrNull { it.body?.name == case.albumId }
            if (slot == null || discardNodes.isRunning) {
                // Nothing on screen to animate, or one is already running � the discard owns
                // a single set of nodes, so a second would fight it for them. Delete outright.
                // Through the same undo window as the animated removal.
                viewModel.scheduleDiscard(case.albumId, 0L)
                return
            }
            // The shelved case hides for the whole removal and the *held* case takes over
            // from its slot � the same handover the open sequence does, so an album is never
            // on screen twice.
            slot.discardClock = 0f
            slot.root?.isVisible = false
            slot.setBeamVisible(false)
            discardNodes.albumId = case.albumId
            discardNodes.slotX = slot.baseX
            discardNodes.slotY = slot.baseY
            discardNodes.clock = 0f
            discardingAlbumId = case.albumId

            // A small margin over the animation's own length, so the visual always finishes
            // before the album vanishes from the database � never the reverse. Scheduled on
            // the view model, not here � see `scheduleDiscard`'s KDoc for why.
            viewModel.scheduleDiscard(
                case.albumId, (CaseDiscard.DURATION_S * 1000L).toLong() + 80L
            )
        }

        /** A tap while a case is breaking apart � finish it now, don't make them wait. */
        fun skipDiscard(albumId: String) {
            if (discardNodes.albumId != albumId) return
            viewModel.skipDiscard(albumId)
            // Jumped past the end so the frame loop's own `done` path retires everything on
            // the next frame, rather than duplicating that cleanup here.
            discardNodes.clock = CaseDiscard.DURATION_S + 1f
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
            scrubReadout.value = null
        
            // **Let the platter go, and the scratch gives the record back** (0.8): wound up
            // to speed — or spun back, if it was thrown — and handed to the player in step,
            // so the music returns out of the wind-up instead of after a gap. The room only
            // says where the finger left the disc, for the cases there is nothing to wind up
            // with: turned off the end of the track, or a file that would not decode.
            scratchAudio.release(
                scratch.cursor.trackIndex,
                scratch.cursor.offsetMs,
                // A disc that was being spun to find a place is set down there: the music is at
                // that place and at speed at once, with nothing run on past it.
                placed = scratch.gearing.travelling
            )
        }

        val gestureListener = rememberOnGestureListener(
            onDown = { e, node ->
                scope.launch { panX.stop() }
                scope.launch { panY.stop() }
                // Each new touch starts as "not a browse" until onScroll says otherwise.
                settle.panned = false

                // Grabbing the platter happens HERE, on the touch landing � not in
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
                        // music holds with it, for as long as the finger is down � which is
                        // both what a turntable does and what makes scrubbing legible,
                        // because you are no longer hearing playback fight the scrub.
                        //
                        // Remembered rather than assumed: releasing must restore what was
                        // true before the grab, or grabbing a paused disc would start it.
                        scratch.moved = 0.0
                        scratch.gearing.reset()
                        scratch.grabIndex = scratch.cursor.trackIndex
                        scratch.grabOffsetMs = scratch.cursor.offsetMs
                        scratch.lastPlayerSeekAt = 0L

                        // **The record is handed to the scratch, and the scratch decides when the
                        // player stops** (0.8). It used to be paused here, at once, and the sound
                        // began whenever a decode finished: a dead cut, a gap, then noise. Now the
                        // music plays on for the few hundredths of a second that takes, and the
                        // scratch takes it over at full speed and winds it down under the finger —
                        // which is the sound a hand on a record makes. See ScratchAudio.
                        deck.tracks.getOrNull(scratch.cursor.trackIndex)?.let { track ->
                            scratchAudio.grab(
                                uri = android.net.Uri.parse(track.fileUri),
                                trackIndex = scratch.cursor.trackIndex,
                                trackDurationMs = track.durationMs,
                                playing = frame.isPlaying,
                                positionMs = scratch.cursor.offsetMs
                            )
                        }
                    }
                }
            },
            onScroll = { _, _, _, distance ->
                // No panning while a case is out — the prototype does the same — and none
                // while an album is being turned by hand, or the shelf would slide away
                // under the record you are trying to look at.
                if (selectedAlbumId == null && caseDrag.slot == null) {
                    // Arms the magnet: this gesture is a browse, so its release should land
                    // on an album. See WallSettle.panned.
                    settle.panned = true
                    // ONE coroutine, not two.
                    //
                    // This fires on every touch sample � 120 a second on this hardware � and
                    // launching a coroutine per axis meant 240 of them a second just to drag
                    // the wall, all of them resuming on the frame the finger is moving. Both
                    // snaps happen in the same one now.
                    val scale = metresPerPx()
                    val dx = distance.x * scale
                    val dy = distance.y * scale
                    scope.launch {
                        panX.snapTo(panX.value + dx)
                        panY.snapTo(panY.value - dy)
                    }
                }
            },
            // Pinch to walk the camera in and out of the room. Browsing only: once a case is
            // out, the open sequence owns the camera and a pinch would fight it for the same
            // channel.
            onScale = { detector, _, _ ->
                if (selectedAlbumId == null) {
                    // Damped, and about 1.0 rather than multiplied raw � see ZOOM_GAIN.
                    val factor = 1f + (detector.scaleFactor - 1f) * ZOOM_GAIN
                    // Moves the TARGET. The camera itself is eased toward it in the frame
                    // loop, which is what filters out the per-sample jitter that made the
                    // zoom judder � see WallZoom. And it stops at the limits: the pinch never
                    // leaves the room (see the note above ZOOM_GAIN).
                    zoom.target = (zoom.target / factor).coerceIn(ZOOM_NEAR, ZOOM_FAR)
                }
            },
            // Records the throw; does NOT animate. The single settle on release reads this
            // and projects from it � see WallSettle for why there is only one settle site.
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
                            // Already on its way off the shelf � a second confirmation for a
                            // deletion already in flight would be nonsense, and the frontmost
                            // tap gesture for it is already the skip above.
                            val discarding = caseSlots.slots.any {
                                it.discardClock != null && it.body?.name == albumId
                            }
                            if (!discarding) {
                                haptics.detent()
                                pendingDelete = layout.cases.firstOrNull { it.albumId == albumId }
                            }
                        }
                }
            },
            // onSingleTapUp, not onSingleTapConfirmed: the latter waits out the double-tap
            // timeout, so a second tap arriving soon after the first — exactly what
            // interrupting a 1.5s sequence looks like — was being swallowed as a double tap.
            onSingleTapUp = { _, node ->
                if (selectedAlbumId != null) {
                    // A tap that landed on the disc belongs to the platter — the prototype
                    // consumes it the same way. Without this, grabbing the disc and letting
                    // go without turning it would close the case.
                    if (!node.isDisc()) {
                        // Reversible from anywhere: a tap while closing sends it back out.
                        if (sequence.direction < 0) sequence.direction = 1 else closeCase()
                    }
                } else if (showroomReachable &&
                    generateSequence(node) { it.parent }.any { it.name == NEON_SIGN_NODE }
                ) {
                    // The neon sign: out to the showroom.
                    haptics.detent()
                    onOpenShowroom()
                } else {
                    // Only ever open something that is actually an album. Taking the first
                    // named ancestor on trust meant any named node in the scene could be
                    // opened as a "case" — which is exactly what the hidden disc did,
                    // leaving it stranded on screen with a deck that had no album behind it.
                    //
                    // Walk for the first ancestor whose name IS an album, rather than taking
                    // the first named one and testing it afterwards. Since the shelved cases
                    // became glTF models, picking lands on a node the asset named itself
                    // ("case_closed"), which sits below the slot node carrying the album id
                    // � so the old order found the model's own name, failed the test, and
                    // silently dropped every tap on the wall.
                    val albumId = generateSequence(node) { it.parent }
                        .mapNotNull { it.name }
                        .firstOrNull { name -> layout.cases.any { it.albumId == name } }
                    if (albumId != null) {
                        // A tap on a case already breaking apart skips straight to the end
                        // instead of opening it � the album's row is still in the database
                        // for the whole discard window (see `discardCase`), so without this
                        // check the walk above would find it and open it mid-collapse.
                        val discarding = caseSlots.slots.any {
                            it.discardClock != null && it.body?.name == albumId
                        }
                        if (discarding) skipDiscard(albumId) else openCase(albumId)
                    }
                }
            },
            // Turning a shelved album uses the move-gesture trio rather than onScroll,
            // because it wants touch slop first � a case should not spin on a stray tap.
            // The disc is the opposite and is handled in onDown. Panning stays on onScroll,
            // which is already inert while a case is open, so the two never contend.
            onMoveBegin = { _, e, node ->
                // Browsing: grabbing an album turns it in place. Resolved once, here, using
                // the same walk the tap handler does � picking lands on a node the glTF
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

                        // The disc tracks the finger exactly — this is the whole illusion.
                        sequence.spinAngle -= degrees

                        // **The record goes where the hand goes** (0.8). The scratch is told the disc's
                        // *place* — how much music it has been turned through since the finger
                        // landed — and when the screen saw it there, never a speed worked out
                        // from two touch events: a record held to a place cannot drift from the
                        // disc, and a finger that stops is simply a record that has arrived. See
                        // Platter and HandTrack; and Scratch.Gearing for what a turn is worth.
                        scratch.moved += scratch.gearing.turn(delta)
                        scratchAudio.hand(scratch.moved, e.eventTime * 1_000_000L)

                        // Where the record is, is where the scratch has it — what is heard is where you
                        // are. Until it has the record (or if it never could), the finger's own sum.
                        val before = scratch.cursor.trackIndex
                        val place = scratchAudio.positionMs()
                            ?: (scratch.grabOffsetMs + (scratch.moved * 1000).toLong())
                        scratch.cursor = Scratch.advance(
                            Scratch.Cursor(scratch.grabIndex, 0L), place, scratch.durations
                        )
                        
                        val now = SystemClock.uptimeMillis()

                        if (scratch.cursor.trackIndex != before) {
                            // A track boundary went past under the finger. Heavier than the
                            // scrub texture and deliberately a different effect, so a skip
                            // can be felt without looking � this is the "vibration on skip"
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
                            // The paused player is walked along behind, so the deck's own line follows the
                            // disc — only once the scratch has the record (until then the player is still
                            // the one sounding, and a seek would be heard), and less often than the readout:
                            // a seek is a decoder flushed, and nobody reads the line that closely.
                            if (scratchAudio.holding && now - scratch.lastPlayerSeekAt >= Scratch.PLAYER_SEEK_THROTTLE_MS) {
                                scratch.lastPlayerSeekAt = now
                                if (!scratchAudio.walk(scratch.cursor.trackIndex, scratch.cursor.offsetMs)) {
                                    viewModel.onScrub(scratch.cursor.trackIndex, scratch.cursor.offsetMs)
                                }
                            }
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
            // **What TalkBack can do with a 3D wall** (0.7.5). The room is a Filament surface, so to
            // a screen reader it was one blank rectangle: no record could be reached without
            // seeing it. It says what is in front now, and offers the moves a finger makes — the
            // next record, the one before, open it, put it away, play or pause. FIND, in the bar,
            // is the way to a record by name. Read only when accessibility asks: these lambdas are
            // semantics, not composition, so they cost nothing with TalkBack off.
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            renderer = renderer,
            // Under a panel: park the loop. The last frame stays on the surface for the panel to
            // slide over, and nothing is drawn behind a screen nobody can see through.
            isRendering = !covered && !parked,
            // Replaces SceneView's default main and fill lights. Those are a neutral studio
            // rig — exactly the "flatly-lit scene" DESIGN.md's Do-not list ends on.
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
            // `OnGestureListener` has no `onUp` � checked against the interface, it has
            // nineteen callbacks and not one of them is a plain release. That did not matter
            // while the grab began at `onMoveBegin`, because it then always ended at
            // `onMoveEnd`. Moving the grab to `onDown` breaks that pairing: press the disc,
            // hold it still, and no move gesture ever begins, so none ever ends � the music
            // would stop and never start again.
            //
            // `onSingleTapUp` does not cover it either. Once `onLongPress` has fired, which
            // it does after half a second, the platform stops delivering it � and half a
            // second is well inside what "press and hold" means.
            //
            // Returns false always: this only observes. SceneView's own gesture handling
            // runs exactly as before, and `endScratch` is idempotent, so `onMoveEnd` calling
            // it too is harmless.
            onTouchEvent = { motionEvent, _ ->
                when (motionEvent.actionMasked) {
                    // A hand on the glass, from the only stream that reliably reports one.
                    // The frame driver reads it to hold the room at full rate for as long as
                    // it is down — including a press that never becomes a gesture, which is
                    // exactly what holding the platter to pause is. See RoomCadence.
                    //
                    // ACTION_DOWN only, not ACTION_POINTER_DOWN: the first finger arms it and
                    // the last release below disarms it, so a pinch neither re-arms nor leaves
                    // it stuck when its second finger lifts.
                    MotionEvent.ACTION_DOWN -> cadence.pointerDown = true

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cadence.pointerDown = false
                        caseDrag.slot = null
                        endScratch()

                        // THE settle � the only one. Both a slow drag and a fling end here.
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
                                    // Both axes driven from ONE album, and the throw's own
                                    // velocity handed to the springs so the wall *carries*
                                    // into its landing rather than stopping dead at the
                                    // release and then easing across � which is exactly what
                                    // a fling that has lost its momentum feels like.
                                    //
                                    // This was dropped once, because a spring given velocity
                                    // can reach a bound and terminate there instead of at its
                                    // target, parking the wall where no album is. The bounds
                                    // now stretch to include wherever the camera already is,
                                    // so there is no longer a bound close enough to hit.
                                    launch {
                                        panX.animateTo(
                                            targetValue = target.x,
                                            initialVelocity = if (flung) settle.flungVx else 0f,
                                            animationSpec = SETTLE_SPRING
                                        )
                                        haptics.tick()
                                    }
                                    launch {
                                        panY.animateTo(
                                            targetValue = target.y,
                                            initialVelocity = if (flung) settle.flungVy else 0f,
                                            animationSpec = SETTLE_SPRING
                                        )
                                    }
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

                // **The idle life winds down when the wall is left alone** (RoomSettle, 0.7.5).
                // Anything happening keeps it awake: a hand on the glass, something still moving
                // from the last frame, a record open, a removal in flight.
                val nowMs = SystemClock.uptimeMillis()
                if (cadence.pointerDown || cadence.wasMoving || frame.selected != null ||
                    discardNodes.isRunning
                ) {
                    idleLife.keepAwake(nowMs)
                }
                idleLife.step(dt, nowMs)

                // Nothing is known to be moving until something in this pass says so — but the
                // decision is taken *here*, from the previous frame, not at the tail.
                //
                // **That is the difference between skipping a draw and skipping the work.** At
                // the tail every node transform has already been written; deciding there saved
                // the rasterisation and nothing else, so at the idle wall the driver was
                // writing two SceneView transforms per case per vsync — 120 times a second —
                // to produce fifteen drawn frames. Seven sets in every eight were overwritten
                // before anything read them.
                //
                // The cost of deciding a frame early is that motion beginning *this* frame is
                // noticed one vsync late. That is 8ms, and it never applies to the case that
                // matters: a finger sets `pointerDown` from the touch handler before the frame
                // runs, so a hand on the glass is exact rather than a frame behind. See
                // RoomCadence.wasMoving.
                cadence.beginFrame()
                val divisor = cadence.divisorFor(
                    // The disc turns under its own power only while an album is actually
                    // playing and no finger is on it — the same three conditions the spin
                    // itself is written under, further down.
                    discTurning = frame.selected != null &&
                        frame.isPlaying &&
                        !scratch.active &&
                        !reduceMotion,
                    ambientMotion = !reduceMotion && !idleLife.settled,
                    settled = idleLife.settled
                ).let { own ->
                    // While Android is still sliding the app into place, a quarter of the rate:
                    // the launcher draws that slide on the same GPU. See RoomCalm.
                    val cap = com.jawon.app.ui.common.RoomCalm.frameCap(SystemClock.uptimeMillis())
                    if (cap == Float.MAX_VALUE) own else maxOf(own, cadence.divisorForFps(cap))
                }
                val draw = cadence.shouldDraw(divisor)

                // Walk the browse camera toward wherever the pinch left it. Every frame,
                // whether or not anything is pinching � it is a no-op once it has arrived,
                // and this is also what carries the camera home when a case opens.
                zoom.advance(dt)
                // `advance` eases exponentially and so never quite arrives — unlike
                // `CaseSlot.settleTowardTarget`, which snaps when it gets close. So this
                // needs a threshold of its own rather than an equality test, or the room
                // would count itself as moving forever after the first pinch. A hundredth of
                // a millimetre of camera dolly is not motion.
                if (abs(zoom.target - zoom.current) > ZOOM_SETTLED_EPS) cadence.moving = true

                // Was the opening guess about this device optimistic? `dt` is clamped above
                // at 1/24s, which is fine here � anything at the clamp is already far past
                // the slow-frame threshold and counts the same either way.
                //
                // Writing Compose state here is safe: it schedules a recomposition rather
                // than running one, so the `remember` that re-applies the View options fires
                // between frames rather than in the middle of this one. `FrameWatch` latches
                // after firing, so this happens at most once per session.
                if (dt > 0f) diagnostics.onFrame(dt)

                // **Judged on drawn frames against what the cadence promised**, not on vsync
                // spacing. See FrameWatch.onDrawnFrame: the old signal could not fire at all
                // once the room started skipping vsyncs on purpose and the panel went to 120.
                //
                // Samples where the divisor changed across the interval are dropped rather
                // than counted — the gap after a finger lifts is legitimately long, and
                // reading it as a missed deadline would step a healthy device down every time
                // someone stopped touching the wall.
                if (draw) {
                    val previous = sequence.lastDrawnNanos
                    if (previous != 0L && divisor == sequence.lastDrawnDivisor) {
                        val interval = (frameNanos - previous) / 1_000_000_000f
                        val expected = divisor / panelRefreshHz
                        if (frameWatch.onDrawnFrame(interval, expected)) {
                            tier.below?.let { stepped ->
                                Log.i(
                                    "Jawon.Quality",
                                    "Drawn frames are late against the cadence; quality $tier -> $stepped"
                                )
                                tier = stepped
                                diagnostics.onTierStepped(stepped)
                            }
                        }
                    }
                    sequence.lastDrawnNanos = frameNanos
                    sequence.lastDrawnDivisor = divisor
                }

                // The dust's whole animation, in one uniform write per frame — no CPU work
                // per mote, which is what DESIGN.md asks for.
                if (!reduceMotion) dustMaterial.setParameter("time", idleLife.clock)

                // The fog drifts with the clock and is dragged and stirred by the pan — three
                // uniform writes, and FogAir's few lines of arithmetic. Held still with reduced
                // motion: the fog stays, its movement goes.
                if (fogSteps > 0 && !reduceMotion) {
                    fogAir.update(idleLife.clock, panX.value, panY.value)
                    fogMaterial.setParameter("time", idleLife.clock)
                    fogMaterial.setParameter("wind", fogAir.windX, fogAir.windY)
                    fogMaterial.setParameter("stir", fogAir.stir)
                }

                // Take the room's own atmosphere back off SceneView.
                //
                // SceneView writes a `RenderQuality` preset over this View from a
                // `LaunchedEffect`, which lands after the `remember` above that applied ours —
                // so the room has been browsing with contact shading switched back on, at the
                // cost RoomAtmosphere.setAmbientOcclusion measured, and with the underlight
                // glowing at a third of its tuned strength. See RoomAtmosphere.isApplied.
                //
                // A predicate rather than "re-apply for the first few frames", because the bug
                // being fixed *is* an ordering assumption and there is no reason to replace it
                // with a shorter one. Costs a float compare per frame and self-heals if
                // SceneView ever writes the preset again.
                if (!RoomAtmosphere.isApplied(filamentView, grading, lensDirt)) {
                    RoomAtmosphere.apply(filamentView, tier, lensDirt)
                    grading.bind(filamentView, engine, roomScheme)
                    // `apply` leaves depth of field and contact shading in their browsing
                    // state. Clearing the latch makes the crossing below re-decide rather than
                    // assuming no case can be open at the moment this fires.
                    sequence.dofEnabled = false
                }

                // Depth of field exists only while a case is open — on the flat wall it
                // erased the spines outright. One write per crossing, not per frame.
                //
                // Ambient occlusion rides the same crossing, but as a change of *quality* rather
                // than a switch: it stays on while browsing, cheap and broad, and takes the
                // full configuration only up close where the 1mm seam is resolvable. Switching
                // it off entirely on the wall was tried and reverted — see
                // RoomAtmosphere.setAmbientOcclusion.
                val wantsDof = frame.selected != null
                if (wantsDof != sequence.dofEnabled) {
                    sequence.dofEnabled = wantsDof
                    RoomAtmosphere.setDepthOfField(filamentView, wantsDof, tier)
                    RoomAtmosphere.setAmbientOcclusion(filamentView, wantsDof, tier)
                }

                // Focus follows the subject, eased rather than snapped so opening a case
                // pulls focus instead of cutting. The disc sits exactly DISC_VIEW_DIST in
                // front of the lens once it is up, so focusing there is what keeps it sharp
                // while the case and the shelves behind it fall away.
                val focusTarget =
                    if (wantsDof) RoomAtmosphere.FOCUS_DISC
                    else RoomAtmosphere.FOCUS_BROWSING
                // Pulling focus is a change to every pixel of the frame, so it counts as
                // motion even though nothing has moved. This one is a rate-limited walk
                // rather than an exponential, so it does arrive exactly and an equality test
                // would do — the epsilon is only there to survive the float landing a ulp out.
                if (abs(focusTarget - sequence.focusDistance) > FOCUS_SETTLED_EPS) {
                    cadence.moving = true
                }
                sequence.focusDistance += (focusTarget - sequence.focusDistance).coerceIn(
                    -RoomAtmosphere.FOCUS_RATE * dt,
                    RoomAtmosphere.FOCUS_RATE * dt
                )
                // Written to the Filament camera itself, not CameraNode.focusDistance:
                // the node's property is SceneView's own, and the DoF pass reads the
                // camera's.
                cameraNode.camera.setFocusDistance(sequence.focusDistance)

                if (sequence.direction != 0) {
                    // The five beats. The one animation everybody watches, so it is never
                    // drawn at anything but full rate.
                    cadence.moving = true
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
                    // One state write per crossing, not per frame — the panel then fades in
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
                        // The scratch puts the music back as it found it: playing, if it was.
                        scratchAudio.cancel()
                    }
                }

                // While the disc can be touched, the scratch keeps the audio round the playhead
                // in hand, so that a finger is answered at once and not when a decoder has
                // started. Said every frame, as a heartbeat: when the room stops drawing —
                // covered, parked, the screen off — it stops being said, and the scratch lets
                // go by itself.
                scratchAudio.arm(sequence.progress > Scratch.ARM_AT)

                // Every shelved case hovers off its board and turns slowly, so a wall of
                // albums reads as a rack of objects presenting themselves rather than a row
                // of spines. Driven here rather than from `bind` because it is per-frame:
                // binding decides which album sits where, this decides how it is floating.
                //
                // Written straight to the nodes, never through Compose state � ENGINEERING.md
                // rule 3. No allocation in this loop either; it runs for every visible slot.
                // Walk the lamp along with the browse position. Panning is frozen while a
                // case is open, so during the open sequence this holds the light still.
                // The group's offset IS the pan; the lamp keeps its own offset inside it.
                // The ribbon belongs to the rack, not to the camera. It used to be parked at
                // exactly (panX, panY) here, which dragged the whole lit pattern across the
                // fixed timber on every swipe — see RoomLighting.SHELF_LIGHT_COUNT.
                roomNodes.placeRibbon(panX.value, panY.value, layout.rows.size)
                roomNodes.placeSpots(panX.value)

                // Recolour the bulbs in place rather than rebuilding the lights.
                //
                // Rebuilding would mean destroying and recreating scene nodes, and this is
                // the file where node lifetimes have already cost three separate bugs — and
                // trap 1 makes any wholesale scene rebuild a process abort, not a glitch.
                // Writing the colour onto lights that already exist has none of that risk.
                // Cheap enough to do unconditionally: it is two float triples a frame, and
                // Filament only uploads when the value actually changes.
                roomNodes.applyScheme(roomScheme)

                // This loop ALWAYS runs, and only the motion is conditional. `bind` no longer
                // writes root.position at all, so skipping it entirely under reduced motion
                // would leave every case stacked at the wall's origin.
                run {
                    // The idle clock, not the frame clock: it winds down when the wall is left alone.
                    val t = if (reduceMotion) 0f else idleLife.clock
                    // How lit the pool of an album being removed still is. Computed once here
                    // because this pass runs before the removal is driven.
                    val discardBeamGlow = discardNodes.clock
                        ?.let { CaseDiscard.beamGlowAt(it / CaseDiscard.DURATION_S) }
                        ?: 0f
                    caseSlots.slots.forEach { slot ->
                        if (!slot.occupied) return@forEach
                        val root = slot.root ?: return@forEach
                        // Slide toward where this case belongs. Almost always a no-op � the
                        // two are equal unless a shelf has just repacked around a deletion
                        // or the now-playing album changed, and `bind` snaps them together
                        // whenever a pooled slot changes hands. See CaseSlot.targetX.
                        //
                        // Asked before the step, not after: `settleTowardTarget` snaps when it
                        // gets within SETTLE_EPS, so testing afterwards would miss the last
                        // frame of a repack — the one that puts the case in its final place.
                        if (slot.baseX != slot.targetX ||
                            slot.baseY != slot.targetY ||
                            slot.baseZ != slot.targetZ
                        ) {
                            cadence.moving = true
                        }
                        slot.settleTowardTarget(dt)
                        // The pool lies on the board and never turns, so it only needs
                        // placing — at the foot of the bay, under wherever the case is
                        // hanging. CASE_H/2 down from the album's centre is the board top.
                        // Nearly always a no-op; see CaseSlot.placeBeam.
                        slot.placeBeam(slot.baseX, slot.baseY - CASE_H / 2f, slot.baseZ)

                        if (slot.discardClock != null) {
                            // Being removed. The shelved case stays hidden for the whole
                            // sequence � the *held* case has taken over from this slot, the
                            // same handover opening does � so this branch only keeps the slot
                            // parked and lets DiscardScene drive everything visible.
                            root.isVisible = false
                            // The pool stays lit and fades over the sequence rather than
                            // being cut on the first frame — see CaseDiscard.beamGlowAt.
                            slot.setBeamGlow(discardBeamGlow)
                            slot.setBeamVisible(discardBeamGlow > 0.01f)
                            return@forEach
                        }

                        // **State first, and unconditionally.** Carry any throw the user gave
                        // this case, shedding it over about a second. Skipped while the finger
                        // is down, because onMove is already writing manualTurn directly.
                        //
                        // This runs on every vsync whether or not the frame is drawn, and that
                        // is load-bearing rather than tidy: it integrates `dt`, so running it
                        // only on drawn frames would make a fling shed its spin at a rate that
                        // depended on the cadence — the same throw travelling further at 15fps
                        // than at 120. Everything in the driver that advances a clock or a
                        // spring is unconditional for this reason; only the *writes* below are
                        // conditional. See RoomCadence.
                        if (!reduceMotion && slot !== caseDrag.slot && slot.spinVelocity != 0f) {
                            // A case still carrying a throw. Shedding over about a second,
                            // and fast enough at the start to need every frame of it.
                            cadence.moving = true
                            slot.manualTurn += slot.spinVelocity * dt
                            slot.spinVelocity *= ShelfHover.SPIN_RETAIN_PER_S.pow(dt)
                            if (abs(slot.spinVelocity) < 1f) slot.spinVelocity = 0f
                        }

                        // The transforms, which only matter on a vsync that will be drawn.
                        //
                        // Two SceneView writes per case, each a matrix compose and a JNI call
                        // into Filament's TransformManager. At the idle wall this loop ran 120
                        // times a second to produce 15 drawn frames: seven of every eight sets
                        // were overwritten before anything read them. Nothing here is a
                        // function of anything but `t` and the slot's own state, both of which
                        // are current whenever this runs, so a skipped write loses nothing —
                        // the next drawn frame computes the same value from the same clock.
                        if (!draw) return@forEach

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
                            // At any spin setting a case still bobs and leans — that is the
                            // room breathing. What the slider scales is the slow rotation,
                            // which is the part that catches the eye from across the screen;
                            // at zero they hold square on, plus any hand-flick.
                            root.rotation = Rotation(
                                ShelfHover.tilt(t, slot.phase),
                                ShelfHover.turn(t, slot.phase, caseSpin) + slot.manualTurn,
                                0f
                            )
                        }
                    }
                }

                // A removal in flight. Driven here, once, rather than from inside the slot
                // loop: it moves the *held* case, which is not any slot's node, and the slot
                // it came from is only parked.
                if (discardNodes.isRunning) {
                    // A disc breaking and being thrown. Full rate for the whole sequence.
                    cadence.moving = true
                    if (driveDiscard(discardNodes, actNodes, dt, panX.value, panY.value, reduceMotion, haptics)) {
                        // The slot is free the moment the case is gone. `driveDiscard` has
                        // already put the held case back to a neutral transform � see its
                        // tail for why leaving it rotated would show the next opened album
                        // mid-tumble.
                        caseSlots.slots.forEach { slot ->
                            if (slot.discardClock != null) {
                                slot.discardClock = null
                                slot.occupied = false
                            }
                        }
                        discardingAlbumId = null

                        // Settle onto whatever is left nearest the gap.
                        //
                        // The wall deliberately does not move *during* a removal � nothing
                        // should rearrange itself under someone watching it � but once the
                        // record is gone, resting on an empty slot is its own kind of wrong.
                        // Eased on the magnet's own spring rather than snapped, so it reads
                        // as the shelf settling rather than the view being repositioned.
                        val target = layout.nearestCase(panX.value, panY.value)
                        if (target != null) {
                            scope.launch { panX.animateTo(target.x, SETTLE_SPRING) }
                            scope.launch { panY.animateTo(target.y, SETTLE_SPRING) }
                        }
                    }
                }

                val selected = frame.selected
                if (selected == null) {
                    // **One tick per album the wall passes, driven from the frame rather than
                    // from the finger.**
                    //
                    // The owner: "one haptic = one album shift, both horizontally and
                    // vertically. it makes navigating through library more satisfying." It
                    // was hooked to `onScroll` first, and he felt nothing — because
                    // `onScroll` only fires while a finger is DOWN, and browsing this wall is
                    // mostly the part after you let go. A swipe covers about one album and
                    // then flings past several more, so the whole riffle — the part that
                    // feels like a crate — happened in silence.
                    //
                    // Here it covers the drag, the fling and the magnet settling, because all
                    // three are just the camera moving. Once per frame rather than once per
                    // touch sample is also 120Hz down to 60 or 90, and the cell arithmetic in
                    // PanDetents caps it far below either.
                    if (panDetents.crossed(panX.value, panY.value)) {
                        haptics.tick()
                        // The rail shows itself while the wall moves and fades after, so the
                        // letters are there when they are useful and gone when they would sit
                        // over the artwork. See railVisibleUntil.
                        railVisibleUntil = SystemClock.elapsedRealtime() + RAIL_DWELL_MS
                    }
                    cameraNode.position = Position(panX.value, panY.value, zoom.current)
                    cameraNode.rotation = LOOK_STRAIGHT_AHEAD
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
                        // (same DURATION_MS, both linear in time) � so reading it live here is
                        // what makes a case opened from a zoomed-out wall arrive at the
                        // sequence's tuned framing smoothly. Hardcoding CAMERA_Z here would
                        // make the correction above dead code: the camera would jump to it the
                        // instant the tap registered, before the pull even begins.
                        baseCameraZ = zoom.current,
                        tanHalfFov = TAN_HALF_FOV,
                        aspect = frame.aspect,
                        clock = sequence.clock
                    )
                    cameraNode.position = Position(panX.value, t.cameraY, t.cameraZ)
                    cameraNode.rotation = LOOK_STRAIGHT_AHEAD
                    actNodes.root?.let { root ->
                        root.position = Position(t.caseX, t.caseY, t.caseZ)
                        root.rotation = Rotation(t.caseRotX, t.caseRotY, 0f)
                    }
                    actNodes.hinge?.rotation = Rotation(0f, t.hingeRotY, 0f)
                    actNodes.disc?.let { disc ->
                        disc.isVisible = t.discVisible
                        disc.position = Position(t.discX, t.discY, t.discZ)
                        disc.scale = Scale(t.discScale)
                        // Free spin yields to the hand on the platter — the prototype's
                        // `if (playing && !scratching)`. The finger's own rotation is
                        // already being added in onMove.
                        //
                        // Subtracted, so idle spin runs the same way round as a forward
                        // scrub. Dragging the disc clockwise moves the audio forward, and
                        // it would read as a fault if letting go then span it backwards.
                        // DESIGN.md §4 also lists the idle spin under reduced motion.
                        if (scratchAudio.turning && !scratch.active && !reduceMotion) {
                            // Let go, and the record is still the scratch's to turn (0.8): winding back up,
                            // or spinning back from a throw. The disc does what the sound is doing — at
                            // the speed the hand had it, easing into its own idle turn as it comes up
                            // to pitch — so a backspin is seen as well as heard.
                            val rate = scratchAudio.rate
                            sequence.spinAngle -= rate * dt *
                                Scratch.degreesPerSecondLetGo(rate, OpenSequence.SPIN_DEG_PER_S * beats.spin)
                        } else if (frame.isPlaying && !scratch.active && !reduceMotion) {
                            sequence.spinAngle -= OpenSequence.SPIN_DEG_PER_S * dt * beats.spin
                        }
                        disc.rotation = Rotation(0f, 0f, sequence.spinAngle)
                    }
                }

                // ---- Is this vsync worth drawing? -------------------------------------
                //
                // Everything above has already run: the room's whole state is current, on
                // every frame, exactly as it was before this existed. All that is decided
                // here is whether Filament rasterises it.
                //
                // `skipNextFrames(1)` sets a counter that the very next `beginFrame` reads and
                // clears — and `beginFrame` is the next thing SceneView does after this
                // callback returns, so the two cannot drift apart. When it returns false
                // SceneView skips `render` and `endFrame` entirely: no culling, no draw
                // submission, no post chain, no present. Verified against Filament 1.72.1
                // (`FrameSkipper::shouldRenderFrame` short-circuits on `mFrameToSkip`) rather
                // than assumed — trap 2's lesson applies to `Renderer` as much as to
                // materials. The one caveat is that Filament's header describes
                // `skipNextFrames` as being for debugging; it is a plain counter feeding the
                // same skip path the fence-based skipper uses on every frame the GPU is
                // behind, but it is the reason this wants a look on device rather than only
                // a green build.
                //
                // The springs are asked here rather than tracked through the pass above
                // because they are Compose `Animatable`s and already know: `isRunning` is
                // true from `animateTo` until the spring settles, which is precisely the
                // window in which the magnet is carrying the wall somewhere.
                // The springs are asked here rather than tracked through the pass above
                // because they are Compose `Animatable`s and already know: `isRunning` is
                // true from `animateTo` until the spring settles, which is precisely the
                // window in which the magnet is carrying the wall somewhere. Raised for the
                // *next* frame's decision now that the decision is taken at the top.
                if (panX.isRunning || panY.isRunning) cadence.moving = true

                // Keep Filament's frame budget in step with the cadence. `interval` means
                // exactly what the divisor means — "the app renders every N display frames" —
                // so publishing it is what lets the dynamic resolution scaler tell a skipped
                // vsync apart from a late one. Only on change; see [SequenceDriver.publishedDivisor].
                if (divisor != sequence.publishedDivisor) {
                    sequence.publishedDivisor = divisor
                    renderer.frameRateOptions = Renderer.FrameRateOptions().apply {
                        interval = divisor.toFloat()
                    }
                }

                com.jawon.app.ui.common.PerfProbe.count(if (draw) "drawn" else "skipped")
                if (!draw) renderer.skipNextFrames(1)
                diagnostics.onFrameDrawn(draw)

                // What the room actually drew itself at, for the Settings readout. Sampled
                // about once a second rather than per frame: it is a JNI call, and the value
                // it reports moves at Filament's own `scaleRate` over several frames, so
                // reading it 120 times a second would cost something to learn nothing.
                if (draw && sequence.scaleSampleIn-- <= 0) {
                    sequence.scaleSampleIn = SCALE_SAMPLE_FRAMES
                    filamentView.getLastDynamicResolutionScale(renderScaleOut)
                    diagnostics.onRenderScale(renderScaleOut[0])
                }
            }
        ) {
            // The shop lamp. A focused spot rather than a directional light, because
            // DESIGN.md wants "one warm lamp over the racks, deep shadow everywhere else" —
            // a directional light has no falloff and would light the whole wall evenly.
            // Wrapped in a bare Node so the frame driver has something to move: the lamp
            // rides with the browse position, and `LightNode` exposes no handle of its own
            // that this file can reach. The light keeps its offset inside the group, so
            // moving the group is exactly moving the lamp.
            // **The carrier stays; the lamp inside it is gone.**
            //
            // This held the room's key light — one warm tungsten spot up and to the right,
            // the single lamp the whole look was built around, and the only shadow caster in
            // the scene. The owner's lighting design replaces it: two lights and nothing else,
            // the backlight below and one real underlight per album. A distant lamp lighting
            // everything is the opposite of that.
            //
            // The carrier itself is load-bearing and must not go with it. The frame driver
            // walks it to the browse position every frame, and the backlight hangs from it —
            // which is what stops a light parented per row sitting at the wall's centre in x,
            // lighting the middle few bays of a two-metre row and nothing else.
            //
            // Losing the key also loses the room's shadows, since it carried the only shadow
            // map. Worth knowing before hunting for them.
            Node(apply = { roomNodes.keyLight = this }) {

            // **The backlight.** One per row, hung in the top back corner of the shelf and
            // throwing back and down at the panel behind the records — the gradient the owner's
            // bookcase photograph is built on, and now one of only two lights in the room.
            //
            // **Since 2026-11-15 the room ships without them**: the same gradient comes out of
            // the panel's material (RibbonLight), which halved a frame on the owner's phone.
            // They are built below only when a bench build asks for them (RoomSwitches).
            //
            // A row of them across the width rather than one: see RoomLighting.WASH_COLUMNS.
            // A single cone tight enough to grade the panel vertically leaves a puddle in the
            // middle of the shelf with darkness either side, where the reference has an evenly
            // lit panel across the full width of every compartment.
            //
            // The light that used to throw FORWARD from here, across the artwork, is gone with
            // the key: lighting the records is the underlight's job now.
            repeat(RoomLighting.SHELF_LIGHT_COUNT) { index ->
                // One carrier per ribbon row, so a row of the rack that does not exist can be
                // switched off whole — see RoomNodes.placeRibbon. Ribbon row `index` sits
                // (1.5 - index) row pitches above the carrier, which the frame driver parks on
                // the midpoint between the two rows either side of the view; that lands the
                // four ribbon rows exactly on four real shelves' tubes.
                // **The shelf-edge rail**, on its own carrier beside the ribbon's and at the
                // same height: under the front lip of the same board. This is the fixture that
                // lights the records — see RoomLighting.RAIL_CANDELA for why a shop puts it here
                // and not under the sleeves. On the album channel only, so it shows the artwork
                // and leaves the panel's gradient, the timber and the room's shadows alone.
                Node(
                    position = Position(
                        0f,
                        (1.5f - index) * ROW_PITCH +
                            WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT -
                            RoomLighting.WASH_DROP,
                        0f
                    ),
                    apply = { roomNodes.railRows[index] = this }
                ) {
                    // **Not built when the case plates carry their light themselves** (RailPlates,
                    // plate.mat; RoomSwitches.Rail). The carrier stays, empty, as the ribbon's does.
                    repeat(if (RoomSwitches.rail.lights) RoomLighting.RAIL_COLUMNS else 0) { column ->
                        LightNode(
                            type = LightManager.Type.FOCUSED_SPOT,
                            position = Position(
                                (column - RoomLighting.RAIL_COLUMNS / 2) *
                                    RoomLighting.RAIL_COLUMN_SPACING,
                                0f,
                                RoomLighting.RAIL_Z
                            ),
                            direction = normalize(
                                Direction(
                                    RoomLighting.RAIL_DIRECTION[0],
                                    RoomLighting.RAIL_DIRECTION[1],
                                    RoomLighting.RAIL_DIRECTION[2]
                                )
                            ),
                            apply = {
                                // Near white, with a hint of the room in it — see
                                // RoomLighting.railColour. The room's own colour here turned
                                // every sleeve the colour of the room.
                                val tint = RoomLighting.railColour(roomScheme.key)
                                color(tint[0], tint[1], tint[2])
                                intensityCandela(RoomLighting.RAIL_CANDELA)
                                spotLightCone(
                                    RoomLighting.RAIL_CONE_INNER,
                                    RoomLighting.RAIL_CONE_OUTER
                                )
                                falloff(RoomLighting.RAIL_FALLOFF)
                                castShadows(false)
                                lightChannel(RoomLighting.ROOM_LIGHT_CHANNEL, false)
                                lightChannel(RoomLighting.UNDER_LIGHT_CHANNEL, true)
                            }
                        )
                    }
                }

                Node(
                    position = Position(
                        0f,
                        (1.5f - index) * ROW_PITCH +
                            WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT -
                            RoomLighting.WASH_DROP,
                        0f
                    ),
                    apply = { roomNodes.ribbonRows[index] = this }
                ) {
                // **The LED ribbon itself**, inside the tube, emitting down the panel the tube
                // lies against. Aimed straight down with a cone short of the 90° where the board
                // above begins, so it lights the panel and not the shelf it hangs from.
                //
                // Every diode here is at a fixed place on the shelf: the carrier above is
                // snapped to the diode pitch, so a pan never slides the pattern across the
                // timber. See RoomLighting.SHELF_LIGHT_COUNT for what it did before.
                //
                // **Not built in the room as it ships**: the panel carries their light itself
                // (RibbonLight, panel.mat), summed from exactly these diodes. They are built only
                // on a bench build switched back to them (RoomSwitches), to compare against. The
                // carrier stays, empty, so placeRibbon has nothing to change.
                repeat(if (RoomSwitches.ribbon.lights) RoomLighting.WASH_COLUMNS else 0) { column ->
                    LightNode(
                        type = LightManager.Type.FOCUSED_SPOT,
                        position = Position(
                            (column - RoomLighting.WASH_COLUMNS / 2) *
                                RoomLighting.WASH_COLUMN_SPACING,
                            0f,
                            RoomLighting.WASH_Z
                        ),
                        direction = normalize(
                            Direction(
                                RoomLighting.WASH_DIRECTION[0],
                                RoomLighting.WASH_DIRECTION[1],
                                RoomLighting.WASH_DIRECTION[2]
                            )
                        ),
                        apply = {
                            // The tube's colour — these diodes are the light inside it. They
                            // took the room's key colour while the tube stayed amber, and in
                            // every room but Vesper the wall was lit a different colour from
                            // the fixture lighting it. See RoomScheme.tube.
                            val light = roomScheme.tubeLight
                            color(light[0], light[1], light[2])
                            intensityCandela(RoomLighting.WASH_CANDELA)
                            spotLightCone(
                                RoomLighting.WASH_CONE_INNER,
                                RoomLighting.WASH_CONE_OUTER
                            )
                            falloff(RoomLighting.WASH_FALLOFF)
                            // None of the ribbon casts any more: the ceiling spots do, with a
                            // beam narrow enough for the shadow to be sharp enough to see. See
                            // RoomLighting.WASH_SHADOW_COLUMN for why the ribbon's was not.
                            castShadows(false)
                        }
                    )
                }
                }
            }
            }
            // The room's one shared coloured spill used to be here — a single light walked to
            // whichever bay was centred, because Filament would not give every bay its own.
            // Superseded: every occupied slot now carries a real light of its own. See
            // CaseSlot.underCarrier and RoomLighting.UNDER_CANDELA.

            // DESIGN.md's "one coloured bounce light" — a rim, low and to the left — used to
            // sit here. Gone with the key, and for the same reason: the room has two lights now
            // and a coloured bounce from nowhere in particular is not one of them.

            // Nothing loaded, no wall. This is what caused the brown flash on launch.
            //
            // `layout` arrives from the database, so for the first frames after the splash
            // it is the placeholder � no rows, no cases � while this backing panel was drawn
            // regardless, at its default size, filling the frame. With nothing in front of it
            // to give the eye scale or contrast, a near-black wall under the room's ambient
            // reads as a flat lit brown field, which is exactly what it was.
            //
            // Gated on `loaded` rather than on having content, which is what it used to be.
            // That older test also caught the genuinely-empty library, so a new user's first
            // screen had no wall either � see WallLayout.loaded.
            // **The rack's timber, built once for every board, the cap and the ends.**
            //
            // It was built inside the per-row block, which meant a fresh 512px texture for
            // every visible row — five of the same bitmap, a megabyte each. One is enough: tiles
            // share a Texture safely, and only a MaterialInstance may not be shared (a CubeNode
            // destroys the instance it is handed).
            val rackTimber = remember(roomScheme) {
                ImageTexture.Builder()
                    .bitmap(
                        RoomSurface.veneerTile(roomScheme.board, BOARD_TILE_SEED, grainAlongX = true),
                        TextureType.COLOR
                    )
                    .build(engine)
            }

            // **The shelf ribbon's light, carried by the rack itself** — as the room ships, and
            // unless a bench build switches it back to lights (RoomSwitches): `panel.mat` is
            // `opaque_textured` plus RibbonLight's tables, for the panel's face and for the top of
            // each board, the two things the ribbon's lights reached. Loaded only when used. The tubes' height comes from the layout, the same place the tube
            // meshes and the lights take it, so a change in the row count moves it with them.
            val ribbonMode = RoomSwitches.ribbon
            val ribbonGain = RoomSwitches.panelGain
            val topTubeY = RibbonLight.tubeY(layout.rows.firstOrNull()?.centerY ?: 0f)
            val panelMaterial = remember(materialLoader) {
                lazy { materialLoader.createMaterial(PANEL_MATERIAL_ASSET) }
            }
            val ribbonTable = remember(engine) { lazy { RibbonLight.texture(engine) } }
            val ribbonBoardTable = remember(engine) { lazy { RibbonLight.boardTexture(engine) } }
            // Not at file level: constructing a sampler is a native call (trap 23).
            val ribbonSampler = remember {
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.REPEAT
                ).apply { wrapModeT = TextureSampler.WrapMode.CLAMP_TO_EDGE }
            }
            val ribbonBoardSampler = remember {
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.CLAMP_TO_EDGE
                )
            }
            // One instance per node, as everything on a CubeNode must be (it destroys what it
            // is handed). The panel's face or a board's top, by [board].
            fun ribbonInstance(
                texture: Texture,
                metallic: Float,
                roughness: Float,
                board: Boolean
            ): MaterialInstance = materialLoader.createInstance(panelMaterial.value).apply {
                setParameter("texture", texture, TextureSampler2D())
                setParameter("metallic", metallic)
                setParameter("roughness", roughness)
                setParameter("reflectance", WALL_REFLECTANCE)
                setParameter("ribbonSurface", if (board) 1f else 0f)
                setParameter("ribbon", ribbonTable.value, ribbonSampler)
                setParameter("ribbonBoard", ribbonBoardTable.value, ribbonBoardSampler)
                val tube = roomScheme.tubeLight
                setParameter("ribbonColor", tube[0] * ribbonGain, tube[1] * ribbonGain, tube[2] * ribbonGain)
                setParameter("ribbonLux", if (ribbonGain > 0f) RibbonLight.LUX_PER_UNIT else 0f)
                setParameter("ribbonTubeY", topTubeY)
                setParameter("ribbonPitch", ROW_PITCH)
                setParameter("ribbonDiodePitch", RoomLighting.WASH_COLUMN_SPACING)
                setParameter("ribbonStandoff", RibbonLight.STANDOFF)
                setParameter("ribbonTop", RibbonLight.TOP)
                setParameter("ribbonBoardDrop", RibbonLight.BOARD_DROP)
                setParameter("ribbonBoardBack", RibbonLight.BOARD_BACK)
                setParameter("ribbonBoardDepth", BOARD_D)
                // Only the panel turns magenta: the mask is for finding the panel's pixels.
                setParameter("mask", if (!board && ribbonMode == RoomSwitches.Ribbon.MASK) 1f else 0f)
            }

            // **The lit air under every strip**, one texture per sheet depth, shared by every row
            // for the timber's reason. Rebuilt with the room, so the haze is always the tube's
            // colour. See RoomAir.haze — the bitmaps are 8x256 and cost nothing to make here.
            val hazeBackTexture = remember(roomScheme) {
                ImageTexture.Builder()
                    .bitmap(
                        RoomAir.haze(roomScheme.tubeLight, RoomAir.HAZE_BACK_ALPHA, RoomAir.HAZE_BACK_H),
                        TextureType.COLOR
                    )
                    .build(engine)
            }
            val hazeFrontTexture = remember(roomScheme) {
                ImageTexture.Builder()
                    .bitmap(
                        RoomAir.haze(roomScheme.tubeLight, RoomAir.HAZE_FRONT_ALPHA, RoomAir.HAZE_FRONT_H),
                        TextureType.COLOR
                    )
                    .build(engine)
            }

            // **The room's own wall: painted brick**, one texture for every tile, in this room's
            // colour. See RoomSurface.brickTile, and the wall itself below.
            val brickTexture = remember(roomScheme) {
                ImageTexture.Builder()
                    .bitmap(
                        RoomSurface.brickTile(roomScheme.brick, BRICK_TILE_SEED),
                        TextureType.COLOR
                    )
                    .build(engine)
            }

            if (layout.loaded) {
                // Sized to what the CAMERA can reach, not to what the content occupies.
                //
                // It used to be the content's own extent, centred on the world origin, and
                // that only worked because the camera used to tilt back toward the origin
                // wherever it panned � the backing was always in the middle of the shot by
                // construction. Now that the lens looks straight ahead (see
                // LOOK_STRAIGHT_AHEAD), panning to a low shelf walks the view clean off the
                // bottom edge of the panel and the room behind the records goes black.
                //
                // Spanning the full pan range plus a frustum in each direction covers every
                // position the user can reach, at any zoom: halfW and halfH are computed at
                // ZOOM_FAR, the furthest the camera is allowed to stand.
                // Sized to what the camera can reach, and keyed on that size along with its
                // own material.
                //
                // The material has to be created in the same scope that rebuilds, because a
                // CubeNode destroys the `MaterialInstance` it is handed. Remembered outside,
                // it was destroyed the first time the size changed and every later wall got a
                // dead one � the room went black after a deletion and stayed black.
                //
                // Making the panel a fixed 24m avoided that and cost far more than it saved:
                // this is a shadow receiver, and the room's shadow near/far hints are tuned to
                // a scene 1.5m across. Browsing fell to 31fps.
                key(layout.minPanX, layout.maxPanX, layout.minPanY, layout.maxPanY, roomScheme) {
                    // The scheme joins the key rather than being read inside `remember`, so
                    // changing it rebuilds the panel and its material together — the same
                    // discipline the size already follows, and for the same reason: a
                    // CubeNode destroys the instance it is handed.
                    // **The rack's own back panel, not a room wall.**
                    //
                    // This used to be sized to what the CAMERA can reach — the whole pan range
                    // plus a frustum in every direction — which is why it read as a wall with
                    // shelves hung on it. A cupboard's back is the same size as the cupboard.
                    // Bounded to the run and to the rows, it ends where the sides and the top
                    // board end, and the three of them are one object.
                    //
                    // ROW_PITCH above the top row's board is the top of that row's compartment,
                    // and half a board below the bottom row's is the underside of the carcass.
                    val rackTopY = (layout.rows.firstOrNull()?.boardY ?: 0f) + ROW_PITCH
                    val rackBottomY = (layout.rows.lastOrNull()?.boardY ?: 0f) - BOARD_H / 2f
                    val panelW = layout.wallWidth
                    val panelH = (rackTopY - rackBottomY).coerceAtLeast(ROW_PITCH)

                    // **The wall has a surface now, not just a colour.**
                    //
                    // This was `createColorInstance` - one constant albedo over the largest
                    // object on screen, which no amount of lighting or post-processing can
                    // articulate. `createTextureInstance` binds SceneView's stock
                    // `opaque_textured`, whose parameter set really is metallic / roughness /
                    // reflectance plus a `texture` sampler - read out of the shipped .filamat's
                    // MAT_UIB chunk rather than assumed - so this costs no hand-written material
                    // and does not touch the locked decision that keeps dust.mat the only one.
                    //
                    // The aspect ratio goes in because a CubeNode's UVs are 0..1 and the stock
                    // material has no UV scale: the texture stretches over the panel exactly
                    // once, so the mottle cell counts have to be pre-distorted to arrive square
                    // in world space. See RoomSurface.
                    //
                    // Remembered in the same scope as the node, for the reason the note above
                    // gives: a CubeNode destroys the MaterialInstance it is handed.
                    // **The back panel of the rack, tiled.**
                    //
                    // One node wearing one texture is what this was, and it could not carry a
                    // material: `opaque_textured` has no UV scale, so the texture stretches
                    // across the whole panel exactly once, and the camera sees about 26cm of a
                    // panel nearly two metres wide. That is one texel per nineteen screen
                    // pixels — measured, by putting hard stripes on it and counting how many
                    // reached the frame. One and a half did.
                    //
                    // A grid of nodes sharing one texture is the way to tile with a stock
                    // material. Each tile is about RoomSurface.TILE_M across and gets the whole
                    // 512px, which is a third of a millimetre per texel rather than three and a
                    // half millimetres, and RoomSurface.woodTile is built to wrap so the joins
                    // between tiles read as the seams between boards.
                    //
                    // **One texture, many material instances, and that is not an oversight.** A
                    // CubeNode destroys the MaterialInstance it is handed, so tiles cannot share
                    // one instance — the first tile to rebuild would kill it for all the others,
                    // which is exactly the fault that made the shelf boards vanish on import.
                    // They share the Texture instead, which nothing here destroys, so the memory
                    // is one bitmap however many tiles there are.
                    val panelTexture = remember(roomScheme) {
                        ImageTexture.Builder()
                            // **`COLOR` explicitly, never the default.**
                            //
                            // The bitmap holds sRGB bytes, because that is what `Color.toArgb`
                            // gives. Uploading it as anything but `COLOR` binds an `RGBA8`
                            // rather than an `SRGB8_A8`, so Filament reads those bytes as
                            // *already linear* and never de-gammas them. On a wall whose albedo
                            // is a few per cent that is roughly a thirteenfold overexposure of
                            // the largest surface in the room, and it is what turned the wall
                            // into bright mauve once before.
                            .bitmap(
                                RoomSurface.veneerTile(
                                    roomScheme.wall,
                                    WALL_TILE_SEED,
                                    grainAlongX = true
                                ),
                                TextureType.COLOR
                            )
                            .build(engine)
                    }
                    val tilesX = ceil(panelW / RoomSurface.TILE_M).toInt().coerceIn(1, 64)
                    val tilesY = ceil(panelH / RoomSurface.TILE_M).toInt().coerceIn(1, 64)
                    val tileW = panelW / tilesX
                    val tileH = panelH / tilesY
                    val panelLeft = -panelW / 2f
                    val panelBottom = rackBottomY

                    repeat(tilesY) { ty ->
                        repeat(tilesX) { tx ->
                            // Keyed by index, never by anything that can change identity — the
                            // same discipline the case pool and the shelf boards use, and for
                            // the same reason: a rebuild must re-point an existing node rather
                            // than destroy one and take its material with it.
                            key(tx, ty) {
                                val tile = remember(
                                    roomScheme, tx, ty, tilesX, tilesY, ribbonMode, ribbonGain, topTubeY
                                ) {
                                    if (!ribbonMode.bakedPanel) {
                                        materialLoader.createTextureInstance(
                                            panelTexture,
                                            metallic = 0f,
                                            // Sawn board rather than plaster: smooth enough that
                                            // the ribbon grazing it picks out the grain instead of
                                            // dying in a matte field.
                                            roughness = PANEL_ROUGHNESS,
                                            // **0.5 is a normal surface, not 0.04.** Filament's
                                            // reflectance is a 0..1 remap, F0 = 0.16 * r^2, so 0.5
                                            // is the ~4% a dielectric actually returns. 0.04 gives
                                            // 0.026% and turned this wall black once already.
                                            reflectance = WALL_REFLECTANCE
                                        )
                                    } else {
                                        ribbonInstance(
                                            panelTexture,
                                            metallic = 0f,
                                            roughness = PANEL_ROUGHNESS,
                                            board = false
                                        )
                                    }
                                }
                                CubeNode(
                                    size = Size(tileW, tileH, 0.01f),
                                    position = Position(
                                        panelLeft + (tx + 0.5f) * tileW,
                                        panelBottom + (ty + 0.5f) * tileH,
                                        WALL_Z - 0.005f
                                    ),
                                    materialInstance = tile,
                                    apply = {
                                        isTouchable = false
                                        // Takes shadows, casts none — there is nothing behind it.
                                        isShadowReceiver = true
                                        isShadowCaster = false
                                    }
                                )
                            }
                        }
                    }

                    // **The ends of the rack.**
                    //
                    // A carcass has sides, and without them the shelves run off into the dark at
                    // both ends and the thing reads as boards fixed to a wall rather than as a
                    // piece of furniture standing in a room. They are only in frame when the
                    // browse is at one end of the run — the camera sees about 26cm of a rack
                    // well over a metre wide — which is exactly why they are two nodes and not
                    // tiled: at that rarity the stretch costs nothing worth spending draws on.
                    //
                    // Spanning the whole run, from under the bottom board to the top of the top
                    // compartment, so the rack has a top and a bottom as well as two ends.
                    // The same extent the back panel uses, so the carcass cannot come apart.
                    // Up to the top of the cap below, so the ends and the cap meet at the corners.
                    val rackTop = rackTopY + BOARD_H / 2f
                    val rackBottom = rackBottomY
                    val rackHeight = (rackTop - rackBottom).coerceAtLeast(ROW_PITCH)
                    listOf(-1f, 1f).forEach { side ->
                        key(side) {
                            val cheek = remember(roomScheme, side, rackHeight) {
                                materialLoader.createTextureInstance(
                                    rackTimber,
                                    metallic = 0.04f,
                                    roughness = 0.82f,
                                    reflectance = WALL_REFLECTANCE
                                )
                            }
                            CubeNode(
                                size = Size(SIDE_T, rackHeight, BOARD_D),
                                position = Position(
                                    side * (layout.wallWidth / 2f + SIDE_T / 2f),
                                    (rackTop + rackBottom) / 2f,
                                    0f
                                ),
                                materialInstance = cheek,
                                apply = {
                                    isTouchable = false
                                    isShadowReceiver = true
                                    isShadowCaster = true
                                }
                            )
                        }
                    }

                    // **The cap: a board over the top shelf.**
                    //
                    // The rack had none, so the top row's compartment was open to the dark and its
                    // tube had nothing to be mounted under. The owner caught it before it shipped:
                    // "the most top shelf will not have shelf above it to attach a tube to". Every
                    // other shelf's tube hangs from the board of the row above; this is the board
                    // the top row's hangs from. It sits exactly where the next row's board would,
                    // one pitch above the top row's own, and spans the ends as well so the three
                    // pieces close into a carcass.
                    //
                    // Tiled for the reason every other board is, and each segment owns its own
                    // MaterialInstance for the reason every other board's does.
                    val capW = layout.wallWidth + SIDE_T * 2f
                    val capTiles = ceil(capW / RoomSurface.TILE_M).toInt().coerceIn(1, 64)
                    val capSegW = capW / capTiles
                    repeat(capTiles) { cx ->
                        key("cap", cx) {
                            val capSegment = remember(roomScheme, cx, capTiles) {
                                materialLoader.createTextureInstance(
                                    rackTimber,
                                    metallic = 0.04f,
                                    roughness = 0.82f,
                                    reflectance = WALL_REFLECTANCE
                                )
                            }
                            CubeNode(
                                size = Size(capSegW, BOARD_H, BOARD_D),
                                position = Position(
                                    -capW / 2f + (cx + 0.5f) * capSegW,
                                    rackTopY,
                                    0f
                                ),
                                materialInstance = capSegment,
                                apply = {
                                    isTouchable = false
                                    isShadowReceiver = true
                                    isShadowCaster = true
                                }
                            )
                        }
                    }

                    // **The room itself: a brick wall the rack stands against.**
                    //
                    // Until now there was nothing outside the rack. A friend of the owner's said
                    // the room was too dark to see the shelf and looked like a black void, and
                    // with one shelf of records that was most of the frame, literally empty.
                    //
                    // Sized to what the camera can reach — the whole pan range plus a frame in
                    // each direction at the furthest zoom, then a margin — and not a metre more.
                    // A fixed 24m wall was tried once for the rack's own panel and browsing fell
                    // to 31fps: it is a shadow receiver, and the shadows are tuned to a scene
                    // about 1.5m across (see the note on this block above).
                    //
                    // Tiled with the rack's own discipline: one shared texture, every tile its
                    // own MaterialInstance because a CubeNode destroys the one it is handed.
                    // Tiles hidden entirely behind the rack are skipped rather than drawn and
                    // depth-rejected.
                    val rackLeft = -panelW / 2f - SIDE_T
                    val rackRight = panelW / 2f + SIDE_T
                    val roomLeft = minOf(layout.minPanX - halfW, rackLeft) - ROOM_MARGIN
                    val roomRight = maxOf(layout.maxPanX + halfW, rackRight) + ROOM_MARGIN
                    val roomBottom = layout.floorY
                    val roomTop = maxOf(layout.maxPanY + halfH, rackTopY + BOARD_H) + ROOM_MARGIN
                    val tile = RoomSurface.TILE_M
                    val brickCols = ceil((roomRight - roomLeft) / tile).toInt().coerceIn(1, 160)
                    val brickRows = ceil((roomTop - roomBottom) / tile).toInt().coerceIn(1, 48)
                    for (by in 0 until brickRows) {
                        for (bx in 0 until brickCols) {
                            val x0 = roomLeft + bx * tile
                            val y0 = roomBottom + by * tile
                            val hidden = x0 >= rackLeft && x0 + tile <= rackRight &&
                                y0 >= rackBottomY && y0 + tile <= rackTopY
                            if (!hidden) key("brick", bx, by) {
                                val brick = remember(roomScheme, bx, by) {
                                    materialLoader.createTextureInstance(
                                        brickTexture,
                                        metallic = 0f,
                                        roughness = 0.9f,
                                        reflectance = BRICK_REFLECTANCE
                                    )
                                }
                                CubeNode(
                                    size = Size(tile, tile, ROOM_WALL_T),
                                    position = Position(x0 + tile / 2f, y0 + tile / 2f, ROOM_WALL_Z),
                                    materialInstance = brick,
                                    apply = {
                                        isTouchable = false
                                        isShadowReceiver = true
                                        isShadowCaster = false
                                    }
                                )
                            }
                        }
                    }

                    // **The ceiling spots** — the third light, and the room's shadow casters.
                    // See RoomLighting.CEILING_SPOT_CANDELA. The outer node's X is the frame
                    // driver's (RoomNodes.placeSpots, snapped to the track's grid); this inner
                    // one holds the height and depth, which come from the rack. Aimed at the
                    // middle of the rack's back panel, so a tall rack is lit top to bottom.
                    val spotY = rackTopY + RoomLighting.CEILING_SPOT_RISE
                    val spotAimY = (rackTopY + rackBottomY) / 2f
                    Node(apply = { roomNodes.spotRig = this }) {
                        Node(position = Position(0f, spotY, RoomLighting.CEILING_SPOT_Z)) {
                            repeat(RoomLighting.CEILING_SPOT_COUNT) { i ->
                                val centre = i == RoomLighting.CEILING_SPOT_COUNT / 2
                                LightNode(
                                    type = LightManager.Type.FOCUSED_SPOT,
                                    position = Position(
                                        (i - RoomLighting.CEILING_SPOT_COUNT / 2) *
                                            RoomLighting.CEILING_SPOT_SPACING,
                                        0f,
                                        0f
                                    ),
                                    direction = normalize(
                                        Direction(
                                            0f,
                                            spotAimY - spotY,
                                            WALL_Z - RoomLighting.CEILING_SPOT_Z
                                        )
                                    ),
                                    apply = {
                                        color(roomScheme.key[0], roomScheme.key[1], roomScheme.key[2])
                                        intensityCandela(RoomLighting.CEILING_SPOT_CANDELA)
                                        spotLightCone(
                                            RoomLighting.CEILING_SPOT_INNER,
                                            RoomLighting.CEILING_SPOT_OUTER
                                        )
                                        falloff(RoomLighting.CEILING_SPOT_FALLOFF)
                                        // All three at HIGH, the one over the view at MEDIUM,
                                        // none at LOW: a shadow map is a pass per casting light.
                                        castShadows(
                                            initialTier.shadowsOnFixtures &&
                                                (centre || initialTier == QualityTier.HIGH)
                                        )
                                        // **Shadows tuned for this room, not Filament's
                                        // defaults**, which are sized for metre-scale scenes:
                                        // at 1024px over a whole generic depth range with
                                        // metre-scale bias, a case's shadow came out blurred
                                        // and pushed off the case. See
                                        // RoomLighting.CEILING_SPOT_SHADOW_PX.
                                        shadowOptions(
                                            LightManager.ShadowOptions().apply {
                                                mapSize = minOf(
                                                    RoomLighting.CEILING_SPOT_SHADOW_PX,
                                                    initialTier.shadowMapPx
                                                )
                                                shadowBulbRadius = RoomLighting.KEY_BULB_RADIUS
                                                shadowNearHint = RoomLighting.KEY_SHADOW_NEAR
                                                shadowFarHint = RoomLighting.CEILING_SPOT_FALLOFF
                                                constantBias = RoomLighting.KEY_SHADOW_CONSTANT_BIAS
                                                normalBias = RoomLighting.KEY_SHADOW_NORMAL_BIAS
                                            }
                                        )
                                    }
                                )
                                // **The wall washer on the same head** — see
                                // RoomLighting.WASHER_CANDELA. Turned off the rack and onto the
                                // brick above it, so the neon sign and the framed records have
                                // something lighting them at all. No shadows: a washer exists to
                                // make a flat wall even.
                                LightNode(
                                    type = LightManager.Type.FOCUSED_SPOT,
                                    position = Position(
                                        (i - RoomLighting.CEILING_SPOT_COUNT / 2) *
                                            RoomLighting.CEILING_SPOT_SPACING,
                                        0f,
                                        0f
                                    ),
                                    direction = normalize(
                                        Direction(
                                            0f,
                                            rackTopY + RoomLighting.WASHER_AIM_ABOVE_CAP - spotY,
                                            WALL_Z - RoomLighting.CEILING_SPOT_Z
                                        )
                                    ),
                                    apply = {
                                        color(
                                            roomScheme.key[0],
                                            roomScheme.key[1],
                                            roomScheme.key[2]
                                        )
                                        intensityCandela(RoomLighting.WASHER_CANDELA)
                                        spotLightCone(
                                            RoomLighting.WASHER_CONE_INNER,
                                            RoomLighting.WASHER_CONE_OUTER
                                        )
                                        falloff(RoomLighting.WASHER_FALLOFF)
                                        castShadows(false)
                                    }
                                )
                            }

                            // **The aisle fittings** — the other half of the same track.
                            //
                            // Every light above aims backward, at the rack. These point at the
                            // floor in front of it, offset half a pitch so the two aims
                            // alternate down the track the way a shop's do. Without them the
                            // room has nothing lighting anything that is not a record, which
                            // is how the props ended up as black silhouettes on a black floor.
                            // See RoomLighting.AISLE_SPOT_CANDELA.
                            // How far an aisle head has to throw. Everything about these is
                            // derived from it — see RoomLighting.AISLE_SPOT_LUX.
                            val aisleDrop = (spotY - layout.floorY).coerceAtLeast(0.5f)
                            repeat(RoomLighting.CEILING_SPOT_COUNT) { i ->
                                LightNode(
                                    type = LightManager.Type.FOCUSED_SPOT,
                                    position = Position(
                                        (i - RoomLighting.CEILING_SPOT_COUNT / 2) *
                                            RoomLighting.CEILING_SPOT_SPACING +
                                            RoomLighting.AISLE_SPOT_OFFSET,
                                        0f,
                                        0f
                                    ),
                                    direction = normalize(
                                        Direction(
                                            0f,
                                            layout.floorY - spotY,
                                            RoomLighting.AISLE_SPOT_AIM_Z -
                                                RoomLighting.CEILING_SPOT_Z
                                        )
                                    ),
                                    apply = {
                                        color(
                                            roomScheme.key[0],
                                            roomScheme.key[1],
                                            roomScheme.key[2]
                                        )
                                        // Intensity from the DROP, not a fixed figure: see
                                        // RoomLighting.AISLE_SPOT_LUX. The distance to the
                                        // floor is a function of how many records the owner
                                        // owns, so a constant candela is dark on a tall
                                        // library and blinding on a short one.
                                        intensityCandela(
                                            (RoomLighting.AISLE_SPOT_LUX * aisleDrop * aisleDrop)
                                                .coerceAtMost(RoomLighting.AISLE_SPOT_MAX_CANDELA)
                                        )
                                        spotLightCone(
                                            RoomLighting.AISLE_SPOT_INNER,
                                            RoomLighting.AISLE_SPOT_OUTER
                                        )
                                        falloff(aisleDrop * RoomLighting.AISLE_SPOT_REACH)
                                        // Shadows only on HIGH, and only from the fitting over
                                        // the view. A shadow map is a whole pass per casting
                                        // light and the rack fittings have already spent that
                                        // budget — but a prop standing on a floor with no
                                        // shadow under it is a prop floating over one, which
                                        // is the whole reason these exist.
                                        castShadows(
                                            initialTier == QualityTier.HIGH &&
                                                i == RoomLighting.CEILING_SPOT_COUNT / 2
                                        )
                                        shadowOptions(
                                            LightManager.ShadowOptions().apply {
                                                mapSize = minOf(
                                                    RoomLighting.CEILING_SPOT_SHADOW_PX,
                                                    initialTier.shadowMapPx
                                                )
                                                shadowBulbRadius = RoomLighting.KEY_BULB_RADIUS
                                                shadowNearHint = RoomLighting.KEY_SHADOW_NEAR
                                                shadowFarHint = aisleDrop * RoomLighting.AISLE_SPOT_REACH
                                                constantBias =
                                                    RoomLighting.KEY_SHADOW_CONSTANT_BIAS
                                                normalBias = RoomLighting.KEY_SHADOW_NORMAL_BIAS
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // **The neon JAWON sign**, on the brick just above the rack's cap, centred on
                    // the rack — the shop's name over its records. See NeonSign. Its instance is
                    // made here, in the rack's own scope, because a PlaneNode destroys the one it
                    // is handed; the room's accent colours it, and a point light just in front
                    // of it lends that colour to the brick around it.
                    val mask = neonMask
                    if (mask != null) {
                        val neonColour = NeonSign.linear(roomScheme.accent)
                        val neon = remember(roomScheme, mask) {
                            materialLoader.createInstance(neonMaterial).apply {
                                setParameter("mask", mask, NEON_SAMPLER)
                                setParameter("color", neonColour[0], neonColour[1], neonColour[2])
                                setParameter("coreGlow", NeonSign.CORE, NeonSign.GLOW)
                                setParameter("power", 1f)
                            }
                        }
                        val signY = rackTopY + BOARD_H / 2f + NeonSign.GAP_ABOVE_CAP_M +
                            NeonSign.HEIGHT_M / 2f
                        val signZ = ROOM_WALL_Z + ROOM_WALL_T / 2f + NeonSign.STANDOFF_M
                        PlaneNode(
                            size = Size(NeonSign.WIDTH_M, NeonSign.HEIGHT_M, 0f),
                            normal = Direction(z = 1f),
                            materialInstance = neon,
                            position = Position(0f, signY, signZ),
                            apply = {
                                // The shop's name is also its door: a tap on it goes out to the
                                // showroom, in a build that has one.
                                name = NEON_SIGN_NODE
                                isTouchable = showroomReachable
                                isShadowCaster = false
                                isShadowReceiver = false
                            }
                        )
                        LightNode(
                            type = LightManager.Type.POINT,
                            position = Position(0f, signY, signZ + NeonSign.LIGHT_Z_M),
                            apply = {
                                color(neonColour[0], neonColour[1], neonColour[2])
                                intensityCandela(NeonSign.LIGHT_CANDELA)
                                falloff(NeonSign.LIGHT_FALLOFF)
                                castShadows(false)
                            }
                        )
                    }

                    // **The framed records**, in the band the washers light, spread along the
                    // wall with the middle left to the sign. See WallDecor.
                    FramedAlbums(
                        prints = framedAlbums,
                        timber = rackTimber,
                        materialLoader = materialLoader,
                        positions = WallDecor.slots(roomLeft, roomRight),
                        bandY = rackTopY + RoomLighting.WASHER_AIM_ABOVE_CAP,
                        wallZ = ROOM_WALL_Z + ROOM_WALL_T / 2f
                    )

                    // The shop's own things: a deck and a plant on the floor out past the
                    // rack, a clock high on the brick. See RoomProps for why none of them is
                    // on top of the rack.
                    RoomPropNodes(
                        turntable = turntableModel,
                        clock = clockModel,
                        plant = plantModel,
                        floorY = layout.floorY,
                        rackTopY = rackTopY,
                        wallZ = ROOM_WALL_Z + ROOM_WALL_T / 2f,
                        minPanX = layout.minPanX,
                        maxPanX = layout.maxPanX
                    )

                    // The posters hang on the frames' own grid, offset half a pitch, so the
                    // wall alternates paper and glass rather than presenting a row of either.
                    GigPosters(
                        posters = posters,
                        materialLoader = materialLoader,
                        positions = GigPoster.slots(roomLeft, roomRight),
                        bandY = rackTopY + RoomLighting.WASHER_AIM_ABOVE_CAP,
                        wallZ = ROOM_WALL_Z + ROOM_WALL_T / 2f
                    )

                    // **A floor, which this room has never had.**
                    //
                    // RoomAtmosphere's own fog comment said so outright: "Height fog would need a
                    // floor to pool on; this room has none." Until now the shelving stood in
                    // blackness - nothing cast a shadow downward, there was no surface for the
                    // lamp to graze, and the room had no bottom. Three of the reasons it read as
                    // a backdrop rather than a place.
                    //
                    // Positioned from the layout rather than fixed: see WallLayout.floorY. Built
                    // in the same rebuild scope as the wall so its material shares that
                    // lifetime, and from the wall's own colour darkened, so it belongs to
                    // whichever of the four modes is showing without being tuned four times.
                    val floorDepth = FLOOR_REACH - WALL_Z
                    // Wall to wall now, not rack-wide: with a room around the rack, a floor that
                    // stopped at the rack's ends was a slab floating in front of a wall.
                    val floorW = roomRight - roomLeft
                    val floorSurface = remember(roomScheme, floorW, floorDepth) {
                        val texture = ImageTexture.Builder()
                            // sRGB, for the same reason as the wall above.
                            .bitmap(
                                RoomSurface.floorBitmap(roomScheme.wall, floorW / floorDepth),
                                TextureType.COLOR
                            )
                            .build(engine)
                        materialLoader.createTextureInstance(
                            texture,
                            metallic = 0f,
                            // Smoother and a touch more reflective than the wall, deliberately.
                            // A floor seen at a grazing angle is where one lamp shows as a long
                            // soft sheen, and that sheen is most of what tells the eye the
                            // ground is there at all. Same units correction as the wall above.
                            roughness = 0.72f,
                            reflectance = FLOOR_REFLECTANCE
                        )
                    }
                    CubeNode(
                        size = Size(floorW, FLOOR_T, floorDepth),
                        position = Position(
                            (roomLeft + roomRight) / 2f,
                            layout.floorY,
                            // Centred between the wall and the floor's far reach, so the slab
                            // runs from behind the shelving to past the camera.
                            (WALL_Z + FLOOR_REACH) / 2f
                        ),
                        materialInstance = floorSurface,
                        apply = {
                            isTouchable = false
                            // Receives, never casts. There is nothing below a floor to catch its
                            // shadow, and casting would spend shadow-map budget for no return.
                            isShadowReceiver = true
                            isShadowCaster = false
                        }
                    )
                }
            }
            // Keyed by INDEX, not shelf id � the same discipline the case pool uses, and for
            // the same reason.
            //
            // On shelf id, every change to the shelf list rebuilds these nodes, and a
            // CubeNode destroys the `MaterialInstance` it was handed on the way out. That
            // instance is shared and remembered above, so the *next* board gets a dead one:
            // importing albums into an empty library swapped the placeholder row for real
            // ones and the shelf boards vanished until the app was relaunched. Exactly the
            // fault the wall backing had, one node along.
            //
            // By index, a row change re-points a node that already exists instead of
            // destroying it, so the material outlives the churn.
            visibleRows.forEachIndexed { rowIndex, row ->
                // Keyed on the width as well as the row, so the board and the material it
                // owns are rebuilt together. A CubeNode destroys the instance handed to it,
                // so one remembered further out dies with the first board that rebuilds and
                // leaves every later board with a dead material � which is why the shelf
                // disappeared on import, and again on delete.
                key(rowIndex, layout.wallWidth, roomScheme) {
                    // **The shelf itself, in the same timber as the panel behind it.**
                    //
                    // This was one node wearing one flat colour, which is what left the rack
                    // reading as boards floating in front of a wall rather than as one piece of
                    // furniture: the back panel had a material and the shelves did not.
                    //
                    // Segmented along its length for the reason the back panel is — the texture
                    // stretches across a node exactly once, and a board is well over a metre
                    // wide, so one node puts a couple of texels on every centimetre. Each
                    // segment is about RoomSurface.TILE_M and gets the whole bitmap.
                    //
                    // The grain runs along the board — see RoomSurface.veneerTile.
                    val boardTiles =
                        ceil(layout.wallWidth / RoomSurface.TILE_M).toInt().coerceIn(1, 64)
                    val boardW = layout.wallWidth / boardTiles
                    repeat(boardTiles) { bx ->
                        key(bx) {
                            // Its own instance, never a shared one: a CubeNode destroys the
                            // MaterialInstance it is handed, and one shared across the segments
                            // would die with the first to rebuild and leave the rest of the
                            // shelf holding a dead one. That is the exact fault that made the
                            // boards vanish on import once already.
                            val segment = remember(
                                roomScheme, bx, boardTiles, ribbonMode, ribbonGain, topTubeY
                            ) {
                                if (!ribbonMode.bakedPanel) {
                                    materialLoader.createTextureInstance(
                                        rackTimber,
                                        metallic = 0.04f,
                                        roughness = 0.82f,
                                        reflectance = WALL_REFLECTANCE
                                    )
                                } else {
                                    // With the ribbon's light on its top: see ribbonInstance.
                                    ribbonInstance(rackTimber, metallic = 0.04f, roughness = 0.82f, board = true)
                                }
                            }
                            CubeNode(
                                size = Size(boardW, BOARD_H, BOARD_D),
                                position = Position(
                                    -layout.wallWidth / 2f + (bx + 0.5f) * boardW,
                                    row.boardY,
                                    0f
                                ),
                                materialInstance = segment,
                                apply = {
                                    isTouchable = false
                                    isShadowReceiver = true
                                    isShadowCaster = true
                                }
                            )
                        }
                    }

                    // **The tube.** One node spanning the whole shelf, so it is one continuous
                    // strip rather than anything that could read as a row of lamps.
                    //
                    // In the corner, touching both surfaces of it: flush under the board above
                    // (RoomLighting.SHELF_LIGHT_LIFT) and lying against the panel behind
                    // (RoomLighting.STRIP_Z). There is no bracket any more. The one that was
                    // here covered 8.7mm of panel under the tube with a dark unlit plate, so the
                    // brightest part of the gradient was hidden and what showed was a dark band
                    // and then a glow — the reason the light did not look like it came from here.
                    //
                    // Unlit and over bloom's 1.0 threshold, so it goes straight to the output and
                    // glows — in this room's own colour (RoomScheme.tube), which is also the
                    // colour of the light the ribbon throws, so the two cannot disagree.
                    // Remembered in this row's key alongside the board, because a CubeNode
                    // destroys the MaterialInstance it is handed; keyed on the scheme, so a room
                    // change rebuilds it in the new colour.
                    val tube = remember(rowIndex, roomScheme) {
                        materialLoader.createUnlitColorInstance(
                            dev.romainguy.kotlin.math.Float4(
                                roomScheme.tube[0],
                                roomScheme.tube[1],
                                roomScheme.tube[2],
                                1f
                            )
                        )
                    }
                    CubeNode(
                        size = Size(
                            layout.wallWidth,
                            RoomLighting.STRIP_H,
                            RoomLighting.STRIP_D
                        ),
                        position = Position(
                            0f,
                            row.centerY + WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT,
                            RoomLighting.STRIP_Z
                        ),
                        materialInstance = tube,
                        apply = {
                            isTouchable = false
                            // Neither: it is a light fitting. Casting would lay a bar of shadow
                            // across the panel it is there to light.
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )

                    // **The shelf-edge rail's own tube**, under the front lip of the board
                    // above — the fixture whose light shows the records (RoomLighting.RAIL_*).
                    // Thinner and a third as bright as the back tube: a shop's working light,
                    // not the neon the bay is styled around. In the room's lamp colour, so what
                    // it shows of a sleeve is the sleeve's own colour.
                    val rail = remember(rowIndex, roomScheme) {
                        val tint = RoomLighting.railColour(roomScheme.key)
                        materialLoader.createUnlitColorInstance(
                            dev.romainguy.kotlin.math.Float4(
                                tint[0] * RoomLighting.RAIL_GLOW,
                                tint[1] * RoomLighting.RAIL_GLOW,
                                tint[2] * RoomLighting.RAIL_GLOW,
                                1f
                            )
                        )
                    }
                    CubeNode(
                        size = Size(
                            layout.wallWidth,
                            RoomLighting.RAIL_H,
                            RoomLighting.RAIL_D
                        ),
                        position = Position(
                            0f,
                            row.centerY + WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT,
                            RoomLighting.RAIL_Z
                        ),
                        materialInstance = rail,
                        apply = {
                            isTouchable = false
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )

                    // **The air under the tube: two sheets of lit haze.** Filament cannot light
                    // the air itself (RoomAir says why), so this is drawn — a tall sheet in the
                    // gap behind the records and a short one near the front of the shelf, both
                    // hanging from the tube and fading down. Part of the rack, in this row's key
                    // like the tube, so the haze belongs to the furniture and can never slide
                    // across it the way the old ribbon did (trap 5, "a light that is part of the
                    // furniture must be positioned by the furniture").
                    //
                    // PlaneNodes, not ImageNodes, though the material is the same unlit blended
                    // one ImageNode uses: rows come and go with the library, and an ImageNode
                    // that goes aborts the process (trap 1, "never destroy an ImageNode"). A
                    // PlaneNode destroys only the instance it is handed, and the texture it
                    // samples belongs to the room above, not to it.
                    val hazeBack = remember(rowIndex, roomScheme) {
                        materialLoader.createImageInstance(hazeBackTexture, HAZE_SAMPLER)
                    }
                    val hazeFront = remember(rowIndex, roomScheme) {
                        materialLoader.createImageInstance(hazeFrontTexture, HAZE_SAMPLER)
                    }
                    val tubeCentreY =
                        row.centerY + WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT
                    val tubeBottomY = tubeCentreY - RoomLighting.STRIP_H / 2f
                    val boardAboveY = tubeCentreY + RoomLighting.STRIP_H / 2f
                    // Facing the room, stated. A SceneView plane does not face +Z by default —
                    // every ImageNode in this codebase passes the normal for that reason — and
                    // the first build of these lay flat as shelves, edge-on to the camera and
                    // invisible, with nothing to say they were there at all.
                    PlaneNode(
                        size = Size(layout.wallWidth, RoomAir.HAZE_BACK_H, 0f),
                        normal = Direction(z = 1f),
                        materialInstance = hazeBack,
                        position = Position(
                            0f,
                            tubeBottomY - RoomAir.HAZE_BACK_H / 2f,
                            RoomAir.HAZE_BACK_Z
                        ),
                        apply = {
                            isTouchable = false
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )
                    PlaneNode(
                        size = Size(layout.wallWidth, RoomAir.HAZE_FRONT_H, 0f),
                        normal = Direction(z = 1f),
                        materialInstance = hazeFront,
                        position = Position(
                            0f,
                            boardAboveY - RoomAir.HAZE_FRONT_H / 2f,
                            RoomAir.HAZE_FRONT_Z
                        ),
                        apply = {
                            isTouchable = false
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )

                    ViewNode(
                        windowManager = viewNodeManager,
                        // Unlit. The plate sits on the front edge of a board, which in the
                        // two-light room nothing reaches: the ribbon grazes it from above and
                        // behind at N.L = 0, and the underlights are confined to the artwork. A
                        // label that has to be read cannot depend on light that is not there.
                        unlit = true,
                        position = Position(row.plateX, row.boardY, BOARD_D / 2f + PLATE_LIFT),
                        apply = {
                            pxPerUnits = PLATE_PX_PER_UNIT
                            isTouchable = false
                        }
                    ) {
                        ShelfPlate(name = row.name)
                    }
                }
            }
            // The wall's cases come from a POOL, keyed by slot index rather than album id.
            //
            // Mounting a node per visible album and dropping it when it left the frustum
            // window destroyed its ImageNodes, and ImageNode.destroy() is broken on this
            // SceneView version � the process aborts with "Invalid texture still bound to
            // MaterialInstance". A hard vertical fling pulls whole rows out at once and
            // killed the app within a few swipes. See CaseSlots for the full story; do not
            // "simplify" this back into a keyed loop over visibleCases.
            CaseSlotNodes(
                slots = caseSlots,
                materialLoader = materialLoader,
                size = casePoolSize,
                caseInstances = caseInstances,
                beamInstances = beamInstances,
                plates = railPlates
            )
            // The plates' rail light kept in step with the room: the mode's colour, the rack's
            // rows, the bench gain. Writes only when one of them changed. The switches are read
            // here, in composition, so a change recomposes this and the effect sees it: a state
            // read inside the effect itself subscribes nothing.
            val plateGain = RoomSwitches.plateGain
            val platesMasked = RoomSwitches.rail == RoomSwitches.Rail.MASK
            SideEffect {
                railPlates.update(
                    light = RoomLighting.railColour(roomScheme.key),
                    gain = plateGain,
                    railTopY = RailLight.railY(layout.rows.firstOrNull()?.centerY ?: 0f),
                    rowCount = layout.rows.size,
                    masked = platesMasked
                )
            }
            // The disc that breaks when an album is removed. Mounted always, hidden until
            // it is needed � see DiscardScene.
            DiscardSceneNodes(
                nodes = discardNodes,
                shardsInstance = shardsModel,
                discTexture = discTexture,
                sampler = sampler
            )
            // Dust hangs in front of the wall, animated entirely on the GPU. Not touchable
            // and casts nothing — it is glow, and a shadow-casting mote would be absurd.
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
            // The volumetric fog: one quad, raymarched per pixel by fog.mat. Absent on LOW.
            // Kept on with reduced motion — it is atmosphere, not movement — but held still;
            // see the frame driver.
            MeshNode(
                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer = fogGeometry.vertexBuffer,
                indexBuffer = fogGeometry.indexBuffer,
                boundingBox = fogGeometry.boundingBox,
                materialInstance = fogMaterial,
                apply = {
                    isTouchable = false
                    isShadowCaster = false
                    isShadowReceiver = false
                    isVisible = fogSteps > 0
                }
            )

            // Mounted once and hidden when idle, never torn down — see ActiveCaseNodes.
            ActiveCaseNodes(
                nodes = actNodes,
                materialLoader = materialLoader,
                trayModel = trayModel,
                lidModel = lidModel,
                discModel = discModel,
                plates = railPlates
            )
        }

        // The room, as TalkBack meets it (0.7.5): a layer over the scene carrying its description and
        // actions — SceneView does not carry semantics on its own modifier. It takes no touches.
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    val open = selectedCase
                    val front = focusCase
                    contentDescription = when {
                        open != null -> "${open.title} by ${open.artist}, open and on the deck below."
                        front != null -> "Your wall of records, ${layout.cases.size} on its shelves. " +
                            "${front.title} by ${front.artist} is in front."
                        else -> "Your wall of records. It is empty; add music from the bar below."
                    }
                    customActions = buildList {
                        if (open != null) {
                            add(androidx.compose.ui.semantics.CustomAccessibilityAction("Put the record away") {
                                closeCase(); true
                            })
                            add(androidx.compose.ui.semantics.CustomAccessibilityAction(
                                if (deck.isPlaying) "Pause" else "Play"
                            ) { viewModel.onPlayPause(); true })
                        } else if (front != null) {
                            add(androidx.compose.ui.semantics.CustomAccessibilityAction("Open ${front.title}") {
                                openCase(front.albumId); true
                            })
                            fun step(by: Int): Boolean {
                                val ordered = layout.cases.sortedWith(compareBy({ it.row }, { it.col }))
                                val i = ordered.indexOfFirst { it.albumId == front.albumId }
                                val target = ordered.getOrNull(i + by) ?: return false
                                scope.launch { panX.animateTo(target.x, animationSpec = FLIP_SPRING) }
                                scope.launch { panY.animateTo(target.y, animationSpec = FLIP_SPRING) }
                                return true
                            }
                            add(androidx.compose.ui.semantics.CustomAccessibilityAction("Next record") { step(1) })
                            add(androidx.compose.ui.semantics.CustomAccessibilityAction("Previous record") { step(-1) })
                        }
                    }
                }
        )


        if (layout.isWaiting) {
            // Sits low, under the empty board rather than over the middle of it, so the shelf
            // itself is what the eye lands on first. The room is the pitch; this is the
            // caption.
            // TopCenter, matching how this is laid out � it pads down from the status bar and
            // occupies the slot the browse label uses once there are records. Aligned to the
            // bottom it ran straight through the ADD / SHELVES / SETTINGS row.
            WaitingForRecords(
                onAddAlbum = onAddAlbum,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        } else if (selectedAlbumId == null) {
            // While a case is open the disc occupies this band, and the album is already the
            // whole subject — the browse label would only collide with it.
            FocusLabel(
                case = focusCase,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = FOCUS_MAX_W)
            )
        }

        pendingDelete?.let { case ->
            RemoveAlbumDialog(
                case = case,
                onConfirm = {
                    discardCase(case)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
                onEdit = {
                    pendingDelete = null
                    onEditAlbum(case.albumId)
                }
            )
        }

        // Back closes an open case. While browsing it goes up a level, to the showroom between
        // formats, in a build that has one; in one that does not, it is left alone and exits the
        // app as it always has. The showroom's own Back is what leaves the app from there.
        BackHandler(enabled = !covered && !com.jawon.app.ui.common.LocalRoomsCardOpen.current && selectedAlbumId != null) { closeCase() }
        BackHandler(enabled = !covered && !com.jawon.app.ui.common.LocalRoomsCardOpen.current && selectedAlbumId == null && showroomReachable) { onOpenShowroom() }

        // Search is opened from the chrome row and takes its place while it is up. Declared
        // after the two above so that, while it is open, Back closes it and nothing else.
        var searchOpen by remember { mutableStateOf(false) }
        // A case opened some other way — tapped above the open panel — takes search down with it.
        LaunchedEffect(selectedAlbumId) { if (selectedAlbumId != null) searchOpen = false }
        BackHandler(enabled = !covered && !com.jawon.app.ui.common.LocalRoomsCardOpen.current && searchOpen) { searchOpen = false }

        // **What happens when the record finishes.**
        //
        // Until now: nothing. The queue ran out, playback stopped, and the case sat open in
        // front of a dead deck until someone pressed back — the one moment every listener
        // reaches, every time, and the app had no answer for it.
        //
        // The answer is the one already in the room rather than a new screen: the disc stops,
        // the case closes itself back into its slot, and you are left on the wall looking at
        // the record you just heard. That is the open sequence run backwards, which
        // `OpenSequence` supports by construction — every curve is a pure function of
        // `progress`, so closing is the same five beats with the clock running the other way.
        //
        // Deliberately no autoplay. The album is the atomic unit (PLAN §2), and rolling into
        // another record is the shuffle-a-huge-library behaviour this app exists not to have.
        // The wall is the offer; tapping the next case is the user's move.
        // A record UNDO put back: the wall turns to it, on the magnet's spring, once it is in the
        // layout again — so it is in front of you, not somewhere along the shelf.
        LaunchedEffect(Unit) {
            viewModel.restored.collect { albumId ->
                val target = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                    androidx.compose.runtime.snapshotFlow { layout.cases.firstOrNull { it.albumId == albumId } }
                        .first { it != null }
                } ?: return@collect
                scope.launch { panX.animateTo(target.x, animationSpec = FLIP_SPRING) }
                scope.launch { panY.animateTo(target.y, animationSpec = FLIP_SPRING) }
            }
        }

        LaunchedEffect(Unit) {
            viewModel.albumEnded.collect {
                // Guarded because the event fires whether or not a case is open — a record can
                // end while the deck is closed and the room is being browsed, and closing an
                // already-closed case would run the sequence backwards from nothing.
                if (selectedAlbumId != null) closeCase()
                // And the record is over, so it is put away and the bar goes — but only by this
                // room while it is the one on screen. Parked behind the pocket player (the stage
                // keeps it), it would otherwise reach into the shared view model and take the
                // pocket player's Now Playing away the moment an album ended there.
                if (!currentCovered.value) viewModel.stop()
            }
        }

        // Search, only while browsing. Like the crate flip it is a Compose overlay with its
        // own pointer input, so the scene never sees these events (ENGINEERING.md trap 10), and it
        // lands on a record through the same `animateTo` the flip and the magnet use.
        if (selectedAlbumId == null && searchOpen) {
            WallSearchPanel(
                layout = layout,
                onClose = { searchOpen = false },
                // **The only way into a record that does not go through the 3D surface.**
                //
                // Search used to pan the camera and stop there, leaving the user to find and
                // tap the case — which is fine with eyes and impossible without them:
                // `openCase` is otherwise reachable only from a Filament hit test on a surface
                // that never enters the view system. A screen reader could reach every screen
                // in this app, read the whole library and work every setting, and could not
                // start a record.
                //
                // It is also simply better sighted behaviour. You searched for a record; two
                // steps to hear it was one too many.
                onOpen = { target ->
                    // Pan first so the sequence starts from the right framing — see the note
                    // in `openCase` about FRONT_GAP being tuned against the canonical camera.
                    scope.launch { panX.animateTo(target.x, animationSpec = FLIP_SPRING) }
                    scope.launch { panY.animateTo(target.y, animationSpec = FLIP_SPRING) }
                    openCase(target.albumId)
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // The crate flip, only while browsing and only on a library big enough to need it.
        //
        // `animateTo` on the same `Animatable`s the magnet uses, which is what keeps this safe:
        // starting one cancels whatever settle was in flight rather than fighting it, so the
        // two can never both be driving the wall (ENGINEERING.md trap 10).
        if (selectedAlbumId == null) {
            // Recomposed by the clock only while it matters: `railVisibleUntil` moves on
            // the pan, and this reads it against a tick that stops as soon as the rail is
            // down. Nothing here runs while the wall is still.
            var railNow by remember { mutableLongStateOf(0L) }
            LaunchedEffect(railVisibleUntil) {
                while (SystemClock.elapsedRealtime() < railVisibleUntil) {
                    railNow = SystemClock.elapsedRealtime()
                    delay(RAIL_TICK_MS)
                }
                railNow = SystemClock.elapsedRealtime()
            }
            val visibleRail = railNow < railVisibleUntil

            CrateFlip(
                layout = layout,
                // Two coroutines, not three. This used to launch an outer one that launched
                // two more, per album passed — and a drag across a 200-album library passes
                // one every five pixels, so a single flip started several hundred coroutines
                // that each immediately cancelled the one before. The axes still need one
                // each, because `animateTo` suspends until it arrives.
                onScrub = { target ->
                    scope.launch { panX.animateTo(target.x, animationSpec = FLIP_SPRING) }
                    scope.launch { panY.animateTo(target.y, animationSpec = FLIP_SPRING) }
                },
                // The same spring the magnet and the fling settle with, so a record reached
                // by the rail arrives exactly like one reached by a swipe.
                onSettle = { target ->
                    scope.launch { panX.animateTo(target.x, animationSpec = SETTLE_SPRING) }
                    scope.launch { panY.animateTo(target.y, animationSpec = SETTLE_SPRING) }
                },
                // Shown while the wall is moving and for a moment after, rather than
                // always. `railVisibleUntil` is stamped by the pan's own detents, so the rail
                // appears for exactly the gesture the owner described — touch and slide —
                // and for its own drag, which keeps it usable as a control in its own right.
                visible = visibleRail,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp, top = 96.dp, bottom = 128.dp)
            )
        }

        // Chrome, only while browsing: with a case open the deck owns the screen, and with
        // search open the field stands where the chrome was.
        if (selectedAlbumId == null && !searchOpen) {
            // Centred along the bottom: the top belongs to the focus label (a long title ran
            // straight under the icons when they lived up there), and on a phone this tall
            // the bottom is the only comfortable reach. navigationBarsPadding keeps it off
            // the system nav, which it was sitting on.
            // The now-playing bar and the chrome share one column and one gradient, so the
            // bar sits *on* the same ground the icons do rather than floating above it on a
            // second wash of its own — which was the first attempt and read as two bars.
            Column(
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
                    .padding(top = 44.dp, bottom = 10.dp)
            ) {
            // Clamped inside the full-width ground above, so the surface still spans the window
            // while the controls on it stay within reach. See CHROME_MAX_W.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = CHROME_MAX_W)
            ) {
            // The first time on the wall, and only once there is a record to open.
            if (com.jawon.app.ui.common.Hints.CD !in hintsSeen && layout.cases.isNotEmpty() && !covered) {
                com.jawon.app.ui.common.FirstRunHint(
                    title = "This is your wall",
                    body = "Tap a record to open it, then turn the spinning disc to scratch. " +
                        "Press and hold a case to edit or remove it.",
                    onGotIt = { viewModel.onHintSeen(com.jawon.app.ui.common.Hints.CD) },
                    onGuide = {
                        viewModel.onHintSeen(com.jawon.app.ui.common.Hints.CD)
                        onOpenGuide()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            // A record just taken off the wall, for as long as it can still come back.
            removed?.let { r ->
                RemovedBar(
                    removed = r,
                    windowMs = WallSceneViewModel.UNDO_WINDOW_MS,
                    onUndo = viewModel::undoRemove
                )
            }
            nowPlaying?.let { playing ->
                NowPlayingBar(
                    nowPlaying = playing,
                    trackTitle = deck.currentTrack?.title,
                    isPlaying = deck.isPlaying,
                    durationMs = deck.durationMs,
                    position = viewModel.positionMs.collectAsStateWithLifecycle(),
                    haptics = haptics,
                    onOpen = {
                        // Pan to the record first, for the same reason search does — see the
                        // note on FRONT_GAP in `openCase`. Without it the sequence starts from
                        // whatever framing browsing happened to leave behind.
                        layout.cases.firstOrNull { it.albumId == playing.albumId }?.let { target ->
                            scope.launch { panX.animateTo(target.x, animationSpec = FLIP_SPRING) }
                            scope.launch { panY.animateTo(target.y, animationSpec = FLIP_SPRING) }
                        }
                        openCase(playing.albumId)
                    },
                    onPlayPause = viewModel::onPlayPause,
                    onNext = { viewModel.onNext() },
                    onPrevious = { viewModel.onPrevious() },
                    onPutAway = viewModel::stop
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // Even shares rather than a huddle in the middle: wide targets across the
                // bottom are the easiest thing to hit one-handed on a phone this size.
                //
                // FIND lived in the top corner until 0.7, alone: under the status bar's icons
                // on an edge-to-edge window, in the way of a long title, and at the far end of
                // the reach the comment above is about.
                ChromeAction(Icons.Default.Add, "ADD", onAddAlbum, Modifier.weight(1f))
                if (layout.cases.size >= SEARCH_MIN_ALBUMS) {
                    ChromeAction(
                        Icons.Default.Search, "FIND", { searchOpen = true }, Modifier.weight(1f)
                    )
                }
                // ROOMS, in the middle, wearing Jawon's own mark (0.7.2): the brand is the door between
                // the rooms. Not shown in a build with only one room to go to.
                if (showroomReachable) {
                    ChromeAction(
                        icon = null,
                        label = "ROOMS",
                        onClick = onOpenRooms,
                        modifier = Modifier.weight(1f),
                        iconContent = {
                            com.jawon.app.ui.common.JawonMark(
                                size = 24.dp,
                                color = LabelCream.copy(alpha = 0.82f),
                                contentDescription = null
                            )
                        }
                    )
                }
                ChromeAction(
                    Icons.AutoMirrored.Filled.List, "SHELVES", onOpenShelfManager,
                    Modifier.weight(1f)
                )
                ChromeAction(
                    Icons.Default.Settings, "SETTINGS", onOpenSettings, Modifier.weight(1f)
                )
            }
            }
            }
        }

        // The deck's entrance.
        //
        // It used to arrive on `animateFloatAsState`'s default spring, which is quick and
        // built for a control changing state — so the whole panel, transport, tracklist and
        // quality readout appeared almost at once, at the end of a 1.9s sequence that had
        // been unhurried up to that point. An explicit, slower ease matches what it is
        // following, and the rise below gives it somewhere to come *from*.
        val deckAlpha by animateFloatAsState(
            targetValue = if (deckRevealed) 1f else 0f,
            animationSpec = tween(
                durationMillis = DECK_FADE_MS,
                easing = FastOutSlowInEasing
            ),
            label = "deckAlpha"
        )
        if (deckAlpha > 0.01f) {
            val openCase = selectedCase
            DeckOverlay(
                title = openCase?.title.orEmpty(),
                artist = openCase?.artist.orEmpty(),
                filterPosition = viewModel.filterPosition,
                onFilterChange = viewModel::onFilterChange,
                speedPosition = viewModel.speedPosition,
                pitchPosition = viewModel.pitchPosition,
                onSpeedChange = viewModel::onSpeedChange,
                onPitchChange = viewModel::onPitchChange,
                onPitchCommit = viewModel::onPitchCommit,
                lyrics = viewModel.lyrics,
                drivePosition = viewModel.drivePosition,
                onDriveChange = viewModel::onDriveChange,
                echoTime = viewModel.echoTime,
                echoFeedback = viewModel.echoFeedback,
                echoMix = viewModel.echoMix,
                onEchoTime = viewModel::onEchoTimeChange,
                onEchoFeedback = viewModel::onEchoFeedbackChange,
                onEchoMix = viewModel::onEchoMixChange,
                nativeAvailable = viewModel.nativeAvailable,
                bassStrength = viewModel.bassStrength,
                stereoWidth = viewModel.stereoWidth,
                reverbPreset = viewModel.reverbPreset,
                loudnessGain = viewModel.loudnessGain,
                onBassChange = viewModel::onBassChange,
                onWidthChange = viewModel::onWidthChange,
                onReverbChange = viewModel::onReverbChange,
                onLoudnessChange = viewModel::onLoudnessChange,
                sleepMode = sleepMode,
                sleepRemainingMs = sleepRemainingMs,
                onSleepModeChange = viewModel::onSleepModeChange,
                deck = deck,
                positionFlow = viewModel.positionMs,
                onPlayPause = viewModel::onPlayPause,
                onNext = viewModel::onNext,
                onPrevious = viewModel::onPrevious,
                onSeek = viewModel::onSeek,
                onSelectTrack = viewModel::onSelectTrack,
                offline = viewModel.offline.collectAsStateWithLifecycle(),
                downloading = viewModel.downloading.collectAsStateWithLifecycle(),
                offlineMessage = viewModel.offlineMessage.collectAsStateWithLifecycle(),
                onKeepOffline = viewModel::onKeepOffline,
                onCancelKeepOffline = viewModel::onCancelKeepOffline,
                onDropOffline = { viewModel.onDropOffline() },
                scrubReadout = scrubReadout,
                eqAvailable = eqAvailable,
                eqEnabled = eqEnabled,
                eqBands = eqBands,
                eqRangeMb = eqRangeMb,
                eqPresets = eqPresets,
                onEqEnabledChange = viewModel::onEqEnabledChanged,
                onEqBandChange = viewModel::onEqBandChanged,
                onEqPreset = viewModel::onEqPreset,
                onEqReset = viewModel::onEqReset,
                engagedChannels = viewModel.engagedChannels,
                onChannelToggle = viewModel::onChannelToggle,
                deckPage = deckPage,
                onDeckPageChange = {
                    deckPage = it
                    // The rack is a whole instrument — eight keys, three faders and a window —
                    // and at anything short of the tallest stop it arrives already clipped. So
                    // SOUND asks for the full panel rather than merely more than PEEK. Lyrics
                    // only need to escape the smallest stop, where one line is no use.
                    when (it) {
                        PAGE_SOUND -> if (detent != DeckDetent.FULL) detent = DeckDetent.FULL
                        PAGE_LYRICS -> if (detent == DeckDetent.PEEK) detent = DeckDetent.DECK
                        else -> Unit
                    }
                },
                onSheetDrag = { dy ->
                    // Up is negative on screen and taller here, hence the sign. Dragged live
                    // rather than animated, because a sheet that lags the finger stops feeling
                    // like a thing being moved and starts feeling like a request being processed.
                    scope.launch {
                        sheetFraction.snapTo(
                            DeckDetent.clamp(sheetFraction.value - dy / screenHeightPx)
                        )
                    }
                },
                onSheetRelease = { detent = DeckDetent.nearestTo(sheetFraction.value) },
                sheetFraction = sheetFraction.value,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Capped so a wide window gives the room the extra space rather than
                    // stretching a column of one-line rows across it. See DECK_MAX_W.
                    .widthIn(max = DECK_MAX_W)
                    // Three stops, dragged by the header — see DeckDetent.
                    //
                    // This used to be a fixed fraction, and it could not stay one. Everything in
                    // the panel is pinned except the list in the middle, which takes the slack,
                    // so every control added to the rack cost the tracklist a row; by the time
                    // the filter, drive and echo were all there the equaliser had been pushed off
                    // the bottom. "Watch the record spin" and "show me all twenty-seven tracks"
                    // are different heights and only the user knows which one they want.
                    .fillMaxHeight(sheetFraction.value)
                    .graphicsLayer {
                        alpha = deckAlpha
                        // Rises the last few millimetres into place as it fades up. A panel
                        // that only fades appears; one that also moves *arrives*.
                        translationY = (1f - deckAlpha) * DECK_RISE_PX
                    }
            )
        }
    }
}

/**
 * Finding a record by typing it.
 *
 * Opened from FIND in the chrome row, and standing where that row stood while it is up: the
 * field at the bottom, just above the keyboard, and what it matches stacked above the field with
 * the best match nearest the thumb. Not on screen until it is wanted, because the room is the
 * point and a search field sitting permanently over it would be the first piece of ordinary app
 * chrome in a screen that has worked hard not to have any.
 *
 * Results are a short list rather than a filtered wall. Filtering would mean rebuilding the
 * layout — every case re-binding, the shelf packing up around the gaps — for something the
 * user is about to leave. Panning to the record instead keeps the wall exactly as it was, and
 * arriving at a record *on its shelf, in its place* is the answer the room should give.
 */
@Composable
private fun WallSearchPanel(
    layout: WallLayout,
    onClose: () -> Unit,
    /** Pans to the record *and opens it*. See the call site for why both. */
    onOpen: (CaseLayout) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun close() {
        keyboard?.hide()
        onClose()
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    val matches = remember(layout, query) {
        if (query.isBlank()) emptyList() else WallSearch.rank(layout.cases, query).take(6)
    }

    // The chrome row's own ground, so the field arrives on the surface the row left.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to ComposeColor.Transparent,
                    0.22f to ComposeColor(0xE0060403),
                    1f to ComposeColor(0xFA060403)
                )
            )
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(top = 44.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CHROME_MAX_W)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            if (query.isNotBlank() && matches.isEmpty()) {
                Text(
                    text = "NOTHING ON THE SHELVES MATCHES",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = JawonType.Caption,
                    letterSpacing = 1.2.sp,
                    color = LabelDim,
                    modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
                )
            }

            // Best match last, so it sits on the field the thumb is already at.
            matches.asReversed().forEach { case ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(case); close() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = case.title,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = JawonType.Title,
                        color = LabelCream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = case.artist.uppercase(Locale.ROOT),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = JawonType.Caption,
                        letterSpacing = 1.sp,
                        color = LabelDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .background(ComposeColor(0xF21A1511), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = LampAmber,
                    modifier = Modifier.padding(start = 8.dp).size(17.dp)
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = JawonType.Title,
                        color = LabelCream
                    ),
                    cursorBrush = SolidColor(LampAmber),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            WallSearch.best(layout.cases, query)?.let { onOpen(it); close() }
                        }
                    ),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "ALBUM OR ARTIST",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = JawonType.Body,
                                letterSpacing = 1.4.sp,
                                color = LabelDim.copy(alpha = 0.7f)
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                        .focusRequester(focus)
                )
                IconButton(onClick = { close() }) {
                    Icon(Icons.Default.Close, contentDescription = "Close search", tint = LabelDim)
                }
            }
        }
    }
}

/**
 * Below this a glance finds a record faster than typing does.
 *
 * Two, not twelve. Twelve was the right number while search only *panned the camera* — below
 * that a glance beats typing and the field was clutter. It stopped being right the moment
 * search became the only way to open a record without touching the 3D surface: gating it on
 * library size meant a screen-reader user with five albums could reach every screen in the app
 * and play none of them. Clutter on a small library is a much smaller cost than that.
 */
private const val SEARCH_MIN_ALBUMS = 2

/** How long a replaced lighting environment waits before it is destroyed — see `environment`. */
private const val ENVIRONMENT_RETIRE_MS = 500L

/**
 * The crate flip — a thumb rail down the right edge that runs the wall past you.
 *
 * `PLAN.md` §9 #7. Panning covers about one album per swipe, and the camera frames roughly
 * one row, so crossing a real collection is dozens of gestures; this crosses it in one.
 *
 * **It is a rail down the wall, not an A–Z index**, and `WallLayout.caseAtFraction` explains
 * why at length: the wall is filed in slot order, not alphabetically, so a sorted index would
 * throw the wall backwards and forwards under a thumb moving steadily down. The letters here
 * describe what is at each position, the way the dividers in a real crate describe the crate
 * rather than reordering it.
 *
 * Its own `pointerInput`, deliberately. `ENGINEERING.md` trap 10 is that the gesture detector and
 * the raw touch stream have no guaranteed ordering and must never drive the same `Animatable`;
 * this sits in front of the `SceneView` as a Compose overlay, so the scene never sees these
 * events at all and there is nothing to race.
 */
@Composable
private fun CrateFlip(
    layout: WallLayout,
    onScrub: (CaseLayout) -> Unit,
    /** Where the wall comes to rest when the thumb lifts. See onDragEnd. */
    onSettle: (CaseLayout) -> Unit,
    /** Whether the wall is moving, or was a moment ago. See RAIL_DWELL_MS. */
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val ordered = layout.casesInWallOrder

    // Pointless on a library you can already cross in a couple of swipes, and it would sit
    // over the room for no reason.
    if (ordered.size < FLIP_MIN_ALBUMS) return

    val haptics = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var railHeight by remember { mutableFloatStateOf(1f) }
    var lastLanded by remember { mutableStateOf<String?>(null) }
    var lastLetter by remember { mutableStateOf(' ') }

    // Faded rather than removed: the rail keeps its own pointerInput either way, so it can
    // still be grabbed the instant it appears instead of needing a second touch to wake it.
    // Composed out entirely and a flip would always cost one wasted gesture.
    val railAlpha by animateFloatAsState(
        targetValue = if (visible || dragging) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible || dragging) 110 else 420),
        label = "railAlpha"
    )

    fun scrubTo(y: Float) {
        fraction = (y / railHeight).coerceIn(0f, 1f)
        layout.caseAtFraction(fraction)?.let { landed ->
            if (landed.albumId != lastLanded) {
                lastLanded = landed.albumId
                // A tick per *divider*, not per record.
                //
                // Per record is what this did, and it does not survive a real library: at 215
                // albums the rail passes one every five pixels, so a single drag fired a
                // couple of hundred ticks — a continuous buzz rather than feedback. Ticking on
                // the letter caps it at the number of dividers and is the better signal
                // anyway, because feeling the dividers go by is exactly what flipping a crate
                // is like.
                val letter = WallLayout.initialOf(landed.title)
                if (letter != lastLetter) {
                    lastLetter = letter
                    haptics.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                onScrub(landed)
            }
        }
    }

    Row(
        // Two thirds of the height rather than all of it. At full height the marks spread so
        // far apart they stopped reading as a rail and became loose letters scattered down
        // the room, straight across the artwork — and the thumb travel to cross the library
        // was the whole screen.
        // `graphicsLayer` rather than `alpha`, and rather than composing it out: the layer is
        // narrow (trap 14 is about screen-wide washes, not a rail), the rail keeps its own
        // pointerInput at every alpha so it can be grabbed the moment it appears, and nothing
        // recomposes when the value changes.
        modifier = modifier
            .fillMaxHeight(0.66f)
            .graphicsLayer { alpha = railAlpha },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The letter you are on, held out where a thumb is not covering it.
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(90)),
            exit = fadeOut(tween(160))
        ) {
            val landed = layout.caseAtFraction(fraction)
            Row(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .background(ComposeColor(0xEE1C1712), RoundedCornerShape(3.dp))
                    .padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The record under the thumb, and nothing else.
                //
                // The 34pt initial that used to lead this went with the rail's letters, for
                // the same reason: it named a drawer, not a record. The title is what makes
                // coarse navigation usable — the rail gets you to the right part of the wall
                // and this says exactly where that is.
                landed?.let {
                    Column(Modifier.width(170.dp)) {
                        Text(
                            text = it.title,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.sp,
                            color = LabelCream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = it.artist.uppercase(Locale.ROOT),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                            color = LabelDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                // **48dp, not 28.** This is the primary way to cross the wall now, and 28 was
                // under the minimum touch target on every accessibility guideline there is —
                // Play's pre-launch report flags it, and a thumb reaching across a phone this
                // tall was missing a rail narrower than the thumb. The marks stay where they
                // were visually; what grew is the part you can hit.
                .width(48.dp)
                .fillMaxHeight()
                // Its own ground, for the same reason the bottom chrome has one: bare on the
                // room, the marks landed on whatever shelf plank happened to be behind them.
                .background(
                    Brush.horizontalGradient(
                        0f to ComposeColor.Transparent,
                        1f to ComposeColor(0x66120F0C)
                    ),
                    RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                )
                .onSizeChanged { railHeight = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(ordered.size) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            lastLanded = null
                            scrubTo(offset.y)
                        },
                        // **The wall settles onto a record rather than stopping dead.**
                        //
                        // Until now the release only hid the letter plate: the wall stayed
                        // wherever the last scrub had dragged it, which on a fast flip is
                        // between two records rather than on one. That is what made this read
                        // as a jump-to-position control rather than as part of the room —
                        // every other way of moving the wall ends on an album, because the
                        // magnet catches it.
                        //
                        // It settles with SETTLE_SPRING, not the stiffer FLIP_SPRING that
                        // carries the wall *during* the drag. The two want opposite things:
                        // mid-drag the wall has to keep up with a moving thumb, and at the end
                        // it is a heavy rack sliding to a stop.
                        onDragEnd = {
                            dragging = false
                            layout.caseAtFraction(fraction)?.let(onSettle)
                        },
                        onDragCancel = {
                            dragging = false
                            layout.caseAtFraction(fraction)?.let(onSettle)
                        }
                    ) { change, _ ->
                        scrubTo(change.position.y)
                    }
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // **A track and a thumb, and no letters at all.**
            //
            // It was fifteen sampled initials down the rail, on the reasoning that a crate's
            // dividers tell you where you are. The owner's verdict: "no a-z rail, just a rail
            // to navigate, to make it more convenient to navigate through the shelve." He is
            // right about this wall in particular, and the file's own comment says why
            // without noticing it: on a real library "each mark on the rail covers a dozen or
            // more records, so a letter says almost nothing about where you have landed."
            // A letter that describes a twelfth of the collection is decoration.
            //
            // What a navigation rail has to show is POSITION, which is one thing and needs no
            // alphabet: how far through the wall you are, and where your thumb would put you.
            // Read outside the DrawScope: these are composable-backed theme values and a
            // draw lambda is not a composable.
            val trackColour = LabelDim
            val thumbIdle = LabelCream
            val thumbHeld = LampAmber
            Canvas(Modifier.fillMaxHeight().width(RAIL_TRACK_W)) {
                val cx = size.width / 2f
                val travel = size.height
                // The track: the whole wall, as one dim line.
                drawLine(
                    color = trackColour.copy(alpha = if (dragging) 0.5f else 0.28f),
                    start = Offset(cx, 0f),
                    end = Offset(cx, travel),
                    strokeWidth = RAIL_LINE_PX,
                    cap = StrokeCap.Round
                )
                // The thumb: where the wall is now. Long enough to be a handle rather than a
                // dot, and it grows while dragging because that is when it is being held.
                val thumb = travel * RAIL_THUMB_FRACTION
                val centre = (travel - thumb) * fraction.coerceIn(0f, 1f) + thumb / 2f
                drawLine(
                    color = if (dragging) thumbHeld else thumbIdle.copy(alpha = 0.8f),
                    start = Offset(cx, centre - thumb / 2f),
                    end = Offset(cx, centre + thumb / 2f),
                    strokeWidth = if (dragging) RAIL_LINE_PX * 2.4f else RAIL_LINE_PX * 1.7f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Below this the wall is a couple of swipes wide and the rail is clutter.
 *
 * Eight, not twelve. Twelve was chosen while the rail was a secondary control worth hiding
 * until a library clearly needed it; it is the primary way to cross the wall now, and the wall
 * shows roughly nine cases at once — so from about eight upward there is already something off
 * screen to reach for, which is exactly when a rail earns its place.
 */
private const val FLIP_MIN_ALBUMS = 8

/** How wide the drawn track is. The touch target around it is 48dp — see the rail's Column. */
private val RAIL_TRACK_W = 18.dp

/** The track's stroke, in pixels. Hairline: it is a guide, not a scrollbar. */
private const val RAIL_LINE_PX = 3.5f

/**
 * How much of the track the thumb covers.
 *
 * A twelfth, so it reads as a handle you could grab rather than a dot marking a spot, and so
 * it stays visible on a library long enough that a proportional thumb would vanish.
 */
private const val RAIL_THUMB_FRACTION = 0.085f

/**
 * The label plate's content, rendered to a texture by [ViewNode]. Sized in raw pixels rather
 * than dp: the plate's world size is `viewSize / pxPerUnits`, so a density-dependent size
 * would make the same shelf label physically bigger on one device than another.
 */
@Composable
private fun ShelfPlate(name: String) {
    val density = LocalDensity.current
    val widthDp = with(density) { PLATE_PX_W.toDp() }
    val heightDp = with(density) { PLATE_PX_H.toDp() }
    val textSize = with(density) { 34.toSp() }

    Box(
        modifier = Modifier
            .size(widthDp, heightDp)
            // Dark card, ivory type — an engraved plate rather than a sticker. The old pale
            // pastel only read when the key light was washing the front of every board; lit by
            // nothing in the two-light room, it came out a grey smudge. The node is unlit now
            // (see where it is mounted), so these are the colours that reach the screen.
            .background(PLATE_CARD),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.uppercase(Locale.ROOT),
            color = ComposeColor(0xFFE6D6B5),
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
 * spine — the one piece of the room carrying its own vertical text — is always directly
 * behind this. Without something to sit on, the two sets of words interleave.
 */
/**
 * Confirms taking an album off the shelf.
 *
 * **The second line is the whole point of this dialog.** Jawon's albums are records *about*
 * files that belong to the user and live in the user's own folders, and no confirmation that
 * just says "are you sure?" tells them whether their music is about to be deleted. It is not,
 * and it never can be � Jawon holds a read-only grant on those files � so the dialog says so
 * plainly rather than leaving the user to find out by checking afterwards.
 */
@Composable
private fun RemoveAlbumDialog(
    case: CaseLayout,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Square, not rounded, and hairlined in the shelf's own edge colour. This room is
        // built out of flat boards and printed card; a floating pill with a drop shadow
        // belongs to a different app.
        shape = RectangleShape,
        containerColor = CrateBlack,
        tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, CrateEdge),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Set like the shelf plates and the browse label: mono, wide-tracked, small.
                // Names the action before the record, which is the order you read it in when
                // you are deciding whether to go through with it.
                Text(
                    text = "REMOVE FROM SHELF",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 2.2.sp,
                    color = LampAmber
                )
                Text(
                    text = case.title,
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    lineHeight = 25.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = case.artist.uppercase(Locale.ROOT),
                    style = JawonTextStyle.mono.copy(color = LabelDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    text = "It comes off the shelf, along with the artwork you gave it. " +
                        "Your audio files stay exactly where they are — Jawon never deletes " +
                        "your music.",
                    style = JawonTextStyle.mono.copy(color = LabelDim),
                    lineHeight = 19.sp
                )
                // The long press is where you already reach for an album, so the alternative
                // to throwing it away belongs here: most of the time someone holding down on
                // a record wants to fix its cover or its colour, not destroy it. Offered as
                // a quiet line rather than a third button, so the dialog still reads as the
                // one question it is asking.
                Text(
                    text = "EDIT THIS ALBUM INSTEAD →",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                    color = LampAmber,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEdit)
                        .padding(vertical = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "REMOVE",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 1.6.sp,
                    color = LampAmber
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "KEEP IT",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 13.sp,
                    letterSpacing = 1.6.sp,
                    color = LabelDim
                )
            }
        }
    )
}

/**
 * The first thing anyone sees, and per `PLAN.md` �4 the most important screen in the app: it
 * "must read as 'your shelves, waiting' rather than 'broken app'".
 *
 * What used to be here was one line of centred text over black � and the room behind it was
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
            // Describes the route people actually take now. It used to say "point Jawon at a
            // folder of your music", which was written before the device scan existed and
            // sent every new user down the slower of the two paths on their first screen.
            text = "Jawon can find the music already on this phone and sort it into albums, " +
                "or you can point it at a folder.",
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
            text = case?.title.orEmpty().uppercase(Locale.ROOT),
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
            text = case?.artist.orEmpty().uppercase(Locale.ROOT),
            style = JawonTextStyle.mono,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
