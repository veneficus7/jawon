package com.jawon.app.playback

import com.jawon.app.ui.tapescene.DeckEvent
import com.jawon.app.ui.tapescene.DeckSounds
import com.jawon.app.ui.tapescene.VuMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound of tape, and the deck's own sounds: what they must never do. Change the music with the
 * colour off; click going on or off; hiss louder than a cheap tape; clip; or be louder than the
 * music they sit beside.
 */
class TapeColourTest {

    private val rate = 48_000

    private fun tone(frames: Int, hz: Double = 440.0, level: Float = 0.5f, channels: Int = 2): FloatArray =
        FloatArray(frames * channels) { i -> (level * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }

    @Test
    fun offItIsTheMusicALittleLaterAndNothingElse() {
        val colour = TapeColour(rate, 2)
        val input = tone(9_600)
        val out = input.copyOf()
        colour.process(out, 9_600, on = false)
        val delay = (TapeColour.REST_DELAY_S * rate).toInt()
        for (f in delay + 2 until 9_600) {
            assertEquals("frame $f", input[(f - delay) * 2], out[f * 2], 1e-4f)
        }
    }

    /**
     * The processor is in every room's chain, always (it never comes and goes: trap 19). Off, what
     * the player hands it must come out to the bit — only later by a whole number of samples —
     * at either rate a phone plays at. A fractional delay at 44.1kHz would be a filter on every
     * record in every room.
     */
    @Test
    @androidx.media3.common.util.UnstableApi
    fun offTheProcessorGivesBackThePlayersSamplesToTheBit() {
        for (sampleRate in listOf(44_100, 48_000)) {
            val state = TapeState().apply { colour = false }
            val processor = TapeAudioProcessor(state)
            processor.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(sampleRate, 2, androidx.media3.common.C.ENCODING_PCM_16BIT))
            processor.flush()
            val frames = 4_096
            val input = ShortArray(frames * 2) { i -> ((i * 7919L % 65_536L) - 32_768L).toShort() }
            val bytes = java.nio.ByteBuffer.allocateDirect(input.size * 2).order(java.nio.ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(input)
            processor.queueInput(bytes)
            val out = processor.output.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            val got = ShortArray(out.remaining()).also { out.get(it) }
            assertEquals(input.size, got.size)
            val delay = Math.round(TapeColour.REST_DELAY_S * sampleRate)
            for (i in delay * 2 until got.size) {
                assertEquals("at $sampleRate, sample $i", input[i - delay * 2], got[i])
            }
        }
    }

    /**
     * The read never lands one past the end of the delay line. A delay a hair longer than the
     * write is far along wraps to `size − 0.000008`, which a Float rounds to the size itself; on
     * the owner's phone that index stopped the player (2026-11-14). At either rate, and for delays
     * stepped finely all round the write.
     */
    @Test
    fun theReadIsAlwaysInsideTheDelayLine() {
        for (sampleRate in listOf(44_100, 48_000)) {
            val size = (sampleRate * TapeColour.MAX_DELAY_S).toInt() + 4
            for (write in listOf(0, 1, 50, 66, 72, 82, size - 1)) {
                for (delay in listOf(write.toFloat(), Math.nextUp(write.toFloat()), write + 1e-6f, write + 3e-6f)) {
                    val read = TapeColour.readPlace(write, delay, size)
                    assertTrue("at $sampleRate, write $write, delay $delay: read $read", read >= 0f && read < size)
                    assertTrue(read.toInt() in 0 until size)
                }
            }
        }
    }

    /** And played for a long while with the colour on, nothing is read outside the line. */
    @Test
    fun tenMinutesOfTapeAtFortyFourPointOne() {
        val colour = TapeColour(44_100, 2)
        val buffer = FloatArray(4_096 * 2)
        repeat(44_100 * 600 / 4_096) {
            buffer.fill(0.1f)
            colour.process(buffer, 4_096, on = true)
        }
    }

    @Test
    fun theHissIsAboutSixtyDecibelsDown() {
        val colour = TapeColour(rate, 2)
        val silence = FloatArray(rate * 2 * 2)
        // A fifth of a second to come in, then a second of it.
        colour.process(silence, rate * 2, on = true)
        var sum = 0.0
        for (i in rate * 2 until rate * 4) sum += (silence[i] * silence[i]).toDouble()
        val db = 20 * log10(sqrt(sum / (rate * 2)))
        assertTrue("hiss at $db dB", db < -55 && db > -75)
    }

    @Test
    fun goingOnAndOffNeverClicks() {
        val colour = TapeColour(rate, 2)
        val frames = rate
        val buffer = tone(frames, hz = 200.0)
        colour.process(buffer, frames / 2, on = false)
        val rest = buffer.copyOfRange(frames, frames * 2)
        colour.process(rest, frames / 2, on = true)
        // A 200Hz tone at 0.5 moves at most 2π·200·0.5/48000 ≈ 0.013 a sample; the hiss adds a little.
        var biggest = 0f
        for (i in 2 until rest.size step 2) biggest = maxOf(biggest, abs(rest[i] - rest[i - 2]))
        assertTrue("step $biggest", biggest < 0.03f)
    }

    @Test
    fun itMeasuresTheLevelForTheMeters() {
        val colour = TapeColour(rate, 2)
        val buffer = tone(rate / 10, level = 0.5f)
        colour.process(buffer, rate / 10, on = false)
        assertTrue(colour.levelChanged)
        assertEquals("a sine's RMS", 0.5f / sqrt(2f), colour.level[0], 0.02f)
    }

    @Test
    fun theDecksSoundsNeitherClipNorClickNorShout() {
        for (event in DeckEvent.entries) {
            val pcm = DeckSounds.render(event, rate)
            val loudest = pcm.maxOf { abs(it) }
            assertTrue("$event peaks at $loudest", loudest <= DeckSounds.PEAK + 1e-4f)
            assertTrue("$event starts in silence", abs(pcm[0]) < 0.02f)
            assertTrue("$event ends in silence", abs(pcm.last()) < 1e-3f)
        }
    }

    @Test
    fun theMotorIsQuietAndClimbsWithTheReel() {
        val motor = DeckSounds.Motor(rate)
        var loudest = 0f
        repeat(rate) { loudest = maxOf(loudest, abs(motor.next(1f, 60f))) }
        assertTrue("motor at $loudest", loudest < 0.2f && loudest > 0.01f)
    }

    @Test
    fun aVuMeterReadsZeroAtZeroVuAndRestsLeftInSilence() {
        assertEquals(-VuMeter.HALF_SWING_DEG, VuMeter.angle(0f), 1e-4f)
        // 0 VU is about seven tenths of the way across, as the scale is printed.
        val zero = VuMeter.angle(VuMeter.ZERO_VU)
        assertEquals(-45f + 90f * 0.708f, zero, 0.5f)
        assertTrue(VuMeter.angle(1f) <= VuMeter.HALF_SWING_DEG + 3f)
    }
}
