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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
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
import androidx.lifecycle.compose.currentStateAsState
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.jawon.app.BuildConfig
import com.jawon.app.data.lyrics.LyricsState
import com.jawon.app.ui.showroom.Exhibit
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.QualityTier
import com.jawon.app.ui.wallscene.RoomCadence
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallSceneViewModel
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import com.jawon.app.ui.common.rememberRoomEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** How dark the face goes as it is pinched away, at the moment it lets go. */
private const val PULL_DIM = 0.75f

/** The rate the room is drawn at when nobody is touching it — see the note in onFrame. */
private const val POCKET_IDLE_FPS = 60f

/** At rest the room draws only on a change — see onFrame — but never less often than this. */
private const val REDRAW_AT_LEAST_MS = 1_000L

/** Drawn every frame for this long after the room opens, while the face and its shaders arrive. */
private const val WARM_UP_MS = 2_500L

// Slots in the room's `drawState`.
private const val LCD_DRAWN = 0
private const val DRAWN_AT = 1
private const val ENTERED_AT = 2

/** How often the main menu's slideshow moves on. */
private const val SLIDE_TICK_MS = 50L

/** Covers drawn either side of the middle one in Cover Flow, and how many are kept decoded. */
internal const val COVER_WINDOW = 4
private const val COVER_CACHE_MAX = 40

/** How often the title bar's battery is read while the room is on screen. */
private const val BATTERY_READ_MS = 60_000L

/** How large sideways Cover Flow decodes its covers, and how many it keeps. */
private const val SIDEWAYS_COVER_PX = 720
private const val SIDEWAYS_CACHE_MAX = 24

/** Proximity nearer than this, held for [HOLD_ENGAGE_MS], is Hold. */
private const val HOLD_NEAR_CM = 3f
private const val HOLD_ENGAGE_MS = 400L

/**
 * How far the room's light dims the screen: nothing from about 60 lux up — an ordinary lit room —
 * down to [ROOM_DIM_MAX] in the dark, along the log of the light, which is how eyes measure it.
 */
private const val ROOM_DIM_MAX = 0.32f

internal fun roomDimFor(lux: Float): Float {
    val l = lux.coerceIn(1f, 60f)
    return ROOM_DIM_MAX * (1f - kotlin.math.ln(l) / kotlin.math.ln(60f))
}

/** The face: the phone as the player — see `PocketFace` and `build_pocket_face()`. */
private const val POCKET_FACE_ASSET = "models/pocket_face.glb"

/**
 * **How many texture pixels the screen draws per LCD pixel.** The face's picture is 59mm across,
 * about 1,150 pixels of the owner's phone, and the LCD is laid out at 640: drawn at that, every
 * letter on it was magnified 1.8 times and soft. Twice over, it is drawn finer than the glass
 * shows it, and the text is as sharp as the phone's own.
 */
private const val LCD_SUPERSAMPLE = 2f

/**
 * The lens, in millimetres against Filament's 24mm sensor height.
 *
 * Long, and that is the point: square on to a flat face, a long lens keeps the plate flat to its
 * corners, where a wide one would bend its edges outward the way a phone camera held close does.
 */
private const val FOCAL_LENGTH_MM = 50f
private const val SENSOR_HEIGHT_MM = 24f

/** The lens held level — see the `onFrame` note and trap 8. A value, so it is not reallocated per frame. */
private val LEVEL = Rotation(0f, 0f, 0f)

/**
 * The iPod mode's room — **the phone is the player**: its whole display is the player's face, the
 * window across the top and the wheel where a thumb rests, drawn straight on and life size
 * (`PocketFace`). The wheel turns through real menus built from the library, and plays from them;
 * the room around it is only what its metal and glass reflect (`PocketLighting`), sliding as the
 * phone tilts.
 *
 * A sibling of `WallSceneScreen`, never a branch inside it — `ENGINEERING.md`'s standard for a new
 * format room. What it shares, it shares by calling: the same [WallSceneViewModel] (so a record
 * is played and remembered the same way in both rooms), [RoomHaptics], `CaseArt`'s decoder.
 */
