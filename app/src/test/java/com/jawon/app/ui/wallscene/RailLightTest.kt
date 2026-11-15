package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The shelf-edge rail's light as a table instead of 60 lights (RailLight, plate.mat). The covers
 * were tuned under the lights, so the table has to be what Filament made of them — for a record
 * facing the room exactly, and for one turned sideways from the side it faces.
 */
class RailLightTest {

    /**
     * One head, written out from Filament 1.72.1's own source rather than from RailLight:
     * `getSquareFalloffAttenuation`, `getDistanceAttenuation` and `getAngleAttenuation`
     * (surface_light_punctual.fs) and the cone's scale and offset (FLightManager). From the point
     * to the head: [px], [py], [pz]. Returns illuminance arriving along that direction, before N·L.
     */
    private fun head(px: Float, py: Float, pz: Float): Float {
        val distSq = px * px + py * py + pz * pz
        val radius = RoomLighting.RAIL_FALLOFF
        val factor = distSq / (radius * radius)
        val window = (1f - factor * factor).coerceIn(0f, 1f).let { it * it }
        val r = sqrt(distSq)
        val d = RoomLighting.RAIL_DIRECTION
        val dLen = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        val cd = -(d[0] * px + d[1] * py + d[2] * pz) / (dLen * r)
        val cosOuter = cos(RoomLighting.RAIL_CONE_OUTER)
        val scale = 1f / (cos(RoomLighting.RAIL_CONE_INNER) - cosOuter)
        val angle = (cd * scale - cosOuter * scale).coerceIn(0f, 1f)
        return RoomLighting.RAIL_CANDELA * window / max(distSq, 1e-4f) * angle * angle
    }

    /**
     * Illuminance on a surface with normal ([nx], 0, [nz]) at [d] below the rail and [behind] it,
     * from every head along the rail, averaged along one spacing — summed head by head, each with
     * its own clamped N·L, which is what Filament does and what the table has to reproduce.
     */
    private fun direct(d: Float, behind: Float, nx: Float, nz: Float): Float {
        val s = RoomLighting.RAIL_COLUMN_SPACING
        var total = 0.0
        val samples = 64
        for (u in 0 until samples) {
            val x = (u + 0.5f) / samples * s
            for (c in -14..14) {
                val px = c * s - x
                val r = sqrt(px * px + d * d + behind * behind)
                val nol = max((nx * px + nz * behind) / r, 0f)
                total += head(px, d, behind) * nol
            }
        }
        return (total / samples).toFloat()
    }

    /** What plate.mat makes of the bins for that normal: Σ max(n·Vᵦ, 0), each bin and its mirror. */
    private fun fromTable(d: Float, behind: Float, nx: Float, nz: Float): Float =
        RailLight.binVectors(d, behind).sumOf { v ->
            (max(nx * v[0] + nz * v[2], 0f) + max(-nx * v[0] + nz * v[2], 0f)).toDouble()
        }.toFloat()

    @Test
    fun `the tables are the heads' light, bin by bin, averaged along the shelf`() {
        val tables = RailLight.tables()
        val s = RoomLighting.RAIL_COLUMN_SPACING
        val samples = 64
        for (k in listOf(16, 40, 64, 100)) {
            for (j in listOf(20, 64, 110)) {
                val d = RailLight.depth(k)
                val behind = RailLight.behind(j)
                // The heads to the point's +x, by azimuth from straight ahead: 0-30°, 30-60°, beyond.
                val expected = Array(RailLight.BINS) { DoubleArray(3) }
                for (u in 0 until samples) {
                    val x = (u + 0.5f) / samples * s
                    for (c in 1..14) {
                        val px = c * s - x
                        val r = sqrt(px * px + d * d + behind * behind)
                        val e = head(px, d, behind) / samples
                        val azimuth = Math.toDegrees(kotlin.math.atan2(px.toDouble(), behind.toDouble()))
                        val b = when {
                            azimuth < 30.0 -> 0
                            azimuth < 60.0 -> 1
                            else -> 2
                        }
                        expected[b][0] += e * px / r
                        expected[b][1] += e * d / r
                        expected[b][2] += e * behind / r
                    }
                }
                val o = (k * RailLight.BACK + j) * 4
                for (b in 0 until RailLight.BINS) {
                    for (i in 0..2) {
                        val want = expected[b][i].toFloat()
                        val stored = tables[b][o + i] * RailLight.LUX_PER_UNIT
                        assertEquals("k=$k j=$j bin $b component $i", want, stored, abs(want) * 0.01f + 0.5f)
                    }
                }
            }
        }
    }

    /** A record facing the room has every head in front of it, so its light is exact. */
    @Test
    fun `a record facing the room gets exactly the light the heads gave it`() {
        for (d in listOf(0.06f, 0.10f, 0.15f, 0.19f)) {
            for (behind in listOf(0.03f, 0.05f, 0.08f)) {
                val expected = direct(d, behind, nx = 0f, nz = 1f)
                val got = fromTable(d, behind, nx = 0f, nz = 1f)
                assertTrue("lit at d=$d behind=$behind", expected > 100f)
                assertEquals("d=$d behind=$behind", expected, got, expected * 0.01f)
            }
        }
    }

    /** A spine turned to face along the shelf takes the light from the heads on its own side. */
    @Test
    fun `a spine facing along the shelf gets the light from its own side`() {
        for (d in listOf(0.08f, 0.14f)) {
            val behind = 0.05f
            val expected = direct(d, behind, nx = -1f, nz = 0f)
            val got = fromTable(d, behind, nx = -1f, nz = 0f)
            assertTrue("lit at d=$d", expected > 10f)
            assertEquals("d=$d", expected, got, expected * 0.01f)
        }
    }

    /**
     * A case turned part-way: each bin is clamped as a whole, so this is close rather than exact —
     * within 2% from 20° to 65°, where two halves lost up to 10%.
     */
    @Test
    fun `a case turned anywhere is within two percent`() {
        for (d in listOf(0.07f, 0.12f, 0.17f)) {
            for (deg in listOf(20f, 35f, 50f, 65f)) {
                val a = Math.toRadians(deg.toDouble())
                val nx = kotlin.math.sin(a).toFloat()
                val nz = kotlin.math.cos(a).toFloat()
                val expected = direct(d, 0.05f, nx, nz)
                val got = fromTable(d, 0.05f, nx, nz)
                assertEquals("turned $deg° at d=$d", expected, got, expected * 0.02f)
            }
        }
    }

    @Test
    fun `nothing reaches past the falloff`() {
        val bins = RailLight.binVectors(RoomLighting.RAIL_FALLOFF, 0.05f)
        assertTrue(bins.all { v -> v.all { it == 0f } })
        // And the tables' last row, half a texel short of it, is all but dark.
        val tables = RailLight.tables()
        fun row(k: Int) = tables.maxOf { t -> (0 until RailLight.BACK).maxOf { abs(t[(k * RailLight.BACK + it) * 4 + 2]) } }
        assertTrue("last row against the middle", row(RailLight.DOWN - 1) < row(RailLight.DOWN / 2) * 0.01f)
    }

    @Test
    fun `every stored value fits a half float, and is not lost below its resolution`() {
        val tables = RailLight.tables()
        assertTrue(tables.all { t -> t.all { it.isFinite() && abs(it) < 65_504f } })
        assertTrue(tables.maxOf { t -> t.maxOrNull()!! } > 1f)
    }
}
