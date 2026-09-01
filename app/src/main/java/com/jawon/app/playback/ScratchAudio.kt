package com.jawon.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * Hearing a record while your hand is on it.
 *
 * Holding the disc pauses ExoPlayer and always has — a hand on the platter stops the platter.
 * What was missing is what a real turntable does *next*: the record is still under your hand,
 * so dragging it makes a sound. That sound is the whole point of a disc you can grab, and
 * without it a scratch is a silent seek with a nice animation over it.
 *
 * ### Why none of this can be ExoPlayer
 *
 * A player will run at half speed or double on request. It will not run at **minus** one, and
 * backwards is half of scratching. So for as long as the disc is held, a window of audio around
 * the playhead is decoded into memory and read out by hand: a floating cursor stepped by the
 * rate [ScratchTone] computes from the disc's own angular velocity, negative when the finger
 * pulls back, interpolated between samples so the slow drags stay smooth. Positive and
 * fractional gives the chirp, negative gives the growl, and both fall out of one signed number.
 *
 * ### The window, and why it is finite
 *
 * Decoding is not free and scratching has to start *now*, so this takes a few seconds either
 * side of the playhead rather than the whole track. That is not a compromise so much as the
 * shape of the gesture: a scratch worth making covers a second or two. Run the cursor off
 * either end and the output goes quiet rather than clicking, and letting go re-seeks the real
 * player to wherever the disc ended up, so the window never has to be the whole record.
 *
 * ### What it must never do
 *
 * Stall the frame loop. Decoding happens on IO, the write loop is its own thread, and the
 * scene only ever hands over a rate — a plain float, written from the frame callback, read by
 * the audio thread. `@Volatile` rather than a lock, because a scratch writes it sixty times a
 * second and a contended lock in a frame callback is a dropped frame.
 */
@Singleton
class ScratchAudio @Inject constructor() {

    private var track: AudioTrack? = null
    private var pcm: ShortArray = ShortArray(0)
    private var sampleRate = 44_100
    private var channels = 1

    /** Where in [pcm] the window began, so a release can report an absolute position. */
    private var windowStartMs = 0L

    /** Read cursor, in *frames* from the start of the window. Fractional by design. */
    private var cursor = 0.0

    /** What the finger is asking for, and where the smoothing has actually got to. */
    @Volatile private var targetRate = 0f
    private var currentRate = 0f

    @Volatile private var running = false
    private var writer: Thread? = null
    private var prepareJob: Job? = null

    /** Whether there is decoded audio to scratch. False means fall back to silence. */
    val ready: Boolean get() = pcm.isNotEmpty()

    /**
     * Decode a window around [positionMs] of [uri] and open the output.
     *
     * Returns immediately; the audio becomes available when the decode finishes, which is
     * typically well inside the time it takes someone to move their finger. Starting the
     * gesture is never blocked on it.
     */
    fun begin(context: Context, scope: CoroutineScope, uri: Uri, positionMs: Long) {
        end()
        running = true
        targetRate = 0f
        currentRate = 0f
        prepareJob = scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { decodeWindow(context, uri, positionMs) }.getOrNull()
            }
            if (decoded == null || !running) return@launch
            pcm = decoded.samples
            sampleRate = decoded.sampleRate
            channels = decoded.channels
            windowStartMs = decoded.startMs
            cursor = ((positionMs - decoded.startMs) / 1000.0) * sampleRate * channels
            startOutput()

