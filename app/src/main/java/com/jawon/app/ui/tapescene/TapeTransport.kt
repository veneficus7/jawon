package com.jawon.app.ui.tapescene

import com.jawon.app.ui.vinylscene.Sides
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** The deck's keys, left to right under the door — and the small one by the counter. */
enum class TapeKey { EJECT, REW, PLAY, FF, STOP, PAUSE, REV }

/** Something the deck did that is heard (and, mostly, felt): see `DeckSound`. */
enum class DeckEvent { KEY_DOWN, KEY_UP, LATCH, DOOR_OPEN, DOOR_CLOSE, SEAT, REVERSE, AUTO_STOP, TICK }

/**
 * What the deck needs of the player. The room wires it to the real one (`ScratchLink`, through the
 * view model); `TapeTransportTest` to a pretend one.
 */
interface TapePlayer {
    fun isPlaying(): Boolean
    fun track(): Int
    fun positionMs(): Long?
    /** Stopped by itself at the end of a track: a side has ended, or the album has. */
    fun endedTrack(): Boolean
    fun play()
    fun pause()
    fun seek(trackIndex: Int, positionMs: Long)
    fun stopAtEndOfTrack(stop: Boolean)
}

/**
 * **The deck's transport**: what its keys do, and where the tape is because of it.
 *
 * Pure — frames in, the player told what to do, and everything the scene draws read off it: the
 * place along the tape ([x]) both packs and the counter come from, how fast it is moving ([u]),
 * which way up the cassette is and which way the head reads, how far down each key is, the door,
 * the tape lifted out and turned over. `TapeTransportTest` drives it through every way a side can
 * end and every key there is.
 *
 * ### What a deck is, here
 *
 * - **PLAY** latches down and the music runs; the tape follows the player's own clock. **PAUSE**
 *   latches too. **STOP** lets them both up.
 * - **REW and FF**: a *tap* is the music search a deck had (AMS) — the tape wound to the start of
 *   the next song, or back to the start of this one, or from its first three seconds the one
 *   before — in about a second, motor and all. A *hold* winds for as long as it is held: playing,
 *   that is the cue, and the music is heard going by ([cueing]); stopped, it is the motor and the
 *   reels and nothing else. Nothing crosses the end of a side.
 * - **The end of a side**: the blank after its music is wound through, and then, with auto-reverse,
 *   the head turns over and the other side plays; without it the deck waits ([Mode.ENDED]) for the
 *   tape to be turned over by hand — a touch on it, PLAY, or a song on the other side touched.
 * - **EJECT** stops the tape and opens the door with it in; a touch on the tape then turns it over
 *   and puts it back.
 *
 * The music is from the moment PLAY goes down, never before: a tape loaded or wound is silent.
 */
class TapeTransport(private val player: TapePlayer) {

    enum class Mode { EMPTY, LOADING, STOP, PLAY, WIND, CUE, SEARCH, REVERSE, ENDED, EJECTED, FLIP }

    var mode = Mode.EMPTY
        private set

    /** The album as tapes; empty with no tape in. */
    var set: TapeSet = TapeSet(emptyList())
        private set

    /** Which tape of the set is in, from 0. */
    var tape = 0
        private set

    /** The cassette's B face is the one toward you: it has been turned over. */
    var faceB = false
        private set

    /** The head has turned over and reads right to left — the ◀ lamp. */
    var headReverse = false
        private set

    /** Where the tape is, in seconds of tape from the start of its first side. */
    var x = 0.0
        private set

    /** How fast it is moving, in seconds of tape a second along [x]: ±1 playing, ±tens winding. */
    var u = 0.0
        private set

    /** Auto-reverse: the deck's REV, and Settings. */
    var autoReverse = true

    /** The room is being looked at: only then is the player asked to stop at the end of a side. */
    var seen = true

    /** What was heard and felt since the room last asked. */
    val events = ArrayList<DeckEvent>()

    /** The counter, as it reads. */
    var counter = 0.0
        private set

    /** The side the head reads now. */
    val side: Int get() = tape * 2 + if (faceB != headReverse) 1 else 0

    /** Whether the head reads forwards along the tape (+x). */
    val readsForwards: Boolean get() = side % 2 == 0

    /** The other side, when the deck is waiting for the tape to be turned over to it; −1 when not waiting. */
    var awaiting = -1
        private set

    /** The music going by is being heard: the room's cue sound follows [x] at [u]. */
    val cueing: Boolean get() = mode == Mode.CUE

    /** How hard the motor is working, 0..1, and how fast the reel it drives is turning (radians a second): for the whine. */
    var motor = 0f
        private set
    var motorRate = 0.0
        private set

    // ---- What the scene draws -----------------------------------------------------------------------

    private val keyDepth = FloatArray(TapeKey.entries.size)
    private val keyHeld = BooleanArray(TapeKey.entries.size)
    private val keyDownAt = LongArray(TapeKey.entries.size)

    /** A hold that has run the tape to its end: it does not start again until the key is let up. */
    private val holdSpent = BooleanArray(TapeKey.entries.size)

