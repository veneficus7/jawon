package com.jawon.app.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * **A record with weight** (0.8) — how it moves under a hand, and when the hand lets go.
 *
 * The scratch used to turn a finger's speed straight into a playback rate, and it sounded like
 * what it was: a sample being resampled. Everything that makes a record under a hand sound like
 * one is in how the *record* moves, which is not how the finger moves:
 *
 * - **It does not stop dead when touched.** A finger lands on a turning record and the record
 *   slips under it for a moment before the grip takes — the music winds down, a tenth of a
 *   second of falling pitch. That is the sound of putting a hand on a record.
 * - **Held, it goes exactly where the hand goes.** Position, not speed: the record is at the place
 *   the finger dragged it to, so the sound and the disc can never drift apart, and a finger held
 *   still is a record held still — silence, where the old engine went on playing at whatever
 *   speed the last touch event reported.
 * - **Let go, the motor takes it back.** The slipmat drags the record up to speed: from a
 *   standstill that is the wind-up, from a shove forward it is the glide back down, and from a
 *   pull backwards it is a backspin — the record still going the wrong way, slowing, turning, and
 *   coming up to pitch.
 *
 * Shared by every room with something to turn: the CD's disc now, the vinyl room's platter next.
 *
 * Pure. Positions are seconds of music and [rate] is 1 at normal speed; [step] is called once per
 * output sample, so everything here is a handful of multiplications.
 */
class Platter {

    /** Where the record is, in seconds of music. Unbounded: off either end of the track is silence. */
    var position = 0.0
        private set

    /** How fast it is turning: 1 is normal speed, negative is backwards. */
    var rate = 0.0
        private set

    enum class Drive {
        /** A hand is on it. */
        HAND,

        /** Nobody is touching it and it should be playing: the motor brings it to speed. */
        MOTOR,

        /** Nobody is touching it and it should be still: it coasts to a stop. */
        COAST
    }

    var drive = Drive.COAST
        private set

    // The hand: where it is and how fast it is going, in the record's own units.
    private var handPosition = 0.0
    private var handRate = 0.0

    /** The record's place relative to the hand's, fixed once the grip has taken. */
    private var anchor = 0.0
    private var gripped = false
    private var slipping = 0.0

    /**
     * The speed the motor turns the record at: 1, unless the deck's tempo control says otherwise.
     *
     * The player can be running fast or slow, and a record let go has to come back up to *that*
     * — wound up to normal speed beside a player at nine tenths of it, the two drift apart
     * faster than anything can close, and the hand-back is a jump.
     */
    var nominal = 1.0
        private set

    /** Puts the record somewhere, turning at [rate], with nobody touching it and the motor set to [nominal]. */
    fun place(position: Double, rate: Double, playing: Boolean, nominal: Double = 1.0) {
        this.position = position
        this.rate = rate
        this.nominal = nominal
        drive = if (playing) Drive.MOTOR else Drive.COAST
    }

    /** A hand lands. The record keeps the speed it had and starts to slip under the fingers. */
    fun grab(handPosition: Double) {
        drive = Drive.HAND
        this.handPosition = handPosition
        handRate = 0.0
        gripped = false
        slipping = 0.0
        anchor = position - handPosition
    }

    /** Where the hand is now, and how fast it is moving. */
    fun hand(position: Double, rate: Double) {
        handPosition = position
        handRate = rate
    }

    /**
     * The hand lets go. With the motor on, the record comes back up to speed; off, it coasts.
     *
     * It leaves the hand no faster than a hand can throw one ([letGoAt]). Under a finger the
     * record can be going many times that — a disc being spun to find a place is geared up, and
     * the record with it — and a record let go at twelve times its speed and left to the mat
     * would fly on for seconds and half a minute of music. Nothing that heavy was ever moving
     * that fast: the speed was the finger's, and it left with the finger.
     */
    fun release(playing: Boolean) {
        rate = letGoAt(rate)
        drive = if (playing) Drive.MOTOR else Drive.COAST
    }

    /**
     * The hand sets the record down rather than letting it go: it is where it was put, and
     * already playing — or already still — with nothing to wind up or run on.
     *
     * For a disc that was being spun to *find a place* (0.7.8). A record thrown is a sound; a
     * record placed is a decision about where the music should be, and the owner's word for how
     * soon it should be there was "instant": "its taking some to exactly continue from the part
     * of the song that it skipped to". Let go at speed, the record ran on past the place for a
     * quarter of a second, or back through it, before it settled — exactly not where it was put.
     */
    fun place(playing: Boolean) {
        rate = if (playing) nominal else 0.0
        drive = if (playing) Drive.MOTOR else Drive.COAST
    }

