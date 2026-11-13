package com.jawon.app.ui.tapescene

import com.jawon.app.ui.wallscene.WallLayout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * **The racks** (`docs/formats.md` §6): the library as a wall of cassette racks, one tower per
 * shelf in the order the shelves are kept, its tapes lying spine out in the order the shelf holds
 * them, top slot first. A shelf of more than [TapeDimens.RACK_SLOTS] goes on in a second tower,
 * and its card says so.
 *
 * Pure: what stands in which slot, where a hand can take the camera, and which tape a point on a
 * rack's face is. `TapeRackTest` holds it.
 */
object TapeRack {

    class Tape(
        val albumId: String,
        val title: String,
        val artist: String,
        val coverPath: String?,
        /** The spine's colour, if the album has chosen one; otherwise the cover decides, and failing that this. */
        val storedHue: Float,
        val fallbackHue: Float,
        val tower: Int,
        val slot: Int
    )

    class Tower(val shelfId: String, val name: String, val part: Int, val parts: Int, val tapes: List<Tape>) {
        /** "1 OF 2" for a shelf that needs more than one rack; nothing for one that fits. */
        val partLabel: String get() = if (parts > 1) "${part + 1} OF $parts" else ""
    }

    fun towers(layout: WallLayout): List<Tower> {
        if (!layout.loaded) return emptyList()
        val out = ArrayList<Tower>()
        val byRow = layout.casesInWallOrder.groupBy { it.row }
        for (row in layout.rows.sortedBy { it.row }) {
            val cases = byRow[row.row].orEmpty()
            if (cases.isEmpty()) continue
            val chunks = cases.chunked(TapeDimens.RACK_SLOTS)
            chunks.forEachIndexed { part, chunk ->
                val tower = out.size
                val tapes = chunk.mapIndexed { slot, case ->
                    Tape(case.albumId, case.title, case.artist, case.coverPath, case.storedHue, case.fallbackHue, tower, slot)
                }
                out += Tower(row.shelfId, row.name, part, chunks.size, tapes)
            }
        }
        return out
    }

    /** Where [albumId] is: (tower, slot), or null. */
    fun find(towers: List<Tower>, albumId: String?): Pair<Int, Int>? {
        if (albumId == null) return null
        towers.forEachIndexed { t, tower ->
            tower.tapes.forEach { if (it.albumId == albumId) return t to it.slot }
        }
        return null
    }

    /** The middle of a box in [slot], for the camera to look at. */
    fun slotMiddleY(slot: Int): Float = TapeDimens.slotY(slot) + TapeDimens.BOX_T / 2

    /** How low and how high the camera can look along a rack, for a frame [tall] metres tall at its face. */
    fun lookRange(tall: Float): ClosedFloatingPointRange<Float> {
        val low = TapeDimens.RACKS_BOTTOM + tall / 2 - 0.04f
        val high = TapeDimens.RACKS_BOTTOM + TapeDimens.RACK_H + 0.025f - tall / 2
        return if (low <= high) low..high else ((low + high) / 2).let { it..it }
    }

    /**
     * Which tape a point on the plane of the spines is — ([x], [y]) in the room — as (tower, slot),
     * or null between racks, above or below them, or on an empty slot of [towers].
     */
    fun at(towers: List<Tower>, x: Float, y: Float): Pair<Int, Int>? {
        val pitch = TapeDimens.RACK_W + TapeDimens.RACK_GAP
        val tower = (x / pitch).roundToInt()
        if (tower !in towers.indices) return null
        if (abs(x - TapeDimens.rackX(tower)) > TapeDimens.BOX_L / 2) return null
        val fromFoot = y - TapeDimens.RACKS_BOTTOM - TapeDimens.RACK_BOTTOM_BOARD
        if (fromFoot < 0f) return null
        val row = floor(fromFoot / TapeDimens.RACK_PITCH).toInt()
        if (row !in 0 until TapeDimens.RACK_SLOTS) return null
        val slot = TapeDimens.RACK_SLOTS - 1 - row
        return if (towers[tower].tapes.any { it.slot == slot }) tower to slot else null
    }

