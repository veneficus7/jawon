package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The light the face is seen by, as far as it is arithmetic.
 *
 * **Two copies of this room exist and must agree**: this one, and `tools/models/pocket_light.py`,
 * which the face was designed against in Cycles. The pinned values are the Python's own output for
 * the same directions — change one copy alone and the first test fails.
 *
 * Directions are elevation (up the phone from the face's normal) and azimuth (round to the right,
 * from straight back at the person holding it), in degrees, in the face's frame.
 */
class PocketLightingTest {

    private fun direction(elDeg: Double, azDeg: Double): FloatArray {
        val e = Math.toRadians(elDeg)
        val a = Math.toRadians(azDeg)
        return floatArrayOf((cos(e) * sin(a)).toFloat(), sin(e).toFloat(), (cos(e) * cos(a)).toFloat())
    }

    private fun radiance(elDeg: Double, azDeg: Double): FloatArray {
        val d = direction(elDeg, azDeg)
        return FloatArray(3).also { PocketLighting.radiance(d[0], d[1], d[2], it) }
    }

    private fun luminance(rgb: FloatArray) = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]

    private fun assertRgb(expected: DoubleArray, elDeg: Double, azDeg: Double) {
        val actual = radiance(elDeg, azDeg)
        for (c in 0..2) {
            // A tenth of a per cent: floats against doubles, and an acos near a light's centre.
            assertEquals(
                "($elDeg, $azDeg) channel $c",
                expected[c], actual[c].toDouble(), 1e-3 * maxOf(0.01, expected[c])
            )
        }
    }

    @Test
    fun `the room is the room the face was designed in`() {
        // pocket_light.radiance, for the same directions.
        assertRgb(doubleArrayOf(0.557498, 0.531819, 0.506199), 0.0, 0.0)       // straight back at you
        assertRgb(doubleArrayOf(0.007210, 0.006623, 0.006084), 3.3, 0.0)       // the glass's lowest row
        assertRgb(doubleArrayOf(2.080630, 2.080252, 2.105822), 13.0, 0.0)      // the face's top
        assertRgb(doubleArrayOf(8.006021, 8.005814, 8.105608), 40.0, 0.0)      // the ceiling light
        assertRgb(doubleArrayOf(16.006000, 16.005800, 16.205600), 85.0, 10.0)  // overhead
        assertRgb(doubleArrayOf(5.006000, 3.405800, 2.005600), -12.0, -62.0)   // the lamp
        assertRgb(doubleArrayOf(1.406000, 1.705800, 2.305600), 15.0, 80.0)     // the window
        assertRgb(doubleArrayOf(1.108818, 1.057716, 1.006727), -15.0, 0.0)     // you, lit
        assertRgb(doubleArrayOf(0.356000, 0.305800, 0.245600), -60.0, 150.0)   // the floor
        assertRgb(doubleArrayOf(0.006000, 0.005800, 0.005600), 10.0, 180.0)    // behind the phone
    }

    @Test
    fun `the screen's glass reflects nothing bright`() {
        // Seen straight on, the picture's glass looks back at you from 3.3° to 11° up. Anything
        // bright there is a glare across the menu; the ceiling light stops above it and the band
        // of you below it, so the whole span stays at the dark of the room.
        var el = 3.3
        while (el <= 11.0) {
            val l = luminance(radiance(el, 0.0))
            assertTrue("a glare of $l at $el°", l < 0.02f)
            el += 0.1
        }
    }

    @Test
    fun `the face is brighter at its top than at its foot`() {
        // The top reflects ~13° up, the foot ~-14°: the ceiling light's glow is the gradient.
        assertTrue(luminance(radiance(13.0, 0.0)) > 1.5f * luminance(radiance(-14.0, 0.0)))
    }

    @Test
    fun `a dark finish has something to show below the screen`() {
        // A black face is a near-mirror: below the screen it reflects -14° to 0°, and with nothing
        // there it rendered as a void. The band of you is what it shows.
        assertTrue(luminance(radiance(-10.0, 0.0)) > 0.5f)
    }

    @Test
    fun `overhead is the brightest thing in the room`() {
        // What every edge facing up catches: the window's lower lip, the top of the wheel's rim.
        val zenith = luminance(radiance(85.0, 0.0))
        for (el in listOf(40.0, 13.0, 0.0, -12.0)) assertTrue(zenith > luminance(radiance(el, 0.0)))
    }
}
