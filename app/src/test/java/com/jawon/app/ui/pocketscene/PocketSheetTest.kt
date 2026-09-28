package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sheet the device lies on, as the app reads it back out of the model script's height map.
 * What would read as wrong: a cord floating over the folds or sunk into them, or a device lying
 * above or inside the sheet it rests on.
 */
class PocketSheetTest {

    private fun map(
        columns: Int, rows: Int, x0: Float, z0: Float, step: Float, baseY: Float, scale: Float, values: IntArray
    ): ByteArray {
        val b = ByteBuffer.allocate(36 + columns * rows * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("JWHM".toByteArray(Charsets.US_ASCII))
        b.putInt(1)
        b.putInt(columns)
        b.putInt(rows)
        b.putFloat(x0)
        b.putFloat(z0)
        b.putFloat(step)
        b.putFloat(baseY)
        b.putFloat(scale)
        for (v in values) b.putShort(v.toShort())
        return b.array()
    }

    @Test
    fun `a map reads back what was written, point for point - unsigned, not negative`() {
        // 60000 would read back as a negative short if the heights were taken as signed.
        val m = HeightMap.parse(map(3, 2, -0.1f, -0.2f, 0.01f, -0.3f, 0.001f, intArrayOf(0, 10, 20, 30, 40, 60000)))
        assertEquals(-0.30f, m.at(0, 0), 1e-6f)
        assertEquals(-0.29f, m.at(1, 0), 1e-6f)
        assertEquals(-0.27f, m.at(0, 1), 1e-6f)
        assertEquals(-0.3f + 60f, m.at(2, 1), 1e-3f)
    }

    @Test
    fun `between points it is bilinear, and past the edge it holds the edge`() {
        val m = HeightMap.parse(map(2, 2, 0f, 0f, 0.01f, 0f, 0.001f, intArrayOf(0, 10, 20, 30)))
        // The middle of the cell is the mean of its corners.
        assertEquals(0.015f, m.topAt(0.005f, 0.005f), 1e-6f)
        // Along an edge, between its two ends.
        assertEquals(0.005f, m.topAt(0.005f, 0f), 1e-6f)
        // Well past a corner, the corner.
        assertEquals(0.030f, m.topAt(1f, 1f), 1e-6f)
        assertEquals(0f, m.topAt(-1f, -1f), 1e-6f)
    }

    @Test
    fun `a file that is not a map is refused, not misread`() {
        val good = map(2, 2, 0f, 0f, 0.01f, 0f, 0.001f, intArrayOf(0, 1, 2, 3))
        for (bad in listOf(
            good.copyOf(20),
            good.copyOf().also { it[0] = 'X'.code.toByte() },
            good.copyOf(good.size - 2)
        )) {
            try {
                HeightMap.parse(bad)
                fail("parsed a broken map")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    // ---- the committed map --------------------------------------------------------------------

    private val committed: HeightMap by lazy {
        val file = listOf("src/main/assets/${SheetSurface.ASSET}", "app/src/main/assets/${SheetSurface.ASSET}")
            .map(::File).first { it.exists() }
        HeightMap.parse(file.readBytes())
    }

    /** A point in the resting device's own frame, across and along its face, in the room. */
    private fun onDevice(across: Float, along: Float): Pair<Float, Float> {
        val yaw = Math.toRadians(RestPose.DEVICE_YAW_DEG.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        return Pair(RestPose.DEVICE_X + c * across + s * along, RestPose.DEVICE_Z - s * across + c * along)
    }

    @Test
    fun `under the device the sheet is pressed flat at exactly the height the device lies at`() {
        // `SHEET_DEVICE_T` in the script and `RestPose.SURFACE_T` here must agree, or the device
        // floats over the sheet or sinks into it.
        for (across in listOf(-0.025f, 0f, 0.025f)) for (along in listOf(-0.045f, 0f, 0.045f)) {
            val (x, z) = onDevice(across, along)
            assertEquals(
                "at ($across, $along)",
                RestPose.TABLE_TOP_Y + RestPose.SURFACE_T, committed.topAt(x, z), 0.0001f
            )
        }
    }

    @Test
    fun `round the device the sheet lies in folds, over the table and under the window`() {
        // Across what the camera sees at rest, the sheet rises and falls by centimetres, and never
        // goes through the table.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var x = -0.06f
        while (x <= 0.10f) {
            var z = -0.43f
            while (z <= -0.11f) {
                val y = committed.topAt(x, z)
                lo = minOf(lo, y)
                hi = maxOf(hi, y)
                z += 0.003f
            }
            x += 0.003f
        }
        assertTrue("lowest ${lo - RestPose.TABLE_TOP_Y}", lo > RestPose.TABLE_TOP_Y)
        assertTrue("folds only ${(hi - lo) * 1000}mm deep", hi - lo > 0.008f)
        assertTrue("folds ${(hi - lo) * 1000}mm deep", hi - lo < 0.05f)
    }
}
