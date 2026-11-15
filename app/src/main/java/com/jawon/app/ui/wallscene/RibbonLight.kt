package com.jawon.app.ui.wallscene

import android.util.Half
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The shelf ribbon's light on the back panel, computed once instead of lit every frame.
 *
 * The ribbon is a row of [RoomLighting.WASH_COLUMNS] diodes under every board, 15mm in front of
 * the panel, aimed down it. As Filament lights they cost most of a frame on the S25 Ultra
 * (ENGINEERING.md, trap 46): each reaches 0.26m, so every pixel of the rack sat inside dozens of
 * them. But what they draw on the panel never moves — it depends only on how far below a tube a
 * point is and where it sits between two diodes — so it can be a table, and `panel.mat` reads it.
 *
 * The table is the sum of the diodes **as Filament computes a focused spot**: luminous
 * intensity in candela, the squared falloff window, the cone's scale-and-offset attenuation, and
 * N·L on the panel's face (the formulas in `surface_light_punctual.fs` and
 * `FLightManager::setSpotLightCone`, Filament 1.72.1). For each texel:
 *
 *  - **R** — illuminance from this compartment's own ribbon, in units of [LUX_PER_UNIT] lux
 *  - **G, B** — the y and z of the direction that light arrives from, weighted by its share,
 *    for the material's specular term
 *  - **A** — illuminance from the ribbon one row up, which lights through the board as Filament's
 *    lights do (they cast no shadow), in the same units
 *
 * Across: one diode pitch, so the scallops under each diode are kept. Down: one row pitch, from
 * [TOP] above the tube. Units of 10 klux, because right under a diode the light is ~400 klux and a half float
 * stops at 65,504.
 */
internal object RibbonLight {

    /** Texels across one diode pitch. */
    const val ACROSS = 16

    /** Texels down one row pitch. */
    const val DOWN = 256

    /** What one unit in the table stands for. */
    const val LUX_PER_UNIT = 10_000f

    /**
     * How far above the tube's light line the table starts. The board's underside is 3.2mm above
     * it, and the panel in between is lit by the diodes from below their own height; started at
     * the light line, that strip took the far end of the compartment above and drew a dark line.
     */
    const val TOP = 0.005f

    /** How far in front of the panel's face the diodes are. */
    val STANDOFF: Float = RoomLighting.WASH_Z - WallDimens.WALL_Z

    /** Where the ribbon's light comes from: the tube's light line, for row [row]'s centre. */
    fun tubeY(rowCenterY: Float): Float =
        rowCenterY + WallDimens.CASE_H / 2f + RoomLighting.SHELF_LIGHT_LIFT - RoomLighting.WASH_DROP

    private class Spot(val scale: Float, val offset: Float)

    /** `FLightManager::setSpotLightCone`, as Filament 1.72.1 writes it. */
    private fun spot(inner: Float, outer: Float): Spot {
        val halfPi = (Math.PI / 2).toFloat()
        val minAngle = (0.5 * Math.PI / 180).toFloat()
        val outerClamped = abs(outer).coerceIn(minAngle, halfPi)
        val innerClamped = min(abs(inner).coerceIn(minAngle, halfPi), outerClamped)
        val cosOuter = cos(outerClamped)
        val cosInner = cos(innerClamped)
        val scale = 1f / max(1f / 1024f, cosInner - cosOuter)
        return Spot(scale, -cosOuter * scale)
    }

    private fun abs(v: Float) = if (v < 0f) -v else v

    /**
     * Illuminance on the panel's face, in lux, at [x] along the shelf (0 under a diode) and [d]
     * below the tube, from one row of diodes; with the light-weighted direction it arrives from.
     */
    fun light(x: Float, d: Float): FloatArray = diodes(x, d, STANDOFF, facingUp = false)

    /**
     * How far the tube's light line is above the top of the board its own row stands on — the
     * board the ribbon also lit, 191mm below it.
     */
    val BOARD_DROP: Float =
        WallDimens.CASE_H + RoomLighting.SHELF_LIGHT_LIFT - RoomLighting.WASH_DROP

    /** Texels across a board's depth, back to front. */
    const val BOARD_SAMPLES = 64

    /** World z of a board's back edge; the rack is centred on 0. */
    val BOARD_BACK: Float = -WallDimens.BOARD_D / 2f

