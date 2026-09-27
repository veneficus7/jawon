package com.jawon.app.ui.pocketscene

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.jawon.app.BuildConfig
import com.jawon.app.data.lyrics.LyricsState
import com.jawon.app.ui.showroom.Exhibit
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.RoomCadence
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallSceneViewModel
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The key light: where the carriage's ceiling lamp hangs relative to the device, and how hard it
 * shines. Aimed back at the device from there.
 */
private val KEY_POSITION = floatArrayOf(-0.12f, 0.50f, 0.42f)
private const val KEY_CANDELA = 1_300f
private const val KEY_CONE_INNER_RAD = 0.35f
private const val KEY_CONE_OUTER_RAD = 0.80f

/**
 * How the device is turned toward you: a few degrees off square, because an object photographed
 * dead-on shows none of its thickness, and the thickness is where the polished back and the
 * chamfer live.
 *
 * **Held still on purpose.** A synthetic hand sway was tried: small enough not to float the
 * device, it moved nothing measurable (0px over 2.5s), and it was wrong in principle — the phone
 * *is* the device here, so the hand's real motion already arrives through the gyro (TiltFilter).
 * A sway would only fake what the hand does, or move a phone lying still on a table.
 */
internal const val DEVICE_PITCH_DEG = -6f
internal const val DEVICE_YAW_DEG = 15f

/** How dark the carriage goes as it is pinched away, at the moment it lets go. */
private const val PULL_DIM = 0.75f

/** How bright the night outside is drawn, relative to the material's own tuning. */
private const val WINDOW_GAIN = 0.8f

/** The rate the room is drawn at when nobody is touching it — see the note in onFrame. */
private const val POCKET_IDLE_FPS = 60f

/** How often the main menu's slideshow moves on. */
private const val SLIDE_TICK_MS = 50L

/** Covers drawn either side of the middle one in Cover Flow, and how many are kept decoded. */
internal const val COVER_WINDOW = 4
private const val COVER_CACHE_MAX = 40

/** How far a passing lamp reaches — past the device from either window, with room to spare. */
private const val LAMP_FALLOFF_M = 6f

/** The window's picture — see `materials/night_window.mat` and `NightWindow`. */
private const val NIGHT_WINDOW_MATERIAL_ASSET = "materials/night_window.filamat"

/**
 * The shell's polished steel: the glTF's own values (`build_pocket()`), with the lobe widened
 * across a pixel's footprint where the bead curves — see `materials/polished_steel.mat`.
 *
 * [STEEL_FOOTPRINT] is measured, not derived. On the flagship AVD the ceiling's runs crossed the
 * top of the bead as dashed lines at 1; at **4** they joined into continuous lines that still read
 * as a mirror; at 12 the bead went to satin. The arithmetic had predicted 1.
 */
internal const val POLISHED_STEEL_MATERIAL_ASSET = "materials/polished_steel.filamat"
internal val STEEL_BASE_COLOUR = floatArrayOf(0.560f, 0.570f, 0.580f)
internal const val STEEL_ROUGHNESS = 0.055f
internal const val STEEL_FOOTPRINT = 4f

/**
 * The compartment around the device — table, panelling, window frame, sill — in the room's own
 * coordinates (`build_carriage()`). Placed so the life-size view does not change: only the view at
 * rest, pulled back, shows it.
 */
private const val CARRIAGE_MODEL_ASSET = "models/carriage.glb"

/**
 * The window's pane, in a file of its own because its grime needs PNG's alpha (`build_carriage_glass()`).
 * It is what makes the window a window: it reflects the lit compartment behind the viewer over the
 * night, softly, as a night train's glass does.
 */
private const val CARRIAGE_GLASS_ASSET = "models/carriage_glass.glb"

/**
 * Where a platform's light comes in: outside the window, over its top, a little to the left of the
 * device — aimed down at the table in front of the window, so it crosses the device on the way.
 */
private val PLATFORM_LIGHT_POSITION = floatArrayOf(-0.18f, 0.40f, -0.62f)
private val PLATFORM_LIGHT_TARGET = floatArrayOf(0.05f, -0.30f, -0.12f)

/** The earphone plug in the top edge's jack, in the device's frame (`build_earphone_plug()`). */
private const val EARPHONE_PLUG_ASSET = "models/earphone_plug.glb"

/**
 * The lamp's light on the earphones' white plastic, from the reading lamp's direction over your
 * shoulder, on the cord's own channel (`CordMesh.CORD_LIGHT_CHANNEL`) — so it lights the cord and the
 * plug and nothing else.
 */
