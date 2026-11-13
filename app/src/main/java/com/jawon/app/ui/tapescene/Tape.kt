package com.jawon.app.ui.tapescene

import com.jawon.app.ui.vinylscene.Side
import com.jawon.app.ui.vinylscene.Sides
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

/**
 * **A cassette's tape**, as the arithmetic everything in the room is drawn from.
 *
 * The reels are the progress bar (`docs/formats.md` §6): tape leaves one hub and is wound onto the
 * other, so the size of the two packs *is* where you are in the side. That only reads as true if
 * the packs are the size a real tape's would be, so they are worked out from what a tape is — its
 * length, its thickness and the hub it is wound on — and not drawn as a fraction.
 *
 * Pure, and the reason it is: `TapeTest` holds the two things that would otherwise only be
 * noticed on a phone, that the packs never jump (at a reverse, at a turn of the tape, at either end)
 * and that one coordinate along the tape gives both of them.
 */
object Tape {

    /** 1⅞ inches a second: the speed every compact cassette in the world plays at. */
    const val SPEED_M_S = 0.047625

    /** The hub the tape is wound on: 22mm across, so this is how small a pack can be. */
    const val HUB_R = 0.0110f

    /** How far apart the two hubs are, centre to centre (IEC 60094-7). */
    const val HUB_PITCH = 0.0425f

    /**
     * The cassettes there are: how long a side runs, and how thick the tape is that makes a side
     * that long fit. A longer cassette is not a bigger one — it is thinner tape on the same hubs,
     * which is why a C120 was the one that stretched and snarled.
     */
    class Stock(val name: String, val sideSeconds: Double, val thicknessM: Double) {
        override fun toString(): String = name
    }

    val C46 = Stock("C46", 23.0 * 60, 18e-6)
    val C60 = Stock("C60", 30.0 * 60, 16e-6)
    val C90 = Stock("C90", 45.0 * 60, 12e-6)
    val C100 = Stock("C100", 50.0 * 60, 11e-6)
    val C120 = Stock("C120", 60.0 * 60, 9e-6)
    val STOCK = listOf(C46, C60, C90, C100, C120)

    /** The longest a side can be: a C120's. An album longer than two of these is more than one tape. */
    const val LONGEST_SIDE_MS = 60L * 60_000L

    /** The shortest cassette that holds [longestSideMs] on each side. */
    fun stockFor(longestSideMs: Long): Stock =
        STOCK.firstOrNull { it.sideSeconds * 1000.0 >= longestSideMs } ?: C120

    /** How big a pack is with [seconds] of [stock] wound on it: the hub, and the tape's area round it. */
    fun radius(stock: Stock, seconds: Double): Float {
        val metres = seconds.coerceIn(0.0, stock.sideSeconds) * SPEED_M_S
        return sqrt(HUB_R.toDouble() * HUB_R + metres * stock.thicknessM / PI).toFloat()
    }

    /** How many times round its hub a pack holding [seconds] of [stock] has been wound. */
    fun turns(stock: Stock, seconds: Double): Double =
        (radius(stock, seconds) - HUB_R) / stock.thicknessM

    /** The biggest a pack of [stock] gets: all of it on one hub. */
    fun fullRadius(stock: Stock): Float = radius(stock, stock.sideSeconds)
}

/**
 * An album as tapes: its sides, which tape each is on, and the stock each tape is.
 *
 * A side is cut as the vinyl room cuts one (`Sides.cut`), never through a track, but a side of tape
 * can run an hour where a side of a record runs twenty-three minutes. So nearly every album is one
 * tape with two sides. One longer than two hours is a set, and the sides go A B on the first tape,
 * C D on the second, as they would on a record.
 */
class TapeSet(val sides: List<Side>) {

    val tapes: Int = (sides.size + 1) / 2

    /** The tape [side] is on, from 0. */
    fun tapeOf(side: Int): Int = side / 2

    /** The two sides of [tape]: the one read first (A, C, …) and the other, or −1 if it has only one. */
    fun first(tape: Int): Int = tape * 2
    fun second(tape: Int): Int = if (tape * 2 + 1 < sides.size) tape * 2 + 1 else -1

    private val stock: List<Tape.Stock> = List(tapes) { tape ->
        val a = sides.getOrNull(first(tape))?.durationMs ?: 0L
        val b = sides.getOrNull(second(tape))?.durationMs ?: 0L
        Tape.stockFor(max(a, b))
    }

    fun stock(tape: Int): Tape.Stock = stock.getOrElse(tape) { Tape.C60 }

    /**
     * **Where on the tape a place in the album is**, in seconds of tape from the start of its
     * first side — the one coordinate both packs and the counter are worked out from.
     *
     * The first side is read from the start forwards; the second is the same tape read back the
     * other way, so its music begins where the tape ends and goes back toward the start. That is
     * why the packs never jump at a reverse: the last moment of side A and the first of side B are
     * the same place on the same tape, give or take the blank between them.
     */
    fun at(side: Int, offsetInSideMs: Long): Double {
        val seconds = offsetInSideMs / 1000.0
        return if (side % 2 == 0) seconds else stock(tapeOf(side)).sideSeconds - seconds
    }

    /** [at] for a place given as a track and how far into it. */
    fun xOf(trackIndex: Int, offsetMs: Long): Double {
        val side = Sides.of(sides, trackIndex)
        return at(side, offsetInSide(side, trackIndex, offsetMs))
    }

