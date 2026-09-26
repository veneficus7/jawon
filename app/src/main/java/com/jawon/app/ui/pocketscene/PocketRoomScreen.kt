package com.jawon.app.ui.pocketscene

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.jawon.app.data.lyrics.LyricsState
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.RoomCadence
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallLayout
import com.jawon.app.ui.wallscene.WallSceneViewModel
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
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
private const val DEVICE_PITCH_DEG = -6f
private const val DEVICE_YAW_DEG = 15f

/** How bright the night outside is drawn, relative to the material's own tuning. */
private const val WINDOW_GAIN = 0.8f

/** The rate the room is drawn at when nobody is touching it — see the note in onFrame. */
private const val POCKET_IDLE_FPS = 60f

/** How often the main menu's slideshow moves on. */
private const val SLIDE_TICK_MS = 50L

/** Covers drawn either side of the middle one in Cover Flow, and how many are kept decoded. */
private const val COVER_WINDOW = 4
private const val COVER_CACHE_MAX = 40

/** How far a passing lamp reaches — past the device from either window, with room to spare. */
private const val LAMP_FALLOFF_M = 6f

/** The window's picture — see `materials/night_window.mat` and `NightWindow`. */
private const val NIGHT_WINDOW_MATERIAL_ASSET = "materials/night_window.filamat"

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
 * The panel is `POCKET_SCREEN_W/H` less 1.2mm, centred `POCKET_SCREEN_Y` up the face and
 * `POCKET_SCREEN_SINK` (1.0mm) behind it. The live screen sits **0.1mm in front of that panel**:
 * enough that the depth buffer never has to choose between them, and still 0.45mm under the
 * glass, which is what makes it read as a picture *under* a window rather than a sticker on one.
 *
 * **Why the screen could not be seen for a morning**: the panel was 1.6mm down, *inside* the
 * back shell, which is solid up to the plate's underside. What the window showed was the
 * shell's polished top face reflecting the lamp. Two probes — the glass hidden, then the panel
 * hidden — changed nothing, and the one that moved the screen in front of everything showed it
 * working perfectly; the depth it had been given was the whole problem.
 */
private const val SCREEN_W_M = 0.0443f
private const val SCREEN_CENTRE_Y = 0.0255f
private const val SCREEN_Z = 0.00525f - 0.0010f + 0.0001f

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

    // The window behind the device. Created here, handed to its PlaneNode below, which owns it.
    val windowMaterial = remember(materialLoader) {
        materialLoader.createMaterial(NIGHT_WINDOW_MATERIAL_ASSET)
    }

    val deviceModel = rememberModelInstance(modelLoader, POCKET_MODEL_ASSET)
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

    val nowPlayingAlbum = nowPlayingAlbumId?.let { library.album(it) }
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
    val lcd = remember { mutableStateOf(screen) }
    SideEffect { lcd.value = screen }

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
                slide = slideClock.floatValue
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
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        tilt.reset()
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
            // **The window**, behind everything: the night going past, out of focus. Its
            // instance is made in this scope because a PlaneNode destroys the one it is handed.
            val outside = remember(windowMaterial) {
                materialLoader.createInstance(windowMaterial).apply {
                    setParameter("size", NightWindow.PLANE_W_M, NightWindow.PLANE_H_M)
                    setParameter("scroll", 0f, 0f, 0f, 0f)
                    setParameter("bob", 0f, 0f)
                    // A little under full: the lights outside are the room, and the device in
                    // front of them is the subject — at 1.0 they competed with it.
                    setParameter("gain", WINDOW_GAIN)
                }
            }
            device.windowMaterial = outside
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
                            isShadowCaster = false
                            isShadowReceiver = false
                            device.bindParts(renderableNodes)
                        }
                    )
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
    }

    // Kept in step with the player, for the title bar's play glyph.
    SideEffect { device.playingNow.value = deck.isPlaying }
}

/** The wall's layout, as shelves-as-playlists and albums. The placeholder board is not a shelf. */
private fun pocketLibraryOf(layout: WallLayout): PocketLibrary {
    val byRow = layout.cases.groupBy { it.row }
    val shelves = layout.rows
        .filter { it.shelfId != WallLayout.WAITING_SHELF_ID }
        .sortedBy { it.row }
        .map { row ->
            PocketLibrary.Shelf(
                id = row.shelfId,
                name = row.name,
                albumIds = byRow[row.row].orEmpty().sortedBy { it.col }.map { it.albumId }
            )
        }
    val albums = layout.cases.map {
        PocketLibrary.Album(id = it.albumId, title = it.title, artist = it.artist, coverPath = it.coverPath)
    }
    return PocketLibrary(shelves, albums)
}

