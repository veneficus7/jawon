package com.jawon.app.ui.wallscene

import kotlin.math.roundToInt

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
     * What [moving] was at the end of the previous frame.
     *
     * Exists so the draw decision can be taken at the *top* of a frame rather than the tail.
     * The tail is where it belongs logically — by then the driver has walked every animation
     * and knows the truth — but a decision taken there can only skip the *draw*, never the
     * work: every node transform has already been written by the time it is made.
     *
     * Deciding from the previous frame lets the driver skip the writes too. The cost is that
     * motion starting this frame is noticed one vsync late, which at 120Hz is 8ms and at the
     * slowest cadence 67ms — and neither matters, because the one thing that must never be
     * late is a response to a finger, and [pointerDown] is set by the touch handler *before*
     * the frame runs. It is exact, not a frame behind.
     *
     * Erring toward drawing on purpose: `moving || wasMoving` keeps the room live for one
     * extra frame after everything settles, which costs a single frame and removes any chance
     * of the last frame of an animation being the one that gets skipped.
     */
    var wasMoving = false
        private set

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
        wasMoving = moving
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
        pointerDown || moving || wasMoving -> LIVE
        discTurning -> divisorForFps(TURNING_FPS)
        ambientMotion -> divisorForFps(DRIFTING_FPS)
        else -> divisorForFps(STILL_FPS)
    }

    /**
     * The panel's actual refresh rate, which every interval below is derived from.
     *
     * **This used to be assumed rather than known, and the assumption was 120.** The intervals
     * were written as vsync counts — every second, fourth, eighth — with their reasoning stated
     * in the frame rates those produce *on a 120Hz panel*. On any other panel they silently
     * produced something else: at 60Hz the drifting wall fell from 30fps to 15 and the still
     * room to 7.5, which is visible as judder and was reported as the room simply looking worse.
     * Nothing said the constants had a panel baked into them.
     *
     * Half the phones this app runs on are 60Hz, so that was not a hypothetical — it was the
     * majority case, wrong, since the cadence work shipped.
     *
     * Set once from the display; defaults to 120 so a device that will not report a rate behaves
     * as the test hardware does rather than as something slower.
     */
    var refreshHz: Float = DEFAULT_REFRESH_HZ
        set(value) {
            // A nonsense rate would produce a nonsense divisor, and this arrives from the
            // platform rather than from us.
            field = if (value.isFinite() && value >= 20f) value else DEFAULT_REFRESH_HZ
        }

    /**
     * How many vsyncs to skip to land nearest [fps], never fewer than every frame.
     *
     * Expressed as a target frame rate rather than as a count, because the frame rate is the
     * thing the reasoning is actually about — 30fps is the floor for animation this slow whether
     * the panel runs at 60, 120 or 144.
     */
    fun divisorForFps(fps: Float): Int =
        (refreshHz / fps).roundToInt().coerceAtLeast(LIVE)

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

        /** The rate assumed when the display will not say. The test hardware's. */
        const val DEFAULT_REFRESH_HZ = 120f

        /**
         * Sixty frames a second, whatever the panel runs at.
         *
         * The disc's own rate sets this. At 78°/s a point on the rim moves 0.65° between vsyncs
         * and 1.3° at this interval, which at the size the disc is drawn is well under a pixel
         * of difference in how the rotation reads. Halving it again would put the label's
         * printing at 2.6° a frame, which is where a slow rotation starts to look stepped.
         */
        const val TURNING_FPS = 60f

        /**
         * Thirty frames a second.
         *
         * The wall with nothing open, or a case open and paused. `ShelfHover` bobs and turns on
         * multi-second periods and the dust drifts slower still; 30 is the conventional floor
         * for animation of that speed, and the wall is not being touched when it applies.
         */
        const val DRIFTING_FPS = 30f

        /**
         * Fifteen frames a second.
         *
         * Only reachable with reduced motion on, where the hover, the dust and the disc's idle
         * spin are all switched off and successive frames are *identical*. This is not a frame
         * rate so much as a floor that guarantees anything arriving outside the driver — a cover
         * finishing its decode, a scheme change — reaches the screen within ~67ms without
         * needing an explicit invalidate path.
         */
        const val STILL_FPS = 15f
    }
}