    /** At speed under the motor: what comes out is the record playing, and nothing else. */
    val atSpeed: Boolean get() = drive == Drive.MOTOR && abs(rate - nominal) < AT_SPEED

    val atRest: Boolean get() = drive == Drive.COAST && abs(rate) < AT_REST

    fun step(dt: Double) {
        when (drive) {
            Drive.HAND -> if (!gripped) slip(dt) else follow(dt)
            Drive.MOTOR -> pull(nominal, MOTOR_ACCEL, dt)
            Drive.COAST -> pull(0.0, COAST_ACCEL, dt)
        }
        position += rate * dt
    }

    /**
     * The first moment of a touch: the record's speed falls onto the hand's, and nothing pulls it
     * back to where the finger landed — the finger slid there with it.
     */
    private fun slip(dt: Double) {
        rate += (handRate - rate) * (1.0 - exp(-dt / SLIP_SECONDS))
        slipping += dt
        if (abs(handRate - rate) < GRIPPED_WITHIN || slipping > SLIP_LONGEST) {
            gripped = true
            // **Held a little ahead of where it is, by exactly its stopping distance.** The grip
            // is a critically damped spring, and one of those set going from its resting place
            // with some speed left overshoots and comes back — here, a record that winds down,
            // dips backwards and returns: a blip at the end of every touch. Started this far
            // short of its rest instead, the same spring only ever closes the gap, and the last
            // of the record's speed dies away without the record ever turning round.
            anchor = position + rate * dt - handPosition + (rate - handRate) / GRIP
        }
    }

    /**
     * Gripped: the record is held to the hand's place by something stiff and well damped —
     * fingertips. Stiff enough to follow a scribble, soft enough that the steps a touchscreen
     * reports a moving finger in (a position every few milliseconds) arrive as a smooth turn
     * rather than as a buzz on the pitch.
     */
    private fun follow(dt: Double) {
        val error = handPosition + anchor - position
        val acceleration = GRIP * GRIP * error + 2.0 * GRIP * (handRate - rate)
        rate += acceleration * dt
    }

    /**
     * Nobody touching it: friction against the mat takes the record toward [target]. A slipping
     * mat pulls with a steady force, so the speed changes at a steady rate — a straight glide in
     * pitch, which is what a record coming up to speed sounds like — and eases in at the end so
     * it arrives rather than snaps.
     */
    private fun pull(target: Double, hardest: Double, dt: Double) {
        val wanted = (target - rate) / LOCK_SECONDS
        rate += wanted.coerceIn(-hardest, hardest) * dt
    }

    /**
     * How long the motor will take to bring the record to speed from here, and how far the record
     * will have travelled by then — so that whatever takes over can be waiting at that place.
     */
    fun settle(): Settled = if (drive == Drive.MOTOR) settleFrom(rate, nominal) else Settled(0.0, 0.0)

    class Settled(val seconds: Double, val travelled: Double)

    companion object {
        /**
         * Whether a record let go at [rate] is being *set down* rather than thrown: the hand was
         * [travelling] — spinning the disc to find a place — or had as good as stopped. A finger
         * that stops and lifts has chosen a place; one that lifts while moving has thrown the
         * record, and what the record does next is the sound it was thrown to make.
         */
        fun setDown(rate: Double, travelling: Boolean, nominal: Double = 1.0): Boolean =
            travelling || abs(rate) < STILL_BELOW * nominal

        /** Slower than a quarter of its playing speed, a record under a lifting finger is not being thrown anywhere. */
        const val STILL_BELOW = 0.25

        /** The speed a record let go at [rate] actually leaves the hand with. */
        fun letGoAt(rate: Double): Double = rate.coerceIn(-LET_GO_FASTEST, LET_GO_FASTEST)

        /**
         * As fast as a record can be thrown, either way: twice its speed. A backspin from there
         * is a fifth of a second of the record turning the wrong way — a flick, heard, and over.
         */
        const val LET_GO_FASTEST = 2.0

        /**
         * [settle], for a record let go at [rate] with the motor running at [nominal] — from any
         * thread, touching nothing.
         */
        fun settleFrom(rate: Double, nominal: Double = 1.0): Settled {
            var r = rate
            var travelled = 0.0
            var seconds = 0.0
            val dt = 0.0005
            while (abs(r - nominal) >= AT_SPEED && seconds < 4.0) {
                r += ((nominal - r) / LOCK_SECONDS).coerceIn(-MOTOR_ACCEL, MOTOR_ACCEL) * dt
                travelled += r * dt
                seconds += dt
            }
            return Settled(seconds, travelled)
        }

        /**
         * How quickly a turning record gives in to a hand that has just landed on it: the time
         * its speed takes to fall most of the way to the hand's. About a tenth of a second from
         * touch to still, all told — long enough to hear the pitch fall, short enough that the
         * record is yours by the time you move.
         */
        const val SLIP_SECONDS = 0.034

        /** The grip has taken once the record is this close to the hand's speed — or after this long regardless. */
        const val GRIPPED_WITHIN = 0.05
        const val SLIP_LONGEST = 0.16

        /**
         * How stiffly a gripped record follows the hand, as an angular frequency: 26 cycles a
         * second. Fast scratching is eight or ten strokes a second, which this follows without
         * lagging; a touchscreen's 60 to 240 reports a second are far above it and are smoothed.
         */
        const val GRIP = 2.0 * PI * 26.0

        /**
         * The slipmat's pull with the motor running, in speeds per second. From a standstill to
         * playing in a tenth of a second.
         *
         * It was 7.5 — a fifth of a second from still, half a second from a hard backspin, which
         * is about what a real deck's motor does — and on the owner's phone that read as the
         * music *taking a while to come back*. A wind-up has to be heard to be a wind-up, and
         * has to be over before anybody is waiting for it: this is a deck with a strong motor.
         */
        const val MOTOR_ACCEL = 14.0

        /** With the motor off there is only friction, and it is gentler: a spun record runs on a little before it stops. */
        const val COAST_ACCEL = 9.0

        /** The ease at the end of either: how long the last of the difference takes to go. */
        const val LOCK_SECONDS = 0.030

        const val AT_SPEED = 0.01
        const val AT_REST = 0.006
    }
}

