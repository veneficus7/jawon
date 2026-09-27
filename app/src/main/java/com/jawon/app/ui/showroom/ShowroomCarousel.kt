package com.jawon.app.ui.showroom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The camera's place along the showroom's row of plinths (`docs/formats.md` §7, step 5b), as the
 * finger moves it.
 *
 * Held, it follows the finger one to one — stretched against a rubber band past either end, so
 * the row has edges you can feel. Let go, and it springs to a plinth: a flick moves on to the next
 * one at least, however short it was, and a slow drag settles on whichever is nearest. Every
 * plinth crossed is one [ticked], for a haptic detent, the way the CD wall ticks per album.
 *
 * Positions are in plinths — 0 is the first, 1 the next — so the scene multiplies by its own
 * spacing and nothing here knows metres or pixels.
 */
class ShowroomCarousel(private val count: Int, start: Int = 0) {

    var position: Float = start.coerceIn(0, count - 1).toFloat()
        private set

    /** The plinth it is on, or nearest to. */
    val index: Int get() = position.roundToInt().coerceIn(0, count - 1)

    /** Where it is going: the plinth it will settle on. */
    var target: Int = index
        private set

    val isMoving: Boolean
        get() = dragging || abs(target - position) > SETTLED || abs(velocity) > SETTLED

    val isHeld: Boolean get() = dragging

    private var velocity = 0f
    private var dragging = false
    private var dragFrom = 0f
    private var fingerFrom = 0f
    private var pressedOn = index
    private var lastIndex = index
    private var pendingTicks = 0

    /** A finger down, at [finger] plinths along the screen. Catches the row where it is. */
    fun press(finger: Float) {
        dragging = true
        dragFrom = position
        fingerFrom = finger
        velocity = 0f
        pressedOn = index
    }

    /** The finger moved to [finger]: dragging it left carries the row on to the right. */
    fun drag(finger: Float) {
        if (!dragging) return
        position = band(dragFrom - (finger - fingerFrom))
        noteCrossing()
    }

    /** The finger let go, moving at [speed] plinths a second (negative is leftward). */
    fun release(speed: Float) {
        if (!dragging) return
        dragging = false
        velocity = -speed
        // Where the throw would carry it, and the plinth nearest that — but a flick always
        // moves on from the plinth the finger caught it on, however short the throw.
        val thrown = (position - speed * THROW_S).roundToInt()
        target = when {
            speed < -FLICK_SPEED -> maxOf(thrown, pressedOn + 1)
            speed > FLICK_SPEED -> minOf(thrown, pressedOn - 1)
            else -> position.roundToInt()
        }.coerceIn(0, count - 1)
    }

    /** Straight to plinth [i], springing there as from a release. */
    fun goTo(i: Int) {
        dragging = false
        target = i.coerceIn(0, count - 1)
    }

    fun step(dt: Float) {
        if (dragging || dt <= 0f) return
        val steps = (dt / MAX_STEP_S).toInt() + 1
        val h = dt / steps
        repeat(steps) {
            // Critically damped: it arrives without going past, as a heavy thing on a track does.
            val accel = -2f * OMEGA * velocity - OMEGA * OMEGA * (position - target)
            velocity += accel * h
            position += velocity * h
        }
        if (abs(target - position) < SETTLED && abs(velocity) < SETTLED) {
            position = target.toFloat()
            velocity = 0f
        }
        noteCrossing()
    }

    /** Whether a plinth has been crossed since this was last asked — one haptic tick each. */
    fun ticked(): Boolean {
        if (pendingTicks == 0) return false
        pendingTicks--
        return true
    }

    private fun noteCrossing() {
        val now = index
        if (now != lastIndex) {
            pendingTicks += abs(now - lastIndex)
            lastIndex = now
        }
    }

    /** Past either end the row gives, but less and less — never more than [RUBBER] of a plinth. */
    private fun band(x: Float): Float {
        val hi = (count - 1).toFloat()
        return when {
            x < 0f -> -RUBBER * (1f - 1f / (1f + -x / RUBBER))
            x > hi -> hi + RUBBER * (1f - 1f / (1f + (x - hi) / RUBBER))
            else -> x
        }
    }

    companion object {
        /** How far ahead a throw is projected, in seconds of its own speed. */
        const val THROW_S = 0.35f

        /** A release faster than this, in plinths a second, is a flick and moves on. */
        const val FLICK_SPEED = 0.6f

        /** Springing to a plinth: settled to the eye in about half a second, and no bounce. */
        const val OMEGA = 9f

        /** How far past either end the row can be pulled, in plinths. */
        const val RUBBER = 0.3f

        private const val SETTLED = 1e-3f
        private const val MAX_STEP_S = 1f / 240f
    }
}
