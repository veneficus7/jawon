package com.jawon.app.ui.vinylscene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import com.jawon.app.ui.common.EnvironmentCubemap
import io.github.sceneview.environment.Environment
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The vinyl room's light: a lamp, a window, and the dark between them.
 *
 * Its own, as every room's is (`docs/formats.md` §2: the CD room's four modes are the CD room's).
 * It is a room at night with one shaded lamp, up and to the left of where you stand, and that is
 * deliberate rather than atmospheric: **a record is black, and the only thing there is to see of
 * it is what it reflects.** A broad soft light turns the whole side grey — the first render did
 * exactly that — where one small warm one draws the bow-tie of light across the grooves that
 * everybody knows a record by, and leaves the rest of it black.
 *
 * Four things light it, each with one job:
 *
 * - **The lamp** ([KEY_POSITION]): the key. A real spot, because it is the one light that has
 *   to cast — the arm's shadow across the record is most of what puts the arm *above* it — and
 *   the same lamp painted into the environment, because the highlight on the vinyl and the spun
 *   platter is a reflection of it and a spot is not something a surface can reflect.
 * - **The window** (in the environment only): cool, dim, off to the right. It is what the chrome
 *   of the arm and the platter's beads have on their dark side, so that metal does not end in
 *   black.
 * - **The target light** ([TARGET_POSITION]): the little lamp at the front of a deck that lights
 *   the stylus so the groove under it can be found in the dark. Here it does the same.
 * - **The strobe** ([STROBE_POSITION]): red, beside the platter, on its beaded rim and nowhere else.
 *
 * Designed and looked at in Cycles first (`tools/vinyl-preview/`), with the same lamp in the
 * same place.
 */
object VinylLighting {

    const val CUBEMAP_PX = 128

    /** How much the environment gives. Low: the room is dark and the lamp does the work. */
    const val AMBIENT_INTENSITY = 5_200f

    // ---- The lamp ------------------------------------------------------------------------------

    /** Up, to the left, and a little toward you — over your left shoulder as you stand at the deck. */
    val KEY_POSITION = floatArrayOf(-0.70f, 1.05f, 0.42f)
    val KEY_AIM = floatArrayOf(0.0f, 0.05f, 0.10f)
    val KEY_COLOUR = floatArrayOf(1.0f, 0.80f, 0.56f)
    const val KEY_CANDELA = 14_000f
    const val KEY_CONE_INNER_RAD = 0.38f
    const val KEY_CONE_OUTER_RAD = 0.80f

    /** Longer than anything it lights: Filament's falloff is a cut-off, not a decay (`ENGINEERING.md`, trap 6). */
    const val KEY_FALLOFF_M = 6f
    const val KEY_SHADOW_NEAR_M = 0.5f
    const val KEY_SHADOW_FAR_M = 2.6f

    // ---- The deck's own two lamps -----------------------------------------------------------------

    /** The target light's lens, at the front of the deck; aimed at wherever the stylus is. */
    val TARGET_POSITION = floatArrayOf(0.060f, VinylDimens.TOP_Y + 0.030f, VinylDimens.DECK_D / 2 - 0.038f)
    val TARGET_COLOUR = floatArrayOf(1.0f, 0.93f, 0.78f)
    const val TARGET_CANDELA = 22f
    const val TARGET_CONE_INNER_RAD = 0.20f
    const val TARGET_CONE_OUTER_RAD = 0.42f
    const val TARGET_FALLOFF_M = 0.6f

    /** The strobe's window, front left, facing the platter's rim. */
    val STROBE_POSITION = floatArrayOf(-0.1665f, VinylDimens.TOP_Y + 0.010f, 0.0785f)
    val STROBE_COLOUR = floatArrayOf(1.0f, 0.12f, 0.05f)
    const val STROBE_CANDELA = 2.2f
    const val STROBE_FALLOFF_M = 0.20f

    /** The crate's own light, so a sleeve can be read: from the lamp's side, high, raking across the covers. */
    val CRATE_POSITION = floatArrayOf(-0.55f, 0.55f, 1.75f)
    val CRATE_AIM = floatArrayOf(0.0f, VinylDimens.FLOOR_Y + 0.16f, 1.02f)
    const val CRATE_CANDELA = 2_600f

    // ---- What the room reflects ------------------------------------------------------------------------

