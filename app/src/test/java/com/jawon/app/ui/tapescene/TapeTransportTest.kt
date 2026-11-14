package com.jawon.app.ui.tapescene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The deck, driven frame by frame against a player that behaves as ExoPlayer does where it
 * matters: it runs on its own clock, goes from one track to the next by itself, and — told to —
 * stops at the end of a track with its position at the track's length.
 */
class TapeTransportTest {

    private val minute = 60_000L

    /** Six songs: three on side A (15 minutes), three on side B (12) — a C46. */
    private val durations = listOf(5 * minute, 5 * minute, 5 * minute, 4 * minute, 4 * minute, 4 * minute)

    private class Player(val durations: List<Long>) : TapePlayer {
        var playing = false
        var trackIndex = 0
        var position = 0L
        var stopAtEnd = false
        var ended = false
        val told = ArrayList<String>()
        /** The tracks it was stopped at the end of, having been told to. */
        val stoppedAfter = ArrayList<Int>()

        override fun isPlaying() = playing
        override fun track() = trackIndex
        override fun positionMs() = position
        override fun endedTrack() = ended
        override fun play() {
            playing = true
            ended = false
            told += "play"
        }
        override fun pause() {
            playing = false
            told += "pause"
        }
        override fun seek(trackIndex: Int, positionMs: Long) {
            this.trackIndex = trackIndex
            position = positionMs
            ended = false
            told += "seek $trackIndex $positionMs"
        }
        override fun stopAtEndOfTrack(stop: Boolean) {
            stopAtEnd = stop
        }

        fun advance(ms: Long) {
            if (!playing) return
            position += ms
            while (position >= durations[trackIndex]) {
                if (stopAtEnd || trackIndex == durations.lastIndex) {
                    if (stopAtEnd) stoppedAfter += trackIndex
                    position = durations[trackIndex]
                    playing = false
                    ended = true
                    return
                }
                position -= durations[trackIndex]
                trackIndex++
            }
        }
    }

    private class Rig(durations: List<Long>) {
        val player = Player(durations)
        val deck = TapeTransport(player)
        val set = TapeSet.of(durations)
        var now = 1_000L
        val xs = ArrayList<Double>()

        fun run(seconds: Double, step: Double = 1.0 / 60.0, until: (() -> Boolean)? = null) {
            var left = seconds
            while (left > 0) {
                player.advance((step * 1000).toLong())
                now += (step * 1000).toLong()
                deck.frame(step, now)
                xs += deck.x
                left -= step
                if (until?.invoke() == true) return
            }
        }

        fun tap(key: TapeKey) {
            deck.press(key, now)
            run(0.1)
            deck.release(key, now)
        }

        fun loadAt(track: Int, offsetMs: Long) {
            player.seek(track, offsetMs)
            player.told.clear()
            deck.beginLoad()
            run(0.3)
            deck.seated(set, track, offsetMs)
            run(0.5)
            // The tape was put in at its place: what follows is what the reels do.
            xs.clear()
        }
    }

    @Test
    fun aTapeGoesInAndPlaysFromWhereTheAlbumWasLeft() {
        val rig = Rig(durations)
        rig.deck.beginLoad()
        assertEquals(TapeTransport.Mode.LOADING, rig.deck.mode)
        rig.run(0.4)
        assertTrue("the door opens for it", rig.deck.door > 0.95f)
        assertFalse("silent until PLAY", rig.player.playing)
        rig.player.seek(4, 30_000L)
        rig.deck.seated(rig.set, 4, 30_000L)
        rig.run(0.6)
        assertEquals(TapeTransport.Mode.PLAY, rig.deck.mode)
        assertTrue(rig.player.playing)
        assertTrue("side B out, for a song on side B", rig.deck.faceB)
        assertEquals(1, rig.deck.side)
        assertEquals(rig.set.xOf(4, 30_000L), rig.deck.x, 1.0)
        assertTrue("the door is shut", rig.deck.door < 0.05f)
        assertEquals("the counter starts at nought", 0.0, rig.deck.counter, 1.5)
    }

