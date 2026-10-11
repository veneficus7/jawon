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
     * Seconds of music to one turn of the disc: **1.8, a long-playing record's** — 33⅓ turns a
     * minute.
     *
     * It was 6, the prototype's exported `SCRATCH_SEC_REV`, chosen when turning the disc was
     * first of all a way to seek. At 6 a finger moving an ordinary scratch's distance was asking
     * for the music four to ten times too fast, the sound was pinned at its speed limit for the
     * whole stroke, and a scratch was one squeak at one pitch. At a record's own gearing a
     * stroke is a quarter of a second of music at once to three times speed: the pitch rises
     * and falls with the hand, which is what a scratch is.
     *
     * A record's gearing alone would make the disc a slow way to travel through a song — three
     * turns for what one used to cover — so it is the gearing only while the hand is scratching:
     * see [Gearing].
     */
    const val SEC_PER_REV = 1.8f

    /** How rarely the paused player is moved along behind a scratch — see the room's `onMove`. */
    const val PLAYER_SEEK_THROTTLE_MS = 250L

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

    /**
     * Audio time covered by turning the disc [radians] at a record's own gearing — what a turn
     * is worth to a hand that is scratching. See [Gearing] for one that is travelling.
     */
    fun radiansToMs(radians: Float): Long =
        (radians / TWO_PI * SEC_PER_REV * 1000f).toLong()

    /**
     * **What a turn of the disc is worth**: a record's own gearing while the hand goes back and
     * forth, and several times that once it is plainly going somewhere.
     *
     * Two things are done with the disc and they want opposite gearings. Scratching wants a
     * record's — [SEC_PER_REV] — or the sound is pinned at its limit. Finding a place in a song
     * wants far more, or it is turn after turn for half a minute. A hand says which it is doing
     * by how it moves: a scratch turns back within half a turn, every time; a search keeps
     * going round. So the first three quarters of a turn in one direction are a record's, and
     * from there the gearing climbs, smoothly, to [GEAR_TOP] times that by two and a half turns
     * — past the six seconds a turn the disc used to be worth. Turn back, and it is a record
     * again from the first degree.
     *
     * It is an iPod's wheel, and reads the same way: slow is precise, and it gets out of the
     * way when you spin it.
     */
    class Gearing {
        /** Radians turned the same way since the hand last turned back; signed. */
        private var run = 0f

        /** Radians turned the other way since — not yet enough to call a turn back. */
        private var against = 0f

        fun reset() {
            run = 0f
            against = 0f
        }

        /**
         * The hand has been going one way long enough to have left a record's gearing behind: it
         * is finding a place, not scratching. What it lets go of then is *set down* where it is
         * (`Platter.place`), not thrown.
         */
        val travelling: Boolean get() = boost(run) > 1.0

        /** The hand has turned [delta] radians. Returns the seconds of music that was. */
        fun turn(delta: Float): Double {
            if (delta == 0f) return 0.0
            if (run == 0f || (delta > 0f) == (run > 0f)) {
                run += delta
                against = (against - abs(delta)).coerceAtLeast(0f)
            } else {
                // Against the run. A touchscreen reports a spinning finger with the odd step
                // backwards in it, and one of those must not drop the gearing mid-spin — that
                // is a lurch in the speed, and speed is pitch. A few degrees of it is a turn back.
                against += abs(delta)
                if (against > TURNED_BACK_RAD) {
                    run = if (delta > 0f) against else -against
                    against = 0f
                }
            }
            return delta.toDouble() / (2.0 * PI) * SEC_PER_REV * boost(run)
        }
    }

    /** How many times a record's gearing a hand that has gone [run] radians one way has earned. */
    fun boost(run: Float): Double {
        val turns = abs(run) / TWO_PI
        val along = ((turns - GEAR_FROM_TURNS) / (GEAR_FULL_TURNS - GEAR_FROM_TURNS)).coerceIn(0f, 1f)
        val eased = along * along * (3f - 2f * along)
        return 1.0 + (GEAR_TOP - 1.0) * eased
    }

    /** No scratch goes this far round without turning back; a search always does. */
    const val GEAR_FROM_TURNS = 0.75f

    /** By here the disc is fully a way to travel. */
    const val GEAR_FULL_TURNS = 2.5f

    /** Four times a record's: 7.2 seconds a turn, where the disc was 6 before it could scratch. */
    const val GEAR_TOP = 4.0

    /** About three and a half degrees the other way is a hand turning back, not a screen's jitter. */
    const val TURNED_BACK_RAD = 0.06f

    /**
     * How fast the disc is drawn turning, per unit of the record's speed, once a hand has let it
     * go and the motor is bringing it back.
     *
     * Under a finger one turn is [SEC_PER_REV] seconds of music; left to itself the disc idles
     * round much more slowly than that ([idleDegreesPerSecond] — it is a look, not a gearing).
     * A record let go mid-throw has to leave the hand at the speed the hand had it and arrive
     * at its idle turn without a jolt at either end, so this is the hand's gearing while the
     * record is far from normal speed, easing to the idle one as it comes up to pitch.
     */
    fun degreesPerSecondLetGo(rate: Float, idleDegreesPerSecond: Float): Float {
        val handled = 360f / SEC_PER_REV
        val away = (abs(rate - 1f) / LET_GO_BLEND).coerceIn(0f, 1f)
        return idleDegreesPerSecond + (handled - idleDegreesPerSecond) * away
    }

    private const val LET_GO_BLEND = 0.6f

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
