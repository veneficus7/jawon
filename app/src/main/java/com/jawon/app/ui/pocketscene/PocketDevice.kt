package com.jawon.app.ui.pocketscene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.google.android.filament.MaterialInstance
import com.jawon.app.ui.wallscene.RoomHaptics
import com.jawon.app.ui.wallscene.WallSceneViewModel
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode
import kotlin.math.max

/** A song asked for — see `PocketDevice.pendingPlay`. [at] is `SystemClock.uptimeMillis()`. */
internal data class PendingPlay(val albumId: String, val index: Int?, val at: Long)

/** How long a song asked for may stand in on Now Playing before the player must have answered. */
private const val PENDING_PLAY_MS = 3_000L

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

    var deviceNode: Node? = null
    var cameraNode: CameraNode? = null
    var windowMaterial: MaterialInstance? = null
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

    /**
     * Finds the parts that move, by the names `build_pocket()` gives them, and puts the steel's
     * own material on the shell. Once.
     */
    fun bindParts(nodes: List<Node>, steel: MaterialInstance) {
        if (wheelParts.isNotEmpty()) return
        wheelParts = nodes.filter { it.name == "pocket_wheel" || it.name == "pocket_print" }
        buttonPart = nodes.firstOrNull { it.name == "pocket_button" }
        // The steel wears its own material — see `materials/polished_steel.mat` for why.
        (nodes.firstOrNull { it.name == "pocket_back" } as? RenderableNode)?.materialInstance = steel
    }

    /** A thumb on the wheel, or the wheel still moving from one: the room draws every vsync. */
    val interacting: Boolean
        get() = gesture.isDown || rock.isMoving

    fun onFrame(now: Long) {
        if (lastInputAt == 0L) lastInputAt = now
        // A song asked for that never arrived — a file gone, say — stops standing in for it.
        pendingPlay.value?.let { if (now - it.at > PENDING_PLAY_MS) pendingPlay.value = null }
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
                pendingPlay.value = PendingPlay(action.albumId, action.index, SystemClock.uptimeMillis())
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
