package com.jawon.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.exp

/**
 * Runs [Tone] over the player's output — the TONE cell, in Jawon's own chain at last.
 *
 * **Placed first, before the filter.** Tone is the source's character rather than something
 * done to it: on a real chain the tone controls are at the input and the DJ filter is what you
 * sweep afterwards, so putting the bass shelf ahead means closing the low-pass still takes the
 * bottom end away rather than leaving a boosted shelf sitting under a filter that is supposed
 * to have removed it. Width goes here for the same reason — widening the echo's repeats rather
 * than the record would smear the effect instead of the music.
 *
 * The width half needs two channels at once, so unlike the other processors this reads a whole
 * frame before writing one. Mono is not an error: there is no side signal to widen, so only the
 * bass applies, which [Tone.widen] states in its own arithmetic.
 */
@UnstableApi
class ToneAudioProcessor(private val rack: RackState) : BaseAudioProcessor() {

    private var channelCount = 2
    private var coefficient = 0f
    private var smoothingK = 0f

    private var bassAmount = 0f
    private var widthAmount = 0f

    /**
     * The low-pass memory, one per channel.
     *
     * Sized at configure time rather than allocated per buffer: this runs on the audio thread,
     * where an allocation is a risk of a garbage collection between two frames of music.
     */
    private var low = FloatArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        coefficient = Tone.shelfCoefficient(inputAudioFormat.sampleRate)
        smoothingK = 1f - exp(-Tone.SMOOTHING_PER_S / inputAudioFormat.sampleRate)
        low = FloatArray(channelCount)
        bassAmount = 0f
        widthAmount = 0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val output = replaceOutputBuffer(frames * 2 * channelCount)
        val targetBass = rack.bassPosition
        val targetWidth = rack.widthPosition

        // Both off and both settled: copy through rather than computing the identity, the same
        // bypass the filter and the drive take.
        if (!Tone.audible(targetBass) && !Tone.audible(bassAmount) &&
            !Tone.audible(targetWidth) && !Tone.audible(widthAmount)
        ) {
            bassAmount = 0f
            widthAmount = 0f
            // The filter memory has to go too. Leaving it holding the last low-passed sample
            // means the first frame after the cell is switched back in steps from that stale
            // value to the live one, which is a click at exactly the moment somebody is
            // listening for the effect to arrive.
            low.fill(0f)
            copyWholeFrames(inputBuffer, output, frames * 2 * channelCount)
            return
        }

        val stereo = channelCount == 2
        for (frame in 0 until frames) {
            bassAmount += (targetBass - bassAmount) * smoothingK
            widthAmount += (targetWidth - widthAmount) * smoothingK

            val gain = Tone.gainFor(bassAmount)
            val trim = Tone.trimFor(bassAmount)
            val width = Tone.widthFor(widthAmount)

            if (stereo) {
                val (l, lowL) = Tone.bass(Pcm16.toFloat(inputBuffer.short), low[0], coefficient, gain, trim)
                val (r, lowR) = Tone.bass(Pcm16.toFloat(inputBuffer.short), low[1], coefficient, gain, trim)
                low[0] = lowL
                low[1] = lowR
                val (outL, outR) = Tone.widen(l, r, width)
                output.putShort(Pcm16.toShort(outL))
                output.putShort(Pcm16.toShort(outR))
            } else {
                for (channel in 0 until channelCount) {
                    val (out, next) = Tone.bass(
                        Pcm16.toFloat(inputBuffer.short), low[channel], coefficient, gain, trim
                    )
                    low[channel] = next
                    output.putShort(Pcm16.toShort(out))
                }
            }
        }
        output.flip()
    }

    override fun onReset() {
        bassAmount = 0f
        widthAmount = 0f
        low.fill(0f)
    }

    /** A seek leaves the shelf holding audio from somewhere else in the record. */
    override fun onFlush() {
        low.fill(0f)
    }
}