    /** Which slot the camera's middle is level with, for the ticks as the camera passes them. */
    fun slotAtHeight(y: Float): Int {
        val fromFoot = y - TapeDimens.RACKS_BOTTOM - TapeDimens.RACK_BOTTOM_BOARD
        return TapeDimens.RACK_SLOTS - 1 - floor(fromFoot / TapeDimens.RACK_PITCH).toInt()
    }
}

/**
 * A hand along the racks: up and down a tower with a throw that runs on and slows, and from one
 * tower to the next with a swipe that settles square on it. Pure and frame-driven, as the crate's
 * motion is.
 */
class RackMotion {

    /** Which tower is in front: a fraction while on the way from one to the next. */
    var tower = 0f
        private set
    var towerTarget = 0
        private set
    private var towerVelocity = 0f

    /** Where along the rack the camera looks. */
    var y = 0.55f
        private set
    private var yVelocity = 0f
    private var range = 0.4f..0.7f

    /** A finger is moving it, and it follows exactly. */
    var held = false
        private set

    val settled: Boolean
        get() = !held && abs(tower - towerTarget) < 0.0008f && abs(towerVelocity) < 0.002f && abs(yVelocity) < 0.0005f

    fun setRange(r: ClosedFloatingPointRange<Float>) {
        range = r
        y = y.coerceIn(r.start, r.endInclusive)
    }

    fun place(tower: Int, y: Float) {
        this.tower = tower.toFloat()
        towerTarget = tower
        towerVelocity = 0f
        this.y = y.coerceIn(range.start, range.endInclusive)
        yVelocity = 0f
    }

    fun grab() {
        held = true
        yVelocity = 0f
    }

    /** Moved by a finger: [dy] metres up the rack. */
    fun drag(dy: Float) {
        y = (y + dy).coerceIn(range.start - OVERSCROLL, range.endInclusive + OVERSCROLL)
    }

    /** Let go with the finger moving [velocity] metres a second up the rack. */
    fun fling(velocity: Float) {
        held = false
        yVelocity = velocity.coerceIn(-MAX_THROW, MAX_THROW)
    }

    /** To the next tower or the one before, of [count]. */
    fun swipe(forward: Boolean, count: Int) {
        towerTarget = (towerTarget + if (forward) 1 else -1).coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    fun goTo(tower: Int, y: Float? = null) {
        towerTarget = tower
        if (y != null) yGoal = y.coerceIn(range.start, range.endInclusive)
    }

    private var yGoal: Float? = null

    fun step(dt: Float) {
        if (dt <= 0f) return
        // Square on a tower: a spring that arrives in about a third of a second and does not come back.
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            towerVelocity += (STIFF * STIFF * (towerTarget - tower) - 2f * STIFF * towerVelocity) * h
            tower += towerVelocity * h
            left -= h
        }
        if (abs(tower - towerTarget) < 0.0008f && abs(towerVelocity) < 0.002f) {
            tower = towerTarget.toFloat()
            towerVelocity = 0f
        }
        if (held) return
        val goal = yGoal
        if (goal != null) {
            y += (goal - y) * (1f - exp(-GOAL_EASE * dt))
            if (abs(goal - y) < 0.0005f) {
                y = goal
                yGoal = null
            }
            return
        }
        // A throw, slowing; and past either end, drawn back.
        y += yVelocity * dt
        yVelocity *= exp(-FRICTION * dt)
        if (abs(yVelocity) < 0.0004f) yVelocity = 0f
        val over = when {
            y < range.start -> range.start - y
            y > range.endInclusive -> range.endInclusive - y
            else -> 0f
        }
        if (over != 0f) {
            yVelocity = 0f
            y += over * (1f - exp(-BACK_EASE * dt))
            if (abs(over) < 0.0003f) y += over
        }
        if (sign(yVelocity) == 0f && y in range) yVelocity = 0f
    }

    private companion object {
        const val STIFF = 13f
        const val FRICTION = 4.2f
        const val MAX_THROW = 1.6f
        const val OVERSCROLL = 0.025f
        const val BACK_EASE = 14f
        const val GOAL_EASE = 9f
    }
}
