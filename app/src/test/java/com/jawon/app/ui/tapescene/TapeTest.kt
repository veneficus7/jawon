package com.jawon.app.ui.tapescene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The tape's arithmetic: what a cassette is, and the one coordinate along it that both packs and
 * the counter come from. The rule the room depends on is that **nothing ever jumps** — not at the
 * end of a side, not at a reverse, not at a turn of the tape — because a pack that jumps is a
 * reel that was never real.
 */
class TapeTest {

    private val minute = 60_000L

    @Test
    fun theShortestCassetteThatHoldsTheLongerSideIsChosen() {
        assertEquals("a single", Tape.C10, Tape.stockFor(3 * minute + 20_000L))
        assertEquals("an EP", Tape.C20, Tape.stockFor(7 * minute + 30_000L))
        assertEquals(Tape.C46, Tape.stockFor(10 * minute + 1))
        assertEquals(Tape.C46, Tape.stockFor(20 * minute))
        assertEquals(Tape.C46, Tape.stockFor(23 * minute))
        assertEquals(Tape.C60, Tape.stockFor(23 * minute + 1))
        assertEquals(Tape.C90, Tape.stockFor(31 * minute))
        assertEquals(Tape.C120, Tape.stockFor(58 * minute))
    }

    @Test
    fun aFullC60PackIsTheSizeARealOneIs() {
        // 85.7m of 16µm tape on a 22mm hub: about 47mm across.
        val full = Tape.fullRadius(Tape.C60)
        assertEquals(0.0236f, full, 0.0003f)
        assertEquals(Tape.HUB_R, Tape.radius(Tape.C60, 0.0), 1e-6f)
        // Two full packs never touch, however long the tape: a C120's beside an empty hub.
        assertTrue(Tape.fullRadius(Tape.C120) + Tape.HUB_R < Tape.HUB_PITCH)
    }

    @Test
    fun tapeIsNeitherMadeNorLostBetweenTheTwoPacks() {
        val stock = Tape.C90
        val area = { x: Double ->
            val l = TapePacks.left(stock, x).toDouble()
            val r = TapePacks.right(stock, x).toDouble()
            l * l + r * r
        }
        val start = area(0.0)
        for (x in listOf(1.0, 300.0, 1350.0, 2000.0, stock.sideSeconds)) {
            assertEquals("at $x", start, area(x), 1e-9)
        }
    }

    @Test
    fun sideBIsTheSameTapeReadBack() {
        val set = TapeSet.of(listOf(4 * minute, 5 * minute, 6 * minute, 3 * minute, 4 * minute, 5 * minute))
        assertEquals(2, set.sides.size)
        val stock = set.stock(0)
        // Side A from the start of the tape, side B from its end.
        assertEquals(0.0, set.musicFrom(0), 1e-9)
        assertEquals(stock.sideSeconds, set.musicFrom(1), 1e-9)
        // A place on either side, there and back.
        for (track in 0 until 6) {
            for (offset in listOf(0L, 1_000L, 59_000L)) {
                val x = set.xOf(track, offset)
                val side = if (track < set.sides[1].firstTrack) 0 else 1
                assertEquals("track $track at $offset", track to offset, set.placeAt(side, x))
            }
        }
    }

    @Test
    fun theReelsDoNotJumpAtAReverse() {
        // Unequal sides: A runs fifteen minutes, B twelve, on a C46 — eight minutes of blank after A.
        val set = TapeSet.of(listOf(5 * minute, 5 * minute, 5 * minute, 6 * minute, 6 * minute))
        val stock = set.stock(0)
        assertEquals(Tape.C46, stock)
        // The blank after side A's music is wound through to the end of the tape, which is where
        // side B's music begins: the place the reverse happens at is one place, read either way.
        val endOfA = set.endFor(0)
        val startOfB = set.musicFrom(1)
        assertEquals(endOfA, startOfB, 1e-9)
        assertEquals(TapePacks.left(stock, endOfA), TapePacks.left(stock, startOfB), 0f)
        // And B's music ends short of the start of the tape: that blank is side B's tail.
        assertTrue(set.musicTo(1) > set.endFor(1))
    }

    @Test
    fun anAlbumOverTwoHoursIsATapeSet() {
        val set = TapeSet.of(List(14) { 10 * minute })
        assertEquals(4, set.sides.size)
        assertEquals(2, set.tapes)
        assertEquals(0, set.tapeOf(1))
        assertEquals(1, set.tapeOf(2))
        assertEquals(2, set.first(1))
        assertEquals(3, set.second(1))
    }

    @Test
    fun aSingleTrackIsOneSide() {
        val set = TapeSet.of(listOf(40 * minute))
        assertEquals(1, set.sides.size)
        assertEquals(-1, set.second(0))
    }

    @Test
    fun theCounterIsGearedToTheSpindleNotTheClock() {
        val stock = Tape.C60
        assertEquals(0.0, TapeCounter.reading(stock, 0.0, 0.0), 1e-9)
        // Half a count a turn of a spindle that winds a whole side on: about 390 for a C60.
        val full = TapeCounter.reading(stock, stock.sideSeconds, 0.0)
        assertEquals(394.0, full, 6.0)
        // It runs faster at the start of a side than at the end, which is why nobody trusted it.
        val firstFiveMinutes = TapeCounter.reading(stock, 300.0, 0.0)
        val lastFiveMinutes = full - TapeCounter.reading(stock, stock.sideSeconds - 300.0, 0.0)
        assertTrue(firstFiveMinutes > lastFiveMinutes * 1.5)
        // Counting back past nought rolls round, as drums do.
        assertTrue(TapeCounter.reading(stock, 0.0, 10.0) > 990.0)
    }

    @Test
    fun theDrumsCarryTogether() {
        val drums = DoubleArray(3)
        TapeCounter.drums(42.0, drums)
        assertEquals(2.0, drums[0], 1e-9)
        assertEquals(4.0, drums[1], 1e-9)
        assertEquals(0.0, drums[2], 1e-9)
        // Half way from 099 to 100, all three are half way over.
        TapeCounter.drums(99.5, drums)
        assertEquals(9.5, drums[0], 1e-9)
        assertEquals(9.5, drums[1], 1e-9)
        assertEquals(0.5, drums[2], 1e-9)
        // The tens do not move while the units are anywhere but their last tenth.
        TapeCounter.drums(123.4, drums)
        assertEquals(2.0, drums[1], 1e-9)
        assertTrue(abs(drums[2] - 1.0) < 1e-9)
    }
}