    /** How far down [key] is: 0 up, 1 pressed home; a latched key rests a little short of home. */
    fun depth(key: TapeKey): Float = keyDepth[key.ordinal]

    /** The door: 0 shut, 1 open. */
    var door = 0f
        private set
    private var doorTarget = 0f

    /** The cassette lifted out of the door's seat, 0..1, and turned over, 0..1 of half a turn. */
    var lifted = 0f
        private set
    var turned = 0f
        private set

    /** The head turning over at a reverse, 0..1: the lamps change half way. */
    var reversing = 0f
        private set

    /** A tape change is half done: the label is edge-on, and may be printed for the next tape now. */
    var labelSwap = false
        private set

    private var pauseLatched = false

    /** How long the player has been silent while the deck was playing: past [OUTSIDE_PAUSE_S], it was paused from outside. */
    private var silentFor = 0.0

    // ---- Internal ------------------------------------------------------------------------------------

    private var now = 0L

    /** A planned run of the tape — search, blank — from [from] to [to] over [duration] seconds. */
    private var planFrom = 0.0
    private var planTo = 0.0
    private var planT = 0.0
    private var planDuration = 1.0
    private var afterPlan: (() -> Unit)? = null

    /** A key held: which, how long, and the speed it has the tape at. */
    private var holdKey: TapeKey? = null
    private var holdFor = 0.0

    /** Told the player to go somewhere: until it says it is there, what it says is not believed. */
    private var agreeing = -1.0
    private var agreeTrack = -1
    private var agreeMs = 0L

    /** The player was moved by somebody else and the tape is being wound after it, for the look of it only. */
    private var chasing = false

    /** A clock for the timelines — reverse, flip — and what to do at the end of one. */
    private var clock = 0.0
    private var flipChange = false
    private var flipToSide = -1
    private var flipThenTrack = -1
    private var flipFromFaceB = false
    private var labelWaited = 0.0
    private var reverseThenTrack = -1

    /** The counter's zero: how much tape was on the deck's right-hand spindle when it was last reset. */
    private var counterZero = 0.0

    // ---- Loading -------------------------------------------------------------------------------------------

    /** A tape is on its way in: the door opens for it. Whatever was playing has already been stopped by the room. */
    fun beginLoad() {
        holdKey = null
        loadingClosing = false
        chasing = false
        agreeing = -1.0
        if (doorTarget < 1f) events += DeckEvent.DOOR_OPEN
        doorTarget = 1f
        mode = Mode.LOADING
        pauseLatched = false
        u = 0.0
        awaiting = -1
    }

    /**
     * The tape is in the door's seat: [set] is the album, and the player is at [trackIndex],
     * [offsetMs] — where the album was left, so the tape is put in at that place, that side out.
     * The door shuts, and PLAY goes down.
     */
    fun seated(set: TapeSet, trackIndex: Int, offsetMs: Long) {
        this.set = set
        val s = if (set.sides.isEmpty()) 0 else Sides.of(set.sides, trackIndex.coerceAtLeast(0))
        tape = set.tapeOf(s)
        faceB = s % 2 == 1
        headReverse = false
        x = if (set.sides.isEmpty()) 0.0 else set.xOf(trackIndex.coerceAtLeast(0), offsetMs)
        resetCounter()
        events += DeckEvent.SEAT
        doorTarget = 0f
        events += DeckEvent.DOOR_CLOSE
        mode = Mode.LOADING
        clock = 0.0
        loadingClosing = true
    }

    private var loadingClosing = false

    /**
     * Nobody is looking — the home button, a panel, another room — and a tape on its way in is in:
     * the door shut and PLAY down now, because the frames that would have done it are not coming.
     */
    fun finishLoad() {
        if (mode != Mode.LOADING || set.sides.isEmpty()) return
        loadingClosing = false
        doorTarget = 0f
        door = 0f
        startPlaying()
    }

    /** The deck is waiting for a tape to arrive in its door. */
    val loading: Boolean get() = mode == Mode.LOADING

    /** Where the tape is for side [face] out and nothing else known: the room's question before it has one. */
    fun faceFor(set: TapeSet, trackIndex: Int): Boolean =
        set.sides.isNotEmpty() && Sides.of(set.sides, trackIndex.coerceAtLeast(0)) % 2 == 1

    /** The room was opened on a tape already in and playing, or paused: it is there, as it is. */
    fun adopt(set: TapeSet, trackIndex: Int, offsetMs: Long, playing: Boolean) {
        this.set = set
        if (set.sides.isEmpty()) {
            mode = Mode.EMPTY
            return
        }
        val s = Sides.of(set.sides, trackIndex.coerceAtLeast(0))
        tape = set.tapeOf(s)
        faceB = s % 2 == 1
        headReverse = false
        x = set.xOf(trackIndex.coerceAtLeast(0), offsetMs)
        resetCounter()
        doorTarget = 0f
        door = 0f
        mode = if (playing) Mode.PLAY else Mode.STOP
        pauseLatched = false
        if (playing) keyDepth[TapeKey.PLAY.ordinal] = LATCHED
    }

