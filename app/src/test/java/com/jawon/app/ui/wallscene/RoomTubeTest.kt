package com.jawon.app.ui.wallscene

import com.jawon.app.ui.theme.RoomScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The strip light's colour per room — `RoomScheme.tube` and the light derived from it.
 *
 * Like the rest of the room's look this cannot test whether a colour is *right*; that is the
 * owner's phone. What it pins is the arithmetic that makes a colour survive the pipeline, and
 * the one property the owner asked for by name: that each room's tube is its own.
 */
class RoomTubeTest {

    private val rooms = RoomScheme.entries

    /** Rec. 709 luminance, the weights the eye's sensitivity gives each channel. */
    private fun luminance(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    /** Colour without brightness: each channel's share of the total. */
    private fun chromaticity(c: FloatArray): FloatArray {
        val sum = c[0] + c[1] + c[2]
        return floatArrayOf(c[0] / sum, c[1] / sum, c[2] / sum)
    }

    @Test
    fun `every room's tube is a colour no other room has`() {
        // "Exclusive tube light color for each room" — the owner's words. Measured on colour
        // alone, so two tubes cannot pass by being the same hue at different brightnesses.
        // 0.15 is roughly the gap between a cold white and a saturated cyan, which is the
        // closest pair here and a pair this project has confused once already (Glacies used to
        // be cyan beside Vigilia's cyan, and the two rooms read as one idea).
        for (i in rooms.indices) for (j in i + 1 until rooms.size) {
            val a = chromaticity(rooms[i].tube)
            val b = chromaticity(rooms[j].tube)
            val distance = sqrt(
                (a[0] - b[0]) * (a[0] - b[0]) +
                    (a[1] - b[1]) * (a[1] - b[1]) +
                    (a[2] - b[2]) * (a[2] - b[2])
            )
            assertTrue(
                "${rooms[i].name} and ${rooms[j].name} have tubes $distance apart in colour; " +
                    "they need to be told apart at a glance",
                distance >= 0.15f
            )
        }
    }

    @Test
    fun `every tube is as bright as every other`() {
        // Switching rooms should change the colour of the light, not how much of it there is.
        // Equal luminance rather than equal channel values: at the amber's numbers a green tube
        // is a third brighter, because the eye is most sensitive to green.
        for (room in rooms) {
            val y = luminance(room.tube)
            assertTrue("${room.name}'s tube has luminance $y, outside 1.8..2.0", y in 1.8f..2.0f)
        }
    }

    @Test
    fun `no tube can collapse into a white bar`() {
        // Three channels comfortably over 1.0 land in the same place on the ACES shoulder and
        // render white — the amber's first version did exactly that. Holding the brightest at
        // least twice the weakest keeps a hue in the tube's edge and its bloom. And the brightest
        // has to clear bloom's 1.0 threshold, or it is a painted stripe rather than a light.
        for (room in rooms) {
            val t = room.tube
            val peak = maxOf(t[0], t[1], t[2])
            val floor = minOf(t[0], t[1], t[2])
            assertTrue("${room.name}'s tube does not reach bloom", peak > 1f)
            assertTrue(
                "${room.name}'s tube channels are within 2x of each other — it will render white",
                peak >= 2f * floor
            )
        }
    }

    @Test
    fun `the light the tube throws is the tube's own colour`() {
        // The panel's gradient and the tube cannot disagree, which is the owner's standing
        // complaint about this fixture. tubeLight is the tube with its peak scaled to 1: same
        // ratios, so the same colour, and a full-strength light colour.
        for (room in rooms) {
            val light = room.tubeLight
            assertEquals("${room.name}: light peak", 1f, maxOf(light[0], light[1], light[2]), 1e-6f)
            val a = chromaticity(room.tube)
            val b = chromaticity(light)
            for (c in 0..2) assertEquals("${room.name}: channel $c", a[c], b[c], 1e-5f)
        }
    }

    @Test
    fun `the grade's shadows lean away from the tube, which is the room's light now`() {
        // RoomGradeTest asserts the same of RoomScheme.key, from when the key was the room's
        // lamp. The strip took over that role, and a light and its shadows leaning the same way
        // is one hue at two brightnesses — the muddiness the grade exists to remove. A channel
        // crushed LEAST is the one the shadows lean toward, and that must not be the light's.
        for (room in rooms) {
            val gamma = RoomGrade.specFor(room).shadowGamma
            val leansToward = gamma.withIndex().minByOrNull { it.value }!!.index
            val tubeStrongest = room.tube.withIndex().maxByOrNull { it.value }!!.index
            assertNotEquals(
                "${room.name}: the shadows lean toward the tube's own strongest channel",
                tubeStrongest,
                leansToward
            )
        }
    }
}