    @Test
    fun aTapOnFfWindsToTheNextSongInUnderASecondAndPlaysIt() {
        val rig = Rig(durations)
        rig.loadAt(0, 40_000L)
        rig.player.told.clear()
        rig.tap(TapeKey.FF)
        assertEquals(TapeTransport.Mode.SEARCH, rig.deck.mode)
        val started = rig.now
        rig.run(2.0, until = { rig.deck.mode == TapeTransport.Mode.PLAY })
        assertTrue("under a second, a song away", rig.now - started < 1_000L)
        assertTrue(rig.player.told.contains("seek 1 0"))
        assertEquals("play", rig.player.told.last())
        assertEquals(rig.set.trackStart(1), rig.deck.x, 0.05)
    }

    @Test
    fun rewIsTheStartOfThisSongOrFromItsFirstSecondsTheOneBefore() {
        val rig = Rig(durations)
        rig.loadAt(1, 60_000L)
        rig.tap(TapeKey.REW)
        rig.run(2.0, until = { rig.deck.mode == TapeTransport.Mode.PLAY })
        assertTrue(rig.player.told.contains("seek 1 0"))
        rig.player.told.clear()
        rig.run(0.5)
        rig.tap(TapeKey.REW)
        rig.run(2.0, until = { rig.deck.mode == TapeTransport.Mode.PLAY })
        assertTrue("from its first seconds: the one before", rig.player.told.contains("seek 0 0"))
    }

    @Test
    fun aHeldFfWhilePlayingCuesAndLettingGoPlaysFromThere() {
        val rig = Rig(durations)
        rig.loadAt(0, 10_000L)
        val from = rig.deck.x
        rig.deck.press(TapeKey.FF, rig.now)
        rig.run(0.5)
        assertEquals(TapeTransport.Mode.CUE, rig.deck.mode)
        assertTrue(rig.deck.cueing)
        assertFalse("the player is quiet: the cue is what is heard", rig.player.playing)
        rig.run(1.5)
        assertTrue("never faster than the cue reads cleanly", abs(rig.deck.u) <= 12.0 + 1e-9)
        assertTrue(rig.deck.x > from + 5.0)
        rig.player.told.clear()
        rig.deck.release(TapeKey.FF, rig.now)
        assertEquals(TapeTransport.Mode.PLAY, rig.deck.mode)
        val (track, offset) = rig.set.placeAt(0, rig.deck.x)
        assertEquals(listOf("seek $track $offset", "play"), rig.player.told)
    }

    @Test
    fun aHeldFfWhileStoppedWindsSilentlyAndStopsWhereLetGo() {
        val rig = Rig(durations)
        rig.loadAt(0, 0L)
        rig.tap(TapeKey.STOP)
        assertEquals(TapeTransport.Mode.STOP, rig.deck.mode)
        rig.deck.press(TapeKey.FF, rig.now)
        rig.run(1.0)
        assertEquals(TapeTransport.Mode.WIND, rig.deck.mode)
        assertFalse(rig.deck.cueing)
        assertTrue(rig.deck.motor > 0.3f)
        rig.player.told.clear()
        rig.deck.release(TapeKey.FF, rig.now)
        assertEquals(TapeTransport.Mode.STOP, rig.deck.mode)
        assertFalse(rig.player.told.contains("play"))
        assertTrue(rig.player.told.first().startsWith("seek"))
    }

    @Test
    fun windingNeverGoesPastTheEndOfTheTape() {
        val rig = Rig(durations)
        rig.loadAt(2, 0L)
        rig.tap(TapeKey.STOP)
        rig.deck.events.clear()
        rig.player.told.clear()
        rig.deck.press(TapeKey.FF, rig.now)
        rig.run(30.0)
        // The end of the tape is heard once, and the player told once — not again every frame the key is still held.
        assertEquals(1, rig.deck.events.count { it == DeckEvent.AUTO_STOP })
        assertEquals(1, rig.player.told.count { it.startsWith("seek") })
        val end = rig.set.stock(0).sideSeconds
        assertTrue(rig.xs.all { it <= end + 1e-9 && it >= -1e-9 })
        assertEquals(TapeTransport.Mode.STOP, rig.deck.mode)
        assertEquals(end, rig.deck.x, 1e-9)
    }

