package com.jawon.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.exp

/**
 * Runs [Drive] over the player's output, between the filter and the echo.
 *
 * **That position is the whole reason to have an order.** Filter first means the drive is fed
 * whatever the filter left — sweep the low-pass down and the distortion has less to bite on, so
 * the two knobs interact the way they do on a real chain. Echo last means the repeats are echoes
 * of the *driven* signal rather than clean copies arriving underneath it.
 *
 * Stateless per sample, so unlike the other two there is nothing to reset on a seek — only the
 * smoothed knob, which is not audio.
 */
@UnstableApi
class DriveAudioProcessor(private val rack: RackState) : BaseAudioProcessor() {

    private var channelCount = 2
    private var sampleRate = 44_100
    private var amount = 0f
    private var smoothingK = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        smoothingK = 1f - exp(-Drive.SMOOTHING_PER_S / sampleRate)
        amount = 0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val output = replaceOutputBuffer(frames * 2 * channelCount)
        val target = rack.drivePosition

        // Off, and the smoothing has caught up: copy through. Running a waveshaper to compute the
        // identity is the same waste the filter's bypass avoids.
        if (!Drive.audible(target) && !Drive.audible(amount)) {
            amount = 0f
            copyWholeFrames(inputBuffer, output, frames * 2 * channelCount)
            return
        }

        for (frame in 0 until frames) {
            amount += (target - amount) * smoothingK
            for (channel in 0 until channelCount) {
                val sample = Pcm16.toFloat(inputBuffer.short)
                val out = Drive.process(sample, amount)
                output.putShort(Pcm16.toShort(out))
            }
        }
        output.flip()
    }

    override fun onReset() {
        amount = 0f
    }
}
