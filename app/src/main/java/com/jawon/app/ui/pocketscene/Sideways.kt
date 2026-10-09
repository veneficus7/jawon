package com.jawon.app.ui.pocketscene

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which way the phone is held, as far as the pocket player cares: upright, or turned on its side
 * with its top edge to the [LEFT] (turned anticlockwise) or to the [RIGHT].
 */
enum class Holding { UPRIGHT, LEFT, RIGHT }

/**
 * **Turned on its side** (0.7.3): the iPod nano's own gesture — rotate the player and Cover Flow
 * fills the screen. The activity is held in portrait, because rotating it would rebuild every
 * room, so the turn is read here, from gravity, and the pocket room draws its sideways Cover Flow
 * turned to meet the phone.
 *
 * Pure. Fed gravity in the phone's own axes — x to its right, y up its screen, z out of the
 * glass, reading +g along whichever axis points up — it reports how the phone is held, with the
 * hysteresis a hand needs:
 *
 * - **Into a side** only well past halfway (x beyond [SIDE_IN], y small): a phone tipped a little
 *   while reading is not a phone turned.
 * - **Back upright** only once y is clearly up again ([UPRIGHT_IN]), so a phone held at 45° does
 *   not flicker between the two.
 * - **Lying flat, nothing changes**: with the glass facing the ceiling no edge is up, and a phone
 *   put down on a table should leave the screen as it was.
 * - **Held for [dwellMs]** before it counts, so swinging the phone through sideways on the way
 *   to a pocket does not flash Cover Flow up.
 */
class SidewaysDetector(private val dwellMs: Long = 280L) {

    var holding = Holding.UPRIGHT
        private set

    private var candidate = Holding.UPRIGHT
    private var candidateSince = 0L

    fun update(gx: Float, gy: Float, gz: Float, nowMs: Long): Holding {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 1f) return holding // free fall, or no reading: nothing to go on
        val x = gx / g
        val y = gy / g
        val z = gz / g

        val seen = when {
            abs(z) > FLAT -> holding
            x > SIDE_IN && abs(y) < SIDE_Y_MAX -> Holding.LEFT
            x < -SIDE_IN && abs(y) < SIDE_Y_MAX -> Holding.RIGHT
            y > UPRIGHT_IN -> Holding.UPRIGHT
            else -> holding
        }
        if (seen != candidate) {
            candidate = seen
            candidateSince = nowMs
        }
        if (candidate != holding && nowMs - candidateSince >= dwellMs) holding = candidate
        return holding
    }

    /** Back to upright with nothing pending — on returning to the room. */
    fun reset() {
        holding = Holding.UPRIGHT
        candidate = Holding.UPRIGHT
        candidateSince = 0L
    }

    companion object {
        /** |z| past this — about 55° from upright toward lying flat — and the phone counts as flat. */
        const val FLAT = 0.82f
        /** x past this, about 50° of turn, to go sideways. */
        const val SIDE_IN = 0.77f
        const val SIDE_Y_MAX = 0.55f
        /** y past this, within about 37° of upright, to come back — 45° stays as it was. */
        const val UPRIGHT_IN = 0.80f
    }
}

/**
 * A sideways lean, as a number: −1 with the left edge well down, +1 with the right, from gravity
 * across the phone. What Brick steers by and Cover Flow drifts with — **absolute**, not the
 * reflections' tilt from how it is held ([TiltFilter]), whose neutral follows the phone: a lean
 * held to keep the paddle moving would fade away under it, and coming back level would read as a
 * lean the other way.
 */
fun leanOf(gx: Float, gy: Float, gz: Float): Float {
    val g = sqrt(gx * gx + gy * gy + gz * gz)
    if (g < 1f) return 0f
    // gx reads +g when the right edge is up, so the right edge going down is −gx.
    return (-gx / g / LEAN_FULL).coerceIn(-1f, 1f)
}

/** Sin of the lean that counts as all the way: about 35°. */
private const val LEAN_FULL = 0.57f
