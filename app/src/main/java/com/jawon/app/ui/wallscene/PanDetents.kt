package com.jawon.app.ui.wallscene

import kotlin.math.abs
import kotlin.math.floor

/**
 * One haptic tick per album the wall moves past, in both axes.
 *
 * The owner's ask, and the reasoning behind it is worth keeping: *"one haptic = one album
 * shift, both horizontally and vertically. it makes navigating through library more
 * satisfying."* A crate of records has detents in it — the sleeves themselves — and flipping
 * through one is a sequence of small physical events rather than a smooth slide. The wall had
 * that feel in exactly one place, [CrateFlip]'s rail down the right edge, where it was a
 * separate control you had to find. This puts it on the wall's own pan, which is what
 * everybody actually uses.
 *
 * ## Why this is not simply "tick when the position changes"
 *
 * A pan is continuous and reports about 120 samples a second. Ticking per sample is a buzz,
 * and [CrateFlip] already records what that cost: at 215 albums its first version fired a
 * couple of hundred ticks in a single drag. The unit here is the **cell** — which whole album
 * the wall is nearest — so a tick fires only when that integer changes, at most once per
 * `SLOT_PITCH` of travel however fast or slow the finger moves.
 *
 * Both axes share one instance and one tick: crossing a corner diagonally is one event, not
 * two, because the hand feels one movement.
 */
class PanDetents {

    /** The cell the wall was last seen in. `Int.MIN_VALUE` until the first sample. */
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    /**
     * Records a pan position and returns true if the wall has crossed into a new album.
     *
     * Called from the scroll handler on every touch sample, so it allocates nothing and does
     * no work beyond two divisions and two comparisons.
     */
    fun crossed(panX: Float, panY: Float): Boolean {
        val cellX = cellOf(panX, WallDimens.SLOT_PITCH)
        val cellY = cellOf(panY, WallDimens.ROW_PITCH)
        // The first sample of a gesture establishes where we are and never ticks: a drag that
        // began by ticking would fire on every touch-down, including the ones that turn out
        // to be taps.
        val first = lastX == Int.MIN_VALUE
        val moved = !first && (cellX != lastX || cellY != lastY)
        lastX = cellX
        lastY = cellY
        return moved
    }

    /** Forgets where the wall was, so the next gesture starts silent. See [crossed]. */
    fun reset() {
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
    }

    companion object {

        /**
         * Which cell a position falls in.
         *
         * Rounded rather than floored, so a cell is centred on an album rather than starting
         * at one: the tick then lands as a record passes the middle of the frame, which is
         * where the eye is and where the wall settles (`WallSettle`). Floored, every tick
         * would fire half an album early and the feel would be subtly ahead of the picture.
         */
        fun cellOf(position: Float, pitch: Float): Int =
            floor(position / pitch + 0.5f).toInt()

        /**
         * How far the wall must move before a tick can fire again, as a fraction of a pitch.
         *
         * Not used to gate [crossed] — the cell arithmetic does that — but it is the number
         * that says what the feel costs: at `SLOT_PITCH` 150mm and a frame about 260mm wide,
         * a drag across one screen is about two ticks. Sparse enough to be felt individually,
         * which is the whole point of a detent.
         */
        const val TICKS_PER_FRAME = 1.7f

        /** True if two positions are in different cells. Kept for tests and for callers. */
        fun crosses(from: Float, to: Float, pitch: Float): Boolean =
            cellOf(from, pitch) != cellOf(to, pitch)

        /** How many cells apart two positions are. */
        fun cellsBetween(from: Float, to: Float, pitch: Float): Int =
            abs(cellOf(to, pitch) - cellOf(from, pitch))
    }
}
