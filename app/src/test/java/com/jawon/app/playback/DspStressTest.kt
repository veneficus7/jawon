package com.jawon.app.playback

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rack under abuse, rather than under use.
 *
 * The existing kernel tests each ask whether one effect computes the right number from sensible
 * inputs. These ask the opposite question: what happens when someone plays the rack like an
 * instrument — every knob moving at once, held at the stops, for the length of a real record —
 * and what happens when something upstream hands the chain a value it was never supposed to see.
 *
 * Three failure modes are worth this much machinery, because all three are silent until they are
 * catastrophic and none of them can be found by ear until they happen to a listener:
 *
 * 1. **A number that escapes.** A resonant filter or a feedback line can ring past full scale on
 *    a transient. If that reaches the 16-bit conversion it is a click; if it compounds it is a
 *    scream. Every processor here clamps its output, and these tests are what says so.
 * 2. **A number that stops being a number.** One NaN reaching a recursive filter's state stays
 *    there: the state feeds itself, so the channel outputs NaN for the rest of the session and no
 *    amount of good audio afterwards washes it out. This is the one that reads to a listener as
 *    "the music died and never came back".
 * 3. **Arithmetic that is legal but slow.** Denormal floats — the vanishingly small values a
 *    decaying filter state passes through on its way to zero — are handled in microcode on some
 *    CPUs and cost tens of times a normal multiply. A chain that comfortably runs in real time
 *    can fall off a cliff a few seconds after the music stops, which is exactly the shape of
 *    "the phone gets laggy for no reason".
 *
 * Everything here is deterministic. The thrash uses a seeded PRNG so a failure reproduces on the
 * next run rather than being a story about something that happened once.
 */
class DspStressTest {

    private companion object {
        const val SAMPLE_RATE = 48_000

        /**
         * How much audio each endurance test pushes through, in seconds.
         *
         * Long enough to be a record rather than a buffer — a feedback line that grows by a
         * fraction of a percent per pass shows nothing in a hundred milliseconds and is a scream
         * by the end of a side. Short enough that the whole suite stays quick.
         */
        const val ENDURANCE_S = 60

        /** One processing block, in frames. Matches the order of magnitude Media3 hands over. */
        const val BLOCK = 1024
    }

    /** Every sample the chain has produced must be a real number inside the rails. */
    private fun assertUsable(sample: Float, where: String, index: Int) {
        assertTrue(
            "$where produced a non-finite sample at $index: $sample",
            sample.isFinite()
        )
        assertTrue(
            "$where produced $sample at $index, outside [-1, 1]",
            sample >= -1f && sample <= 1f
        )
    }

    /**
     * Everything at once, moving, for a minute.
     *
     * This is the test that stands in for the owner's actual question: play the rack as hard as
     * it can be played and see whether anything comes loose. Every parameter is re-randomised
     * every block — far faster than a finger could move, and deliberately without the smoothing
     * the real processors apply, so the kernels are being asked to cope with genuine jumps rather
     * than the eased ramps they normally see.
     *
     * The signal underneath is full-scale, because a quiet input hides exactly the overshoot this
     * is looking for.
     */
    @Test
    fun `whole chain survives a minute of every knob being thrashed`() {
        val random = Random(20260902)
        val filter = DjFilter.Svf()
        val echo = Echo.Line(Echo.bufferFramesFor(SAMPLE_RATE))
        val blocks = ENDURANCE_S * SAMPLE_RATE / BLOCK
        var index = 0

        repeat(blocks) {
            // A new setting for every knob, every block.
            val filterPosition = random.nextFloat()
            val g = DjFilter.gFor(DjFilter.cutoffHz(filterPosition), SAMPLE_RATE)
            val q = DjFilter.resonanceFor(filterPosition)
            val highPass = DjFilter.modeFor(filterPosition) == DjFilter.Mode.HIGH_PASS
            val driveAmount = random.nextFloat()
            val delayFrames = Echo.timeMsFor(random.nextFloat()) / 1000f * SAMPLE_RATE
            val feedback = Echo.feedbackFor(random.nextFloat())
            val mix = random.nextFloat()

            repeat(BLOCK) {
                // Full scale, and not a pure tone: a sine plus a harmonic gives the filter
                // something to resonate on and the saturator something to fold.
                val t = index.toFloat() / SAMPLE_RATE
                val input = 0.7f * sin(2f * Math.PI.toFloat() * 440f * t) +
                    0.3f * sin(2f * Math.PI.toFloat() * 3_150f * t)

                // The documented chain order: filter, then drive, then echo last so the repeats
                // are echoes of the driven signal rather than driven echoes.
                val filtered = filter.process(input, g, q, highPass)
                assertUsable(filtered, "filter", index)
                val driven = Drive.process(filtered, driveAmount)
                assertUsable(driven, "drive", index)
                val out = echo.process(driven, delayFrames, feedback, mix)
                assertUsable(out, "echo", index)
                index++
            }
        }
    }

