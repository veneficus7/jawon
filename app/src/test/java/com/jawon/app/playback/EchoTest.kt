package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class EchoTest {

    private val sampleRate = 44_100

    // ---- the knobs -----------------------------------------------------------------

    @Test
    fun `time spans the useful range and moves in ratios`() {
        assertEquals(Echo.MIN_MS, Echo.timeMsFor(0f), 0.01f)
        assertEquals(Echo.MAX_MS, Echo.timeMsFor(1f), 0.5f)
        // What the ear tracks is the ratio between repeat lengths, not the difference.
        val a = ln(Echo.timeMsFor(0.30f) / Echo.timeMsFor(0.10f))
        val b = ln(Echo.timeMsFor(0.80f) / Echo.timeMsFor(0.60f))
        assertEquals("equal travel should be equal ratios", a, b, 0.02f)
    }

    @Test
    fun `feedback can never reach unity`() {
        // The only genuinely dangerous number in the file: at 1 the echo never decays, and above
        // it the output grows without bound until it is a full-scale scream.
        for (p in listOf(0f, 0.5f, 1f, 2f, 100f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val fb = Echo.feedbackFor(p)
            assertTrue("feedback $fb out of range for $p", fb in 0f..Echo.MAX_FEEDBACK)
        }
        assertTrue(Echo.MAX_FEEDBACK < 1f)
    }

    // ---- the line ------------------------------------------------------------------

    @Test
    fun `an impulse comes back one delay later`() {
        val line = Echo.Line(Echo.bufferFramesFor(sampleRate))
        val delayFrames = 1_000f
        // Fully wet, no feedback: exactly one repeat and nothing else.
        var heardAt = -1
        for (n in 0 until 3_000) {
            val input = if (n == 0) 1f else 0f
            val out = line.process(input, delayFrames, 0f, 1f)
            if (out > 0.5f && heardAt < 0) heardAt = n
        }
        assertEquals("the repeat should land one delay later", 1_000, heardAt)
    }

    @Test
    fun `dry means dry — the echo can be pulled out cleanly at any moment`() {
        // The gesture is turning it up for a bar and back down. If "off" still leaked, every
        // track would quietly smear.
        val line = Echo.Line(Echo.bufferFramesFor(sampleRate))
        repeat(2_000) { line.process(0.8f, 500f, 0.8f, 1f) }
        // Now fully dry, feeding silence: the output must be exactly the (silent) input.
        repeat(2_000) {
            assertEquals(0f, line.process(0f, 500f, 0.8f, 0f), 1e-6f)
        }
    }

    @Test
    fun `repeats decay rather than build, even at maximum feedback`() {
        val line = Echo.Line(Echo.bufferFramesFor(sampleRate))
        val delayFrames = 500f
        val peaks = mutableListOf<Float>()
        var peak = 0f
        for (n in 0 until 500 * 8) {
            val input = if (n == 0) 1f else 0f
            val out = line.process(input, delayFrames, Echo.MAX_FEEDBACK, 1f)
            peak = maxOf(peak, abs(out))
            if (n % 500 == 499) { peaks += peak; peak = 0f }
        }
        // Fully wet, so the first window is silent — the impulse has not come back round yet.
        // The repeats are what follows it.
        val repeats = peaks.dropWhile { it < 1e-4f }
        assertTrue("expected several repeats, got ${peaks.size}", repeats.size >= 4)
        for (i in 1 until repeats.size) {
            assertTrue(
                "repeat $i (${repeats[i]}) should be quieter than ${repeats[i - 1]}",
                repeats[i] <= repeats[i - 1] + 1e-4f
            )
        }
        assertTrue("the tail should actually die away", repeats.last() < repeats.first() * 0.5f)
    }

    @Test
    fun `a sustained loud input at maximum feedback still cannot leave full scale`() {
        // The runaway case, driven as hard as it can be driven.
        val line = Echo.Line(Echo.bufferFramesFor(sampleRate))
        var peak = 0f
        repeat(sampleRate * 3) {
            peak = maxOf(peak, abs(line.process(1f, 300f, 10f, 1f)))
        }
        assertTrue("output reached $peak", peak <= 1f)
    }

    @Test
    fun `the read point wraps correctly rather than indexing off the front`() {
        val line = Echo.Line(256)
        // Ask for more delay than the buffer holds, repeatedly, from a cold start.
        repeat(4_000) {
            val out = line.process(0.5f, 10_000f, 0.5f, 1f)
            assertTrue("output $out not finite", out.isFinite())
            assertTrue("output $out out of range", abs(out) <= 1f)
        }
    }

    @Test
    fun `resetting clears the tail`() {
        val line = Echo.Line(Echo.bufferFramesFor(sampleRate))
        repeat(3_000) { line.process(0.9f, 400f, 0.8f, 1f) }
        line.reset()
        repeat(400) {
            assertEquals("a reset line must be silent", 0f, line.process(0f, 400f, 0.8f, 1f), 1e-6f)
        }
    }

    @Test
    fun `resetting is cheap, because it happens constantly`() {
        // Every change to playback speed or pitch flushes the audio pipeline, which resets this
        // line — and a finger on the pitch slider produces those by the dozen per second. When
        // reset zeroed the whole buffer, that pinned the playback thread at a full core and it
        // never recovered: 606 jiffies per six seconds against a healthy 11, hot enough to
        // throttle the phone. A hundred thousand resets must not take meaningful time.
        val line = Echo.Line(Echo.bufferFramesFor(48_000))
        repeat(1_000) { line.process(0.5f, 500f, 0.5f, 1f) }
        val started = System.nanoTime()
        repeat(100_000) { line.reset() }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("100k resets took ${millis}ms — reset is not O(1)", millis < 200)
    }

    @Test
    fun `a reset line cannot play back what the last one left behind`() {
        // Clearing is now a counter rather than a memset, so this is the property that replaces
        // the zeroed buffer: until the line has recorded as far back as it is being asked to
        // read, it must answer silence rather than whatever is still in the array.
        val line = Echo.Line(Echo.bufferFramesFor(44_100))
        repeat(5_000) { line.process(0.9f, 400f, 0.0f, 1f) }
        line.reset()
        // Feeding silence, fully wet: every output must be silent until enough new audio exists.
        repeat(400) {
            assertEquals("stale audio leaked after reset", 0f, line.process(0f, 400f, 0f, 1f), 1e-6f)
        }
    }

    @Test
    fun `a fresh line is silent until it has recorded far enough back`() {
        val line = Echo.Line(Echo.bufferFramesFor(44_100))
        // Fully wet with no feedback: nothing can come back until a whole delay has been
        // recorded, which takes exactly `back` writes.
        for (n in 0 until 1_000) {
            assertEquals("leaked at frame $n", 0f, line.process(1f, 1_000f, 0f, 1f), 1e-6f)
        }
        // And on the very next frame the beginning of the recording comes round.
        assertTrue(line.process(1f, 1_000f, 0f, 1f) > 0.5f)
    }

    @Test
    fun `the buffer is big enough for the longest repeat`() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000)) {
            val frames = Echo.bufferFramesFor(rate)
            val needed = (Echo.MAX_MS / 1000f) * rate
            assertTrue("buffer $frames too small at $rate (needs $needed)", frames > needed)
        }
        assertTrue(Echo.bufferFramesFor(0) >= 2)
    }

    // ---- glide ---------------------------------------------------------------------

    @Test
    fun `delay time glides rather than jumps`() {
        // A read point that jumps lands somewhere unrelated to where the waveform was, which is
        // a click. Gliding is also the best sound the effect makes — it bends pitch like tape.
        var v = 0f
        repeat(100) { v = Echo.approach(v, 1f, 1f / 44_100f, Echo.TIME_GLIDE_PER_S) }
        assertTrue("time should barely move in 100 samples (got $v)", v < 0.02f)

        var level = 0f
        repeat(100) { level = Echo.approach(level, 1f, 1f / 44_100f, Echo.LEVEL_SMOOTHING_PER_S) }
        assertTrue("levels should move faster than time", level > v)
    }
}
