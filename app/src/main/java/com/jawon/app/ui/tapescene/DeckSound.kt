package com.jawon.app.ui.tapescene

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Process
import android.util.Log
import com.jawon.app.playback.RollingDecoder
import com.jawon.app.playback.ScratchEngine
import com.jawon.app.playback.ScratchWindow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **The deck's own sound**: its mechanism ([DeckSounds]), its motor, and the cue — the music going
 * by at many times its speed while REW or FF is held, tiny and high, which is the sound of looking
 * for a song on a tape.
 *
 * One output, on a thread of its own: `AudioTrack.play()` is a blocking call into `audioserver`,
 * and on the main thread it once froze the pocket room for forty milliseconds a click
 * (`ClickPlayer`). The output sleeps when there is nothing to play and is woken by the next thing.
 *
 * ### The cue
 *
 * This is *not* the scratch (`docs/formats.md` §6): the scratch keeps a few seconds round the
 * playhead, and twelve times speed eats a few seconds in a quarter of one. Here a decoder of its
 * own streams ahead **in the direction the tape is going**, a second thread keeping a stretch of
 * some seconds in front of the place, and the output reads it at the tape's speed through the
 * scratch's own clean reader (`ScratchEngine.read`), which averages what goes by rather than
 * skipping it. The deck says where the tape is every frame ([cueAt]); the output keeps its own
 * place between frames and is drawn to the deck's. Only for a file on the phone — and not when
 * memory is short — and where it cannot, the wind is the motor alone, which is what most decks
 * gave you anyway.
 */
class DeckSound(context: Context) : AutoCloseable {

    class Source(val uri: Uri, val durationMs: Long)

    private val appContext = context.applicationContext
    private val pending = ConcurrentLinkedQueue<DeckEvent>()
    private val lock = Object()

    @Volatile private var alive = true
    @Volatile private var awake = false
    @Volatile private var lastWanted = 0L

    // ---- The motor --------------------------------------------------------------------------------

    @Volatile var motorLevel = 0f
    @Volatile var motorRadPerS = 0f

    // ---- The cue ------------------------------------------------------------------------------------

    @Volatile private var sources: List<Source> = emptyList()
    @Volatile private var cueing = false
    @Volatile private var cueTrack = -1
    @Volatile private var cueSeconds = 0.0
    @Volatile private var cueRate = 0.0
    @Volatile private var window: ScratchWindow? = null
    @Volatile private var windowTrack = -1

    /** The level the cue is at, for the meters while it plays: each channel, as the player's are. */
    @Volatile var cueLevelLeft = 0f
        private set
    @Volatile var cueLevelRight = 0f
        private set

    /** The player's own levelling: the cue is the music, and as loud as the music is played. */
    @Volatile var levelling = 1f

    private val output = Thread({ run() }, "JawonDeck").apply { start() }
    private val decoder = Thread({ decode() }, "JawonDeckCue").apply { start() }

    /** Something happened that is heard. */
    fun event(event: DeckEvent) {
        pending.add(event)
        wake()
    }

    /** The album's tracks, for the cue to read from. */
    fun setSources(list: List<Source>) {
        sources = list
    }

    /**
     * Where the tape is and how fast it is going, from the deck, every frame: track [trackIndex],
     * [seconds] into it, moving [rate] times its speed. [on] false and the cue is silent.
     */
    fun cueAt(on: Boolean, trackIndex: Int, seconds: Double, rate: Double) {
        cueTrack = trackIndex
        cueSeconds = seconds
        cueRate = rate
        if (on != cueing) {
            cueing = on
            if (on) {
                wake()
                synchronized(lock) { lock.notifyAll() }
            }
        }
    }

    fun wake() {
        lastWanted = System.nanoTime()
        if (!awake) synchronized(lock) { lock.notifyAll() }
    }

    override fun close() {
        alive = false
        synchronized(lock) { lock.notifyAll() }
    }

    // ---- The output thread --------------------------------------------------------------------------

