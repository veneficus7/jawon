package com.jawon.app.ui.vinylscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The vinyl room's arithmetic that is not the deck's: where it is looked at from, where a finger
 * lands, where the lines on the back of a sleeve are, and what the room reflects.
 */
class VinylRoomTest {

    private val phones = listOf(16f / 9f, 19.5f / 9f, 3120f / 1440f, 21f / 9f)

    private fun corners(shot: VinylCamera.Shot, aspect: Float, planeY: Float): FloatArray {
        // The frame's left, right, far and near edges on a level surface, for a display 1000 wide.
        val w = 1000f
        val h = w * aspect
        val a = FloatArray(2)
        val b = FloatArray(2)
        VinylCamera.onPlane(shot, 0f, 0f, w, h, planeY, a)
        VinylCamera.onPlane(shot, w, h, w, h, planeY, b)
        return floatArrayOf(a[0], b[0], a[1], b[1])
    }

    // ---- The lens ----------------------------------------------------------------------------

    @Test
    fun `the whole-deck view holds the whole deck and the sleeve in front of it, on any phone and on its side`() {
        for (aspect in phones + phones.map { 1f / it } + 1f + 4f / 3f) {
            val shot = VinylCamera.play(0f, aspect)
            val (left, right, far, near) = corners(shot, aspect, VinylDimens.TOP_Y).let { listOf(it[0], it[1], it[2], it[3]) }
            assertTrue("deck's left edge at $aspect", left < -VinylDimens.DECK_W / 2)
            assertTrue("deck's right edge at $aspect", right > VinylDimens.DECK_W / 2)
            assertTrue("deck's back edge at $aspect", far < -VinylDimens.DECK_D / 2)
            assertTrue("the sleeve's near edge at $aspect", near > SLEEVE_FRONT_Z)
            assertEquals(-90f, shot.pitchDeg, 0f)
        }
    }

    @Test
    fun `under the thumb, the record is wider than the phone and its label is in the frame`() {
        for (aspect in phones) {
            val shot = VinylCamera.play(1f, aspect)
            val c = corners(shot, aspect, VinylDimens.RECORD_TOP_Y)
            val width = c[1] - c[0]
            assertEquals(VinylCamera.THUMB_WIDTH, width, 1e-4f)
            assertTrue("a 302mm record in a ${width * 1000}mm frame", 2 * VinylDimens.LP_R > width * 1.3f)
            // The spindle is on screen, and not at its edge.
            assertTrue(VinylDimens.SPINDLE_X > c[0] + 0.05f && VinylDimens.SPINDLE_X < c[1] - 0.05f)
            assertTrue(VinylDimens.SPINDLE_Z > c[2] + 0.05f && VinylDimens.SPINDLE_Z < c[3] - 0.05f)
        }
    }

    @Test
    fun `the zoom closes in steadily, and equal pinches are equal factors`() {
        val aspect = 3120f / 1440f
        var last = Float.MAX_VALUE
        var zoom = 0f
        while (zoom <= 1f) {
            val width = VinylCamera.width(zoom, aspect)
            assertTrue("widened at $zoom", width < last)
            last = width
            zoom += 0.05f
        }
        val third = VinylCamera.width(1f / 3f, aspect) / VinylCamera.width(0f, aspect)
        assertEquals(third, VinylCamera.width(2f / 3f, aspect) / VinylCamera.width(1f / 3f, aspect), 1e-4f)
        // The camera comes down as it closes in, and never through anything.
        assertTrue(VinylCamera.play(0f, aspect).y > VinylCamera.play(1f, aspect).y)
        assertTrue(VinylCamera.play(1f, aspect).y > VinylDimens.PIVOT_Y + 0.2f)
    }

