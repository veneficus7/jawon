package com.jawon.app.ui.tapescene

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jawon.app.playback.ScratchAudio
import com.jawon.app.playback.TapeState
import com.jawon.app.ui.vinylscene.Sides
import com.jawon.app.ui.wallscene.DeckState
import com.jawon.app.ui.wallscene.RoomHaptics
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min

/**
 * What moves in the cassette room, and what a finger does at the deck: the frame loop's side of
 * the room, as `VinylStage` is the vinyl room's. A plain object the scene writes its nodes into —
 * nothing here recomposes anything but the three or four pieces of Compose state that change a
 * few times a minute ([deckward], [shownTape], [awaitingSide], [modeShown]).
 *
 * Two places, and the camera goes between them: **the racks** on the wall, where the library is
 * (`TapeRackScene`), and **the deck**, face on. A tape touched in a rack is taken and put in
 * (`LoadTape`); the deck then does what its keys say (`TapeTransport`), and everything that moves
 * on it is drawn from that every frame.
 */
internal class TapeStage(
    private val scratch: ScratchAudio,
    private val haptics: RoomHaptics,
    private val rack: TapeRackScene,
    private val sound: DeckSound,
    private val tapeState: TapeState,
    startAtDeck: Boolean
) {
    /** The player, the long way round through the view model: for when there is no player to tell straight. */
    var onPlay: (Boolean) -> Unit = {}
    var onSeek: (Int, Long) -> Unit = { _, _ -> }

    /** The deck's REV, which is the setting too. */
    var onAutoReverse: (Boolean) -> Unit = {}

    val transport = TapeTransport(object : TapePlayer {
        override fun isPlaying(): Boolean = scratch.playerIsPlaying()
        override fun track(): Int = scratch.playerTrack()
        override fun positionMs(): Long? = scratch.playerPositionMs()
        override fun endedTrack(): Boolean = scratch.endedTrack()
        override fun play() {
            Log.i(TAG, "player: play")
            if (!scratch.setPlayerPlaying(true)) onPlay(true)
        }
        override fun pause() {
            Log.i(TAG, "player: pause")
            if (!scratch.setPlayerPlaying(false)) onPlay(false)
        }
        override fun seek(trackIndex: Int, positionMs: Long) {
            Log.i(TAG, "player: to track ${trackIndex + 1} at ${positionMs}ms")
            if (!scratch.seekPlayer(trackIndex, positionMs)) onSeek(trackIndex, positionMs)
        }
        override fun stopAtEndOfTrack(stop: Boolean) = scratch.stopAtEndOfTrack(stop)
    })

    init {
        transport.onAdoptSide = { side -> Log.i(TAG, "the player is on side ${Sides.letter(side)}: the deck follows it") }
    }

    // ---- Nodes ---------------------------------------------------------------------------------------

    var caseNode: Node? = null
    var lidNode: Node? = null
    var cassetteNode: Node? = null
    var hubLeft: Node? = null
    var hubRight: Node? = null
    var packLeft: Node? = null
    var packRight: Node? = null
    var racksLamp: Node? = null

    private val keyParts = Array(TapeKey.entries.size) { ArrayList<Node>() }
    private val doorParts = ArrayList<Node>()
    private val needles = arrayOfNulls<Node>(2)
    private val drums = Array(3) { ArrayList<Node>() }
    private var lampForward: Node? = null
    private var lampReverse: Node? = null
    private var deckBound: ModelNode? = null

    /** The deck's model is built: find its moving parts by the names the model script gave them. */
    fun bindDeck(model: ModelNode) {
        if (deckBound === model) return
        deckBound = model
        keyParts.forEach { it.clear() }
        doorParts.clear()
        drums.forEach { it.clear() }
        val keyNames = mapOf(
            "eject" to TapeKey.EJECT, "rew" to TapeKey.REW, "play" to TapeKey.PLAY,
            "ff" to TapeKey.FF, "stop" to TapeKey.STOP, "pause" to TapeKey.PAUSE, "rev" to TapeKey.REV
        )
        for (part in model.nodes) {
            val name = part.name ?: continue
            when {
                name.startsWith("key_") -> {
                    val kind = name.removePrefix("key_").removeSuffix("_ink")
                    keyNames[kind]?.let { keyParts[it.ordinal] += part }
                }
                name == "door" || name == "door_glass" || name == "door_clips" -> doorParts += part
                name == "needle_l" -> needles[0] = part
                name == "needle_r" -> needles[1] = part
                name.startsWith("drum_") -> name.removePrefix("drum_").take(1).toIntOrNull()?.let { if (it in 0..2) drums[it] += part }
                name == "lamp_fwd_on" -> lampForward = part
                name == "lamp_rev_on" -> lampReverse = part
            }
        }
        Log.i(TAG, "deck bound: ${keyParts.sumOf { it.size }} key parts, ${doorParts.size} door parts, ${drums.sumOf { it.size }} drum parts")
    }

    // ---- Compose's few pieces ---------------------------------------------------------------------

    var deckward by mutableStateOf(startAtDeck)
        private set

    /** Which tape of a set is in, for printing its labels and its J-card. */
    var shownTape by mutableStateOf(0)
        private set

    /** The side the deck is waiting to be turned over to, −1 when it is not: the J-card says so. */
    var awaitingSide by mutableStateOf(-1)
        private set

    var modeShown by mutableStateOf(TapeTransport.Mode.EMPTY)
        private set

    /** What is printed, and for which album and tape: set by the room when it has printed it. */
    var printedAlbum: String? = null
    var printedTape = -1

    /** Something was printed or put somewhere: draw. */
    var changed = true

    // ---- A tape on its way in --------------------------------------------------------------------

    private class Run(val albumId: String, val fromX: Float, val fromY: Float) {
        var t = 0f
        var rate = 1f
        var waited = 0f
        var doorAsked = false
        var seated = false
    }

    private var run: Run? = null
    private val load = LoadTape.State()

    /** A tape touched in a rack: out of its slot, and everything `LoadTape` says, from now. */
    fun take(albumId: String, x: Float, slotY: Float) {
        run = Run(albumId, x, slotY)
        rack.out = albumId
        transport.empty()
        zoomTarget = 0f
        Log.i(TAG, "taking $albumId")
    }

    /** [albumId] is the tape in the deck, with nothing on its way. */
    fun isIn(albumId: String): Boolean = run == null && transport.mode != TapeTransport.Mode.EMPTY && deckAlbum == albumId

    /** Up to the deck. */
    fun toDeck() {
        if (transport.mode != TapeTransport.Mode.EMPTY || run != null) placeTarget = 1f
    }

    fun toRacks() {
        zoomTarget = 0f
        placeTarget = 0f
    }

    /** Nobody is looking: a tape on its way in is in, and the player is not asked to stop at the end of a side. */
    fun unseen() {
        transport.seen = false
        scratch.stopAtEndOfTrack(false)
        sound.cueAt(false, -1, 0.0, 0.0)
        val going = run ?: return
        run = null
        if (deckAlbum == going.albumId && lastSet.sides.isNotEmpty()) {
            if (!transport.loading) transport.beginLoad()
            transport.seated(lastSet, scratch.playerTrack().coerceAtLeast(0), scratch.playerPositionMs() ?: 0L)
            transport.finishLoad()
        } else {
            onPlay(true)
        }
        placeTarget = 1f
    }

    // ---- Where the camera is ---------------------------------------------------------------------------

    private var place = if (startAtDeck) 1f else 0f
    private var placeTarget = place
        set(value) {
            field = value
            if (deckward != value > 0.5f) deckward = value > 0.5f
        }
    private var placeVelocity = 0f

    val atDeck: Boolean get() = placeTarget > 0.5f

    var zoom = 0f
    var zoomTarget = 0f

    var shot = TapeCamera.deck(0f, 2f)
        private set

    var widthPx = 1f
    var heightPx = 1f
    var touchSlopPx = 24f
    var publishedDivisor = 0

    /** A hand is on it, or anything is on its way somewhere. */
    var busy = false
        private set

    /** The reels are turning, or a needle is moving: drawn at sixty. */
    var turning = false
        private set

    // ---- Every frame ----------------------------------------------------------------------------------

    private var lastFrameAt = 0L
    private var deckAlbum: String? = null
    private var lastSet = TapeSet(emptyList())
    private var leftAngle = 0f
    private var rightAngle = 0f
    private val needleAngle = FloatArray(2)
    private val needleVelocity = FloatArray(2)
    private val drumValues = DoubleArray(3)
    private val seat = Pose()
    private val cassette = Pose()
    private var frames = 0

    fun onFrame(now: Long, deck: DeckState, set: TapeSet, autoReverse: Boolean, seen: Boolean) {
        val dt = if (lastFrameAt == 0L) 0f else ((now - lastFrameAt) / 1000f).coerceAtMost(0.1f)
        lastFrameAt = now
        val aspect = heightPx / widthPx
        deckAlbum = deck.tracks.firstOrNull()?.albumId
        lastSet = set
        transport.seen = seen
        if (frames == 0 || transport.autoReverse != autoReverse && !reverseToggled) transport.autoReverse = autoReverse
        reverseToggled = false
        sound.setSources(deck.tracks.map { DeckSound.Source(Uri.parse(it.fileUri), it.durationMs) })

        // ---- A tape on its way in --------------------------------------------------------------------
        val going = run
        if (going != null) {
            val art = printedAlbum == going.albumId
            val ready = art && deckAlbum == going.albumId && set.sides.isNotEmpty() && printedTape >= 0
            when {
                // Nothing leaves the rack before it is printed: a box with somebody else's cover on it would be a lie.
                going.t < 0.02f && !art -> going.waited += dt
                // The door is open and waiting; the tape is not in the player yet.
                going.t >= LoadTape.SEATED_AT && !ready -> going.waited += dt
                else -> going.t = min(LoadTape.TOTAL_S, going.t + dt * going.rate)
            }
            if (going.waited > GIVE_UP_S) {
                Log.w(TAG, "gave up waiting for ${going.albumId}")
                run = null
            }
            if (going.t >= LoadTape.CAMERA_AT && placeTarget < 1f) placeTarget = 1f
            if (!going.doorAsked && going.t >= LoadTape.DOOR_AT) {
                going.doorAsked = true
                transport.beginLoad()
            }
            if (!going.seated && going.t >= LoadTape.SEATED_AT && ready) {
                going.seated = true
                val track = scratch.playerTrack().takeIf { it in deck.tracks.indices } ?: deck.currentTrackIndex.coerceAtLeast(0)
                transport.seated(set, track, scratch.playerPositionMs() ?: 0L)
                adoptedAlbum = going.albumId
                adoptedShape = set.sides.map { it.trackDurationsMs }
                Log.i(TAG, "seated ${going.albumId} at track ${track + 1}${if (going.rate > 1f) ", hurried" else ""}")
                run = null
            }
        } else if (set.sides.isNotEmpty() && deckAlbum != null && printedAlbum == deckAlbum) {
            // A tape already in when the room opened; or one put in from somewhere else — the car,
            // the widget — or the album edited under it: it is in the deck, as it is.
            val shape = set.sides.map { it.trackDurationsMs }
            if (transport.mode == TapeTransport.Mode.EMPTY || adoptedAlbum != deckAlbum || adoptedShape != shape) {
                val track = scratch.playerTrack().takeIf { it in deck.tracks.indices } ?: deck.currentTrackIndex.coerceAtLeast(0)
                transport.adopt(set, track, scratch.playerPositionMs() ?: 0L, scratch.playerIsPlaying())
                adoptedAlbum = deckAlbum
                adoptedShape = shape
                Log.i(TAG, "adopted the tape in the deck: track ${track + 1}")
            }
        }
        val waitingToLeave = going != null && going.t < 0.02f && printedAlbum != going.albumId
        rack.out = when {
            waitingToLeave -> null
            run != null -> run?.albumId
            transport.mode != TapeTransport.Mode.EMPTY -> deckAlbum
            else -> null
        }

        // ---- The deck ---------------------------------------------------------------------------------
        transport.frame(dt.toDouble(), now)
        for (e in transport.events) {
            sound.event(e)
            when (e) {
                DeckEvent.KEY_DOWN, DeckEvent.SEAT, DeckEvent.DOOR_CLOSE -> haptics.tick()
                DeckEvent.LATCH, DeckEvent.AUTO_STOP, DeckEvent.REVERSE -> haptics.detent()
                else -> Unit
            }
        }
        transport.events.clear()
        if (transport.autoReverse != autoReverse && frames > 0) {
            reverseToggled = true
            onAutoReverse(transport.autoReverse)
        }
        if (shownTape != transport.tape) shownTape = transport.tape
        if (awaitingSide != transport.awaiting) awaitingSide = transport.awaiting
        if (modeShown != transport.mode) {
            modeShown = transport.mode
            Log.i(TAG, "deck: ${transport.mode}, side ${Sides.letter(transport.side)}${if (transport.faceB) " (B out)" else ""}${if (transport.headReverse) ", head reversed" else ""}, tape at %.1fs".format(transport.x))
        }

        val stock = transport.set.stock(transport.tape)
        sound.motorLevel = transport.motor
        sound.motorRadPerS = transport.motorRate.toFloat()
        sound.levelling = scratch.playerLevelling()
        if (transport.cueing && transport.set.sides.isNotEmpty()) {
            val (track, offset) = transport.set.placeAt(transport.side, transport.x)
            val musicRate = if (transport.readsForwards) transport.u else -transport.u
            sound.cueAt(true, track, offset / 1000.0, musicRate)
        } else {
            sound.cueAt(false, -1, 0.0, 0.0)
        }

        // ---- The reels: both packs from the one place, turning at the tape's speed over each one's radius.
        val x = transport.x
        val u = transport.u
        leftAngle += capped(TapePacks.leftRate(stock, x, u)) * dt
        rightAngle += capped(TapePacks.rightRate(stock, x, u)) * dt
        leftAngle %= (2f * Math.PI.toFloat())
        rightAngle %= (2f * Math.PI.toFloat())
        val rl = TapePacks.left(stock, x)
        val rr = TapePacks.right(stock, x)
        hubLeft?.quaternion = aboutZ(leftAngle)
        hubRight?.quaternion = aboutZ(rightAngle)
        packLeft?.quaternion = aboutZ(leftAngle)
        packRight?.quaternion = aboutZ(rightAngle)
        packLeft?.scale = Scale(rl, rl, 1f)
        packRight?.scale = Scale(rr, rr, 1f)

        // ---- The keys, the door, the counter, the lamps -----------------------------------------------
        for (key in TapeKey.entries) {
            val travel = if (key == TapeKey.REV) 0.0015f else TapeDimens.KEY_TRAVEL
            val m = translation(Float3(0f, 0f, -transport.depth(key) * travel))
            for (part in keyParts[key.ordinal]) part.transform = m
        }
        val doorDeg = transport.door * TapeDimens.DOOR_OPEN_DEG
        val doorM = about(X_AXIS, doorDeg, 0f, TapeDimens.DOOR_HINGE_Y, TapeDimens.DOOR_HINGE_Z)
        for (part in doorParts) part.transform = doorM
        TapeCounter.drums(transport.counter, drumValues)
        for (k in 0..2) {
            val m = about(X_AXIS, (-36.0 * drumValues[k]).toFloat(), 0f, TapeDimens.DRUM_Y, TapeDimens.DRUM_Z)
            for (part in drums[k]) part.transform = m
        }
        val loaded = transport.mode != TapeTransport.Mode.EMPTY
        lampForward?.isVisible = loaded && !transport.headReverse
        lampReverse?.isVisible = loaded && transport.headReverse

        // ---- The meters: the level the music is at, on a VU's ballistics ----------------------------
        val fresh = System.nanoTime() - tapeState.levelAtNanos < 250_000_000L
        val levels = when {
            transport.cueing -> floatArrayOf(sound.cueLevelLeft, sound.cueLevelRight)
            fresh && scratch.playerIsPlaying() -> floatArrayOf(tapeState.levelLeft, tapeState.levelRight)
            else -> floatArrayOf(0f, 0f)
        }
        var moving = false
        for (c in 0..1) {
            val target = VuMeter.angle(levels[c])
            val sub = 4
            repeat(sub) {
                val h = dt / sub
                needleVelocity[c] += (VuMeter.SPRING * (target - needleAngle[c]) - VuMeter.DAMPING * needleVelocity[c]) * h
                needleAngle[c] += needleVelocity[c] * h
            }
            if (abs(needleVelocity[c]) > 0.5f || abs(target - needleAngle[c]) > 0.3f) moving = true
            val sx = if (c == 0) -TapeDimens.METER_X else TapeDimens.METER_X
            needles[c]?.transform = about(Z_AXIS, -needleAngle[c], sx, TapeDimens.NEEDLE_PIVOT_Y, 0f)
        }

        // ---- The cassette, and its box --------------------------------------------------------------------
        val going2 = run
        val boxSeen = loaded || going2 != null
        if (going2 != null) {
            LoadTape.at(going2.t, going2.fromX, going2.fromY, doorDeg, load)
            caseNode?.position = Position(load.case.x, load.case.y, load.case.z)
            caseNode?.quaternion = load.case.q.toKotlin()
            lidNode?.quaternion = Quat.aboutX(load.lidDeg).toKotlin()
            cassette.copyFrom(load.cassette)
            // Before it is lifted it is in a box nobody can see into but from the deck: shown once the box is down.
            cassetteNode?.isVisible = going2.t >= LoadTape.DOWN_AT
        } else {
            caseNode?.position = Position(0f, TapeDimens.CASE_Y, TapeDimens.CASE_Z)
            caseNode?.quaternion = IDENTITY
            lidNode?.quaternion = Quat.aboutX(TapeDimens.LID_OPEN_DEG).toKotlin()
            LoadTape.seat(doorDeg * (1f - transport.lifted), seat)
            // Lifted out of the door toward you, and turned over about its own upright.
            val l = transport.lifted
            val px = seat.x
            val py = seat.y + 0.010f * l
            val pz = seat.z + 0.070f * l
            val baseFace = if (transport.mode == TapeTransport.Mode.FLIP) (if (transport.flipStartsFaceB) 180f else 0f) + 180f * transport.turned
            else if (transport.faceB) 180f else 0f
            cassette.set(px, py, pz, seat.q * Quat.aboutY(baseFace))
            cassetteNode?.isVisible = loaded
        }
        // Not before it is printed: a box with somebody else's cover on it would be a lie.
        caseNode?.isVisible = boxSeen && !(going2 != null && going2.t < 0.02f && printedAlbum != going2.albumId)
        cassetteNode?.position = Position(cassette.x, cassette.y, cassette.z)
        cassetteNode?.quaternion = cassette.q.toKotlin()

        // ---- The lens --------------------------------------------------------------------------------------
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            placeVelocity += (PLACE_STIFFNESS * PLACE_STIFFNESS * (placeTarget - place) - 2f * PLACE_STIFFNESS * placeVelocity) * h
            place += placeVelocity * h
            left -= h
        }
        if (abs(placeTarget - place) < 0.0006f && abs(placeVelocity) < 0.004f) {
            place = placeTarget
            placeVelocity = 0f
        }
        zoom += (zoomTarget - zoom) * (1f - exp(-ZOOM_EASE * dt))
        if (abs(zoomTarget - zoom) < 0.0005f) zoom = zoomTarget
        // Always: the racks' feet are at the top of the frame over the deck too.
        val racksBusy = rack.onFrame(dt, deckAlbum.takeIf { transport.mode != TapeTransport.Mode.EMPTY }) && place < 1f
        val deckShot = TapeCamera.deck(zoom, aspect)
        val rackShot = TapeCamera.rack(rack.cameraX, rack.cameraY, aspect)
        val risen = place.coerceIn(0f, 1f)
        shot = if (risen >= 1f) deckShot else if (risen <= 0f) rackShot else TapeCamera.between(rackShot, deckShot, ease(risen))
        // The racks' lamp goes along the wall with the camera.
        racksLamp?.position = Position(rack.cameraX, 0f, 0f)

        turning = abs(u) > 0.01 || moving
        busy = changed || rack.changed || racksBusy || run != null || held != null || pinching ||
            place != placeTarget || zoom != zoomTarget ||
            transport.mode == TapeTransport.Mode.SEARCH || transport.mode == TapeTransport.Mode.WIND ||
            transport.mode == TapeTransport.Mode.CUE || transport.mode == TapeTransport.Mode.FLIP ||
            transport.mode == TapeTransport.Mode.REVERSE || transport.mode == TapeTransport.Mode.LOADING ||
            transport.door != 0f && transport.door != 1f || TapeKey.entries.any { transport.depth(it).let { d -> d != 0f && d != 0.78f && d != 1f && d != 0.39f } }
        changed = false
        rack.changed = false
        frames++
    }

    private var reverseToggled = false

    /** The album the deck took to be in it, and the shape of its sides: a change to either is a different tape. */
    private var adoptedAlbum: String? = null
    private var adoptedShape: List<List<Long>> = emptyList()

    /** A reel's speed, kept to what a screen can show turning: beyond a few turns a second it is a blur either way. */
    private fun capped(radPerS: Double): Float = radPerS.coerceIn(-MOST_RAD_PER_S, MOST_RAD_PER_S).toFloat()

    // ---- A finger at the deck ---------------------------------------------------------------------------

    private var held: TapeKey? = null
    private var pinching = false
    private var pinchSpan = 0f
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L
    private val spot = FloatArray(3)

    fun onTouch(event: MotionEvent, deck: DeckState) {
        // At the racks, a finger is the racks'; on the way between, nobody's.
        if (place < 0.04f && placeTarget == 0f) {
            rack.onTouch(event, shot, widthPx, heightPx, touchSlopPx)
            return
        }
        run?.let { going ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) going.rate = HURRY
            return
        }
        if (place < 0.96f || placeTarget != 1f) return
        val now = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                downX = event.x
                downY = event.y
                moved = false
                sound.wake()
                val key = keyAt(event.x, event.y)
                if (key != null && transport.mode != TapeTransport.Mode.EMPTY) {
                    held = key
                    transport.press(key, now)
                    if (key == TapeKey.REV) reverseToggled = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                held?.let { transport.release(it, now) }
                held = null
                pinching = true
                moved = true
                pinchSpan = span(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlopPx) moved = true
                if (pinching && event.pointerCount >= 2) {
                    val now2 = span(event)
                    if (pinchSpan > 1f && now2 > 1f) zoomTarget = (zoomTarget + ln(now2 / pinchSpan) / PINCH_FOR_WHOLE_ZOOM).coerceIn(0f, 1f)
                    pinchSpan = now2
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) pinching = false
            MotionEvent.ACTION_UP -> {
                val key = held
                held = null
                if (key != null) {
                    transport.release(key, now)
                } else if (!pinching && !moved && event.eventTime - downAt <= TAP_LONGEST_MS) {
                    tap(event)
                }
                pinching = false
                zoomTarget = if (zoomTarget >= 0.5f) 1f else 0f
            }
            MotionEvent.ACTION_CANCEL -> {
                held?.let { transport.release(it, now) }
                held = null
                pinching = false
            }
        }
    }

    private fun span(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    /** The key under a point of the screen — with a thumb's margin round each, since the keys are close together. */
    fun keyAt(px: Float, py: Float): TapeKey? {
        TapeCamera.onUpright(shot, px, py, widthPx, heightPx, TapeDimens.FASCIA_T + 0.005f, spot)
        val x = spot[0]
        val y = spot[1]
        if (abs(y - TapeDimens.KEY_Y) <= TapeDimens.KEY_H / 2 + KEY_MARGIN_Y) {
            val index = Math.round(x / TapeDimens.KEY_PITCH + 2.5f)
            if (index in 0..5 && abs(x - TapeDimens.keyX(index)) <= TapeDimens.KEY_PITCH / 2) return TapeKey.entries[index]
        }
        if (abs(x - TapeDimens.REV_X) <= REV_REACH && abs(y - TapeDimens.REV_Y) <= REV_REACH) return TapeKey.REV
        return null
    }

    private fun tap(event: MotionEvent) {
        if (transport.mode == TapeTransport.Mode.EMPTY) {
            // A deck with no tape in it: the racks are where a tape comes from.
            toRacks()
            return
        }
        // The cassette, through its door.
        TapeCamera.onUpright(shot, event.x, event.y, widthPx, heightPx, TapeDimens.SEAT_Z + 0.008f, spot)
        val onTape = abs(spot[0]) <= TapeDimens.DOOR_W / 2 &&
            spot[1] >= TapeDimens.DOOR_HINGE_Y && spot[1] <= TapeDimens.DOOR_HINGE_Y + TapeDimens.DOOR_H
        if (onTape && (transport.mode == TapeTransport.Mode.ENDED || transport.mode == TapeTransport.Mode.EJECTED)) {
            haptics.tick()
            transport.tapTape()
            return
        }
        // A song on the J-card.
        val track = jcardTrackAt(event.x, event.y)
        if (track >= 0) {
            haptics.tick()
            transport.choose(track)
            return
        }
        // Anywhere else, twice: in to the tape, or back out.
        if (event.eventTime - lastTapAt <= DOUBLE_TAP_WITHIN_MS) {
            zoomTarget = if (zoomTarget >= 0.5f) 0f else 1f
            haptics.detent()
            lastTapAt = 0L
        } else {
            lastTapAt = event.eventTime
        }
    }

    /** The song whose line on the open J-card is under a point of the screen, or −1. */
    fun jcardTrackAt(px: Float, py: Float): Int {
        val set = transport.set
        if (set.sides.isEmpty()) return -1
        // The lid stands open on the deck: the J-card's inside is a plane through the hinge, leaning back.
        val hy = TapeDimens.CASE_Y + TapeDimens.HINGE_UP
        val hz = TapeDimens.CASE_Z - TapeDimens.HINGE_BACK
        val q = Quat.aboutX(TapeDimens.LID_OPEN_DEG)
        val o = FloatArray(3)
        val n = FloatArray(3)
        q.rotate(0f, TapeDimens.JCARD_Y - TapeDimens.HINGE_UP, 0f, o)
        q.rotate(0f, -1f, 0f, n)
        if (!TapeCamera.onPlane(shot, px, py, widthPx, heightPx, o[0], hy + o[1], hz + o[2], n[0], n[1], n[2], spot)) return -1
        // Back into the lid's own frame: turned the other way about the hinge.
        val back = Quat.aboutX(-TapeDimens.LID_OPEN_DEG)
        val local = FloatArray(3)
        back.rotate(spot[0], spot[1] - hy, spot[2] - hz, local)
        val caseZ = local[2] - TapeDimens.HINGE_BACK
        val u = (local[0] + TapeDimens.JCARD_L / 2) / TapeDimens.JCARD_L
        val v = 1f - (caseZ + TapeDimens.JCARD_W / 2) / TapeDimens.JCARD_W
        if (u !in 0f..1f || v !in 0f..1f) return -1
        return JCardLayout.of(set.sides, transport.tape).trackAt(u, v)
    }

    // ---- What TalkBack's actions do ----------------------------------------------------------------------

    fun pressAndRelease(key: TapeKey) {
        val now = SystemClock.uptimeMillis()
        if (key == TapeKey.REV) reverseToggled = true
        transport.press(key, now)
        transport.release(key, now)
    }

    fun release() {
        unseen()
        sound.motorLevel = 0f
    }

    private companion object {
        /** `adb logcat -s Jawon.Tape`: what the deck did and when. */
        const val TAG = "Jawon.Tape"
        const val PLACE_STIFFNESS = 7.5f
        const val ZOOM_EASE = 11f
        const val HURRY = 7f
        const val GIVE_UP_S = 5f
        const val TAP_LONGEST_MS = 280L
        const val DOUBLE_TAP_WITHIN_MS = 300L
        const val PINCH_FOR_WHOLE_ZOOM = 0.6f
        const val KEY_MARGIN_Y = 0.006f
        const val REV_REACH = 0.008f
        /** Six turns a second: past it a reel is a blur, and a turning shape on a screen strobes. */
        const val MOST_RAD_PER_S = 6.0 * 2.0 * Math.PI

        val X_AXIS = Float3(1f, 0f, 0f)
        val Z_AXIS = Float3(0f, 0f, 1f)
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        /** A part baked in place, turned [deg] about [axis] through the pivot (px, py, pz). */
        fun about(axis: Float3, deg: Float, px: Float, py: Float, pz: Float): Mat4 =
            translation(Float3(px, py, pz)) * rotation(axis, deg) * translation(Float3(-px, -py, -pz))

        fun aboutZ(rad: Float): Quaternion = Quat.aboutZ(Math.toDegrees(rad.toDouble()).toFloat()).toKotlin()

        fun ease(s: Float): Float {
            val t = s.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/** This rotation, as SceneView takes it: kotlin-math's (x, y, z, w). */
internal fun Quat.toKotlin(): Quaternion = Quaternion(x, y, z, w)

/**
 * **A VU meter**: where its needle points for a level, and how it gets there. A VU is not a peak
 * meter — it was made to read like an ear, rising in about three tenths of a second and
 * overshooting a hair — and that sluggish swing is what everybody remembers of one.
 */
object VuMeter {

    /** 0 VU: the level a record is mastered round, about eighteen decibels under full scale as RMS. */
    const val ZERO_VU = 0.126f

    /** How far the needle swings either side of upright, in degrees. */
    const val HALF_SWING_DEG = 45f

    /** A second-order needle that reaches its reading in about 300ms with a percent of overshoot. */
    const val SPRING = 190f
    const val DAMPING = 22f

    /** Degrees from upright, rightward positive, for an RMS level [rms] of full scale. */
    fun angle(rms: Float): Float {
        if (rms <= 1e-5f) return -HALF_SWING_DEG
        val db = 20f * log10(rms / ZERO_VU)
        // The VU law: deflection goes as voltage, with +3 VU at the right-hand end.
        val f = Math.pow(10.0, db / 20.0).toFloat() / Math.pow(10.0, 3.0 / 20.0).toFloat()
        return (-HALF_SWING_DEG + 2f * HALF_SWING_DEG * f).coerceIn(-HALF_SWING_DEG - 2f, HALF_SWING_DEG + 3f)
    }
}
