package com.jawon.app.ui.pocketscene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import io.github.sceneview.environment.Environment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The pocket player's room, as an image-based light: a train carriage at night — **lit the way a
 * photographer would light the object in it.**
 *
 * **Why this room is lit by an environment at all:** almost every surface here is *metal* or
 * glass. A polished back and an anodised front are pictures of whatever is around them; with no
 * environment they render black, and no analytic light fixes that (`docs/realism.md` §1.2).
 *
 * **Why the environment is placed rather than painted.** The first version was a ring — a window
 * band all the way round, lights repeating in it — and on the owner's phone the device read as a
 * flat black slab: nothing bright sat where its surfaces actually look. A flat face reflects one
 * small patch of the world, so the patch has to be *chosen*. With the device turned 15° and
 * tipped 6°, the view reflects off:
 *
 * - **the front and the screen's glass** toward azimuth ~60°, elevation ~0.2 rad — behind you and
 *   over your right shoulder. That is where the **reading lamp** is: a warm softbox whose lower
 *   edge crosses the face, so the anodising has a gradient down it and the glass a sheen across
 *   its top, the look of every good product photograph.
 * - **the visible left side** toward azimuth ~−120°, level — the window behind the device, cool
 *   and dark with the city low in it, which is what the polished edge should show.
 * - **the chamfers** all round, which catch the **ceiling lamps**: a row of small warm points
 *   overhead, for the bright hairlines along the edges that say *machined metal*.
 *
 * Everything else is the dark of a carriage at night. The lights that *move* across the device are
 * analytic ones the room animates ([PassingLamps]); this is the room that holds still.
 *
 * Directions: azimuth is `atan2(z, x)` — 0 along +X, +π/2 toward the viewer — and elevation is up
 * from level. Built procedurally, like `RoomLighting`'s, so it can be tuned against a screenshot.
 */
object PocketLighting {

    /**
     * Cubemap face size. The glass at roughness 0.03 reads the sharpest level, and a softbox's
     * edge should be an edge on it, not a smear.
     */
    const val CUBEMAP_PX = 256

    /** How hard the carriage lights the device, before the room's own lights. */
    const val AMBIENT_INTENSITY = 2_600f

    /** The carriage at night: almost black, a little blue from the window. */
    private val VOID = floatArrayOf(0.0038f, 0.0042f, 0.0058f)

    /** The reading lamp, as a softbox: where it hangs, how large, how soft its edges, how bright. */
    private const val SOFTBOX_AZ = 1.04f
    private const val SOFTBOX_EL = 0.52f
    private const val SOFTBOX_HALF_AZ = 0.50f
    private const val SOFTBOX_HALF_EL = 0.30f
    private const val SOFTBOX_EDGE = 0.10f
    private val SOFTBOX_COLOUR = floatArrayOf(2.30f, 1.95f, 1.52f)

    /** The window behind the device: the glass dark and cool, the city a warm glow low in it. */
    private const val WINDOW_AZ = (-PI / 2.0).toFloat()
    private const val WINDOW_HALF_AZ = 1.05f
    private const val WINDOW_LOW_EL = -0.22f
    private const val WINDOW_HIGH_EL = 0.55f
    // As bright as the first version's band was: it is what gives the polished side its sheen,
    // and at a fifth of this the side fell from 35 to 3 of 255 on the owner's phone.
    private val WINDOW_COLOUR = floatArrayOf(0.24f, 0.30f, 0.42f)
    private val CITY_GLOW = floatArrayOf(0.20f, 0.13f, 0.075f)

    /** Far lights standing still in the window — the town a long way off. */
    private val WINDOW_LIGHTS = arrayOf(
        floatArrayOf(-2.30f, 0.06f, 2.6f, 1.6f, 0.7f),
        floatArrayOf(-1.95f, 0.12f, 1.4f, 1.3f, 1.2f),
        floatArrayOf(-1.62f, 0.03f, 2.8f, 1.9f, 0.9f),
        floatArrayOf(-1.20f, 0.18f, 1.2f, 1.4f, 1.9f),
        floatArrayOf(-0.95f, 0.08f, 2.4f, 1.3f, 0.5f),
        floatArrayOf(-0.72f, 0.02f, 1.8f, 1.6f, 1.1f)
    )
    private const val WINDOW_LIGHT_SIZE = 0.035f

    /** The ceiling lamps: a row of small warm points down the carriage, overhead. */
    private const val CEILING_EL = 1.12f
    private const val CEILING_LAMPS = 7
    private const val CEILING_LAMP_SIZE = 0.05f
    private val CEILING_LAMP_COLOUR = floatArrayOf(3.4f, 2.8f, 2.1f)

    /**
     * The carriage's other windows, all round at eye level, dimmer than the one behind the device:
     * what keeps the wheel's rim and the chamfers lit from every side, which is most of what
     * separates a dark device from a dark room.
     */
    private val WINDOWS_ALL_ROUND = floatArrayOf(0.10f, 0.12f, 0.17f)
    private const val WINDOWS_EL = 0.06f
    private const val WINDOWS_HALF_EL = 0.30f

    /** Warm bounce off the seats and the floor, below level everywhere — the underside's fill. */
    private val BOUNCE = floatArrayOf(0.045f, 0.035f, 0.026f)