            // **Then widen it, because the first window is not big enough to scratch with.**
            //
            // One revolution of the disc is `Scratch.SEC_PER_REV` seconds of audio — six —
            // so a four-second window runs out inside a single turn, and the sound simply
            // stopped after a couple of spins. It cannot just be decoded wide to begin with:
            // this has to make a noise the instant a finger lands, and half a minute of audio
            // takes long enough to decode that the start of the gesture would be silent.
            //
            // So: a small window to start on, immediately, and a large one that replaces it a
            // moment later. The swap re-bases the cursor onto the new window's origin, and a
            // torn read across it costs at most a click of silence, because `sampleAt` already
            // answers out-of-range with zero rather than a held sample.
            val wide = withContext(Dispatchers.IO) {
                runCatching { decodeWindow(context, uri, positionMs, WIDE_WINDOW_MS) }.getOrNull()
            }
            if (wide == null || !running) return@launch
            val playingMs = windowStartMs + ((cursor / channels) / sampleRate * 1000.0)
            windowStartMs = wide.startMs
            pcm = wide.samples
            cursor = ((playingMs - wide.startMs) / 1000.0) * sampleRate * channels
        }
    }

    /** The disc's angular velocity, in radians per second. Called from the frame loop. */
    fun setAngularVelocity(radiansPerSecond: Float) {
        targetRate = ScratchTone.rateFor(radiansPerSecond)
    }

    /**
     * Stop, and report where the record ended up so the real player can be seeked there.
     *
     * Null when there was nothing decoded, in which case the caller keeps whatever position
     * its own cursor arrived at.
     */
    fun end(): Long? {
        val landed = if (ready) {
            windowStartMs + ((cursor / channels) / sampleRate * 1000.0).toLong()
        } else null

        running = false
        prepareJob?.cancel()
        prepareJob = null

        // **Torn down off the calling thread, deliberately.**
        //
        // This is called from the gesture handler, which is the main thread, and `begin` calls
        // it too — so a scratch started immediately after one ended would pay for the previous
        // teardown before it could start. Joining the writer and releasing an AudioTrack take
        // milliseconds, and milliseconds spent on the main thread inside a touch handler are
        // dropped frames on the exact gesture this feature exists to make feel good.
        //
        // The references are taken first and the fields cleared straight away, so nothing that
        // arrives in the meantime can touch a track that is being released.
        val dying = track
        val dyingWriter = writer
        track = null
        writer = null
        pcm = ShortArray(0)

        Thread({
            runCatching { dyingWriter?.join(200) }
            runCatching {
                dying?.pause()
                dying?.flush()
                dying?.release()
            }
        }, "jawon-scratch-release").start()

        return landed
    }

    // ---- output ----------------------------------------------------------------------

    private fun startOutput() {
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val out = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                // Twice the minimum: one buffer of latency is what makes the sound follow the
                // finger, and less than that underruns into clicks on a busy frame.
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        track = out
        runCatching { out.play() }

        writer = Thread({ writeLoop(out, minBuffer / 2) }, "jawon-scratch").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun writeLoop(out: AudioTrack, chunkSamples: Int) {
        val buffer = ShortArray(chunkSamples.coerceAtLeast(256))
        // Each chunk covers this much time, which is the step the rate smoothing advances by.
        val chunkSeconds = (buffer.size / channels).toFloat() / sampleRate

        while (running) {
            currentRate = ScratchTone.approach(currentRate, targetRate, chunkSeconds)
            val rate = currentRate
            val step = rate.toDouble()

            if (!ScratchTone.audible(rate)) {
                // Held still: silence, not a buzz. See ScratchTone.SILENCE_BELOW.
                java.util.Arrays.fill(buffer, 0)
                cursor += step * buffer.size
            } else {
                var i = 0
                while (i < buffer.size) {
                    // Channels advance together so a stereo image is not torn apart by the
                    // interpolation reading one side a fraction ahead of the other.
                    val frameBase = cursor
                    var c = 0
                    while (c < channels && i < buffer.size) {
                        buffer[i] = ScratchTone.sampleAt(pcm, frameBase + c)
                        i++
                        c++
                    }
                    cursor += step * channels
                }
            }

            val written = runCatching { out.write(buffer, 0, buffer.size) }.getOrDefault(-1)
            if (written < 0) break
        }
    }

    // ---- decoding ----------------------------------------------------------------------

    private class Window(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val startMs: Long
    )

    /**
     * Decode [WINDOW_MS] either side of [positionMs] into interleaved 16-bit PCM.
     *
     * Deliberately a plain `MediaExtractor` + `MediaCodec` loop rather than anything shared
     * with the player: this needs raw samples in an array, which is exactly what a player is
     * built to avoid ever handing out.
     */
    private fun decodeWindow(
        context: Context,
        uri: Uri,
        positionMs: Long,
        halfWindowMs: Long = WINDOW_MS
    ): Window? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)

            val startMs = (positionMs - halfWindowMs).coerceAtLeast(0L)
            val endMs = positionMs + halfWindowMs
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val out = ShortArray(((endMs - startMs) / 1000.0 * rate * ch).toInt() + rate * ch)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd && written < out.size) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0 || extractor.sampleTime > endMs * 1000L) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outBuf: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = min(info.size / 2, out.size - written)
                        shorts.get(out, written, count)
                        written += count
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEnd = true
                    }
                }
            }

            if (written == 0) return null
            return Window(out.copyOf(written), rate, ch, startMs)
        } catch (t: Throwable) {
            // A scratch that cannot decode is a silent scratch, which is what it was before
            // this existed. Never a crash: this runs on a user's own files, of any format,
            // some of which will be broken.
            Log.w(TAG, "scratch decode failed: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "Jawon.Scratch"

        /**
         * How much audio is grabbed either side of the playhead, in milliseconds.
         *
         * Four seconds each way covers any scratch a hand actually makes while staying a
         * decode that finishes in a fraction of a second. Run past it and the sound stops
         * rather than clicking — see [ScratchTone.sampleAt].
         */
        const val WINDOW_MS = 4_000L

        /**
         * The window that replaces it, once there has been time to decode one.
         *
         * Twenty-five seconds either side is four full revolutions of the disc in each
         * direction, which is past the point where anyone is still scratching rather than
         * seeking. Around 9MB of PCM for a stereo 44.1kHz track, held only while a finger is
         * down.
         */
        const val WIDE_WINDOW_MS = 25_000L

        const val TIMEOUT_US = 8_000L
    }
}