private fun lcdScreenFor(
    nav: PocketNav,
    library: PocketLibrary,
    settings: PocketSettings,
    slides: LcdScreen.ArtSlides?,
    albumsInOrder: List<PocketLibrary.Album>,
    coverArt: Map<String, ImageBitmap?>,
    trackTitles: Map<String, List<String>>,
    hasNowPlaying: Boolean,
    deck: com.jawon.app.ui.wallscene.DeckState,
    positionMs: Long,
    albumTitle: String,
    albumArtist: String,
    cover: ImageBitmap?,
    bar: LcdScreen.Bar,
    volume: Float,
    scrubMs: Long,
    lyrics: LcdScreen.LyricsView?
): LcdScreen {
    val key = nav.current.key
    if (key == MenuKey.NowPlaying) {
        val track = deck.currentTrack
        if (track == null || deck.tracks.isEmpty()) {
            return LcdScreen.Menu("Now Playing", emptyList(), -1, 0, 0, emptyText = "Nothing playing")
        }
        return LcdScreen.NowPlaying(
            trackTitle = track.title,
            artist = track.artist?.takeIf { it.isNotBlank() } ?: albumArtist,
            album = albumTitle,
            trackNumber = deck.currentTrackIndex + 1,
            trackCount = deck.tracks.size,
            positionMs = positionMs,
            durationMs = deck.durationMs.takeIf { it > 0 } ?: track.durationMs,
            cover = cover,
            bar = bar,
            volume = volume,
            scrubMs = scrubMs,
            lyrics = lyrics
        )
    }
    if (key == MenuKey.CoverFlow || key is MenuKey.Flipped) {
        if (albumsInOrder.isEmpty()) {
            return LcdScreen.Menu("Cover Flow", emptyList(), -1, 0, 0, emptyText = "No albums yet")
        }
        val flowLevel = if (key is MenuKey.Flipped) nav.stack[nav.stack.size - 2] else nav.current
        val centre = flowLevel.selected.coerceIn(0, albumsInOrder.lastIndex)
        val from = (centre - COVER_WINDOW).coerceAtLeast(0)
        val to = (centre + COVER_WINDOW).coerceAtMost(albumsInOrder.lastIndex)
        val covers = (from..to).map { i ->
            val album = albumsInOrder[i]
            LcdCover(album.id, i, coverArt[album.id])
        }
        val back = if (key is MenuKey.Flipped) {
            val rows = PocketMenus.rows(key, library, trackTitles, hasNowPlaying)
            val level = nav.clamp(rows.size).current
            val end = minOf(level.top + PocketMenus.VISIBLE_ROWS, rows.size)
            LcdScreen.Menu(
                title = albumsInOrder[centre].title,
                rows = (if (level.top < end) rows.subList(level.top, end) else emptyList())
                    .map { LcdRow(it.label, it.leadsOn) },
                selected = level.selected - level.top,
                top = level.top,
                total = rows.size,
                emptyText = if (key.albumId in trackTitles) "No tracks" else ""
            )
        } else {
            null
        }
        return LcdScreen.CoverFlow(covers, albumsInOrder[centre].title, albumsInOrder[centre].artist, back)
    }
    if (key is MenuKey.Adjust) {
        val control = key.control
        val position = settings.sound[control] ?: 0f
        val (low, high) = PocketSound.ends(control)
        return LcdScreen.Slider(
            title = control.title,
            position = position,
            bipolar = control.bipolar,
            valueText = PocketSound.label(control, position),
            lowEnd = low,
            highEnd = high
        )
    }
    val rows = PocketMenus.rows(key, library, trackTitles, hasNowPlaying, settings)
    val level = nav.clamp(rows.size).current
    val end = minOf(level.top + PocketMenus.VISIBLE_ROWS, rows.size)
    val window = if (level.top < end) rows.subList(level.top, end) else emptyList()
    return LcdScreen.Menu(
        title = PocketMenus.title(key, library),
        rows = window.map { LcdRow(it.label, it.leadsOn, it.value, it.checked) },
        selected = level.selected - level.top,
        top = level.top,
        total = rows.size,
        art = if (key == MenuKey.Main) slides else null,
        emptyText = when (key) {
            MenuKey.Albums, MenuKey.Artists -> "No albums yet"
            MenuKey.Shelves -> "No shelves yet"
            is MenuKey.Album -> if (key.albumId in trackTitles) "No tracks" else ""
            else -> ""
        }
    )
}

