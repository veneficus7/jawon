package com.jawon.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [TapeColour] over the player's output, last in the chain — after the echo, because a
 * record played on a tape deck through a rack is still a tape at the end of it.
 *
 * Always in the chain and always active: with the colour off it is the delay standing still and
 * a copy, so that turning the sound of tape on or off never reconfigures the pipeline (the rule
 * every processor here keeps, and `ENGINEERING.md` trap 19 is why). Audio-thread rules apply as in
 * [FilterAudioProcessor]: no allocation, no locks, outside [onConfigure].
 */
@UnstableApi
class TapeAudioProcessor(private val state: TapeState) : BaseAudioProcessor() {

    private var colour: TapeColour? = null
    private var channels = 2
    private var rate = 44_100
    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Reused unless the shape of the audio changed: onConfigure runs on every change of
        // speed, and a fresh delay line each time is the allocation EchoAudioProcessor learnt not to make.
        if (colour == null || inputAudioFormat.channelCount != channels || inputAudioFormat.sampleRate != rate) {
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            rate = inputAudioFormat.sampleRate
            colour = TapeColour(rate, channels)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val colour = colour
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        if (colour == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val samples = bytes / 2
        if (floats.size < samples) floats = FloatArray(samples)
        val shorts = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until samples) floats[i] = shorts.get(i) / 32768f
        inputBuffer.position(inputBuffer.limit())
        colour.process(floats, samples / channels, state.colour)
        for (i in 0 until samples) out.putShort(Pcm16.toShort(floats[i]))
        out.flip()
        if (colour.levelChanged) {
            colour.levelChanged = false
            state.levelLeft = colour.level[0]
            state.levelRight = colour.level[1]
            state.levelAtNanos = System.nanoTime()
        }
    }

    override fun onFlush() {
        colour?.reset()
    }

    override fun onReset() {
        colour = null
        floats = FloatArray(0)
    }
}
