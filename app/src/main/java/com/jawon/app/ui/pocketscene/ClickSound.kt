package com.jawon.app.ui.pocketscene

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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The click, as sound: what the classic's little speaker made on every step of the wheel.
 *
 * That click is most of what people mean when they call the wheel satisfying, and it only works
 * if it is *locked* to the step — a click that lags the thumb by a frame reads as a sound effect
 * laid over the wheel rather than the wheel itself. So it is synthesised once, held in a static
 * buffer, and replayed on the low-latency path rather than through anything that decodes or
 * queues.
 *
 * The waveform is pure and tested ([ClickSound]); the player is the thin part that cannot be.
 */
object ClickSound {

    /** Long enough to ring, short enough that 40 steps a second never overlap. */
    const val LENGTH_MS = 12f

    /** Peak level: loud enough to hear over music, a long way short of clipping. */
    const val PEAK = 0.6f

    /**
     * A piezo's click: a hard edge, then two short resonances ringing down.
     *
     * The edge is what makes it a *click* rather than a beep; the 2.9kHz ring is the plastic;
     * the 6.1kHz one is the tick on top that makes it crisp. A low 1.1kHz body keeps it from
     * sounding like a watch. All of it is gone inside 12ms.
     */
    fun render(sampleRateHz: Int): ShortArray {
        val n = (sampleRateHz * LENGTH_MS / 1000f).toInt().coerceAtLeast(16)
        val out = FloatArray(n)
        val dt = 1f / sampleRateHz
        val edgeS = 0.00022f
        for (i in 0 until n) {
            val t = i * dt
            // A raised-cosine pulse: a hard edge with no discontinuity for the DAC to ring on.
            val edge = if (t < edgeS) 0.5f * (1f - cos((2f * PI.toFloat() * t / edgeS))) else 0f
            val ring = 0.55f * exp(-t / 0.0011f) * sin(2f * PI.toFloat() * 2900f * t)
            val tick = 0.32f * exp(-t / 0.00045f) * sin(2f * PI.toFloat() * 6100f * t)
            val body = 0.22f * exp(-t / 0.0022f) * sin(2f * PI.toFloat() * 1100f * t)
            out[i] = edge + ring + tick + body
        }
        // A one-pole high-pass at ~250Hz takes the pulse's DC out, so a click leaves the speaker
        // cone where it found it rather than parked off-centre for a moment.
        val rc = 1f / (2f * PI.toFloat() * 250f)
        val a = rc / (rc + dt)
        var prevIn = 0f
        var prevOut = 0f
        for (i in 0 until n) {
            val x = out[i]
            val y = a * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            out[i] = y
        }
        // The last millisecond fades to silence, so the buffer never ends on a step.
        val fade = (sampleRateHz / 1000).coerceAtMost(n)
        for (k in 0 until fade) {
            out[n - 1 - k] *= k / fade.toFloat()
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val scale = PEAK / peak * Short.MAX_VALUE
        return ShortArray(n) { (out[it] * scale).toInt().coerceIn(-32767, 32767).toShort() }
    }
}

/**
 * Plays [ClickSound] with as little between the touch and the speaker as Android allows.
 *
 * A static buffer at the output's own sample rate, on the low-latency path: no decoding, no
 * resampling, nothing queued ahead of it. Replaying is a rewind of the same buffer.
 *
 * **Sonification, not media.** The click belongs to the device, not to the music: it follows the
 * phone's system-sounds volume and goes quiet in silent mode, where the step's haptic tick
 * carries on alone. Tied to the music's volume instead, turning the volume down on Now Playing
 * would fade out the very clicks that are telling you it is turning.
 *
 * **Never on the main thread.** `play()` is a blocking call into `audioserver`, which starts the
 * output for each click and lets it go when the buffer ends. Measured on the owner's S25 Ultra
 * (2026-10-09, system trace): 3.8ms of the main thread asleep in that call per step with the
 * phone on silent, and with the speaker to wake up it was the 42–67ms freeze that followed every
 * click in the carriage room. So the track is built, rewound and started on a thread of its own
 * at audio priority, and [click] only posts to it. Clicks that arrive while one is still being
 * started collapse into one — a click late by a start is a click, a queue of them is a rattle.
 */
class ClickPlayer(context: Context) : AutoCloseable {

    private val thread = HandlerThread("JawonClick", Process.THREAD_PRIORITY_URGENT_AUDIO)
        .apply { start() }
    private val handler = Handler(thread.looper)

    /** Touched only on [thread]. */
    private var track: AudioTrack? = null

    init {
        val app = context.applicationContext
        handler.post { track = runCatching { build(app) }.getOrNull() }
    }

    fun click() {
        if (handler.hasMessages(CLICK)) return
        handler.sendMessage(Message.obtain(handler, Runnable { play() }).apply { what = CLICK })
    }

    private fun play() {
        val t = track ?: return
        runCatching {
            // A static track rewinds only while stopped or paused.
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
            t.reloadStaticData()
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
        // The output's native rate: anything else is resampled, and a resampled track is
        // refused the fast mixer — which is the whole reason for building it this way.
        val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val pcm = ClickSound.render(rate)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private companion object {
        const val CLICK = 1
    }
}
