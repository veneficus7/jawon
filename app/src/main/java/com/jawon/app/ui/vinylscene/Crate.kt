package com.jawon.app.ui.vinylscene

import com.jawon.app.ui.wallscene.WallLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/** What stands in the crate. */
sealed class CrateItem {
    /** The card that says whose records the ones behind it are: a shelf's name on a tab. */
    data class Divider(val shelfId: String, val name: String, val records: Int) : CrateItem()

    data class Record(val albumId: String, val title: String, val artist: String, val coverPath: String?) : CrateItem()

    /** The card at the back of every crate: where a record that is not in it yet comes in. */
    object Add : CrateItem()
}

/**
 * **The crate** (`docs/formats.md` §5): the whole library standing in one bin, in the order the
 * shelves hold it, with a tabbed card between one shelf's records and the next — which is how a
 * record shop keeps a bin, and why this is one crate and not a row of them. A card's tab stands
 * above the sleeves, so the shelves further back can be read before they are reached; and one
 * crate is one pool of sleeves, whatever the size of the library.
 */
object Crate {

    /** Every shelf that has records, each behind its own card, and the card to add one at the very back. */
    fun items(layout: WallLayout): List<CrateItem> {
        // Not before the library has been read: a crate that offered only "add a record" for a
        // moment, to somebody with three hundred of them, would be saying something untrue.
        if (!layout.loaded) return emptyList()
        val out = ArrayList<CrateItem>()
        val byRow = layout.casesInWallOrder.groupBy { it.row }
        for (row in layout.rows.sortedBy { it.row }) {
            val records = byRow[row.row].orEmpty()
            if (records.isEmpty()) continue
            out += CrateItem.Divider(row.shelfId, row.name, records.size)
            records.mapTo(out) { CrateItem.Record(it.albumId, it.title, it.artist, it.coverPath) }
        }
        out += CrateItem.Add
        return out
    }

    fun indexOf(items: List<CrateItem>, albumId: String?): Int =
        if (albumId == null) -1 else items.indexOfFirst { it is CrateItem.Record && it.albumId == albumId }

    /**
     * Where a hand can come to rest: on a record, or on the card at the back. Never on a shelf's
     * card — that is something gone past, not something stopped at, and a crate that rested on
     * one would be showing a blank piece of card as if it were a record.
     */
    fun stops(items: List<CrateItem>): BooleanArray = BooleanArray(items.size) { items[it] !is CrateItem.Divider }

    /** Where a crate is opened at with nothing to go back to: its first record. */
    fun start(items: List<CrateItem>): Int = items.indexOfFirst { it !is CrateItem.Divider }.coerceAtLeast(0)

    /**
     * Where a sideways swipe goes from [from]: on to the first record of the next shelf, or back
     * to the first record of this one — and from there, to the first of the shelf before. Past
     * the last shelf is the back of the crate; before the first, there is nowhere, and it stays.
     */
    fun shelfStart(items: List<CrateItem>, from: Int, forward: Boolean): Int {
        if (items.isEmpty()) return 0
        val at = from.coerceIn(0, items.lastIndex)
        if (forward) {
            val next = (at + 1..items.lastIndex).firstOrNull { items[it] is CrateItem.Divider }
            return if (next == null) items.lastIndex else (next + 1).coerceAtMost(items.lastIndex)
        }
        val own = (at downTo 0).firstOrNull { items[it] is CrateItem.Divider } ?: return start(items)
        if (at > own + 1) return own + 1
        val before = (own - 1 downTo 0).firstOrNull { items[it] is CrateItem.Divider } ?: return (own + 1).coerceAtMost(items.lastIndex)
        return before + 1
    }

    /**
     * Which card each thing is, counting only cards (−1 for a record). A card's number is which
     * of the pool's cards draws it, and where along its top edge its tab is cut: left, middle,
     * right, and round again, so that three shelves in a row can all be read at once.
     */
    fun cardOrdinals(items: List<CrateItem>): IntArray {
        var n = 0
        return IntArray(items.size) { if (items[it] is CrateItem.Record) -1 else n++ }
    }
}

/**
 * How everything in the crate stands, as a function of one number.
 *
 * `position` is which thing is being read — a record's index, and between two of them while a
 * hand is going from one to the next — and everything else follows from how far each thing is
 * from it ([pose]'s `d`). The ones not yet reached lean back against the back of the crate, and
 * the one being read is simply the front one of those: there is nothing in front of it but air.
 * The ones already seen lean forward against the front. Flicking is `position` moving, and a
 * sleeve falls forward across the gap because its `d` went from 0 to −1.
 *
 * **Both piles rest on the wood**, which is where every number here comes from. A crate drawn
 * from the middle outward had its last record leaning on nothing; so the back pile is counted
 * from the back wall and the front pile from the front wall, and what is in between is the gap.
 * In a library bigger than the crate — any library — only [BEHIND] and [FRONT] of them are
 * drawn, and the piles slide as they are gone through; at either end of the crate the truth
 * shows, as it does in a real one: the gap is at the front when you start and at the back when
 * you have seen them all.
 *
 * Pure. Distances are along the crate from its middle, toward you positive; a lean is in degrees,
 * positive with the top of the sleeve toward you.
 */
