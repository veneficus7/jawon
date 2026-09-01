package com.jawon.app.ui.wallscene

import kotlin.math.abs

/**
 * How tall the deck panel is, as a fraction of the screen.
 *
 * **Why this had to become adjustable.** Everything in the panel is pinned except the list in the
 * middle, which takes the slack — so every control added to the rack costs the tracklist a row,
 * and by the time the filter, the drive and the echo were all there the equaliser had been pushed
 * off the bottom entirely. A fixed height cannot serve both "watch the record spin" and "show me
 * all twenty-seven tracks"; they are different sizes, and the user is the only one who knows
 * which they want.
 *
 * Three stops rather than free resizing, because a panel that lands anywhere lands somewhere
 * slightly wrong every time. Snapping is also what makes it feel like a physical object, which is
 * the same reason the case's open sequence has beats rather than a slider.
 *
 * Pure, so the snapping can be tested without a device.
 */
enum class DeckDetent(val fraction: Float) {

    /** Almost all record. Title, transport, nothing else — for watching it turn and scratching it. */
    PEEK(0.30f),

    /** The resting state: disc above, tracklist or rack below. */
    DECK(0.575f),

    /** The panel takes the screen. The whole tracklist, or the whole rack. */
    FULL(0.88f);

    companion object {

        /**
         * The detent a sheet released at [fraction] should settle to.
         *
         * Nearest by distance rather than by direction of travel. Flick-to-advance was the
         * alternative and it is worse here: the sheet is dragged by its header, which is a short
         * grab, and a gesture that short reports velocities too noisy to read intent from.
         */
        fun nearestTo(fraction: Float): DeckDetent {
            if (!fraction.isFinite()) return DECK
            return entries.minBy { abs(it.fraction - fraction) }
        }

        /** The smallest and largest a drag may reach, with a little give past each end. */
        val MIN_FRACTION = PEEK.fraction - OVERSHOOT
        val MAX_FRACTION = FULL.fraction + OVERSHOOT

        /**
         * How far past the end stops a drag may go.
         *
         * A sheet that stops dead at its limit feels broken rather than bounded — the finger is
         * still moving and the thing under it is not. A little give, then a snap back, reads as a
         * limit you have reached rather than a gesture that failed.
         */
        private const val OVERSHOOT = 0.03f

        /** Clamp a dragged fraction into the range the sheet allows. */
        fun clamp(fraction: Float): Float {
            if (!fraction.isFinite()) return DECK.fraction
            return fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)
        }
    }
}