private val CORD_FILL_POSITION = floatArrayOf(0.35f, 0.45f, 0.60f)
private const val CORD_FILL_CANDELA = 2_500f

/** The cord's white plastic, the plug's own: a little gloss, no coat. Linear RGB. */
private val CORD_COLOUR = Float4(0.80f, 0.80f, 0.78f, 1f)
private const val CORD_ROUGHNESS = 0.28f

/**
 * **The reading lamp**, over the window's right-hand corner: warm, shaded downward onto the table,
 * and the room's one shadow caster. At rest it pools on the walnut and throws the floating
 * device's shadow onto it just below and in front — which is what makes the float read as a
 * float. In the hand it is above and behind the device, and lays a line along its top edge.
 *
 * **Placed for the shadow.** Beside the window, where it was first, the float's shadow fell 15cm
 * to the side and out of the frame. **And bright for this exposure**: the room is exposed for the
 * daylight-level values the metal needs (`PocketLighting`), so a lamp that pools visibly on dark
 * walnut is in the tens of thousands of candela, not the hundreds — at 800 the table was black.
 */
private val LAMP_POSITION = floatArrayOf(0.30f, 0.34f, -0.44f)
private val LAMP_TARGET = floatArrayOf(0.0f, -0.30f, -0.25f)
private const val LAMP_CANDELA = 15_000f
private const val LAMP_CONE_INNER_RAD = 0.5f
private const val LAMP_CONE_OUTER_RAD = 1.0f

/** Where the device's model lives. Eight objects in one file — see `docs/models.md`. */
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
 * The LCD panel, in the device's own frame — these must agree with `build_pocket()`.
 *
 * The live picture is `POCKET_SCREEN_W/H` less 1.2mm, centred `POCKET_SCREEN_Y` up the face, and
 * sits **0.1mm in front of the panel** — which is `POCKET_SCREEN_SINK` (1.0mm) behind the face and
 * fills the window as the LCD's black surround, so the picture has a 0.6mm mask all round as a
 * real LCD's active area does. 0.1mm is enough that the depth buffer never has to choose between
 * them, and still 0.45mm under the glass, which is what makes it read as a picture *under* a
 * window rather than a sticker on one.
 *
 * **Why the screen could not be seen for a morning**: the panel was 1.6mm down, *inside* the
 * back shell, which is solid up to the plate's underside. What the window showed was the
 * shell's polished top face reflecting the lamp. Two probes — the glass hidden, then the panel
 * hidden — changed nothing, and the one that moved the screen in front of everything showed it
 * working perfectly; the depth it had been given was the whole problem.
 */
internal const val SCREEN_W_M = 0.0443f
internal const val SCREEN_CENTRE_Y = 0.0255f
internal const val SCREEN_Z = 0.00525f - 0.0010f + 0.0001f

/**
 * The iPod mode's room: a pocket player at life size, lit by a carriage at night — and now
 * working. The wheel turns through real menus built from the library, and plays from them.
 *
 * A sibling of `WallSceneScreen`, never a branch inside it — `ENGINEERING.md`'s standard for a new
 * format room. What it shares, it shares by calling: the same [WallSceneViewModel] (so a record
 * is played and remembered the same way in both rooms), [RoomHaptics], `CaseArt`'s decoder.
 */
