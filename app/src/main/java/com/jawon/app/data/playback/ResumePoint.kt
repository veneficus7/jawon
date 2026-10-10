package com.jawon.app.data.playback

/**
 * Where a record picks up when it is put back on.
 *
 * A CD player that forgets its place is one nobody trusts, and every way of starting a record —
 * taking it off the wall, the widget, a car, "resume" said out loud — has to agree on what "where
 * it was left" means. So the rule is here, once: the saved track and position, unless that is the
 * last few seconds of the last track. "Resuming" a record you finished means starting it again,
 * not hearing its last breath and stopping.
 *
 * Pure. The wall's view model had this inline until 0.7.6, when the room stopped being the only
 * thing that could start a record.
 */
object ResumePoint {

    /** Within this of the end of the last track, the record counts as finished. */
    const val FINISHED_TAIL_MS = 5_000L

    data class At(val index: Int, val positionMs: Long)

    val TOP = At(0, 0L)

    fun of(lastTrackIndex: Int, lastPositionMs: Long, durationsMs: List<Long>): At {
        if (durationsMs.isEmpty()) return TOP
        val index = lastTrackIndex.coerceIn(0, durationsMs.lastIndex)
        val position = lastPositionMs.coerceAtLeast(0L)
        val finished = index == durationsMs.lastIndex &&
            position > 0L &&
            position >= durationsMs[index] - FINISHED_TAIL_MS
        return if (finished) TOP else At(index, position)
    }
}
