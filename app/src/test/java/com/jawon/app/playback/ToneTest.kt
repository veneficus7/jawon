package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The two halves of TONE, argued with on the JVM.
 *
 * These are worth testing precisely because nobody can hear a unit test: a bass control that is
 * slightly wrong sounds like a bass control, and the failure a listener would notice — a click
 * on switching in, a knob whose off position is not off, a widener that collapses a mono record
 * — is the kind that is obvious in arithmetic and invisible by ear until it annoys somebody for
 * a week.
 */
class ToneTest {

    private val rate = 44_100
    private val k = Tone.shelfCoefficient(rate)

    // ---- Off means off ----------------------------------------------------------------

    @Test
    fun `a bass knob at zero returns the sample unchanged`() {
        // **The identity that matters.** `Drive` documents the same rule and for the same
        // reason: an effect whose rest position quietly reshapes every waveform is the bug this
        // project has now found three times.
        val gain = Tone.gainFor(0f)
        val trim = Tone.trimFor(0f)
        var low = 0f

        for (sample in listOf(0.5f, -0.25f, 0.9f, -0.9f, 0f)) {
            val (out, next) = Tone.bass(sample, low, k, gain, trim)
            low = next
            assertEquals(sample, out, 1e-6f)
        }
    }

    @Test
    fun `a width knob at zero returns both channels unchanged`() {
        val width = Tone.widthFor(0f)

        val (l, r) = Tone.widen(0.7f, -0.3f, width)

        assertEquals(0.7f, l, 1e-6f)
        assertEquals(-0.3f, r, 1e-6f)
    }

    // ---- Bass ---------------------------------------------------------------------------

    @Test
    fun `bass rises against the midrange, which is what a tone control does`() {
        // **Stated as a ratio, because absolute is the wrong claim.** There is no headroom
        // above full scale to boost into, so the trim gives back exactly what the shelf added
        // and the control is a tilt: the low end holds its level while everything above it
        // comes down. Asserting that 50Hz gets *absolutely* louder would be asserting a
        // clipping bass control, which is the thing this design refuses to be.
        val lowBefore = peakOfBass(hz = 50f, amount = 0f)
        val midBefore = peakOfBass(hz = 1_000f, amount = 0f)
        val lowAfter = peakOfBass(hz = 50f, amount = 1f)
        val midAfter = peakOfBass(hz = 1_000f, amount = 1f)

        val before = lowBefore / midBefore
        val after = lowAfter / midAfter

        assertTrue("$before -> $after", after > before * 1.4f)
    }

    @Test
    fun `turning the bass up never makes the record louder`() {
        // The promise the trim exists to keep, and the one a listener would notice being
        // broken — a control that raises level always seems like the better setting.
        val flat = peakOfBass(hz = 50f, amount = 0f)

        for (amount in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val boosted = peakOfBass(hz = 50f, amount = amount)
            assertTrue("at $amount: $flat -> $boosted", boosted <= flat + 1e-3f)
        }
    }

    @Test
    fun `a high tone is not lifted by the bass control`() {
        // The whole point of a shelf: it adds weight without thickening the middle. A 4kHz tone
        // is far above the corner, so it should come back at or below where it started — the
        // trim pulls it down slightly, which is correct and is what stops the knob being a
        // volume control.
        val boosted = peakOfBass(hz = 4_000f, amount = 1f)
        val flat = peakOfBass(hz = 4_000f, amount = 0f)

        assertTrue("4kHz: $flat -> $boosted", boosted <= flat + 1e-3f)
    }

    @Test
    fun `the shelf sits where it says it does`() {
        // Around the corner frequency the lift should be roughly half of what it is well below
        // it. Loose bounds — this is asserting a shape, not a filter design.
        val deep = liftAt(40f)
        val corner = liftAt(Tone.SHELF_HZ)
        val high = liftAt(2_000f)

        assertTrue("deep $deep, corner $corner", deep > corner)
        assertTrue("corner $corner, high $high", corner > high)
    }

    @Test
    fun `full bass on a full-scale signal does not flatten against the rail`() {
        // **What a missing trim would actually sound like.** Staying inside ±1 is not the test
        // — `bass` coerces, so that passes with no trim at all and the failure it is supposed
        // to catch sails through. What the trim is for is keeping the waveform off the rail in
        // the first place: a sine that arrives at full scale and gets low end added to it will
        // sit flat at 1.0 for a large part of every cycle, and flat is a square wave, which is
        // audible as buzz on the loudest passage of a record. This asserts the shape, and it
        // fails the moment the trim is removed.
        val gain = Tone.gainFor(1f)
        val trim = Tone.trimFor(1f)
        var low = 0f
        var atRail = 0
        val total = 4_000

        repeat(total) { n ->
            val sample = sin(2.0 * Math.PI * 40.0 * n / rate).toFloat()
            val (out, next) = Tone.bass(sample, low, k, gain, trim)
            low = next
            assertTrue("$out left the rails", out in -1f..1f)
            if (abs(out) > 0.999f) atRail++
        }

        assertTrue("$atRail of $total samples pinned at full scale", atRail == 0)
    }

