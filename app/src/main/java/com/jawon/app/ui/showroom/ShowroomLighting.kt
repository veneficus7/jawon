package com.jawon.app.ui.showroom

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
import kotlin.math.sqrt

/**
 * The showroom as an image-based light: **a dark gallery, lit for the things in it.**
 *
 * The exhibits are almost all reflection — a clear jewel case, a polished steel back, black lacquer
 * — and each is lit by its own spot, which is analytic and in the screen. What this supplies is
 * what those surfaces *show*, placed where they look (the same argument as `PocketLighting`):
 *
 * - **two tall strip lights behind you**, either side of the camera — where every face turned to
 *   the camera looks. As an exhibit turns, a strip's reflection slides across its face: a sheen
 *   moving over the jewel case's clear front, a band of light down the pocket player's black one.
 *   Strips, not a broad softbox: a doorway-sized light filled the whole clear lid at once and laid a
 *   grey veil over the cover, and put nothing on the black anodising that moved.
 * - **the ceiling's diffuser slots**, two long runs over the row, one in front of the plinths and
 *   one behind — for the bright lines along a bead, a case's edge and a plinth's lacquered top.
 *   Traced onto a ceiling, as the carriage's runs were, so they narrow and converge with distance.
 * - **the walls all round**, washed low and warm by the gallery's other lights, far off — what the
 *   polished side of the pocket player and the lacquer's rounded edges show.
 *
 * Everything else is the dark of a gallery with the lights down. Directions: azimuth is
 * `atan2(z, x)` — 0 along the row, +π/2 toward the viewer — and elevation is up from level.
 */
object ShowroomLighting {

    const val CUBEMAP_PX = 256

    /** The same scale as the pocket room's, for the same camera exposure. */
    const val AMBIENT_INTENSITY = 2_600f

    private val VOID = floatArrayOf(0.0016f, 0.0016f, 0.0019f)

    /**
     * The strips behind you: where each stands (azimuth, from straight behind the camera at π/2),
     * how wide, and how bright — the key's side brighter. Tall enough that a face tipped a few
     * degrees still finds them.
     */
    private const val BEHIND_AZ = (PI / 2.0).toFloat()
    private val STRIP_OFFSET = floatArrayOf(0.42f, -0.55f)
    private const val STRIP_HALF_W = 0.075f
    private const val STRIP_EDGE = 0.05f
    // From well below eye level: the camera is above the exhibits, so a face turned to it reflects a
    // direction a few degrees *below* level — the first strips stopped just short of it.
    private const val STRIP_LOW_EL = -0.45f
    private const val STRIP_HIGH_EL = 0.80f
    // Moderate: at nearly twice this, a turn that lined the black anodised front up with the key's
    // strip flashed the whole face silver.
    private val STRIP_COLOUR = arrayOf(
        floatArrayOf(2.0f, 1.86f, 1.66f),
        floatArrayOf(1.1f, 1.04f, 0.98f)
    )

    /** The ceiling's slots, over the row: how high above the exhibits, where across, how wide. */
    private const val CEILING_H_M = 2.1f
    private val SLOTS_Z_M = floatArrayOf(0.95f, -0.85f)
    private const val SLOT_W_M = 0.10f
    private const val HALL_HALF_LENGTH_M = 9f
    private val SLOT_COLOUR = floatArrayOf(4.2f, 4.0f, 3.6f)

    /** The walls round about, washed low and warm. */
    private val WALL_WASH = floatArrayOf(0.09f, 0.072f, 0.054f)
    private const val WASH_LOW_EL = -0.06f
    private const val WASH_HIGH_EL = 0.42f

    /**
     * The gallery's ceiling, a dark plaster lit back by the spots: the shadows' only fill. Without
     * it an exhibit's shadow on its cap was a hole, not a shade — the cap read as notched.
     */
    private val CEILING_GLOW = floatArrayOf(2.4f, 2.22f, 1.97f)
    private const val CEILING_FROM_EL = 0.30f
    private const val CEILING_FULL_EL = 1.10f

    /** The black stone floor, below level everywhere. */
    private val FLOOR = floatArrayOf(0.0042f, 0.0038f, 0.0034f)