/** Lyric lines on the page at once. */
private const val LYRIC_ROWS = 7

/**
 * The lyrics page's window onto the words: following the line being sung — kept third from the
 * top so what comes next is in view — unless the wheel has scrolled it, in which case [top].
 */
private fun lyricsView(
    state: LyricsState?,
    positionMs: Long,
    top: Int,
    following: Boolean
): LcdScreen.LyricsView = when (state) {
    null, LyricsState.Idle, LyricsState.Looking -> LcdScreen.LyricsView(emptyList(), -1, "Looking for the words…")
    LyricsState.None -> LcdScreen.LyricsView(emptyList(), -1, "No lyrics for this song")
    is LyricsState.Ready -> {
        val lines = state.lyrics.lines
        val current = state.lyrics.lineAt(positionMs)
        val first = if (following && current >= 0) current - 2 else top
        val from = first.coerceIn(0, (lines.size - LYRIC_ROWS).coerceAtLeast(0))
        val shown = lines.subList(from, minOf(from + LYRIC_ROWS, lines.size)).map { it.text }
        LcdScreen.LyricsView(shown, if (current >= 0) current - from else -1, null, top = from)
    }
}

/**
 * The device's behaviour: what each touch of the wheel does, given where the menus are and
 * what is playing.
 *
 * A plain object rather than composable state, because SceneView is handed its touch and frame
 * callbacks once. The room writes the latest library and player state into it on every
 * composition, and what it changes — the menus, the bar, the volume shown — goes back out
 * through Compose state so the screen redraws.
 */
