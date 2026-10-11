package com.jawon.app.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * The last few seconds of a track as they are decoded: added to at one end, let go at the other.
 *
 * What [RollingDecoder] keeps its audio in. Pure, so that the one thing here that can go quietly
 * wrong — a stretch that says it starts somewhere it does not, after audio has been dropped from
 * the front of it — is held by a test rather than by listening for a record that has slipped.
 */
class RollingStretch(val sampleRate: Int, val channels: Int) {

    private var samples = ShortArray(0)
    private var count = 0

    /** Where in the track the first frame held is, in seconds. */
    var startSeconds = 0.0
        private set

    val frames: Int get() = count / channels

    val endSeconds: Double get() = startSeconds + frames.toDouble() / sampleRate

    /** Empty, and beginning at [startSeconds]. */
    fun reset(startSeconds: Double) {
        count = 0
        this.startSeconds = startSeconds
    }

    /** Adds [length] interleaved samples from [from], which follow on from what is held. */
    fun append(from: ShortArray, length: Int) {
        val whole = length - length % channels
        // Grown by what is needed and a second more, never doubled: this settles at a few
        // seconds of audio and stays there, and twice that held for nothing is megabytes.
        if (count + whole > samples.size) samples = samples.copyOf(count + whole + sampleRate * channels)
        System.arraycopy(from, 0, samples, count, whole)
        count += whole
    }

    /**
     * Lets go of what is before [seconds] — in whole frames, and only when there is at least
     * [worthIt] seconds of it to let go: every drop moves everything that is kept, and a playhead
     * creeping forward would otherwise do that many times a second.
     */
    fun dropBefore(seconds: Double, worthIt: Double = 1.0) {
        if (seconds - startSeconds < worthIt) return
        val gone = min(frames, ((seconds - startSeconds) * sampleRate).toInt())
        if (gone <= 0) return
        System.arraycopy(samples, gone * channels, samples, 0, count - gone * channels)
        count -= gone * channels
        startSeconds += gone.toDouble() / sampleRate
    }

    /** What is held, as a stretch of its own that nothing will write to again. */
    fun window(): ScratchWindow = ScratchWindow(samples.copyOf(count), sampleRate, channels, startSeconds)
}

/**
 * **Keeps a few seconds round a moving playhead decoded**, so that a hand finds the record
 * already there.
 *
 * How soon a touch on the disc is answered used to be how long a decoder takes to start: a
 * tenth to a fifth of a second of the music playing on regardless under a finger, which is the
 * difference between a record and a picture of one. A decoder cannot be made to start faster, so
 * this one is simply never stopped — it follows the player a few seconds ahead for as long as the
 * disc can be touched, at the cost of decoding the track a second time while it plays (one or two
 * hundredths of a processor), and the scratch takes the record the instant a finger lands.
 *
 * One file, opened once, read forwards; a seek only when the playhead has moved somewhere this
 * is not. Not thread-safe: it is used by one caller at a time, off the main thread.
 */
class RollingDecoder(private val context: Context, val uri: Uri) {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var stretch: RollingStretch? = null
    private var scratch = ShortArray(0)
    private val info = MediaCodec.BufferInfo()

    private var inputEnded = false
    private var outputEnded = false

    /** What the last stretch handed out reached from and to, so the same one is not handed out twice. */
    private var givenStart = Double.NaN
    private var givenEnd = Double.NaN

    /** Nothing more can be done with this file: it would not open, or broke. */
    var failed = false
        private set

    /**
     * Makes sure what is held reaches from about [fromSeconds] to [untilSeconds], decoding as
     * much more as that takes, and returns it — or null if it could not, or was interrupted, or
     * there is nothing new since the last time (the end of the file).
     */
    fun cover(fromSeconds: Double, untilSeconds: Double): ScratchWindow? {
        if (failed) return null
        try {
            if (codec == null && !open()) return null
            val held = stretch ?: return null
            val from = max(0.0, fromSeconds)
            // Somewhere this is not: behind what is held, or further ahead than is worth decoding through.
            if (held.frames == 0 || from < held.startSeconds - 0.05 || from > held.endSeconds + JUMP_SECONDS) seek(from)
            while (held.endSeconds < untilSeconds && !outputEnded) {
                if (Thread.currentThread().isInterrupted) return null
                step(held)
            }
            held.dropBefore(from)
            if (held.frames == 0 || held.startSeconds == givenStart && held.endSeconds == givenEnd) return null
            givenStart = held.startSeconds
            givenEnd = held.endSeconds
            return held.window()
        } catch (t: Throwable) {
            // Like every decode for the scratch: a file that will not decode is a scratch that
            // waits a moment longer, never a crash.
            Log.w(TAG, "rolling decode failed: ${t.message}")
            failed = true
            close()
            return null
        }
    }

    private fun open(): Boolean {
        val extractor = MediaExtractor().also { this.extractor = it }
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        val format = track?.let { extractor.getTrackFormat(it) }
        val mime = format?.getString(MediaFormat.KEY_MIME)
        if (track == null || format == null || mime == null) {
            failed = true
            close()
            return false
        }
        extractor.selectTrack(track)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
        stretch = RollingStretch(rate, channels)
        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
        return true
    }

    private fun seek(seconds: Double) {
        val extractor = extractor ?: return
        val codec = codec ?: return
        extractor.seekTo((seconds * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputEnded = false
        outputEnded = false
        // Placed by the first of what comes out, when it does — see [step].
        stretch?.reset(-1.0)
    }

    /** Everything the decoder will take, then everything it has; a short wait only if neither. */
    private fun step(held: RollingStretch) {
        val extractor = extractor ?: return
        val codec = codec ?: return
        var busy = false
        while (!inputEnded) {
            val index = codec.dequeueInputBuffer(0L)
            if (index < 0) break
            busy = true
            val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
            // Not the whole file at once: a few buffers, and then see what has come out.
            if (held.endSeconds > 0 && extractor.sampleTime / 1e6 > held.endSeconds + FEED_AHEAD_SECONDS) break
        }
        while (!outputEnded) {
            val index = codec.dequeueOutputBuffer(info, if (busy) 0L else WAIT_US)
            if (index < 0) break
            busy = true
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                // **Placed by the decoder's own stamp**, as the scratch's other decode is: a seek
                // lands on the sync point before the place asked for, which can be a good
                // fraction of a second early, and a record taken from the player has to be the
                // same record.
                if (held.startSeconds < 0) held.reset(info.presentationTimeUs / 1e6)
                val count = info.size / 2
                if (scratch.size < count) scratch = ShortArray(count)
                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, count)
                held.append(scratch, count)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
        }
    }

    fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        stretch = null
    }

    private companion object {
        const val TAG = "Jawon.Scratch"

        /** Further ahead of what is held than this, a seek is cheaper than decoding the way there. */
        const val JUMP_SECONDS = 1.5

        /** How far past what has come out the decoder is fed before its output is looked at again. */
        const val FEED_AHEAD_SECONDS = 1.0

        const val WAIT_US = 2_000L
    }
}