    /** How far into its side, in milliseconds, [offsetMs] into track [trackIndex] is. */
    fun offsetInSide(side: Int, trackIndex: Int, offsetMs: Long): Long {
        val s = sides.getOrNull(side) ?: return 0L
        var before = 0L
        for (i in 0 until (trackIndex - s.firstTrack).coerceIn(0, s.trackDurationsMs.size)) before += s.trackDurationsMs[i]
        return before + offsetMs
    }

    /** And back: the side [side] read at [x] is which track, and how far into it. Past the music is the end of its last track. */
    fun placeAt(side: Int, x: Double): Pair<Int, Long> {
        val s = sides.getOrNull(side) ?: return 0 to 0L
        val stock = stock(tapeOf(side))
        val into = if (side % 2 == 0) x else stock.sideSeconds - x
        var left = Math.round(into.coerceAtLeast(0.0) * 1000.0)
        for ((i, length) in s.trackDurationsMs.withIndex()) {
            if (left < length || i == s.trackDurationsMs.lastIndex) return (s.firstTrack + i) to left.coerceIn(0L, max(length - 1, 0L))
            left -= length
        }
        return s.firstTrack to 0L
    }

    /** Where side [side]'s music begins and ends, as places on its tape: from, to, in the order it is read. */
    fun musicFrom(side: Int): Double = at(side, 0L)
    fun musicTo(side: Int): Double = at(side, sides.getOrNull(side)?.durationMs ?: 0L)

    /** The start of [trackIndex], on the tape. */
    fun trackStart(trackIndex: Int): Double = xOf(trackIndex, 0L)

    /** Whether the head reads [side] going forwards along the tape (A, C, …) or back (B, D, …). */
    fun forwards(side: Int): Boolean = side % 2 == 0

    /** Where the tape ends for a head reading [side]: its far end, which the blank after the music is wound to. */
    fun endFor(side: Int): Double = if (forwards(side)) stock(tapeOf(side)).sideSeconds else 0.0

    companion object {
        fun of(durationsMs: List<Long>): TapeSet = TapeSet(Sides.cut(durationsMs, Tape.LONGEST_SIDE_MS))
    }
}

/**
 * The two packs, and the counter, at a place along a tape.
 *
 * "Left" and "right" are the cassette's own, with side A toward you: the tape runs from the left
 * hub to the right one while side A plays. Turned over, the cassette's left hub is on the deck's
 * right — the scene turns the whole shell, so nothing here needs to know which way it faces.
 */
object TapePacks {

    /** The left hub's pack at [x]: everything not yet read of side A, which is everything side B has read. */
    fun left(stock: Tape.Stock, x: Double): Float = Tape.radius(stock, stock.sideSeconds - x)

    /** The right hub's pack: everything side A has read. */
    fun right(stock: Tape.Stock, x: Double): Float = Tape.radius(stock, x)

    /**
     * How fast each hub turns, in radians a second about the cassette's own front-facing axis,
     * for tape moving [u] seconds of tape a second (1 playing side A, −1 playing side B, ±tens
     * winding). Both turn the same way: the tape leaves the left pack on its outer side, runs
     * along the bottom past the head and is wound onto the right pack's outer side, so with side A
     * toward you both hubs go anticlockwise, and the nearly empty one goes round much the faster.
     */
    fun leftRate(stock: Tape.Stock, x: Double, u: Double): Double = u * Tape.SPEED_M_S / left(stock, x)
    fun rightRate(stock: Tape.Stock, x: Double, u: Double): Double = u * Tape.SPEED_M_S / right(stock, x)
}

/**
 * The deck's counter: three drums geared to its right-hand spindle, half a count to a turn, as
 * the counters on real decks were — which is why theirs never agreed with a clock, ran faster at
 * the start of a side than the end, and why everybody wrote the numbers on the J-card anyway.
 */
object TapeCounter {

    /** Counts a turn of the spindle is worth. */
    const val PER_TURN = 0.5

    /**
     * What the counter reads with [onRight] seconds of [stock] on the deck's right-hand spindle,
     * when it was reset with [atReset] on it. Counting down past zero rolls round, as drums do.
     */
    fun reading(stock: Tape.Stock, onRight: Double, atReset: Double): Double {
        val counts = (Tape.turns(stock, onRight) - Tape.turns(stock, atReset)) * PER_TURN
        return ((counts % 1000.0) + 1000.0) % 1000.0
    }

    /**
     * How far each of the three drums is turned, in tenths of a turn (a digit is one), for a
     * counter reading [value]: the units turn smoothly, and each drum to the left turns only in
     * the last tenth of its neighbour's turn — the carry, which is how a mechanical counter rolls
     * from 099 to 100 with the two left drums going over together.
     */
    fun drums(value: Double, out: DoubleArray) {
        val v = ((value % 1000.0) + 1000.0) % 1000.0
        out[0] = v % 10.0
        val units = v % 10.0
        val tensCarry = if (units > 9.0) units - 9.0 else 0.0
        out[1] = (kotlin.math.floor(v / 10.0) % 10.0) + tensCarry
        val tens = v % 100.0
        val hundredsCarry = if (tens > 99.0) tens - 99.0 else 0.0
        out[2] = (kotlin.math.floor(v / 100.0) % 10.0) + hundredsCarry
    }
}
