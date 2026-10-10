package com.jawon.app.ui.wallscene

import kotlin.math.exp

/**
 * **A room left alone goes still** (0.7.5).
 *
 * The wall's idle life — every case bobbing and turning, the dust drifting, the fog moving — is
 * what makes it a room, and it is also why the wall drew thirty frames a second for as long as it
 * was on screen, touched or not: the owner reported the app "really struggling" on his S25 Ultra,
 * and a wall nobody is looking at was spending the same GPU as one being browsed.
 *
 * So the idle life runs on a clock of its own, and that clock **winds down** when nothing has
 * happened for [settleAfterMs]: its rate eases to zero over about [easeS], the way a turntable
 * spins down, so every case comes to rest where it was rather than snapping to a pose. Anything
 * at all — a touch, a case opening, a removal, the camera still moving — winds it back up the same
 * way. Stopped, nothing in the room changes, and [RoomCadence] draws it at a heartbeat
 * ([RoomCadence.SETTLED_FPS]) instead of thirty.
 *
 * Pure: fed time and activity, it hands back the clock the idle motion should use. Tested in
 * `RoomSettleTest`.
 */
class RoomSettle(
    private val settleAfterMs: Long = SETTLE_AFTER_MS,
    private val easeS: Float = EASE_S
) {
    /** How fast the idle clock runs, 0 (stopped) to 1 (real time). */
    var rate = 1f
        private set

    /** The idle motion's clock: seconds, advancing at [rate]. Never jumps, only slows and speeds. */
    var clock = 0f
        private set

    private var lastActiveAt = Long.MIN_VALUE

    /** True once the idle clock has wound all the way down. */
    val settled: Boolean get() = rate == 0f

    /** Something happened: stay awake, or wake up. */
    fun keepAwake(nowMs: Long) {
        lastActiveAt = nowMs
    }

    /** Advance by [dtS] seconds at [nowMs]. */
    fun step(dtS: Float, nowMs: Long) {
        if (lastActiveAt == Long.MIN_VALUE) lastActiveAt = nowMs
        val awake = nowMs - lastActiveAt < settleAfterMs
        val target = if (awake) 1f else 0f
        val k = 1f - exp(-dtS.coerceAtLeast(0f) / (easeS / 3f))
        rate += (target - rate) * k
        // Snap the last sliver either way, so "settled" and "fully awake" are exact states.
        if (!awake && rate < SNAP) rate = 0f
        if (awake && rate > 1f - SNAP) rate = 1f
        clock += dtS.coerceAtLeast(0f) * rate
    }

    companion object {
        /** Untouched this long, the room starts to wind down. */
        const val SETTLE_AFTER_MS = 20_000L

        /** About how long winding down, or up, takes. */
        const val EASE_S = 1.6f

        private const val SNAP = 0.002f
    }
}
