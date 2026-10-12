package com.jawon.app.ui.vinylscene

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Process
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * **What a needle sounds like coming down on a record**: the thump of the stylus meeting the
 * vinyl, and the second of surface noise in the lead-in groove before there is any music.
 *
 * Made, not recorded, for the reason the click wheel's click is (`ClickSound`): a sample is a
 * file with somebody else's deck in it, and this is forty lines that are the same on every phone.
 * Three things added together:
 *
 * - **the thump**: a low, heavily damped tone — the cantilever and the arm's own mass ringing
 *   once — with a millisecond of grit on the front of it, which is the stylus finding the groove;
 * - **the crackle**: a few dozen ticks, each a single damped pulse, scattered at random and
 *   thinning out — dust and static, which every record has most of at its edge;
 * - **the hiss** under them: quiet noise with the top taken off, fading.
 *
 * Pure: [render] is arithmetic on a seed, so that what it must not do — clip, click at either
 * end, be louder than the music it is about to give way to — can be held by a test.
 */
object NeedleSound {

    const val SECONDS = 0.95f

    /** Its loudest sample, of full scale: well under the music's, which it is the edge of. */
    const val PEAK = 0.30f

    private const val THUMP_HZ = 62f
    private const val THUMP_TAU_S = 0.040f
    private const val GRIT_TAU_S = 0.0016f
    private const val TICKS = 34
    private const val TICK_TAU_S = 0.00035f
    private const val HISS = 0.012f
    private const val FADE_TAU_S = 0.34f

    fun render(sampleRateHz: Int, seed: Long = 33L): ShortArray {
        val n = (sampleRateHz * SECONDS).toInt()
        val out = FloatArray(n)
        var state = seed
        fun random(): Float {
            // A small generator of its own, so that the same seed is the same sound on any JVM.
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 40).toInt() and 0xFFFF) / 65535f
        }

        // The thump, and the grit on the front of it.
        for (i in 0 until n) {
            val t = i / sampleRateHz.toFloat()
            // Up from nothing in a millisecond and a half: a tone that began at full height would be a click.
            val attack = (t / 0.0015f).coerceAtMost(1f)
            out[i] += 0.62f * attack * exp(-t / THUMP_TAU_S) * sin(2f * PI.toFloat() * THUMP_HZ * t)
            if (t < GRIT_TAU_S * 6f) out[i] += 0.30f * attack * exp(-t / GRIT_TAU_S) * (random() * 2f - 1f)
        }

        // The crackle: ticks, more of them early than late.
        repeat(TICKS) {
            val u = random()
            val at = 0.02f + (SECONDS - 0.10f) * u * u
            val size = (0.05f + 0.22f * random() * random()) * exp(-at / (FADE_TAU_S * 1.6f))
            val sign = if (random() < 0.5f) -1f else 1f
            val start = (at * sampleRateHz).toInt()
            val length = (TICK_TAU_S * 7f * sampleRateHz).toInt()
            for (k in 0 until length) {
                val i = start + k
                if (i >= n) break
                val t = k / sampleRateHz.toFloat()
                out[i] += sign * size * exp(-t / TICK_TAU_S) * (if (k % 2 == 0) 1f else -0.6f)
            }
        }

        // The hiss, with its top taken off by a one-pole filter, and all of it fading.
        var low = 0f
        for (i in 0 until n) {
            val t = i / sampleRateHz.toFloat()
            low += 0.18f * ((random() * 2f - 1f) - low)
            out[i] += HISS * low * exp(-t / FADE_TAU_S) * 3f
        }

        // Out to nothing over the last twentieth of a second, and scaled so that the loudest
        // sample is exactly [PEAK].
        val tail = (0.05f * sampleRateHz).toInt()
        var loudest = 1e-6f
        for (i in 0 until n) {
            if (i >= n - tail) out[i] *= (n - 1 - i) / tail.toFloat()
            loudest = maxOf(loudest, abs(out[i]))
        }
        val gain = PEAK / loudest
        return ShortArray(n) { (out[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }
}

/**
 * Plays [NeedleSound], at the music's own volume — it is the record's sound, and turning the
 * music down turns it down — and never on the main thread: `AudioTrack.play()` is a blocking
 * call into `audioserver` (`ClickPlayer`, and the forty milliseconds it once cost the pocket room).
 */
class NeedlePlayer(context: Context) : AutoCloseable {

    private val thread = HandlerThread("JawonNeedle", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)

    /** Touched only on [thread]. */
    private var track: AudioTrack? = null

    init {
        val app = context.applicationContext
        handler.post { track = runCatching { build(app) }.getOrNull() }
    }

    /** The needle has come down; [level] is how hard, 0..1 — a record put on, or a hop between two tracks. */
    fun drop(level: Float) {
        handler.removeMessages(DROP)
        handler.sendMessage(Message.obtain(handler, Runnable { play(level.coerceIn(0f, 1f)) }).apply { what = DROP })
    }

    private fun play(level: Float) {
        val t = track ?: return
        runCatching {
            // A static track rewinds only while stopped or paused.
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
            t.reloadStaticData()
            t.setVolume(level)
            t.play()
        }
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
        }
        thread.quitSafely()
    }

    private fun build(context: Context): AudioTrack {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val pcm = NeedleSound.render(rate)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private companion object {
        const val DROP = 1
    }
}