object CrateLayout {

    /** How many already-seen sleeves are drawn leaning forward, and how many standing behind the one being read. */
    const val FRONT = 6
    const val BEHIND = 16

    /** Sleeves in the pool: every one that can be drawn, and one more, so that the one changing what it is is never one in sight. */
    const val POOL = FRONT + BEHIND + 2

    /** Tabbed cards in the pool. More shelves than this in sight at once, and the furthest go without. */
    const val CARDS = 8

    /** The inside of the crate, from its middle to its front or back; and the top edge of its top slat, which is what a pile leans on. */
    const val WALL = VinylDimens.CRATE_D / 2
    const val WALL_TOP = 0.2275f

    const val LEAN_BACK_DEG = 20f
    const val LEAN_FRONT_DEG = 24f

    /** Foot to foot, a sleeve with a record in it among others: six millimetres, which is 3.4 of card and the air in a pile nobody has squared up. */
    const val PITCH_BACK = 0.0062f
    const val PITCH_FRONT = 0.0065f

    /** Where the foot of a sleeve stands when it is leaning on the back of the crate, and on the front. */
    val REST_BACK = -WALL + WALL_TOP * tan(Math.toRadians(LEAN_BACK_DEG.toDouble())).toFloat()
    val REST_FRONT = WALL - WALL_TOP * tan(Math.toRadians(LEAN_FRONT_DEG.toDouble())).toFloat()

    /** How far the record that is on the deck stands out of the crate: it is out, and this says which. */
    const val PROUD_M = 0.030f

    /** How far a sleeve is lifted as it is taken. */
    const val PULL_M = 0.30f

    /** A card's tab: how far it is cut from the middle, by which of three it is. */
    const val TAB_STEP = 0.098f
    fun tabX(ordinal: Int): Float = (Math.floorMod(ordinal, 3) - 1) * TAB_STEP

    class Pose {
        var z = 0f
        var leanDeg = 0f
        var drawn = false
    }

    /** Where the foot of the one being read stands, [position] of the way through [count] things. */
    fun readZ(position: Float, count: Int): Float = REST_BACK + behind(position, count) * PITCH_BACK

    private fun behind(position: Float, count: Int): Float = ((count - 1) - position).coerceIn(0f, BEHIND.toFloat())
    private fun inFront(position: Float): Float = position.coerceIn(0f, FRONT.toFloat())

    /**
     * Where the thing [d] places behind the one being read stands (negative: already seen), with
     * [position] being read out of [count].
     */
    fun pose(d: Float, position: Float, count: Int, out: Pose) {
        val b = behind(position, count)
        val f = inFront(position)
        val read = REST_BACK + b * PITCH_BACK
        // The newest of the ones already seen: the back of the front pile.
        val landed = REST_FRONT - (f - 1f).coerceAtLeast(0f) * PITCH_FRONT
        when {
            d >= 0f -> {
                out.z = REST_BACK + (b - d).coerceAtLeast(0f) * PITCH_BACK
                out.leanDeg = -LEAN_BACK_DEG
            }
            d > -1f -> {
                // Falling forward across the gap. Its foot goes steadily; the fall starts slow and lands soft.
                val t = -d
                out.z = read + (landed - read) * t
                out.leanDeg = -LEAN_BACK_DEG + (LEAN_FRONT_DEG + LEAN_BACK_DEG) * ease(t)
            }
            else -> {
                out.z = REST_FRONT - (f + d).coerceAtLeast(0f) * PITCH_FRONT
                out.leanDeg = LEAN_FRONT_DEG
            }
        }
        // To the wall and no further: one that has reached it is behind the one that got there before.
        out.drawn = d >= -FRONT && d <= BEHIND
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Which thing a sleeve of the pool is, with [position] being read and [count] things in the
     * crate; −1 if it is none of them just now.
     *
     * The pool is a ring: thing `i` is always sleeve `i mod POOL`. So as the crate is gone
     * through, exactly one sleeve changes what it is for each record passed — the one that left
     * one end becomes the one arriving at the other, a whole record's travel before it is drawn —
     * and no sleeve is ever made or thrown away (`ENGINEERING.md`, trap 1: nothing drawn here is
     * ever destroyed).
     */
    fun itemFor(slot: Int, position: Float, count: Int): Int {
        val first = floor(position).toInt() - FRONT
        val i = first + Math.floorMod(slot - first, POOL)
        return if (i in 0 until count) i else -1
    }
}

/**
 * A hand going through the crate: where it is, and how it comes to rest.
 *
 * Dragged, the crate is where the finger is. Let go, it runs on a little by how fast it was
 * going and comes to rest *on* a record — never between two, never on a shelf's card — on a
 * spring that arrives and does not come back. A flick always goes somewhere: the least it does
 * is the next record.
 */
class CrateMotion {

