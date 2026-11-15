package com.jawon.app.ui.wallscene

import com.google.android.filament.Engine
import com.google.android.filament.Texture
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The shelf-edge rail's light on the records, computed once instead of lit every frame.
 *
 * The rail is [RoomLighting.RAIL_COLUMNS] heads under the front lip of every board, aimed down and
 * back at the records standing on the shelf below — the light the artwork is read by. As Filament
 * lights they were 60 of the room's punctual lights, and lights were what made a frame of the
 * wall expensive (ENGINEERING.md, trap 46). Unlike the ribbon's light on the panel
 * ([RibbonLight]), theirs falls on things that move: a case floats, turns, and is pulled out and
 * opened. So this is not the light on one surface but **the light arriving at a point**, for any
 * surface there: the plates' material (`plate.mat`) reads it at the fragment's world position and
 * shades it with the fragment's own normal.
 *
 * What it holds are *irradiance vectors*: the sum of each head's illuminance times the direction
 * it arrives from, Σ Eᵢ·lᵢ, so that a surface with normal n receives n·V. That is exact for as
 * long as every head summed is in front of the surface, and a turned case has some heads behind
 * it — so the heads are summed apart by **azimuth**, the direction they arrive from seen from
 * above, in six bins of 30°, and the material clamps each bin on its own: Σ max(n·Vᵦ, 0). A record
 * facing the room or turned square to the shelf comes out exact; turned anywhere between, within
 * about 1.5% (two halves, the first version, lost up to 10% at 50–65°). Only the three bins to the
 * point's +x are stored: the other three are their mirror, (−x, y, z).
 *
 * Along the shelf the heads repeat every [RoomLighting.RAIL_COLUMN_SPACING], and their beams
 * overlap three deep where the records are, so the lights rippled along a shelf by about ±1.5%
 * over most of a sleeve and ±6% across its top centimetre — scallops that jumped half a spacing
 * whenever the carrier snapped during a pan. The table is the rail as the continuous strip it
 * stands for: each head's light integrated along the shelf and divided by the spacing, which is
 * the heads' light averaged along it, exactly. That leaves two dimensions — how far below the
 * rail's line a point is, and how far behind the rail.
 *
 * Each head **as Filament computes a focused spot**: intensity in candela, the squared falloff
 * window, the cone's scale-and-offset attenuation (`surface_light_punctual.fs` and
 * `FLightManager::setSpotLightCone`, Filament 1.72.1). Per texel of bin ᵦ's table, RGB is Vᵦ in
 * units of [LUX_PER_UNIT] lux; A is unused.
 */
internal object RailLight {

    /** Bins stored: azimuth 0–30°, 30–60° and 60° round to straight behind, to the point's +x. */
    const val BINS = 3

    private val TAN_30 = tan(Math.toRadians(30.0)).toFloat()
    private val TAN_60 = tan(Math.toRadians(60.0)).toFloat()

    /** Texels from the rail's line down to its reach. */
    const val DOWN = 128

    /** Texels from [Z_NEAR] to [Z_FAR]. */
    const val BACK = 128

    /** The step along the shelf for the integral, in metres. */
    const val STEP = 0.002f

    /** What one unit in the table stands for. */
    const val LUX_PER_UNIT = 10_000f

    /** How far below the rail's line the table reaches: the falloff, past which there is nothing. */
    val D_MAX: Float = RoomLighting.RAIL_FALLOFF

    /**
     * How far behind the rail the table starts — negative, so in front of it, where a case held
     * open travels — and ends: just behind the panel's face, past which no plate can be.
     */
    const val Z_NEAR = -0.25f
    val Z_FAR: Float = RoomLighting.RAIL_Z - WallDimens.WALL_Z + 0.005f

    /** Where the rail hangs: in the tube's line, under the same board, on the same carrier row. */
    fun railY(rowCenterY: Float): Float = RibbonLight.tubeY(rowCenterY)

    /** How far below the rail's line texel row [k] is. */
    fun depth(k: Int): Float = (k + 0.5f) / DOWN * D_MAX

