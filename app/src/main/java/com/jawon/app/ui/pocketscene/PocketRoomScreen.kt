package com.jawon.app.ui.pocketscene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.LightManager
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallLayout
import com.jawon.app.ui.wallscene.WallSceneViewModel
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
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

    val deviceModel = rememberModelInstance(modelLoader, POCKET_MODEL_ASSET)
    val ydpi = remember(context) { max(context.resources.displayMetrics.ydpi, 1f) }
    // What renders Compose into the scene. See `docs/realism.md` §1.4: a SurfaceTexture feeding
    // a Filament Stream — the path video takes — so the screen is live UI, not a baked image.
    val viewNodeManager = rememberViewNodeManager()

    // ---- the library and the player, as the device sees them ------------------------------
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val nowPlayingAlbumId by viewModel.nowPlayingAlbumId.collectAsStateWithLifecycle()

    val library = remember(layout) { pocketLibraryOf(layout) }
    val trackTitles = remember { mutableStateMapOf<String, List<String>>() }
    val navState = rememberSaveable(stateSaver = PocketNav.Saver) { mutableStateOf(PocketNav()) }
    val device = remember { PocketDevice(context, viewModel, navState) }

    DisposableEffect(device) { onDispose { device.close() } }

    // What the handlers read. Written here every composition, so a touch always acts on the
    // library, the tracklists and the player as they are *now* — the handlers themselves are
    // built once and handed to SceneView.
    device.library = library
    device.trackTitles = trackTitles
    device.deck = deck
    device.positionMs = positionMs
    device.hasNowPlaying = nowPlayingAlbumId != null && deck.tracks.isNotEmpty()
    device.onOpenSettings = onOpenSettings
    device.onAddAlbum = onAddAlbum

    val nav = device.nav.value
    val currentKey = nav.current.key

    // A tracklist is not part of the wall's layout: read one when its album is opened.
    LaunchedEffect(currentKey) {
        if (currentKey is MenuKey.Album && currentKey.albumId !in trackTitles) {
            trackTitles[currentKey.albumId] = viewModel.trackTitles(currentKey.albumId)
        }
    }

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
        trackTitles = trackTitles,
        hasNowPlaying = device.hasNowPlaying,
        deck = deck,
        positionMs = positionMs,
        albumTitle = nowPlayingAlbum?.title.orEmpty(),
        albumArtist = nowPlayingAlbum?.artist.orEmpty(),
        cover = cover,
        bar = device.bar.value,
        volume = device.volume.value,
        scrubMs = device.scrubMs.value
    )
    val lcd = remember { mutableStateOf(screen) }
    SideEffect { lcd.value = screen }

    BackHandler(enabled = nav.stack.size > 1) { device.back() }

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
                device.onFrame(SystemClock.uptimeMillis())
            }
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
                Node(
                    rotation = Rotation(x = -6f, y = 15f, z = 0f),
                    apply = { device.deviceNode = this }
                ) {
                    ModelNode(
                        instance,
                        apply = {
                            isTouchable = false
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )
                    // The screen, as a child of the device so it turns with it. Unlit because
                    // an LCD is its own light: nothing in the carriage should be able to
                    // darken what the backlight is putting out.
                    ViewNode(
                        windowManager = viewNodeManager,
                        unlit = true,
                        position = Position(0f, SCREEN_CENTRE_Y, SCREEN_Z),
                        apply = {
                            pxPerUnits = LCD_PX_W / SCREEN_W_M
                            isTouchable = false
                            device.lcdNode = this
                        }
                    ) {
                        PocketLcd(
                            screen = lcd.value,
                            playing = device.playingNow.value,
                            awake = device.awake.value
                        )
                        // **A ViewNode draws its texture once unless told otherwise** — trap 33.
                        // It copies its children into the texture only from its own container's
                        // `dispatchDraw`, and under hardware acceleration a child's invalidate
                        // re-records the child and never reaches that container. So the screen
                        // recomposed on every turn of the wheel and the texture showed the first
                        // frame forever. Invalidating the container after each recomposition is
                        // what puts the new frame on the glass — once per change, never per frame.
                        SideEffect { device.lcdNode?.layout?.invalidate() }
                    }
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
    trackTitles: Map<String, List<String>>,
    hasNowPlaying: Boolean,
    deck: com.jawon.app.ui.wallscene.DeckState,
    positionMs: Long,
    albumTitle: String,
    albumArtist: String,
    cover: ImageBitmap?,
    bar: LcdScreen.Bar,
    volume: Float,
    scrubMs: Long
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
            scrubMs = scrubMs
        )
    }
    val rows = PocketMenus.rows(key, library, trackTitles, hasNowPlaying)
    val level = nav.clamp(rows.size).current
    val end = minOf(level.top + PocketMenus.VISIBLE_ROWS, rows.size)
    val window = if (level.top < end) rows.subList(level.top, end) else emptyList()
    return LcdScreen.Menu(
        title = PocketMenus.title(key, library),
        rows = window.map { LcdRow(it.label, it.leadsOn) },
        selected = level.selected - level.top,
        top = level.top,
        total = rows.size,
        emptyText = when (key) {
            MenuKey.Albums, MenuKey.Artists -> "No albums yet"
            MenuKey.Shelves -> "No shelves yet"
            is MenuKey.Album -> if (key.albumId in trackTitles) "No tracks" else ""
            else -> ""
        }
    )
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

    var library = PocketLibrary()
    var trackTitles: Map<String, List<String>> = emptyMap()
    var deck = com.jawon.app.ui.wallscene.DeckState()
    var positionMs = 0L
    var hasNowPlaying = false
    var onOpenSettings: () -> Unit = {}
    var onAddAlbum: () -> Unit = {}

    var deviceNode: Node? = null
    var cameraNode: CameraNode? = null
    var lcdNode: ViewNode? = null

    private val gesture = WheelGesture()
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

    fun onFrame(now: Long) {
        handle(gesture.poll(now), now)

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

    // ---- behaviour --------------------------------------------------------------------------

    private fun handle(events: List<WheelGesture.Event>, now: Long) {
        for (e in events) handle(e, now)
    }

    private fun handle(event: WheelGesture.Event, now: Long) {
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
        PocketMenus.rows(nav.value.current.key, library, trackTitles, hasNowPlaying)

    private fun turn(turn: WheelGesture.Event.Turn, now: Long) {
        if (nav.value.current.key == MenuKey.NowPlaying && deck.tracks.isNotEmpty()) {
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
                if (onNowPlaying) {
                    // One press brings up the scrubber, another puts it away.
                    barTouchedAt = now
                    if (bar.value == LcdScreen.Bar.SCRUB) {
                        bar.value = LcdScreen.Bar.PROGRESS
                    } else {
                        scrubMs.value = positionMs
                        bar.value = LcdScreen.Bar.SCRUB
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
                    val target = action.key
                    if (target is MenuKey.Album) {
                        viewModel.playAlbum(target.albumId)
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
                nav.value = nav.value.open(action.key)
            }
            is RowAction.PlayTrack -> {
                viewModel.playAlbumFrom(action.albumId, action.index)
                showNowPlaying()
            }
            RowAction.AddAlbums -> onAddAlbum()
            RowAction.OpenSettings -> onOpenSettings()
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
        if (nav.value.current.key == MenuKey.NowPlaying) bar.value = LcdScreen.Bar.PROGRESS
        nav.value = nav.value.back()
    }

    // ---- feedback ---------------------------------------------------------------------------

    /** One step of the wheel: the click and the tick, together and never faster than a buzz. */
    private fun step(now: Long) {
        if (now - lastClickAt < CLICK_MIN_INTERVAL_MS) return
        lastClickAt = now
        clicker.click()
        haptics.tick()
    }

    /** A button: the same click, and the heavier haptic a switch closing deserves. */
    private fun press() {
        clicker.click()
        haptics.detent()
    }

    init {
        refreshVolume()
    }

    private companion object {
        const val CLICK_MIN_INTERVAL_MS = 18L
        const val BAR_IDLE_MS = 3_500L
        const val SEEK_TICK_MS = 200L
        const val SEEK_FAST_AFTER_MS = 2_500L
        const val SEEK_RATE_SLOW = 6f
        const val SEEK_RATE_FAST = 16f
        const val SCRUB_SEEK_MS = 150L
        const val SCRUB_STEPS_PER_TRACK = 96L
    }
}
