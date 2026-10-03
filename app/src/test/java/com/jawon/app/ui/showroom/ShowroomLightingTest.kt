package com.jawon.app.ui.showroom

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gallery's environment is placed for what the exhibits' faces reflect, and each rule here is a
 * look that was wrong on the emulator before it was right: a clear case under a grey veil, a black
 * face that never caught anything, a shadow that read as a hole in its plinth.
 */
class ShowroomLightingTest {

    private val out = FloatArray(3)

    private fun luminance(dx: Float, dy: Float, dz: Float): Float {
        ShowroomLighting.radiance(dx, dy, dz, out)
        return 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2]
    }

    /**
     * Where a vertical face turned [yawDeg] from the camera reflects the camera's view, the camera
     * [aboveDeg] above it: `π/2 − 2·yaw` round, and as far *below* level as the camera is above.
     */
    private fun reflected(yawDeg: Float, aboveDeg: Float): Float {
        val az = (Math.PI / 2.0 - 2.0 * Math.toRadians(yawDeg.toDouble())).toFloat()
        val el = -Math.toRadians(aboveDeg.toDouble()).toFloat()
        return luminance(cos(el) * cos(az), sin(el), cos(el) * sin(az))
    }

    @Test
    fun `a face square to the camera reflects the dark, so a clear front is not veiled`() {
        assertTrue(reflected(yawDeg = 0f, aboveDeg = 7f) < 0.15f)
    }

    @Test
    fun `a face turning either way finds a strip on its way round`() {
        val right = (0..25).maxOf { reflected(yawDeg = -it.toFloat(), aboveDeg = 7f) }
        val left = (0..25).maxOf { reflected(yawDeg = it.toFloat(), aboveDeg = 7f) }
        assertTrue("key side $right", right > 1f)
        assertTrue("fill side $left", left > 0.6f)
    }

    @Test
    fun `the ceiling is lit, so a shadow on a cap has something filling it`() {
        assertTrue(luminance(0f, 1f, 0f) > 1f)
    }

    @Test
    fun `below level is the dark of the floor`() {
        assertTrue(luminance(0f, -1f, 0f) < 0.02f)
    }
}
