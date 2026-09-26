package com.jawon.app.ui.pocketscene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The click wheel: where a finger is on it, and what it is doing there.
 *
 * Pure — no Android, no Filament — for the reason `Scratch` and `OpenSequence` are: the feel
 * of the device lives in these numbers, and they should be reviewable and testable without one
 * attached. Positions arrive in **millimetres in the wheel's own plane**, centred on the wheel,
 * +x to the right and +y up the face. Turning a touch on the glass into that is the room's job
 * ([unproject] is the arithmetic it uses).
 *
 * The rules are the iPod classic's, from Apple's own user guide, point by point
 * (`docs/formats.md` §4) — the owner has never held one and wants back the experience people
 * remember, so faithful is the brief:
 *
 * - **Turning** moves the highlight one item per step, **clockwise is down**, and a fast spin
 *   through a long list **accelerates**.
 * - **The centre** selects. **MENU**, at the top, goes back; held, it goes to the main menu.
 * - **Play/Pause** at the bottom; held, it switches the player off.
 * - **Next** and **Previous** at the sides; held, they fast-forward and rewind.
 */
object ClickWheel {

    // ---- Geometry. Must agree with `build_pocket()` in tools/models/build_models.py. ----------

    /** The wheel's outer edge — `POCKET_WHEEL_R`. */
    const val WHEEL_R_MM = 19.5f

    /**
     * The centre button's edge and the hairline around it — `POCKET_BUTTON_R` plus
     * `POCKET_BUTTON_GAP`. Inside this a touch is on the button, not the wheel.
     */
    const val BUTTON_R_MM = 8.25f + 0.3f

    /** The wheel's centre on the device, in the device's own frame — `POCKET_WHEEL_Y`. */
    const val CENTRE_Y_M = -0.0225f

    /** The wheel's face: the plate's front, less `POCKET_WHEEL_SINK`. */
    const val FACE_Z_M = 0.00525f - 0.0006f

    /**
     * How far past the rim a touch still counts as the wheel.
     *
     * A real wheel sits in a recess the thumb can feel for. Glass has no edge to find, so a
     * thumb meaning the wheel's rim often lands just beyond it — and the nearest thing out there
     * is anodised plate, which does nothing. Generous, because a miss here reads as a dead wheel.
     */
    const val RIM_SLOP_MM = 4f

    /**
     * Nearer the centre than this, the angle about it is noise.
     *
     * A millimetre of wobble at 2mm from the centre is 30° of "turn". Inside this radius the
     * wheel stops listening to angle and re-anchors when the finger comes back out, so a thumb
     * drifting across the button never scrolls on its way.
     */
    const val TRACK_MIN_R_MM = 4.5f

    // ---- Turning. ---------------------------------------------------------------------------

    /**
     * One item per 15°: 24 a revolution, about 3.7mm of thumb travel at the ring's middle. The
     * classic's own wheel reported finer than this and its firmware grouped the counts into
     * steps of about this size.
     */
    const val STEP_DEG = 15f

    /** A spin slower than this is always one item per step. About a revolution a second. */
    const val ACCEL_FROM_DEG_S = 420f

    /** At this speed and above the acceleration is full. */
    const val ACCEL_FULL_DEG_S = 1400f

    /** Items per step at full speed. */
    const val ACCEL_MAX = 6

    /**
     * Lists shorter than this never accelerate. On a menu of five, "faster" is only ever
     * "overshoot" — the classic accelerated in the song list, not on the main menu.
     */
    const val ACCEL_MIN_ITEMS = 40

    /** How quickly the measured speed follows the finger, in seconds. */
    private const val SPEED_SMOOTHING_S = 0.06f

    // ---- Pressing. --------------------------------------------------------------------------

    /** Further than this from where it landed and a touch is no longer a press. */
    const val TAP_SLOP_MM = 2.5f

    enum class Button { MENU, NEXT, PLAY, PREVIOUS, SELECT }

    /**
     * How long each button has to be held for its long form.
     *
     * Not one number, because the long forms are not alike. Fast-forward should start about as
     * soon as the thumb has clearly stayed; switching the player off should take a deliberate
     * hold, since it is the one that stops the music.
     */
    fun holdMs(button: Button): Long = when (button) {
        Button.NEXT, Button.PREVIOUS -> 450L
        Button.MENU, Button.SELECT -> 700L
        Button.PLAY -> 1500L
    }

