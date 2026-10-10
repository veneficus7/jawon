package com.jawon.app.ui.pocketscene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.google.android.filament.MaterialInstance
import com.jawon.app.ui.showroom.PullOut
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallSceneViewModel
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode

/** A song asked for — see `PocketDevice.pendingPlay`. [at] is `SystemClock.uptimeMillis()`. */
internal data class PendingPlay(val albumId: String, val index: Int?, val at: Long)

/** How long a song asked for may stand in on Now Playing before the player must have answered. */
private const val PENDING_PLAY_MS = 3_000L

/** How far the camera backs away as the room is pinched away, as a share of where it stands. */
private const val PULL_BACK = 0.8f

/** The lens held square on — see `pose`. A value, so it is not reallocated per frame. */
private val LEVEL = Rotation(0f, 0f, 0f)


/**
 * The device's behaviour: what each touch of the wheel does, given where the menus are and
 * what is playing.
 *
 * A plain object rather than composable state, because SceneView is handed its touch and frame
 * callbacks once. The room writes the latest library and player state into it on every
 * composition, and what it changes — the menus, the bar, the volume shown — goes back out
 * through Compose state so the screen redraws.
 */
internal class PocketDevice(
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

    /**
     * **Hold** (0.7.3), the classic's switch, thrown by the phone itself: with the proximity sensor
     * covered — in a pocket, face down on a table — the wheel does nothing, so a thigh cannot skip a
     * track, and a lock shows in the title bar. Written by the room; uncovered, it lets go at once.
     */
    val hold = mutableStateOf(false)

    /**
     * How far the room's light dims the screen, 0..1 — the ambient light sensor's reading, eased
     * (0.7.3). A backlit LCD in a dark room is a torch; this takes the edge off it, as a panel
     * that answered the room would. Written by the room.
     */
    val roomDim = mutableFloatStateOf(0f)

    /** The phone's sideways lean, −1..1 ([leanOf]) — for Brick and for Cover Flow. Written by the room. */
    var lean = 0f

    /** Tilt & Turn, from the device's Settings: whether a lean browses Cover Flow. */
    var motionOn = true

    /** Cover Flow's drift with the lean, in covers — added to where the covers are, never to the selection. */
    val flowDrift = mutableFloatStateOf(0f)
    private var lastFlickAt = 0L

    /** Brick, and what its screen shows. The game is kept while you are away from it, paused. */
    private val brick = BrickGame()
    val brickFrame = mutableStateOf<BrickFrame?>(null)
    private var brickPaused = false
    private var brickWasOver = false
    var brickBest = 0

    /** Search: which letter of the strip the cursor is on, and whether the wheel is up in the results. */
    val searchLetter = androidx.compose.runtime.mutableIntStateOf(0)
    val searchInResults = mutableStateOf(false)

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

    /**
     * A song just asked for, shown on Now Playing until the player says it is on. The player
     * takes a few frames to answer, and without this Now Playing slid in reading "Nothing
     * playing" — or naming the song before — until it caught up; the classic showed the song you
     * chose at once. [PendingPlay.index] is null when an album resumes where it was left, which
     * only the view model knows.
     */
    val pendingPlay = mutableStateOf<PendingPlay?>(null)
    var onOpenSettings: () -> Unit = {}
    var onAddAlbum: () -> Unit = {}
    var onOpenRooms: () -> Unit = {}
    var onOpenGuide: () -> Unit = {}

    // **Pinching out of the room** - two fingers closing, into the showroom between formats
    // (docs/formats.md section 7, step 5b). Nothing else here uses two fingers, and the wheel never
    // sees them: a second finger cancels whatever the first was doing to it.
    val pullOut = PullOut()
    var showroomReachable = false
    var onOpenShowroom: () -> Unit = {}
    /** For the room's dimming: whether it is being pinched away, and how far. */
    val pulling = mutableStateOf(false)
    val pullDim = mutableFloatStateOf(0f)
    private var pinching = false
    private var pinchSpan = 0f

    /** The face's node — scaled to the display by the room (`PocketFace.fit`) — and the camera. */
    var deviceNode: Node? = null
    var cameraNode: CameraNode? = null
    /** Where the camera stands for life size — worked out by the room from the screen's height. */
    var cameraZ = 0.33f
    var tilt: TiltFilter? = null
    var indirectLight: com.google.android.filament.IndirectLight? = null

    // The world's turn against the phone's tilt, column-major — and the light is told its
    // transpose (see turnWorld). Written only when the tilt changes.
    private val worldTurn = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private val iblTurn = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var turnPitch = Float.NaN
    private var turnYaw = Float.NaN

    private val gesture = WheelGesture()
    private val rock = WheelRock()

    // The model's own moving parts: the ring and its print tip together; the button goes down.
    private var wheelParts: List<Node> = emptyList()
    private var buttonPart: Node? = null

    // The parts a colour is put on (PocketFinish), found by name when the model loads.
    private var frontMaterial: MaterialInstance? = null
    private var wheelMaterial: MaterialInstance? = null
    private var buttonMaterial: MaterialInstance? = null
    private var printMaterial: MaterialInstance? = null
    private var finishShown: PocketFinish? = null

    /** Which one you bought: written by the room from the preference, put on the model when it changes. */
    var finish: PocketFinish = PocketFinish.SILVER
        set(value) {
            field = value
            showFinish()
        }

    private fun showFinish() {
        if (finishShown == finish || frontMaterial == null) return
        finish.applyTo(frontMaterial, wheelMaterial, buttonMaterial, printMaterial)
        finishShown = finish
        sceneChanged = true
    }
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
        if (pinch(event)) return true
        val now = event.eventTime
        if (hold.value) {
            // Held: the touch is taken and nothing happens, as the classic's wheel did under its
            // switch. Anything already under the thumb is let go.
            if (gesture.isDown) handle(gesture.cancel(), now)
            return true
        }
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

    /**
     * Two fingers: the pinch out of the room. True while it has the touch - from the second finger
     * landing until the last one lifts, so the finger left behind does not start turning the wheel.
     */
    private fun pinch(event: MotionEvent): Boolean {
        if (!showroomReachable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount != 2) return pinching
                if (gesture.isDown) handle(gesture.cancel(), event.eventTime)
                pinching = true
                pinchSpan = span(event)
                pullOut.begin()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pinching) return false
                if (event.pointerCount >= 2 && pinchSpan > 0f) {
                    val now = span(event)
                    if (now > 0f && pullOut.pinch(now / pinchSpan)) {
                        haptics.detent()
                        onOpenShowroom()
                    }
                    pinchSpan = now
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!pinching) return false
                // Down to one finger: the pinch is over, but the touch is still the pinch's.
                if (event.pointerCount <= 2) {
                    pullOut.end()
                    pinchSpan = 0f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pinching) return false
                pinching = false
                pinchSpan = 0f
                pullOut.end()
                return true
            }
        }
        return false
    }

    private fun span(event: MotionEvent): Float =
        kotlin.math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    /**
     * Finds the parts that move, and the parts a finish colours, by the names `build_pocket_face()`
     * gives them — the device's own names. Once.
     */
    fun bindParts(nodes: List<Node>) {
        if (wheelParts.isNotEmpty()) return
        wheelParts = nodes.filter { it.name == "pocket_wheel" || it.name == "pocket_print" }
        buttonPart = nodes.firstOrNull { it.name == "pocket_button" }
        fun material(name: String) = (nodes.firstOrNull { it.name == name } as? RenderableNode)?.materialInstance
        frontMaterial = material("pocket_front")
        wheelMaterial = material("pocket_wheel")
        buttonMaterial = material("pocket_button")
        printMaterial = material("pocket_print")
        showFinish()
        sceneChanged = true
    }

    /** A thumb on the wheel, the wheel still moving from one, a pinch, or a ball in play: drawn every vsync. */
    val interacting: Boolean
        get() = gesture.isDown || rock.isMoving || pullOut.isMoving || brickRunning

    private val brickRunning: Boolean
        get() = nav.value.current.key == MenuKey.Brick && awake.value && !brickPaused &&
            brick.phase == BrickGame.Phase.PLAYING

    // ---- whether there is anything new to draw ----------------------------------------------

    /** Something this device wrote into the scene — a part posed, a finish, the camera — since the room last drew. */
    private var sceneChanged = true
    private var drawnPitch = Float.NaN
    private var drawnYaw = Float.NaN
    private var posedZ = Float.NaN

    /**
     * Whether the face would look any different from the frame the room drew last, as far as this
     * device knows: something it wrote into the scene, or the phone tilted far enough to move the
     * reflections. The screen's own picture is the room's to check (`LcdPanel.framesDelivered`).
     *
     * The tilt counts only past [TURN_EPS_RAD], measured from what was *drawn*, so a slow drift
     * still arrives once it adds up, and a phone lying on a table — whose sensor still murmurs —
     * stops asking for frames.
     */
    val changedSinceDrawn: Boolean
        get() = sceneChanged || turnedSinceDrawn()

    private fun turnedSinceDrawn(): Boolean {
        if (turnPitch.isNaN()) return false
        if (drawnPitch.isNaN()) return true
        return kotlin.math.abs(turnPitch - drawnPitch) > TURN_EPS_RAD ||
            kotlin.math.abs(turnYaw - drawnYaw) > TURN_EPS_RAD
    }

    /** The room drew a frame: everything up to now is on the glass. */
    fun drawn() {
        sceneChanged = false
        drawnPitch = turnPitch
        drawnYaw = turnYaw
    }

    fun onFrame(now: Long) {
        if (lastInputAt == 0L) lastInputAt = now
        // A game in play is a hand on the device, wheel or no wheel: the backlight stays up.
        if (brickRunning) lastInputAt = now
        // A song asked for that never arrived — a file gone, say — stops standing in for it.
        pendingPlay.value?.let { if (now - it.at > PENDING_PLAY_MS) pendingPlay.value = null }
        handle(gesture.poll(now), now)

        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now

        pullOut.step(dt)
        pullDim.floatValue = pullOut.progress
        if (pulling.value != pullOut.engaged) pulling.value = pullOut.engaged
        pose()
        // Written only while something moves, and once more to leave it exactly at rest.
        if (rock.isMoving || rockWasMoving) {
            rock.step(dt)
            poseParts()
        }
        rockWasMoving = rock.isMoving

        // The room turned against the phone's tilt: its reflections slide across the face.
        turnWorld()

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

        stepBrick(dt)
        browseByLean(now)

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
     * Brick's clock: steered by the lean as well as the wheel, stepped only while its page is up
     * and it is not paused, and its frame handed to the screen. A finished game's score goes to
     * the view model, which keeps it only if it is the best.
     */
    private fun stepBrick(dt: Float) {
        val onBrick = nav.value.current.key == MenuKey.Brick && awake.value
        if (!onBrick) {
            // Away from the game it waits, and a ball in play waits for the centre on the way back.
            if (brick.phase == BrickGame.Phase.PLAYING) brickPaused = true
            if (brickFrame.value != null) brickFrame.value = null
            return
        }
        val speed = BrickGame.leanSpeed(lean)
        if (!brickPaused && brick.phase == BrickGame.Phase.PLAYING) {
            if (speed != 0f) brick.movePaddle(speed * dt)
            brick.step(dt)
        } else if (brick.phase != BrickGame.Phase.PLAYING) {
            // Waiting to serve, the paddle still answers a lean, with the ball on it.
            if (speed != 0f) brick.movePaddle(speed * dt)
        }
        val over = brick.phase == BrickGame.Phase.OVER
        if (over && !brickWasOver) {
            viewModel.onBrickScore(brick.score)
            if (brick.score > brickBest) brickBest = brick.score
            press()
        }
        brickWasOver = over
        val f = brick.frame()
        brickFrame.value = BrickFrame(
            paddleX = f.paddleX, ballX = f.ballX, ballY = f.ballY, bricks = f.bricks,
            score = f.score, lives = f.lives, level = f.level, phase = f.phase,
            best = maxOf(brickBest, f.score), paused = brickPaused
        )
    }

    /**
     * **Leaning through Cover Flow** (0.7.3): a slight lean drifts the covers the way it leans, and
     * a firm one — past [FLICK_LEAN] — moves to the next cover, again every [FLICK_REPEAT_MS] for
     * as long as it is held, like a key held down. The wheel stays the precise control; with a
     * thumb on it the lean does nothing.
     */
    private fun browseByLean(now: Long) {
        val inFlow = nav.value.current.key == MenuKey.CoverFlow
        val active = inFlow && motionOn && awake.value && !gesture.isDown && !hold.value
        // Quantised, so a sensor's murmur is not a new picture for the screen every reading.
        val drift = if (active) kotlin.math.round(lean * DRIFT_COVERS * 100f) / 100f else 0f
        if (drift != flowDrift.floatValue) flowDrift.floatValue = drift
        if (!active || kotlin.math.abs(lean) < FLICK_LEAN || now - lastFlickAt < FLICK_REPEAT_MS) return
        val count = library.albums.size
        val moved = nav.value.clamp(count).move(if (lean > 0f) 1 else -1, count)
        lastFlickAt = now
        if (moved != nav.value) {
            nav.value = moved
            lastInputAt = now
            backlight.value = true
            step(now)
        }
    }

    /**
     * A point on the glass, onto the wheel: into [wheelPoint], in **the classic's millimetres**.
     * Projects the face's wheel — its centre and a radius along each axis — through the face's
     * node, scale and all; `unproject` measures against `ClickWheel.WHEEL_R_MM`, so the face's
     * 25.5mm radius reads as the classic's 19.5mm and every rule of the gesture (the button, the
     * rim, the dead zone, the tap's slop) holds on the bigger wheel in proportion.
     */
    private fun toWheel(x: Float, y: Float): Boolean {
        val node = deviceNode ?: return false
        val camera = cameraNode ?: return false
        val m = node.worldTransform
        fun screen(lx: Float, ly: Float): Vector3 {
            val w = m * Float4(lx, ly, PocketFace.WHEEL_FACE_Z_M, 1f)
            return camera.worldToScreenPoint(Vector3(w.x, w.y, w.z))
        }
        val r = PocketFace.WHEEL_R_M
        val c = screen(0f, PocketFace.WHEEL_Y_M)
        val ex = screen(r, PocketFace.WHEEL_Y_M)
        val ey = screen(0f, PocketFace.WHEEL_Y_M + r)
        return ClickWheel.unproject(c.x, c.y, ex.x, ex.y, ey.x, ey.y, x, y, wheelPoint)
    }

    /**
     * The camera, square on to the face at life size — written every frame, which trap 8 needs:
     * SceneView's camera re-aims itself at the origin otherwise. Pinched, it backs away as the
     * room darkens, which is the one move it makes.
     */
    private fun pose() {
        val camera = cameraNode ?: return
        val z = cameraZ * (1f + PULL_BACK * pullOut.progress)
        if (z != posedZ) {
            posedZ = z
            sceneChanged = true
        }
        camera.position = Position(0f, 0f, z)
        camera.rotation = LEVEL
    }

    /**
     * The inverse of the phone's tilt, applied to the room: `Ry(-yaw) · Rx(-pitch)`. Turning the
     * room the other way is the same, to the eye, as the face turning inside a still one — which is
     * what the phone in the hand is doing — so its reflections slide across the face and the glass.
     *
     * **Filament turns where the environment is looked up, not the picture**: it folds the rotation
     * into the whole scene's frame, so to see the room turned by A the light is told Aᵀ. Measured on
     * the carriage, not assumed: told A, the face's gradient ran the wrong way.
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
        for (r in 0..2) for (c in 0..2) iblTurn[c * 3 + r] = worldTurn[r * 3 + c]
        indirectLight?.setRotation(iblTurn)
    }

    /**
     * The ring about its centre, tipped and sunk so it pivots on its far edge ([WheelRock]); the
     * print is on the ring and goes with it; the button goes straight down.
     */
    private fun poseParts() {
        // Spelled out rather than `centre + …`: kotlin-math's vector operators are inline and
        // built for a newer JVM target than this module, so they will not compile here.
        // About the face's wheel, in the face's own frame — the parts' parent's.
        val y = PocketFace.WHEEL_Y_M
        val z = PocketFace.WHEEL_FACE_Z_M
        val ring = translation(Float3(0f, y, z - rock.sinkMm / 1000f)) *
            rotation(X_AXIS, rock.tiltXDeg) *
            rotation(Y_AXIS, rock.tiltYDeg) *
            translation(Float3(0f, -y, -z))
        for (part in wheelParts) part.transform = ring
        buttonPart?.transform = translation(Float3(0f, 0f, -rock.buttonMm / 1000f))
        sceneChanged = true
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
        // On Search's letters the wheel moves along the strip; up in the results it is a list.
        if (nav.value.current.key is MenuKey.Search && !searchInResults.value) {
            val next = (searchLetter.intValue + turn.steps).coerceIn(0, PocketSearch.LETTERS.lastIndex)
            if (next != searchLetter.intValue) {
                searchLetter.intValue = next
                step(now)
            }
            return
        }
        if (nav.value.current.key == MenuKey.Brick) {
            brick.movePaddle(turn.steps * BrickGame.PADDLE_PER_STEP)
            step(now)
            return
        }
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
        // Search's own buttons: the centre types, ◀◀ takes a letter back, ▶▶ goes up into what was
        // found, and MENU from there comes back down to the letters.
        if (key is MenuKey.Search) {
            when {
                button == ClickWheel.Button.MENU && searchInResults.value -> {
                    searchInResults.value = false
                    return
                }
                button == ClickWheel.Button.SELECT && !searchInResults.value -> {
                    typeLetter(key, PocketSearch.LETTERS[searchLetter.intValue])
                    return
                }
                button == ClickWheel.Button.PREVIOUS && !searchInResults.value -> {
                    typeLetter(key, PocketSearch.DELETE)
                    return
                }
                button == ClickWheel.Button.NEXT && !searchInResults.value -> {
                    if (rows().isNotEmpty()) {
                        searchInResults.value = true
                        nav.value = nav.value.clamp(rows().size)
                    }
                    return
                }
                else -> Unit
            }
        }
        when (button) {
            ClickWheel.Button.MENU -> back(silent = true)
            ClickWheel.Button.SELECT -> {
                // On a slider the centre is done, as MENU is.
                if (key is MenuKey.Adjust) {
                    nav.value = nav.value.back()
                    return
                }
                // In Brick the centre serves, resumes, or starts again.
                if (key == MenuKey.Brick) {
                    if (brickPaused) brickPaused = false else brick.press()
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
                    pendingPlay.value = PendingPlay(action.albumId, action.index, SystemClock.uptimeMillis())
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
                        pendingPlay.value = PendingPlay(albumId, null, SystemClock.uptimeMillis())
                        showNowPlaying()
                        return
                    }
                }
                else -> Unit
            }
        }
        if (hasNowPlaying) viewModel.setPlaying(true)
    }

    private fun typeLetter(key: MenuKey.Search, letter: String) {
        val query = PocketSearch.type(key.query, letter)
        if (query != key.query) nav.value = nav.value.retop(MenuKey.Search(query))
    }

    private fun act(action: RowAction) {
        when (action) {
            is RowAction.Open -> {
                // Into Search on the letters, whatever was left the last time.
                if (action.key is MenuKey.Search) searchInResults.value = false
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
                pendingPlay.value = PendingPlay(action.albumId, action.index, SystemClock.uptimeMillis())
                showNowPlaying()
            }
            RowAction.AddAlbums -> onAddAlbum()
            RowAction.OpenRooms -> onOpenRooms()
            RowAction.OpenSettings -> onOpenSettings()
            RowAction.OpenGuide -> onOpenGuide()
            is RowAction.Choose -> {
                choose(action.setting, action.option)
                // The classic went back to Settings once a choice was made.
                nav.value = nav.value.back()
            }
            RowAction.Nothing -> Unit
            RowAction.FlatSound -> flatten()
            RowAction.ShuffleAlbums -> {
                val chosen = viewModel.shuffleAlbum(library.albums.map { it.id }) ?: return
                pendingPlay.value = PendingPlay(chosen, 0, SystemClock.uptimeMillis())
                showNowPlaying()
            }
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
            PocketSetting.FINISH -> PocketFinish.entries.getOrNull(option)
                ?.let { viewModel.onPocketFinishChanged(it.name) }
            PocketSetting.MOTION -> viewModel.onPocketMotionChanged(option == 0)
            PocketSetting.SLEEP -> viewModel.onSleepModeChange(PocketSettings.sleepMode(option))
            PocketSetting.REVERB -> {
                if (option > 0) viewModel.engage(com.jawon.app.playback.RackChannel.SPACE)
                viewModel.onReverbChange(option)
            }
        }
    }

    /** A track chosen in sideways Cover Flow: played, and Now Playing waiting when the phone comes upright. */
    fun playFrom(albumId: String, index: Int) {
        viewModel.playAlbumFrom(albumId, index)
        pendingPlay.value = PendingPlay(albumId, index, SystemClock.uptimeMillis())
        showNowPlaying()
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

    /**
     * The wheel's buttons and its turn, for TalkBack's actions (0.7.5): the same behaviour a thumb
     * gets, with the same click, so a player nobody can see is still a player.
     */
    fun accessibilityPress(button: ClickWheel.Button) {
        val now = SystemClock.uptimeMillis()
        handle(WheelGesture.Event.Press(button), now)
    }

    fun accessibilityTurn(steps: Int) {
        val now = SystemClock.uptimeMillis()
        handle(WheelGesture.Event.Turn(steps, 0f), now)
    }

    /** A cover passing in sideways Cover Flow: the wheel's own click and tick. */
    fun tick() = step(SystemClock.uptimeMillis())

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

        /**
         * A tilt worth drawing, in radians: 0.06 degrees, which turns a reflection 0.11 — far under
         * what the studio's soft lights could show, and over a still phone's sensor noise.
         */
        const val TURN_EPS_RAD = 0.001f
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

        /** How far a full lean drifts Cover Flow, in covers. */
        const val DRIFT_COVERS = 0.32f
        /** A lean past this — about 20° — flicks to the next cover. */
        const val FLICK_LEAN = 0.6f
        const val FLICK_REPEAT_MS = 480L
    }
}
