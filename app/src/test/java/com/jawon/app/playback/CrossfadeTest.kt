package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The crossfade's arithmetic.
 *
 * Worth testing precisely because the rest of the feature cannot be: whether a crossfade
 * *sounds* right is a question for ears and a real device, so the part that can be pinned
 * down — when it fires and how loud each side is — should be.
 */
class CrossfadeTest {

    private val tolerance = 0.001f

    // ---- The curve ------------------------------------------------------------------

    @Test
    fun `starts with the outgoing track at full and the incoming silent`() {
        assertEquals(1f, Crossfade.gainOut(0f), tolerance)
        assertEquals(0f, Crossfade.gainIn(0f), tolerance)
    }

    @Test
    fun `ends with the outgoing track silent and the incoming at full`() {
        assertEquals(0f, Crossfade.gainOut(1f), tolerance)
        assertEquals(1f, Crossfade.gainIn(1f), tolerance)
    }

    /**
     * The reason for cosine and sine rather than two straight lines.
     *
     * Uncorrelated signals sum in power, so constant *power* is what keeps the loudness
     * steady through the overlap. A linear pair would give 0.5 + 0.5 here and sound like a
     * dip; equal-power gives two signals at 1/√2, whose squares sum to 1.
     */
    @Test
    fun `holds constant power through the fade, with no dip at the midpoint`() {
        var t = 0f
        while (t <= 1f) {
            val power = Crossfade.gainOut(t) * Crossfade.gainOut(t) +
                Crossfade.gainIn(t) * Crossfade.gainIn(t)
            assertTrue(
                "power was $power at t=$t, expected 1",
                abs(power - 1f) < 0.001f
            )
            t += 0.05f
        }
        // And specifically at the midpoint, which is where a linear fade audibly sags.
        assertEquals(1f / sqrt(2f), Crossfade.gainOut(0.5f), tolerance)
        assertEquals(1f / sqrt(2f), Crossfade.gainIn(0.5f), tolerance)
    }

    @Test
    fun `clamps progress outside zero to one`() {
        assertEquals(1f, Crossfade.gainOut(-0.5f), tolerance)
        assertEquals(0f, Crossfade.gainOut(2f), tolerance)
        assertEquals(0f, Crossfade.gainIn(-0.5f), tolerance)
        assertEquals(1f, Crossfade.gainIn(2f), tolerance)
    }

    // ---- When it fires --------------------------------------------------------------

    @Test
    fun `a normal track fades for the requested length`() {
        val fourMinutes = 240_000L
        assertEquals(4000, Crossfade.effectiveMs(fourMinutes, 4000))
        assertEquals(236_000L, Crossfade.startAtMs(fourMinutes, 4000))
    }

    /**
     * A short track must not be mostly crossfade. Twelve seconds of fade on a twenty-second
     * interlude would leave four seconds of it audible on its own.
     */
    @Test
    fun `a track shorter than twice the fade never fades more than half of itself`() {
        val twentySeconds = 20_000L
        assertEquals(10_000L, Crossfade.startAtMs(twentySeconds, 12_000))
        assertEquals(10_000, Crossfade.effectiveMs(twentySeconds, 12_000))
    }

    @Test
    fun `an unknown duration never arms`() {
        assertFalse(Crossfade.shouldArm(0L, 0L, 4000, hasNextTrack = true))
        assertFalse(Crossfade.shouldStart(0L, -1L, 4000, hasNextTrack = true))
        assertEquals(0, Crossfade.effectiveMs(0L, 4000))
    }

    @Test
    fun `the last track of an album never fades`() {
        val position = 236_500L
        assertFalse(Crossfade.shouldArm(position, 240_000L, 4000, hasNextTrack = false))
        assertFalse(Crossfade.shouldStart(position, 240_000L, 4000, hasNextTrack = false))
    }

    @Test
    fun `a fade below the minimum is treated as off`() {
        assertEquals(0, Crossfade.effectiveMs(240_000L, Crossfade.MIN_MS - 1))
        assertFalse(
            Crossfade.shouldStart(239_000L, 240_000L, Crossfade.MIN_MS - 1, hasNextTrack = true)
        )
    }

    @Test
    fun `arms during the preload window and not before it`() {
        val duration = 240_000L
        val fade = 4000
        val startAt = 236_000L
        val armFrom = startAt - Crossfade.PRELOAD_MS

        assertFalse(Crossfade.shouldArm(armFrom - 1, duration, fade, hasNextTrack = true))
        assertTrue(Crossfade.shouldArm(armFrom, duration, fade, hasNextTrack = true))
        assertTrue(Crossfade.shouldArm(startAt - 1, duration, fade, hasNextTrack = true))
        // Past the start it is too late to arm — it should be running instead.
        assertFalse(Crossfade.shouldArm(startAt, duration, fade, hasNextTrack = true))
    }

    @Test
    fun `starts exactly at the fade point and stays started`() {
        val duration = 240_000L
        val fade = 4000
        assertFalse(Crossfade.shouldStart(235_999L, duration, fade, hasNextTrack = true))
        assertTrue(Crossfade.shouldStart(236_000L, duration, fade, hasNextTrack = true))
        assertTrue(Crossfade.shouldStart(239_000L, duration, fade, hasNextTrack = true))
    }
}
