package com.jawon.app.ui.wallscene

import com.google.android.filament.View

/**
 * Everything between the camera and the wall — DESIGN.md §4's depth of field, bloom, fog
 * and vignette, plus the contact shading and reflections that make the room read as solid.
 *
 * All of it is plain option structs on Filament's [View], so unlike the dust it needs no
 * material, no asset and no dependency. They are configured once; the GPU does the rest.
 * The single exception is the depth-of-field *focus distance*, which tracks the subject and
 * therefore lives in the frame driver rather than here.
 *
 * Sits beside [RoomLighting] so the room's whole look is reviewable in two files.
 *
 * **A warning about this file.** Filament's option structs are wired to native through
 * hand-written JNI, and the Java class carries fields the binding does not forward — set
 * one and it compiles, runs, and does nothing. `AmbientOcclusionOptions.aoType` is the one
 * that bites here; see [AO_RADIUS]. Before trusting a new knob, check it against
 * `nSet…Options`' argument list in the bytecode rather than against the class.
 *
 * The other recurring trap is scale. Filament's defaults are authored for rooms with
 * furniture in them; this room is 0.4m across and its details are millimetres. Every
 * distance here — AO radius, SSR thickness and reach, SSCT distances — is deliberately one
 * to two orders of magnitude below the default, and the defaults are useless rather than
 * merely untuned.
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
     * Two earlier attempts were both wrong, in opposite directions. The first ran a 12mm
     * aperture against a 33mm lens, where the field at half a metre is razor-thin, so the
     * whole frame blurred and the wall became unreadable. The correction stopped the
     * aperture right down to 3.5mm and then propped the result back up with a `cocScale` of
     * 2.4 — which is not the same thing at all. `cocScale` multiplies the blur radius
     * *after* the physics, so the field stayed deep while everything outside it smeared
     * 2.4× further than it should. That is what made the effect read as artificial.
     *
     * These are the honest pair: unity scale, and an aperture opened far enough to give
     * real subject/background separation on its own.
     */
    private const val DOF_COC_SCALE = 1.0f
    private const val DOF_MAX_APERTURE = 0.006f

    /**
     * Ceiling on how far out-of-focus background can smear, in pixels.
     *
     * Without it the disc came out sharp only where the *case* was behind it and blurred
     * where the *wall* was. Filament's depth of field gathers from neighbouring pixels, and
     * the wall sits about 1.25m away against the disc's 0.55m, so its circle of confusion
     * was enormous and bled inward over the disc's edge.
     *
     * **The clamp alone was treating the symptom, and 12px was still far too loose.** The
     * disc is flat, every normal `+Z`, and sits exactly on the focus plane, so it should be
     * uniformly sharp — but it is raised `bandY` above centre while the case behind it
     * drops by `LIFT_DROP_Y`, which leaves the disc's top ~25% with nothing behind it but
     * the 1.25m wall. That band is precisely where the bleed was eating into it. Tightened
     * here, and fixed properly by [DOF_NATIVE_RESOLUTION] and the median filter below.
     */
    private const val DOF_MAX_BACKGROUND_COC = 5
    private const val DOF_MAX_FOREGROUND_COC = 5

    /**
     * Run the depth-of-field pass at full resolution rather than Filament's default half.
     *
     * This is the actual fix for background bleeding over the disc. At half resolution the
     * gather works on a buffer where one texel spans two screen pixels, so the
     * in-focus/out-of-focus boundary is decided at half precision and the far wall's circle
     * of confusion reaches an extra pixel or two inward — across an edge as thin as the
     * disc's rim, that is the difference between a clean silhouette and a smeared one.
     *
     * It is the most expensive single switch in this file. If M10's tiers have to give
     * something back on weaker GPUs, this is the first candidate — followed by turning the
     * whole pass off, which is where it already was.
     */
    private const val DOF_NATIVE_RESOLUTION = true

    /**
     * Gather ring counts. Left at Filament's adaptive defaults (0) the pass picks a ring
     * count from the CoC size, which varies frame to frame as the camera dollies and makes
     * the blur quality visibly pulse during the open sequence. Pinned so it stays steady.
     */
    private const val DOF_FOREGROUND_RINGS = 3
    private const val DOF_BACKGROUND_RINGS = 5

    /**
     * Ambient occlusion — the contact shading that was missing entirely.
     *
     * Nothing in the room set this before, and its absence is most of why objects read as
     * pasted onto the scene rather than sitting in it: no darkening where the lid meets the
     * tray, none in the tray well, none where a case meets the board it stands on.
     *
     * **[AO_RADIUS] is the number that matters.** Filament's default is 0.3m, which is
     * sized for rooms with furniture in them; this room is 0.4m across and a case is 10mm
     * thick, so at the default the sampling sphere swallows the entire scene and the effect
     * is uniform grey — present, and worthless. 20mm is about two case thicknesses, which
     * is the scale of the creases that actually need it.
     *
     * Note this is SAO, not GTAO. `AmbientOcclusionOptions.aoType` exists on the Java class
     * but **is not forwarded by the JNI binding** in filament-android 1.72.1 —
     * `nSetAmbientOcclusionOptions` takes twelve values and the type is not among them, so
     * setting GTAO here would compile, do nothing, and quietly leave SAO running. Checked
     * against the bytecode rather than assumed.
     */
    private const val AO_RADIUS = 0.02f
    private const val AO_INTENSITY = 1.15f
    private const val AO_POWER = 1.0f
    private const val AO_BIAS = 0.0005f

    /**
     * Screen-space cone tracing — directional occlusion from the lamp's own direction.
     *
     * Unlike the AO above, which darkens creases from every direction equally, this traces
     * toward the key light, so a case shadows its neighbour along the direction the lamp
     * actually is. It is the cheap half of ENGINEERING.md's never-properly-checked "inter-case
     * shadowing", and unlike `aoType` it *is* forwarded — the binding sends it through a
     * separate `nSetSSCTOptions` call.
     */
    private const val SSCT_INTENSITY = 0.7f
    private const val SSCT_SHADOW_DISTANCE = 0.12f
    private const val SSCT_CONTACT_DISTANCE_MAX = 0.4f

    /**
     * Screen-space reflections.
     *
     * The lid is polished plastic at roughness 0.05 and the disc is 0.22, and until now the
     * only thing either could reflect was the image-based light — which is a smooth gradient
     * with no room in it. These give them the actual scene: the lamp, the board, the case
     * next door.
     *
     * Distances are in metres and scaled to this room, not to Filament's defaults (0.5m
     * thickness, 3m reach), which are again sized for architecture. A 0.5m ray thickness in
     * a scene 0.4m deep would treat the whole room as one surface.
     */
    private const val SSR_THICKNESS = 0.008f
    private const val SSR_BIAS = 0.002f
    private const val SSR_MAX_DISTANCE = 0.6f
    private const val SSR_STRIDE = 2.0f

    /**
     * Soft shadow penumbra, for [View.ShadowType.PCSS].
     *
     * PCSS is what makes a shadow behave like a real one: hard where the object touches the
     * surface, softening as it lifts away. The room's shadows were plain PCF at whatever map
     * size Filament defaults to, which gives a shadow of one uniform softness everywhere and
     * reads as a decal.
     */
    private const val PENUMBRA_SCALE = 1.0f
    private const val PENUMBRA_RATIO_SCALE = 1.0f

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

        // Contact shading. Unlike the effects above this one is not mood — it is the thing
        // that makes a rendered object look like it is resting on something.
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = true
            radius = AO_RADIUS
            intensity = AO_INTENSITY
            power = AO_POWER
            bias = AO_BIAS
            quality = View.QualityLevel.HIGH
            // Full-resolution AO. At the default half resolution the occlusion line where
            // the lid meets the tray — a 1mm seam — lands between samples and disappears.
            resolution = 1.0f
            upsampling = View.QualityLevel.HIGH
            lowPassFilter = View.QualityLevel.MEDIUM
            // Feeds occluded directions back into the indirect light, so the IBL arrives
            // from where it can actually reach rather than uniformly.
            bentNormals = true

            ssctEnabled = true
            ssctIntensity = SSCT_INTENSITY
            ssctShadowDistance = SSCT_SHADOW_DISTANCE
            ssctContactDistanceMax = SSCT_CONTACT_DISTANCE_MAX
            ssctLightDirection = RoomLighting.KEY_DIRECTION.copyOf()
        }

        view.screenSpaceReflectionsOptions = View.ScreenSpaceReflectionsOptions().apply {
            enabled = true
            thickness = SSR_THICKNESS
            bias = SSR_BIAS
            maxDistance = SSR_MAX_DISTANCE
            stride = SSR_STRIDE
        }

        // Contact-hardening shadows. The key light's own map size and bulb radius are set
        // where the light is built, in WallSceneScreen — this is only the filter.
        // Called, not assigned: Filament exposes `setShadowType` with no matching getter,
        // so Kotlin cannot synthesize a property for it the way it does for the rest.
        view.setShadowType(View.ShadowType.PCSS)
        view.softShadowOptions = View.SoftShadowOptions().apply {
            penumbraScale = PENUMBRA_SCALE
            penumbraRatioScale = PENUMBRA_RATIO_SCALE
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
            maxForegroundCOC = DOF_MAX_FOREGROUND_COC
            nativeResolution = DOF_NATIVE_RESOLUTION
            foregroundRingCount = DOF_FOREGROUND_RINGS
            backgroundRingCount = DOF_BACKGROUND_RINGS
            // Median pass over the gather result. Its whole purpose is suppressing the
            // bleed where a sharp subject sits against a very out-of-focus background —
            // which is exactly the disc against the far wall.
            filter = View.DepthOfFieldOptions.Filter.MEDIAN
        }
    }
}
