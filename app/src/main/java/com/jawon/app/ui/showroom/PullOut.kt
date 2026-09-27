package com.jawon.app.ui.showroom

import kotlin.math.exp
import kotlin.math.ln

/**
 * **Pinching out of a room** — the way into the showroom (`docs/formats.md` §7, step 5b).
 *
 * A room's own pinch stops at the furthest its camera may stand. Past that, the same pinch keeps
 * going here instead: the room pulls back a little further against a spring, and far enough on,
 * it lets go and the showroom takes over. Short of that, lifting the fingers springs it back and
 * nothing has happened.
 *
 * Fed each pinch sample's own scale factor — the span over the previous span, so below 1 is the
 * fingers closing — for as long as the room can take the pinch no further. [pull] is the log of
 * how far the fingers have closed past that point, so it adds up to the same thing however finely
 * the pinch happens to be sampled, and opening them again gives it back.
 *
 * Pure: the rooms own the camera and the haptic; this only says how far, and when.
 */
class PullOut {

    /** How far past the room's limit the fingers have closed, as a log of the span. */
    var pull = 0f
        private set

    /** Whether it has let go of the room. Once, until [reset]. */
    var left = false
        private set

    private var held = false

    /** How far toward leaving, 0…1: what the room pulls back by. */
    val progress: Float get() = (pull / TRIGGER).coerceIn(0f, 1f)

    /** Whether the pinch is being spent here, not on the room's own zoom. */
    val engaged: Boolean get() = pull > 0f || left

    /** Whether it is anywhere but at rest — the room keeps drawing every frame while it is. */
    val isMoving: Boolean get() = held || (pull > 0f && !left)

    /** A pinch began. */
    fun begin() {
        held = true
    }

    /**
     * One sample of a pinch the room itself can take no further, [scaleFactor] its span over the
     * previous one. True exactly once: on the sample that lets go of the room.
     */
    fun pinch(scaleFactor: Float): Boolean {
        if (left || scaleFactor <= 0f || scaleFactor.isNaN()) return false
        held = true
        pull = (pull - ln(scaleFactor)).coerceAtLeast(0f)
        if (pull < TRIGGER) return false
        pull = TRIGGER
        left = true
        return true
    }

    /** The fingers lifted. */
    fun end() {
        held = false
    }

    /** Springs back while nothing holds it; once it has left, it stays where it went. */
    fun step(dt: Float) {
        if (held || left || pull == 0f || dt <= 0f) return
        pull *= exp(-RETURN_RATE * dt)
        if (pull < REST) pull = 0f
    }

    fun reset() {
        pull = 0f
        left = false
        held = false
    }

    companion object {
        /**
         * The fingers a third closer again, past the room's limit: `−ln(0.7)`. Far enough that a
         * pinch meant for the room's own zoom, overshooting its end a little, does not leave it.
         */
        const val TRIGGER = 0.36f

        /** Springing back, per second: gone in about a third of a second. */
        const val RETURN_RATE = 12f

        private const val REST = 1e-4f
    }
}