    /** A different album's tracks are in the player now: there is no tape of it in the deck. */
    fun empty() {
        mode = Mode.EMPTY
        set = TapeSet(emptyList())
        u = 0.0
        awaiting = -1
    }

    // ---- Keys ------------------------------------------------------------------------------------------------

    fun press(key: TapeKey, nowMs: Long) {
        if (keyHeld[key.ordinal]) return
        keyHeld[key.ordinal] = true
        keyDownAt[key.ordinal] = nowMs
        events += DeckEvent.KEY_DOWN
        // A tape on its way in or being turned over: the keys go down, and nothing else happens.
        if (mode == Mode.LOADING || mode == Mode.FLIP) return
        when (key) {
            TapeKey.PLAY -> play()
            TapeKey.STOP -> stop(sound = false)
            TapeKey.PAUSE -> pause()
            TapeKey.EJECT -> eject()
            TapeKey.REV -> {
                autoReverse = !autoReverse
                events += DeckEvent.TICK
            }
            // A tap or a hold: which, is known when it is let go or when it has been held long enough.
            TapeKey.FF, TapeKey.REW -> Unit
        }
    }

    fun release(key: TapeKey, nowMs: Long) {
        if (!keyHeld[key.ordinal]) return
        keyHeld[key.ordinal] = false
        val spent = holdSpent[key.ordinal]
        holdSpent[key.ordinal] = false
        events += DeckEvent.KEY_UP
        if (mode == Mode.LOADING || mode == Mode.FLIP) return
        if (key != TapeKey.FF && key != TapeKey.REW) return
        if (holdKey == key) {
            letGoOfHold()
        } else if (!spent && nowMs - keyDownAt[key.ordinal] < HOLD_AFTER_MS) {
            skip(forward = key == TapeKey.FF)
        }
    }

    /** Every key let up — a finger gone off the edge of the screen. */
    fun releaseAll(nowMs: Long) {
        for (key in TapeKey.entries) release(key, nowMs)
    }

    // ---- What the keys do ------------------------------------------------------------------------------------

    private fun hasTape(): Boolean = set.sides.isNotEmpty() && mode != Mode.EMPTY

    private fun play() {
        if (!hasTape()) return
        when (mode) {
            Mode.PLAY -> if (pauseLatched) pause()
            Mode.ENDED -> if (awaiting >= 0) {
                val first = set.sides[awaiting].firstTrack
                // Auto-reverse put on since the side ended: the head turns, rather than a hand.
                if (set.tapeOf(awaiting) == tape && autoReverse) reverseTo(first)
                else turnOverTo(awaiting, first, change = set.tapeOf(awaiting) != tape)
            }
            Mode.EJECTED -> {
                // The door shut on it, and away.
                closeDoor()
                startPlaying()
            }
            Mode.STOP, Mode.WIND, Mode.SEARCH -> {
                holdKey = null
                if (albumOver()) fromTheTop() else startPlaying()
            }
            else -> Unit
        }
    }

    /**
     * Past the last of the album's music: nothing after it on this side, and no side after this
     * one. PLAY there used to be the deck's auto-stop and nothing else — a real deck's answer, and
     * on the owner's phone (2026-11-14) a key that seemed not to work.
     */
    /** The album is over and the deck stopped at its end: PLAY, or a touch on the tape, plays it again. */
    val over: Boolean get() = mode == Mode.STOP && hasTape() && albumOver()

    private fun albumOver(): Boolean {
        val s = set.sides.getOrNull(side) ?: return false
        return pastTheMusic() && s.lastTrack + 1 >= set.sides.sumOf { it.trackDurationsMs.size }
    }

    /**
     * The album again from its first song, the deck's own way there: wound back on this side,
     * the head reversed to the other, or the tape turned over or changed for the first of a set —
     * as a song touched on the J-card is got to.
     */
    private fun fromTheTop() {
        choose(0)
    }

    private fun startPlaying() {
        events += DeckEvent.LATCH
        mode = Mode.PLAY
        pauseLatched = false
        // Past the end of the music on this side — a cue let go in the blank after it — there is
        // nothing to play: the blank runs out, and the side ends as it would have.
        if (pastTheMusic()) {
            sideEnded()
            return
        }
        val place = set.placeAt(side, x)
        tell(place.first, place.second, play = true)
    }

    /** The tape is beyond the last of this side's music, in the direction the head reads. */
    private fun pastTheMusic(): Boolean {
        val end = set.musicTo(side)
        return if (readsForwards) x >= end - MUSIC_END_SLACK_S else x <= end + MUSIC_END_SLACK_S
    }

    private fun stop(sound: Boolean) {
        if (!hasTape()) return
        if (mode == Mode.EJECTED) return
        val was = mode
        holdKey = null
        afterPlan = null
        pauseLatched = false
        if (was == Mode.PLAY || was == Mode.CUE) player.pause()
        if (was == Mode.CUE || was == Mode.WIND || was == Mode.SEARCH) settlePlayer(play = false)
        mode = Mode.STOP
        u = 0.0
        awaiting = -1
        if (sound) events += DeckEvent.AUTO_STOP
    }

