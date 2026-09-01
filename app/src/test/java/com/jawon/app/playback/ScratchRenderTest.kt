package com.jawon.app.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders a scratch to a `.wav` so it can be *listened to* without a phone.
 *
 * **This exists because the feedback loop was too slow.** The sound can only be judged by ear,
 * the ear belongs to the owner, and every round of tuning otherwise costs a build, an install,
 * a hand on a disc and a message back. Running the real signal chain offline over a real file
 * turns that into something anyone can play.
 *
 * It renders through [ScratchTone] itself — the same rate curve, gain tilt, tone filter and
 * saturator the audio thread uses — so what comes out is what the app does, not an
 * approximation of it. The only thing simulated is the hand.
 *
 * Skipped rather than failed when there is no source audio to work from, because this is a
 * listening aid and not a correctness check: nothing about it should be able to break a build.
 */
class ScratchRenderTest {

    /** Where the renders land. Outside the repo — these are listening material, not assets. */
    private val outDir = File(
        System.getProperty("jawon.scratch.out")
            ?: System.getProperty("java.io.tmpdir") + "/jawon-scratch-render"
    )

    private val source = File("C:/Windows/Media/Ring05.wav")

    @Test
    fun `render a scratch to wav for listening`() {
        assumeTrue("no source audio on this machine", source.isFile)

        val wav = readWav(source) ?: return

        val plain = renderScratch(wav, withFader = false)
        File(outDir, "scratch-demo.wav").let {
            writeWav(it, plain, wav.sampleRate, wav.channels)
            println("JAWON-SCRATCH wrote ${it.absolutePath} (${it.length() / 1024} KB)")
        }

        // The same movement with the other hand on the fader. Worth rendering as a pair:
        // the difference between them is the entire argument for having a crossfader, and
        // it is far easier to hear than to describe.
        val cut = renderScratch(wav, withFader = true)
        File(outDir, "scratch-demo-fader.wav").let {
            writeWav(it, cut, wav.sampleRate, wav.channels)
            println("JAWON-SCRATCH wrote ${it.absolutePath} (${it.length() / 1024} KB)")
        }
    }

    /**
     * The fader hand: a transform through the slow drags, then a chirp on each turnaround.
     *
     * Returns fader position, 0 shut and 1 open — the same units the strip on the deck hands
     * to the audio engine, so this is the real curve rather than a mute.
     */
    private fun faderAt(seconds: Float): Float = when {
        seconds < 0.8f -> 1f
        // Transform: taps open against a moving record.
        seconds < 2.6f -> if ((seconds * 7f).toInt() % 2 == 0) 1f else 0f
        // Chirps: shut through each turnaround, open across the middle of the movement.
        seconds < 4.4f -> if (abs(sin((seconds - 2.6f) * 9.0f)) > 0.45f) 1f else 0f
        seconds < 5.0f -> 1f
        // Crab: fast clicks over the closing flurry.
        else -> if ((seconds * 19f).toInt() % 2 == 0) 1f else 0f
    }

    // ---- the hand -------------------------------------------------------------------

    /**
     * A plausible scratch, as a function of time: some slow drags, then faster back-and-forth,
     * then a hard shove.
     *
     * Deliberately a *movement*, not a rate. The hand moves the record and the rate follows,
     * exactly as it does on the device — so the smoothing, the gain tilt and the drive all see
     * the same shape of input they would from a finger.
     */
    private fun handVelocityAt(seconds: Float): Float {
        val revPerSec = 1f / com.jawon.app.ui.wallscene.Scratch.SEC_PER_REV
        val normal = revPerSec * 2f * Math.PI.toFloat()
        return when {
            // Settle: the record runs forward at normal speed.
            seconds < 0.8f -> normal
            // Two slow drags back and forth.
            seconds < 2.6f -> normal * 1.6f * sin((seconds - 0.8f) * 3.4f)
            // Faster, shorter — where the drive and the tone filter start to bite.
            seconds < 4.4f -> normal * 2.2f * sin((seconds - 2.6f) * 9.0f)
            // A hard shove forward, into the ceiling.
            seconds < 5.0f -> normal * 3.5f
            else -> normal * 2.6f * sin((seconds - 5.0f) * 14.0f)
        }
    }

    private fun renderScratch(wav: Wav, withFader: Boolean = false): ShortArray {
        val seconds = 6.5f
        val frames = (seconds * wav.sampleRate).toInt()
        val out = ShortArray(frames * wav.channels)
        val toneState = FloatArray(wav.channels)

        // Start a little way in, so the render does not open on silence.
        var cursor = (wav.sampleRate * wav.channels * 0.5).toDouble()
        var rate = 0f
        val dt = 1f / wav.sampleRate
        var cut = Crossfader.RESTING

        var i = 0
        while (i < out.size) {
            val t = (i / wav.channels).toFloat() / wav.sampleRate
            rate = ScratchTone.approach(rate, ScratchTone.rateFor(handVelocityAt(t)), dt)

            if (!ScratchTone.audible(rate)) {
                var c = 0
                while (c < wav.channels && i < out.size) { out[i] = 0; i++; c++ }
                java.util.Arrays.fill(toneState, 0f)
                continue
            }

            val gain = ScratchTone.gainFor(rate)
            val drive = ScratchTone.driveFor(rate)
            val coeff = ScratchTone.toneCoeffFor(rate, wav.sampleRate)
            val target = if (withFader) Crossfader.gainFor(faderAt(t)) else 1f
            cut = Crossfader.approach(cut, target, dt)

            var c = 0
            while (c < wav.channels && i < out.size) {
                val raw = ScratchTone.sampleAt(wav.samples, cursor + c) / 32768f
                toneState[c] += coeff * (raw - toneState[c])
                val shaped = ScratchTone.saturate(toneState[c], drive) * gain * cut
                out[i] = (shaped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                i++
                c++
            }
            cursor += rate.toDouble() * wav.channels

            // Keep the render inside the source rather than running off the end into silence,
            // which a real scratch avoids by the window being centred on the playhead.
            if (cursor < 0 || cursor > wav.samples.size - wav.channels * 2) {
                cursor = (wav.samples.size / 2).toDouble()
            }
        }
        return out
    }

    // ---- the smallest possible wav reader and writer ---------------------------------

    private class Wav(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /** 16-bit PCM only, which is what everything on this machine happens to be. */
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

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataBytes); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16)
        bb.putShort(1); bb.putShort(channels.toShort()); bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 2)
        bb.putShort((channels * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataBytes)
        for (s in samples) bb.putShort(s)
        file.writeBytes(bb.array())
    }

    /** A cheap sanity check on the render, so a silent or clipped file is noticed here. */
    @Test
    fun `the render is neither silent nor slammed against the rails`() {
        assumeTrue("no source audio on this machine", source.isFile)
        val wav = readWav(source) ?: return
        val out = renderScratch(wav)

        val peak = out.maxOf { abs(it.toInt()) }
        val railed = out.count { abs(it.toInt()) >= 32760 }
        assert(peak > 4000) { "the render came out silent (peak $peak)" }
        assert(railed < out.size / 50) {
            "the render is clipped against the rails for ${railed * 100 / out.size}% of its length"
        }
    }
}