@Composable
fun PocketRoomScreen(
    onOpenSettings: () -> Unit,
    onAddAlbum: () -> Unit,
    /** Two fingers pinched together: the showroom between formats. */
    onOpenShowroom: () -> Unit = {},
    viewModel: WallSceneViewModel = hiltViewModel()
) {
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

    // The shell's steel, put on the model's part when it loads (bindParts). The loader owns it,
    // and is disposed after the model, so the part is gone before its material is.
    val steel = remember(materialLoader) {
        materialLoader.createInstance(materialLoader.createMaterial(POLISHED_STEEL_MATERIAL_ASSET)).apply {
            setParameter("baseColor", STEEL_BASE_COLOUR[0], STEEL_BASE_COLOUR[1], STEEL_BASE_COLOUR[2])
            setParameter("roughness", STEEL_ROUGHNESS)
            setParameter("footprint", STEEL_FOOTPRINT)
        }
    }
    // The window behind the device. Created here, handed to its PlaneNode below, which owns it.
    val windowMaterial = remember(materialLoader) {
        materialLoader.createMaterial(NIGHT_WINDOW_MATERIAL_ASSET)
    }

    val deviceModel = rememberModelInstance(modelLoader, POCKET_MODEL_ASSET)
    val carriageModel = rememberModelInstance(modelLoader, CARRIAGE_MODEL_ASSET)
    val carriageGlass = rememberModelInstance(modelLoader, CARRIAGE_GLASS_ASSET)
    val earphonePlug = rememberModelInstance(modelLoader, EARPHONE_PLUG_ASSET)
    val ydpi = remember(context) { max(context.resources.displayMetrics.ydpi, 1f) }
    // How often the room is actually drawn — the CD room's cadence, reused by call. See onFrame.
    val cadence = remember(context) {
        RoomCadence().apply {
            refreshHz = context.display?.refreshRate ?: RoomCadence.DEFAULT_REFRESH_HZ
        }
    }
    val publishedDivisor = remember { intArrayOf(0) }
    // What renders Compose into the scene. See `docs/realism.md` §1.4: a SurfaceTexture feeding
    // a Filament Stream — the path video takes — so the screen is live UI, not a baked image.
    val viewNodeManager = rememberViewNodeManager()

    // ---- the library and the player, as the device sees them ------------------------------
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val nowPlayingAlbumId by viewModel.nowPlayingAlbumId.collectAsStateWithLifecycle()

    val library = remember(layout) { pocketLibraryOf(layout) }

    // Whatever the player is already playing is Now Playing, even when this room was rebuilt
    // while it played on — the classic always knew what was on.
    LaunchedEffect(viewModel) { viewModel.adoptPlayingAlbum() }

    // ---- what the device's own Settings menu shows ----------------------------------------
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqPresets by viewModel.eqPresets.collectAsStateWithLifecycle()
    val replayGainMode by viewModel.replayGainMode.collectAsStateWithLifecycle()
    val backlightMs by viewModel.pocketBacklightMs.collectAsStateWithLifecycle()
    val clickerOn by viewModel.pocketClicker.collectAsStateWithLifecycle()
    val songCount by viewModel.trackCount.collectAsStateWithLifecycle()
    val filter by viewModel.filterPosition.collectAsStateWithLifecycle()
    val speed by viewModel.speedPosition.collectAsStateWithLifecycle()
    val drive by viewModel.drivePosition.collectAsStateWithLifecycle()
    val echoMix by viewModel.echoMix.collectAsStateWithLifecycle()
    val echoTime by viewModel.echoTime.collectAsStateWithLifecycle()
    val echoFeedback by viewModel.echoFeedback.collectAsStateWithLifecycle()
    val bass by viewModel.bassStrength.collectAsStateWithLifecycle()
    val width by viewModel.stereoWidth.collectAsStateWithLifecycle()
    val reverb by viewModel.reverbPreset.collectAsStateWithLifecycle()
    val trackTitles = remember { mutableStateMapOf<String, List<String>>() }
    val navState = rememberSaveable(stateSaver = PocketNav.Saver) { mutableStateOf(PocketNav()) }
    val device = remember { PocketDevice(context, viewModel, navState) }

    DisposableEffect(device) { onDispose { device.close() } }

    // What the handlers read. Written here every composition, so a touch always acts on the
    // library, the tracklists and the player as they are *now* — the handlers themselves are
    // built once and handed to SceneView.
    device.library = library
    device.trackTitles = trackTitles
    device.eqPresets = eqPresets
    device.backlightMs = backlightMs
    device.clickerOn = clickerOn
    device.settings = PocketSettings(
        eqOptions = listOf("Off") + eqPresets,
        // Off when the equaliser is; otherwise the preset chosen here this session, and
        // "Custom" when the bands have been set by hand in the rack instead.
        eqChosen = if (!eqEnabled) 0 else device.eqPicked.value ?: -1,
        soundCheck = when (com.jawon.app.playback.ReplayGain.Mode.fromName(replayGainMode)) {
            com.jawon.app.playback.ReplayGain.Mode.OFF -> 0
            com.jawon.app.playback.ReplayGain.Mode.ALBUM -> 1
            com.jawon.app.playback.ReplayGain.Mode.TRACK -> 2
        },
        backlightMs = backlightMs,
        clicker = clickerOn,
        albums = library.albums.size,
        songs = songCount,
        shelves = library.shelves.size,
        version = com.jawon.app.BuildConfig.VERSION_NAME,
        sound = mapOf(
            SoundControl.FILTER to filter,
            SoundControl.SPEED to speed,
            SoundControl.DRIVE to drive,
            SoundControl.ECHO to echoMix,
            SoundControl.ECHO_TIME to echoTime,
            SoundControl.ECHO_FEEDBACK to echoFeedback,
            SoundControl.BASS to bass,
            SoundControl.WIDTH to width
        ),
        reverbOptions = PocketSound.reverbOptions(com.jawon.app.data.playback.NativeEffects.REVERB_PRESETS),
        reverb = reverb
    )
    device.deck = deck
    device.positionMs = positionMs
    device.hasNowPlaying = nowPlayingAlbumId != null && deck.tracks.isNotEmpty()
    device.onOpenSettings = onOpenSettings
    device.onAddAlbum = onAddAlbum
    device.onOpenShowroom = onOpenShowroom
    device.showroomReachable = Exhibit.reachable(BuildConfig.DEBUG || BuildConfig.SHOW_FORMATS)

    val nav = device.nav.value
    val currentKey = nav.current.key

    // A tracklist is not part of the wall's layout: read one when its album is opened.
    LaunchedEffect(currentKey) {
        val albumId = when (currentKey) {
            is MenuKey.Album -> currentKey.albumId
            is MenuKey.Flipped -> currentKey.albumId
            else -> null
        }
        if (albumId != null && albumId !in trackTitles) {
            trackTitles[albumId] = viewModel.trackTitles(albumId)
        }
    }

    // ---- Cover Flow: covers loaded around the selection, the flow and the turn eased ---------
    val inFlow = currentKey == MenuKey.CoverFlow || currentKey is MenuKey.Flipped
    val flowCentre = if (!inFlow) 0 else {
        val level = if (currentKey is MenuKey.Flipped) nav.stack[nav.stack.size - 2] else nav.current
        level.selected
    }
    val coverArt = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val albumsInOrder = remember(library) { PocketMenus.albumsInOrder(library) }
    LaunchedEffect(inFlow, flowCentre, albumsInOrder) {
        if (!inFlow || albumsInOrder.isEmpty()) return@LaunchedEffect
        val from = (flowCentre - COVER_WINDOW - 2).coerceAtLeast(0)
        val to = (flowCentre + COVER_WINDOW + 2).coerceAtMost(albumsInOrder.lastIndex)
        for (i in from..to) {
            val album = albumsInOrder[i]
            if (album.id in coverArt) continue
            coverArt[album.id] = withContext(Dispatchers.IO) {
                album.coverPath?.let { CaseArt.loadCover(it, maxPx = 256)?.asImageBitmap() }
            }
        }
        // Keep the cache near the selection: a long library turned through end to end would
        // otherwise hold every cover it passed.
        if (coverArt.size > COVER_CACHE_MAX) {
            val keep = (from..to).map { albumsInOrder[it].id }.toSet()
            coverArt.keys.filter { it !in keep }.take(coverArt.size - COVER_CACHE_MAX).forEach { coverArt.remove(it) }
        }
    }
    // ---- the main menu's slideshow: the library's covers, in an order of its own ------------
    val onMain = currentKey == MenuKey.Main
    val slideOrder = remember(albumsInOrder) {
        albumsInOrder.filter { it.coverPath != null }.shuffled(kotlin.random.Random(albumsInOrder.size))
    }
    val slideIndex = remember { mutableIntStateOf(0) }
    val slideClock = remember { mutableFloatStateOf(SLIDE_FADE_S) }
    val showingSlides = onMain && slideOrder.isNotEmpty() && device.awake.value && device.backlight.value
    LaunchedEffect(showingSlides) {
        if (!showingSlides) return@LaunchedEffect
        // Twenty steps a second: each is a redraw of the whole screen in software (trap 33), and
        // a drift this slow is smooth at twenty with sub-pixel drawing.
        while (true) {
            kotlinx.coroutines.delay(SLIDE_TICK_MS)
            var t = slideClock.floatValue + SLIDE_TICK_MS / 1000f
            if (t >= SLIDE_HOLD_S + SLIDE_FADE_S) {
                t -= SLIDE_HOLD_S
                slideIndex.intValue += 1
            }
            slideClock.floatValue = t
        }
    }
    LaunchedEffect(onMain, slideIndex.intValue, slideOrder) {
        if (!onMain || slideOrder.isEmpty()) return@LaunchedEffect
        for (k in 0..2) {
            val album = slideOrder[(slideIndex.intValue + k) % slideOrder.size]
            if (album.id in coverArt) continue
            coverArt[album.id] = withContext(Dispatchers.IO) {
                album.coverPath?.let { CaseArt.loadCover(it, maxPx = 256)?.asImageBitmap() }
            }
        }
    }
    val slides = if (onMain && slideOrder.isNotEmpty()) {
        val i = slideIndex.intValue
        LcdScreen.ArtSlides(
            current = coverArt[slideOrder[i % slideOrder.size].id],
            next = coverArt[slideOrder[(i + 1) % slideOrder.size].id],
            seed = i
        )
    } else {
        null
    }

    val flowAnim = remember { Animatable(0f) }
    val flipAnim = remember { Animatable(0f) }
    val wasInFlow = remember { mutableStateOf(false) }
    LaunchedEffect(inFlow, flowCentre) {
        if (!inFlow) {
            wasInFlow.value = false
            return@LaunchedEffect
        }
        if (!wasInFlow.value) {
            // Arriving in Cover Flow: start where the selection is, not a library away.
            flowAnim.snapTo(flowCentre.toFloat())
            wasInFlow.value = true
        } else {
            flowAnim.animateTo(flowCentre.toFloat(), spring(dampingRatio = 0.92f, stiffness = 170f))
        }
    }
    LaunchedEffect(currentKey is MenuKey.Flipped) {
        flipAnim.animateTo(
            if (currentKey is MenuKey.Flipped) 1f else 0f,
            tween(durationMillis = 460, easing = FastOutSlowInEasing)
        )
    }

    // **Lyrics are asked for only while their page is up**, never in the background: the third
    // place they are looked for is LRCLIB, and the privacy policy promises a lookup happens when
    // the words are asked for — the CD room's lyrics page keeps the same promise.
    val lyricsState = if (device.lyricsOpen.value && currentKey == MenuKey.NowPlaying) {
        viewModel.lyrics.collectAsStateWithLifecycle().value
    } else {
        null
    }
    device.lyricLineCount = (lyricsState as? LyricsState.Ready)?.lyrics?.lines?.size ?: 0

    // A song just asked for stands in on Now Playing until the player says it is on.
    val asked = device.pendingPlay.value
    val caughtUp = asked != null && deck.currentTrack?.albumId == asked.albumId &&
        (asked.index == null || deck.currentTrackIndex == asked.index)
    SideEffect { if (caughtUp) device.pendingPlay.value = null }
    val pending = asked?.takeIf { !caughtUp }
    val nowPlayingAlbum = (pending?.albumId ?: nowPlayingAlbumId)?.let { library.album(it) }
    val cover by produceState<ImageBitmap?>(initialValue = null, nowPlayingAlbum?.coverPath) {
        val path = nowPlayingAlbum?.coverPath
        value = if (path == null) null else withContext(Dispatchers.IO) {
            CaseArt.loadCover(path, maxPx = 256)?.asImageBitmap()
        }
    }

    // The screen's content, derived from all of the above and handed to the LCD through one
    // state object — read *inside* the ViewNode's own composition, so the in-world screen
    // redraws on a change without depending on how SceneView forwards a new content lambda.
    val screen = lcdScreenFor(
        nav = nav,
        pending = pending,
        library = library,
        settings = device.settings,
        slides = slides,
        albumsInOrder = albumsInOrder,
        coverArt = coverArt,
        trackTitles = trackTitles,
        hasNowPlaying = device.hasNowPlaying,
        deck = deck,
        positionMs = positionMs,
        albumTitle = nowPlayingAlbum?.title.orEmpty(),
        albumArtist = nowPlayingAlbum?.artist.orEmpty(),
        cover = cover,
        bar = device.bar.value,
        volume = device.volume.value,
        scrubMs = device.scrubMs.value,
        lyrics = if (device.lyricsOpen.value) {
            lyricsView(lyricsState, positionMs, device.lyricTop.value, device.lyricsFollowing.value)
        } else {
            null
        }
    )
    // Where the lyrics page is actually showing from, for the wheel's first turn on it.
    device.lyricShownTop = (screen as? LcdScreen.NowPlaying)?.lyrics?.top ?: 0
    // The earphone cord: the plug's white plastic, drawn round the simulated chain every frame.
    // Handed to the device, and taken out of the scene and destroyed, from inside the scene's own
    // content below — see the note there.
    val cordMesh = remember(engine, materialLoader) {
        CordMesh(engine, materialLoader.createColorInstance(CORD_COLOUR, 0f, CORD_ROUGHNESS, 0.5f))
    }

    val lcd = remember { mutableStateOf(screen) }
    SideEffect { lcd.value = screen }
    // Which page it is, so the screen can slide between pages as the classic's did.
    val lcdPage = remember { mutableStateOf(nav.page) }
    SideEffect { lcdPage.value = nav.page }

    // The screen's own composition, on LcdPanel's hardware-drawn surface. Set once: everything it
    // shows is read from state inside it, and any change beneath the panel's host redraws the
    // texture (LcdPanel.onDescendantInvalidated) — which is what trap 33 needed all along.
    val lcdPanel = remember(engine) { LcdPanel(context, engine, LCD_PX_W, LCD_PX_H) }
    DisposableEffect(lcdPanel, viewNodeManager) {
        viewNodeManager.addView(lcdPanel.host)
        onDispose { viewNodeManager.removeView(lcdPanel.host) }
    }
    remember(lcdPanel) {
        lcdPanel.composeView.setContent {
            val unlit by animateFloatAsState(
                targetValue = if (device.backlight.value) 0f else BACKLIGHT_OFF_DIM,
                animationSpec = tween(durationMillis = if (device.backlight.value) 90 else 700),
                label = "backlight"
            )
            PocketLcd(
                screen = lcd.value,
                playing = device.playingNow.value,
                awake = device.awake.value,
                unlit = unlit,
                flow = flowAnim.value,
                flip = flipAnim.value,
                slide = slideClock.floatValue,
                page = lcdPage.value.path,
                depth = lcdPage.value.depth
            )
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { device.back() }

    // **The phone is the device**, so tilting it turns the device against the world: the
    // carriage's reflections slide across the glass and the polished edge — first-order, twice
    // the tilt, as a turned mirror's do. Only the change from how it is held counts (TiltFilter).
    // Listening only while the room is on screen: a sensor left running in the background is
    // battery for nothing.
    val tilt = remember { TiltFilter() }
    // And whether it has been put down on a table — the device's cue to rest (PocketRest).
    val flat = remember { FlatDetector() }
    LifecycleResumeEffect(context) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val rotation = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val quat = FloatArray(4)
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getQuaternionFromVector(quat, event.values)
                val dt = if (lastNs == 0L) 0f else (event.timestamp - lastNs) / 1e9f
                lastNs = event.timestamp
                // getQuaternionFromVector writes (w, x, y, z).
                tilt.update(quat[1], quat[2], quat[3], quat[0], dt)
                device.lyingFlat = flat.update(quat[1], quat[2], quat[3], quat[0], dt)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        tilt.reset()
        flat.reset()
        device.lyingFlat = false
        if (sensors != null && rotation != null) {
            sensors.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)
        }
        onPauseOrDispose { sensors?.unregisterListener(listener) }
    }
    device.tilt = tilt
    device.indirectLight = environment.indirectLight

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
        device.cameraNode = cameraNode
        device.handCameraZ = sceneHeightM * FOCAL_LENGTH_MM / SENSOR_HEIGHT_MM
        device.frameScale = SENSOR_HEIGHT_MM / 2f / FOCAL_LENGTH_MM

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
            viewNodeWindowManager = viewNodeManager,
            onTouchEvent = { event, _ -> device.onTouch(event) },
            // **Trap 8, again, and measured again at the same −8.28°.** SceneView's camera node
            // arrives already pitched down, and nothing in this room resets it, so the device
            // sat a quarter of a screen too high while the arithmetic above was right. The CD
            // room overwrites the rotation every frame (`LOOK_STRAIGHT_AHEAD`), and so does this:
            // a device held square in front of you is looked at square on.
            onFrame = {
                cameraNode.rotation = LEVEL
                // The grade, re-applied if SceneView's own preset has undone it — see PocketAtmosphere.
                if (!PocketAtmosphere.isApplied(filamentView)) PocketAtmosphere.apply(filamentView)
                // The earphone cord is drawn by a renderable of its own, not a node: into the scene
                // once SceneView has made one.
                device.addCordTo(filamentView.scene)
                device.onFrame(SystemClock.uptimeMillis())
                // **Drawn at 60 unless a thumb is on the wheel.** SceneView renders every vsync —
                // 120 a second on the owner's phone — and nothing in this room moves fast enough
                // to need it except the device answering a touch: the night outside drifts, the
                // lamps take a second or more to pass. So the clocks all advance every vsync as
                // before and only the rasterising drops to 60, which is half the GPU work on a
                // 120Hz panel (traps 28-30: the CD room's problem is heat, not jank). A finger
                // down or the wheel still rocking is back to every vsync on the next frame.
                val divisor = if (device.interacting) {
                    RoomCadence.LIVE
                } else {
                    cadence.divisorForFps(POCKET_IDLE_FPS)
                }
                if (divisor != publishedDivisor[0]) {
                    publishedDivisor[0] = divisor
                    renderer.frameRateOptions = Renderer.FrameRateOptions().apply {
                        interval = divisor.toFloat()
                    }
                }
                if (!cadence.shouldDraw(divisor)) renderer.skipNextFrames(1)
            }
        ) {
            // **The cord's renderable leaves the scene while there still is one.** This effect was
            // first declared out beside the `remember` above, which put its disposal *after*
            // SceneView's — so leaving the room removed the cord from a scene SceneView had already
            // destroyed, and every way out of this room threw ("Calling method on destroyed Scene").
            // In here it is disposed with the scene's content, which goes before the scene does.
            DisposableEffect(cordMesh) {
                device.cordMesh = cordMesh
                onDispose {
                    device.cordMesh = null
                    filamentView.scene?.removeEntity(cordMesh.entity)
                    cordMesh.destroy()
                }
            }
            // **The carriage's ceiling lamp: the key light.** Warm, above and just behind you, aimed
            // at the device, so the anodised face has a sheen running down it and the wheel's
            // coat a highlight. At SceneView's default exposure a light has to be in the
            // thousand-candela range to register against the carriage's ambient at all: the two
            // lamps this replaced were 2.6 and 3.4cd, about one per cent of the ambient on the
            // device's face, and tuning them moved nothing (see docs/models.md, the print's note).
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(KEY_POSITION[0], KEY_POSITION[1], KEY_POSITION[2]),
                direction = Direction(-KEY_POSITION[0], -KEY_POSITION[1], -KEY_POSITION[2]),
                apply = {
                    color(1.0f, 0.86f, 0.70f)
                    intensityCandela(KEY_CANDELA)
                    spotLightCone(KEY_CONE_INNER_RAD, KEY_CONE_OUTER_RAD)
                    falloff(3f)
                    castShadows(false)
                }
            )
            // The lamp's light on the earphones' white plastic, and on nothing else.
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(CORD_FILL_POSITION[0], CORD_FILL_POSITION[1], CORD_FILL_POSITION[2]),
                apply = {
                    color(1.0f, 0.86f, 0.70f)
                    intensityCandela(CORD_FILL_CANDELA)
                    falloff(3f)
                    castShadows(false)
                    lightChannel(0, false)
                    lightChannel(CordMesh.CORD_LIGHT_CHANNEL, true)
                }
            )
            // **The lights going past**, as light: a fixed pool the frame loop moves and dims —
            // see PassingLamps. Off by intensity, never by hiding (trap 24).
            repeat(PassingLamps.COUNT) { index ->
                LightNode(
                    type = LightManager.Type.POINT,
                    position = Position(0f, 0f, 2f),
                    apply = {
                        color(1f, 0.6f, 0.3f)
                        intensityCandela(0f)
                        falloff(LAMP_FALLOFF_M)
                        castShadows(false)
                    },
                    nodeApply = { device.lampNodes[index] = this }
                )
            }
            // **A platform's light**, coming in at the window while the train stands at one: cool,
            // from outside the window's top, down across the table. Off until then, by intensity.
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(PLATFORM_LIGHT_POSITION[0], PLATFORM_LIGHT_POSITION[1], PLATFORM_LIGHT_POSITION[2]),
                direction = Direction(
                    PLATFORM_LIGHT_TARGET[0] - PLATFORM_LIGHT_POSITION[0],
                    PLATFORM_LIGHT_TARGET[1] - PLATFORM_LIGHT_POSITION[1],
                    PLATFORM_LIGHT_TARGET[2] - PLATFORM_LIGHT_POSITION[2]
                ),
                apply = {
                    color(0.74f, 0.90f, 0.94f)
                    intensityCandela(0f)
                    spotLightCone(0.6f, 1.2f)
                    falloff(3f)
                    castShadows(false)
                },
                nodeApply = { device.platformLightNode = this }
            )
            // **The window**, behind everything: the night going past, out of focus. Its
            // instance is made in this scope because a PlaneNode destroys the one it is handed.
            val outside = remember(windowMaterial) {
                materialLoader.createInstance(windowMaterial).apply {
                    setParameter("size", NightWindow.PLANE_W_M, NightWindow.PLANE_H_M)
                    setParameter("scroll", 0f, 0f, 0f, 0f)
                    setParameter("bob", 0f, 0f)
                    // No station until the train says so: an unset one would be at the window.
                    setParameter("station", NO_STATION_M, 0f)
                    // A little under full: the lights outside are the room, and the device in
                    // front of them is the subject — at 1.0 they competed with it.
                    setParameter("gain", WINDOW_GAIN)
                }
            }
            device.windowMaterial = outside
            // Where the frame's edge falls on the window, for the lens's cat's eye, is written by
            // the device each frame — the camera moves between the hand and the table.
            outside.setParameter("frame", device.handCameraZ.minus(NightWindow.PLANE_Z_M) * device.frameScale)
            // **The carriage**, and the lamp beside its window.
            carriageModel?.let { carriage ->
                ModelNode(
                    carriage,
                    apply = {
                        isTouchable = false
                        isShadowCaster = false
                        isShadowReceiver = true
                    }
                )
            }
            carriageGlass?.let { glass ->
                ModelNode(
                    glass,
                    apply = {
                        isTouchable = false
                        isShadowCaster = false
                        isShadowReceiver = false
                    }
                )
            }
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(LAMP_POSITION[0], LAMP_POSITION[1], LAMP_POSITION[2]),
                direction = Direction(
                    LAMP_TARGET[0] - LAMP_POSITION[0],
                    LAMP_TARGET[1] - LAMP_POSITION[1],
                    LAMP_TARGET[2] - LAMP_POSITION[2]
                ),
                apply = {
                    color(1.0f, 0.74f, 0.48f)
                    intensityCandela(LAMP_CANDELA)
                    spotLightCone(LAMP_CONE_INNER_RAD, LAMP_CONE_OUTER_RAD)
                    falloff(3f)
                    castShadows(true)
                }
            )
            PlaneNode(
                size = Size(NightWindow.PLANE_W_M, NightWindow.PLANE_H_M, 0f),
                normal = Direction(z = 1f),
                materialInstance = outside,
                position = Position(0f, 0f, NightWindow.PLANE_Z_M),
                apply = {
                    isTouchable = false
                    isShadowCaster = false
                    isShadowReceiver = false
                }
            )
            deviceModel?.let { instance ->
                // Turned a few degrees off square: an object photographed dead-on shows none
                // of its thickness, and the thickness is where the polished back and the
                // chamfer live.
                Node(
                    rotation = Rotation(x = DEVICE_PITCH_DEG, y = DEVICE_YAW_DEG, z = 0f),
                    apply = { device.deviceNode = this }
                ) {
                    ModelNode(
                        instance,
                        apply = {
                            isTouchable = false
                            // Its shadow on the table is what makes the float at rest a float.
                            isShadowCaster = true
                            isShadowReceiver = false
                            device.bindParts(renderableNodes, steel)
                        }
                    )
                    // The earphones' plug, in the jack: it rides with the device, and catches the
                    // lamp as the cord does.
                    earphonePlug?.let { plug ->
                        ModelNode(
                            plug,
                            apply = {
                                isTouchable = false
                                isShadowCaster = true
                                isShadowReceiver = false
                                for (part in renderableNodes) {
                                    part.renderableManager.setLightChannel(
                                        part.renderableInstance, CordMesh.CORD_LIGHT_CHANNEL, true
                                    )
                                }
                            }
                        )
                    }
                    // The screen, as a child of the device so it turns with it. Unlit because
                    // an LCD is its own light: nothing in the carriage should be able to
                    // darken what the backlight is putting out.
                    // **The screen**: LcdPanel's texture on a plane 0.1mm over the panel, a child of
                    // the device so it turns with it. Unlit because an LCD is its own light. The
                    // panel's release is declared *before* the plane, so Compose — forgetting in
                    // reverse — takes the plane and the material sampling the texture down first.
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

        // No chrome over the room: the device *is* the interface, and the phone app's Settings
        // are on its own menu (Settings → All Settings). The debug line and a SETTINGS link used
        // to sit under the device and were the first thing that said "screen", not "object".

        // The carriage going dark as it is pinched away - composed only while it is (trap 14).
        if (device.pulling.value) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = PULL_DIM * device.pullDim.floatValue }
                    .background(Color.Black)
            )
        }
    }

    // Kept in step with the player, for the title bar's play glyph.
    SideEffect { device.playingNow.value = deck.isPlaying }
}
