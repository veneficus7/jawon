package com.jawon.app.ui.vinylscene

import com.google.android.filament.View
import com.jawon.app.ui.wallscene.QualityTier

/**
 * How the vinyl room is photographed: the grade on Filament's `View`.
 *
 * The pocket player's is the model (`PocketAtmosphere`) and the reasons are its reasons — 4× MSAA
 * because this is hard bright edges on dark, a bloom over the HDR threshold only, a vignette —
 * with two differences that are this room's own:
 *
 * - **It has shadows.** The lamp casts, and the arm's shadow across the record is most of what
 *   puts the arm above it. Plain PCF: the CD room found a soft shadow's stipple needs temporal
 *   anti-aliasing this renderer is not running (`RoomAtmosphere`), and a lamp a metre away
 *   throws an edge on a record 25mm below the arm that PCF draws cleanly anyway.
 * - **Its ambient occlusion is at the scale of a deck**, a centimetre and a half: the gap under
 *   the platter's rim, a sleeve lying on a table, a row of them standing in a crate. Without it
 *   those are objects pasted onto a surface.
 *
 * Re-applied whenever SceneView's own preset has undone it; [BLOOM_STRENGTH] is the sentinel.
 */
object VinylAtmosphere {

    /** Written here and by nothing else — not the pocket player's 0.085, nor the showroom's. */
    const val BLOOM_STRENGTH = 0.0925f
    private const val BLOOM_LEVELS = 6

    private const val VIGNETTE_MID = 0.62f
    private const val VIGNETTE_ROUNDNESS = 0.45f
    private const val VIGNETTE_FEATHER = 0.9f

    private const val AO_RADIUS_M = 0.015f
    private const val AO_INTENSITY = 1.0f
    private const val AO_POWER = 1.3f
    private const val AO_BIAS_M = 0.0003f

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
            midPoint = VIGNETTE_MID
            roundness = VIGNETTE_ROUNDNESS
            feather = VIGNETTE_FEATHER
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
