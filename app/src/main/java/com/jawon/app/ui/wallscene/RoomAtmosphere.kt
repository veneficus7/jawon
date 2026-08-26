package com.jawon.app.ui.wallscene

import com.google.android.filament.View

/**
 * Everything between the camera and the wall — DESIGN.md §4's depth of field, bloom, fog
 * and vignette.
 *
 * All four are plain option structs on Filament's [View], so unlike the dust they need no
 * material, no asset and no dependency. They are configured once; the GPU does the rest.
 * The single exception is the depth-of-field *focus distance*, which tracks the subject and
 * therefore lives in the frame driver rather than here.
 *
 * Sits beside [RoomLighting] so the room's whole look is reviewable in two files.
 */
object RoomAtmosphere {

    /**
     * Fog. The effect here that fixes a defect rather than adding mood: the wall currently
     * stops at a hard boundary, and DESIGN.md wants it to "recede into darkness at its
     * edges". Matches the palette's fog colour `#120E0B`, which is also
     * [RoomLighting.VOID_COLOR].
     *
     * Distance is where fog begins, in metres from the camera. The prototype's 7.2 in
     * decimetres — the wall centre sits about 0.5m away, so fog only starts biting toward
     * the far sides of a wide wall, which is exactly the intent.
     */
    private const val FOG_START = 0.72f
    private const val FOG_DENSITY = 0.9f
    private const val FOG_MAX_OPACITY = 0.92f

    /**
     * Bloom. DESIGN.md: "subtle, on the lamp and the disc's specular highlight only. Not a
     * global haze." Hence the threshold — without it every lit surface glows and the room
     * turns to soup. M9 part 1's IBL puts the lamp above 1.0, so there is a real highlight
     * for this to find.
     */
    private const val BLOOM_STRENGTH = 0.28f

    /**
     * Depth of field.
     *
     * **Expect this to be subtle while browsing.** The wall is flat and square-on: a case at
     * the screen edge is ~0.538m away against 0.5m at the centre, and no plausible aperture
     * separates a 7% difference. Where it earns its keep is the open state, when a case
     * flies toward the lens and the disc settles in front of it.
     *
     * The first attempt tried to exaggerate that and was badly wrong in the other
     * direction: at a 12mm aperture and 33mm focal length the field at half a metre is
     * razor-thin, so the whole frame blurred — shelf plates, spines and all — and the wall
     * became unreadable. A *small* aperture is what deepens the field, so this is stopped
     * well down and the scale left at unity.
     */
    private const val DOF_COC_SCALE = 2.4f
    private const val DOF_MAX_APERTURE = 0.0035f

    /**
     * Ceiling on how far out-of-focus background can smear, in pixels.
     *
     * Without it the disc came out sharp only where the *case* was behind it and blurred
     * where the *wall* was. Filament's depth of field gathers from neighbouring pixels, and
     * the wall sits about 1.25m away against the disc's 0.55m, so its circle of confusion
     * was enormous and bled inward over the disc's edge. Clamping it keeps the background
     * soft without letting it eat the subject.
     */
    private const val DOF_MAX_BACKGROUND_COC = 12

    /** Vignette. Pulls the eye to the middle and hides the wall's edges cheaply. */
    private const val VIGNETTE_MID = 0.62f
    private const val VIGNETTE_FEATHER = 0.42f

    /**
     * Focus distance while browsing — the wall plane, which is roughly [WallDimens.CAMERA_Z]
     * in front of the camera.
     */
    const val FOCUS_BROWSING = WallDimens.CAMERA_Z

    /** Focus distance once a case is open — where the disc settles in front of the lens. */
    const val FOCUS_DISC = 0.55f

    /** How fast focus eases between the two, in units per second. */
    const val FOCUS_RATE = 1.6f

    fun apply(view: View) {
        view.fogOptions = View.FogOptions().apply {
            enabled = true
            distance = FOG_START
            density = FOG_DENSITY
            maximumOpacity = FOG_MAX_OPACITY
            color = RoomLighting.VOID_COLOR.copyOf()
            // Height fog would need a floor to pool on; this room has none.
            heightFalloff = 0f
        }

        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = BLOOM_STRENGTH
            threshold = true
            quality = View.QualityLevel.MEDIUM
        }

        // Off to begin with; the frame driver turns it on only when a case is open. See
        // [setDepthOfField].
        setDepthOfField(view, false)

        view.vignetteOptions = View.VignetteOptions().apply {
            enabled = true
            midPoint = VIGNETTE_MID
            feather = VIGNETTE_FEATHER
            color = floatArrayOf(0f, 0f, 0f, 1f)
        }
    }

    /**
     * Depth of field, on only while a case is open.
     *
     * **It cannot be on while browsing.** DESIGN.md asks for "shelf sections to the sides
     * fall off", but this wall physically cannot deliver that: it is flat and square-on, so
     * everything is at nearly the same depth, and a 33mm lens focused at half a metre has a
     * razor-thin field. Measured on device, any amount of blur erased the spines outright —
     * a spine is 10mm wide, about 25px, and smears into the black background completely,
     * while the big bright shelf plates survive. Legibility of the wall is the entire point
     * of the wall, so it wins.
     *
     * Once a case is out there is real depth to work with — the case flies toward the lens
     * and the disc settles in front of it — and that is worth having.
     *
     * It is also the costliest thing in Filament's post chain, so confining it to the open
     * state is a performance win too, and it stays the first thing M10's tiers should drop.
     */
    fun setDepthOfField(view: View, enabled: Boolean) {
        view.depthOfFieldOptions = View.DepthOfFieldOptions().apply {
            this.enabled = enabled
            cocScale = DOF_COC_SCALE
            maxApertureDiameter = DOF_MAX_APERTURE
            maxBackgroundCOC = DOF_MAX_BACKGROUND_COC
        }
    }
}
