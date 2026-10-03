package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carriage's ceiling, as the polished bead sees it: two runs of light down its length, traced
 * onto the ceiling rather than painted on the sky — see `PocketLighting`'s note on them.
 */
class PocketLightingTest {

    private fun luminance(x: Float, y: Float, z: Float): Float {
        val out = FloatArray(3)
        PocketLighting.radiance(x, y, z, out)
        return 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2]
    }

    @Test
    fun `a run is lit straight overhead, over the window and over the aisle`() {
        assertTrue(luminance(0f, 1.05f, -0.30f) > 3f)
        assertTrue(luminance(0f, 1.05f, 0.60f) > 3f)
    }

    @Test
    fun `the ceiling between the two runs is dark`() {
        assertTrue(luminance(0f, 1.05f, 0.15f) < 0.1f)
    }

    @Test
    fun `the device sits under a fitting, not a gap, and the next gap is dark`() {
        assertTrue(luminance(0.30f, 1.05f, -0.30f) > 3f)
        assertTrue(luminance(0.75f, 1.05f, -0.30f) < 0.1f)
    }

    @Test
    fun `far down the carriage a run is still there, and still only as wide as a run`() {
        assertTrue(luminance(8f, 1.05f, 0.60f) > 3f)
        assertTrue(luminance(8f, 1.05f, 0.75f) < 0.3f)
    }

    @Test
    fun `past the end of the carriage there is no ceiling to light`() {
        assertTrue(luminance(20f, 1.05f, 0.60f) < 0.3f)
    }
}