    private const val TEXEL_RAD = 2f / CUBEMAP_PX

    /**
     * The gallery's six faces at [size], as the float pixels a cubemap is made of. Pure and slow —
     * 393,216 directions at the full size, a quarter of a second on the emulator — so the screen
     * works it out off the main thread and hands the result to [createEnvironment].
     */
    fun faces(size: Int = CUBEMAP_PX): ByteBuffer {
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
        return buffer
    }

    fun createEnvironment(
        engine: Engine,
        faces: ByteBuffer,
        size: Int = CUBEMAP_PX,
        prefilter: (Texture) -> Texture
    ): Environment {
        val cubemap = buildCubemap(engine, faces, size)
        val indirectLight = IndirectLight.Builder()
            .reflections(prefilter(cubemap))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    /** The gallery's radiance in one direction — pure, so the placement can be tested. */
    fun radiance(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val el = asin((dy / len).coerceIn(-1f, 1f))
        val az = atan2(dz, dx)

        for (c in 0..2) out[c] = VOID[c]

        // The strips behind you, each a tall soft-edged band, fading off at its ends.
        var strips = 0f
        for (i in STRIP_OFFSET.indices) {
            val band = softRect(angleDelta(az, BEHIND_AZ + STRIP_OFFSET[i]), STRIP_HALF_W, STRIP_EDGE) *
                softStep(STRIP_LOW_EL - 0.08f, STRIP_LOW_EL + 0.08f, el) *
                (1f - softStep(STRIP_HIGH_EL - 0.15f, STRIP_HIGH_EL + 0.15f, el))
            for (c in 0..2) out[c] += STRIP_COLOUR[i][c] * band
            strips += band
        }

        // The ceiling's slots: where this direction meets the ceiling, is it under one?
        if (dy > 0f) {
            val t = CEILING_H_M * len / dy
            val hx = dx / len * t
            val hz = dz / len * t
            if (abs(hx) < HALL_HALF_LENGTH_M) {
                val texel = t * TEXEL_RAD
                for (z in SLOTS_Z_M) {
                    val across = coverage(abs(hz - z), SLOT_W_M / 2f, texel)
                    for (c in 0..2) out[c] += SLOT_COLOUR[c] * across
                }
            }
        }

        // The ceiling's glow, strongest overhead.
        val ceiling = softStep(CEILING_FROM_EL, CEILING_FULL_EL, el)
        for (c in 0..2) out[c] += CEILING_GLOW[c] * ceiling

        // The walls, washed low all round — but not where the strips stand.
        val wash = softStep(WASH_LOW_EL - 0.05f, WASH_LOW_EL + 0.05f, el) *
            (1f - softStep(WASH_HIGH_EL - 0.15f, WASH_HIGH_EL + 0.15f, el))
        for (c in 0..2) out[c] += WALL_WASH[c] * wash * (1f - strips.coerceAtMost(1f))

        // The floor.
        val below = softStep(0.02f, -0.25f, el)
        for (c in 0..2) out[c] += FLOOR[c] * below
    }

    private fun buildCubemap(engine: Engine, buffer: ByteBuffer, size: Int): Texture {
        // RGBA float: the prefilter rejects an LDR single-level cubemap, and the slots need
        // headroom above 1.0 or they are pale patches rather than lights.
        val faceBytes = size * size * 4 * Float.SIZE_BYTES
        buffer.rewind()
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

    private fun angleDelta(a: Float, b: Float): Float {
        var d = a - b
        val twoPi = (2.0 * PI).toFloat()
        while (d > PI) d -= twoPi
        while (d <= -PI) d += twoPi
        return d
    }

    private fun softRect(offset: Float, half: Float, edge: Float): Float =
        1f - softStep(half - edge * 0.5f, half + edge * 0.5f, abs(offset))

    private fun softStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How much of a sample [filter] wide, [offset] from a band's middle, falls inside its ±[half]. */
    private fun coverage(offset: Float, half: Float, filter: Float): Float {
        val lo = maxOf(offset - filter / 2f, -half)
        val hi = minOf(offset + filter / 2f, half)
        return (maxOf(0f, hi - lo) / filter).coerceIn(0f, 1f)
    }
}
