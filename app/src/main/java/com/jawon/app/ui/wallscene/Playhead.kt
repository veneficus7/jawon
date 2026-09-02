package com.jawon.app.ui.wallscene

/**
 * Deciding what the interpolated playhead should read next.
 *
 * The lyrics page carries the position forward on the frame clock between the player's
 * twice-a-second samples, and that extrapolation and the next real sample rarely agree to the
 * millisecond. This is the rule for reconciling them, and it is subtle enough in exactly one
 * direction to be worth stating on its own and testing.
 */
object Playhead {

    /**
     * How far behind the running value a new reading may be and still be treated as noise.
     *
     * Generous, because the samples are 500ms apart and the extrapolation between them drifts
     * against whatever the player reports; anything inside this is the two disagreeing about
     * the same moment rather than the music having moved.
     */
    const val JITTER_MS = 400L

    /**
     * The next value, given where the smoothing had got to and where the player now says it is.
     *
     * **A small step backwards is jitter; a large one is a fact.** The first version of this
     * refused to go backwards at all, which killed the jitter and also killed the two cases
     * that matter: skipping to another track, and seeking. Both reset the position to something
     * far behind, so the playhead stayed pinned at the old track's time, `lineAt` kept
     * answering with the final line, and the page sat stuck on the last lyric of the previous
     * song — which is exactly what it did.
     *
     * So the rule is not "never backwards" but "not backwards by a little". A jump of more than
     * [JITTER_MS] is taken at face value, because nothing else produces one.
     */
    fun next(current: Long, projected: Long): Long =
        if (current - projected in 0..JITTER_MS) current else projected
}
