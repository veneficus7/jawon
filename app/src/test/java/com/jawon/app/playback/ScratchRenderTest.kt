package com.jawon.app.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders a scratch to a `.wav` so it can be *listened to* without a phone.
 *
 * **This exists because the feedback loop was too slow.** The sound can only be judged by ear,
 * the ear belongs to the owner, and every round of tuning otherwise costs a build, an install,
 * a hand on a disc and a message back. Running the real engine offline over a piece of music
 * turns that into a file anyone can play.
 *
 * It drives [ScratchEngine] itself — the same platter, the same needle, the same hand tracking
 * the phone uses — with a hand that is scripted instead of real, reported the way a touchscreen
 * reports one: 120 times a second, a little late. What it stands in for is the player either
 * side of the scratch, which here is simply the music copied across.
 *
 * Skipped unless `JAWON_SCRATCH_DIR` names a folder with a `bed.wav` in it (16-bit PCM;
 * `tools/` has nothing to make one — any music will do). A listening aid, not a correctness
 * check: nothing about it can break a build.
 */
class ScratchRenderTest {

    private val dir: File? = System.getenv("JAWON_SCRATCH_DIR")?.let(::File)

    @Test
    fun `render the scratch to wav for listening`() {
        val bed = dir?.let { File(it, "bed.wav") }
        assumeTrue("no JAWON_SCRATCH_DIR/bed.wav on this machine", bed?.isFile == true)
        val wav = readWav(bed!!) ?: return
        val out = render(wav)
        File(dir, "scratch-new.wav").let {
            writeWav(it, out, wav.sampleRate)
            println("JAWON-SCRATCH wrote ${it.absolutePath} (${it.length() / 1024} KB)")
        }
        val peak = out.maxOf { abs(it.toInt()) }
        assert(peak > 4000) { "the render came out silent (peak $peak)" }
    }

    // ---- the hand ------------------------------------------------------------------------------

    /**
     * A performance: the music plays; a finger lands and holds; slow strokes, faster ones, a
     * scribble; let go and the record winds back up; then a finger lands again, throws the
     * record backwards and lets go mid-throw — a backspin.
     *
     * In revolutions of the disc, as a real finger would move it. Null while nobody is touching.
     */
    private fun handAt(t: Double): Double? = when {
        t < 2.0 -> null
        t < 2.45 -> 0.0
        // Whole strokes only, so the hand never jumps: three slow, five quicker, six fast.
        t < 4.325 -> 0.07 * sin(2 * PI * 1.6 * (t - 2.45))
        t < 5.8875 -> 0.055 * sin(2 * PI * 3.2 * (t - 4.325))
        t < 6.8106 -> 0.03 * sin(2 * PI * 6.5 * (t - 5.8875))
        t < 7.1 -> 0.0
        t < 8.7 -> null
        t < 8.85 -> 0.0
        t < 9.15 -> -0.35 * ((t - 8.85) / 0.3).let { it * it }
        else -> null
    }