    @Test
    fun `a finger is put on the table by the lens run backwards - the middle of the screen is what the camera is over`() {
        val aspect = 3120f / 1440f
        for (zoom in listOf(0f, 0.4f, 1f)) {
            val shot = VinylCamera.play(zoom, aspect)
            val at = FloatArray(2)
            VinylCamera.onPlane(shot, 500f, 500f * aspect, 1000f, 1000f * aspect, VinylDimens.TOP_Y, at)
            assertEquals(shot.x, at[0], 1e-6f)
            assertEquals(shot.z, at[1], 1e-6f)
            // Right on the screen is right on the deck; down the screen is toward you.
            VinylCamera.onPlane(shot, 900f, 500f * aspect, 1000f, 1000f * aspect, VinylDimens.TOP_Y, at)
            assertTrue(at[0] > shot.x)
            VinylCamera.onPlane(shot, 500f, 900f * aspect, 1000f, 1000f * aspect, VinylDimens.TOP_Y, at)
            assertTrue(at[1] > shot.z)
            // Nearer the lens, a surface is seen larger: the same point of the screen is less far out on it.
            val low = FloatArray(2)
            val high = FloatArray(2)
            VinylCamera.onPlane(shot, 1000f, 0f, 1000f, 1000f * aspect, 0f, low)
            VinylCamera.onPlane(shot, 1000f, 0f, 1000f, 1000f * aspect, VinylDimens.RECORD_TOP_Y, high)
            assertTrue(high[0] - shot.x < low[0] - shot.x)
        }
    }

    @Test
    fun `at the crate the camera stands over it, looking down the way you would`() {
        for (aspect in phones) {
            val shot = VinylCamera.crate(aspect)
            assertTrue("looks ${shot.pitchDeg}° down", shot.pitchDeg in -58f..-44f)
            assertTrue(shot.y > VinylDimens.FLOOR_Y + 0.9f)
            assertTrue(shot.z > VinylDimens.CRATE_Z + VinylDimens.CRATE_D / 2)
            // Halfway to the deck it is halfway there in every way.
            val deck = VinylCamera.play(0f, aspect)
            val mid = VinylCamera.between(shot, deck, 0.5f)
            assertEquals((shot.y + deck.y) / 2, mid.y, 1e-5f)
            assertEquals((shot.pitchDeg + deck.pitchDeg) / 2, mid.pitchDeg, 1e-4f)
        }
    }

    // ---- A finger on a sleeve in the crate ---------------------------------------------------------

    /** A point of a sleeve standing at [footZ], leaning [leanDeg]: [across] from its middle, [up] from its foot. */
    private fun onSleeve(footZ: Float, leanDeg: Float, across: Float, up: Float): FloatArray {
        val lean = Math.toRadians(leanDeg.toDouble())
        return floatArrayOf(across, VinylDimens.FLOOR_Y + up * Math.cos(lean).toFloat(), footZ + up * Math.sin(lean).toFloat())
    }

    @Test
    fun `a finger is put on a sleeve in the crate by the same lens - there and back is where it started`() {
        val w = 1440f
        for (aspect in phones) {
            val h = w * aspect
            val shot = VinylCamera.crate(aspect)
            for (lean in listOf(-CrateLayout.LEAN_BACK_DEG, 0f, CrateLayout.LEAN_FRONT_DEG)) {
                val footZ = VinylDimens.CRATE_Z + 0.03f
                for ((across, up) in listOf(0f to 0f, 0.12f to 0.30f, -0.15f to 0.02f, 0.05f to 0.16f)) {
                    val world = onSleeve(footZ, lean, across, up)
                    val px = FloatArray(2)
                    assertTrue(VinylCamera.project(shot, world[0], world[1], world[2], w, h, px))
                    val back = FloatArray(2)
                    assertTrue(VinylCamera.onSleeve(shot, px[0], px[1], w, h, VinylDimens.FLOOR_Y, footZ, lean, back))
                    assertEquals(across, back[0], 2e-4f)
                    assertEquals(up, back[1], 2e-4f)
                }
            }
            // Right on the screen is right on the sleeve, and up the screen is up it.
            val at = FloatArray(2)
            val right = FloatArray(2)
            val higher = FloatArray(2)
            val z = VinylDimens.CRATE_Z
            VinylCamera.onSleeve(shot, w * 0.5f, h * 0.5f, w, h, VinylDimens.FLOOR_Y, z, -20f, at)
            VinylCamera.onSleeve(shot, w * 0.7f, h * 0.5f, w, h, VinylDimens.FLOOR_Y, z, -20f, right)
            VinylCamera.onSleeve(shot, w * 0.5f, h * 0.4f, w, h, VinylDimens.FLOOR_Y, z, -20f, higher)
            assertTrue(right[0] > at[0] && abs(right[1] - at[1]) < 1e-4f)
            assertTrue(higher[1] > at[1])
        }
        // Looking straight down at something lying flat, it is the deck's own arithmetic.
        val down = VinylCamera.play(0.3f, 2f)
        val flat = FloatArray(2)
        val plane = FloatArray(2)
        assertTrue(VinylCamera.onSleeve(down, 300f, 900f, 1000f, 2000f, VinylDimens.TOP_Y, 0.2f, -90f, flat))
        VinylCamera.onPlane(down, 300f, 900f, 1000f, 2000f, VinylDimens.TOP_Y, plane)
        assertEquals(plane[0], flat[0], 1e-5f)
        assertEquals(plane[1], 0.2f - flat[1], 1e-5f)
        // A sleeve seen exactly edge on is not something a finger can be on: from straight above,
        // one standing bolt upright, at the middle of the screen.
        assertTrue(!VinylCamera.onSleeve(down, 500f, 1000f, 1000f, 2000f, 0f, 0.2f, 0f, flat))
    }