    private fun pause() {
        if (mode != Mode.PLAY) return
        pauseLatched = !pauseLatched
        if (pauseLatched) player.pause() else player.play()
        // The deck's own doing: what the player says for the next moment is it catching up, not somebody else.
        ownWord = OWN_WORD_S
    }

    /** Counting down from the deck's own pause or play: the player is not taken as having been moved from outside meanwhile. */
    private var ownWord = 0.0

    private fun eject() {
        if (!hasTape()) return
        when (mode) {
            Mode.EJECTED -> {
                closeDoor()
                mode = Mode.STOP
            }
            else -> {
                stop(sound = false)
                doorTarget = 1f
                events += DeckEvent.DOOR_OPEN
                mode = Mode.EJECTED
            }
        }
    }

    private fun closeDoor() {
        doorTarget = 0f
        events += DeckEvent.DOOR_CLOSE
    }

    /** A touch on the cassette: turn it over, when there is a reason to. */
    fun tapTape() {
        if (!hasTape()) return
        when (mode) {
            Mode.ENDED -> play()
            // The album over: a touch on the tape is PLAY, and plays it again.
            Mode.STOP -> if (albumOver()) fromTheTop()
            Mode.EJECTED -> {
                // Turned over and put back, to play its other side — or, a side with none, played again.
                val other = otherSideOfTape()
                turnOverTo(other, set.sides[other].firstTrack, change = false)
            }
            else -> Unit
        }
    }

    /** TalkBack's "turn the tape over": as a hand would, at any time — out of the door, over, and back in. */
    fun turnOverByHand() {
        if (!hasTape() || mode == Mode.LOADING || mode == Mode.FLIP || mode == Mode.REVERSE) return
        if (mode == Mode.ENDED) {
            play()
            return
        }
        val other = otherSideOfTape()
        turnOverTo(other, set.sides[other].firstTrack, change = false)
    }

    /** A song touched on the J-card: the tape wound to it, turned or reversed to it first if it is on another side. */
    fun choose(trackIndex: Int) {
        if (!hasTape() || trackIndex !in 0 until set.sides.sumOf { it.trackDurationsMs.size }) return
        if (mode == Mode.LOADING || mode == Mode.FLIP || mode == Mode.REVERSE) return
        holdKey = null
        val to = Sides.of(set.sides, trackIndex)
        if (to == side) {
            if (mode == Mode.EJECTED) closeDoor()
            if (mode == Mode.PLAY) player.pause()
            search(set.trackStart(trackIndex), then = { startPlaying() })
            return
        }
        if (set.tapeOf(to) == tape && autoReverse && mode != Mode.EJECTED) {
            reverseTo(trackIndex)
        } else {
            turnOverTo(to, trackIndex, change = set.tapeOf(to) != tape)
        }
    }

    /** A tap on REW or FF: the next song, or this one's start, or the one before. */
    private fun skip(forward: Boolean) {
        if (!hasTape() || mode == Mode.EJECTED || mode == Mode.REVERSE) return
        val playing = mode == Mode.PLAY || (mode == Mode.SEARCH && searchingToPlay)
        val s = set.sides.getOrNull(side) ?: return
        val (track, _) = set.placeAt(side, x)
        val along = abs(x - set.trackStart(track))
        if (mode == Mode.PLAY) player.pause()
        val then: () -> Unit = if (playing) ({ startPlaying() }) else ({ stopHere() })
        if (forward) {
            if (track < s.lastTrack) {
                search(set.trackStart(track + 1), then, playing)
            } else {
                // The last song of the side: on to the end of the tape — and, playing, whatever the
                // end of a side does; stopped, stopped there.
                search(set.endFor(side), if (playing) ({ sideRanOut() }) else then, playing)
            }
        } else {
            val to = if (along < BACK_TO_PREVIOUS_S && track > s.firstTrack) track - 1 else track
            search(set.trackStart(to), then, playing)
        }
    }

    private var searchingToPlay = false

    private fun stopHere() {
        mode = Mode.STOP
        u = 0.0
        val place = set.placeAt(side, x)
        tell(place.first, place.second, play = false)
    }

    private fun search(to: Double, then: () -> Unit, toPlay: Boolean = true) {
        val end = set.stock(tape).sideSeconds
        planFrom = x
        planTo = to.coerceIn(0.0, end)
        planT = 0.0
        planDuration = searchSeconds(abs(planTo - planFrom))
        afterPlan = then
        searchingToPlay = toPlay
        pauseLatched = false
        mode = Mode.SEARCH
        awaiting = -1
    }

    private fun letGoOfHold() {
        val was = mode
        holdKey = null
        when (was) {
            Mode.CUE -> {
                // Back to the music, from wherever the cue got to.
                u = if (readsForwards) 1.0 else -1.0
                startPlaying()
            }
            Mode.WIND -> stopHere()
            else -> Unit
        }
    }