    var position = 0f
        private set
    var target = 0f
        private set
    private var velocity = 0f
    private var held = false
    private var grabbedOn = 0
    private var stops = BooleanArray(0)
    private var first = 0
    private var last = 0

    val count: Int get() = stops.size

    /** The thing nearest the hand. */
    val nearest: Int get() = position.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

    val settled: Boolean get() = !held && position == target && velocity == 0f

    /** A crate's worth of things, [stops] saying which can be rested on, and the hand put at [at] with no journey. */
    fun reset(stops: BooleanArray, at: Int) {
        this.stops = stops
        first = stops.indexOfFirst { it }.coerceAtLeast(0)
        last = stops.indexOfLast { it }.coerceAtLeast(0)
        position = stopNear(at.toFloat(), 0f).toFloat()
        target = position
        velocity = 0f
        held = false
    }

    fun grab() {
        held = true
        velocity = 0f
        grabbedOn = stopNear(target, 0f)
        target = position
    }

    /** The finger has moved [records] records' worth: forwards is deeper into the crate. */
    fun drag(records: Float) {
        if (count == 0) return
        var wanted = position + records
        // Past either end the crate gives a little and no more, as the last record does against the wood.
        if (wanted < first) wanted = first - give(first - wanted)
        if (wanted > last) wanted = last + give(wanted - last)
        position = wanted
        target = position
    }

    /** Let go, going [recordsPerSecond]. */
    fun release(recordsPerSecond: Float) {
        held = false
        if (count == 0) return
        val run = (recordsPerSecond * COAST_SECONDS).coerceIn(-FURTHEST_RUN, FURTHEST_RUN)
        var to = stopNear(position + run, recordsPerSecond)
        // A flick that would have come back to where it started goes on by one instead.
        if (to == grabbedOn && abs(recordsPerSecond) >= FLICK) {
            to = nextStop(grabbedOn, if (recordsPerSecond > 0f) 1 else -1)
        }
        target = to.toFloat()
        // No faster than a spring this stiff can stop in the distance there is: it arrives, and does not pass.
        val most = STIFFNESS * abs(target - position)
        velocity = recordsPerSecond.coerceIn(-most, most)
    }

    /** Go to [index] — or the nearest thing to it that can be rested on — from wherever this is. */
    fun goTo(index: Int) {
        held = false
        if (count == 0) return
        target = stopNear(index.toFloat(), 0f).toFloat()
    }

    /** One thing on ([by] 1) or back (−1) from where this is going. */
    fun nudge(by: Int) {
        if (count == 0) return
        held = false
        target = nextStop(stopNear(target, 0f), by).toFloat()
    }

    fun step(dt: Float) {
        if (held || count == 0) return
        var left = dt.coerceIn(0f, 0.1f)
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            // Critically damped: it arrives, and does not come back.
            velocity += (-STIFFNESS * STIFFNESS * (position - target) - 2f * STIFFNESS * velocity) * h
            position += velocity * h
            left -= h
        }
        if (abs(position - target) < 0.0008f && abs(velocity) < 0.01f) {
            position = target
            velocity = 0f
        }
    }

    /** The stop nearest [at]; exactly between two, or on something that is not one, the way it was [going]. */
    private fun stopNear(at: Float, going: Float): Int {
        if (count == 0) return 0
        val i = at.roundToInt().coerceIn(first, last)
        if (stops[i]) return i
        val on = nextStop(i, if (going >= 0f) 1 else -1)
        return if (stops[on]) on else nextStop(i, if (going >= 0f) -1 else 1)
    }

    /** The next stop from [from] the way [by] points; [from] itself where there is none. */
    private fun nextStop(from: Int, by: Int): Int {
        var i = from + by
        while (i in first..last) {
            if (stops[i]) return i
            i += by
        }
        return from.coerceIn(first, last)
    }

    private fun give(over: Float): Float = MOST_GIVE * (1f - 1f / (1f + over / MOST_GIVE))

    companion object {
        /** How quickly it comes to rest: most of the way in a seventh of a second. */
        const val STIFFNESS = 14f

        /** A flick runs on for this long at the speed it was let go with. */
        const val COAST_SECONDS = 0.22f

        /** However hard it is flicked, no more than this many at once: a crate has weight. */
        const val FURTHEST_RUN = 9f

        /** Faster than this when let go, in records a second, and it was a flick and not a change of mind. */
        const val FLICK = 1.5f

        /** How far past either end a finger can pull it: a third of a record. */
        const val MOST_GIVE = 0.35f
    }
}