    private class Voice(val samples: FloatArray) {
        var at = 0
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val track = runCatching { build(rate) }.onFailure { Log.w(TAG, "no output for the deck's sound: $it") }.getOrNull() ?: return
        val shots = HashMap<DeckEvent, FloatArray>()
        val voices = ArrayList<Voice>()
        val motor = DeckSounds.Motor(rate)
        val block = 256
        val out = ShortArray(block * 2)
        var place = -1.0
        var placeTrack = -1
        var gain = 0f
        var squaresL = 0.0
        var squaresR = 0.0
        var counted = 0
        try {
            while (alive) {
                // Asleep until there is something to play.
                val quiet = pending.isEmpty() && voices.isEmpty() && motorLevel <= 0f && !cueing && gain <= 0f
                if (quiet && System.nanoTime() - lastWanted > SLEEP_AFTER_NS) {
                    if (awake) {
                        runCatching { track.pause(); track.flush() }
                        awake = false
                    }
                    synchronized(lock) {
                        if (alive && pending.isEmpty() && !cueing && motorLevel <= 0f) lock.wait(1_000)
                    }
                    continue
                }
                if (!awake) {
                    runCatching { track.play() }
                    awake = true
                }
                while (true) {
                    val e = pending.poll() ?: break
                    voices += Voice(shots.getOrPut(e) { DeckSounds.render(e, rate) })
                }
                val src = window
                val cueOn = cueing && src != null && windowTrack == cueTrack
                for (i in 0 until block) {
                    var m = 0f
                    var v = voices.size - 1
                    while (v >= 0) {
                        val voice = voices[v]
                        m += voice.samples[voice.at++]
                        if (voice.at >= voice.samples.size) voices.removeAt(v)
                        v--
                    }
                    m += motor.next(motorLevel, motorRadPerS)
                    var l = m
                    var r = m
                    // The cue: read where the tape is, at the speed it is going.
                    val speed = abs(cueRate)
                    val wanted = if (cueOn) min(1f, CUE_GAIN * ScratchEngine.loudness(speed) * levelling) else 0f
                    gain += (wanted - gain) * 0.004f
                    if (gain > 0.0005f && src != null) {
                        if (placeTrack != cueTrack || place < 0 || abs(place - cueSeconds) > CUE_JUMP_S) {
                            place = cueSeconds
                            placeTrack = cueTrack
                        }
                        val at = (place - src.startSeconds) * src.sampleRate
                        if (at >= 0 && at < src.frames - 1) {
                            val stride = speed * src.sampleRate / rate
                            val a = ScratchEngine.read(src, at, stride, 0)
                            val b = if (src.channels > 1) ScratchEngine.read(src, at, stride, 1) else a
                            l += a * gain
                            r += b * gain
                            squaresL += (a * a).toDouble()
                            squaresR += (b * b).toDouble()
                        }
                        place += cueRate / rate
                        // Drawn to the deck's own place, a little each sample: the two keep time.
                        place += (cueSeconds - place) * 0.00005
                    }
                    counted++
                    out[2 * i] = (l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    out[2 * i + 1] = (r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                if (counted >= rate / 30) {
                    cueLevelLeft = sqrt(squaresL / counted).toFloat()
                    cueLevelRight = sqrt(squaresR / counted).toFloat()
                    squaresL = 0.0
                    squaresR = 0.0
                    counted = 0
                }
                if (!cueing) place = -1.0
                if (track.write(out, 0, out.size) < 0) break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "the deck's sound gave up: $t")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun build(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, 2048) * 2)
            .build()
    }

    // ---- The cue's decoder --------------------------------------------------------------------------

    private fun decode() {
        var rolling: RollingDecoder? = null
        var rollingTrack = -1
        var idleSince = System.nanoTime()
        try {
            while (alive) {
                if (!cueing) {
                    if (rolling != null && System.nanoTime() - idleSince > DECODER_IDLE_NS) {
                        rolling.close()
                        rolling = null
                        rollingTrack = -1
                        window = null
                        windowTrack = -1
                    }
                    synchronized(lock) { if (alive && !cueing) lock.wait(500) }
                    continue
                }
                idleSince = System.nanoTime()
                val track = cueTrack
                val source = sources.getOrNull(track)
                if (source == null || !onThePhone(source.uri) || headroom() < HEADROOM_BYTES) {
                    Thread.sleep(40)
                    continue
                }
                if (rolling == null || rollingTrack != track) {
                    rolling?.close()
                    rolling = RollingDecoder(appContext, source.uri)
                    rollingTrack = track
                }
                val at = cueSeconds
                val rate = cueRate
                val ahead = (abs(rate) * AHEAD_FOR_S).coerceIn(2.0, MOST_AHEAD_S)
                val has = window
                val covered = has != null && windowTrack == track &&
                    if (rate >= 0) at >= has.startSeconds && at + ahead * 0.5 < has.endSeconds
                    else at <= has.endSeconds && at - ahead * 0.5 > has.startSeconds
                if (!covered && !rolling.failed) {
                    val fresh = if (rate >= 0) rolling.cover(at - 0.2, at + ahead) else rolling.cover(at - ahead, at + 0.2)
                    if (fresh != null) {
                        window = fresh
                        windowTrack = track
                    }
                }
                Thread.sleep(15)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "the cue's decoder gave up: $t")
        } finally {
            rolling?.close()
        }
    }

    private fun onThePhone(uri: Uri): Boolean = uri.scheme == "content" || uri.scheme == "file"

    private fun headroom(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }

    private companion object {
        const val TAG = "Jawon.Tape"
        const val SLEEP_AFTER_NS = 1_500_000_000L
        const val DECODER_IDLE_NS = 4_000_000_000L
        /** The cue is the music going by and quieter than it: a third, before the speed takes more away. */
        const val CUE_GAIN = 0.36f
        /** Further from the deck's place than this, the output goes to it. */
        const val CUE_JUMP_S = 0.6
        /** How far ahead the decoder stays: this many seconds of output, at the tape's speed. */
        const val AHEAD_FOR_S = 0.6
        const val MOST_AHEAD_S = 8.0
        /** Without this much memory to spare, no cue: a wind is the motor alone. */
        const val HEADROOM_BYTES = 12L * 1024 * 1024
    }
}
