package com.jawon.app.ui.tapescene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.jawon.app.ui.common.EnvironmentCubemap
import com.jawon.app.ui.wallscene.QualityTier
import io.github.sceneview.environment.Environment
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The cassette room's light: a lamp, the lit room behind you, and a bulb in the deck.
 *
 * Its own, as every room's is (`docs/formats.md` §2). Designed and looked at in Cycles first
 * (`tools/tape-preview/`), with the same lights in the same places, and four things light it,
 * each with one job:
 *
 * - **The lamp** ([KEY_POSITION]): over your left shoulder, warm. The key, and the one light
 *   that casts — the box on the deck's top, a rack's tapes on each other.
 * - **The room behind you** (in the environment only): wide, soft and warm. **This is what the
 *   deck is made of**: brushed aluminium seen face on reflects what is behind the person looking
 *   at it, and with a dark room there the first Cycles frame had a black deck with amber holes in
 *   it. With it, the aluminium is aluminium.
 * - **The racks' lamp** ([RACKS_POSITION]): low and in front, so the spines' faces — which look
 *   straight out — are met nearly square. It goes along the wall with the camera, as a lamp you
 *   carry would; it is not a fitting anybody can see.
 * - **The bulb in the well** ([WELL_POSITION]): what a good deck had, so a tape could be read
 *   through its smoked door.
 *
 * The meters' backlight and the direction lamps are emissive, in their own materials.
 */
object TapeLighting {

    const val CUBEMAP_PX = 128

    /** How much the environment gives: low, the room is a room at night. */
    const val AMBIENT_INTENSITY = 6_000f

    // ---- The lamp ---------------------------------------------------------------------------------

    val KEY_POSITION = floatArrayOf(-0.55f, 1.10f, 0.70f)
    val KEY_AIM = floatArrayOf(0.05f, 0.20f, -0.10f)
    val KEY_COLOUR = floatArrayOf(1.0f, 0.80f, 0.58f)
    const val KEY_CANDELA = 11_000f
    const val KEY_CONE_INNER_RAD = 0.45f
    const val KEY_CONE_OUTER_RAD = 0.95f

    /** Longer than anything it lights: Filament's falloff is a cut-off (`ENGINEERING.md`, trap 6). */
    const val KEY_FALLOFF_M = 6f
    const val KEY_SHADOW_NEAR_M = 0.6f
    const val KEY_SHADOW_FAR_M = 2.4f

    // ---- The racks' lamp -----------------------------------------------------------------------------

    /** Relative to the camera's x: in front of and above the rack being read. */
    val RACKS_POSITION = floatArrayOf(0.16f, 1.05f, 0.55f)
    val RACKS_AIM = floatArrayOf(0.04f, 0.52f, TapeDimens.RACK_FRONT_Z)
    val RACKS_COLOUR = floatArrayOf(1.0f, 0.86f, 0.68f)
    const val RACKS_CANDELA = 8_500f
    const val RACKS_CONE_INNER_RAD = 0.36f
    const val RACKS_CONE_OUTER_RAD = 0.80f
    const val RACKS_FALLOFF_M = 4f

    // ---- The bulb in the well ---------------------------------------------------------------------

    /** Just behind the door's top rail, over the cassette's label. */
    val WELL_POSITION = floatArrayOf(0f, 0.100f, -0.0005f)
    val WELL_COLOUR = floatArrayOf(1.0f, 0.78f, 0.50f)
    const val WELL_CANDELA = 3.0f
    const val WELL_FALLOFF_M = 0.09f

    // ---- What the room reflects ---------------------------------------------------------------------

    private val lamp = unit(KEY_POSITION[0] - KEY_AIM[0], KEY_POSITION[1] - KEY_AIM[1], KEY_POSITION[2] - KEY_AIM[2])
    private val room = unit(0.15f, 0.22f, 1.0f)
    private val window = unit(0.95f, 0.30f, 0.20f)