    /**
     * Which control a point is on, or null when it is off the wheel altogether.
     *
     * The ring is four quarters about the compass points, which is what the classic's ring
     * was: a single moulding over four switches, so pressing anywhere in a quarter presses the
     * switch under it.
     */
    fun buttonAt(xMm: Float, yMm: Float): Button? {
        val r = hypot(xMm, yMm)
        if (r > WHEEL_R_MM + RIM_SLOP_MM) return null
        if (r < BUTTON_R_MM) return Button.SELECT
        val deg = Math.toDegrees(atan2(yMm, xMm).toDouble())
        return when {
            deg >= 45.0 && deg < 135.0 -> Button.MENU
            deg >= -45.0 && deg < 45.0 -> Button.NEXT
            deg >= -135.0 && deg < -45.0 -> Button.PLAY
            else -> Button.PREVIOUS
        }
    }

    /**
     * How many items one step moves, given how fast the wheel is turning and how long the list
     * is. One, until both are large.
     */
    fun itemsPerStep(degPerSecond: Float, listSize: Int): Int {
        if (listSize < ACCEL_MIN_ITEMS || degPerSecond <= ACCEL_FROM_DEG_S) return 1
        val t = ((degPerSecond - ACCEL_FROM_DEG_S) / (ACCEL_FULL_DEG_S - ACCEL_FROM_DEG_S))
            .coerceIn(0f, 1f)
        return 1 + (t * (ACCEL_MAX - 1)).roundToInt()
    }

    /** Shortest-arc difference between two angles in degrees, wrapped to (-180, 180]. */
    fun wrapDeg(d: Float): Float {
        var x = d
        while (x > 180f) x -= 360f
        while (x <= -180f) x += 360f
        return x
    }

    /**
     * A point on the screen, back into the wheel's plane.
     *
     * The room projects three points of the wheel — its centre, and one [WHEEL_R_MM] along
     * each of its own axes — and hands their screen positions here with the touch. Those two
     * offsets are the columns of a 2×2 map from wheel millimetres to screen pixels, and the
     * touch is solved back through it.
     *
     * Affine rather than a ray cast, which ignores perspective across the wheel: at life size
     * the device is ~30cm from the lens and the wheel's far edge only ~5mm further than its
     * near one, so the error at the rim is under 2% — a third of a millimetre. The platter in
     * the CD room is measured the same way, about a projected centre, for the same reason.
     *
     * Returns null when the three points are degenerate (the wheel seen edge on).
     */
    fun unproject(
        centreX: Float, centreY: Float,
        xAxisX: Float, xAxisY: Float,
        yAxisX: Float, yAxisY: Float,
        touchX: Float, touchY: Float,
        out: FloatArray
    ): Boolean {
        val ax = (xAxisX - centreX) / WHEEL_R_MM
        val ay = (xAxisY - centreY) / WHEEL_R_MM
        val bx = (yAxisX - centreX) / WHEEL_R_MM
        val by = (yAxisY - centreY) / WHEEL_R_MM
        val det = ax * by - bx * ay
        if (abs(det) < 1e-6f) return false
        val dx = touchX - centreX
        val dy = touchY - centreY
        out[0] = (by * dx - bx * dy) / det
        out[1] = (-ay * dx + ax * dy) / det
        return true
    }

    internal fun smoothing(dtS: Float): Float = 1f - exp(-dtS / SPEED_SMOOTHING_S)

    internal const val RAD_TO_DEG = (180.0 / PI).toFloat()
}

/**
 * One finger on the wheel, from landing to lifting: a turn, a press, or a hold.
 *
 * **A touch is not decided when it lands.** A thumb set down on MENU might be pressing it or
 * might be about to turn, and the real wheel told the two apart the same way: turning is
 * movement round the ring, a press is a click that comes with none. So a touch starts as a
 * *candidate* press of whatever it landed on, and stops being one the moment it has turned a
 * step or strayed [ClickWheel.TAP_SLOP_MM] — after which lifting the finger does nothing.
 *
 * Holds need time to pass with nothing happening, which no touch event reports, so the room
 * calls [poll] from its frame loop.
 */
class WheelGesture {

