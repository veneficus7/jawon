package com.jawon.app.ui.wallscene

/**
 * How often the room actually needs to be **drawn**, as opposed to how often it is asked to be.
 *
 * **Why this exists.** SceneView drives one render per vsync, forever, for as long as the room
 * is on screen. On the test device that is 120 a second — of a scene that, most of the time, is
 * not doing anything a person could see change in 8 milliseconds. Jawon is a *music player*: the
 * state it spends its life in is an album playing with the deck up and nobody touching the glass.
 * In that state the fastest thing in the room is the disc, turning at
 * [OpenSequence.SPIN_DEG_PER_S] — 78°/s, about thirteen revolutions a minute. Everything else is
 * a hover bob and a drift of dust, both of them sinusoids with periods measured in seconds.
 *
 * Drawing that 120 times a second is not detail, it is heat. So the room draws at whatever rate
 * the fastest thing actually on screen justifies, and the frame driver keeps running at full rate
 * regardless — every clock, spring and eased value advances on every vsync exactly as before, so
 * nothing about the room's *timing* changes. Only how often the result is rasterised.
 *
 * **Nothing is throttled while a person is touching it.** A finger down, the open sequence, a
 * fling still settling, a case still carrying a throw — any of those and the room is back at
 * [LIVE] on the very next vsync, so the response to a touch is never more than one frame late.
 * The saving comes entirely from the states where a lower rate is indistinguishable.
 *
 * Pure — no Android, no Filament — so the divisor and the counter can be reasoned about, and
 * tested, without a device. See `RoomCadenceTest`.
 */
class RoomCadence {

    /**
     * Whether a finger is on the glass.
     *
     * Written from the raw touch stream rather than the gesture detector, for the same reason
     * `endScratch` is: `OnGestureListener` has no plain press or release among its nineteen
     * callbacks, and a press that never becomes a gesture — holding the platter to pause — must
     * still count as interaction.
     */
    var pointerDown = false

    /**
     * Raised by the frame driver during a frame when something it moved is *still* moving.
     *
     * A single flag rather than a set of queries because the driver already knows: it has just
     * walked every animation in the room. Reset by [beginFrame], raised anywhere, read once at
     * the end. Deliberately a plain field and not a captured local — capturing a `var` in the
     * slot loop's lambda would allocate a `Ref.BooleanRef` every frame, and that loop is the one
     * place in this file that must not allocate.
     */
    var moving = false

    /**
     * Vsyncs skipped since the last one that drew.
     *
     * Starts unbounded rather than at zero, because before the first frame there *is* no last
     * drawn one — and "zero skipped so far" would make a fresh room sit out the opening gap of
     * whatever cadence it woke up in, which at [STILL] is seven blank frames on the first thing
     * the user sees. It never increments from here: the check below succeeds first, and the
     * only path that increments is the one where it is already below `divisor - 1`.
     */
    private var skipped = Int.MAX_VALUE

    /** Call at the top of every frame, before the driver runs. */
    fun beginFrame() {
        moving = false
    }

    /**
     * How many vsyncs each drawn frame is worth, given what is moving.
     *
     * Ordered by what the eye can actually resolve, not by how much each state costs:
     *
     * - a hand on the glass, or anything still animating, is [LIVE] — no exceptions;
     * - a turning disc sets the floor at [TURNING], because it carries printed artwork and a
     *   coarser step would strobe on the label;
     * - the ambient room — hover, tilt, dust — is slow enough for [DRIFTING];
     * - with reduced motion on, none of those three run at all and the room is genuinely still
     *   between events, so [STILL] is a heartbeat rather than an animation rate.
     *
     * @param discTurning the disc is spinning under its own power: a case is open, playing, and
     *   not being scrubbed.
     * @param ambientMotion the room's idle life is running — false only under reduced motion,
     *   where DESIGN.md §4 drops the rotation, the parallax and the dust.
     */
    fun divisorFor(discTurning: Boolean, ambientMotion: Boolean): Int = when {
        pointerDown || moving -> LIVE
        discTurning -> TURNING
        ambientMotion -> DRIFTING
        else -> STILL
    }

    /**
     * Whether this vsync should be drawn, at [divisor].
     *
     * **A drop in the divisor takes effect immediately**, never after a wait: the counter is a
     * count of frames already skipped, so widening the interval extends the current gap while
     * narrowing it ends the gap now. That is what keeps the first frame after a touch on time
     * however long the room had been idling.
     */
    fun shouldDraw(divisor: Int): Boolean {
        if (skipped >= divisor - 1) {
            skipped = 0
            return true
        }
        skipped++
        return false
    }

    companion object {

        /** Every vsync. Anything a hand is driving, and every animation. */
        const val LIVE = 1

        /**
         * Every second vsync — 60fps on a 120Hz panel.
         *
         * The disc's own rate sets this. At 78°/s a point on the rim moves 0.65° between vsyncs
         * and 1.3° at this interval, which at the size the disc is drawn is well under a pixel
         * of difference in how the rotation reads. Halving it again would put the label's
         * printing at 2.6° a frame, which is where a slow rotation starts to look stepped.
         */
        const val TURNING = 2

        /**
         * Every fourth vsync — 30fps.
         *
         * The wall with nothing open, or a case open and paused. `ShelfHover` bobs and turns on
         * multi-second periods and the dust drifts slower still; 30 is the conventional floor
         * for animation of that speed, and the wall is not being touched when it applies.
         */
        const val DRIFTING = 4

        /**
         * Every eighth vsync — 15fps.
         *
         * Only reachable with reduced motion on, where the hover, the dust and the disc's idle
         * spin are all switched off and successive frames are *identical*. This is not a frame
         * rate so much as a floor that guarantees anything arriving outside the driver — a cover
         * finishing its decode, a scheme change — reaches the screen within ~67ms without
         * needing an explicit invalidate path.
         */
        const val STILL = 8
    }
}