@Composable
fun PocketRoomScreen(
    onOpenSettings: () -> Unit,
    onAddAlbum: () -> Unit,
    /** Back at the main menu, or two fingers pinched together: the showroom between formats. */
    /** The room switcher (JawonNavHost), from the main menu's Rooms row. */
    onOpenRooms: () -> Unit = {},
    /** Settings' Guide, on this player's page — from the device's Settings → Guide. */
    onOpenGuide: () -> Unit = {},
    onOpenShowroom: () -> Unit = {},
    /** A 2D panel is up over the room: it stays composed, stops rendering, and its Back stands aside. */
    covered: Boolean = false,
    viewModel: WallSceneViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val engine = rememberRoomEngine() // not SceneView's: see RoomEngine, its teardown leaked the GL context
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

    val faceModel = rememberModelInstance(modelLoader, POCKET_FACE_ASSET)
    val ydpi = remember(context) { max(context.resources.displayMetrics.ydpi, 1f) }
    // The same guess the CD room starts from (RAM and API level), so a phone gets one tier everywhere.
    val tier = remember(context) { QualityTier.detect(context) }
    // How often the room is actually drawn — the CD room's cadence, reused by call. See onFrame.
    val cadence = remember(context) {
        RoomCadence().apply {
            refreshHz = context.display?.refreshRate ?: RoomCadence.DEFAULT_REFRESH_HZ
            maxFps = tier.maxFps
        }
    }
    val publishedDivisor = remember { intArrayOf(0) }
    // What the room last put on the glass: the screen's frame count, when, and when it opened.
    val drawState = remember { LongArray(3) }
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
    val finishName by viewModel.pocketFinish.collectAsStateWithLifecycle()
    val motionOn by viewModel.pocketMotion.collectAsStateWithLifecycle()
    val hintsSeen by viewModel.hintsSeen.collectAsStateWithLifecycle()
    val brickBest by viewModel.pocketBrickBest.collectAsStateWithLifecycle()
    val finish = PocketFinish.fromName(finishName)
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
    device.finish = finish
    device.motionOn = motionOn
    device.brickBest = maxOf(device.brickBest, brickBest)
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
        reverb = reverb,
        finish = finish.ordinal,
        motion = motionOn
    )
    device.deck = deck
    device.positionMs = positionMs
    device.hasNowPlaying = nowPlayingAlbumId != null && deck.tracks.isNotEmpty()
    device.onOpenSettings = onOpenSettings
    device.onAddAlbum = onAddAlbum
    device.onOpenRooms = onOpenRooms
    device.onOpenGuide = onOpenGuide
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
    // The view model's, so the covers survive the room being rebuilt (see pocketCoverArt).
    val coverArt = viewModel.pocketCoverArt
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
    val showingSlides = onMain && slideOrder.isNotEmpty() && device.awake.value && device.backlight.value && !covered
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
        },
        searchLetter = device.searchLetter.intValue,
        searchInResults = device.searchInResults.value
    )
    // Where the lyrics page is actually showing from, for the wheel's first turn on it.
    device.lyricShownTop = (screen as? LcdScreen.NowPlaying)?.lyrics?.top ?: 0

    val lcd = remember { mutableStateOf(screen) }
    SideEffect { lcd.value = screen }
    // Which page it is, so the screen can slide between pages as the classic's did.
    val lcdPage = remember { mutableStateOf(nav.page) }
    SideEffect { lcdPage.value = nav.page }

    // The screen's own composition, on LcdPanel's hardware-drawn surface. Set once: everything it
    // shows is read from state inside it, and any change beneath the panel's host redraws the
    // texture (LcdPanel.onDescendantInvalidated) — which is what trap 33 needed all along.
    // The phone's battery, for the title bar's — the device is the phone, so its battery is the phone's.
    val battery = remember { mutableFloatStateOf(0.75f) }
    val charging = remember { mutableStateOf(false) }
    //
    // **Read, not listened to.** The battery's state is a sticky broadcast, and asking for it with
    // no receiver hands back the last one without registering anything. A registered receiver was
    // the first version, flagged not-exported as Android 14 wants — and below Android 13 the compat
    // library implements that flag with a permission of the app's own, which the system's sender
    // does not hold, so on Android 9 every update was refused ("Permission Denial: broadcasting
    // Intent BATTERY_CHANGED", on the API 28 emulator). A reading a minute while the room is on
    // screen is all a title-bar battery needs.
    val roomOnScreen = !covered && androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        .currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(roomOnScreen) {
        while (roomOnScreen) {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            if (intent != null) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) battery.floatValue = level / scale.toFloat()
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                charging.value = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
            kotlinx.coroutines.delay(BATTERY_READ_MS)
        }
    }

    val lcdPanel = remember(engine) { LcdPanel(context, engine, LCD_PX_W, LCD_PX_H, LCD_SUPERSAMPLE) }
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
                // The backlight's own fade, or the room's light taking the edge off it — whichever is darker.
                unlit = maxOf(unlit, device.roomDim.floatValue),
                flow = flowAnim.value + device.flowDrift.floatValue,
                flip = flipAnim.value,
                slide = slideClock.floatValue,
                page = lcdPage.value.path,
                depth = lcdPage.value.depth,
                held = device.hold.value,
                battery = battery.floatValue,
                charging = charging.value,
                brick = device.brickFrame.value
            )
        }
    }

    BackHandler(enabled = !covered && nav.stack.size > 1) { device.back() }
    // At the main menu, Back goes up out of the player to the showroom between formats, in a build
    // that has one — the same way out as the CD room's, so the two rooms leave alike.
    BackHandler(enabled = !covered && nav.stack.size <= 1 && device.showroomReachable) { device.onOpenShowroom() }

    // **The phone is the player**, so tilting it turns the player against the room: the room's
    // reflections slide across the face and the glass — first-order, twice the tilt, as a turned
    // mirror's do. Only the change from how it is held counts (TiltFilter). Listening only while
    // the room is on screen: a sensor left running in the background is battery for nothing.
    val tilt = remember { TiltFilter() }
    // Turned on its side: Cover Flow fills the screen (SidewaysCoverFlow). Read from gravity, since
    // the activity itself never turns.
    val sideways = remember { SidewaysDetector() }
    val holding = remember { mutableStateOf(Holding.UPRIGHT) }
    // Back while sideways puts Cover Flow away until the phone has been upright again.
    val sidewaysDismissed = remember { mutableStateOf(false) }
    val currentMotion = androidx.compose.runtime.rememberUpdatedState(motionOn)
    val currentKeyState = androidx.compose.runtime.rememberUpdatedState(currentKey)
    // Keyed on `covered` too (0.7.2): a room parked off the stage, or under a panel, listens to nothing.
    LifecycleResumeEffect(context, covered) {
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
        // **Gravity**, for which way the phone is held (sideways Cover Flow) and how far it leans
        // (Brick's paddle, Cover Flow's drift). The platform's own gravity sensor where there is
        // one; otherwise the accelerometer, low-passed down to the gravity in it.
        val gravitySensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelerometer = if (gravitySensor == null) sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val gravity = FloatArray(3)
        var haveGravity = false
        val gravityListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && haveGravity) {
                    for (i in 0..2) gravity[i] += (event.values[i] - gravity[i]) * 0.12f
                } else {
                    for (i in 0..2) gravity[i] = event.values[i]
                }
                haveGravity = true
                device.lean = leanOf(gravity[0], gravity[1], gravity[2])
                val now = SystemClock.uptimeMillis()
                val seen = sideways.update(gravity[0], gravity[1], gravity[2], now)
                // Not in Brick, whose paddle is steered by the very lean that would turn it; not
                // with Tilt & Turn off; not with the player switched off or held.
                val allowed = currentMotion.value && currentKeyState.value != MenuKey.Brick &&
                    device.awake.value && !device.hold.value
                val shown = if (allowed) seen else Holding.UPRIGHT
                if (seen == Holding.UPRIGHT) sidewaysDismissed.value = false
                if (holding.value != shown) holding.value = shown
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // **Hold**, from the proximity sensor: covered for a moment — a pocket, a face-down phone
        // — and the wheel stops answering; uncovered, it answers at once.
        val proximity = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val engageHold = Runnable {
            device.hold.value = true
            device.backlight.value = false
        }
        val proximityListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val range = event.sensor.maximumRange
                val near = event.values[0] < minOf(range, HOLD_NEAR_CM)
                holdHandler.removeCallbacks(engageHold)
                if (near) {
                    holdHandler.postDelayed(engageHold, HOLD_ENGAGE_MS)
                } else if (device.hold.value) {
                    device.hold.value = false
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // **A backlight that answers the room**, from the light sensor: in the dark the screen
        // dims a little, eased so a passing shadow does not flicker it.
        val light = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
        var dim = device.roomDim.floatValue
        val lightListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val target = roomDimFor(event.values[0])
                dim += (target - dim) * 0.25f
                val shown = kotlin.math.round(dim * 100f) / 100f
                if (shown != device.roomDim.floatValue) device.roomDim.floatValue = shown
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        tilt.reset()
        sideways.reset()
        holding.value = Holding.UPRIGHT
        // Back from the background the surface is new and holds nothing: draw through a warm-up
        // again rather than waiting on a change that may not come for a second.
        drawState[ENTERED_AT] = 0L
        if (!covered && sensors != null) {
            rotation?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            (gravitySensor ?: accelerometer)?.let { sensors.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_GAME) }
            proximity?.let { sensors.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
            light?.let { sensors.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        onPauseOrDispose {
            sensors?.unregisterListener(listener)
            sensors?.unregisterListener(gravityListener)
            sensors?.unregisterListener(proximityListener)
            sensors?.unregisterListener(lightListener)
            holdHandler.removeCallbacks(engageHold)
            // Out of the room nothing is held, and no lean is left steering anything.
            device.hold.value = false
            device.lean = 0f
            holding.value = Holding.UPRIGHT
        }
    }
    device.tilt = tilt
    device.indirectLight = environment.indirectLight

    val sidewaysUp = holding.value != Holding.UPRIGHT && !sidewaysDismissed.value && !covered &&
        albumsInOrder.isNotEmpty()
    BackHandler(enabled = sidewaysUp) { sidewaysDismissed.value = true }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // **Life size, computed from the scene's own height rather than chosen by eye.**
        //
        // The frame is `24 * distance / focal` tall for Filament's 24mm sensor. Put the
        // *physical height of the surface being drawn into* there and every millimetre of
        // model is a millimetre of glass: on the display the face was drawn for, the face *is* the
        // display, and everywhere else it is the same face scaled to fit (PocketFace.fit).
        //
        // The surface's height, measured here — not `displayMetrics.heightPixels`. That is the
        // window without its system bars (2807px on a 3120px panel), while the room draws edge
        // to edge under them, so the first version was 11% short of life size and nothing on
        // screen said so. The instrumentation that caught it read the viewport at 1440x3120.
        val sceneHeightM = constraints.maxHeight / ydpi * 0.0254f
        // The width by the same measure: the frame's width is its height times the surface's
        // shape, and the lens has no other idea of a millimetre.
        val sceneWidthM = sceneHeightM * constraints.maxWidth / max(constraints.maxHeight, 1)
        val fit = PocketFace.fit(sceneWidthM, sceneHeightM)
        val cameraZ = sceneHeightM * FOCAL_LENGTH_MM / SENSOR_HEIGHT_MM
        cameraNode.position = Position(0f, 0f, cameraZ)
        device.cameraNode = cameraNode
        device.cameraZ = cameraZ

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            // Under a panel, or behind sideways Cover Flow: park the loop, and leave the last frame.
            isRendering = !covered && !sidewaysUp,
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
                val regraded = !PocketAtmosphere.isApplied(filamentView)
                if (regraded) PocketAtmosphere.apply(filamentView, tier)
                device.onFrame(SystemClock.uptimeMillis())
                // **Drawn at 60 unless a thumb is on the wheel.** SceneView renders every vsync —
                // 120 a second on the owner's phone — and nothing in this room moves fast enough
                // to need it except the device answering a touch: the main menu's slideshow and the
                // tilt's reflections are both slow. So the clocks all advance every vsync as
                // before and only the rasterising drops to 60, which is half the GPU work on a
                // 120Hz panel (traps 28-30: the CD room's problem is heat, not jank). A finger
                // down or the wheel still rocking is back to every vsync on the next frame.
                val live = device.interacting
                val divisor = if (live) cadence.live else cadence.divisorForFps(POCKET_IDLE_FPS)
                if (divisor != publishedDivisor[0]) {
                    publishedDivisor[0] = divisor
                    renderer.frameRateOptions = Renderer.FrameRateOptions().apply {
                        interval = divisor.toFloat()
                    }
                }
                // **And at rest, drawn only when there is something new to show.** Untouched, the
                // face changes in exactly two ways — the screen's picture, and the reflections when
                // the phone tilts — and between those a fresh frame is the last one again. It was
                // drawn at 60 regardless: 0.60 of a core and the GPU 85% busy on the owner's phone,
                // for a still menu. So a frame is drawn when the screen has delivered one, when the
                // device says the scene moved, through a warm-up while the model and the first
                // shaders arrive, and once a second whatever happens, in case anything slipped by.
                val now = SystemClock.uptimeMillis()
                if (drawState[ENTERED_AT] == 0L) drawState[ENTERED_AT] = now
                val lcdFrames = lcdPanel.framesDelivered
                val fresh = live || regraded ||
                    device.changedSinceDrawn ||
                    lcdFrames != drawState[LCD_DRAWN] ||
                    now - drawState[DRAWN_AT] >= REDRAW_AT_LEAST_MS ||
                    now - drawState[ENTERED_AT] < WARM_UP_MS
                if (cadence.shouldDraw(divisor) && fresh) {
                    device.drawn()
                    drawState[LCD_DRAWN] = lcdFrames
                    drawState[DRAWN_AT] = now
                } else {
                    renderer.skipNextFrames(1)
                }
            }
        ) {
            // **The key**: a spot above and behind you, over your left shoulder. The face is metal
            // and needs only the room to reflect; the wheel, the button and the print are plastic and
            // ink, and need light *on* them. Every number is `PocketLighting`'s, and the preview's.
            LightNode(
                type = LightManager.Type.SPOT,
                position = Position(
                    PocketLighting.KEY_POSITION[0],
                    PocketLighting.KEY_POSITION[1],
                    PocketLighting.KEY_POSITION[2]
                ),
                direction = Direction(
                    PocketLighting.KEY_AIM[0] - PocketLighting.KEY_POSITION[0],
                    PocketLighting.KEY_AIM[1] - PocketLighting.KEY_POSITION[1],
                    PocketLighting.KEY_AIM[2] - PocketLighting.KEY_POSITION[2]
                ),
                apply = {
                    color(PocketLighting.KEY_COLOUR[0], PocketLighting.KEY_COLOUR[1], PocketLighting.KEY_COLOUR[2])
                    intensityCandela(PocketLighting.KEY_CANDELA)
                    spotLightCone(PocketLighting.KEY_CONE_INNER_RAD, PocketLighting.KEY_CONE_OUTER_RAD)
                    falloff(PocketLighting.KEY_FALLOFF_M)
                    castShadows(false)
                }
            )
            faceModel?.let { instance ->
                // **The face**, square on and centred, scaled to this display (PocketFace.fit): life
                // size on the one it was drawn for, the same face a little larger or smaller on any
                // other. The screen is its child, so it scales with it.
                Node(
                    scale = Scale(fit.scale),
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
                    // **The screen**: LcdPanel's texture on a plane 0.1mm over the panel, in the face's
                    // frame. Unlit because an LCD is its own light. The panel's release is declared
                    // *before* the plane, so Compose — forgetting in reverse — takes the plane and the
                    // material sampling the texture down first.
                    DisposableEffect(lcdPanel) { onDispose { lcdPanel.release(engine) } }
                    val screenMaterial = remember(lcdPanel) {
                        materialLoader.createViewInstance(lcdPanel.texture, true, false)
                    }
                    PlaneNode(
                        size = Size(PocketFace.LCD_W_M, PocketFace.LCD_H_M, 0f),
                        normal = Direction(z = 1f),
                        materialInstance = screenMaterial,
                        position = Position(0f, PocketFace.SCREEN_Y_M, PocketFace.LCD_Z_M),
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

        // The first time in the player: over the window, clear of the wheel a thumb needs.
        if (com.jawon.app.ui.common.Hints.POCKET !in hintsSeen && !covered && !sidewaysUp) {
            com.jawon.app.ui.common.FirstRunHint(
                title = "Your phone is the player",
                body = "Slide your thumb round the wheel and press the centre to choose; MENU, at " +
                    "the top of the wheel, goes back. Turn the phone on its side for Cover Flow.",
                onGotIt = { viewModel.onHintSeen(com.jawon.app.ui.common.Hints.POCKET) },
                onGuide = {
                    viewModel.onHintSeen(com.jawon.app.ui.common.Hints.POCKET)
                    onOpenGuide()
                },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 24.dp)
            )
        }

        // **Sideways**: Cover Flow over the whole screen, turned to meet the phone.
        androidx.compose.animation.AnimatedVisibility(
            visible = sidewaysUp,
            enter = androidx.compose.animation.fadeIn(tween(180)),
            exit = androidx.compose.animation.fadeOut(tween(160))
        ) {
            // Where it opens: the cover the player was showing in Cover Flow, else the record on,
            // else the first.
            val startAt = remember {
                val onFlow = currentKey == MenuKey.CoverFlow || currentKey is MenuKey.Flipped
                when {
                    onFlow -> flowCentre
                    nowPlayingAlbumId != null -> albumsInOrder.indexOfFirst { it.id == nowPlayingAlbumId }.coerceAtLeast(0)
                    else -> 0
                }
            }
            val sideCentre = remember { mutableIntStateOf(startAt) }
            // Covers decoded large enough for the whole screen, kept near where the row is.
            val bigCovers = viewModel.pocketBigCoverArt
            LaunchedEffect(sideCentre.intValue, albumsInOrder) {
                val c = sideCentre.intValue
                val from = (c - 7).coerceAtLeast(0)
                val to = (c + 7).coerceAtMost(albumsInOrder.lastIndex)
                for (i in (from..to).sortedBy { kotlin.math.abs(it - c) }) {
                    val album = albumsInOrder[i]
                    if (album.id in bigCovers) continue
                    bigCovers[album.id] = withContext(Dispatchers.IO) {
                        album.coverPath?.let { CaseArt.loadCover(it, maxPx = SIDEWAYS_COVER_PX)?.asImageBitmap() }
                    }
                }
                if (bigCovers.size > SIDEWAYS_CACHE_MAX) {
                    val keep = (from..to).map { albumsInOrder[it].id }.toSet()
                    bigCovers.keys.filter { it !in keep }.take(bigCovers.size - SIDEWAYS_CACHE_MAX).forEach { bigCovers.remove(it) }
                }
            }
            // Turned back upright, the player's own Cover Flow is left on the record the row was on.
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    val key = device.nav.value.current.key
                    if (key == MenuKey.CoverFlow) {
                        device.nav.value = device.nav.value.clamp(albumsInOrder.size)
                            .move(sideCentre.intValue - device.nav.value.current.selected, albumsInOrder.size)
                    }
                }
            }
            SidewaysCoverFlow(
                holding = if (holding.value == Holding.UPRIGHT) Holding.LEFT else holding.value,
                albums = albumsInOrder,
                startAt = startAt,
                // The large cover once it has been decoded, the player's own until then.
                art = { id -> bigCovers[id] ?: coverArt[id] },
                trackTitles = trackTitles,
                playingAlbumId = nowPlayingAlbumId,
                playingIndex = deck.currentTrackIndex,
                onCentre = { sideCentre.intValue = it },
                onOpen = { albumId ->
                    if (albumId !in trackTitles) {
                        scope.launch { trackTitles[albumId] = viewModel.trackTitles(albumId) }
                    }
                },
                onPlay = { albumId, index -> device.playFrom(albumId, index) },
                onTick = { device.tick() }
            )
        }

        // The face going dark as it is pinched away - composed only while it is (trap 14).
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
