package com.jawon.app.ui.wallscene

import com.jawon.app.ui.theme.RoomScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grade's *rules*, not its taste.
 *
 * How warm Vesper should look is a judgement to make on the device against real artwork — no
 * test can hold an opinion about that. What a test can hold is the design behind the numbers,
 * and that design is falsifiable: every mode must crush its blacks, no mode may wash out, the
 * cold modes must not be warmed, and each mode's shadows must lean away from its own key light
 * rather than with it. Those are the claims [RoomGrade]'s KDoc makes, so they are the claims
 * worth guarding — a later tuning pass that quietly violates one of them would undo the reason
 * the file exists while leaving every value looking plausible.
 */
class RoomGradeTest {

    private val all = RoomScheme.entries

    @Test
    fun `every mode has a grade`() {
        // A `when` over the enum is exhaustive at compile time, so this is really asserting that
        // a new mode added later cannot reach the renderer ungraded — it would not compile, and
        // if the `when` ever gains an `else` this test is what notices.
        for (scheme in all) {
            val spec = RoomGrade.specFor(scheme)
            assertEquals("${scheme.name} shadowGamma", 3, spec.shadowGamma.size)
        }
    }

    @Test
    fun `the grade leans the shadows without darkening them`() {
        // **This test used to assert the opposite, and the opposite was wrong.**
        //
        // It required every channel's gamma to be above 1 — "grading a dark room means putting
        // the black back in". True of a scene with a normal spread of tones, and catastrophic
        // here: this room is almost entirely shadow, its wall albedo running from 10% down to
        // zero, so crushing the shadows and crushing the room are the same instruction. The
        // device answered in one look: the wall went black.
        //
        // What the gammas are actually for is the *spread* between channels, which is what makes
        // shadows lean away from the key light. So the rule is that they average out neutral
        // while still differing from each other.
        for (scheme in all) {
            val spec = RoomGrade.specFor(scheme)
            val mean = spec.shadowGamma.average().toFloat()

            assertTrue(
                "${scheme.name} shadow gamma averages $mean — the grade is darkening the room",
                mean in 0.94f..1.04f
            )
            assertTrue(
                "${scheme.name} shadow gamma is flat across channels, so nothing leans",
                spec.shadowGamma.max() - spec.shadowGamma.min() > 0.02f
            )
        }
    }

    @Test
    fun `no mode touches exposure, and every mode uses the tuned curve`() {
        // **Both halves of this were learned the hard way, in one look at the device.**
        //
        // The room's lamp intensities were found on hardware under Filament's default transform.
        // A grade that swaps the tone curve silently re-exposes every one of them — AgX did, its
        // lifted blacks washed the room grey, and adding exposure to compensate only made the
        // wash brighter. So the curve stays the one the lighting was tuned against and the
        // exposure stays at zero; this file refines a tuned image rather than replacing it.
        for (scheme in all) {
            val spec = RoomGrade.specFor(scheme)

            assertEquals("${scheme.name} exposure", 0f, spec.exposure, 0f)
            assertEquals(
                "${scheme.name} must use the curve RoomLighting was tuned under",
                RoomGrade.Mapper.ACES_LEGACY,
                spec.mapper
            )
        }
    }

    @Test
    fun `no mode washes the image out or blows it up`() {
        // Guards the ranges Filament documents, and the narrower ranges this room actually wants.
        // A contrast of 2 or a saturation of 0 are both legal values that would destroy the look.
        for (scheme in all) {
            val spec = RoomGrade.specFor(scheme)
            assertTrue("${scheme.name} contrast", spec.contrast in 1f..1.35f)
            assertTrue("${scheme.name} saturation", spec.saturation in 0.9f..1.25f)
            assertTrue("${scheme.name} vibrance", spec.vibrance in 1f..1.3f)
            assertTrue("${scheme.name} temperature", spec.temperature in -0.25f..0.25f)
            assertTrue("${scheme.name} tint", spec.tint in -0.25f..0.25f)
            assertTrue("${scheme.name} highlightScale", spec.highlightScale in 1f..1.1f)
        }
    }

    @Test
    fun `the cold modes are not warmed`() {
        // **The one that protects Glacies.** Its whole idea is a cold slate room whose only
        // warmth is a single orange rim light, and a positive temperature would warm the slate
        // too, collapsing the contrast the mode is built on. Officina is a monitor in the dark
        // and has the same objection.
        for (scheme in listOf(RoomScheme.COLD_STORAGE, RoomScheme.WORKSHOP, RoomScheme.AFTER_HOURS)) {
            val spec = RoomGrade.specFor(scheme)
            assertTrue("${scheme.name} temperature ${spec.temperature}", spec.temperature <= 0f)
        }
    }

    @Test
    fun `only the tungsten room is warmed`() {
        assertTrue(RoomGrade.specFor(RoomScheme.MIDNIGHT_SHOP).temperature > 0f)
    }

    @Test
    fun `the shadows lean away from the key light rather than with it`() {
        // The claim in RoomGrade's KDoc, and the cheapest depth available to this room: shadows
        // that oppose the key's hue read as air between surfaces. Asserted as a *relationship*
        // between the grade and RoomScheme's own key colour, so the two cannot drift apart —
        // retuning a lamp without retuning its shadows is exactly how this would rot.
        //
        // It also caught a real error while being written: three of the four modes crushed the
        // channels so their shadows leaned the SAME way as their key light, with comments
        // claiming the opposite. A cold key over cold shadows is one hue at two brightnesses,
        // which is the muddiness the grade exists to remove.
        //
        // Mechanically: a channel crushed HARDER is darker, so the shadows lean toward whichever
        // channel is crushed LEAST. That channel must not be the key light's strongest.
        for (scheme in RoomScheme.entries) {
            val spec = RoomGrade.specFor(scheme)
            val leansToward = spec.shadowGamma.withIndex().minByOrNull { it.value }!!.index
            val keyStrongest = scheme.key.withIndex().maxByOrNull { it.value }!!.index

            assertNotEquals(
                "${scheme.name}: shadows lean toward channel $leansToward, which is also the " +
                    "key light's strongest — they agree instead of opposing",
                keyStrongest,
                leansToward
            )
        }
    }

    @Test
    fun `the true-black room carries the most contrast`() {
        // Officina is the only mode whose void is an actual black rather than a very dark brown,
        // so it is the one place contrast can be pushed without taking the room with it. This
        // used to assert that it *crushed* hardest, which was the same mistake as above.
        val workshop = RoomGrade.specFor(RoomScheme.WORKSHOP).contrast
        for (scheme in all - RoomScheme.WORKSHOP) {
            assertTrue(
                "${scheme.name} has at least as much contrast as Officina",
                RoomGrade.specFor(scheme).contrast < workshop
            )
        }
    }

    @Test
    fun `the four modes are actually four different grades`() {
        // A copy-paste that left two modes identical would be invisible on a device — you would
        // have to switch between them and notice nothing changed, which is precisely the kind of
        // nothing that goes unnoticed.
        val specs = all.map { RoomGrade.specFor(it) }

        assertEquals(all.size, specs.distinct().size)
    }

    @Test
    fun `the same mode always grades the same way`() {
        // Nothing here may depend on time, randomness or call order — a grade that drifted
        // between two calls would rebuild the LUT on every frame through RoomGrading's
        // identity check.
        for (scheme in all) {
            assertEquals(RoomGrade.specFor(scheme), RoomGrade.specFor(scheme))
        }
    }
}
