package com.jawon.app.ui.pocketscene

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **The sheet's top surface**, as `build_sheet()` left it — so what lies on it lies *on* it. The
 * sheet is crumpled by a cloth simulation in the model script, which has no formula to mirror
 * here, so the script samples the settled sheet from above into `models/sheet_height.bin` and the
 * app reads that: the earphone cord's resting curve follows it over the folds, where a flat floor
 * had it floating over the valleys and sunk into the ridges.
 *
 * Until the map has been read ([load], off the main thread) the surface is flat at the height the
 * device lies at, which is exactly what the sheet is pressed to under it.
 */
object SheetSurface {
    const val ASSET = "models/sheet_height.bin"

    @Volatile private var map: HeightMap? = null

    /** Bumped when the map arrives, so a cord laid on the flat stand-in can lie again. */
    @Volatile var version = 0
        private set

    /** Reads the map from the app's assets. Blocking — call it off the main thread. */
    fun load(assets: AssetManager) {
        if (map != null) return
        val bytes = assets.open(ASSET).use { it.readBytes() }
        map = HeightMap.parse(bytes)
        version++
    }

    /** The sheet's top at ([x], [z]) in the room, metres. */
    fun topAt(x: Float, z: Float): Float =
        map?.topAt(x, z) ?: (RestPose.TABLE_TOP_Y + RestPose.SURFACE_T)
}

/**
 * A surface's height every [step] metres over a grid from ([x0], [z0]): `sheet_height.bin`'s
 * contents. Pure, so the reading and the interpolation can be tested without a device.
 *
 * The file, little-endian: `JWHM`, a version (1), the grid's columns and rows, x0, z0, the step,
 * the height of zero and metres per unit, then the heights as unsigned 16-bit values, a row per z.
 */
class HeightMap(
    val columns: Int,
    val rows: Int,
    val x0: Float,
    val z0: Float,
    val step: Float,
    val baseY: Float,
    val scale: Float,
    private val values: CharArray
) {
    /** The height at grid point ([i], [j]), metres in the room. */
    fun at(i: Int, j: Int): Float =
        baseY + values[j.coerceIn(0, rows - 1) * columns + i.coerceIn(0, columns - 1)].code * scale

    /** The surface at ([x], [z]): bilinear between the four grid points round it, held at the edge. */
    fun topAt(x: Float, z: Float): Float {
        val gx = ((x - x0) / step).coerceIn(0f, (columns - 1).toFloat())
        val gz = ((z - z0) / step).coerceIn(0f, (rows - 1).toFloat())
        val i = gx.toInt().coerceAtMost(columns - 2).coerceAtLeast(0)
        val j = gz.toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val fx = gx - i
        val fz = gz - j
        val a = at(i, j) + (at(i + 1, j) - at(i, j)) * fx
        val b = at(i, j + 1) + (at(i + 1, j + 1) - at(i, j + 1)) * fx
        return a + (b - a) * fz
    }

    companion object {
        private const val MAGIC = "JWHM"
        private const val HEADER_BYTES = 36

        fun parse(bytes: ByteArray): HeightMap {
            require(bytes.size >= HEADER_BYTES) { "height map too short: ${bytes.size} bytes" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            require(magic == MAGIC) { "not a height map: $magic" }
            b.position(4)
            val version = b.int
            require(version == 1) { "height map version $version" }
            val columns = b.int
            val rows = b.int
            val x0 = b.float
            val z0 = b.float
            val step = b.float
            val baseY = b.float
            val scale = b.float
            require(columns >= 2 && rows >= 2 && step > 0f) { "height map grid $columns x $rows at $step" }
            require(bytes.size == HEADER_BYTES + columns * rows * 2) { "height map size ${bytes.size}" }
            val values = CharArray(columns * rows)
            b.asCharBuffer().get(values)
            return HeightMap(columns, rows, x0, z0, step, baseY, scale, values)
        }
    }
}