    private const val LAMP_RADIUS_DEG = 8f
    private const val LAMP_EDGE_DEG = 6f
    private const val LAMP_HALO_DEG = 40f
    private const val LAMP_LEVEL = 24f
    private const val LAMP_HALO_LEVEL = 0.5f

    /** The lit room behind the camera: wide, and bright enough that aluminium facing it is silver. */
    private const val ROOM_RADIUS_DEG = 34f
    private const val ROOM_EDGE_DEG = 34f
    private const val ROOM_LEVEL = 1.7f

    private const val WINDOW_RADIUS_DEG = 14f
    private const val WINDOW_EDGE_DEG = 12f
    private const val WINDOW_LEVEL = 0.9f

    /** The light arriving from the unit direction ([dx], [dy], [dz]), as linear RGB into [out]. */
    fun radiance(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        var r: Float
        var g: Float
        var b: Float
        if (dy >= 0f) {
            r = 0.030f; g = 0.034f; b = 0.030f
        } else {
            r = 0.062f; g = 0.045f; b = 0.030f
        }
        val toLamp = angleDeg(dx, dy, dz, lamp)
        val shade = 1f - smooth(toLamp, LAMP_RADIUS_DEG, LAMP_RADIUS_DEG + LAMP_EDGE_DEG)
        val halo = (1f - smooth(toLamp, LAMP_RADIUS_DEG, LAMP_HALO_DEG)).let { it * it }
        val warm = LAMP_LEVEL * shade + LAMP_HALO_LEVEL * halo
        r += warm * KEY_COLOUR[0]; g += warm * KEY_COLOUR[1]; b += warm * KEY_COLOUR[2]

        val lit = ROOM_LEVEL * (1f - smooth(angleDeg(dx, dy, dz, room), ROOM_RADIUS_DEG, ROOM_RADIUS_DEG + ROOM_EDGE_DEG))
        r += lit * 1.0f; g += lit * 0.86f; b += lit * 0.70f

        val pane = WINDOW_LEVEL * (1f - smooth(angleDeg(dx, dy, dz, window), WINDOW_RADIUS_DEG, WINDOW_RADIUS_DEG + WINDOW_EDGE_DEG))
        r += pane * 0.55f; g += pane * 0.70f; b += pane * 1.0f

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

    private fun smooth(x: Float, from: Float, to: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * How the cassette room is photographed: the vinyl room's grade (`VinylAtmosphere`), with its
 * ambient occlusion at the scale of a cassette — the keys in their slot, a tape in its rack — and
 * a little more bloom, for the meters' glow. [BLOOM_STRENGTH] is the sentinel for SceneView's
 * own preset having undone it.
 */
object TapeAtmosphere {

    const val BLOOM_STRENGTH = 0.1025f
    private const val BLOOM_LEVELS = 6

    private const val AO_RADIUS_M = 0.008f
    private const val AO_INTENSITY = 1.0f
    private const val AO_POWER = 1.3f
    private const val AO_BIAS_M = 0.0002f

    fun apply(view: View, tier: QualityTier) {
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = if (tier == QualityTier.LOW) 2 else 4
        }
        view.antiAliasing = View.AntiAliasing.NONE
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = BLOOM_STRENGTH
            threshold = true
            levels = BLOOM_LEVELS
        }
        view.vignetteOptions = View.VignetteOptions().apply {
            enabled = true
            midPoint = 0.62f
            roundness = 0.45f
            feather = 0.9f
            color = floatArrayOf(0f, 0f, 0f, 1f)
        }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = tier.ambientOcclusion
            radius = AO_RADIUS_M
            intensity = AO_INTENSITY
            power = AO_POWER
            bias = AO_BIAS_M
            quality = View.QualityLevel.HIGH
            resolution = 0.5f
            upsampling = View.QualityLevel.HIGH
            lowPassFilter = View.QualityLevel.MEDIUM
        }
        view.setShadowType(View.ShadowType.PCF)
        view.dithering = View.Dithering.TEMPORAL
    }

    fun isApplied(view: View): Boolean = view.bloomOptions.strength == BLOOM_STRENGTH
}