    @Test
    fun `the record being read is whole in the frame on every phone, and clear of the band that is the table`() {
        val w = 1440f
        for (aspect in phones) {
            val h = w * aspect
            for ((position, count) in listOf(50f to 100, 1f to 100, 99f to 100, 1f to 3)) {
                val shot = VinylCamera.crate(aspect, (CrateLayout.readZ(position, count) - CrateLayout.readZ(50f, 100)) * 0.8f)
                val pose = CrateLayout.Pose().also { CrateLayout.pose(0f, position, count, it) }
                val footZ = VinylDimens.CRATE_Z + pose.z
                val px = FloatArray(2)
                for ((across, up) in listOf(-VinylDimens.SLEEVE / 2 to 0f, VinylDimens.SLEEVE / 2 to 0f, -VinylDimens.SLEEVE / 2 to VinylDimens.SLEEVE, VinylDimens.SLEEVE / 2 to VinylDimens.SLEEVE)) {
                    val world = onSleeve(footZ, pose.leanDeg, across, up)
                    assertTrue(VinylCamera.project(shot, world[0], world[1], world[2], w, h, px))
                    assertTrue("a corner at x ${px[0]} of $w (aspect $aspect, position $position of $count)", px[0] > 0f && px[0] < w)
                    // Below the band at the top that goes back up to the deck, above the system's bar.
                    assertTrue("a corner at y ${px[1] / h} of the screen (aspect $aspect, position $position of $count)", px[1] > h * 0.16f && px[1] < h * 0.92f)
                }
            }
            // Following the record back moves the camera back with it and does nothing else.
            val a = VinylCamera.crate(aspect)
            val b = VinylCamera.crate(aspect, -0.05f)
            assertEquals(a.z - 0.05f, b.z, 1e-6f)
            assertEquals(a.y, b.y, 0f)
            assertEquals(a.pitchDeg, b.pitchDeg, 0f)
        }
    }

    // ---- The back of the sleeve ------------------------------------------------------------------

    private fun sides(vararg counts: Int): List<Side> {
        var first = 0
        return counts.map { n -> Side(first, List(n) { 200_000L }).also { first += n } }
    }

    @Test
    fun `every track has one line, the lines do not overlap, and a finger on a line finds its track`() {
        for (album in listOf(sides(1), sides(2, 2), sides(3, 2), sides(6, 5), sides(9, 9), sides(5, 5, 5, 4), sides(7, 7, 7, 7, 6, 6))) {
            val layout = SleeveBackLayout.of(album)
            val tracks = album.sumOf { it.trackDurationsMs.size }
            assertEquals((0 until tracks).toList(), layout.rows.map { it.trackIndex }.sorted())
            assertEquals(album.size, layout.headings.size)
            for (row in layout.rows) {
                assertTrue(row.top >= SleeveBackLayout.LIST_TOP - 1e-5f && row.bottom <= SleeveBackLayout.LIST_BOTTOM + 1e-4f)
                assertTrue(row.left >= SleeveBackLayout.MARGIN - 1e-5f && row.right <= 1f - SleeveBackLayout.MARGIN + 1e-5f)
                assertEquals(row.trackIndex, layout.trackAt((row.left + row.right) / 2, (row.top + row.bottom) / 2))
                // No other line claims any part of this one.
                for (other in layout.rows) {
                    if (other === row) continue
                    val apart = other.bottom <= row.top + 1e-6f || other.top >= row.bottom - 1e-6f ||
                        other.right <= row.left || other.left >= row.right
                    assertTrue("lines ${row.trackIndex} and ${other.trackIndex} overlap", apart)
                }
            }
            // The title, the margins and the foot are not lines.
            assertEquals(-1, layout.trackAt(0.5f, 0.12f))
            assertEquals(-1, layout.trackAt(0.01f, 0.5f))
            assertEquals(-1, layout.trackAt(0.5f, 0.9f))
        }
    }

