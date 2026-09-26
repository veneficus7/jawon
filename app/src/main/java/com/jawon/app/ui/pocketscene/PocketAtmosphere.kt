package com.jawon.app.ui.pocketscene

import com.google.android.filament.View

/**
 * How the pocket player's room is *photographed*: the grade on Filament's `View`.
 *
 * The CD room's `RoomAtmosphere` is bound to its four modes, which are the CD room's alone, so
 * this room has its own — and a simpler one, because it has one subject at arm's length rather
 * than a room of them:
 *
 * - **4× MSAA**, not FXAA. The device is all hard, thin, bright edges — the chamfer, the wheel's
 *   rim, the print — which is exactly where FXAA smears, and trap 30 measured MSAA as close to
 *   free where resolution is not.
 * - **A restrained bloom** over the HDR threshold only: the lamps' highlights and the brightest of
 *   the lights outside glow a little, as they do through a lens; the screen, below the
 *   threshold, stays crisp.
 * - **A vignette**, because the eye should come to rest on the device, not on the window's edge.
 *
 * **Re-applied whenever it has been undone.** SceneView applies a `RenderQuality` preset of its own
 * to the View it is handed, after the first frame, overwriting whatever was set before it — the
 * CD room lost its bloom that way (`RoomAtmosphere.isApplied`). [BLOOM_STRENGTH] is the sentinel:
 * written here and by nothing else.
 */
object PocketAtmosphere {

    const val BLOOM_STRENGTH = 0.085f
    private const val BLOOM_LEVELS = 6
    // Light: at a midpoint of 0.42 the top of the window fell from 23 to 3 of 255 and the wheel
    // went into the dark with it. The vignette is a hint at the corners, not a spotlight.
    private const val VIGNETTE_MID = 0.68f
    private const val VIGNETTE_ROUNDNESS = 0.5f
    private const val VIGNETTE_FEATHER = 0.85f

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
        view.dithering = View.Dithering.TEMPORAL
    }

    fun isApplied(view: View): Boolean = view.bloomOptions.strength == BLOOM_STRENGTH
}