    sealed interface Event {
        /** Turned by [steps]; positive is clockwise, which is down a list. */
        data class Turn(val steps: Int, val degPerSecond: Float) : Event
        data class Press(val button: ClickWheel.Button) : Event
        data class HoldStart(val button: ClickWheel.Button) : Event
        data class HoldEnd(val button: ClickWheel.Button) : Event
    }

    private var active = false
    private var candidate: ClickWheel.Button? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var travel = 0f
    private var lastDeg = 0f
    private var tracking = false
    private var residualDeg = 0f
    private var lastAt = 0L
    private var speed = 0f
    private var turned = false
    private var holding = false

    /** The button being held down past its hold time, or null. */
    val held: ClickWheel.Button?
        get() = if (holding) candidate else null

    /** Whether a finger is on the wheel at all. */
    val isDown: Boolean get() = active

    fun down(xMm: Float, yMm: Float, atMs: Long): List<Event> {
        reset()
        val button = ClickWheel.buttonAt(xMm, yMm) ?: return emptyList()
        active = true
        candidate = button
        downX = xMm
        downY = yMm
        downAt = atMs
        lastAt = atMs
        anchor(xMm, yMm)
        return emptyList()
    }

    fun move(xMm: Float, yMm: Float, atMs: Long): List<Event> {
        if (!active) return emptyList()
        travel = maxOf(travel, hypot(xMm - downX, yMm - downY))
        if (travel > ClickWheel.TAP_SLOP_MM && !holding) candidate = null

        val r = hypot(xMm, yMm)
        if (r < ClickWheel.TRACK_MIN_R_MM) {
            tracking = false
            lastAt = atMs
            return emptyList()
        }
        val deg = atan2(yMm, xMm) * ClickWheel.RAD_TO_DEG
        if (!tracking) {
            anchor(xMm, yMm)
            lastAt = atMs
            return emptyList()
        }
        // Counter-clockwise is a positive angle, and clockwise is down the list — hence the sign.
        val delta = -ClickWheel.wrapDeg(deg - lastDeg)
        lastDeg = deg

        val dtS = (atMs - lastAt).coerceAtLeast(0L) / 1000f
        lastAt = atMs
        if (dtS > 0f) {
            val instant = abs(delta) / dtS
            speed += (instant - speed) * ClickWheel.smoothing(dtS)
        }

        // A held button is being pressed, not turned — the classic's ring could not do both.
        if (holding) return emptyList()

        residualDeg += delta
        var steps = 0
        while (residualDeg >= ClickWheel.STEP_DEG) {
            residualDeg -= ClickWheel.STEP_DEG
            steps++
        }
        while (residualDeg <= -ClickWheel.STEP_DEG) {
            residualDeg += ClickWheel.STEP_DEG
            steps--
        }
        if (steps == 0) return emptyList()
        turned = true
        candidate = null
        return listOf(Event.Turn(steps, speed))
    }

    fun up(xMm: Float, yMm: Float, atMs: Long): List<Event> {
        if (!active) return emptyList()
        val events = move(xMm, yMm, atMs).toMutableList()
        val button = candidate
        when {
            holding && button != null -> events += Event.HoldEnd(button)
            button != null && !turned && travel <= ClickWheel.TAP_SLOP_MM ->
                events += Event.Press(button)
        }
        reset()
        return events
    }

    /** The touch was taken away — by the system, or by the room leaving. */
    fun cancel(): List<Event> {
        val button = candidate
        val events = if (active && holding && button != null) listOf(Event.HoldEnd(button)) else emptyList()
        reset()
        return events
    }

    /** Called every frame: a button that has stayed down long enough becomes held. */
    fun poll(atMs: Long): List<Event> {
        val button = candidate ?: return emptyList()
        if (!active || holding || turned) return emptyList()
        if (atMs - downAt < ClickWheel.holdMs(button)) return emptyList()
        holding = true
        return listOf(Event.HoldStart(button))
    }

    private fun anchor(xMm: Float, yMm: Float) {
        if (hypot(xMm, yMm) < ClickWheel.TRACK_MIN_R_MM) {
            tracking = false
            return
        }
        lastDeg = atan2(yMm, xMm) * ClickWheel.RAD_TO_DEG
        tracking = true
    }

    private fun reset() {
        active = false
        candidate = null
        travel = 0f
        tracking = false
        residualDeg = 0f
        speed = 0f
        turned = false
        holding = false
    }
}