    /** At the end of a side: wind through the blank, then reverse, wait, or stop. */
    private fun sideEnded() {
        val end = set.endFor(side)
        if (abs(end - x) > BLANK_LEAST_S) {
            // PLAY stays down through the blank when the deck is going on to the other side.
            search(end, { sideRanOut() }, toPlay = autoReverse)
            planDuration = blankSeconds(abs(planTo - planFrom))
        } else {
            x = end
            sideRanOut()
        }
    }

    private fun sideRanOut() {
        u = 0.0
        val s = set.sides.getOrNull(side)
        val nextTrack = (s?.lastTrack ?: -1) + 1
        val tracks = set.sides.sumOf { it.trackDurationsMs.size }
        if (s == null || nextTrack >= tracks) {
            // The album is over: the deck stops itself, as one did at the end of the tape.
            mode = Mode.STOP
            pauseLatched = false
            events += DeckEvent.AUTO_STOP
            val place = set.placeAt(side, x)
            tell(place.first, place.second, play = false)
            return
        }
        val next = side + 1
        val sameTape = set.tapeOf(next) == tape
        when {
            sameTape && autoReverse -> reverseTo(nextTrack)
            autoReverse -> turnOverTo(next, nextTrack, change = true)
            else -> {
                mode = Mode.ENDED
                pauseLatched = false
                awaiting = next
                events += DeckEvent.AUTO_STOP
            }
        }
    }

    /** The head turns over, and the tape is wound to [trackIndex] on what is now the side it reads. */
    private fun reverseTo(trackIndex: Int) {
        if (mode == Mode.PLAY) player.pause()
        mode = Mode.REVERSE
        clock = 0.0
        u = 0.0
        reverseThenTrack = trackIndex
        awaiting = -1
        events += DeckEvent.REVERSE
    }

    /** Out of the door, turned over — or changed for the next tape of a set — and back in, then wound to [trackIndex]. */
    private fun turnOverTo(toSide: Int, trackIndex: Int, change: Boolean) {
        if (mode == Mode.PLAY) player.pause()
        holdKey = null
        flipChange = change
        flipToSide = toSide
        flipThenTrack = trackIndex
        flipFromFaceB = faceB
        labelWaited = 0.0
        mode = Mode.FLIP
        clock = if (door >= 0.99f) FLIP_DOOR_S else 0.0
        if (doorTarget < 1f) events += DeckEvent.DOOR_OPEN
        doorTarget = 1f
        u = 0.0
        awaiting = -1
        labelSwap = false
    }

    private fun otherSideOfTape(): Int {
        val other = if (side % 2 == 0) side + 1 else side - 1
        return if (other in set.sides.indices) other else side
    }

    // ---- Telling the player --------------------------------------------------------------------------------

    private fun tell(trackIndex: Int, offsetMs: Long, play: Boolean) {
        player.seek(trackIndex, offsetMs)
        if (play) player.play() else player.pause()
        agreeing = 0.0
        agreeTrack = trackIndex
        agreeMs = offsetMs
        chasing = false
    }

    /** The player put where the tape now is, without starting it. */
    private fun settlePlayer(play: Boolean) {
        if (set.sides.isEmpty()) return
        val place = set.placeAt(side, x)
        tell(place.first, place.second, play)
    }

    // ---- Every frame ----------------------------------------------------------------------------------------

    fun frame(dt: Double, nowMs: Long) {
        now = nowMs
        // Held long enough to be a hold, not a tap: the tape winds, or cues.
        for (key in arrayOf(TapeKey.FF, TapeKey.REW)) {
            if (keyHeld[key.ordinal] && !holdSpent[key.ordinal] && holdKey == null && nowMs - keyDownAt[key.ordinal] >= HOLD_AFTER_MS) beginHold(key)
        }

        when (mode) {
            Mode.EMPTY -> {
                u = 0.0
                player.stopAtEndOfTrack(false)
            }
            Mode.LOADING -> loading(dt)
            Mode.STOP, Mode.ENDED, Mode.EJECTED -> {
                u = approach(u, 0.0, dt, BRAKE_S)
                player.stopAtEndOfTrack(false)
                if (agreeing >= 0.0) {
                    agreeing += dt
                    if (agreeing > AGREE_WITHIN_S) agreeing = -1.0
                }
                // Started from somewhere else — the notification, a car, the widget: the door
                // shuts on the tape and PLAY goes down, because the music is going.
                if (agreeing < 0.0 && player.isPlaying()) {
                    if (mode == Mode.EJECTED) closeDoor()
                    mode = Mode.PLAY
                    awaiting = -1
                    pauseLatched = false
                    events += DeckEvent.LATCH
                }
            }
            Mode.PLAY -> playing(dt)
            Mode.WIND, Mode.CUE -> holding(dt)
            Mode.SEARCH -> searching(dt)
            Mode.REVERSE -> reversingFrame(dt)
            Mode.FLIP -> flipping(dt)
        }
        if (mode != Mode.PLAY && mode != Mode.CUE && mode != Mode.SEARCH && mode != Mode.WIND) x = x.coerceIn(0.0, set.stock(tape).sideSeconds)

        // The door, on its damper; the keys, on their springs.
        door = approachF(door, doorTarget, dt, if (doorTarget > door) DOOR_OPEN_S else DOOR_CLOSE_S)
        for (key in TapeKey.entries) {
            val target = when {
                keyHeld[key.ordinal] -> 1f
                key == TapeKey.PLAY && (mode == Mode.PLAY || mode == Mode.CUE || (mode == Mode.SEARCH && searchingToPlay)) -> LATCHED
                key == TapeKey.PAUSE && pauseLatched && mode == Mode.PLAY -> LATCHED
                key == holdKey -> 1f
                key == TapeKey.REV && autoReverse -> LATCHED * 0.5f
                else -> 0f
            }
            val i = key.ordinal
            keyDepth[i] = approachF(keyDepth[i], target, dt, if (target > keyDepth[i]) KEY_DOWN_S else KEY_UP_S)
        }

        // The motor: how hard it works, and the take-up reel it drives.
        val stock = set.stock(tape)
        val winding = abs(u) > 1.5
        motor = approachF(motor, if (winding) min(1f, (abs(u) / 60.0).toFloat() + 0.35f) else 0f, dt, 0.06)
        motorRate = if (set.sides.isEmpty()) 0.0 else {
            val supplyLeft = u > 0
            abs(if (supplyLeft) TapePacks.leftRate(stock, x, u) else TapePacks.rightRate(stock, x, u))
        }

        // The counter, geared to the deck's right-hand spindle — the cassette's right hub, unless
        // it has been turned over.
        counter = TapeCounter.reading(stock, onDeckRight(), counterZero)
    }

