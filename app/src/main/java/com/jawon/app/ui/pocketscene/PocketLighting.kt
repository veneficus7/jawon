package com.jawon.app.ui.pocketscene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import io.github.sceneview.environment.Environment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The pocket player's room, as an image-based light: a train carriage at night.
 *
 * **Why this room is lit by an environment at all**, when the CD room's own row records
 * ambient being cut five-fold for flattening it: because almost every surface here is
 * *metal*. A mirror-polished back and an anodised front are pictures of whatever is around
 * them — with no environment they render black, and no amount of analytic light fixes that
 * (`docs/realism.md` §1.2: one IBL per scene, no reflection probes, no real-time GI).
 *
 * So the cubemap is the carriage: a dark interior, one bright band where the window is, a
 * dim warm strip along the ceiling, and — the part that matters for a polished object —
 * **bright spots repeating around the window band**, which are what a device's back catches
 * as the train passes lights. They are in the reflection only; the lights that actually
 * *move* across the device are analytic ones the room animates.
 *
 * Built procedurally, like `RoomLighting`'s, and for the same reasons: nothing to ship, and
 * it can be tuned against a screenshot rather than against an HDRI nobody can edit.
 */
object PocketLighting {

    /**
     * Cubemap face size. 256, matching the CD room's, and for its reason: the specular
     * prefilter turns this into a roughness mip chain, and a polished back at roughness 0.055
     * reads the sharpest level. A smaller face would reflect a smear where the window is.
     */
    const val CUBEMAP_PX = 256

    /**
     * How hard the carriage lights the device.
     *
     * Lower than the CD room's 8000: this is a dark train at night, and the whole point of the
     * scene is that the device is lit in flashes by what goes past rather than evenly by a
     * room. Raise it and the object floats in a grey box.
     */
    const val AMBIENT_INTENSITY = 2_600f

    // ---- the carriage, as bands of elevation ------------------------------------------

    /** Almost black, with a little blue in it — night, through glass, off dark trim. */
    private val VOID = floatArrayOf(0.0045f, 0.0052f, 0.0075f)

    /** The window band: where the outside is, a little above the device. */
    private const val WINDOW_ELEVATION_RAD = 0.10f
    private const val WINDOW_HALF_RAD = 0.30f
    private val WINDOW_COLOUR = floatArrayOf(0.26f, 0.32f, 0.44f)

    /**
     * The lights going past, as reflections.
     *
     * Twelve around the band rather than evenly spread light, because that is what makes a
     * polished surface read as *being somewhere*: a continuous band reflects as a smear, and
     * a smear is what a rendered object looks like.
     */
    private const val PASSING_COUNT = 12f
    private const val PASSING_SHARPNESS = 26f
    private val PASSING_COLOUR = floatArrayOf(2.10f, 1.72f, 1.05f)

    /** The carriage's own ceiling light: dim, warm, and above everything. */
    private const val CEILING_ELEVATION_RAD = 1.05f
    private const val CEILING_HALF_RAD = 0.45f
    private val CEILING_COLOUR = floatArrayOf(0.085f, 0.068f, 0.048f)

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

    private fun buildCarriageCubemap(engine: Engine, size: Int): Texture {
        // RGBA float: the prefilter rejects an LDR single-level cubemap, and the passing
        // lights need headroom above 1.0 or they are pale patches rather than lights.
        val faceBytes = size * size * 4 * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        val dir = FloatArray(3)

        for (face in 0 until 6) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val u = (x + 0.5f) / size * 2f - 1f
                    val v = (y + 0.5f) / size * 2f - 1f
                    faceDirection(face, u, v, dir)
                    val len = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
                    val elevation = asin((dir[1] / len).coerceIn(-1f, 1f))
                    val azimuth = atan2(dir[2], dir[0])

                    val window = 1f - smoothStep(
                        WINDOW_HALF_RAD * 0.35f, WINDOW_HALF_RAD,
                        abs(elevation - WINDOW_ELEVATION_RAD)
                    )
                    val ceiling = 1f - smoothStep(
                        CEILING_HALF_RAD * 0.4f, CEILING_HALF_RAD,
                        abs(elevation - CEILING_ELEVATION_RAD)
                    )
                    // Periodic in azimuth, and raised to a power so each one is a light
                    // rather than a bulge. Only inside the window: a lamp reflected in the
                    // ceiling would be a lamp that is not there.
                    val phase = cos(azimuth * PASSING_COUNT) * 0.5f + 0.5f
                    val passing = window * pow(phase, PASSING_SHARPNESS)

                    for (c in 0..2) {
                        floats.put(
                            VOID[c] +
                                WINDOW_COLOUR[c] * window +
                                CEILING_COLOUR[c] * ceiling +
                                PASSING_COLOUR[c] * passing
                        )
                    }
                    floats.put(1f)
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

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** `Math.pow` on floats, without the boxing — this runs 393k times. */
    private fun pow(base: Float, exponent: Float): Float {
        var result = 1f
        var b = base
        var e = exponent.toInt()
        while (e > 0) {
            if (e and 1 == 1) result *= b
            b *= b
            e = e shr 1
        }
        return result
    }
}
