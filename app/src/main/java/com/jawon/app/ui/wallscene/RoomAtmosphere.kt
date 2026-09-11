package com.jawon.app.ui.wallscene

import com.google.android.filament.Texture
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
    // Pushed back from 0.72m when the bays widened and the camera moved to 0.78m. At the old
    // value the wall plane sat *behind* the start of the fog, so the entire rack was hazed
    // rather than just its far edges.
    private const val FOG_START = 1.15f
    private const val FOG_DENSITY = 0.9f
    private const val FOG_MAX_OPACITY = 0.92f



    /**
     * Bloom. DESIGN.md: "subtle, on the lamp and the disc's specular highlight only. Not a
     * global haze." Hence the threshold — without it every lit surface glows and the room
     * turns to soup. M9 part 1's IBL puts the lamp above 1.0, so there is a real highlight
     * for this to find.
     */
    /**
     * Not private, because it doubles as the sentinel [isApplied] reads. See that function for
     * why the room has to be able to ask whether its own settings are still in force.
     */
    const val BLOOM_STRENGTH = 0.28f

    /**
     * How far the glow spreads: blur levels, against Filament's default of six.
     *
     * The owner asked for fog, and the single strongest thing fog does to a light is give it a
     * wide soft halo. Filament has no volumetric lighting to do that properly (see [RoomAir]),
     * but each bloom level doubles the blur's reach, so two more levels turn a tight glow round
     * the strip into light hanging in the air around it. The added levels are at a sixteenth and
     * a thirty-second of the bloom's resolution, which is why this is cheap.
     */
    private const val BLOOM_LEVELS = 8

    /**
     * Lens dirt. Filament **multiplies** the bloom by [RoomAir.dirt] times this where it
     * composites, so the marks on the lens appear only where light falls through them — the
     * strip's halo, a hot highlight — and the rest of the frame stays clean.
     *
     * The inverse of [RoomAir.DIRT_CLEAN], so clean glass passes the bloom at exactly 1. At 0.9
     * over a black texture, which is what shipped first, it multiplied the bloom by nothing
     * across almost the whole frame. A test pins the product.
     */
    const val LENS_DIRT_STRENGTH = 2.5f

    /**
     * Lens flare, from Filament's bloom: ghosts mirrored through the centre of the frame, a
     * halo ring, a starburst and a little colour fringing on both.
     *
     * The threshold is the load-bearing number. Filament's default of 10 is for sunlight; here
     * nothing is that bright, so at the default there would be no flare at all. At this level
     * only the strips and the hottest highlights reach it — the album art and the lit panel
     * stay below, so the flare comes from the lights and nothing else. Few, faint ghosts: this
     * is an expensive lens catching a strip light, not a lens-flare filter.
     */
    private const val FLARE_THRESHOLD = 2.2f
    private const val FLARE_GHOSTS = 3
    private const val FLARE_GHOST_SPACING = 0.55f
    private const val FLARE_HALO_THICKNESS = 0.06f
    private const val FLARE_HALO_RADIUS = 0.42f
    private const val FLARE_CHROMATIC_ABERRATION = 0.004f

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
     * Half resolution, which is Filament's default — **and this is the fix for the stutter
     * during the open sequence.**
     *
     * It ran at full resolution to stop the far wall's blur bleeding over the disc's rim.
     * That was measured in the *settled* state, where it costs nothing, and the settled
     * state is not where the cost is. During the sequence the case flies to 0.21m and fills
     * the frame, so almost every pixel is inside a large circle of confusion and the gather
     * runs over all of them at full res.
     *
     * Measured across repeated open/close cycles on a Galaxy S25 Ultra at Thermal Status 2:
     *
     * | | average fps | frames at 16ms | hitches ≥24ms |
     * |---|---|---|---|
     * | full resolution | 102.6 | 243 | 16 |
     * | half resolution | **112.5** | **131** | **10** |
     *
     * The bleeding it was hired to fix is handled well enough by the median filter and the
     * tightened [DOF_MAX_BACKGROUND_COC] on their own. If it ever comes back, drop that
     * clamp further before reaching for this — steady frames during the one moment the user
     * is watching an animation are worth more than a pixel of rim.
     */
    private const val DOF_NATIVE_RESOLUTION = false

    /**
     * Gather ring counts. 0 is Filament's default and means adaptive — it picks a ring count
     * from the actual circle of confusion, which here is usually small.
     *
     * These were briefly pinned to 3 and 5, on the untested theory that a per-frame choice
     * would make blur quality pulse as the camera dollies. Measured on device, pinning them
     * cost nothing at all, and the pulsing was never observed either. Left adaptive because
     * that is the default and there is no evidence for doing anything else; if the pulsing
     * ever turns out to be real, bound the CoC rather than pinning the rings.
     */
    private const val DOF_FOREGROUND_RINGS = 0
    private const val DOF_BACKGROUND_RINGS = 0

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
     * AO render-target scale while browsing.
     *
     * Half resolution, which is Filament's own default and what every tier below HIGH already
     * uses. On the wall a case is a few hundred pixels tall and canted away from the lens, so
     * this costs nothing visible and is a quarter of the fragment work.
     */
    private const val AO_RESOLUTION_BROWSING = 0.5f

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
     * **OFF, and it should stay off until M10 can put it behind a tier.** The constants are
     * kept because the decision is worth being able to re-check, not because it is close.
     *
     * The idea was sound: the lid is polished plastic and the disc is glossy, and the only
     * thing either could reflect was the image-based light. Measured on a Galaxy S25 Ultra
     * with a case open, it is the most expensive thing anyone has put in this room:
     *
     * | configuration | fps |
     * |---|---|
     * | off (and the pre-M11 baseline) | **124** |
     * | on, 0.6m reach, stride 2 | **17** |
     * | on, 0.25m reach, stride 8 | **86** |
     *
     * Browsing the wall stays at 124 either way, which is what makes it look innocent: SSR
     * raymarches per glossy pixel, and browsing has almost none. Open a case and the lid and
     * disc fill the frame, so nearly every pixel traces.
     *
     * Even crippled to a stride of 8 it costs a third of the frame budget on flagship
     * hardware, for reflections in a room that is deliberately near-black, on a lid at 22%
     * alpha and a disc mostly covered by artwork. Raising the IBL to 256px already gave both
     * something to reflect, which was the actual complaint.
     *
     * Distances are in metres and scaled to this room, not to Filament's defaults (0.5m
     * thickness, 3m reach), which are again sized for architecture. A 0.5m ray thickness in
     * a scene 0.4m deep would treat the whole room as one surface.
     */
    private const val SSR_ENABLED = false
    private const val SSR_THICKNESS = 0.008f
    private const val SSR_BIAS = 0.002f
    private const val SSR_MAX_DISTANCE = 0.25f
    private const val SSR_STRIDE = 8.0f

    /**
     * Soft shadow penumbra, for [View.ShadowType.PCSS].
     *
     * PCSS is what makes a shadow behave like a real one: hard where the object touches the
     * surface, softening as it lifts away. The room's shadows were plain PCF at whatever map
     * size Filament defaults to, which gives a shadow of one uniform softness everywhere and
     * reads as a decal.
     *
     * **Measured against PCF and left on, unlike ambient occlusion.** The obvious question
     * once dynamic resolution exposed a live GPU-timer readout ([QualityTier.maxRenderScale])
     * was whether PCSS was the reason the wall sits at its render-scale floor rather than its
     * ceiling — it was the next-costliest-sounding thing still running while browsing, and it
     * is the reason `setAmbientOcclusion` came off the wall in the first place.
     *
     * It measured as nothing. Two methods, back to back on a cooled Galaxy S25 Ultra,
     * PCSS against PCF forced on with nothing else changed:
     *
     * | | CPU jiffies/10s (FEngine + JobSystem) | live render scale |
     * |---|---|---|
     * | PCSS | 117, 118, 113 | 60% (floor) |
     * | PCF | 113, 117, 116 | 60% (floor) |
     *
     * Both landed inside run-to-run noise, on both signals. The CPU number was expected to be
     * uninformative — PCSS and PCF differ only in the shadow map's *sampling pattern* inside
     * an existing fragment shader, not in the number of passes or draw calls Filament's
     * command generation submits, so there was never going to be a CPU-side story here the way
     * there was for AO's extra render targets. The render-scale number is the one that
     * mattered, because it reads Filament's own GPU timer query rather than anything this app
     * computes — and it did not move either. On a room lit by one spot, over a handful of
     * shadow-casting cases, at the resolution the wall is already drawn at, PCSS's per-pixel
     * cost over PCF's fixed kernel is too small to find. Left on at HIGH; not worth a crossing.
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

    /**
     * Configure the whole post chain for a device at [tier].
     *
     * Safe to call again when [FrameWatch] steps the tier down mid-session — every option
     * struct here is replaced wholesale rather than mutated, so re-applying is idempotent.
     * The one thing that cannot be re-applied is the IBL, which is baked once; see
     * [QualityTier.cubemapPx].
     */
    fun apply(view: View, tier: QualityTier, lensDirt: Texture? = null) {
        view.fogOptions = View.FogOptions().apply {
            enabled = true
            distance = FOG_START
            density = FOG_DENSITY
            maximumOpacity = FOG_MAX_OPACITY
            color = RoomLighting.VOID_COLOR.copyOf()
            // Flat, and this is load-bearing rather than a default - see [setFogFloor]. This
            // fog is what lifts the far wall off pure black, and any height falloff steep enough
            // to read as ground fog removes that lift at shelf height.
            heightFalloff = 0f
        }

        // Set here as well as in `RoomGrading.bind`, so the very first frame is dithered even
        // if the grade has not been bound yet. See RoomGrade.DITHERING for why this room needs
        // it more than most: almost all of its tonal range sits in the bottom few percent,
        // where 8-bit output has the fewest codes to spend.
        view.dithering = RoomGrade.DITHERING

        // **The underlights, and the most expensive thing in the room per unit of look.**
        //
        // Bloom is not one pass. It is a chain of downsamples and upsamples, and each one costs
        // draw-call submission on Filament's own thread as well as fragment work on the GPU —
        // at this panel's 1440x3120, a lot of both. It also runs on every frame of every screen,
        // including a wall that is doing nothing.
        //
        // **The `quality` line that used to be here did nothing, and the measurement that
        // justified it measured noise.** `View.setBloomOptions` reads eighteen fields and
        // passes them to `nSetBloomOptions`; `quality` is not one of them — checked in the
        // bytecode, the same way `AmbientOcclusionOptions.aoType` was. The numbers recorded
        // against it (MEDIUM 234 jiffies, off 186, LOW 172) said so on their own terms, and
        // were read the wrong way: **LOW came out cheaper than switching bloom off entirely**,
        // which is impossible for a setting that is on, and is the signature of three runs
        // separated by thermal drift rather than by a change.
        //
        // So the real cost of bloom in this room has never been established. It has been
        // running at Filament's default quality throughout. See `docs/room-design.md` §2.2.
        //
        // Everything else in the struct *is* forwarded and none of it is used — lens dirt,
        // flare, starburst, ghosts, halo, chromatic aberration. For a dark room whose subject
        // is one lamp and some small glowing things, that is the best look-per-unit-of-work in
        // the engine.
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = BLOOM_STRENGTH
            threshold = true
            levels = BLOOM_LEVELS
            // Null until RoomAir has built it off the main thread; the frame driver re-applies
            // once it lands, because isApplied compares it.
            dirt = lensDirt
            dirtStrength = LENS_DIRT_STRENGTH
            lensFlare = true
            starburst = true
            chromaticAberration = FLARE_CHROMATIC_ABERRATION
            ghostCount = FLARE_GHOSTS
            ghostSpacing = FLARE_GHOST_SPACING
            ghostThreshold = FLARE_THRESHOLD
            haloThickness = FLARE_HALO_THICKNESS
            haloRadius = FLARE_HALO_RADIUS
            haloThreshold = FLARE_THRESHOLD
        }

        // **Draw the room smaller than the screen, and let Filament put it back.**
        //
        // Every full-screen pass in this file — the fog, the bloom chain, the vignette, the
        // tone map, the FXAA, and the ambient occlusion and depth of field once a case is
        // open — costs one multiply per pixel of render target. On the test device that
        // target is 1440x3120: four and a half million pixels, a hundred and twenty times a
        // second. It is the largest single number in the room's whole cost and nothing had
        // ever touched it.
        //
        // `minScale`/`maxScale` carry the reasoning; see [QualityTier.maxRenderScale]. What
        // belongs here is the choice of upscaler, because it is not the obvious one.
        //
        // **LOW, deliberately, and not FSR1.** Filament offers a bilinear blit at LOW,
        // Qualcomm's SGSR at MEDIUM and AMD's FSR1 above that, and the better-sounding ones
        // are the wrong choice for this room. `Options.h` is explicit: *"FSR1 and SGSR
        // require a well anti-aliased (MSAA or TAA), noise free scene. Avoid FXAA and
        // dithering."* This room ran FXAA on every tier when that was written, and still does on
        // LOW. Both are sharpening reconstruction filters, and what they would reconstruct is
        // FXAA's own smearing, amplified. A bilinear blit does not sharpen and cannot amplify
        // anything — the caveat is scoped to the two that do.
        //
        // TAA was investigated and rejected, and that still stands: it is barely bound on
        // Android — `nSetTemporalAntiAliasingOptions` forwards three of its sixteen fields,
        // the upscaling factor is not among them, and one of the three is deprecated as having
        // no effect. MSAA was rejected at the same time on the grounds that geometry is not
        // what aliases here, and **that part was wrong** — see [QualityTier.msaaSamples] and
        // the block below. The upscaler stays LOW regardless: at HIGH the ceiling is 1.0, so it
        // only runs when a hot phone has already pulled the scale down.
        //
        // `homogeneousScaling` so the room never stretches: an anisotropic drop is visible in
        // a way a uniform one is not. Sharpness is Filament's own default and applies only at
        // MEDIUM and above, so it is inert here and left alone.
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
            enabled = true
            homogeneousScaling = true
            // SGSR wherever MSAA is on. Options.h's condition for it — "a well anti-aliased
            // (MSAA or TAA), noise free scene" — is exactly what the tiers with MSAA now are, and
            // the owner's readout showed why it matters: at HIGH his S25 Ultra was drawing the
            // room at 81% and an S23 at MEDIUM at 55%, both stretched back up by a bilinear blit
            // that softens every edge and re-steps the ones MSAA had just smoothed. SGSR
            // reconstructs edges instead. LOW keeps FXAA, and FXAA keeps the bilinear blit.
            quality = if (tier.msaaSamples > 1) View.QualityLevel.MEDIUM else View.QualityLevel.LOW
            minScale = tier.minRenderScale
            maxScale = tier.maxRenderScale
        }

        // **Edges: MSAA where the tier has it, FXAA where it does not — never both.**
        //
        // The cases' rims are thinner than a pixel and were rasterising as dashes; four coverage
        // samples draw them as lines. See [QualityTier.msaaSamples] for the evidence.
        //
        // **No `customResolve`, and it was on for one release.** It tonemaps each sample before
        // averaging, which resolves HDR edges more cleanly — and on a tiling GPU it defeats the
        // reason MSAA is affordable at all: the samples can no longer resolve in tile memory, so
        // all four go out to DRAM and back for a separate resolve pass over the full frame. On
        // the owner's S25 Ultra the room then sat at an 81% render scale — the GPU out of
        // budget — and the phone "getting hot pretty fast". The hardware resolve is free; the
        // price is that the very brightest thin edges resolve a little harder.
        //
        // FXAA goes off when MSAA is on. It works on the finished image, so over edges MSAA has
        // already resolved it only smears — the spine lettering and the printed art are exactly
        // what it would blur.
        //
        // Written here and not once at startup, because SceneView's `RenderQuality` preset writes
        // both fields too (checked in the bytecode: `applyRenderQuality` sets the MSAA options
        // and `setAntiAliasing(FXAA)`), and it lands after this. [isApplied] notices the preset
        // and the frame driver re-applies — which now restores these along with the bloom.
        val msaa = tier.msaaSamples > 1
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = msaa
            sampleCount = tier.msaaSamples
            customResolve = false
        }
        view.antiAliasing = if (msaa) View.AntiAliasing.NONE else View.AntiAliasing.FXAA

        // On from the first frame, at browsing quality — the full configuration arrives when a
        // case does. It used to be off entirely here; see [setAmbientOcclusion] for why that was
        // reverted.
        setAmbientOcclusion(view, false, tier)

        view.screenSpaceReflectionsOptions = View.ScreenSpaceReflectionsOptions().apply {
            enabled = SSR_ENABLED
            thickness = SSR_THICKNESS
            bias = SSR_BIAS
            maxDistance = SSR_MAX_DISTANCE
            stride = SSR_STRIDE
        }

        // OFF, because nothing uses transmission any more.
        //
        // This was turned on for the cases' KHR_materials_transmission shells. Those went
        // back to alpha blending when it turned out refraction cannot show the cover inlay
        // sealed inside them — but this switch was left on, buying a refraction pass that no
        // material in the scene asks for. Free to leave enabled only if you never look.
        view.setScreenSpaceRefractionEnabled(false)

        // Contact-hardening shadows. The key light's own map size and bulb radius are set
        // where the light is built, in WallSceneScreen — this is only the filter.
        // Called, not assigned: Filament exposes `setShadowType` with no matching getter,
        // so Kotlin cannot synthesize a property for it the way it does for the rest.
        //
        // PCSS samples a variable penumbra per pixel and is the expensive one. PCF is a
        // fixed kernel — the shadow lands in the same place and is the same darkness, it
        // just no longer hardens at the contact point.
        view.setShadowType(
            if (tier.softShadows) View.ShadowType.PCSS else View.ShadowType.PCF
        )
        view.softShadowOptions = View.SoftShadowOptions().apply {
            penumbraScale = PENUMBRA_SCALE
            penumbraRatioScale = PENUMBRA_RATIO_SCALE
        }

        // Off to begin with; the frame driver turns it on only when a case is open. See
        // [setDepthOfField].
        setDepthOfField(view, false, tier)

        view.vignetteOptions = View.VignetteOptions().apply {
            enabled = true
            midPoint = VIGNETTE_MID
            feather = VIGNETTE_FEATHER
            color = floatArrayOf(0f, 0f, 0f, 1f)
        }
    }

    /**
     * Whether [apply]'s settings are still the ones on [view] — **and they are not, by default.**
     *
     * SceneView applies a `RenderQuality` preset of its own to whichever `View` it is handed:
     *
     * ```kotlin
     * LaunchedEffect(view, renderQuality) { view.applyRenderQuality(renderQuality) }
     * ```
     *
     * Its own KDoc states the contract — *"Apply additional View tweaks AFTER calling this — they
     * will not be undone"* — and the room breaks it, through no fault of its own. [apply] runs
     * from a `remember`, which is *during* composition; a `LaunchedEffect` body runs *after* the
     * composition commits. So the preset always lands second and wins, and it writes two fields
     * the room cares about very much:
     *
     * | field | room | `RenderQuality.Default` |
     * |---|---|---|
     * | `bloomOptions.strength` | [BLOOM_STRENGTH] | 0.1 |
     * | `ambientOcclusionOptions` quality | browsing preset | full |
     *
     * The second is the expensive one. AO is no longer switched off while browsing — see
     * [setAmbientOcclusion] — but it still runs there at a deliberately cheaper setting, and the
     * preset quietly replaces that with the full configuration for every session from launch
     * until the first case is closed, which is the first moment the frame driver's crossing
     * writes the field at all. The first is merely a lie: the underlight has been glowing at 0.1
     * while this file said 0.28.
     *
     * **Tested rather than counted.** The obvious fix is to re-apply for the first frame or three
     * and trust that the effect has landed by then. That is an ordering assumption, and an
     * ordering assumption is exactly what caused this. `strength` is the right sentinel because
     * it is written once by [apply] and by nothing else afterwards — unlike the ambient
     * occlusion flag, which the frame driver legitimately toggles on every open and close. The
     * read is free: Filament's getter hands back the Java struct it already holds.
     */
    fun isApplied(view: View): Boolean = view.bloomOptions.strength == BLOOM_STRENGTH

    /**
     * Ground fog, tried and withdrawn - and worth keeping the reason.
     *
     * Adding a floor made height fog possible for the first time, and the fog block's own comment
     * had been asking for it. Switching it on made the room **worse**, and the mechanism is the
     * useful part: the fog here is not decoration, it is what stops the far wall being pure
     * black. `VOID_COLOR` is a dark warm grey rather than zero, and uniform distance fog lifts
     * the wall plane toward it. Height fog replaces that lift with an exponential falloff
     * measured from the ground, and this room is barely half a metre tall - so at any falloff
     * steep enough to read as a layer, the fog at shelf height drops to near nothing and the
     * wall loses the only thing keeping it off black.
     *
     * Doing this properly means *adding* a ground layer to the existing distance fog rather than
     * swapping one for the other, which Filament's single `FogOptions` cannot express directly.
     * Kept as a named no-op so the next person finds this note rather than the idea.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setFogFloor(view: View, floorY: Float) = Unit

    /**
     * The same question, including the grade.
     *
     * [isApplied] checks the bloom strength because that is a field [apply] writes and nothing
     * else does. The grade is owned by a [RoomGrading] rather than by this file, so it needs the
     * holder to answer for it — and it has to be part of the per-frame check for exactly the
     * reason bloom is: SceneView writes its own `RenderQuality` preset over this View from a
     * `LaunchedEffect`, and a preset that reset the colour grading would leave the room running
     * ungraded with nothing to notice it.
     */
    fun isApplied(view: View, grading: RoomGrading): Boolean =
        isApplied(view) && grading.isBound(view)

    /**
     * And the lens dirt, which arrives a moment after the first frame — it is built off the
     * main thread — so [apply] runs once without it and has to run again when it lands. An
     * identity check: Filament's getter hands back the very struct [apply] set.
     */
    fun isApplied(view: View, grading: RoomGrading, lensDirt: Texture?): Boolean =
        isApplied(view, grading) && view.bloomOptions.dirt === lensDirt

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
     * It is also the costliest thing in Filament's post chain, which is why
     * [QualityTier.depthOfField] drops it first — below HIGH the open state simply has a
     * sharp background. Nobody who has not seen the other version misses it; a dropped
     * frame during the one moment the user is watching an animation is noticed by everyone.
     */
    /**
     * Contact shading, on only while a case is open.
     *
     * **It is what makes an object look like it is resting on something** — the dark line where
     * the lid meets the tray, the well of the tray, the seat of a case on its board — and its
     * absence was most of why the room read as pasted together rather than assembled. None of
     * that is in question. What is in question is paying for it on the flat wall.
     *
     * At HIGH this is the most expensive configuration Filament's AO offers: full resolution,
     * high quality, high upsampling and bent normals, over every pixel of a 1440x3120 panel,
     * every frame, forever. Measured back to back on the S25 Ultra at thermal 2, summed across
     * the FEngine and JobSystem threads over ten seconds on the wall with nothing happening:
     *
     *     AO on    309 jiffies
     *     AO off   218
     *
     * Ninety-one jiffies — **twenty-nine percent of everything the room costs** — for shading
     * whose finest detail is a one-millimetre seam. On the wall a case is a few hundred pixels
     * tall and canted away from the lens; the seam is not resolvable and the broad shading it
     * does contribute is doing a fraction of what the underlight and the fog already do. Open a
     * case and it flies to within a fifth of a metre of the lens, filling the frame, and every
     * one of those arguments reverses.
     *
     * **This used to switch AO off entirely while browsing, and that went too far.** Read the
     * argument above again: every clause of it indicts the *expensive configuration* — full
     * resolution, HIGH upsampling, bent normals, for a seam that is not resolvable at wall
     * distance. None of it indicts occlusion itself. What it actually bought was 91 jiffies;
     * what it cost was the shading that makes a case look like it is *resting* in its slot
     * rather than floating in front of the wall, which is most of what makes the room read as
     * a room. The owner noticed it was gone, which settles whether it was doing anything.
     *
     * So AO is now always on, and the *quality* rides the crossing rather than the switch:
     * cheap and broad while browsing, the full configuration the moment a case flies to within
     * a fifth of a metre of the lens and the seam becomes something you can actually see.
     *
     * Written once per crossing rather than per frame — this reallocates render targets.
     *
     * @param close a case is open and near the lens, so the fine detail is worth paying for.
     */
    fun setAmbientOcclusion(view: View, close: Boolean, tier: QualityTier) {
        val fine = close && tier == QualityTier.HIGH
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            this.enabled = tier.ambientOcclusion
            radius = AO_RADIUS
            intensity = AO_INTENSITY
            power = AO_POWER
            bias = AO_BIAS
            quality = if (fine) View.QualityLevel.HIGH else View.QualityLevel.MEDIUM
            // Full-resolution AO only up close. At the half resolution Filament defaults to,
            // the occlusion line where the lid meets the tray — a 1mm seam — lands between
            // samples and disappears. That is a real loss with the case in your hand and no
            // loss at all on the wall, where the seam is under a pixel: the shading survives
            // as shading, which is the part that grounds the case.
            resolution = if (fine) tier.aoResolution else AO_RESOLUTION_BROWSING
            upsampling = if (fine) View.QualityLevel.HIGH else View.QualityLevel.LOW
            lowPassFilter = View.QualityLevel.MEDIUM
            // Feeds occluded directions back into the indirect light, so the IBL arrives
            // from where it can actually reach rather than uniformly. The most expensive
            // single flag here, and the one whose effect is least visible at wall distance.
            bentNormals = fine

            ssctEnabled = tier.ssct
            ssctIntensity = SSCT_INTENSITY
            ssctShadowDistance = SSCT_SHADOW_DISTANCE
            ssctContactDistanceMax = SSCT_CONTACT_DISTANCE_MAX
            ssctLightDirection = RoomLighting.KEY_DIRECTION.copyOf()
        }
    }

    fun setDepthOfField(view: View, enabled: Boolean, tier: QualityTier) {
        view.depthOfFieldOptions = View.DepthOfFieldOptions().apply {
            this.enabled = enabled && tier.depthOfField
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
