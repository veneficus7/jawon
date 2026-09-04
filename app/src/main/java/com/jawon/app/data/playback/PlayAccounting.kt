package com.jawon.app.data.playback

/**
 * How a stretch of listening is turned into a number.
 *
 * **Pulled out of [PlaybackRepository] because this is the arithmetic that decides what the
 * app remembers about you.** Everything downstream of `PlayEvent` reads it: the patina worn
 * into each case on the wall, the listening record, and now the tracklist badges. If a
 * paused track keeps accruing time, or a skipped one is counted as heard, every one of those
 * lies in the same direction and none of them shows the mistake.
 *
 * It was untested for the ordinary reason — it lived inside a class that needs a `MediaController`
 * and a running service, so there was nowhere to stand. None of the *decisions* need either.
 */
object PlayAccounting {

    /**
     * The shortest stretch worth recording.
     *
     * Two seconds is enough to sit through the gap between tracks while deciding, and short
     * enough that genuinely sampling something still counts. It exists so that running a thumb
     * down the tracklist does not write twenty rows claiming an album was played twenty times.
     */
    const val MIN_COUNTED_MS = 2_000L

    /**
     * Whether the stretch in progress has come to an end.
     *
     * Two ways for it to: the track changed under it, or playback stopped. The second is the
     * one that is easy to forget — a stretch also ends when someone pauses and walks away, and
     * waiting for a track transition would silently drop the last track of every session.
     */
    fun closes(hasOpenSegment: Boolean, trackChanged: Boolean, isPlaying: Boolean): Boolean =
        hasOpenSegment && (trackChanged || !isPlaying)

    /**
     * How far into the track the stretch got.
     *
     * **On a track change the previous item ran to its end**, which is not where the player is
     * by the time anyone asks — by then the position belongs to the *next* track and is usually
     * near zero. Reading it there would score every completed track as barely played, which is
     * the exact opposite of the truth and would make NEVER FINISHED appear on an album someone
     * had listened to all the way through.
     *
     * Otherwise it reached wherever the player actually is.
     *
     * @param durationMs the finished track's length, or 0 when the player does not know it.
     */
    fun reachedMs(trackChanged: Boolean, durationMs: Long, positionMs: Long): Long =
        if (trackChanged) durationMs.takeIf { it > 0 } ?: positionMs else positionMs

    /**
     * Milliseconds to record, or null when the stretch does not count.
     *
     * Measured as time *in the track*, not on the clock, which is what keeps a pause from
     * inflating it: the segment remembers where it started in the audio, and this is the
     * distance travelled from there.
     *
     * Negative distances are floored at zero rather than recorded. Scrubbing backwards makes
     * one — the disc is a scrub control, so this is a normal thing to do here rather than an
     * edge case — and a negative `msPlayed` in the database would subtract from a total that
     * every badge and the whole patina scale are computed from.
     */
    fun countedMs(fromPositionMs: Long, reachedMs: Long): Long? {
        val played = (reachedMs - fromPositionMs).coerceAtLeast(0L)
        return played.takeIf { it >= MIN_COUNTED_MS }
    }
}
