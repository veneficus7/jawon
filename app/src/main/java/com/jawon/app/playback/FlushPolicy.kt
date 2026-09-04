package com.jawon.app.playback

/**
 * Whether an audio processor should throw away its internal state when the pipeline flushes.
 *
 * **The pipeline flushes far more often than anyone assumed, and that was audible.** Media3
 * calls `AudioProcessor.flush` on every media-item transition, not only on a seek: a new
 * `Format` reaches the renderer, the sink takes a pending configuration, drains to end of
 * stream and re-runs `setupAudioProcessors`. Confirmed on a device — two flushes about 600ms
 * ahead of a natural, un-seeked advance from one track to the next.
 *
 * The filter and the echo both cleared themselves there. For the filter that means zeroing the
 * state-variable integrators while the *knob* stays where it was, which is a step discontinuity
 * in the output — a click at every track boundary of an album, audible whenever the filter is
 * off centre. The echo lost its tail at the same moment, cutting a repeat dead mid-album.
 *
 * Neither is what a physical chain does. A record does not stop ringing because the track
 * number changed.
 *
 * ## What distinguishes the two cases
 *
 * `StreamMetadata.periodUid` identifies the timeline period the audio belongs to — in this app,
 * one per track, since the queue is one MediaItem per track.
 *
 * - **The period is unchanged**: the flush is a seek *within* the current track. Whatever the
 *   filter is ringing with belongs to a moment the listener never heard, and letting it decay
 *   into the new position is exactly the click the original code was written to avoid. Reset.
 * - **The period changed**: this is a track transition. Reset it and you get the click this
 *   file exists to remove.
 *
 * A user pressing *next* also changes the period, and is not gapless — but there the audio
 * itself jumps, and a few milliseconds of filter state carried across is inaudible under a
 * discontinuity that large. Carrying state is the safe answer for both kinds of period change;
 * clearing it is only right for the one case where the listener heard neither side.
 */
internal object FlushPolicy {

    /**
     * @param lastPeriodUid the period at the previous flush, or null if there has not been one.
     * @param periodUid the period now being flushed to.
     */
    fun shouldResetState(lastPeriodUid: Any?, periodUid: Any?): Boolean {
        // Nothing has played yet — there is no state worth keeping and a clean start is free.
        if (lastPeriodUid == null) return true
        // Media3 does not always know the period; DEFAULT carries null. Without an identity
        // there is no way to tell a seek from a transition, and the conservative answer is the
        // one that cannot leave stale audio ringing.
        if (periodUid == null) return true
        return lastPeriodUid == periodUid
    }
}
