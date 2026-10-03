package com.jawon.app.ui.pocketscene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import io.github.sceneview.environment.Environment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The light the pocket player's face is seen by: a room around a phone held in the hand.
 *
 * **`tools/models/pocket_light.py` is the other copy**, and the one the face was designed against
 * in Cycles (`tools/pocket-preview/`): every constant here is a number there, [radiance] is its
 * function term for term, and `PocketLightingTest` pins this one to values the Python produces.
 *
 * Directions are in the face's frame — +X right, +Y up the phone, +Z out of the screen toward
 * whoever holds it — and the face is never turned, so that is the world's frame too. Seen straight
 * on, a flat face reflects only a narrow cone round +Z, ±14° up and down the phone, so the room is
 * placed for what the face *can* show:
 *
 * - **behind you, dark**: your own shadow — and the reason the screen's glass, which reflects
 *   3–11° up, never carries a glare across the picture;
 * - **a ceiling light above and behind you**, its lower edge at 15°: the top of the face catches it
 *   and the glow fades down the phone, as a product photograph's does;
 * - **you, lit by the room**: a dim band below the glass's elevations, which is what a black face —
 *   a near-mirror — shows instead of a void;
 * - **a lamp low on the left, a window on the right, and the zenith**: what the wheel's coat and the
 *   plate's bevels find, the warm and cool lines along the edges.
 *
 * Measured in the preview's luminance boxes (silver / black / red, of 255): the face's top 153 / 37 /
 * 34, its middle 160 / 26 / 38, its foot 137 / 24 / 31; the wheel 167–181 / 48–67 / 169–181; the
 * screen's glass with nothing on it, nothing.
 */
object PocketLighting {

    /** Cubemap face size. The glass at roughness 0.03 reads the sharpest level. */
    const val CUBEMAP_PX = 256

    const val AMBIENT_INTENSITY = 2_600f

    // ---- the key: a spot above and behind you, for the plastic parts ------------------------

    /** Face frame, metres: up and to the left of the phone, over your shoulder. */
    val KEY_POSITION = floatArrayOf(-0.12f, 0.40f, 0.40f)
    val KEY_AIM = floatArrayOf(0f, 0.05f, 0f)
    const val KEY_CANDELA = 3_200f
    val KEY_COLOUR = floatArrayOf(1.0f, 0.97f, 0.93f)

    /**
     * Half-angles. The whole face lies within 10° of the key's axis, inside the inner cone, so the
     * falloff shapes nothing on it — which is what lets Cycles' spot in the preview (a smoothstep
     * in the cosine) and Filament's (the square of a linear ramp) light it the same.
     */
    const val KEY_CONE_INNER_RAD = 0.30f
    const val KEY_CONE_OUTER_RAD = 0.70f

    /** Past the phone by a long way: Filament's falloff is a hard cutoff (trap 6). */
    const val KEY_FALLOFF_M = 3f

    // ---- the room -----------------------------------------------------------------------------

    private val VOID = floatArrayOf(0.006f, 0.0058f, 0.0056f)

    private val SOFTBOX = floatArrayOf(8.0f, 8.0f, 8.1f)
    private const val SOFTBOX_EL_LO = 15f
    private const val SOFTBOX_EL_HI = 62f
    private const val SOFTBOX_AZ_LO = -48f
    private const val SOFTBOX_AZ_HI = 48f
    private const val SOFTBOX_EDGE = 3f

    private val ZENITH = floatArrayOf(16.0f, 16.0f, 16.2f)
    private const val ZENITH_FROM_EL = 68f
    private const val ZENITH_EDGE = 8f

    private val LAMP = floatArrayOf(5.0f, 3.4f, 2.0f)
    private const val LAMP_AZ = -62f
    private const val LAMP_EL = -12f
    private const val LAMP_RAD = 22f

    private val WINDOW = floatArrayOf(1.4f, 1.7f, 2.3f)
    private const val WINDOW_AZ_LO = 62f
    private const val WINDOW_AZ_HI = 98f
    private const val WINDOW_EL_LO = -18f
    private const val WINDOW_EL_HI = 48f
    private const val WINDOW_EDGE = 5f

    private val FLOOR = floatArrayOf(0.35f, 0.30f, 0.24f)

    private val VIEWER = floatArrayOf(1.1f, 1.05f, 1.0f)
    private const val VIEWER_EL_LO = -34f
    private const val VIEWER_EL_HI = -3f
    private const val VIEWER_AZ_LO = -55f
    private const val VIEWER_AZ_HI = 55f
    private const val VIEWER_EDGE = 6f

    private const val DEG = 57.29578f   // degrees in a radian

    private val LAMP_DIR = floatArrayOf(
        cos(LAMP_EL / DEG) * sin(LAMP_AZ / DEG),
        sin(LAMP_EL / DEG),
        cos(LAMP_EL / DEG) * cos(LAMP_AZ / DEG)
    )

    fun createEnvironment(
        engine: Engine,
        size: Int = CUBEMAP_PX,
        prefilter: (Texture) -> Texture
    ): Environment {
        val cubemap = buildCubemap(engine, size)
        val indirectLight = IndirectLight.Builder()
            .reflections(prefilter(cubemap))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    /** The room's radiance toward one direction, face frame — `pocket_light.radiance`. */
    fun radiance(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val x = dx / len
        val y = dy / len
        val z = dz / len
        val el = asin(y.coerceIn(-1f, 1f)) * DEG
        val az = atan2(x, z) * DEG
        for (c in 0..2) out[c] = VOID[c]

        val softbox = soft(el, SOFTBOX_EL_LO, SOFTBOX_EL_HI, SOFTBOX_EDGE) *
            soft(az, SOFTBOX_AZ_LO, SOFTBOX_AZ_HI, SOFTBOX_EDGE)
        val zen = ((el - ZENITH_FROM_EL) / ZENITH_EDGE).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val a = acos((x * LAMP_DIR[0] + y * LAMP_DIR[1] + z * LAMP_DIR[2]).coerceIn(-1f, 1f)) * DEG
        val lamp = exp(-(a / LAMP_RAD) * (a / LAMP_RAD))
        val window = soft(az, WINDOW_AZ_LO, WINDOW_AZ_HI, WINDOW_EDGE) *
            soft(el, WINDOW_EL_LO, WINDOW_EL_HI, WINDOW_EDGE)
        val viewer = soft(el, VIEWER_EL_LO, VIEWER_EL_HI, VIEWER_EDGE) *
            soft(az, VIEWER_AZ_LO, VIEWER_AZ_HI, VIEWER_EDGE)
        val below = ((-el - 20f) / 25f).coerceIn(0f, 1f)
        for (c in 0..2) {
            out[c] += SOFTBOX[c] * softbox + ZENITH[c] * zen + LAMP[c] * lamp + WINDOW[c] * window +
                VIEWER[c] * viewer + FLOOR[c] * below
        }
    }

    /** 1 inside [lo, hi], falling smoothly to 0 over [edge] outside it — `pocket_light._soft`. */
    private fun soft(x: Float, lo: Float, hi: Float, edge: Float): Float {
        val a = ((x - (lo - edge)) / edge).coerceIn(0f, 1f)
        val b = (((hi + edge) - x) / edge).coerceIn(0f, 1f)
        return a * a * (3f - 2f * a) * (b * b * (3f - 2f * b))
    }

    private fun buildCubemap(engine: Engine, size: Int): Texture {
        // RGBA float: the prefilter rejects an LDR single-level cubemap, and the zenith needs
        // headroom above 1.0 or it is a pale patch rather than a light.
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
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE or Texture.Usage.COLOR_ATTACHMENT)
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
}
