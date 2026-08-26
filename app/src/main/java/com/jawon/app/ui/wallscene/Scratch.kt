package com.jawon.app.ui.wallscene

import kotlin.math.PI
import kotlin.math.abs

/**
 * The platter maths: turning a finger's rotation about the disc into a position in the
 * album.
 *
 * Pure arithmetic, no Filament and no Android types, for the same reason [OpenSequence] is —
 * the feel of the thing lives in these numbers and they should be reviewable without a
 * device attached.
 *
 * The prototype measures the angle in *screen* space about the disc's projected centre
 * rather than casting a ray at the disc, and so do we; see `discAngle()` in
 * `javon-shelf-prototype.html`. All this object cares about is the resulting angle.
 */
object Scratch {

    private const val TWO_PI = (2.0 * PI).toFloat()

    /**
     * Seconds of audio per full revolution of the disc — the prototype's exported
     * `SCRATCH_SEC_REV`. Lower spins more audio past per turn.
     */
    const val SEC_PER_REV = 6.0f

    /** How far the disc must turn between haptic ticks. 12° gives 30 ticks a revolution. */
    const val HAPTIC_STEP_DEG = 12f

    /** A tick closer together than this is a buzz, not a tick. */
    const val HAPTIC_MIN_INTERVAL_MS = 25L

    /** Seeks are throttled to this while dragging; the disc itself is not. */
    const val SEEK_THROTTLE_MS = 80L

    /**
     * How far into the open sequence the disc becomes grabbable — the prototype's
     * `openP >= .86`. Before this the disc is still climbing out of the tray.
     */
    const val ARM_AT = 0.86f

    /**
     * Shortest-arc delta between two absolute angles, in radians, wrapped to (-PI, PI].
     *
     * Without the wrap, dragging across the atan2 discontinuity at ±PI would register as
     * most of a revolution in the wrong direction — an instant jump of about three seconds
     * of audio, every time the finger crossed one particular radius.
     */
    fun angleDelta(from: Float, to: Float): Float {
        var d = to - from
        while (d > PI) d -= TWO_PI
        while (d < -PI) d += TWO_PI
        return d
    }

    /** Audio time covered by turning the disc [radians]. */
    fun radiansToMs(radians: Float): Long =
        (radians / TWO_PI * SEC_PER_REV * 1000f).toLong()

    /** Where the scrub currently sits: which track, and how far into it. */
    data class Cursor(val trackIndex: Int, val offsetMs: Long)

    /**
     * Walks [cursor] by [deltaMs], crossing track boundaries in either direction.
     *
     * **Clamps at the ends of the album rather than wrapping.** The prototype wraps modulo
     * the track count, but its library is generated filler; on a real album, scrubbing off
     * the end of the last track and landing back at the top of track one reads as a bug.
     * Running out of album simply stops, the way a platter runs out of record.
     */
    fun advance(cursor: Cursor, deltaMs: Long, durations: List<Long>): Cursor {
        if (durations.isEmpty()) return cursor

        var index = cursor.trackIndex.coerceIn(0, durations.lastIndex)
        var offset = cursor.offsetMs + deltaMs

        while (offset >= durations[index]) {
            if (index == durations.lastIndex) {
                offset = durations[index]
                break
            }
            offset -= durations[index]
            index++
        }
        while (offset < 0) {
            if (index == 0) {
                offset = 0
                break
            }
            index--
            offset += durations[index]
        }
        return Cursor(index, offset)
    }

    /**
     * Accumulates rotation and reports how many haptic ticks are owed, keeping the
     * remainder so a slow drag still ticks at the same angular spacing as a fast one.
     */
    class HapticAccumulator {
        private var pending = 0f
        private var lastTickAt = 0L

        fun reset() {
            pending = 0f
            lastTickAt = 0L
        }

        /** @return true if a tick should fire now. */
        fun accept(deltaDegrees: Float, nowMs: Long): Boolean {
            pending += abs(deltaDegrees)
            if (pending < HAPTIC_STEP_DEG) return false
            // Collapse rather than queue: a fast flick owes many ticks, and firing them
            // back to back is a buzz. One tick, and the debt is cleared.
            pending = 0f
            if (nowMs - lastTickAt < HAPTIC_MIN_INTERVAL_MS) return false
            lastTickAt = nowMs
            return true
        }
    }
}