    /**
     * The filter held at maximum resonance while the cutoff is swept across it.
     *
     * A sweeping resonant peak is the single loudest thing this chain can do to a signal, and a
     * square wave is the worst thing to sweep it across: every edge is a full-scale transient
     * with energy at every frequency the peak can find.
     */
    @Test
    fun `resonant filter swept across a square wave stays inside the rails`() {
        val filter = DjFilter.Svf()
        val total = ENDURANCE_S * SAMPLE_RATE
        val period = SAMPLE_RATE / 110

        repeat(total) { index ->
            // Sweep the cutoff back and forth across the whole range once a second.
            val phase = (index % SAMPLE_RATE).toFloat() / SAMPLE_RATE
            val position = if (phase < 0.5f) phase * 2f else 2f - phase * 2f
            val g = DjFilter.gFor(DjFilter.cutoffHz(position), SAMPLE_RATE)
            val q = DjFilter.Q_EXTREME
            val square = if ((index % period) < period / 2) 1f else -1f
            assertUsable(filter.process(square, g, q, highPass = false), "filter", index)
        }
    }

    /**
     * The echo held at the top of its feedback range with a full-scale signal going in.
     *
     * [Echo.MAX_FEEDBACK] is described in its own file as the only genuinely dangerous number
     * there. This is what checks the ceiling holds: at a gain per pass below one the line settles
     * to a finite sum, and the clamp on the write catches it even if it did not.
     */
    @Test
    fun `echo at maximum feedback settles instead of running away`() {
        val echo = Echo.Line(Echo.bufferFramesFor(SAMPLE_RATE))
        val delayFrames = Echo.timeMsFor(0f) / 1000f * SAMPLE_RATE
        val feedback = Echo.feedbackFor(1f)
        val total = ENDURANCE_S * SAMPLE_RATE
        var peak = 0f

        repeat(total) { index ->
            val input = if (index % 2 == 0) 1f else -1f
            val out = echo.process(input, delayFrames, feedback, mix = 1f)
            assertUsable(out, "echo", index)
            peak = maxOf(peak, abs(out))
        }

        // The shortest delay and the highest feedback is the fastest possible accumulation. If
        // the line were compounding rather than settling, a minute is thousands of passes and
        // the clamp would be the only thing left holding it at exactly 1.0.
        assertTrue("echo never produced audible output; the test is not testing anything", peak > 0.1f)
    }

    /**
     * One bad sample must not end the session.
     *
     * A recursive filter's state is its memory, so a NaN that reaches it is not a glitch that
     * passes — it is written into the state, and the state feeds itself on the next sample. The
     * channel then outputs NaN forever, through track changes and seeks, until something calls
     * `reset`. Nothing upstream is guaranteed to.
     *
     * [Drive] already takes this seriously: a non-finite amount there "reads as off rather than
     * as an error", in its own words. This asks the filter to be equally hard to break. The
     * signal after the bad sample is ordinary music, and it must come out as ordinary music.
     */
    @Test
    fun `filter recovers when handed a sample that is not a number`() {
        val g = DjFilter.gFor(DjFilter.cutoffHz(0.8f), SAMPLE_RATE)
        val q = DjFilter.resonanceFor(0.8f)

        for (poison in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val filter = DjFilter.Svf()
            // Ordinary audio first, so the state is populated the way it would be mid-record.
            repeat(1_000) { index ->
                filter.process(sin(index * 0.01f), g, q, highPass = false)
            }

            filter.process(poison, g, q, highPass = false)

            // A second of real music afterwards. Every sample of it has to be usable.
            repeat(SAMPLE_RATE) { index ->
                val out = filter.process(sin(index * 0.01f) * 0.5f, g, q, highPass = false)
                assertUsable(out, "filter after $poison", index)
            }
        }
    }

