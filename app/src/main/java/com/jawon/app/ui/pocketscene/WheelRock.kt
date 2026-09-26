package com.jawon.app.ui.pocketscene

import kotlin.math.exp

/**
 * The wheel moving under a press: the ring rocking toward the side that was pushed, the centre
 * button going down.
 *
 * The classic's ring was one moulding floating over four switches, so pressing MENU tipped the
 * whole ring a fraction of a millimetre toward the top, and the switch under it clicked. That tip
 * is small enough that nobody would name it and large enough that its absence makes the wheel a
 * picture of a wheel. This is it, as springs — pure, so the feel is reviewable in numbers.
 *
 * A tap is a *kick*: the ring goes in and is let back out, because the gesture only knows it was
 * a press once the finger has lifted. A hold stays in until the hold ends.
 */
class WheelRock {

    /** Degrees about the device's own X axis; negative tips the top edge in. */
    var tiltXDeg = 0f
        private set

    /** Degrees about the device's own Y axis; positive tips the right edge in. */
    var tiltYDeg = 0f
        private set

    /** How far the centre button is pressed in, in millimetres. */
    var buttonMm = 0f
        private set

    private var velX = 0f
    private var velY = 0f
    private var velButton = 0f

    private var held: ClickWheel.Button? = null
    private var kicked: ClickWheel.Button? = null
    private var kickLeftS = 0f

    /**
     * How far the whole ring sinks while it tips, in millimetres: rotating about the centre by
     * the tilt and sinking by this much is the same as pivoting on the far edge.
     */
    val sinkMm: Float
        get() = ClickWheel.WHEEL_R_MM *
            Math.toRadians((abs(tiltXDeg) + abs(tiltYDeg)).toDouble()).toFloat()

    /** Whether anything is still moving, so the room can stop writing transforms at rest. */
    val isMoving: Boolean
        get() = held != null || kicked != null ||
            abs(tiltXDeg) > REST || abs(tiltYDeg) > REST || abs(buttonMm) > REST_MM ||
            abs(velX) > REST || abs(velY) > REST || abs(velButton) > REST_MM

    fun tap(button: ClickWheel.Button) {
        kicked = button
        kickLeftS = KICK_S
    }

    fun hold(button: ClickWheel.Button) {
        held = button
    }

    fun release() {
        held = null
    }

    /** Advances the springs by [dtS] seconds. */
    fun step(dtS: Float) {
        if (dtS <= 0f) return
        // Sub-stepped so a dropped frame cannot make a stiff spring overshoot into nonsense.
        var remaining = dtS.coerceAtMost(0.1f)
        while (remaining > 0f) {
            val h = remaining.coerceAtMost(MAX_STEP_S)
            remaining -= h
            advance(h)
        }
    }

    private fun advance(h: Float) {
        if (kicked != null) {
            kickLeftS -= h
            if (kickLeftS <= 0f) kicked = null
        }
        val pressing = held ?: kicked
        val (tx, ty, tb) = targetFor(pressing)
        val (x, vx) = spring(tiltXDeg, velX, tx, h)
        val (y, vy) = spring(tiltYDeg, velY, ty, h)
        val (b, vb) = spring(buttonMm, velButton, tb, h)
        tiltXDeg = x; velX = vx
        tiltYDeg = y; velY = vy
        buttonMm = b; velButton = vb
    }

    /**
     * A critically damped spring, integrated exactly over [h] — no overshoot, and the same path
     * whatever the frame rate, which is what "holds up at 120Hz" asks of motion here.
     */
    private fun spring(x: Float, v: Float, target: Float, h: Float): Pair<Float, Float> {
        val w = OMEGA
        val d = x - target
        val e = exp(-w * h)
        val c = v + w * d
        val nx = target + (d + c * h) * e
        val nv = (v - w * c * h) * e
        return nx to nv
    }

    private fun targetFor(button: ClickWheel.Button?): Triple<Float, Float, Float> = when (button) {
        ClickWheel.Button.MENU -> Triple(-TILT_DEG, 0f, 0f)
        ClickWheel.Button.PLAY -> Triple(TILT_DEG, 0f, 0f)
        ClickWheel.Button.NEXT -> Triple(0f, TILT_DEG, 0f)
        ClickWheel.Button.PREVIOUS -> Triple(0f, -TILT_DEG, 0f)
        ClickWheel.Button.SELECT -> Triple(0f, 0f, BUTTON_TRAVEL_MM)
        null -> Triple(0f, 0f, 0f)
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    companion object {
        /**
         * How far the ring tips: 0.3mm of travel at the pressed rim, about what a dome switch
         * under a moulding gives before it clicks. The ring pivots on its *far* edge — the side
         * opposite the press stays where it was — so this is 0.3mm across the 39mm diameter,
         * and the room sinks the whole ring by half that to keep the far edge level
         * ([sinkMm]).
         */
        const val TILT_DEG = 0.44f

        /** The centre button's travel. */
        const val BUTTON_TRAVEL_MM = 0.25f

        /** How long a tap holds the ring in before letting it go — one quick in-and-out. */
        const val KICK_S = 0.07f

        /** Spring stiffness, rad/s: settles in about 60ms, quick enough to feel like a switch. */
        const val OMEGA = 55f

        private const val MAX_STEP_S = 1f / 240f
        private const val REST = 0.002f
        private const val REST_MM = 0.0005f
    }
}
