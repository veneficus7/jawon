package com.jawon.app.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The cassette room's word to the player, and the player's back: whether the music should sound
 * like tape, and how loud it is for the deck's meters. App-scoped, as [RackState] is, because the
 * room that sets it and the player that reads it do not live and die together.
 */
@Singleton
class TapeState @Inject constructor() {

    /** Hiss, wow and flutter on the music: the cassette is the format and Settings has the sound of tape on. */
    @Volatile
    var colour = false

    /** The level the music is at, each channel, as a fraction of full scale (RMS over the last few hundredths of a second). */
    @Volatile
    var levelLeft = 0f

    @Volatile
    var levelRight = 0f

    /** When that was last written: a level nobody has written for a while is silence, not the last thing heard. */
    @Volatile
    var levelAtNanos = 0L
}

/**
 * **What tape does to music**, as arithmetic: a little hiss under it, a slow wow and a quicker
 * flutter in its pitch, and its very top softened. Pure — `TapeColourTest` holds what it must not
 * do: change anything with it off, click going on or off, or hiss louder than a cheap tape's.
 *
 * - **Wow and flutter are a moving delay**, never the player's speed. Reading a short delay line
 *   at a delay that drifts is a pitch that drifts, and it costs a few multiplies; changing
 *   `PlaybackParameters` to do the same would cross neutral twice a second, which is the trap
 *   that wedges the player (`ENGINEERING.md`, trap 19). The delay is there with the colour off too —
 *   still, a millisecond and a half — so that turning it on is the delay beginning to move, not a
 *   jump from one signal to another.
 * - **Hiss** is noise with its bottom taken off, about sixty decibels down: a ferric tape without
 *   noise reduction, which is what a pre-recorded cassette was.
 * - **The top** is rolled off gently, from about fourteen kilohertz.
 *
 * And it measures: the level each channel is at, for the meters.
 */
class TapeColour(private val sampleRate: Int, private val channels: Int) {

    private val size = (sampleRate * MAX_DELAY_S).toInt() + 4

    /**
     * The delay at rest, in **whole** samples: at 44.1kHz a millisecond and a half is 66.15 of
     * them, and a fraction would be read between two — which is a filter, on every room's music.
     * Whole, off is the music to the bit, only later.
     */
    private val restSamples = Math.round(REST_DELAY_S * sampleRate).toFloat()
    private val lines = Array(channels) { FloatArray(size) }
    private var write = 0

    private var wet = 0f
    private val wetStep = 1f / (WET_RAMP_S * sampleRate)

    private var wowPhase = 0.0
    private var flutterPhase = 0.0
    private var driftPhase = 0.0
    private val wowStep = 2.0 * PI * WOW_HZ / sampleRate
    private val flutterStep = 2.0 * PI * FLUTTER_HZ / sampleRate
    private val driftStep = 2.0 * PI * DRIFT_HZ / sampleRate

    private val low = FloatArray(channels)
    private val lowK = 1f - exp(-2.0 * PI * ROLL_OFF_HZ / sampleRate).toFloat()
    private val hissLow = FloatArray(channels)
    private val hissHigh = FloatArray(channels)
    private var seed = 0x2545F491L

    private val squares = DoubleArray(channels)
    private var measured = 0
    private val measureEvery = (sampleRate * MEASURE_S).toInt().coerceAtLeast(1)

    /** The last levels measured, as fractions of full scale. */
    val level = FloatArray(2)
    var levelChanged = false

    /** Clears the delay and the filters: after a seek, nothing of what was playing before comes out. */
    fun reset() {
        for (line in lines) line.fill(0f)
        low.fill(0f)
        hissLow.fill(0f)
        hissHigh.fill(0f)
        squares.fill(0.0)
        measured = 0
    }

