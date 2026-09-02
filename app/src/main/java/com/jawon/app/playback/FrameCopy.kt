package com.jawon.app.playback

import java.nio.ByteBuffer

/**
 * Copy exactly [bytes] from [input] to [output], leaving anything past that for the next call.
 *
 * **Why this is not just `output.put(input)`.** Every processor here sizes its output buffer to
 * a whole number of *frames* — `remaining / (2 * channelCount)`, rounded down — because it reads
 * and writes a frame at a time. `put` copies everything the input has left. The two agree only
 * while every buffer arrives frame-aligned, which is the normal case and therefore the one that
 * gets tested.
 *
 * It stops being the normal case the moment the pitch leaves 1.0. That activates Media3's own
 * time-stretcher upstream, whose output length is a function of the ratio rather than of the
 * input block, so a buffer can arrive carrying a trailing part-frame. `put` then tries to write
 * more bytes than the output buffer holds and throws `BufferOverflowException` — on the audio
 * thread, on every buffer, for the rest of the track.
 *
 * That is the whole bug behind a deck that froze at `0:00 / 0:00` with the playback thread
 * pinned at a full core and the phone hot enough to throttle: measured at 609 jiffies per six
 * seconds against a healthy 11. Playback never recovered because the failure repeated on every
 * attempt to fill the sink.
 *
 * The remainder is deliberately *not* consumed. It is the beginning of a frame whose other half
 * has not arrived; leaving it in place lets the next call complete it, which is what keeps the
 * stereo image from tearing.
 */
internal fun copyWholeFrames(input: ByteBuffer, output: ByteBuffer, bytes: Int) {
    val limit = input.limit()
    input.limit(input.position() + bytes)
    output.put(input)
    input.limit(limit)
    output.flip()
}
