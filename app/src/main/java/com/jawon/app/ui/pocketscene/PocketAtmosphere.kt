package com.jawon.app.ui.pocketscene

import com.google.android.filament.View
import com.jawon.app.ui.wallscene.QualityTier

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
 * - **A restrained bloom** over the HDR threshold only: the overhead light along the bevels and
 *   the wheel's rim glows a little, as it does through a lens; the screen, below the threshold,
 *   stays crisp.
 * - **A vignette**, a hint at the phone's corners, so the face reads as lit from above you rather
 *   than evenly from everywhere.
 *
 * **Re-applied whenever it has been undone.** SceneView applies a `RenderQuality` preset of its own
 * to the View it is handed, after the first frame, overwriting whatever was set before it — the
 * CD room lost its bloom that way (`RoomAtmosphere.isApplied`). [BLOOM_STRENGTH] is the sentinel:
 * written here and by nothing else.
 */
object PocketAtmosphere {

    const val BLOOM_STRENGTH = 0.085f
    private const val BLOOM_LEVELS = 6
    // Light: at a midpoint of 0.42 (in the carriage) the top of the frame fell from 23 to 3 of 255
    // and the wheel went into the dark with it. A hint at the corners, not a spotlight.
    private const val VIGNETTE_MID = 0.68f
    private const val VIGNETTE_ROUNDNESS = 0.5f
    private const val VIGNETTE_FEATHER = 0.85f

    /**
     * The grade, at what [tier] can afford. **LOW drops the ambient occlusion and halves the
     * MSAA** — the two per-pixel costs here — and keeps the bloom and vignette, which are cheap
     * and are most of the look. Until 0.7.1 this room ignored the tier entirely: it was only
     * ever drawn on flagships, and 0.7.1 is the release that puts it on a Redmi 9.
     */
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
        // **Ambient occlusion at the scale of millimetres.** The wheel sits 0.6mm down in a
        // 0.4mm gap and the screen 1mm under the plate, and those recesses are what the eye reads
        // as depth — but lit only by the environment they get no shadow at all, and read as
        // lines drawn on a flat face. A radius of a few millimetres puts a contact shade in
        // exactly those places and nowhere else on an object this size.
        //
        // **At half resolution, measured.** Full resolution held the owner's phone's GPU at
        // 607–660MHz idle on the main menu; half, 342–443MHz, with every luminance box on the face
        // — the wheel's recess included — within 0.2 of 255. The radius is ~70 pixels at life
        // size, far above anything half resolution can lose.
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
        view.dithering = View.Dithering.TEMPORAL
        // No shadows to set: the face is lit by the room it reflects and a key that casts none.
        // (The carriage's lamp used PCSS — plain PCF drew one hard line under the device, DPCF
        // broke the cloth up in blocks, VSM's blur lost the shadow altogether — if a shadow ever
        // comes back, that is where to start.)
    }

    private const val AO_RADIUS_M = 0.0035f
    private const val AO_INTENSITY = 1.1f
    private const val AO_POWER = 1.4f
    private const val AO_BIAS_M = 0.00005f

    fun isApplied(view: View): Boolean = view.bloomOptions.strength == BLOOM_STRENGTH
}