    private val lamp = unit(KEY_POSITION[0] - KEY_AIM[0], KEY_POSITION[1] - KEY_AIM[1], KEY_POSITION[2] - KEY_AIM[2])
    private val window = unit(0.85f, 0.35f, 0.45f)
    private val wallPool = unit(-0.30f, 0.32f, -1.0f)
    private val ceilingOverLamp = unit(-0.22f, 1.0f, 0.10f)

    /** The lamp's shade, seen from the deck: a disc this wide, soft at its edge, and a glow round it. */
    private const val LAMP_RADIUS_DEG = 9f
    private const val LAMP_EDGE_DEG = 7f
    private const val LAMP_HALO_DEG = 46f
    private const val LAMP_LEVEL = 26f
    private const val LAMP_HALO_LEVEL = 0.55f

    private const val WINDOW_RADIUS_DEG = 17f
    private const val WINDOW_EDGE_DEG = 14f
    private const val WINDOW_LEVEL = 1.5f

    private const val POOL_RADIUS_DEG = 20f
    private const val POOL_EDGE_DEG = 24f
    private const val POOL_LEVEL = 0.7f

    /**
     * The ceiling over the deck, lit from below by the lamp. **This is what the deck is made
     * of, seen from above**: a mirror looked straight down on shows what is straight up, and
     * with a black ceiling the top plate, the platter and the arm were black shapes on a lit
     * table — the first frame on a device, top plate 0.0 of 255. A lamp lights the ceiling above
     * it; this is that, soft and wide, and it is why the metal is metal.
     */
    private const val CEILING_RADIUS_DEG = 24f
    private const val CEILING_EDGE_DEG = 40f
    private const val CEILING_LEVEL = 1.25f

    /**
     * The light arriving from the unit direction ([dx], [dy], [dz]), as linear RGB into [out].
     * Pure, so that where the lamp is — and that nothing else is bright — can be held by a test.
     */
    fun radiance(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        // The room itself: a dark ceiling and walls, and below the horizon the wood, a little
        // warmer for what the lamp puts on it.
        var r: Float
        var g: Float
        var b: Float
        if (dy >= 0f) {
            r = 0.026f; g = 0.029f; b = 0.034f
        } else {
            r = 0.060f; g = 0.044f; b = 0.030f
        }
        val toLamp = angleDeg(dx, dy, dz, lamp)
        val shade = 1f - smooth(toLamp, LAMP_RADIUS_DEG, LAMP_RADIUS_DEG + LAMP_EDGE_DEG)
        val halo = (1f - smooth(toLamp, LAMP_RADIUS_DEG, LAMP_HALO_DEG)).let { it * it }
        val warm = LAMP_LEVEL * shade + LAMP_HALO_LEVEL * halo
        r += warm * KEY_COLOUR[0]; g += warm * KEY_COLOUR[1]; b += warm * KEY_COLOUR[2]

        val pane = WINDOW_LEVEL * (1f - smooth(angleDeg(dx, dy, dz, window), WINDOW_RADIUS_DEG, WINDOW_RADIUS_DEG + WINDOW_EDGE_DEG))
        r += pane * 0.55f; g += pane * 0.72f; b += pane * 1.0f

        val pool = POOL_LEVEL * (1f - smooth(angleDeg(dx, dy, dz, wallPool), POOL_RADIUS_DEG, POOL_RADIUS_DEG + POOL_EDGE_DEG))
        r += pool * 1.0f; g += pool * 0.70f; b += pool * 0.42f

        val ceiling = CEILING_LEVEL * (1f - smooth(angleDeg(dx, dy, dz, ceilingOverLamp), CEILING_RADIUS_DEG, CEILING_RADIUS_DEG + CEILING_EDGE_DEG))
        r += ceiling * 1.0f; g += ceiling * 0.86f; b += ceiling * 0.68f

        out[0] = r; out[1] = g; out[2] = b
    }

    fun createEnvironment(engine: Engine, size: Int = CUBEMAP_PX, prefilter: (Texture) -> Texture): Environment {
        val cubemap = EnvironmentCubemap.build(engine, size, ::radiance)
        val indirectLight = IndirectLight.Builder()
            .reflections(prefilter(cubemap))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    private fun unit(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }

    private fun angleDeg(dx: Float, dy: Float, dz: Float, to: FloatArray): Float =
        Math.toDegrees(acos((dx * to[0] + dy * to[1] + dz * to[2]).coerceIn(-1f, 1f)).toDouble()).toFloat()

    /** 0 below [from], 1 above [to], eased between. */
    private fun smooth(x: Float, from: Float, to: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