    /**
     * Illuminance on the top of a board at world depth [z], in lux, from the ribbon of the row
     * standing on it, averaged over one diode pitch along the shelf (191mm down, the diodes'
     * pattern is long gone); with the direction it arrives from. Nothing else reaches it: the
     * ribbon above that one is 396mm away, past its falloff.
     */
    fun boardLight(z: Float): FloatArray {
        var e = 0f
        var ly = 0f
        var lz = 0f
        for (u in 0 until ACROSS) {
            val x = (u + 0.5f) / ACROSS * RoomLighting.WASH_COLUMN_SPACING
            val s = diodes(x, BOARD_DROP, RoomLighting.WASH_Z - z, facingUp = true)
            e += s[0]
            ly += s[0] * s[1]
            lz += s[0] * s[2]
        }
        return if (e > 0f) floatArrayOf(e / ACROSS, ly / e, lz / e) else floatArrayOf(0f, 1f, 0f)
    }

    /**
     * One row of diodes as Filament lights a surface: [py] and [pz] from the point to the diode
     * line, the surface facing +z (the panel) or +y (a board's top).
     */
    private fun diodes(x: Float, py: Float, pz: Float, facingUp: Boolean): FloatArray {
        val i = RoomLighting.WASH_CANDELA
        val falloffInv = 1f / (RoomLighting.WASH_FALLOFF * RoomLighting.WASH_FALLOFF)
        val dir = RoomLighting.WASH_DIRECTION
        val dirLen = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        val sx = -dir[0] / dirLen // -direction: the shader tests the cone against it
        val sy = -dir[1] / dirLen
        val sz = -dir[2] / dirLen
        val cone = spot(RoomLighting.WASH_CONE_INNER, RoomLighting.WASH_CONE_OUTER)
        val half = RoomLighting.WASH_COLUMNS / 2
        var e = 0f
        var ly = 0f
        var lz = 0f
        for (c in 0 until RoomLighting.WASH_COLUMNS) {
            // posToLight: from the point on the surface to the diode.
            val px = (c - half) * RoomLighting.WASH_COLUMN_SPACING - x
            val distSq = px * px + py * py + pz * pz
            val factor = distSq * falloffInv
            val smooth = (1f - factor * factor).coerceIn(0f, 1f)
            val distance = smooth * smooth / max(distSq, 1e-4f)
            val r = sqrt(distSq)
            val lx = px / r
            val lyj = py / r
            val lzj = pz / r
            val cd = sx * lx + sy * lyj + sz * lzj
            val a = (cd * cone.scale + cone.offset).coerceIn(0f, 1f)
            val nol = max(if (facingUp) lyj else lzj, 0f)
            val ej = i * distance * a * a * nol
            e += ej
            ly += ej * lyj
            lz += ej * lzj
        }
        return if (e > 0f) floatArrayOf(e, ly / e, lz / e) else floatArrayOf(0f, 0f, 1f)
    }

    /** How far below the tube's light line texel row [k] is; negative above it. */
    fun depth(k: Int, pitch: Float = WallDimens.ROW_PITCH): Float = (k + 0.5f) / DOWN * pitch - TOP

    /** The table, row by row from [TOP] above the tube down, RGBA per texel. */
    fun table(pitch: Float = WallDimens.ROW_PITCH): FloatArray {
        val out = FloatArray(ACROSS * DOWN * 4)
        for (k in 0 until DOWN) {
            val d = depth(k, pitch)
            for (u in 0 until ACROSS) {
                val x = (u + 0.5f) / ACROSS * RoomLighting.WASH_COLUMN_SPACING
                val own = light(x, d)
                val above = light(x, d + pitch)
                val o = (k * ACROSS + u) * 4
                out[o] = own[0] / LUX_PER_UNIT
                out[o + 1] = own[1]
                out[o + 2] = own[2]
                out[o + 3] = above[0] / LUX_PER_UNIT
            }
        }
        return out
    }

    /** The board tops' table, back to front: R lux in [LUX_PER_UNIT]s, G and B the direction. */
    fun boardTable(): FloatArray {
        val out = FloatArray(BOARD_SAMPLES * 4)
        for (k in 0 until BOARD_SAMPLES) {
            val z = BOARD_BACK + (k + 0.5f) / BOARD_SAMPLES * WallDimens.BOARD_D
            val s = boardLight(z)
            out[k * 4] = s[0] / LUX_PER_UNIT
            out[k * 4 + 1] = s[1]
            out[k * 4 + 2] = s[2]
        }
        return out
    }

    /** The panel's table as a half-float texture. Built in the scene, never at file level (trap 23). */
    fun texture(engine: Engine): Texture = halfTexture(engine, table(), ACROSS, DOWN)

    /** The board tops' table as a half-float texture. */
    fun boardTexture(engine: Engine): Texture = halfTexture(engine, boardTable(), BOARD_SAMPLES, 1)

    private fun halfTexture(engine: Engine, values: FloatArray, width: Int, height: Int): Texture {
        val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        for (v in values) buffer.putShort(Half.toHalf(v))
        buffer.flip()
        val texture = Texture.Builder()
            .width(width)
            .height(height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA16F)
            .build(engine)
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.HALF)
        )
        return texture
    }
}