    /** How far behind the rail texel column [j] is; negative in front of it. */
    fun behind(j: Int): Float = Z_NEAR + (j + 0.5f) / BACK * (Z_FAR - Z_NEAR)

    /**
     * The stored bins' vectors at [d] below the rail's line and [behind] the rail (the rail's z
     * minus the point's), in lux: the rail to the point's +x, as a continuous strip.
     */
    fun binVectors(d: Float, behind: Float): Array<FloatArray> {
        val out = Array(BINS) { FloatArray(3) }
        val falloff = RoomLighting.RAIL_FALLOFF
        val rest = falloff * falloff - d * d - behind * behind
        if (rest <= 0f) return out
        val intensity = RoomLighting.RAIL_CANDELA
        val falloffInv = 1f / (falloff * falloff)
        val dir = RoomLighting.RAIL_DIRECTION
        val dirLen = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        val sx = -dir[0] / dirLen // -direction: the shader tests the cone against it
        val sy = -dir[1] / dirLen
        val sz = -dir[2] / dirLen
        val cone = RibbonLight.spot(RoomLighting.RAIL_CONE_INNER, RoomLighting.RAIL_CONE_OUTER)
        // Heads per metre of shelf: the integral along it, divided by the spacing.
        val perMetre = 1f / RoomLighting.RAIL_COLUMN_SPACING
        val reach = sqrt(rest)
        // Each bin over its own stretch of the strip, so no step straddles a bin's edge: from the
        // point, the strip crosses 30° and 60° of azimuth at behind·tan, and is all beyond 90°
        // for a point in front of the rail.
        val edges = if (behind > 0f) {
            floatArrayOf(0f, min(behind * TAN_30, reach), min(behind * TAN_60, reach), reach)
        } else {
            floatArrayOf(0f, 0f, 0f, reach)
        }
        for (b in 0 until BINS) {
            val from = edges[b]
            val span = edges[b + 1] - from
            if (span <= 0f) continue
            val steps = max(1, ceil(span / STEP).toInt())
            val dt = span / steps
            val v = out[b]
            for (k in 0 until steps) {
                // posToLight: from the point to the strip, t along it to the +x side.
                val t = from + (k + 0.5f) * dt
                val distSq = t * t + d * d + behind * behind
                val factor = distSq * falloffInv
                val smooth = (1f - factor * factor).coerceIn(0f, 1f)
                val distance = smooth * smooth / max(distSq, 1e-4f)
                if (distance == 0f) continue
                val r = sqrt(distSq)
                val lx = t / r
                val ly = d / r
                val lz = behind / r
                val a = ((sx * lx + sy * ly + sz * lz) * cone.scale + cone.offset).coerceIn(0f, 1f)
                val e = intensity * distance * a * a * perMetre * dt
                v[0] += e * lx
                v[1] += e * ly
                v[2] += e * lz
            }
        }
        return out
    }

    /** The bins' tables, each row by row from the rail's line down, [BACK] columns front to back. */
    fun tables(): Array<FloatArray> {
        val out = Array(BINS) { FloatArray(BACK * DOWN * 4) }
        for (k in 0 until DOWN) {
            val d = depth(k)
            for (j in 0 until BACK) {
                val bins = binVectors(d, behind(j))
                val o = (k * BACK + j) * 4
                for (b in 0 until BINS) {
                    out[b][o] = bins[b][0] / LUX_PER_UNIT
                    out[b][o + 1] = bins[b][1] / LUX_PER_UNIT
                    out[b][o + 2] = bins[b][2] / LUX_PER_UNIT
                }
            }
        }
        return out
    }

    /**
     * One bin's table as a half-float texture, made dark: [tables] takes a tenth of a second on a
     * phone, so it is computed off the main thread and written in with [RibbonLight.halfUpload]
     * (RailPlates). Built in the scene, never at file level (trap 23).
     */
    fun darkTexture(engine: Engine): Texture =
        RibbonLight.halfTexture(engine, FloatArray(BACK * DOWN * 4), BACK, DOWN)
}
