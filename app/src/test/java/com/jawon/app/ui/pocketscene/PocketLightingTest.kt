package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The carriage the pocket player is lit by. Pure, so the one thing about it that is arithmetic —
 * *where* each light sits against the directions the device's surfaces reflect — can be held here.
 *
 * Those directions were measured, not derived: the device rendered as a perfect mirror in an
 * environment coloured by direction (`tools/pocket-preview/`), at the room's camera and pose. The
 * front face reflects azimuth ~60°, from elevation ~3° at its foot to ~20° at its top; the wheel
 * ~7°. Azimuth is `atan2(z, x)`, as in [PocketLighting.radiance].
 */
class PocketLightingTest {

    private fun luminance(azDeg: Double, elDeg: Double): Float {
        val az = Math.toRadians(azDeg)
        val el = Math.toRadians(elDeg)
        val out = FloatArray(3)
        PocketLighting.radiance(
            (cos(el) * cos(az)).toFloat(),
            sin(el).toFloat(),
            (cos(el) * sin(az)).toFloat(),
            out
        )
        return 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2]
    }

    @Test
    fun `the foot of the face reflects light, not the dark of the carriage`() {
        // 0.69 with the softbox's skirt; 0.12 without it, which is what left the lower half of the
        // device and its base with no edge against the night.
        assertTrue(luminance(60.0, 3.0) > 0.5f)
        assertTrue("the wheel reflects the skirt too", luminance(62.0, 7.0) > 0.6f)
    }

    @Test
    fun `the face keeps its gradient, brightest at the top`() {
        val top = luminance(60.0, 20.0)
        val foot = luminance(60.0, 3.0)
        // 0.26 as tuned. Much above half and the anodising reads as one flat sheet; much below a
        // sixth and the foot is back in the dark.
        assertTrue("foot $foot against top $top", foot < top * 0.5f)
        assertTrue("foot $foot against top $top", foot > top * 0.15f)
    }

    @Test
    fun `the skirt has no edge where the softbox ends`() {
        // The screen's glass is nearly a mirror and looks into this band. A hard start to the
        // skirt is a 42% jump between neighbouring quarter-degrees; the soft onset is about 6%.
        var previous = luminance(60.0, 4.0)
        var el = 4.25
        while (el <= 21.0) {
            val now = luminance(60.0, el)
            val step = abs(now - previous) / previous
            assertTrue("a ${"%.0f".format(step * 100)}% step at ${"%.2f".format(el)}°", step < 0.12f)
            previous = now
            el += 0.25
        }
    }

    @Test
    fun `away from its lights, the carriage stays dark`() {
        // Behind you and to the left, above the windows and below the ceiling: nothing there. The
        // lift is the skirt, placed where the face looks — not the ambient turned up, which would
        // lift every black the device has.
        assertTrue(luminance(150.0, 23.0) < 0.02f)
    }
}