    /** The same question for the parameters, which arrive from smoothed UI state. */
    @Test
    fun `filter recovers when handed a coefficient that is not a number`() {
        val g = DjFilter.gFor(DjFilter.cutoffHz(0.8f), SAMPLE_RATE)
        val q = DjFilter.resonanceFor(0.8f)
        val filter = DjFilter.Svf()

        repeat(1_000) { filter.process(sin(it * 0.01f), g, q, highPass = false) }
        filter.process(0.5f, Float.NaN, q, highPass = false)
        filter.process(0.5f, g, Float.NaN, highPass = false)

        repeat(SAMPLE_RATE) { index ->
            val out = filter.process(sin(index * 0.01f) * 0.5f, g, q, highPass = false)
            assertUsable(out, "filter after a non-finite coefficient", index)
        }
    }

    /**
     * What the filter's state does on its way to silence.
     *
     * When the music stops the input is zeros, and a recursive filter's state decays toward zero
     * exponentially without ever arriving. On the way it passes through the denormal range, where
     * some CPUs trap into microcode and a multiply that cost a nanosecond starts costing fifty.
     * The room is already rendering continuously; audio suddenly costing an order of magnitude
     * more is the difference between a phone that is warm and a phone that is stuttering.
     *
     * The fix, when it is needed, is to flush the state to true zero once it is inaudible.
     */
    @Test
    fun `filter state reaches true zero after silence rather than idling in denormals`() {
        val filter = DjFilter.Svf()
        val g = DjFilter.gFor(DjFilter.cutoffHz(0.9f), SAMPLE_RATE)
        val q = DjFilter.Q_EXTREME

        // Ring it hard, then stop feeding it.
        repeat(SAMPLE_RATE) { filter.process(if (it % 2 == 0) 1f else -1f, g, q, highPass = false) }
        repeat(SAMPLE_RATE * 2) { filter.process(0f, g, q, highPass = false) }

        // Two seconds of silence is far past the point of audibility. Whatever is left must be a
        // normal float or exactly zero — never a denormal.
        val out = filter.process(0f, g, q, highPass = false)
        assertEquals("filter still ringing after two seconds of silence", 0f, out, 0f)
    }

    /**
     * The whole chain has to be far cheaper than the audio it is processing.
     *
     * Not a benchmark — a cliff detector. The margin here is enormous on purpose: a desktop JVM
     * runs this hundreds of times faster than real time, so the threshold only fires if something
     * has gone categorically wrong, which is the failure worth catching. Denormal stalls, an
     * accidental allocation in the inner loop and an O(n²) buffer walk all look like this and
     * none of them look like anything in a correctness test.
     */
    @Test
    fun `whole chain processes audio far faster than real time`() {
        val filter = DjFilter.Svf()
        val echo = Echo.Line(Echo.bufferFramesFor(SAMPLE_RATE))
        val seconds = 10
        val total = seconds * SAMPLE_RATE
        val g = DjFilter.gFor(DjFilter.cutoffHz(0.75f), SAMPLE_RATE)
        val q = DjFilter.resonanceFor(0.75f)
        val delayFrames = Echo.timeMsFor(0.5f) / 1000f * SAMPLE_RATE
        val feedback = Echo.feedbackFor(0.9f)

        // Warm the JIT, so the measurement is of the compiled loop rather than the interpreter.
        repeat(SAMPLE_RATE) { index ->
            echo.process(Drive.process(filter.process(sin(index * 0.01f), g, q, false), 0.6f), delayFrames, feedback, 0.4f)
        }

        var sink = 0f
        val nanos = measureNanoTime {
            repeat(total) { index ->
                val filtered = filter.process(sin(index * 0.01f), g, q, highPass = false)
                val driven = Drive.process(filtered, 0.6f)
                sink += echo.process(driven, delayFrames, feedback, mix = 0.4f)
            }
        }

        assertTrue("the chain optimised itself away; the measurement is meaningless", sink.isFinite())
        val realTimeFactor = seconds * 1_000_000_000.0 / nanos
        assertTrue(
            "chain ran at only ${"%.1f".format(realTimeFactor)}x real time, which is far below " +
                "what a mono kernel should manage — look for denormals or an allocation in the loop",
            realTimeFactor > 20.0
        )
    }
}
