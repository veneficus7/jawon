package com.jawon.app.ui.wallscene

import com.google.android.filament.ToneMapper
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
    fun `every mode crushes its blacks`() {
        // The whole point of grading a dark room. A shadow gamma of 1 is no curve at all, and
        // below 1 would *lift* the shadows — grey mud where the room wants black, which is the
        // single most likely way for this file to make the room look worse than ungraded.
        for (scheme in all) {
            val spec = RoomGrade.specFor(scheme)
            for ((channel, gamma) in spec.shadowGamma.withIndex()) {
                assertTrue("${scheme.name} channel $channel gamma $gamma", gamma > 1f)
            }
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
            // Exposure is deliberately left alone in all four: the render's own exposure and the
            // lamp intensities in RoomLighting were tuned together on the device, and a stop
            // here would silently re-tune every one of them.
            assertEquals("${scheme.name} exposure", 0f, spec.exposure, 0f)
        }
    }

    @Test
    fun `the cold modes are not warmed`() {
        // **The one that protects Glacies.** Its whole idea is a cold slate room whose only
        // warmth is a single orange rim light, and both a positive temperature and AgX's GOLDEN
        // look would warm the slate too — collapsing the contrast the mode is built on. Officina
        // is a monitor in the dark and has the same objection.
        for (scheme in listOf(RoomScheme.COLD_STORAGE, RoomScheme.WORKSHOP, RoomScheme.AFTER_HOURS)) {
            val spec = RoomGrade.specFor(scheme)
            assertTrue("${scheme.name} temperature ${spec.temperature}", spec.temperature <= 0f)
            assertNotEquals(
                "${scheme.name} must not use AgX's golden look",
                ToneMapper.Agx.AgxLook.GOLDEN,
                spec.look
            )
        }
    }

    @Test
    fun `only the tungsten room gets the warm look`() {
        assertEquals(
            ToneMapper.Agx.AgxLook.GOLDEN,
            RoomGrade.specFor(RoomScheme.MIDNIGHT_SHOP).look
        )
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
    fun `the true-black room crushes hardest`() {
        // Officina is the only mode whose void is an actual black rather than a very dark brown,
        // so it has no warm floor for its shadows to sit on and nothing to protect.
        val workshop = RoomGrade.specFor(RoomScheme.WORKSHOP).shadowGamma.min()
        for (scheme in all - RoomScheme.WORKSHOP) {
            assertTrue(
                "${scheme.name} crushes at least as hard as Officina",
                RoomGrade.specFor(scheme).shadowGamma.min() < workshop
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
