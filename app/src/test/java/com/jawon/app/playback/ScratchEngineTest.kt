package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The needle: what a record's motion sounds like. Pitch is speed, loudness is speed, the reader is
 * clean at any speed, and under the motor at normal speed it is the record and nothing else.
 */
class ScratchEngineTest {

    private val rate = 48_000

    private fun tone(hz: Double, seconds: Double, sampleRate: Int = rate, channels: Int = 1, level: Double = 0.5): ScratchWindow {
        val frames = (seconds * sampleRate).toInt()
        val samples = ShortArray(frames * channels)
        for (i in 0 until frames) {
            val v = (level * 32767 * sin(2 * PI * hz * i / sampleRate)).toInt().toShort()
            for (c in 0 until channels) samples[i * channels + c] = v
        }
        return ScratchWindow(samples, sampleRate, channels, startSeconds = 0.0)
    }

    private fun rms(values: FloatArray, from: Int = 0, to: Int = values.size): Double {
        var sum = 0.0
        for (i in from until to) sum += values[i] * values[i]
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** How often a signal crosses zero going up, as a frequency. */
    private fun pitch(values: FloatArray, sampleRate: Int): Double {
        var crossings = 0
        var first = -1
        var last = -1
        for (i in 1 until values.size) {
            if (values[i - 1] < 0f && values[i] >= 0f) {
                if (first < 0) first = i
                last = i
                crossings++
            }
        }
        return if (crossings < 2) 0.0 else (crossings - 1) * sampleRate.toDouble() / (last - first)
    }

    private fun left(out: ShortArray, frames: Int) = FloatArray(frames) { out[2 * it] / 32768f }

    // ---- Under the motor, at speed: the record and nothing else ------------------------------

    @Test
    fun `at normal speed under the motor it is the record, sample for sample`() {
        val source = tone(440.0, 2.0)
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(position = 0.5, rate = 1.0, playing = true)
        engine.open()
        val frames = 4_800
        val out = ShortArray(frames * 2)
        // Long enough for any colour left from a hand to have gone.
        repeat(6) { engine.render(out, frames, 0L) }
        val startFrame = (engine.positionSeconds * rate).toInt() - frames
        var worst = 0
        for (i in 8 until frames - 8) {
            // The record moves a step before each sample is read.
            val want = source.samples[startFrame + i + 1].toInt()
            worst = maxOf(worst, abs(out[2 * i] - want))
        }
        assertTrue("differs from the record by $worst", worst <= 2)
        // One channel in, the same on both sides out.
        for (i in 0 until frames) assertEquals(out[2 * i], out[2 * i + 1])
    }

    @Test
    fun `a record decoded at another rate than the phone plays at still plays in tune`() {
        val source = tone(1000.0, 2.0, sampleRate = 44_100)
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(0.2, 1.0, playing = true)
        engine.open()
        val frames = 24_000
        val out = ShortArray(frames * 2)
        engine.render(out, frames, 0L)
        assertEquals(1000.0, pitch(left(out, frames), rate), 2.0)
    }

    // ---- The reader ----------------------------------------------------------------------------

    @Test
    fun `pitch is speed - twice as fast is an octave up, half as fast an octave down`() {
        val source = tone(440.0, 4.0)
        fun readAt(stride: Double): Double {
            val n = 20_000
            val out = FloatArray(n) { ScratchEngine.read(source, 2_000.0 + it * stride, stride, 0) }
            return pitch(out, rate)
        }
        assertEquals(880.0, readAt(2.0), 3.0)
        assertEquals(220.0, readAt(0.5), 1.5)
        assertEquals(440.0 * 3.3, readAt(3.3), 6.0)
    }

    @Test
    fun `a slow drag is smooth - the curve between samples is the tone, not a staircase`() {
        // A 2kHz tone read at a twentieth of its speed is a 100Hz tone, and should be a clean one.
        val source = tone(2000.0, 1.0, level = 0.8)
        val stride = 0.05
        var squared = 0.0
        val n = 20_000
        for (i in 0 until n) {
            val at = 4_000.0 + i * stride
            val want = 0.8 * 32767 / 32768.0 * sin(2 * PI * 2000.0 * at / rate)
            val error = ScratchEngine.read(source, at, stride, 0) - want
            squared += error * error
        }
        val errorDb = db(sqrt(squared / n) / (0.8 / sqrt(2.0)))
        assertTrue("roughness at $errorDb dB", errorDb < -48.0)
    }

    @Test
    fun `a fast stroke does not fold what it cannot play back down as hash`() {
        // 15kHz at three times speed is 45kHz: nothing a 48kHz output can carry. Read naively it
        // comes back as a loud false tone at 3kHz; the reader has to take most of that out.
        val source = tone(15_000.0, 1.0, level = 0.8)
        val stride = 3.0
        val n = 4_000
        val kept = FloatArray(n) { ScratchEngine.read(source, 3_000.0 + it * stride, stride, 0) }
        val naive = FloatArray(n) { source.samples[(3_000.0 + it * stride).toInt()] / 32768f }
        val down = db(rms(kept) / rms(naive))
        assertTrue("only $down dB down", down < -14.0)
    }

    @Test
    fun `a fast stroke keeps the music it can play - the averaging does not muffle it`() {
        // 2kHz at 2.5 times speed is 5kHz, well inside what can be heard and carried.
        val source = tone(2_000.0, 1.0, level = 0.5)
        val stride = 2.5
        val n = 4_000
        val out = FloatArray(n) { ScratchEngine.read(source, 3_000.0 + it * stride, stride, 0) }
        assertEquals(5_000.0, pitch(out, rate), 25.0)
        val kept = db(rms(out) / (0.5 / sqrt(2.0)))
        assertTrue("lost ${-kept} dB", kept > -3.0)
    }

    // ---- Loudness is speed ---------------------------------------------------------------------

    @Test
    fun `a record is as loud as it is fast, gently - still is silent, normal speed is itself`() {
        assertEquals(0f, ScratchEngine.loudness(0.0), 0f)
        assertEquals(1f, ScratchEngine.loudness(1.0), 1e-6f)
        var last = 0f
        var speed = 0.0
        while (speed <= ScratchEngine.SEARCH_FROM) {
            val now = ScratchEngine.loudness(speed)
            assertTrue("fell at $speed", now >= last)
            last = now
            speed += 0.01
        }
        // A slow drag is a growl, not a whisper: a fifth of normal speed is well down but there.
        val slow = db(ScratchEngine.loudness(0.2).toDouble())
        assertTrue("a fifth of speed is $slow dB", slow in -11.0..-6.0)
        // And a hard shove is not much louder than normal - it is already brighter and denser.
        assertTrue(ScratchEngine.loudness(4.0) <= 1.45f)
    }

    @Test
    fun `a record spun to find a place in it is a tape being cued, not the loudest thing there is`() {
        // No step where travelling begins...
        assertEquals(
            ScratchEngine.loudness(ScratchEngine.SEARCH_FROM - 1e-6),
            ScratchEngine.loudness(ScratchEngine.SEARCH_FROM + 1e-6),
            1e-4f
        )
        // ...and from there the faster it goes the quieter it is: under the music by eight
        // times speed, and well under by twelve.
        var last = ScratchEngine.loudness(ScratchEngine.SEARCH_FROM)
        var speed = ScratchEngine.SEARCH_FROM + 0.05
        while (speed <= 14.0) {
            val now = ScratchEngine.loudness(speed)
            assertTrue("rose at $speed", now < last)
            last = now
            speed += 0.05
        }
        assertTrue(ScratchEngine.loudness(8.0) < 0.75f)
        assertTrue(db(ScratchEngine.loudness(12.0).toDouble()) in -10.0..-6.0)
    }

    @Test
    fun `the grit adds density, never level past the rails, and none at all when asked for none`() {
        for (x in listOf(-1f, -0.5f, -0.01f, 0f, 0.01f, 0.5f, 1f)) {
            assertEquals(x, ScratchEngine.saturate(x, 0f), 1e-6f)
        }
        for (amount in listOf(0.3f, 1f, 1.7f)) {
            assertEquals(1f, ScratchEngine.saturate(1f, amount), 1e-5f)
            assertEquals(-1f, ScratchEngine.saturate(-1f, amount), 1e-5f)
            assertEquals(1f, ScratchEngine.saturate(3f, amount), 0f)
            // Odd-symmetric, and only ever rising.
            var last = -2f
            var x = -1f
            while (x <= 1f) {
                val y = ScratchEngine.saturate(x, amount)
                assertEquals(-y, ScratchEngine.saturate(-x, amount), 1e-5f)
                assertTrue(y >= last)
                last = y
                x += 0.01f
            }
            // Quiet sound comes up; that is the density.
            assertTrue(ScratchEngine.saturate(0.1f, amount) > 0.1f)
        }
        assertTrue(ScratchEngine.bite(3.0) > ScratchEngine.bite(0.2))
    }

    // ---- A hand on it --------------------------------------------------------------------------

    /** A scratch, back and forth, as the engine hears it: reports every 1/120s, read as they arrive. */
    private fun scratched(source: ScratchWindow, seconds: Double, where: (Double) -> Double): FloatArray {
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(1.0, 0.0, playing = false)
        engine.platter.grab(0.0)
        engine.hand.land(0.0, 0L)
        engine.open()
        val block = 96
        val out = ShortArray(block * 2)
        val all = FloatArray((seconds * rate).toInt() / block * block)
        var frame = 0
        var nextReport = 0.0
        while (frame < all.size) {
            val t = frame.toDouble() / rate
            if (t >= nextReport) {
                engine.hand.move(where(t), (t * 1e9).toLong(), (t * 1e9).toLong())
                nextReport += 1.0 / 120
            }
            engine.render(out, block, (t * 1e9).toLong())
            for (i in 0 until block) all[frame + i] = out[2 * i] / 32768f
            frame += block
        }
        return all
    }

    @Test
    fun `a stroke swells and dies - and where the hand turns round there is nothing`() {
        val source = tone(300.0, 3.0, level = 0.6)
        // Two strokes a second, a quarter of a second of music each way.
        val out = scratched(source, 2.0) { t -> 0.25 * sin(2 * PI * 1.0 * t) }
        // The hand is fastest at t = 1.0 (through the middle) and still at t = 0.75 and 1.25,
        // give or take the few hundredths of a second the record runs behind it.
        fun around(t: Double) = rms(out, ((t - 0.01) * rate).toInt(), ((t + 0.01) * rate).toInt())
        val loud = around(1.02)
        val quiet = minOf(around(0.76), around(0.77), around(0.78))
        assertTrue("middle of the stroke $loud", loud > 0.2)
        assertTrue("the turn is ${db(quiet / loud)} dB down", db(quiet / loud) < -20.0)
    }

    @Test
    fun `the turn is a fade, not a click`() {
        val source = tone(300.0, 3.0, level = 0.6)
        val out = scratched(source, 2.0) { t -> 0.25 * sin(2 * PI * 1.0 * t) }
        // A 300Hz tone played at up to 1.6 times speed and driven a little cannot move further
        // between two samples than this; a click would.
        var worst = 0f
        for (i in 1 until out.size) worst = maxOf(worst, abs(out[i] - out[i - 1]))
        assertTrue("jumped by $worst", worst < 0.12f)
    }

    @Test
    fun `a record held still makes no sound at all`() {
        val source = tone(300.0, 3.0, level = 0.9)
        val out = scratched(source, 1.0) { 0.0 }
        assertEquals(0.0, rms(out), 0.0)
    }

    @Test
    fun `off either end of what was decoded is silence, reached by a fade`() {
        val source = tone(300.0, 0.5, level = 0.9)
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(0.3, 1.0, playing = true)
        engine.open()
        val out = ShortArray(48_000 * 2)
        engine.render(out, 48_000, 0L)
        val heard = left(out, 48_000)
        // Past the end (0.5s of music, reached after 0.2s): nothing.
        assertEquals(0.0, rms(heard, 12_000, 48_000), 0.0)
        assertTrue(rms(heard, 0, 4_800) > 0.3)
        var worst = 0f
        for (i in 1 until heard.size) worst = maxOf(worst, abs(heard[i] - heard[i - 1]))
        assertTrue("ended with a jump of $worst", worst < 0.06f)
    }

    @Test
    fun `an array longer than its audio is only its audio - what lies past the end is not played`() {
        // A decode fills an array sized for what it was asked for; when the track ends first,
        // the rest of the array is not the record.
        val samples = ShortArray(48_000) { 12_000 }
        val window = ScratchWindow(samples, rate, 1, startSeconds = 3.0, frames = 24_000)
        assertEquals(24_000, window.frames)
        assertEquals(3.5, window.endSeconds, 1e-9)
        assertTrue(ScratchEngine.read(window, 10_000.0, 1.0, 0) > 0.3f)
        assertEquals(0f, ScratchEngine.read(window, 30_000.0, 1.0, 0), 0f)
        assertEquals(0f, ScratchEngine.read(window, 30_000.0, 3.0, 0), 0f)
        // And said nothing of, all of it is.
        assertEquals(48_000, ScratchWindow(samples, rate, 1, 0.0).frames)
        assertEquals(24_000, ScratchWindow(samples, rate, 2, 0.0).frames)
    }

    @Test
    fun `nothing leaves louder than full scale, whatever is thrown at it`() {
        val frames = 48_000
        val samples = ShortArray(frames) { if ((it / 37) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val out = scratched(ScratchWindow(samples, rate, 1, 0.0), 1.0) { t -> 0.4 * sin(2 * PI * 4.0 * t) }
        assertTrue(out.all { it in -1f..1f })
    }

    // ---- Handing back ------------------------------------------------------------------------

    @Test
    fun `let go, it winds up to pitch and is then the record again - nothing of the hand left on it`() {
        val source = tone(440.0, 6.0)
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(1.0, 0.0, playing = false)
        engine.platter.grab(0.0)
        engine.hand.land(0.0, 0L)
        engine.open()
        val block = 480
        val out = ShortArray(block * 2)
        repeat(20) { engine.render(out, block, 0L) }
        engine.platter.release(playing = true)

        // The wind-up: pitch rising toward the record's own.
        val rising = FloatArray(block * 12)
        for (b in 0 until 12) {
            engine.render(out, block, 0L)
            for (i in 0 until block) rising[b * block + i] = out[2 * i] / 32768f
        }
        val early = pitch(rising.copyOfRange(0, block * 5), rate)
        val later = pitch(rising.copyOfRange(block * 7, block * 12), rate)
        assertTrue("$early then $later", early < later && later < 445.0)

        // Half a second on: the record, and only the record. It came up to speed at whatever
        // place it had reached - between two samples, as likely as not - so it is held to the
        // tone itself at that place rather than to the samples either side.
        repeat(50) { engine.render(out, block, 0L) }
        assertTrue(engine.platter.atSpeed)
        engine.render(out, block, 0L)
        val end = engine.positionSeconds
        var worst = 0.0
        for (i in 8 until block - 8) {
            val at = end - (block - 1 - i).toDouble() / rate
            val want = 0.5 * 32767 * sin(2 * PI * 440.0 * at)
            worst = maxOf(worst, abs(out[2 * i] - want))
        }
        assertTrue("still differs by $worst", worst <= 4.0)
    }

    @Test
    fun `with the deck's tempo off centre, playing at that tempo is the record untouched`() {
        // Loudness and grit are both measured from the speed the record plays at. Measured from
        // 1 instead, a deck at half tempo would hand back a record four decibels down and
        // still coloured, to a player that is neither.
        val source = tone(440.0, 4.0)
        for (tempo in listOf(0.5, 0.8)) {
            val engine = ScratchEngine(rate)
            engine.window = source
            engine.platter.place(position = 0.5, rate = tempo, playing = true, nominal = tempo)
            engine.open()
            val frames = 4_800
            val out = ShortArray(frames * 2)
            repeat(6) { engine.render(out, frames, 0L) }
            val heard = left(out, frames)
            assertEquals("level at tempo $tempo", 0.5 / sqrt(2.0), rms(heard), 0.004)
            assertEquals("pitch at tempo $tempo", 440.0 * tempo, pitch(heard, rate), 1.0)
            // Untouched: a pure tone in, a pure tone out, to within the rounding to 16 bits.
            val startFrame = engine.positionSeconds * rate - frames * tempo
            var worst = 0.0
            for (i in 8 until frames - 8) {
                val want = 0.5 * sin(2 * PI * 440.0 * (startFrame + (i + 1) * tempo) / rate)
                worst = maxOf(worst, abs(heard[i] - want))
            }
            assertTrue("differs from the record by $worst at tempo $tempo", worst < 0.002)
        }
    }

    @Test
    fun `it plays at the level the player is at - turned down, it is turned down by exactly that`() {
        val source = tone(440.0, 3.0)
        fun render(master: Float, handled: Boolean): FloatArray {
            val engine = ScratchEngine(rate)
            engine.window = source
            engine.master = master
            engine.platter.place(0.5, 1.0, playing = true)
            engine.open()
            if (handled) {
                // A hand dragging it along at one and a half times speed: grit and all.
                engine.platter.grab(0.0)
                engine.hand.land(0.0, 0L)
            }
            val frames = 9_600
            val out = ShortArray(frames * 2)
            var t = 0.0
            for (block in 0 until 4) {
                if (handled) engine.hand.move(1.5 * (t + 0.2), (t * 1e9).toLong(), (t * 1e9).toLong())
                engine.render(out, frames, (t * 1e9).toLong())
                t += frames.toDouble() / rate
            }
            return left(out, frames)
        }
        for (handled in listOf(false, true)) {
            val full = render(1f, handled)
            val half = render(0.5f, handled)
            // The same sound at half the level, sample for sample: the levelling comes after the
            // grit, so a quiet record in the hand is not also a cleaner one.
            assertTrue(rms(full) > 0.1)
            for (i in full.indices) assertEquals(full[i] * 0.5f, half[i], 2f / 32768f)
        }
        assertEquals(0.0, rms(render(0f, handled = true)), 0.0)
    }

    @Test
    fun `set down out of a spin it is the record at once, and its level does not jump to get there`() {
        val source = tone(440.0, 12.0)
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(1.0, 0.0, playing = false)
        engine.open()
        engine.platter.grab(0.0)
        engine.hand.land(0.0, 0L)
        val frames = 480
        val out = ShortArray(frames * 2)
        // A hand spinning the disc forwards at twelve times speed for half a second...
        var t = 0.0
        repeat(50) {
            engine.hand.move(12.0 * (t + 0.01), (t * 1e9).toLong(), (t * 1e9).toLong())
            engine.render(out, frames, (t * 1e9).toLong())
            t += frames.toDouble() / rate
        }
        assertTrue("it was going ${engine.rate}", engine.rate > 9f)
        val quiet = rms(left(out, frames))
        // ...sets it down.
        engine.place(playing = true)
        val after = FloatArray(frames * 8)
        repeat(8) { block ->
            engine.render(out, frames, (t * 1e9).toLong())
            t += frames.toDouble() / rate
            for (i in 0 until frames) after[block * frames + i] = out[2 * i] / 32768f
        }
        assertEquals(1f, engine.rate, 0f)
        assertTrue(engine.atSpeed)
        // Its level climbs to the record's own, and never by a step: no sample moves further
        // from the last than the loudest the tone itself ever does.
        val tonesOwn = 0.5 * 2 * PI * 440.0 / rate
        var worst = 0.0
        for (i in 1 until after.size) worst = maxOf(worst, abs(after[i] - after[i - 1]).toDouble())
        assertTrue("stepped by $worst where the tone moves $tonesOwn", worst < tonesOwn * 2.2)
        assertTrue("a spin is quieter than the record ($quiet)", quiet < 0.22)
        // The first thousandth of a second after it is set down is still nearer the spin's
        // level than the record's: reached, not switched to. (Switched, this is the record's
        // own level from the first sample — and that edge is a tick on every seek.)
        val record = 0.5 / sqrt(2.0)
        assertTrue("its first millisecond was at ${rms(after, 0, 48)}", rms(after, 0, 48) < record * 0.8)
        // A fiftieth of a second on, it is the record: its level, its pitch, nothing of the hand's speed.
        val settled = after.copyOfRange(frames * 3, frames * 8)
        assertEquals(0.5 / sqrt(2.0), rms(settled), 0.02)
        assertEquals(440.0, pitch(settled, rate), 2.0)
    }

    @Test
    fun `sound that arrives where there was none is brought up, not switched on`() {
        val engine = ScratchEngine(rate)
        // Playing, and what is decoded is somewhere else on the record: silence, with the record
        // turning at speed — which is a disc spun past the end of its stretch.
        engine.window = ScratchWindow(ShortArray(rate) { 9_000 }, rate, 1, startSeconds = 60.0)
        engine.platter.place(3.0003, 1.0, playing = true)
        engine.open()
        val frames = 960
        val out = ShortArray(frames * 2)
        repeat(4) { engine.render(out, frames, 0L) }
        assertEquals(0.0, rms(left(out, frames)), 0.0)
        // The stretch for where it is turns up, mid-wave: 441Hz is not at a crossing here.
        val stretch = tone(441.0, 8.0)
        val startsAt = stretch.samples[((engine.positionSeconds) * rate).toInt() + 1] / 32768f
        assertTrue("the wave is at $startsAt as it arrives", kotlin.math.abs(startsAt) > 0.1f)
        engine.arrive(stretch)
        engine.render(out, frames, 0L)
        val heard = left(out, frames)
        assertTrue("began at ${heard[0]}", abs(heard[0]) < 0.02f)
        assertTrue(rms(heard, 0, 96) < rms(heard, 600, 960) * 0.35)
        // A hundredth of a second later it is all there.
        assertEquals(0.5 / sqrt(2.0), rms(heard, 600, 960), 0.03)
    }

    @Test
    fun `a fade is asked for and arrives over the time asked, both ways`() {
        val source = tone(440.0, 3.0)
        val engine = ScratchEngine(rate)
        engine.window = source
        engine.platter.place(0.5, 1.0, playing = true)
        val out = ShortArray(4_800 * 2)
        engine.render(out, 4_800, 0L)
        assertEquals(0.0, rms(left(out, 4_800)), 0.0)
        engine.fade(1f, 0.05f)
        engine.render(out, 4_800, 0L)
        val rising = left(out, 4_800)
        assertTrue(rms(rising, 0, 480) < rms(rising, 1_900, 2_400))
        assertEquals(rms(rising, 2_400, 3_600), rms(rising, 3_600, 4_800), 0.01)
        engine.fade(0f, 0.05f)
        engine.render(out, 4_800, 0L)
        assertEquals(0.0, rms(left(out, 4_800), 2_500, 4_800), 0.0)
        assertTrue(engine.silent)
    }
}