    @Test
    fun withAutoReverseTheHeadTurnsAndSideBPlaysOn() {
        val rig = Rig(durations)
        rig.loadAt(2, 5 * minute - 3_000L)
        assertTrue(rig.deck.autoReverse)
        rig.run(8.0)
        assertEquals("stopped at the end of side A, not run on into B", listOf(2), rig.player.stoppedAfter)
        assertEquals(TapeTransport.Mode.PLAY, rig.deck.mode)
        assertEquals(1, rig.deck.side)
        assertTrue("the ◀ lamp: the head reads back", rig.deck.headReverse)
        assertFalse("not turned over by hand", rig.deck.faceB)
        assertEquals(3, rig.player.trackIndex)
        assertTrue(rig.player.playing)
        // Side B's first song starts where the tape ends: the reels went through the blank and on, unbroken.
        val end = rig.set.stock(0).sideSeconds
        assertTrue(rig.xs.any { abs(it - end) < 0.01 })
        assertBigStepsOnlyWhereWinding(rig)
    }

    @Test
    fun withoutItTheDeckWaitsAndATouchTurnsTheTapeOver() {
        val rig = Rig(durations)
        rig.deck.autoReverse = false
        rig.loadAt(2, 5 * minute - 2_000L)
        rig.run(5.0)
        assertEquals(TapeTransport.Mode.ENDED, rig.deck.mode)
        assertEquals(1, rig.deck.awaiting)
        assertFalse(rig.player.playing)
        rig.player.told.clear()
        rig.deck.tapTape()
        assertEquals(TapeTransport.Mode.FLIP, rig.deck.mode)
        rig.run(1.0)
        assertFalse("nothing plays while it is out of the door", rig.player.told.contains("play"))
        rig.run(3.0)
        assertEquals(TapeTransport.Mode.PLAY, rig.deck.mode)
        assertTrue("turned over", rig.deck.faceB)
        assertFalse(rig.deck.headReverse)
        assertEquals(1, rig.deck.side)
        assertEquals(3, rig.player.trackIndex)
        assertTrue(rig.player.playing)
    }

    @Test
    fun theEndOfTheAlbumStopsTheDeck() {
        val rig = Rig(durations)
        rig.loadAt(5, 4 * minute - 2_000L)
        rig.deck.events.clear()
        rig.run(6.0)
        assertEquals(TapeTransport.Mode.STOP, rig.deck.mode)
        assertTrue(rig.deck.events.contains(DeckEvent.AUTO_STOP))
        assertFalse(rig.player.playing)
        assertEquals(0f, rig.deck.depth(TapeKey.PLAY), 0.01f)
    }

    @Test
    fun aSeekFromOutsideWindsTheTapeAfterItAndTellsThePlayerNothing() {
        val rig = Rig(durations)
        rig.loadAt(0, 10_000L)
        rig.run(2.0)
        rig.player.told.clear()
        // The notification's scrubber: two minutes on.
        rig.player.position += 120_000L
        rig.run(2.0)
        assertTrue(rig.player.told.isEmpty())
        assertEquals(rig.set.xOf(rig.player.trackIndex, rig.player.position), rig.deck.x, 0.5)
    }

    @Test
    fun pausedAndStartedFromOutsideTheKeysFollow() {
        val rig = Rig(durations)
        rig.loadAt(0, 10_000L)
        rig.run(1.0)
        rig.player.playing = false
        rig.run(1.5)
        assertTrue("PAUSE goes down", rig.deck.depth(TapeKey.PAUSE) > 0.5f)
        rig.player.playing = true
        rig.run(0.5)
        assertTrue(rig.deck.depth(TapeKey.PAUSE) < 0.1f)
        rig.tap(TapeKey.STOP)
        rig.run(2.0)
        rig.player.playing = true
        rig.run(0.5)
        assertEquals(TapeTransport.Mode.PLAY, rig.deck.mode)
    }

