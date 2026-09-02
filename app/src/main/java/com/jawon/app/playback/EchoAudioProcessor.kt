package com.jawon.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.exp

/**
 * Runs [Echo] over the player's output, after [FilterAudioProcessor].
 *
 * **The order is deliberate.** Filter first means the repeats inherit whatever the filter was
 * doing when they were created — sweep the filter down and the existing tail keeps its brightness
 * while new echoes arrive progressively darker, which is what a real chain does and what anyone
 * reaching for both expects. Delay first would filter the whole tail at once and lose that.
 *
 * Audio-thread rules apply exactly as in [FilterAudioProcessor]: no allocation, no locks, no
 * suspension. The one allocation here happens in [onConfigure], which runs off it.
 */
@UnstableApi
class EchoAudioProcessor(private val rack: RackState) : BaseAudioProcessor() {

    private var channelCount = 2
    private var sampleRate = 44_100
    private var lines: Array<Echo.Line> = emptyArray()

    private var timeFrames = 0f
    private var feedback = 0f
    private var mix = 0f

    private var glideK = 0f
    private var levelK = 0f

    /** True once the tail has been flushed, so a silent effect costs a copy and nothing more. */
    private var idle = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // **Reused, not rebuilt, and that distinction is the whole bug.**
        //
        // `onConfigure` is not the once-per-track call it looks like: the audio pipeline is
        // reconfigured on every change to playback speed or pitch, which a finger on the pitch
        // slider produces dozens of times a second. Each delay line is over a second of audio —
        // some fifty thousand floats a channel — and allocating one zeroes it, so rebuilding
        // them here meant a fresh quarter-megabyte memset per reconfiguration, on the playback
        // thread. That pinned it at a full core and it never recovered: 608 jiffies per six
        // seconds against a healthy 11, hot enough to throttle the phone.
        //
        // The buffers only actually depend on the channel count and the sample rate, and neither
        // changes when the pitch does.
        val sameFormat = lines.size == inputAudioFormat.channelCount &&
            sampleRate == inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        if (sameFormat) {
            // Still a different point in the stream, so the tail has to go — but that is now a
            // pair of counters rather than a memset. See Echo.Line.
            for (line in lines) line.reset()
        } else {
            lines = Array(channelCount) { Echo.Line(Echo.bufferFramesFor(sampleRate)) }
        }
        glideK = 1f - exp(-Echo.TIME_GLIDE_PER_S / sampleRate)
        levelK = 1f - exp(-Echo.LEVEL_SMOOTHING_PER_S / sampleRate)
        timeFrames = msToFrames(Echo.timeMsFor(rack.echoTimePosition))
        feedback = Echo.feedbackFor(rack.echoFeedbackPosition)
        mix = 0f
        idle = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val output = replaceOutputBuffer(frames * 2 * channelCount)

        val targetMix = rack.echoMixPosition
        val targetFeedback = Echo.feedbackFor(rack.echoFeedbackPosition)
        val targetFrames = msToFrames(Echo.timeMsFor(rack.echoTimePosition))

        // Fully dry and already flushed: copy through. An effect you throw in spends most of its
        // life switched off, and there is no reason to run a delay line to compute the dry signal.
        if (targetMix <= SILENT && idle) {
            mix = 0f
            feedback = targetFeedback
            timeFrames = targetFrames
            copyWholeFrames(inputBuffer, output, frames * 2 * channelCount)
            return
        }

        for (frame in 0 until frames) {
            // Time glides slowly on purpose — the read point moving is a tape motor changing
            // speed, and that pitch bend is the best sound this effect makes.
            timeFrames += (targetFrames - timeFrames) * glideK
            feedback += (targetFeedback - feedback) * levelK
            mix += (targetMix - mix) * levelK

            for (channel in 0 until channelCount) {
                val sample = inputBuffer.short / 32_768f
                val out = lines[channel].process(sample, timeFrames, feedback, mix)
                output.putShort((out * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
            }
        }
        output.flip()

        // Once the knob is down *and* the smoothing has caught up, the next block can take the
        // cheap path. The tail is left in the buffer rather than cleared, so turning the mix back
        // up mid-track picks up what was already there instead of starting from silence.
        idle = targetMix <= SILENT && mix <= SILENT
    }

    override fun onFlush() {
        // A seek hands the delay a completely unrelated piece of audio, and a tail from the old
        // position arriving over the new one is the single most obvious way this could sound
        // broken.
        for (line in lines) line.reset()
        mix = 0f
        idle = true
    }

    override fun onReset() {
        lines = emptyArray()
        idle = true
    }

    private fun msToFrames(ms: Float): Float = ms / 1000f * sampleRate

    private companion object {
        /** Below this the effect is inaudible and can be skipped outright. */
        const val SILENT = 0.001f
    }
}