    fun createEnvironment(
        engine: Engine,
        size: Int = CUBEMAP_PX,
        prefilter: (Texture) -> Texture
    ): Environment {
        val cubemap = buildCarriageCubemap(engine, size)
        val indirectLight = IndirectLight.Builder()
            .reflections(prefilter(cubemap))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    /** The carriage's radiance in one direction — pure, so the placement can be tested. */
    fun radiance(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val el = asin((dy / len).coerceIn(-1f, 1f))
        val az = atan2(dz, dx)

        for (c in 0..2) out[c] = VOID[c]

        // The reading lamp: a soft-edged rectangle in azimuth and elevation.
        val box = softRect(angleDelta(az, SOFTBOX_AZ), SOFTBOX_HALF_AZ, SOFTBOX_EDGE) *
            softRect(el - SOFTBOX_EL, SOFTBOX_HALF_EL, SOFTBOX_EDGE)
        // Brighter toward its middle, as a diffuser is.
        val falloff = 0.75f + 0.25f * (1f - (abs(angleDelta(az, SOFTBOX_AZ)) / SOFTBOX_HALF_AZ).coerceIn(0f, 1f))
        for (c in 0..2) out[c] += SOFTBOX_COLOUR[c] * box * falloff

        // The window, and the city low in it.
        val inWindow = softRect(angleDelta(az, WINDOW_AZ), WINDOW_HALF_AZ, 0.12f) *
            softStep(WINDOW_LOW_EL - 0.05f, WINDOW_LOW_EL + 0.05f, el) *
            (1f - softStep(WINDOW_HIGH_EL - 0.08f, WINDOW_HIGH_EL + 0.08f, el))
        val glow = exp(-((el - 0.02f) / 0.10f) * ((el - 0.02f) / 0.10f))
        for (c in 0..2) out[c] += (WINDOW_COLOUR[c] + CITY_GLOW[c] * glow) * inWindow
        for (light in WINDOW_LIGHTS) {
            val d = hypot2(angleDelta(az, light[0]), el - light[1])
            val spot = exp(-(d / WINDOW_LIGHT_SIZE) * (d / WINDOW_LIGHT_SIZE)) * inWindow
            for (c in 0..2) out[c] += light[2 + c] * spot
        }

        // The ceiling lamps, evenly down the carriage's length (along X).
        for (i in 0 until CEILING_LAMPS) {
            val lampAz = (-PI + (i + 0.5) * 2.0 * PI / CEILING_LAMPS).toFloat()
            val d = hypot2(angleDelta(az, lampAz) * kotlin.math.cos(CEILING_EL), el - CEILING_EL)
            val spot = exp(-(d / CEILING_LAMP_SIZE) * (d / CEILING_LAMP_SIZE))
            for (c in 0..2) out[c] += CEILING_LAMP_COLOUR[c] * spot
        }

        // The other windows, all round.
        val round = 1f - softStep(WINDOWS_HALF_EL * 0.4f, WINDOWS_HALF_EL, abs(el - WINDOWS_EL))
        for (c in 0..2) out[c] += WINDOWS_ALL_ROUND[c] * round * (1f - inWindow)

        // The seats and the floor, a faint warmth below level.
        val below = softStep(0.05f, -0.4f, el)
        for (c in 0..2) out[c] += BOUNCE[c] * below
    }

    private fun buildCarriageCubemap(engine: Engine, size: Int): Texture {
        // RGBA float: the prefilter rejects an LDR single-level cubemap, and the lamps need
        // headroom above 1.0 or they are pale patches rather than lights.
        val faceBytes = size * size * 4 * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        val dir = FloatArray(3)
        val rgb = FloatArray(3)

        for (face in 0 until 6) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val u = (x + 0.5f) / size * 2f - 1f
                    val v = (y + 0.5f) / size * 2f - 1f
                    faceDirection(face, u, v, dir)
                    radiance(dir[0], dir[1], dir[2], rgb)
                    floats.put(rgb[0]); floats.put(rgb[1]); floats.put(rgb[2]); floats.put(1f)
                }
            }
        }

        val levels = 32 - Integer.numberOfLeadingZeros(size)
        val texture = Texture.Builder()
            .width(size)
            .height(size)
            .levels(levels)
            .format(Texture.InternalFormat.RGBA16F)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .usage(
                Texture.Usage.DEFAULT or
                    Texture.Usage.GEN_MIPMAPPABLE or
                    Texture.Usage.COLOR_ATTACHMENT
            )
            .build(engine)
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.FLOAT),
            IntArray(6) { it * faceBytes }
        )
        texture.generateMipmaps(engine)
        return texture
    }

    /** As `RoomLighting.faceDirection` — written into [out] rather than allocated per texel. */
    private fun faceDirection(face: Int, u: Float, v: Float, out: FloatArray) {
        when (face) {
            0 -> { out[0] = 1f; out[1] = -v; out[2] = -u }
            1 -> { out[0] = -1f; out[1] = -v; out[2] = u }
            2 -> { out[0] = u; out[1] = 1f; out[2] = v }
            3 -> { out[0] = u; out[1] = -1f; out[2] = -v }
            4 -> { out[0] = u; out[1] = -v; out[2] = 1f }
            else -> { out[0] = -u; out[1] = -v; out[2] = -1f }
        }
    }

    /** Shortest difference between two angles, wrapped to (−π, π]. */
    private fun angleDelta(a: Float, b: Float): Float {
        var d = a - b
        val twoPi = (2.0 * PI).toFloat()
        while (d > PI) d -= twoPi
        while (d <= -PI) d += twoPi
        return d
    }

    /** 1 inside ±[half], falling to 0 over [edge] beyond it. */
    private fun softRect(offset: Float, half: Float, edge: Float): Float =
        1f - softStep(half - edge * 0.5f, half + edge * 0.5f, abs(offset))

    private fun softStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hypot2(a: Float, b: Float): Float = sqrt(a * a + b * b)
}