    @Test
    fun `the trim gives back exactly what the shelf adds`() {
        assertEquals(1f, Tone.trimFor(0f), 1e-6f)
        assertTrue(Tone.trimFor(0.5f) < Tone.trimFor(0.2f))

        // The invariant the no-clipping guarantee rests on: gain and trim multiply to one at
        // every point of the travel, so the worst case a signal can reach is where it started.
        for (amount in listOf(0f, 0.1f, 0.33f, 0.5f, 0.9f, 1f)) {
            val worstCase = (1f + Tone.gainFor(amount)) * Tone.trimFor(amount)
            assertEquals("at $amount", 1f, worstCase, 1e-5f)
        }
    }

    // ---- Width --------------------------------------------------------------------------

    @Test
    fun `widening pushes the channels apart`() {
        val (l, r) = Tone.widen(0.6f, 0.2f, Tone.widthFor(1f))

        // The mid — what both channels agree on — is preserved; the difference grows.
        assertEquals(0.4f, (l + r) / 2f, 1e-5f)
        assertTrue("$l vs $r", (l - r) > (0.6f - 0.2f))
    }

    @Test
    fun `a mono signal is left exactly alone however wide the knob`() {
        // **Worth stating outright.** Widening cannot invent a stereo image that was never
        // recorded, and an effect that appeared to would be doing phase tricks nobody asked
        // for. Both channels equal means no side signal, so there is nothing to multiply.
        val (l, r) = Tone.widen(0.5f, 0.5f, Tone.widthFor(1f))

        assertEquals(0.5f, l, 1e-6f)
        assertEquals(0.5f, r, 1e-6f)
    }

    @Test
    fun `a fully wide out-of-phase pair stays inside the rails`() {
        val (l, r) = Tone.widen(1f, -1f, Tone.widthFor(1f))

        assertTrue(l in -1f..1f)
        assertTrue(r in -1f..1f)
    }

    // ---- Nonsense in ---------------------------------------------------------------------

    @Test
    fun `a NaN never escapes into the audio`() {
        // A NaN reaching the sink is silence for the rest of the track, because every
        // comparison against it is false and `coerceIn` does not rescue it. Same trap `Drive`
        // documents.
        val (out, _) = Tone.bass(Float.NaN, 0f, k, 1f, 1f)
        assertEquals(0f, out, 0f)

        val (l, r) = Tone.widen(Float.NaN, 0.5f, 2f)
        assertEquals(0f, l, 0f)
        assertEquals(0f, r, 0f)

        // And a nonsense knob reads as off rather than as an error.
        assertEquals(0f, Tone.gainFor(Float.NaN), 0f)
        assertEquals(1f, Tone.trimFor(Float.NaN), 0f)
        assertEquals(1f, Tone.widthFor(Float.NaN), 0f)
    }

    @Test
    fun `the corner stays put as the sample rate changes`() {
        // The same setting has to sound the same on a CD rip and on a hi-res file, in an app
        // whose whole claim is that the file is exactly what you own. A coefficient derived
        // without the exponential would drift with the rate.
        val at44 = Tone.shelfCoefficient(44_100)
        val at192 = Tone.shelfCoefficient(192_000)

        // Response at 120Hz should be the same fraction of full at both rates.
        val lift44 = liftAt(Tone.SHELF_HZ, 44_100, at44)
        val lift192 = liftAt(Tone.SHELF_HZ, 192_000, at192)

        assertEquals(lift44, lift192, 0.05f)
    }

    @Test
    fun `a nonsense sample rate does not divide by zero`() {
        assertEquals(0f, Tone.shelfCoefficient(0), 0f)
        assertEquals(0f, Tone.shelfCoefficient(-1), 0f)
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Peak of a settled sine through the bass stage. */
    private fun peakOfBass(hz: Float, amount: Float, sampleRate: Int = rate): Float {
        val coefficient = Tone.shelfCoefficient(sampleRate)
        val gain = Tone.gainFor(amount)
        val trim = Tone.trimFor(amount)
        var low = 0f
        var peak = 0f
        val total = (sampleRate / hz * 12).toInt().coerceAtLeast(2_000)
        for (n in 0 until total) {
            val sample = sin(2.0 * Math.PI * hz * n / sampleRate).toFloat() * 0.5f
            val (out, next) = Tone.bass(sample, low, coefficient, gain, trim)
            low = next
            // Ignore the first cycles while the filter fills.
            if (n > total / 3) peak = maxOf(peak, abs(out))
        }
        return peak
    }

    /** How much a tone at [hz] is lifted at full bass, as a ratio against flat. */
    private fun liftAt(
        hz: Float,
        sampleRate: Int = rate,
        @Suppress("UNUSED_PARAMETER") coefficient: Float = k
    ): Float = peakOfBass(hz, 1f, sampleRate) / peakOfBass(hz, 0f, sampleRate)
}