/**
 * Where a finger is, between the times a touchscreen says so.
 *
 * A screen reports a moving finger a few hundred times a second at best, in batches, a little
 * late; the record is moved forty-eight thousand times a second. So the audio thread asks *this*
 * where the hand is now, and the answer is a glide: from wherever the hand had got to, to the
 * place just reported, in the time the finger took to make that move.
 *
 * **Gliding to each report, not guessing past it.** The other way to fill the gaps is to carry
 * the last report forward at its own speed, which keeps the record a hundredth of a second
 * closer to the finger and was the first thing built. Measured against a scratching hand it was
 * four times rougher: every report corrects the guess, the corrections arrive as kinks in the
 * record's speed, and speed is pitch. Following a move late and exactly beats following it
 * promptly and wrong; and a finger that stops is simply a glide that arrives — nothing to
 * overshoot, nothing to creep back from.
 *
 * Written by the touch thread and read by the audio thread, as one immutable snapshot swapped in
 * whole: no lock, and never half of one report with half of another.
 */
class HandTrack {

    private class Glide(val from: Double, val to: Double, val startNanos: Long, val spanNanos: Long, val reportNanos: Long) {
        fun place(nowNanos: Long): Double {
            if (spanNanos <= 0L) return to
            val through = ((nowNanos - startNanos).toDouble() / spanNanos).coerceIn(0.0, 1.0)
            return from + (to - from) * through
        }

        fun rate(nowNanos: Long): Double {
            if (spanNanos <= 0L || nowNanos < startNanos || nowNanos - startNanos >= spanNanos) return 0.0
            return (to - from) / (spanNanos / 1e9)
        }
    }

    @Volatile
    private var glide = Glide(0.0, 0.0, 0L, 0L, 0L)

    /** A finger has just landed at [position] (seconds of music): there, and not moving. */
    fun land(position: Double, nowNanos: Long) {
        glide = Glide(position, position, nowNanos, 0L, nowNanos)
    }

    /**
     * The screen says the hand was at [position] at [reportNanos]; it is [nowNanos] as this is
     * heard. The glide to it starts now, from wherever the last one had reached, and takes as long
     * as the finger did between the two reports.
     */
    fun move(position: Double, reportNanos: Long, nowNanos: Long) {
        val before = glide
        val took = (reportNanos - before.reportNanos).coerceIn(SHORTEST_NANOS, LONGEST_NANOS)
        glide = Glide(before.place(nowNanos), position, nowNanos, took, reportNanos)
    }

    /** Where the hand is at [nowNanos], written into [out] as place and speed. */
    fun at(nowNanos: Long, out: DoubleArray) {
        val current = glide
        out[0] = current.place(nowNanos)
        out[1] = current.rate(nowNanos).coerceIn(-FASTEST, FASTEST)
    }

    companion object {
        /** A glide is never shorter than this — two reports stamped together are still one small move. */
        const val SHORTEST_NANOS = 2_000_000L

        /** Nor longer: after a pause, the next report is caught up with quickly rather than strolled to. */
        const val LONGEST_NANOS = 24_000_000L

        /** No hand moves a record faster than this, whatever two noisy reports imply. */
        const val FASTEST = 12.0
    }
}