private class PocketDevice(
    context: android.content.Context,
    private val viewModel: WallSceneViewModel,
    /** Saved with the room, so a trip to Settings and back returns to the same row. */
    val nav: androidx.compose.runtime.MutableState<PocketNav>
) {

    val bar = mutableStateOf(LcdScreen.Bar.PROGRESS)
    val volume = mutableStateOf(0f)
    val scrubMs = mutableStateOf(0L)
    val awake = mutableStateOf(true)
    val playingNow = mutableStateOf(false)

    /**
     * The backlight, which goes out after [backlightMs] without a touch — ten seconds unless the
     * device's own Settings say otherwise, as on the classic — and comes back on the next touch,
     * which still does what it was going to do.
     */
    val backlight = mutableStateOf(true)

    /**
     * The lyrics page on Now Playing, the classic's third press of the centre. While it is up,
     * turning scrolls the words; a few seconds after the last turn they follow the song again.
     */
    val lyricsOpen = mutableStateOf(false)
    val lyricTop = mutableStateOf(0)
    val lyricsFollowing = mutableStateOf(true)
    var lyricLineCount = 0
    var lyricShownTop = 0
    private var lyricTurnedAt = 0L

    var library = PocketLibrary()
    var trackTitles: Map<String, List<String>> = emptyMap()
    var settings = PocketSettings()
    var eqPresets: List<String> = emptyList()
    var backlightMs = 10_000L
    var clickerOn = true

    /** The EQ preset chosen here this session; the equaliser itself only remembers bands. */
    val eqPicked = mutableStateOf<Int?>(null)
    var deck = com.jawon.app.ui.wallscene.DeckState()
    var positionMs = 0L
    var hasNowPlaying = false
    var onOpenSettings: () -> Unit = {}
    var onAddAlbum: () -> Unit = {}

    var deviceNode: Node? = null
    var cameraNode: CameraNode? = null
    var windowMaterial: com.google.android.filament.MaterialInstance? = null
    var tilt: TiltFilter? = null
    var indirectLight: com.google.android.filament.IndirectLight? = null

    // The world's turn against the device, as a column-major 3×3 — written only when it changes.
    private val worldTurn = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var turnPitch = Float.NaN
    private var turnYaw = Float.NaN
    val lampNodes = arrayOfNulls<io.github.sceneview.node.LightNode>(PassingLamps.COUNT)

    private val train = TrainMotion()
    private val passing = PassingLamps()

    private val gesture = WheelGesture()
    private val rock = WheelRock()

    // The model's own moving parts: the ring and its print tip together; the button goes down.
    private var wheelParts: List<Node> = emptyList()
    private var buttonPart: Node? = null
    private var lastFrameAt = 0L
    private var rockWasMoving = false
    private var lastInputAt = 0L
    private val haptics = RoomHaptics(context)
    private val clicker = ClickPlayer(context)
    private val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

    private var lastClickAt = 0L
    private var barTouchedAt = 0L

    // Fast-forward and rewind while a side is held.
    private var seekDirection = 0
    private var seekStartedAt = 0L
    private var seekTickAt = 0L
    private var seekCursorMs = 0L

    // The scrubber's seek, throttled — the playhead moves every step, the player less often.
    private var scrubPending = false
    private var scrubSeekAt = 0L

    private val wheelPoint = FloatArray(2)

    fun close() {
        gesture.cancel()
        clicker.close()
    }

    // ---- input ------------------------------------------------------------------------------

    fun onTouch(event: MotionEvent): Boolean {
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!toWheel(event.x, event.y)) return false
                handle(gesture.down(wheelPoint[0], wheelPoint[1], now), now)
                return gesture.isDown
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gesture.isDown) return false
                // Every sample the touch carries, not just the latest: at a fast spin the
                // batched history is most of the path, and angle is only right if it is walked.
                for (i in 0 until event.historySize) {
                    if (toWheel(event.getHistoricalX(i), event.getHistoricalY(i))) {
                        handle(gesture.move(wheelPoint[0], wheelPoint[1], event.getHistoricalEventTime(i)), now)
                    }
                }
                if (toWheel(event.x, event.y)) handle(gesture.move(wheelPoint[0], wheelPoint[1], now), now)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!gesture.isDown) return false
                if (toWheel(event.x, event.y)) {
                    handle(gesture.up(wheelPoint[0], wheelPoint[1], now), now)
                } else {
                    handle(gesture.cancel(), now)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handle(gesture.cancel(), now)
                return true
            }
        }
        return false
    }

    /** Finds the parts that move, by the names `build_pocket()` gives them. Once. */
    fun bindParts(nodes: List<Node>) {
        if (wheelParts.isNotEmpty()) return
        wheelParts = nodes.filter { it.name == "pocket_wheel" || it.name == "pocket_print" }
        buttonPart = nodes.firstOrNull { it.name == "pocket_button" }
    }

    /** A thumb on the wheel, or the wheel still moving from one: the room draws every vsync. */
    val interacting: Boolean
        get() = gesture.isDown || rock.isMoving

    fun onFrame(now: Long) {
        if (lastInputAt == 0L) lastInputAt = now
        handle(gesture.poll(now), now)

        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        // Written only while something moves, and once more to leave it exactly at rest.
        if (rock.isMoving || rockWasMoving) {
            rock.step(dt)
            poseParts()
        }
        rockWasMoving = rock.isMoving

        // The night going past: the train's distance, split into each layer's parallax.
        train.advance(dt)
        windowMaterial?.let { outside ->
            outside.setParameter("scroll", train.scroll(0), train.scroll(1), train.scroll(2), train.scroll(3))
            val (bobX, bobY) = train.bob()
            outside.setParameter("bob", bobX, bobY)
        }

        // The world turned against the phone's tilt: the environment, and the lamps in it.
        turnWorld()

        // And the lamps out there that land on the device, keeping pace with the train.
        passing.advance(dt, pace = train.speed() / NightWindow.BASE_SPEED_M_S)
        val m = worldTurn
        passing.lamps.forEachIndexed { i, lamp ->
            val node = lampNodes[i] ?: return@forEachIndexed
            if (lamp.active) {
                node.position = Position(
                    m[0] * lamp.x + m[3] * lamp.y + m[6] * lamp.z,
                    m[1] * lamp.x + m[4] * lamp.y + m[7] * lamp.z,
                    m[2] * lamp.x + m[5] * lamp.y + m[8] * lamp.z
                )
                node.color = Float4(lamp.r, lamp.g, lamp.b, 1f)
            }
            node.setIntensityCandela(lamp.intensity)
        }

        // The backlight timer, as set in the device's own Settings; 0 is Always On.
        if (backlight.value && backlightMs > 0L && now - lastInputAt > backlightMs) backlight.value = false

        if (seekDirection != 0 && now - seekTickAt >= SEEK_TICK_MS) {
            seekTickAt = now
            // The classic's cue: slow at first, then faster the longer the side is held.
            val rate = if (now - seekStartedAt < SEEK_FAST_AFTER_MS) SEEK_RATE_SLOW else SEEK_RATE_FAST
            val duration = deck.durationMs
            seekCursorMs = (seekCursorMs + (seekDirection * rate * SEEK_TICK_MS).toLong())
                .coerceIn(0L, (duration - 500L).coerceAtLeast(0L))
            viewModel.onSeek(seekCursorMs)
        }

        if (scrubPending && now - scrubSeekAt >= SCRUB_SEEK_MS) {
            scrubPending = false
            scrubSeekAt = now
            viewModel.onSeek(scrubMs.value)
        }

        // Scrolled lyrics go back to following the song once the wheel has been left a while.
        if (lyricsOpen.value && !lyricsFollowing.value && now - lyricTurnedAt > LYRICS_FOLLOW_AFTER_MS) {
            lyricsFollowing.value = true
        }

        // The scrubber and the volume fall back to the song's progress once the wheel is left.
        if (bar.value != LcdScreen.Bar.PROGRESS && now - barTouchedAt > BAR_IDLE_MS) {
            if (scrubPending) {
                scrubPending = false
                viewModel.onSeek(scrubMs.value)
            }
            bar.value = LcdScreen.Bar.PROGRESS
        }
    }

    /**
     * A point on the glass, onto the wheel: millimetres in the wheel's plane, into [wheelPoint].
     * Projects the wheel's centre and two points on its axes through the device's current pose,
     * so it stays right however the device is turned.
     */
    private fun toWheel(x: Float, y: Float): Boolean {
        val node = deviceNode ?: return false
        val camera = cameraNode ?: return false
        val m = node.worldTransform
        fun screen(lx: Float, ly: Float): Vector3 {
            val w = m * Float4(lx, ly, ClickWheel.FACE_Z_M, 1f)
            return camera.worldToScreenPoint(Vector3(w.x, w.y, w.z))
        }
        val r = ClickWheel.WHEEL_R_MM / 1000f
        val c = screen(0f, ClickWheel.CENTRE_Y_M)
        val ex = screen(r, ClickWheel.CENTRE_Y_M)
        val ey = screen(0f, ClickWheel.CENTRE_Y_M + r)
        return ClickWheel.unproject(c.x, c.y, ex.x, ex.y, ey.x, ey.y, x, y, wheelPoint)
    }

    /**
     * The inverse of the phone's tilt, applied to the world: `Ry(-yaw) · Rx(-pitch)`. Rotating the
     * environment the other way is the same, to the eye, as the device turning inside a still
     * room — which is what the phone in the hand is doing.
     */
    private fun turnWorld() {
        val t = tilt ?: return
        val p = t.pitch
        val y = t.yaw
        if (p == turnPitch && y == turnYaw) return
        turnPitch = p
        turnYaw = y
        val cp = kotlin.math.cos(-p)
        val sp = kotlin.math.sin(-p)
        val cy = kotlin.math.cos(-y)
        val sy = kotlin.math.sin(-y)
        // Ry(-y) · Rx(-p), column by column.
        worldTurn[0] = cy; worldTurn[1] = 0f; worldTurn[2] = -sy
        worldTurn[3] = sy * sp; worldTurn[4] = cp; worldTurn[5] = cy * sp
        worldTurn[6] = sy * cp; worldTurn[7] = -sp; worldTurn[8] = cy * cp
        indirectLight?.setRotation(worldTurn)
    }

    /**
     * The ring about its centre, tipped and sunk so it pivots on its far edge ([WheelRock]); the
     * print is on the ring and goes with it; the button goes straight down.
     */
    private fun poseParts() {
        // Spelled out rather than `centre + …`: kotlin-math's vector operators are inline and
        // built for a newer JVM target than this module, so they will not compile here.
        val y = ClickWheel.CENTRE_Y_M
        val z = ClickWheel.FACE_Z_M
        val ring = translation(Float3(0f, y, z - rock.sinkMm / 1000f)) *
            rotation(X_AXIS, rock.tiltXDeg) *
            rotation(Y_AXIS, rock.tiltYDeg) *
            translation(Float3(0f, -y, -z))
        for (part in wheelParts) part.transform = ring
        buttonPart?.transform = translation(Float3(0f, 0f, -rock.buttonMm / 1000f))
    }

    // ---- behaviour --------------------------------------------------------------------------

    private fun handle(events: List<WheelGesture.Event>, now: Long) {
        for (e in events) handle(e, now)
    }

    private fun handle(event: WheelGesture.Event, now: Long) {
        lastInputAt = now
        backlight.value = true
        when (event) {
            is WheelGesture.Event.Press -> rock.tap(event.button)
            is WheelGesture.Event.HoldStart -> rock.hold(event.button)
            is WheelGesture.Event.HoldEnd -> rock.release()
            is WheelGesture.Event.Turn -> Unit
        }
        if (!awake.value) {
            // Switched off: any button wakes it, as on the classic. Turning does not.
            if (event is WheelGesture.Event.Press) {
                awake.value = true
                press()
            }
            return
        }
        when (event) {
            is WheelGesture.Event.Turn -> turn(event, now)
            is WheelGesture.Event.Press -> pressed(event.button, now)
            is WheelGesture.Event.HoldStart -> held(event.button, now)
            is WheelGesture.Event.HoldEnd -> released(event.button)
        }
    }

    private fun rows(): List<MenuRow> =
        PocketMenus.rows(nav.value.current.key, library, trackTitles, hasNowPlaying, settings)

    private fun turn(turn: WheelGesture.Event.Turn, now: Long) {
        val adjusting = nav.value.current.key
        if (adjusting is MenuKey.Adjust) {
            adjust(adjusting.control, turn.steps, now)
            return
        }
        if (nav.value.current.key == MenuKey.NowPlaying && deck.tracks.isNotEmpty()) {
            if (lyricsOpen.value && lyricLineCount > 0 && bar.value != LcdScreen.Bar.SCRUB) {
                scrollLyrics(turn.steps, now)
                return
            }
            barTouchedAt = now
            if (bar.value == LcdScreen.Bar.SCRUB) scrub(turn) else changeVolume(turn.steps)
            return
        }
        val rows = rows()
        val items = turn.steps * ClickWheel.itemsPerStep(turn.degPerSecond, rows.size)
        val clamped = nav.value.clamp(rows.size)
        val moved = clamped.move(items, rows.size)
        if (moved != clamped) {
            nav.value = moved
            step(now)
        } else {
            // At the end of the list the highlight stops, and so does the clicking — the
            // classic's way of telling you there is no more without showing you anything.
            nav.value = clamped
        }
    }

    /**
     * One of the rack's faders, turned from the pocket player. Read straight from the view
     * model's flows rather than from the last composition, so a fast spin never builds on a
     * stale value; and the effect's cell switched in, or a bypassed effect would turn silently.
     */
    private fun adjust(control: SoundControl, steps: Int, now: Long) {
        val before = soundValue(control)
        val after = PocketSound.nudge(control, before, steps)
        if (after == before) return
        viewModel.engage(control.channel)
        setSound(control, after)
        step(now)
    }

    private fun soundValue(control: SoundControl): Float = when (control) {
        SoundControl.FILTER -> viewModel.filterPosition.value
        SoundControl.SPEED -> viewModel.speedPosition.value
        SoundControl.DRIVE -> viewModel.drivePosition.value
        SoundControl.ECHO -> viewModel.echoMix.value
        SoundControl.ECHO_TIME -> viewModel.echoTime.value
        SoundControl.ECHO_FEEDBACK -> viewModel.echoFeedback.value
        SoundControl.BASS -> viewModel.bassStrength.value
        SoundControl.WIDTH -> viewModel.stereoWidth.value
    }

    private fun setSound(control: SoundControl, value: Float) {
        when (control) {
            SoundControl.FILTER -> viewModel.onFilterChange(value)
            SoundControl.SPEED -> viewModel.onSpeedChange(value)
            SoundControl.DRIVE -> viewModel.onDriveChange(value)
            SoundControl.ECHO -> viewModel.onEchoMixChange(value)
            SoundControl.ECHO_TIME -> viewModel.onEchoTimeChange(value)
            SoundControl.ECHO_FEEDBACK -> viewModel.onEchoFeedbackChange(value)
            SoundControl.BASS -> viewModel.onBassChange(value)
            SoundControl.WIDTH -> viewModel.onWidthChange(value)
        }
    }

    /** Flat: every effect back to where it does nothing. Echo's time and repeats are kept. */
    private fun flatten() {
        setSound(SoundControl.FILTER, 0f)
        setSound(SoundControl.SPEED, 0f)
        setSound(SoundControl.DRIVE, 0f)
        setSound(SoundControl.ECHO, 0f)
        setSound(SoundControl.BASS, 0f)
        setSound(SoundControl.WIDTH, 0f)
        viewModel.onReverbChange(0)
    }

    private fun scrollLyrics(steps: Int, now: Long) {
        val maxTop = (lyricLineCount - LYRIC_ROWS).coerceAtLeast(0)
        // From what is on screen: while following the song that is wherever the song has got
        // to, not wherever the page was last scrolled — the first version jumped back to it.
        val base = if (lyricsFollowing.value) lyricShownTop else lyricTop.value
        val next = (base + steps).coerceIn(0, maxTop)
        lyricsFollowing.value = false
        lyricTurnedAt = now
        if (next != lyricTop.value) {
            lyricTop.value = next
            step(now)
        }
    }

    private fun scrub(turn: WheelGesture.Event.Turn) {
        val duration = deck.durationMs
        if (duration <= 0L) return
        // A quarter of the song a revolution, faster when spun — enough to cross a long track
        // in a few turns, fine enough to land on a line.
        val perStep = (duration / SCRUB_STEPS_PER_TRACK).coerceAtLeast(1_000L)
        val items = turn.steps * ClickWheel.itemsPerStep(turn.degPerSecond, Int.MAX_VALUE)
        val next = (scrubMs.value + items * perStep).coerceIn(0L, (duration - 500L).coerceAtLeast(0L))
        if (next != scrubMs.value) {
            scrubMs.value = next
            scrubPending = true
            step(SystemClock.uptimeMillis())
        }
    }

    private fun changeVolume(steps: Int) {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val before = audio.getStreamVolume(stream)
        val direction = if (steps > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        // No flags: the device shows its own volume bar, and the phone's panel appearing over
        // it would break the illusion that this is the thing being turned.
        repeat(kotlin.math.abs(steps)) { audio.adjustStreamVolume(stream, direction, 0) }
        val after = audio.getStreamVolume(stream)
        bar.value = LcdScreen.Bar.VOLUME
        refreshVolume()
        if (after != before) step(SystemClock.uptimeMillis())
    }

    private fun refreshVolume() {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        volume.value = audio.getStreamVolume(stream) / max.toFloat()
    }

    private fun pressed(button: ClickWheel.Button, now: Long) {
        press()
        val key = nav.value.current.key
        val onNowPlaying = key == MenuKey.NowPlaying && deck.tracks.isNotEmpty()
        when (button) {
            ClickWheel.Button.MENU -> back(silent = true)
            ClickWheel.Button.SELECT -> {
                // On a slider the centre is done, as MENU is.
                if (key is MenuKey.Adjust) {
                    nav.value = nav.value.back()
                    return
                }
                if (onNowPlaying) {
                    // The classic's cycle: the scrubber, then the words, then back to the song.
                    barTouchedAt = now
                    when {
                        bar.value == LcdScreen.Bar.SCRUB -> {
                            bar.value = LcdScreen.Bar.PROGRESS
                            lyricsOpen.value = true
                            lyricsFollowing.value = true
                            lyricTop.value = 0
                        }
                        lyricsOpen.value -> lyricsOpen.value = false
                        else -> {
                            scrubMs.value = positionMs
                            bar.value = LcdScreen.Bar.SCRUB
                        }
                    }
                    return
                }
                val level = nav.value.clamp(rows().size).current
                val row = rows().getOrNull(level.selected) ?: return
                act(row.action)
            }
            ClickWheel.Button.PLAY -> play()
            ClickWheel.Button.NEXT -> if (hasNowPlaying) viewModel.onNext()
            ClickWheel.Button.PREVIOUS -> if (hasNowPlaying) viewModel.onPrevious()
        }
    }

    /**
     * Play/Pause. **With music playing it pauses, whatever is highlighted** — on the classic
     * you choose a song with the centre, and play/pause never swaps the record out from under
     * you. With nothing playing, a highlighted album or song is started, which is how the
     * classic let you play a whole album straight from its list.
     */
    private fun play() {
        if (deck.isPlaying) {
            viewModel.setPlaying(false)
            return
        }
        val key = nav.value.current.key
        if (key != MenuKey.NowPlaying) {
            val row = rows().getOrNull(nav.value.clamp(rows().size).current.selected)
            when (val action = row?.action) {
                is RowAction.PlayTrack -> {
                    viewModel.playAlbumFrom(action.albumId, action.index)
                    showNowPlaying()
                    return
                }
                is RowAction.Open -> {
                    val albumId = when (val target = action.key) {
                        is MenuKey.Album -> target.albumId
                        is MenuKey.Flipped -> target.albumId
                        else -> null
                    }
                    if (albumId != null) {
                        viewModel.playAlbum(albumId)
                        showNowPlaying()
                        return
                    }
                }
                else -> Unit
            }
        }
        if (hasNowPlaying) viewModel.setPlaying(true)
    }

    private fun act(action: RowAction) {
        when (action) {
            is RowAction.Open -> {
                if (action.key == MenuKey.NowPlaying) bar.value = LcdScreen.Bar.PROGRESS
                var next = nav.value.open(action.key)
                if (action.key is MenuKey.Choice) {
                    // A list of choices opens on the one in force, as the classic's did.
                    val choices = PocketMenus.rows(action.key, library, trackTitles, hasNowPlaying, settings)
                    val chosen = choices.indexOfFirst { it.checked }
                    if (chosen > 0) next = next.move(chosen, choices.size)
                }
                nav.value = next
            }
            is RowAction.PlayTrack -> {
                viewModel.playAlbumFrom(action.albumId, action.index)
                showNowPlaying()
            }
            RowAction.AddAlbums -> onAddAlbum()
            RowAction.OpenSettings -> onOpenSettings()
            is RowAction.Choose -> {
                choose(action.setting, action.option)
                // The classic went back to Settings once a choice was made.
                nav.value = nav.value.back()
            }
            RowAction.Nothing -> Unit
            RowAction.FlatSound -> flatten()
        }
    }

    private fun choose(setting: PocketSetting, option: Int) {
        when (setting) {
            PocketSetting.EQ -> {
                if (option == 0) {
                    viewModel.onEqEnabledChanged(false)
                } else {
                    viewModel.onEqEnabledChanged(true)
                    viewModel.onEqPreset(option - 1)
                }
                eqPicked.value = option
            }
            PocketSetting.SOUND_CHECK -> viewModel.onReplayGainModeChanged(
                when (option) {
                    0 -> com.jawon.app.playback.ReplayGain.Mode.OFF
                    1 -> com.jawon.app.playback.ReplayGain.Mode.ALBUM
                    else -> com.jawon.app.playback.ReplayGain.Mode.TRACK
                }
            )
            PocketSetting.BACKLIGHT -> PocketSettings.BACKLIGHT_OPTIONS_MS.getOrNull(option)
                ?.let { viewModel.onPocketBacklightChanged(it) }
            PocketSetting.CLICKER -> viewModel.onPocketClickerChanged(option == 0)
            PocketSetting.REVERB -> {
                if (option > 0) viewModel.engage(com.jawon.app.playback.RackChannel.SPACE)
                viewModel.onReverbChange(option)
            }
        }
    }

    /** Whatever starts a record goes to Now Playing, as the classic did; MENU comes back. */
    private fun showNowPlaying() {
        bar.value = LcdScreen.Bar.PROGRESS
        if (nav.value.current.key != MenuKey.NowPlaying) nav.value = nav.value.open(MenuKey.NowPlaying)
    }

    private fun held(button: ClickWheel.Button, now: Long) {
        when (button) {
            ClickWheel.Button.MENU -> {
                press()
                nav.value = nav.value.main()
            }
            ClickWheel.Button.PLAY -> {
                // Held, Play/Pause switches the player off: the music stops and the screen goes
                // dark, until any button is pressed.
                press()
                viewModel.setPlaying(false)
                bar.value = LcdScreen.Bar.PROGRESS
                awake.value = false
            }
            ClickWheel.Button.NEXT, ClickWheel.Button.PREVIOUS -> {
                if (!hasNowPlaying) return
                press()
                seekDirection = if (button == ClickWheel.Button.NEXT) 1 else -1
                seekStartedAt = now
                seekTickAt = now
                seekCursorMs = positionMs
            }
            ClickWheel.Button.SELECT -> Unit
        }
    }

    private fun released(button: ClickWheel.Button) {
        if (button == ClickWheel.Button.NEXT || button == ClickWheel.Button.PREVIOUS) seekDirection = 0
    }

    /** MENU, and the phone's own back: one level up. */
    fun back(silent: Boolean = false) {
        if (!silent) press()
        if (nav.value.current.key == MenuKey.NowPlaying) {
            bar.value = LcdScreen.Bar.PROGRESS
            lyricsOpen.value = false
        }
        nav.value = nav.value.back()
    }

    // ---- feedback ---------------------------------------------------------------------------

    /** One step of the wheel: the click and the tick, together and never faster than a buzz. */
    private fun step(now: Long) {
        if (now - lastClickAt < CLICK_MIN_INTERVAL_MS) return
        lastClickAt = now
        if (clickerOn) clicker.click()
        haptics.tick()
    }

    /** A button: the same click, and the heavier haptic a switch closing deserves. */
    private fun press() {
        if (clickerOn) clicker.click()
        haptics.detent()
    }

    init {
        refreshVolume()
    }

    private companion object {
        const val CLICK_MIN_INTERVAL_MS = 18L
        const val LYRICS_FOLLOW_AFTER_MS = 4_000L
        val X_AXIS = Float3(1f, 0f, 0f)
        val Y_AXIS = Float3(0f, 1f, 0f)
        const val BAR_IDLE_MS = 3_500L
        const val SEEK_TICK_MS = 200L
        const val SEEK_FAST_AFTER_MS = 2_500L
        const val SEEK_RATE_SLOW = 6f
        const val SEEK_RATE_FAST = 16f
        const val SCRUB_SEEK_MS = 150L
        const val SCRUB_STEPS_PER_TRACK = 96L
    }
}
