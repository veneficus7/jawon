package com.jawon.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Runs [DjFilter] over the player's output.
 *
 * **Why this and not an `AudioEffect`.** Android hands out reverb, bass boost and a compressor
 * for free, attached to the audio *session* — but there is no filter among them, and a session
 * effect cannot be given one. Anything Jawon invents has to sit inside the player's own pipeline,
 * which in Media3 means an `AudioProcessor` between the decoder and the sink. That is also the
 * seam the rest of the rack will use, so the shape here matters more than this one effect: delay
 * and drive slot in beside it.
 *
 * Everything in [queueInput] runs on the audio thread. No allocation, no locks, no suspension —
 * a stall here is not a slow frame, it is a gap in the music.
 */
@UnstableApi
class FilterAudioProcessor(private val rack: RackState) : BaseAudioProcessor() {

    private var channelCount = 2
    private var sampleRate = 44_100
    private var channels: Array<DjFilter.Svf> = emptyArray()

    /** The knob as the filter has actually reached it, which trails the finger. */
    private var smoothed = RackState.CENTRE

    /** Per-frame smoothing weight, recomputed only when the sample rate changes. */
    private var smoothingK = 0f

    // Coefficients, held between recomputes. See COEFF_INTERVAL.
    private var g = 0f
    private var q = DjFilter.Q_REST
    private var highPass = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 16-bit PCM only. Media3 will have converted for us in the normal case; anything else is
        // a format this processor genuinely cannot handle, and saying so lets the sink decide
        // rather than silently corrupting the stream.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        // One filter per channel: the state *is* the filter's memory, and sharing it across left
        // and right would fold the stereo image into mono every time the knob left centre.
        channels = Array(channelCount) { DjFilter.Svf() }
        smoothingK = 1f - exp(-DjFilter.SMOOTHING_PER_S / sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val output = replaceOutputBuffer(frames * 2 * channelCount)
        val target = rack.filterPosition

        // Nothing to do, and nothing to pay for. The knob is centred and the smoothing has caught
        // up with it, so the filter is bypassed — copy the block through untouched rather than
        // running a state-variable filter per sample to compute the identity.
        if (DjFilter.modeFor(target) == DjFilter.Mode.OFF && abs(smoothed) <= RESTED) {
            smoothed = target
            copyWholeFrames(inputBuffer, output, frames * 2 * channelCount)
            // Ringing state left over from the last sweep would fire the moment the knob moved
            // again, so the filters are cleared while the effect is off rather than on re-entry.
            for (svf in channels) svf.reset()
            return
        }

        var sinceCoeffs = COEFF_INTERVAL
        for (frame in 0 until frames) {
            smoothed += (target - smoothed) * smoothingK

            // The cutoff is recomputed every few frames rather than every one. `tan` is the
            // expensive part of this whole processor, and at 44.1kHz a 32-frame interval updates
            // the filter about 1400 times a second — far past the point anyone can hear a step,
            // and a thirty-second of the cost.
            if (++sinceCoeffs >= COEFF_INTERVAL) {
                sinceCoeffs = 0
                val mode = DjFilter.modeFor(smoothed)
                highPass = mode == DjFilter.Mode.HIGH_PASS
                g = DjFilter.gFor(DjFilter.cutoffHz(smoothed), sampleRate)
                q = DjFilter.resonanceFor(smoothed)
            }

            for (channel in 0 until channelCount) {
                val sample = inputBuffer.short / 32_768f
                val filtered = channels[channel].process(sample, g, q, highPass)
                output.putShort((filtered * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
            }
        }
        output.flip()
    }

    override fun onFlush() {
        // A seek hands the filter a completely unrelated piece of audio. Whatever was ringing
        // belongs to the old position and would arrive as a click at the new one.
        for (svf in channels) svf.reset()
    }

    override fun onReset() {
        channels = emptyArray()
        smoothed = RackState.CENTRE
    }

    private companion object {
        /** Close enough to centre that the difference is inaudible and bypass is honest. */
        const val RESTED = 0.001f
        const val COEFF_INTERVAL = 32
    }
}
