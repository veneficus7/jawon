package com.jawon.app.ui.showroom

import com.google.android.filament.View

/**
 * How the showroom is photographed: the grade on Filament's `View`.
 *
 * Its own, as every room's is, and close to the pocket room's for the same reasons — 4× MSAA
 * rather than FXAA for hard bright edges (a plinth's lacquered corner, a case's rim), a bloom over
 * the HDR threshold only, so a spot's highlight on lacquer glows a little as it does through a lens
 * — with two differences for a room of spotlit things standing in the dark:
 *
 * - **A heavier vignette.** The eye belongs on the plinth under the light, and the gallery is only
 *   the dark around it.
 * - **Ambient occlusion at the scale of centimetres**, where the pocket room's is millimetres: the
 *   cap's overhang, the foot on the floor, the exhibit's own recesses as seen from a metre away.
 *
 * Re-applied whenever SceneView's own preset has undone it — [BLOOM_STRENGTH] is the sentinel, as
 * it is in `PocketAtmosphere`.
 */
object ShowroomAtmosphere {

    const val BLOOM_STRENGTH = 0.09f
    private const val BLOOM_LEVELS = 6
    private const val VIGNETTE_MID = 0.52f
    private const val VIGNETTE_ROUNDNESS = 0.6f
    private const val VIGNETTE_FEATHER = 0.8f

    private const val AO_RADIUS_M = 0.02f
    private const val AO_INTENSITY = 1.0f
    private const val AO_POWER = 1.2f
    private const val AO_BIAS_M = 0.0005f

    fun apply(view: View) {
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
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
            enabled = true
            radius = AO_RADIUS_M
            intensity = AO_INTENSITY
            power = AO_POWER
            bias = AO_BIAS_M
            quality = View.QualityLevel.HIGH
            resolution = 0.5f
            upsampling = View.QualityLevel.HIGH
            lowPassFilter = View.QualityLevel.MEDIUM
        }
        view.dithering = View.Dithering.TEMPORAL
    }

    fun isApplied(view: View): Boolean = view.bloomOptions.strength == BLOOM_STRENGTH
}
