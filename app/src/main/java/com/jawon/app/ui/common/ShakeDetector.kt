package com.jawon.app.ui.common

import kotlin.math.sqrt

/**
 * A deliberate shake, told apart from everything else a phone goes through. **Pure**, fed linear
 * acceleration (gravity removed) in m/s² and a time in milliseconds; `ShakeToSwitch` is the part
 * that listens to the sensor.
 *
 * Shaking flips Jawon to the next room (0.7.2, the owner's call). It has to fire every time it is
 * meant and almost never when it is not, so a shake is three things at once:
 *
 * - **Two jolts**, each over [JOLT] — a hand moving the phone sharply, well past walking (~3 m/s²)
 *   or a bus (~5);
 * - **back and forth**: the second jolt points against the first (their dot product is negative).
 *   Putting a phone down on a table is one hard jolt and a settle the same way; a shake reverses;
 * - **close together**: the second within [WINDOW_MS] of the first, and not inside the same
 *   swing ([MIN_GAP_MS]) — one long jolt sampled twice is not two.
 *
 * After a shake nothing counts for [COOLDOWN_MS], so one vigorous shake is one switch, and a phone
 * being shaken for fun steps through the rooms at a pace the eye can follow.
 */
class ShakeDetector {

    private var firstAt = NONE
    private var firstX = 0f
    private var firstY = 0f
    private var firstZ = 0f
    private var lastShakeAt = NONE

    /** One sample. True exactly when it completes a shake. */
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): Boolean {
        if (lastShakeAt != NONE && atMs - lastShakeAt < COOLDOWN_MS) return false
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < JOLT) return false

        if (firstAt != NONE) {
            val gap = atMs - firstAt
            if (gap > WINDOW_MS) {
                firstAt = NONE // too slow: this jolt starts a new attempt below
            } else if (gap >= MIN_GAP_MS && x * firstX + y * firstY + z * firstZ < 0f) {
                firstAt = NONE
                lastShakeAt = atMs
                return true
            } else {
                return false // the same swing, still going the same way
            }
        }
        firstAt = atMs
        firstX = x
        firstY = y
        firstZ = z
        return false
    }

    /** Forget a half-made shake — the room changed, or the screen went off. */
    fun reset() {
        firstAt = NONE
    }

    companion object {
        const val JOLT = 13f
        const val WINDOW_MS = 450L
        const val MIN_GAP_MS = 70L
        const val COOLDOWN_MS = 1_100L
        private const val NONE = Long.MIN_VALUE
    }
}
