package com.jawon.app.ui.pocketscene

import kotlin.math.ln
import kotlin.math.min

/**
 * The lights going past the train, as *light* — the ones that land on the device.
 *
 * The window behind the device (`night_window.mat`) is a picture: it cannot light anything. What
 * makes a glossy object read as being somewhere is the world moving across it, so these are real
 * point lights the room moves through the scene on an irregular schedule:
 *
 * - **Behind you**, past the windows on the other side of the carriage. A lamp there is what the
 *   device's glass and its anodised face reflect, so its glint slides across the screen.
 * - **Behind the device**, past the window you are looking through. Those light the device from
 *   behind: a rim along the polished edge, the chamfer catching it.
 *
 * **Never periodic.** Gaps are drawn from an exponential, with the odd cluster — a station, a
 * level crossing — and the odd long dark stretch, so there is no rhythm for the eye to find
 * (`ENGINEERING.md`'s standard for motion). Seeded, so a test can replay it.
 *
 * Pure: positions in the room's metres, intensities in candela, no Filament. The room writes
 * them onto a fixed pool of light nodes — and switches an idle one *off* by intensity, never by
 * hiding it (trap 24).
 */
class PassingLamps(seed: Long = 7L) {

    class Lamp {
        var active = false
        var x = 0f
        var y = 0f
        var z = 0f
        var r = 1f
        var g = 1f
        var b = 1f
        /** Candela right now; zero when idle. */
        var intensity = 0f

        internal var fromX = 0f
        internal var toX = 0f
        internal var speed = 0f
        internal var peak = 0f
    }

    val lamps: Array<Lamp> = Array(COUNT) { Lamp() }

    private val random = java.util.Random(seed)
    private var clock = 0.0
    private var nextAt = FIRST_AFTER_S
    private var clusterLeft = 0

    /** Seconds since the first frame. */
    val time: Double get() = clock

    /**
     * Moves every lamp on by [dtS], and starts new ones as the schedule comes due.
     * [pace] scales the lamps' speed with the train's — 1 at its base speed.
     */
    fun advance(dtS: Float, pace: Float = 1f) {
        if (dtS <= 0f) return
        val dt = dtS.coerceAtMost(0.1f)
        clock += dt
        for (lamp in lamps) if (lamp.active) move(lamp, dt * pace)
        while (clock >= nextAt) {
            start()
            nextAt += nextGap()
        }
    }

    private fun move(lamp: Lamp, dt: Float) {
        lamp.x -= lamp.speed * dt
        val span = lamp.fromX - lamp.toX
        val done = (lamp.fromX - lamp.x) / span
        if (done >= 1f) {
            lamp.active = false
            lamp.intensity = 0f
            return
        }
        // Faded in and out at the ends of its run: that is the window frame it passes behind,
        // and it keeps a lamp from switching on in mid-air.
        lamp.intensity = lamp.peak * edge(done) * edge(1f - done)
    }

    private fun start() {
        val lamp = lamps.firstOrNull { !it.active } ?: return
        val behindYou = random.nextFloat() < BEHIND_YOU_SHARE
        if (behindYou) {
            lamp.z = between(1.3f, 1.8f)
            lamp.y = between(0.12f, 0.55f)
            lamp.fromX = 2.6f
            lamp.toX = -2.6f
            lamp.speed = between(2.6f, 4.6f)
            // At 900–2600cd the first build washed the *whole* anodised face bronze as each
            // lamp went by (face 16 -> 60 of 255): roughness 0.34 spreads a lamp this near
            // across all of it. This keeps the sheen a passing thing and leaves the glass's glint,
            // which the smoother glass holds tight, doing the work.
            lamp.peak = between(380f, 1_100f)
        } else {
            lamp.z = between(-1.0f, -0.6f)
            lamp.y = between(-0.10f, 0.35f)
            lamp.fromX = 1.4f
            lamp.toX = -1.4f
            lamp.speed = between(1.2f, 2.2f)
            lamp.peak = between(600f, 1_800f)
        }
        val colour = random.nextFloat()
        when {
            colour < 0.52f -> lamp.setColour(1.0f, 0.50f, 0.18f)   // sodium
            colour < 0.82f -> lamp.setColour(1.0f, 0.80f, 0.58f)   // warm white
            else -> lamp.setColour(0.74f, 0.85f, 1.0f)             // LED
        }
        lamp.x = lamp.fromX
        lamp.intensity = 0f
        lamp.active = true
    }

    /**
     * The wait until the next lamp: usually a couple of seconds, exponentially spread; sometimes
     * a cluster of three to five close together; sometimes a long dark run of fields.
     */
    private fun nextGap(): Double {
        if (clusterLeft > 0) {
            clusterLeft--
            return between(0.22f, 0.55f).toDouble()
        }
        val roll = random.nextFloat()
        return when {
            roll < CLUSTER_CHANCE -> {
                clusterLeft = 2 + random.nextInt(3)
                between(0.22f, 0.55f).toDouble()
            }
            roll < CLUSTER_CHANCE + DARK_CHANCE -> between(6f, 10f).toDouble()
            else -> {
                val u = random.nextDouble().coerceAtLeast(1e-6)
                min(-ln(u) * MEAN_GAP_S, MAX_GAP_S).coerceAtLeast(MIN_GAP_S)
            }
        }
    }

    private fun Lamp.setColour(red: Float, green: Float, blue: Float) {
        r = red; g = green; b = blue
    }

    private fun between(lo: Float, hi: Float) = lo + (hi - lo) * random.nextFloat()

    /** 0 at the very end of a run, 1 once it is 15% in. */
    private fun edge(t: Float): Float {
        val x = (t / FADE_SHARE).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        /** Lights in the pool — as many as can be passing at once. */
        const val COUNT = 4
        const val FIRST_AFTER_S = 1.2
        const val MEAN_GAP_S = 2.1
        const val MIN_GAP_S = 0.35
        const val MAX_GAP_S = 7.0
        const val CLUSTER_CHANCE = 0.14f
        const val DARK_CHANCE = 0.09f
        const val BEHIND_YOU_SHARE = 0.6f
        private const val FADE_SHARE = 0.15f
    }
}