    private fun onDeckRight(): Double {
        val stock = set.stock(tape)
        return if (faceB) stock.sideSeconds - x else x
    }

    private fun resetCounter() {
        counterZero = onDeckRight()
    }

    private fun beginHold(key: TapeKey) {
        if (!hasTape() || mode == Mode.EJECTED || mode == Mode.REVERSE || mode == Mode.ENDED) return
        holdKey = key
        holdFor = 0.0
        when (mode) {
            Mode.PLAY -> {
                if (pauseLatched) {
                    mode = Mode.WIND
                } else {
                    player.pause()
                    mode = Mode.CUE
                }
            }
            Mode.SEARCH -> mode = if (searchingToPlay) Mode.CUE else Mode.WIND
            else -> mode = Mode.WIND
        }
        afterPlan = null
        chasing = false
    }

    private fun holding(dt: Double) {
        holdFor += dt
        val key = holdKey ?: TapeKey.FF
        // FF is onward in the music the head reads; REW, back.
        val onward = if (key == TapeKey.FF) 1.0 else -1.0
        val along = if (readsForwards) onward else -onward
        val speed = if (mode == Mode.CUE) {
            CUE_FROM + (CUE_TOP - CUE_FROM) * (1.0 - exp(-holdFor / CUE_RISE_S))
        } else {
            WIND_FROM + (WIND_TOP - WIND_FROM) * (1.0 - exp(-holdFor / WIND_RISE_S))
        }
        u = approach(u, along * speed, dt, MOTOR_S)
        x += u * dt
        val end = set.stock(tape).sideSeconds
        if (x <= 0.0 || x >= end) {
            // The end of the tape: the motor stops itself.
            x = x.coerceIn(0.0, end)
            u = 0.0
            val wasCue = mode == Mode.CUE
            // Which end: the one the side being read runs out at, or the one it starts from.
            val ranOut = if (readsForwards) x >= end else x <= 0.0
            holdSpent[key.ordinal] = true
            holdKey = null
            events += DeckEvent.AUTO_STOP
            when {
                // Cued off the end of the music: that is the end of the side.
                wasCue && ranOut -> {
                    mode = Mode.PLAY
                    sideRanOut()
                }
                // Cued back to the very start: it plays from there.
                wasCue -> letGoOfHold()
                else -> stopHere()
            }
        }
        player.stopAtEndOfTrack(false)
    }

    private fun searching(dt: Double) {
        planT = min(planDuration, planT + dt)
        val s = (planT / planDuration).coerceIn(0.0, 1.0)
        val before = x
        x = planFrom + (planTo - planFrom) * minimumJerk(s)
        u = if (dt > 0) (x - before) / dt else 0.0
        player.stopAtEndOfTrack(false)
        if (planT >= planDuration) {
            x = planTo
            u = 0.0
            val then = afterPlan
            afterPlan = null
            if (then != null) then() else stopHere()
        }
    }

    private fun reversingFrame(dt: Double) {
        clock += dt
        u = approach(u, 0.0, dt, BRAKE_S)
        val was = reversing
        reversing = (clock / REVERSE_S).toFloat().coerceIn(0f, 1f)
        if (was < 0.5f && reversing >= 0.5f) headReverse = !headReverse
        if (clock >= REVERSE_S) {
            reversing = 0f
            val track = reverseThenTrack
            reverseThenTrack = -1
            val to = set.trackStart(track)
            if (abs(to - x) > BLANK_LEAST_S) {
                search(to, { startPlaying() })
            } else {
                x = to
                startPlaying()
            }
        }
    }