    private fun render(wav: Wav): ShortArray {
        val rate = wav.sampleRate
        val seconds = 12.0
        val frames = (seconds * rate).toInt()
        val out = ShortArray(frames * 2)
        val window = ScratchWindow(wav.samples, rate, wav.channels, 0.0)
        val engine = ScratchEngine(rate)
        engine.window = window

        // The player: the music, at its own place, heard at `gate`.
        var playerAt = 0.5
        var gate = 1f
        var gateTarget = 1f
        var playing = true

        var touching = false
        var takeOverAt = -1.0   // when the decode will have finished and the engine takes the record
        var engineOn = false
        var handingBack = false
        var handBackAt = -1.0
        var nextReport = 0.0
        val block = 64
        val buffer = ShortArray(block * 2)

        var frame = 0
        while (frame < frames) {
            val t = frame.toDouble() / rate
            val nanos = (t * 1e9).toLong()
            val hand = handAt(t)

            if (hand != null && !touching) {
                // A finger lands. The music carries on for the moment the decode takes.
                touching = true
                handingBack = false
                if (engineOn) {
                    engine.platter.grab(0.0)
                    engine.hand.land(0.0, nanos)
                    gateTarget = 0f
                    playing = false
                } else {
                    takeOverAt = t + 0.08
                }
                nextReport = t
            }
            if (touching && !engineOn && t >= takeOverAt) {
                engine.platter.place(playerAt, if (playing) 1.0 else 0.0, playing)
                engine.platter.grab(hand?.times(REV_SECONDS) ?: 0.0)
                engine.hand.land(hand?.times(REV_SECONDS) ?: 0.0, nanos)
                engine.fade(1f, 0.02f)
                gateTarget = 0f
                engineOn = true
            }
            if (hand != null && t >= nextReport) {
                // Where the finger was 8ms ago, heard now.
                val then = handAt(t - 0.008) ?: hand
                if (engineOn) engine.hand.move(then * REV_SECONDS, ((t - 0.008) * 1e9).toLong(), nanos)
                nextReport += 1.0 / 120
            }
            if (hand == null && touching) {
                // Let go.
                touching = false
                if (engineOn) {
                    engine.platter.release(playing = true)
                    handingBack = true
                }
            }
            if (handingBack && engine.platter.atSpeed && handBackAt < 0) {
                // The record is back at speed: the player takes over from exactly where it is.
                playerAt = engine.positionSeconds
                playing = true
                gateTarget = 1f
                engine.fade(0f, 0.05f)
                handBackAt = t
            }
            if (handBackAt >= 0 && t > handBackAt + 0.08) {
                engineOn = false
                handingBack = false
                handBackAt = -1.0
            }
            if (gate == 0f && gateTarget == 0f) playing = false

            if (engineOn) engine.render(buffer, block, nanos) else java.util.Arrays.fill(buffer, 0)
            for (i in 0 until block) {
                if (frame + i >= frames) break
                gate += (gateTarget - gate).coerceIn(-1f / (0.02f * rate), 1f / (0.05f * rate))
                var l = buffer[2 * i].toFloat()
                var r = buffer[2 * i + 1].toFloat()
                if (gate > 0f) {
                    val at = (playerAt * rate).toInt()
                    if (at >= 0 && at < window.frames) {
                        l += gate * wav.samples[at * wav.channels]
                        r += gate * wav.samples[at * wav.channels + (wav.channels - 1)]
                    }
                }
                if (playing || gate > 0f) playerAt += 1.0 / rate
                out[2 * (frame + i)] = l.toInt().coerceIn(-32768, 32767).toShort()
                out[2 * (frame + i) + 1] = r.toInt().coerceIn(-32768, 32767).toShort()
            }
            frame += block
        }
        return out
    }

    // ---- the smallest possible wav reader and writer ---------------------------------

    private class Wav(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /** 16-bit PCM only. */
    private fun readWav(file: File): Wav? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 44_100
        var channels = 2
        var bits = 16
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(pos + 10).toInt().coerceIn(1, 2)
                    sampleRate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                }
                "data" -> { dataOffset = pos + 8; dataLength = size }
            }
            if (size <= 0) break
            pos += 8 + size + (size and 1)
        }
        if (dataOffset < 0 || bits != 16) return null
        val count = minOf(dataLength, bytes.size - dataOffset) / 2
        val samples = ShortArray(count)
        val data = ByteBuffer.wrap(bytes, dataOffset, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) samples[i] = data.getShort()
        return Wav(samples, sampleRate, channels)
    }

    private fun writeWav(file: File, stereo: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataBytes = stereo.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataBytes); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16)
        bb.putShort(1); bb.putShort(2); bb.putInt(sampleRate)
        bb.putInt(sampleRate * 4)
        bb.putShort(4); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataBytes)
        for (s in stereo) bb.putShort(s)
        file.writeBytes(bb.array())
    }

    private companion object {
        /** Seconds of music to a turn of the disc — `Scratch.SEC_PER_REV`, as the room will have it. */
        const val REV_SECONDS = 1.8
    }
}