    @Test
    fun theOwnPauseKeyIsNotTakenForSomebodyElses() {
        val rig = Rig(durations)
        rig.loadAt(0, 10_000L)
        rig.run(1.0)
        rig.tap(TapeKey.PAUSE)
        rig.run(1.5)
        assertFalse(rig.player.playing)
        assertTrue(rig.deck.depth(TapeKey.PAUSE) > 0.5f)
        rig.tap(TapeKey.PAUSE)
        rig.run(1.0)
        assertTrue(rig.player.playing)
        assertTrue(rig.deck.depth(TapeKey.PAUSE) < 0.1f)
    }

    @Test
    fun aSongOnTheOtherSideIsReversedToAndWoundTo() {
        val rig = Rig(durations)
        rig.loadAt(0, 10_000L)
        rig.deck.choose(4)
        assertEquals(TapeTransport.Mode.REVERSE, rig.deck.mode)
        rig.run(4.0, until = { rig.deck.mode == TapeTransport.Mode.PLAY && rig.deck.side == 1 })
        assertEquals(1, rig.deck.side)
        assertTrue(rig.player.told.contains("seek 4 0"))
        assertEquals(rig.set.trackStart(4), rig.deck.x, 0.05)
    }

    @Test
    fun ejectStopsAndOpensTheDoorAndShutsItAgain() {
        val rig = Rig(durations)
        rig.loadAt(0, 10_000L)
        rig.tap(TapeKey.EJECT)
        rig.run(0.5)
        assertEquals(TapeTransport.Mode.EJECTED, rig.deck.mode)
        assertFalse(rig.player.playing)
        assertTrue(rig.deck.door > 0.95f)
        rig.tap(TapeKey.EJECT)
        rig.run(0.5)
        assertEquals(TapeTransport.Mode.STOP, rig.deck.mode)
        assertTrue(rig.deck.door < 0.05f)
    }

    @Test
    fun aTapeSetIsChangedNotTurnedForTheNextTape() {
        // Fourteen ten-minute songs: two tapes.
        val long = List(14) { 10 * minute }
        val rig = Rig(long)
        val secondSide = rig.set.sides[1]
        rig.loadAt(secondSide.lastTrack, 10 * minute - 2_000L)
        assertEquals(1, rig.deck.side)
        rig.run(8.0)
        assertEquals(TapeTransport.Mode.PLAY, rig.deck.mode)
        assertEquals(1, rig.deck.tape)
        assertEquals(2, rig.deck.side)
        assertEquals(rig.set.sides[2].firstTrack, rig.player.trackIndex)
    }

    @Test
    fun aCueLetGoInTheBlankAfterTheMusicRunsTheSideOutAndNeverGoesBack() {
        val rig = Rig(durations)
        rig.loadAt(2, 5 * minute - 4_000L)
        rig.deck.press(TapeKey.FF, rig.now)
        rig.run(1.6)
        val endOfMusic = rig.set.musicTo(0)
        assertTrue("cued past the music", rig.deck.x > endOfMusic)
        rig.player.told.clear()
        val letGoAt = rig.deck.x
        rig.xs.clear()
        rig.deck.release(TapeKey.FF, rig.now)
        rig.run(4.0)
        assertFalse("the player is never sent to the end of the last song", rig.player.told.any { it.startsWith("seek 2") })
        // Until the blank has run out at the end of the tape, the reels only ever go on.
        val end = rig.set.stock(0).sideSeconds
        val upToTheEnd = rig.xs.takeWhile { it < end - 0.01 }
        assertTrue("onward, never back", upToTheEnd.zipWithNext().all { (a, b) -> b >= a - 1e-9 } && upToTheEnd.all { it >= letGoAt - 1e-6 })
        assertEquals(1, rig.deck.side)
        assertEquals(3, rig.player.trackIndex)
    }

    /** Between two frames the tape moves no further than the motor could have taken it — the reels never jump. */
    private fun assertBigStepsOnlyWhereWinding(rig: Rig) {
        val steps = rig.xs.zipWithNext { a, b -> abs(b - a) }
        val biggest = steps.maxOrNull() ?: 0.0
        // A blank wound in a quarter of a second at most covers its eight minutes at the
        // minimum-jerk peak of 1.875 times the average: ~30 seconds of tape a frame at sixty.
        assertTrue("biggest step $biggest", biggest < 40.0)
    }
}