    private fun flipping(dt: Double) {
        clock += dt
        val t = clock
        u = 0.0
        // Door (if it was shut), out, over, in, door.
        lifted = when {
            t < FLIP_DOOR_S -> 0f
            t < FLIP_DOOR_S + FLIP_OUT_S -> ease(((t - FLIP_DOOR_S) / FLIP_OUT_S).toFloat())
            t < FLIP_DOOR_S + FLIP_OUT_S + FLIP_TURN_S -> 1f
            t < FLIP_DOOR_S + 2 * FLIP_OUT_S + FLIP_TURN_S -> 1f - ease(((t - FLIP_DOOR_S - FLIP_OUT_S - FLIP_TURN_S) / FLIP_OUT_S).toFloat())
            else -> 0f
        }
        val turnStart = FLIP_DOOR_S + FLIP_OUT_S
        val wasTurned = turned
        turned = ease(((t - turnStart) / FLIP_TURN_S).toFloat().coerceIn(0f, 1f))
        if (wasTurned < 0.5f && turned >= 0.5f) {
            // Edge-on: the cassette is the other way round from here — it has been turned over,
            // whatever else — and if it is the next tape of a set, it is that tape now.
            faceB = !flipFromFaceB
            if (flipChange) {
                tape = set.tapeOf(flipToSide)
                x = set.musicFrom(flipToSide)
                labelSwap = true
            }
            // The head reads the side asked for: forwards if that is the face toward you, and
            // turned over if it is the face away — an auto-reverse deck can play either.
            headReverse = faceB != (flipToSide % 2 == 1)
            resetCounter()
        }
        // The next tape of a set: held edge-on until its labels are printed, as the vinyl room's next
        // record waits under its cover — never back in the door with the last tape's labels on it.
        if (flipChange && labelSwap && turned >= 0.5f && clock > turnStart + FLIP_TURN_S * 0.5) {
            labelWaited += dt
            if (labelWaited < LABEL_WAIT_MOST_S) clock = turnStart + FLIP_TURN_S * 0.5
        }
        val inAt = FLIP_DOOR_S + 2 * FLIP_OUT_S + FLIP_TURN_S
        if (t >= inAt && doorTarget > 0f) {
            events += DeckEvent.SEAT
            closeDoor()
        }
        if (t >= inAt + FLIP_DOOR_S) {
            lifted = 0f
            turned = 0f
            labelSwap = false
            val to = set.trackStart(flipThenTrack.coerceAtLeast(0))
            mode = Mode.STOP
            if (abs(to - x) > BLANK_LEAST_S) search(to, { startPlaying() }) else {
                x = to
                startPlaying()
            }
        }
    }

    /** The tape is turned over by the deck, not by the scene: the scene turns the shell by [turned] from where it started. */
    val flipStartsFaceB: Boolean get() = flipFromFaceB

    private fun loading(dt: Double) {
        u = 0.0
        player.stopAtEndOfTrack(false)
        if (!loadingClosing) return
        clock += dt
        if (clock >= DOOR_CLOSE_S * 2.2) {
            loadingClosing = false
            events += DeckEvent.KEY_DOWN
            startPlaying()
        }
    }

    private fun playing(dt: Double) {
        if (set.sides.isEmpty()) return
        val playing = player.isPlaying()
        val heard = player.track()
        val at = player.positionMs()
        val tracks = set.sides.sumOf { it.trackDurationsMs.size }

        // Told to go somewhere: until the player says it is there, the tape goes on as it was told.
        if (agreeing >= 0.0) {
            agreeing += dt
            if ((heard == agreeTrack && at != null && abs(at - agreeMs) < AGREE_MS) || agreeing > AGREE_WITHIN_S) agreeing = -1.0
        }

        val direction = if (readsForwards) 1.0 else -1.0
        u = if (playing && !pauseLatched) direction else approach(u, 0.0, dt, BRAKE_S)

        // Paused, or started again, from somewhere else: PAUSE goes down, or comes up. Not for a
        // moment's buffering, and not at the end of a side, which is the player stopping as asked.
        if (ownWord > 0.0) {
            ownWord -= dt
            silentFor = 0.0
        } else if (agreeing < 0.0) {
            silentFor = if (playing) 0.0 else silentFor + dt
            if (!pauseLatched && silentFor > OUTSIDE_PAUSE_S && !player.endedTrack()) pauseLatched = true
            if (pauseLatched && playing) pauseLatched = false
        }

        if (agreeing < 0.0 && heard in 0 until tracks && at != null) {
            val heardSide = Sides.of(set.sides, heard)
            if (heardSide != side) {
                // Moved to another side by somebody else — the car, the notification: the deck is on it.
                adoptSide(heardSide)
            }
            val target = set.xOf(heard, at)
            val off = target - x
            when {
                chasing -> Unit
                abs(off) > CHASE_FROM_S -> {
                    // A seek from elsewhere: the tape is wound after it, for the look of it — the player is already there.
                    chasing = true
                    planFrom = x
                    planTo = target
                    planT = 0.0
                    planDuration = searchSeconds(abs(off)) * 0.6
                }
                else -> x += u * dt + off * (1.0 - exp(-dt / FOLLOW_S))
            }
        } else if (!chasing) {
            x += u * dt
        }
        if (chasing) {
            planT = min(planDuration, planT + dt)
            val before = x
            x = planFrom + (planTo - planFrom) * minimumJerk((planT / planDuration).coerceIn(0.0, 1.0))
            u = if (dt > 0) (x - before) / dt else u
            if (planT >= planDuration) chasing = false
        }

        // The end of a side, asked of the player itself: a pause sent when the room noticed would
        // land a fraction of a second into the next track, which is on the other side.
        val s = set.sides.getOrNull(side)
        val track = if (heard in 0 until tracks) heard else set.placeAt(side, x).first
        val last = s != null && track == s.lastTrack
        player.stopAtEndOfTrack(seen && last && track + 1 < tracks)
        if (agreeing < 0.0 && !playing && !pauseLatched && last && player.endedTrack()) {
            sideEnded()
        }
    }

