package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The ribbon's light on the back panel, as a table instead of 108 lights (RibbonLight, panel.mat).
 * The look was tuned with the lights, so the table has to be what Filament made of them.
 */
class RibbonLightTest {

    /**
     * One diode, written out from Filament 1.72.1's own source rather than from RibbonLight:
     * `getSquareFalloffAttenuation`, `getDistanceAttenuation`, `getAngleAttenuation`
     * (surface_light_punctual.fs) and the cone's scale and offset (FLightManager).
     * [px], [py], [pz]: from the point on the panel to the diode.
     */
    private fun diode(px: Float, py: Float, pz: Float): Float {
        val distSq = px * px + py * py + pz * pz
        val radius = RoomLighting.WASH_FALLOFF
        val factor = distSq / (radius * radius)
        val window = (1f - factor * factor).coerceIn(0f, 1f).let { it * it }
        val r = sqrt(distSq)
        val d = RoomLighting.WASH_DIRECTION
        val dLen = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        val cd = -(d[0] * px + d[1] * py + d[2] * pz) / (dLen * r)
        val cosOuter = cos(RoomLighting.WASH_CONE_OUTER)
        val scale = 1f / (cos(RoomLighting.WASH_CONE_INNER) - cosOuter)
        val angle = (cd * scale - cosOuter * scale).coerceIn(0f, 1f)
        val noL = max(pz / r, 0f)
        return RoomLighting.WASH_CANDELA * window / max(distSq, 1e-4f) * angle * angle * noL
    }

    private fun row(x: Float, d: Float): Float {
        val half = RoomLighting.WASH_COLUMNS / 2
        return (0 until RoomLighting.WASH_COLUMNS).sumOf { c ->
            diode((c - half) * RoomLighting.WASH_COLUMN_SPACING - x, d, RibbonLight.STANDOFF).toDouble()
        }.toFloat()
    }

    @Test
    fun `the table is the diodes' light, as Filament computes it`() {
        val table = RibbonLight.table()
        for (k in listOf(0, 3, 20, 64, 140)) {
            for (u in listOf(0, 7, 15)) {
                val x = (u + 0.5f) / RibbonLight.ACROSS * RoomLighting.WASH_COLUMN_SPACING
                val d = (k + 0.5f) / RibbonLight.DOWN * WallDimens.ROW_PITCH - RibbonLight.TOP
                val stored = table[(k * RibbonLight.ACROSS + u) * 4] * RibbonLight.LUX_PER_UNIT
                val expected = row(x, d)
                assertEquals("own row at u=$u k=$k", expected, stored, expected * 1e-4f + 1e-3f)
                val above = table[(k * RibbonLight.ACROSS + u) * 4 + 3] * RibbonLight.LUX_PER_UNIT
                val expectedAbove = row(x, d + WallDimens.ROW_PITCH)
                assertEquals("row above at u=$u k=$k", expectedAbove, above, expectedAbove * 1e-4f + 1e-3f)
            }
        }
    }

    @Test
    fun `the row above reaches exactly as far as its falloff`() {
        val table = RibbonLight.table()
        for (k in 0 until RibbonLight.DOWN) {
            val d = RibbonLight.depth(k)
            val above = (0 until RibbonLight.ACROSS).maxOf { table[(k * RibbonLight.ACROSS + it) * 4 + 3] }
            if (d + WallDimens.ROW_PITCH >= RoomLighting.WASH_FALLOFF) {
                assertEquals("nothing past the falloff, d=$d", 0f, above, 0f)
            }
        }
        // And it does reach the top of the compartment below: the board casts no shadow.
        assertTrue(table[3] > 0f)
    }

    /**
     * Brightest a couple of millimetres under the tube, not at it — the diodes either side only
     * reach the panel fully once a point is a little below them (their cones), exactly as the
     * lights did — and darker all the way down from there.
     */
    @Test
    fun `brightest just under the tube, then darker all the way down`() {
        val table = RibbonLight.table()
        val mean = FloatArray(RibbonLight.DOWN) { k ->
            (0 until RibbonLight.ACROSS).sumOf { table[(k * RibbonLight.ACROSS + it) * 4].toDouble() }
                .toFloat() / RibbonLight.ACROSS
        }
        val peak = mean.indices.maxByOrNull { mean[it] }!!
        val peakDepth = RibbonLight.depth(peak)
        assertTrue("peak at ${peakDepth * 1000}mm", peakDepth > 0f && peakDepth < 0.003f)
        for (k in peak + 1 until RibbonLight.DOWN) {
            assertTrue("brighter at k=$k than above it", mean[k] <= mean[k - 1])
        }
    }

    /** The same diode facing up, onto a board's top: N·L along +y. */
    private fun diodeOnBoard(px: Float, py: Float, pz: Float): Float {
        val r = sqrt(px * px + py * py + pz * pz)
        val distSq = px * px + py * py + pz * pz
        val radius = RoomLighting.WASH_FALLOFF
        val factor = distSq / (radius * radius)
        val window = (1f - factor * factor).coerceIn(0f, 1f).let { it * it }
        val d = RoomLighting.WASH_DIRECTION
        val dLen = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        val cd = -(d[0] * px + d[1] * py + d[2] * pz) / (dLen * r)
        val cosOuter = cos(RoomLighting.WASH_CONE_OUTER)
        val scale = 1f / (cos(RoomLighting.WASH_CONE_INNER) - cosOuter)
        val angle = (cd * scale - cosOuter * scale).coerceIn(0f, 1f)
        return RoomLighting.WASH_CANDELA * window / max(distSq, 1e-4f) * angle * angle * max(py / r, 0f)
    }

    @Test
    fun `a board's top is lit as Filament lit it, brightest at the back`() {
        val table = RibbonLight.boardTable()
        val half = RoomLighting.WASH_COLUMNS / 2
        for (k in listOf(0, 10, 31, 63)) {
            val z = RibbonLight.BOARD_BACK + (k + 0.5f) / RibbonLight.BOARD_SAMPLES * WallDimens.BOARD_D
            val expected = (0 until RibbonLight.ACROSS).sumOf { u ->
                val x = (u + 0.5f) / RibbonLight.ACROSS * RoomLighting.WASH_COLUMN_SPACING
                (0 until RoomLighting.WASH_COLUMNS).sumOf { c ->
                    diodeOnBoard(
                        (c - half) * RoomLighting.WASH_COLUMN_SPACING - x,
                        RibbonLight.BOARD_DROP,
                        RoomLighting.WASH_Z - z
                    ).toDouble()
                }
            }.toFloat() / RibbonLight.ACROSS
            val stored = table[k * 4] * RibbonLight.LUX_PER_UNIT
            assertEquals("board at k=$k", expected, stored, expected * 1e-4f + 1e-3f)
        }
        // The ribbon aims down and back, so the back of the board, under it, has the most.
        assertTrue(table[0] > table[(RibbonLight.BOARD_SAMPLES - 1) * 4])
        // And the front still has some: it is what showed in front of the records.
        assertTrue(table[(RibbonLight.BOARD_SAMPLES - 1) * 4] > 0f)
    }

    @Test
    fun `every stored value fits a half float`() {
        val table = RibbonLight.table()
        assertTrue(table.all { it.isFinite() && kotlin.math.abs(it) < 65_504f })
        // And the brightest is not lost below a half float's resolution either.
        assertTrue(table.maxOrNull()!! > 1f)
    }
}
