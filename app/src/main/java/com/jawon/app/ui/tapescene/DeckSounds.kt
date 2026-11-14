package com.jawon.app.ui.tapescene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * **What the deck's mechanism sounds like**: keys going down and coming up, PLAY catching, the
 * door, a cassette seated, the head turning over at a reverse, the mechanism letting go at the
 * end of the tape — and the motor winding.
 *
 * Made, not recorded, for the reason the needle is (`NeedleSound`): a sample is a file with
 * somebody else's deck in it, and this is the same on every phone. Each is a few damped
 * resonances — the plastic of a key, the metal under it, the thump of a lever — and a breath of
 * noise. Pure: `DeckSoundsTest` holds that none of them clips, clicks at its ends, or is louder
 * than the music it sits beside.
 */
object DeckSounds {

    /** The loudest any of them is, of full scale: a mechanism in the room, not a sound effect over it. */
    const val PEAK = 0.24f

    private class Ring(val hz: Float, val tauS: Float, val level: Float, val atS: Float = 0f)

    private class Recipe(val rings: List<Ring>, val noise: Float, val noiseTauS: Float, val seconds: Float, val peak: Float)

    private val recipes = mapOf(
        DeckEvent.KEY_DOWN to Recipe(listOf(Ring(1850f, 0.008f, 0.6f), Ring(3400f, 0.004f, 0.4f), Ring(140f, 0.016f, 0.7f)), 0.25f, 0.003f, 0.12f, 0.17f),
        DeckEvent.KEY_UP to Recipe(listOf(Ring(2600f, 0.005f, 0.6f), Ring(900f, 0.006f, 0.3f)), 0.15f, 0.002f, 0.08f, 0.09f),
        DeckEvent.LATCH to Recipe(listOf(Ring(92f, 0.030f, 1.0f), Ring(1200f, 0.012f, 0.6f, 0.022f), Ring(2300f, 0.006f, 0.3f, 0.024f)), 0.3f, 0.006f, 0.22f, PEAK),
        DeckEvent.DOOR_OPEN to Recipe(listOf(Ring(70f, 0.060f, 1.0f), Ring(520f, 0.030f, 0.25f), Ring(1600f, 0.008f, 0.3f)), 0.35f, 0.040f, 0.32f, 0.21f),
        DeckEvent.DOOR_CLOSE to Recipe(listOf(Ring(2200f, 0.006f, 0.6f), Ring(110f, 0.020f, 0.8f), Ring(760f, 0.010f, 0.3f)), 0.2f, 0.004f, 0.16f, 0.20f),
        DeckEvent.SEAT to Recipe(listOf(Ring(1600f, 0.010f, 0.7f), Ring(2900f, 0.006f, 0.5f), Ring(180f, 0.012f, 0.5f)), 0.25f, 0.004f, 0.12f, 0.19f),
        DeckEvent.REVERSE to Recipe(listOf(Ring(80f, 0.025f, 1.0f), Ring(1000f, 0.010f, 0.6f), Ring(1400f, 0.010f, 0.5f, 0.30f), Ring(85f, 0.022f, 0.8f, 0.30f)), 0.25f, 0.008f, 0.46f, PEAK),
        DeckEvent.AUTO_STOP to Recipe(listOf(Ring(110f, 0.025f, 1.0f), Ring(1350f, 0.010f, 0.5f), Ring(2600f, 0.005f, 0.25f)), 0.25f, 0.005f, 0.20f, 0.22f),
        DeckEvent.TICK to Recipe(listOf(Ring(3000f, 0.003f, 1.0f)), 0.2f, 0.0015f, 0.04f, 0.06f)
    )

    /** [event] at [sampleRateHz], mono, its loudest sample at its own peak. */
    fun render(event: DeckEvent, sampleRateHz: Int, seed: Long = event.ordinal * 7919L + 13L): FloatArray {
        val recipe = recipes.getValue(event)
        val n = (recipe.seconds * sampleRateHz).toInt()
        val out = FloatArray(n)
        var state = seed
        fun random(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 40).toInt() and 0xFFFF) / 32767.5f - 1f
        }
        for (ring in recipe.rings) {
            val start = (ring.atS * sampleRateHz).toInt()
            for (i in start until n) {
                val t = (i - start) / sampleRateHz.toFloat()
                // Up from nothing in half a millisecond: a tone that began at full height would be a click of its own.
                val attack = (t / 0.0005f).coerceAtMost(1f)
                out[i] += ring.level * attack * exp(-t / ring.tauS) * sin(2f * PI.toFloat() * ring.hz * t)
            }
        }
        var low = 0f
        for (i in 0 until n) {
            val t = i / sampleRateHz.toFloat()
            val r = random()
            low += 0.35f * (r - low)
            // The same half-millisecond rise as the tones: noise that starts at full height clicks.
            out[i] += recipe.noise * (r - low) * exp(-t / recipe.noiseTauS) * (t / 0.0005f).coerceAtMost(1f)
        }
        // Out to nothing over the last few milliseconds, and scaled to its peak.
        val tail = (0.008f * sampleRateHz).toInt().coerceAtMost(n)
        var loudest = 1e-6f
        for (i in 0 until n) {
            if (i >= n - tail) out[i] *= (n - 1 - i) / tail.toFloat()
            loudest = maxOf(loudest, abs(out[i]))
        }
        val gain = recipe.peak / loudest
        for (i in 0 until n) out[i] *= gain
        return out
    }

    /**
     * The motor winding, one sample at a time: a whine whose pitch climbs with the reel it drives
     * — which is how everybody knew a tape was nearly at its end without looking — over the
     * rustle of tape going by. Kept by the caller in [state]; [level] 0..1, [reelRadPerS] the
     * faster reel's speed.
     */
    class Motor(private val sampleRateHz: Int) {
        private var phase = 0.0
        private var hz = 0f
        private var amp = 0f
        private var low = 0f
        private var seed = 99L

        fun next(level: Float, reelRadPerS: Float): Float {
            val revs = reelRadPerS / (2f * PI.toFloat())
            val targetHz = 150f + 22f * revs.coerceAtMost(40f)
            hz += (targetHz - hz) * 0.0006f
            amp += (level * MOTOR_LEVEL - amp) * 0.0008f
            if (amp < 1e-5f) return 0f
            phase += 2.0 * PI * hz / sampleRateHz
            if (phase > 2 * PI) phase -= 2 * PI
            val p = phase.toFloat()
            val tone = sin(p) * 0.6f + sin(2f * p) * 0.28f + sin(3f * p) * 0.12f
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val r = ((seed ushr 40).toInt() and 0xFFFF) / 32767.5f - 1f
            low += 0.12f * (r - low)
            return amp * (tone * 0.55f + low * 1.4f)
        }

        companion object {
            const val MOTOR_LEVEL = 0.08f
        }
    }
}
