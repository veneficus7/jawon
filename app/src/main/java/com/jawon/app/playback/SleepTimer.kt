package com.jawon.app.playback

/**
 * Stopping the music at a sensible moment, for falling asleep to.
 *
 * **The default option is "at the end of this record", not a countdown**, and that is the
 * whole reason this is worth building rather than copying. `PLAN.md` §2 makes the album the
 * atomic unit; a twenty-minute timer that cuts a record off two thirds of the way through is
 * exactly the behaviour this app exists to argue against. A record ends when it ends, and the
 * app should wait for that.
 *
 * The countdowns are still there, because sometimes you want the music to stop in half an
 * hour regardless. But they are the alternative, not the shape of the feature.
 *
 * Pure: an enum and the arithmetic for what to show. Whether the timer has expired is decided
 * against a clock the caller owns, so this is testable without waiting.
 */
object SleepTimer {

    /** Off, at the end of the record, or after a fixed stretch. */
    sealed interface Mode {
        data object Off : Mode

        /**
         * Stop when the album finishes.
         *
         * Not "when the queue empties" — an album *is* the queue here, so they are the same
         * thing, and saying it this way is what stops someone later extending it across
         * albums and quietly undoing the point.
         */
        data object EndOfAlbum : Mode

        /** Stop after [minutes], whatever is playing. */
        data class After(val minutes: Int) : Mode
    }

    /** The countdown lengths offered. Familiar numbers; nothing clever. */
    val OFFERED_MINUTES = listOf(15, 30, 45, 60, 90)

    /**
     * Milliseconds left, or `null` when nothing is counting.
     *
     * @param endsAt when the timer fires, in the same clock [now] is read from.
     */
    fun remainingMs(endsAt: Long?, now: Long): Long? {
        if (endsAt == null) return null
        return (endsAt - now).coerceAtLeast(0L)
    }

    /** Whether a countdown set to end at [endsAt] has run out. */
    fun hasExpired(endsAt: Long?, now: Long): Boolean =
        endsAt != null && now >= endsAt

    /**
     * The label for the control, given what is set and how long is left.
     *
     * Rounded *up* to the next minute, so a timer with forty seconds left reads "1 MIN"
     * rather than "0 MIN" — a countdown that sits on zero while music is still playing looks
     * broken, and the last minute is the one someone is most likely to be watching.
     */
    fun label(mode: Mode, remainingMs: Long?): String = when (mode) {
        Mode.Off -> "OFF"
        Mode.EndOfAlbum -> "END OF RECORD"
        is Mode.After -> {
            val left = remainingMs
            if (left == null) "${mode.minutes} MIN"
            else {
                val minutes = ((left + 59_999L) / 60_000L).toInt()
                if (minutes <= 0) "STOPPING" else "$minutes MIN"
            }
        }
    }

    /** When a countdown of [minutes] starting at [now] should fire. */
    fun endsAt(minutes: Int, now: Long): Long = now + minutes * 60_000L
}