    /**
     * Colours [frames] interleaved frames of [buffer] in place, as floats in −1..1, heading for
     * [on]. Off, what comes out is what went in a millisecond and a half later — exactly.
     */
    fun process(buffer: FloatArray, frames: Int, on: Boolean) {
        val target = if (on) 1f else 0f
        for (f in 0 until frames) {
            if (wet != target) wet = if (wet < target) minOf(target, wet + wetStep) else maxOf(target, wet - wetStep)
            // How far behind the read is: still at rest, drifting with the colour on.
            val wow = sin(wowPhase + 0.6 * sin(driftPhase)).toFloat()
            val flutter = sin(flutterPhase).toFloat()
            wowPhase += wowStep
            flutterPhase += flutterStep
            driftPhase += driftStep
            if (wowPhase > 2 * PI) wowPhase -= 2 * PI
            if (flutterPhase > 2 * PI) flutterPhase -= 2 * PI
            if (driftPhase > 2 * PI) driftPhase -= 2 * PI
            val delay = restSamples + wet * (WOW_DEPTH_S * wow + FLUTTER_DEPTH_S * flutter) * sampleRate
            val read = readPlace(write, delay, size)
            val i0 = read.toInt()
            val frac = read - i0
            val i1 = if (i0 + 1 >= size) 0 else i0 + 1
            for (c in 0 until channels) {
                val x = buffer[f * channels + c]
                val line = lines[c]
                line[write] = x
                var y = line[i0] + (line[i1] - line[i0]) * frac
                // The top, softened.
                low[c] += lowK * (y - low[c])
                y += (low[c] - y) * wet
                // The hiss: white, with its bottom taken off, so it sits in the treble where tape hiss does.
                if (wet > 0f) {
                    val n = noise()
                    hissLow[c] += 0.06f * (n - hissLow[c])
                    val high = n - hissLow[c]
                    hissHigh[c] += 0.7f * (high - hissHigh[c])
                    y += hissHigh[c] * HISS * wet
                }
                buffer[f * channels + c] = y
                squares[c] += (x * x).toDouble()
            }
            write = if (write + 1 >= size) 0 else write + 1
            measured++
            if (measured >= measureEvery) {
                level[0] = sqrt(squares[0] / measured).toFloat()
                level[1] = sqrt(squares[if (channels > 1) 1 else 0] / measured).toFloat()
                squares.fill(0.0)
                measured = 0
                levelChanged = true
            }
        }
    }

    private fun noise(): Float {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return ((seed ushr 40).toInt() and 0xFFFF) / 32767.5f - 1f
    }

    companion object {
        /**
         * Where in a delay line of [size] the read is, [delay] samples behind [write]: always in
         * `0 until size`. **A hair behind zero, wrapped, is the size itself** — −0.000008 plus 268
         * rounds to 268.0 in a Float, whose steps there are 0.00003 — and reading there was one past
         * the end of the line. With the wow moving the delay that happens about once in seven
         * minutes, and on the owner's phone it stopped the player inside two (2026-11-14).
         */
        internal fun readPlace(write: Int, delay: Float, size: Int): Float {
            var read = write - delay
            if (read < 0f) read += size
            if (read >= size) read -= size
            return read
        }

        const val MAX_DELAY_S = 0.006
        /** With the colour off, the music still comes out of the delay — this far behind, and still. */
        const val REST_DELAY_S = 0.0015f

        /** Wow: a pitch wandering about a tenth of a percent, half a dozen times in ten seconds; and drifting itself. */
        const val WOW_HZ = 0.55
        const val DRIFT_HZ = 0.07
        const val WOW_DEPTH_S = 0.00035f

        /** Flutter: quicker, and a third as deep. */
        const val FLUTTER_HZ = 6.8
        const val FLUTTER_DEPTH_S = 0.000012f

        /** About sixty decibels under full scale, once its bottom is gone. */
        const val HISS = 0.0026f
        const val ROLL_OFF_HZ = 14_000.0

        /** On and off over a fifth of a second: never a click. */
        const val WET_RAMP_S = 0.2f

        /** How often the meters are told: thirty times a second. */
        const val MEASURE_S = 0.033
    }
}