    @Test
    fun `a long record goes to two columns before its lines get too small to put a finger on`() {
        val short = SleeveBackLayout.of(sides(5, 4))
        assertTrue(short.rows.all { it.left == SleeveBackLayout.MARGIN })
        assertTrue(short.rowHeight >= SleeveBackLayout.ROW_SHORTEST)
        // Four sides of a double album: A and B on the left, C and D on the right.
        val double = SleeveBackLayout.of(sides(6, 6, 6, 6))
        assertEquals(2, double.rows.map { it.left }.distinct().size)
        assertTrue("lines ${double.rowHeight} tall", double.rowHeight >= SleeveBackLayout.ROW_SHORTEST)
        assertTrue(double.rows.filter { it.trackIndex < 12 }.all { it.left == SleeveBackLayout.MARGIN })
        assertTrue(double.rows.filter { it.trackIndex >= 12 }.all { it.left > 0.5f })
        // Nothing, for nothing.
        assertTrue(SleeveBackLayout.of(emptyList()).rows.isEmpty())
    }

    // ---- What the room reflects ----------------------------------------------------------------------

    private fun level(x: Float, y: Float, z: Float): Float {
        val l = sqrt(x * x + y * y + z * z)
        val rgb = FloatArray(3)
        VinylLighting.radiance(x / l, y / l, z / l, rgb)
        assertTrue(rgb.all { it >= 0f })
        return (rgb[0] + rgb[1] + rgb[2]) / 3f
    }

    @Test
    fun `the lamp is the one bright thing in the room, and it is where the lamp is`() {
        val k = VinylLighting.KEY_POSITION
        val a = VinylLighting.KEY_AIM
        val lamp = level(k[0] - a[0], k[1] - a[1], k[2] - a[2])
        var brightestElsewhere = 0f
        var steps = 0
        for (i in -20..20) for (j in -20..20) for (m in listOf(-1f, 1f)) {
            // Every direction, by the faces of a cube.
            for (dir in listOf(floatArrayOf(i / 20f, j / 20f, m), floatArrayOf(i / 20f, m, j / 20f), floatArrayOf(m, i / 20f, j / 20f))) {
                val l = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
                val cos = (dir[0] * (k[0] - a[0]) + dir[1] * (k[1] - a[1]) + dir[2] * (k[2] - a[2])) /
                    (l * hypot(hypot(k[0] - a[0], k[1] - a[1]), k[2] - a[2]))
                if (cos < 0.93f) brightestElsewhere = maxOf(brightestElsewhere, level(dir[0], dir[1], dir[2]))
                steps++
            }
        }
        assertTrue(steps > 4_000)
        // A record is black: what is seen of it is the lamp. Anything else as bright would be seen in it too.
        assertTrue("the lamp is $lamp and something else is $brightestElsewhere", lamp > brightestElsewhere * 8f)
        // It is warm.
        val rgb = FloatArray(3)
        val l = hypot(hypot(k[0] - a[0], k[1] - a[1]), k[2] - a[2])
        VinylLighting.radiance((k[0] - a[0]) / l, (k[1] - a[1]) / l, (k[2] - a[2]) / l, rgb)
        assertTrue(rgb[0] > rgb[1] && rgb[1] > rgb[2])
    }

    @Test
    fun `straight up is not black - a deck seen from above is made of the ceiling over it`() {
        // The first frame on a device had a black ceiling and a top plate at 0 of 255.
        val up = level(0f, 1f, 0f)
        val behind = level(0.6f, 0.05f, -0.8f)
        assertTrue("straight up is $up", up > 0.5f)
        assertTrue(up > behind * 10f)
        assertTrue(abs(level(0.1f, 1f, 0.1f) - up) < up * 0.5f)
    }
}