    private fun adoptSide(heardSide: Int) {
        onAdoptSide(heardSide)
        val toTape = set.tapeOf(heardSide)
        if (toTape != tape) {
            tape = toTape
            faceB = heardSide % 2 == 1
            headReverse = false
            labelSwap = true
        } else if (side != heardSide) {
            headReverse = !headReverse
        }
    }

    /** Told when the player is found on another side than the head reads — for the room's log. */
    var onAdoptSide: (Int) -> Unit = {}

    /** The label for the next tape has been printed: the swap is over. */
    fun labelPrinted() {
        labelSwap = false
    }

    private companion object {
        /** Held this long, a key is held; let go sooner, it was a tap. */
        const val HOLD_AFTER_MS = 300L

        /** Further into a song than this, REW goes back to its start; closer, to the one before. */
        const val BACK_TO_PREVIOUS_S = 3.0

        /** A latched key rests this far down: short of home, as PLAY stays down on a deck. */
        const val LATCHED = 0.78f

        const val KEY_DOWN_S = 0.022
        const val KEY_UP_S = 0.055
        const val DOOR_OPEN_S = 0.085
        const val DOOR_CLOSE_S = 0.060

        /** A cue: from four times speed to twelve, as a held key is held — twelve is as fast as the cue's reader stays clean. */
        const val CUE_FROM = 4.0
        const val CUE_TOP = 12.0
        const val CUE_RISE_S = 0.5

        /** A wind from stop: twenty times speed rising to a hundred and twenty. */
        const val WIND_FROM = 20.0
        const val WIND_TOP = 120.0
        const val WIND_RISE_S = 1.2

        /** How quickly the tape takes up a speed it is given, and how quickly it stops. */
        const val MOTOR_S = 0.16
        const val BRAKE_S = 0.07

        /** Blank tape shorter than this after the music is not worth winding through. */
        const val BLANK_LEAST_S = 1.0

        /** The player told to go somewhere is believed when it is within this of it, or after this long whatever it says. */
        const val AGREE_MS = 900L
        const val AGREE_WITHIN_S = 1.5

        /** Further from the tape than this, the player has been moved, and the tape is wound after it. */
        const val CHASE_FROM_S = 2.5

        /** How closely the tape keeps to the player's own clock while it plays. */
        const val FOLLOW_S = 0.35

        /** Within this of the end of a side's music is the end of it. */
        const val MUSIC_END_SLACK_S = 0.05

        /** Silent this long while playing, and not at the end of a side: somebody paused it. */
        const val OUTSIDE_PAUSE_S = 0.8

        const val OWN_WORD_S = 0.6

        const val REVERSE_S = 0.55

        const val FLIP_DOOR_S = 0.26
        const val FLIP_OUT_S = 0.30
        const val FLIP_TURN_S = 0.52

        /** How long a tape change waits for its labels before it goes in regardless. */
        const val LABEL_WAIT_MOST_S = 3.0

        /**
         * How long a search takes to wind [distance] seconds of tape: a song away is under a
         * second; a whole side, two. A deck took a minute; this is the deck's whine and the reels'
         * blur in the time a phone is expected to answer in.
         */
        fun searchSeconds(distance: Double): Double = 0.40 + 1.6 * (1.0 - exp(-distance / 900.0))

        /** The blank after a side's music: quicker, it has nothing on it. */
        fun blankSeconds(distance: Double): Double = 0.25 + 0.5 * (1.0 - exp(-distance / 300.0))

        /** Smooth from rest to rest: the speed peaks half way, and nothing jerks at either end. */
        fun minimumJerk(s: Double): Double = s * s * s * (10.0 + s * (-15.0 + 6.0 * s))

        fun ease(s: Float): Float {
            val t = s.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun approach(value: Double, target: Double, dt: Double, tau: Double): Double =
            if (dt <= 0.0) value else target + (value - target) * exp(-dt / tau)

        fun approachF(value: Float, target: Float, dt: Double, tau: Double): Float {
            if (dt <= 0.0) return value
            val next = target + (value - target) * exp(-dt / tau).toFloat()
            return if (abs(next - target) < 0.002f) target else next
        }
    }
}
