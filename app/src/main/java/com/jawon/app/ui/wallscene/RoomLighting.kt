package com.jawon.app.ui.wallscene

import com.jawon.app.ui.theme.RoomScheme
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.round

/**
 * The Midnight Shop's light rig — DESIGN.md §1: "A record shop after closing. One warm lamp
 * over the racks, deep shadow everywhere else."
 *
 * Kept in one file, free of Compose and of Filament lifecycle beyond building the
 * [IndirectLight], for the same reason [OpenSequence] and [Scratch] are: the room's look
 * lives in these numbers and should be reviewable without a device attached.
 *
 * Positions are the prototype's (`javon-shelf-prototype.html`, the lights block) converted
 * ×0.1 — the prototype works in decimetres, where a 142mm case is 1.42 and here it is 0.142.
 *
 * **Intensities are NOT the prototype's.** three.js takes arbitrary multipliers; Filament is
 * physically based and wants candela against a scene roughly 0.4m across, so its 2.2 and 0.7
 * mean nothing here. These are physical estimates, found on the device — the same lesson
 * M8's `emissive` taught, where the prototype's 1.0 turned out to be enormous.
 */
object RoomLighting {

    // DESIGN.md §3's scene palette, as linear-ish RGB in 0..1.
    /** Key light (lamp) — `#FFD2A0`. */
    val KEY_COLOR = floatArrayOf(1.0f, 0.824f, 0.627f)

    /** Rim / bounce — `#F0A93C`. */
    val RIM_COLOR = floatArrayOf(0.941f, 0.663f, 0.235f)

    /** Ambient fill — `#40312A`. */
    val AMBIENT_COLOR = floatArrayOf(0.251f, 0.192f, 0.165f)

    /** Fog / background — `#120E0B`. What the room falls off to. */
    val VOID_COLOR = floatArrayOf(0.071f, 0.055f, 0.043f)

    /** The lamp, above and to the right, aimed back at the wall. */
    val KEY_POSITION = Position(0.16f, 0.42f, 0.34f)

    /** The coloured bounce, low and to the left. */
    val RIM_POSITION = Position(-0.26f, -0.12f, 0.24f)

    /**
     * The direction the key light points — normalised `-KEY_POSITION`, i.e. from the lamp
     * back down at the wall's centre.
     *
     * As a plain `FloatArray` because its consumer is [RoomAtmosphere]'s screen-space cone
     * tracing, which wants three floats rather than SceneView's `Direction`. The light node
     * itself derives the same vector through SceneView's `normalize`; if either moves, both
     * must.
     */
    val KEY_DIRECTION: FloatArray = floatArrayOf(
        -KEY_POSITION.x, -KEY_POSITION.y, -KEY_POSITION.z
    ).let { d ->
        val len = length(d)
        floatArrayOf(d[0] / len, d[1] / len, d[2] / len)
    }

    /**
     * Candela. A domestic lamp is a few hundred; these are close to their subject, so they
     * do not need to be large. Main tuning knobs — expect to move them on device.
     */
    /**
     * The shop lamp.
     *
     * **Pulled back from 500 when the shelves got their own lights.** It used to be the only
     * thing lighting the records, which forced it bright enough to reach every bay — and a lamp
     * bright enough to do that also lights the wall, which is why the wall sat at mid-tone with
     * the sleeves no brighter than the surface behind them. The reference images are the
     * opposite: the room is nearly black and the records carry all the light.
     *
     * Now this only has to say *there is a lamp in here*. The reading happens under
     * [SHELF_CANDELA].
     */
    const val KEY_CANDELA = 320f
    const val RIM_CANDELA = 30f

    /**
     * Cone half-angles in radians.
     *
     * Much wider than the prototype's PI/5. The prototype framed a handful of cases in a
     * landscape browser; our wall grows with the library, and at PI/5 exactly one case was
     * lit and the rest of the shelf fell into pure black — unbrowsable, whatever it did for
     * the mood. DESIGN.md wants deep shadow, not an unreadable wall.
     */
    const val KEY_CONE_INNER = 0.55f
    const val KEY_CONE_OUTER = 1.15f

    /** Metres at which each light has fallen off to nothing. */
    const val KEY_FALLOFF = 6.0f
    const val RIM_FALLOFF = 1.8f

    // ---- The shelf practicals -------------------------------------------------------
    //
    // **What every reference image has and this room did not.** In a real shop each shelf
    // carries its own strip, mounted under the board above and throwing down across the
    // faces of the records. The light source is *in the frame*, it is small, and it is
    // local — and the records are the only lit thing in a room that is otherwise black.
    //
    // Jawon had one distant spot doing all of it, plus an "underlight" per bay that is
    // emissive geometry: it glows and blooms, but it does not *illuminate* anything. So the
    // sleeves were lit by a lamp two feet away and nothing else, which is why they read at
    // the same brightness as the wall behind them.
    //
    // These are real Filament lights, one per visible row. The comment on `beam.glb` is
    // still true — Filament will not give every *album* a spot — but a row is not an album.
    // Three or four rows are on screen at once, which is an ordinary number of lights.

    /**
     * How far above a row's cases the strip hangs.
     *
     * Added to `centerY + CASE_H / 2`, so it clears the tops of the sleeves by this much.
     */
    const val SHELF_LIGHT_LIFT = 0.012f

    /**
     * How far **in front of** the records the strip hangs.
     *
     * **This is the number that makes the light do anything at all, and the first attempt had
     * it wrong.** A record's face is a vertical plane whose normal points at the viewer. A
     * light directly above it, aimed straight down, arrives perpendicular to that normal —
     * `N·L` is *exactly zero* and the artwork receives nothing. The first build of this looked
     * like it was working because the light pooled beautifully on the horizontal shelf board,
     * whose normal points straight up at it.
     *
     * So the strip hangs forward of the sleeves and aims back at them, the way track lighting
     * in a shop does. At this distance `N·L` on the face is about 0.74 — from nothing.
     */
    /**
     * Where the fixture hangs, front to back.
     *
     * **Was 0.155 — in front of the sleeves — and is now behind them.** The forward position
     * was arrived at by arithmetic rather than by looking at the reference: a light directly
     * above a vertical face gives `N·L = 0`, so the strip had to move somewhere with an angle
     * on the artwork, and forward was the nearest place that worked.
     *
     * It works and it is not what the photographs show. In every one of them the fixture is
     * tucked into the **top back corner** of the bay: hidden behind the lip, throwing down the
     * back panel and forward across the records, so what you see is a glowing back wall with
     * the records standing against it. A strip hung out in front of the records is a display
     * case, not a record shop.
     *
     * 18.5mm in front of [WallDimens.WALL_Z], which is close enough to the panel for
     * [WASH_CANDELA] to pool on it and far enough not to intersect it.
     */
    const val SHELF_LIGHT_Z = -0.050f

    /**
     * Which way a strip points: down, and back into the shelf.
     *
     * Derived from the geometry rather than picked — it is the vector from the light to the
     * middle of the face it is lighting, given [SHELF_LIGHT_LIFT] and [SHELF_LIGHT_Z]. Change
     * either of those and this has to be recomputed, which is why the arithmetic is written
     * out instead of a magic pair of numbers.
     */
    val SHELF_DIRECTION: FloatArray = floatArrayOf(
        0f,
        -(WallDimens.CASE_H / 2f + SHELF_LIGHT_LIFT),
        // Forward now, not back: the fixture moved behind the records, so the throw has to
        // cross them from above-behind. Reaching to roughly the front lip of the board keeps
        // `N·L` on the case fronts comfortably positive — which is the whole reason the
        // strip was ever put in front of them.
        WallDimens.CASE_D * 0.9f
    )

    /**
     * Brightness of one strip, in candela.
     *
     * Not comparable with [KEY_CANDELA] and should never be tuned against it: illuminance
     * falls with the square of distance, and this sits about 120mm from what it is lighting
     * where the lamp sits half a metre away. At this figure a sleeve receives roughly twice
     * what the key light gives it — which is the whole point, and what the reference images
     * show.
     */
    const val SHELF_CANDELA = 320f

    /**
     * How fast a strip dies, in metres.
     *
     * Wider than the first attempt, because these are no longer one-per-row: three of them
     * ride the browse position and have to cover whichever rows are on screen. Still short
     * enough that the light is gone well before the far edge of the wall, which is what keeps
     * the room dark everywhere the eye is not.
     */
    const val SHELF_FALLOFF = 0.30f

    /** A broad wash rather than a spot: a strip lights a whole row, not one record. */
    const val SHELF_CONE_INNER = 0.80f
    const val SHELF_CONE_OUTER = 1.35f

    /**
     * How many strips ride with the browse position, and how far apart.
     *
     * Three — the row being looked at and its two neighbours — spaced a row apart. See the
     * note where they are built for why they follow the *camera* rather than the rows.
     */
    // ---- The half of the fixture that lights the shelf itself ----------------------
    //
    // A strip that illuminates nothing around it reads as fake however bright it is, and that
    // was measurable rather than a matter of taste: the bar rendered at luminance 50 while the
    // back panel 19mm behind it sat at **8.2**, indistinguishable from the black void at the
    // edges of the frame. In the reference photographs the area immediately around the
    // fixture is the brightest thing after the fixture, and the gradient running down the
    // back panel out of it is most of what makes those rooms read as rooms.
    //
    // [SHELF_DIRECTION] cannot do this job. It aims forward, across the artwork, and the back
    // panel faces the camera — so the panel's `N·L` against it is negative and the wash would
    // be exactly zero. It needs its own light aimed **down and back**, into the wall.

    /**
     * Down and back, into the panel. The backward component is what makes `N·L` positive on a
     * wall facing the camera; straight down would graze it and light nothing, which is the
     * same trap that made the first shelf strips measure 146.9 → 147.7.
     */
    val WASH_DIRECTION: FloatArray = floatArrayOf(0f, -1f, -0.45f)

    /**
     * Dimmer than [SHELF_CANDELA] on purpose. This is spill off a fixture, not a second
     * source, and the records have to stay the brightest thing on the wall — reference note
     * 2, "the records are lit; the room is not".
     */
    const val WASH_CANDELA = 95f

    /** Wide: a pool spreading down the panel, not a second spot. */
    const val WASH_CONE_INNER = 1.0f
    const val WASH_CONE_OUTER = 1.5f

    /** Short reach, so the pool fades before it arrives at the row below. */
    const val WASH_FALLOFF = 0.22f

    const val SHELF_LIGHT_COUNT = 3

    // ---- The strip you can actually see ---------------------------------------------
    //
    // **Every reference photograph shows its own source.** A strip inside the shelf, a pendant,
    // a track spot, a device's display — the light is small, local, and *in the frame*. A room
    // lit by something you cannot see reads as lit by nothing, which is most of the difference
    // between the wall as it was and the photographs.
    //
    // Geometry rather than an emissive material from a `.glb`, because it needs no new asset:
    // an unlit colour above 1.0 goes straight to the output and blooms, since `RoomAtmosphere`
    // runs bloom thresholded at 1.0. Same trick the underlight uses, one surface up.

    /** How thick the bar is. A strip, not a soffit. */
    const val STRIP_H = 0.0035f

    /** How deep, front to back. Enough to catch the eye edge-on, not enough to be a shelf. */
    const val STRIP_D = 0.014f

    /** Where it sits, front to back. The tube belongs where the light comes from. */
    const val STRIP_Z = SHELF_LIGHT_Z

    /**
     * How bright the visible bar is, **and what colour it survives as.**
     *
     * Above bloom's 1.0 threshold so it glows rather than sitting there as a pale rectangle.
     * It does no lighting of its own — [SHELF_CANDELA] does that — so raising it buys glare
     * and nothing else.
     *
     * **The first values here were (2.1, 1.65, 1.15) and rendered pure white.** A vertical
     * luminance profile caught it: at the strip's row the frame read mean 241.4, max 242,
     * *100% of pixels clipped*, with no channel separation left. The reason is the
     * tonemapper's shoulder — [RoomGrade] runs ACES, which compresses toward white as values
     * climb, so three channels all comfortably over 1.0 arrive at the same place. A warm
     * tungsten strip is the most recognisable thing in the owner's reference photographs, and
     * it was being thrown away in the last step of the pipeline.
     *
     * So the ratio matters more than the level: red stays above the bloom threshold and
     * glows, green sits near it, blue well under. Same fixture, amber instead of white, and
     * the bloom halo around it warms with it.
     */
    val STRIP_EMISSIVE: FloatArray = floatArrayOf(1.7f, 0.86f, 0.32f)

    // ---- The body the strip is mounted in ------------------------------------------
    //
    // Without this the fixture is a bar that glows on all six faces, which is a light *box*
    // and reads as a neon tube hanging in the dark. Every reference photograph shows the
    // opposite: a dark housing tucked under the board above, and light only on the underside,
    // pointing down at the records. The housing is what makes the difference between a
    // luminous rectangle and a thing that is lighting something.
    //
    // Slightly deeper and taller than the tube so it reads as holding it, and shadow-neutral
    // for the same reason the strip is — it sits directly above the records it lights, so
    // casting would put a bar of shadow across them.

    /** How tall the housing is. Small; this is a channel, not a soffit. */
    const val HOUSING_H = 0.0075f

    /** How deep, front to back — enough to overhang the tube and hide it from above. */
    const val HOUSING_D = STRIP_D + 0.006f

    /**
     * The housing's colour, unlit and dark.
     *
     * Unlit rather than a shaded material on purpose: it sits immediately beside a bloom
     * source, and anything lit there picks up the strip's own glare and turns into a second
     * bright bar, which is the problem this is here to solve. A flat dark value holds the
     * line no matter how hot the tube gets.
     */
    val HOUSING_COLOR: FloatArray = floatArrayOf(0.035f, 0.030f, 0.026f)

    /**
     * The key light's shadow map, in texels per side.
     *
     * Filament's default is 1024, which is what `CaseSlots` was working around when it
     * turned on screen-space contact shadows and noted the map was too coarse to resolve a
     * contact line. The map has to cover the whole lit wall, so a 10mm spine occupies very
     * few texels of it.
     *
     * 2048 rather than 4096: this is a depth texture, so each step doubles memory as well
     * as texels, and 4096² is ~67MB of it for one light. 2048 quadruples the default's
     * texel count for ~17MB, which is the honest trade on a phone.
     *
     * **This is the HIGH tier's value only** — that "expect it to become a tier" prediction
     * came true, and the per-tier sizes are on [QualityTier.shadowMapPx]. Unlike the View
     * options, the size is fixed when the light is built and does not follow a mid-session
     * tier step; see the note at the light in `WallSceneScreen`.
     */
    const val KEY_SHADOW_MAP_PX = 2048

    /**
     * Emitter radius in metres, driving PCSS's contact hardening. A shop spot is a small
     * source, so shadows stay tight where objects touch and soften only gradually — which
     * is the point of the effect. Raise it for a softer, more diffuse room.
     */
    const val KEY_BULB_RADIUS = 0.025f

    /**
     * Depth range hints, in metres from the lamp. The lamp sits ~0.56m from the wall's
     * centre and the whole room fits inside a 1.5m sphere around it; bounding the range
     * this tightly is most of what buys usable depth precision at this scale.
     */
    const val KEY_SHADOW_NEAR = 0.05f
    const val KEY_SHADOW_FAR = 1.6f

    /**
     * Shadow acne bias. Filament's defaults are sized for metre-scale geometry and at this
     * scale they push a shadow clean off the object casting it — the lid's shadow detaches
     * from the lid. Scaled down to match millimetre features.
     */
    const val KEY_SHADOW_CONSTANT_BIAS = 0.001f
    const val KEY_SHADOW_NORMAL_BIAS = 0.01f

    /**
     * Lux for the image-based ambient.
     *
     * **This used to be 40,000, and its own comment said why: "this has to carry most of the
     * room". That was true, and it was the single thing making the room look flat.**
     *
     * An image-based ambient is uniform. It lights every surface the same amount wherever that
     * surface is, so a room carried by it has no falloff anywhere — which is the difference
     * between a space that is *lit* and one that is merely *illuminated*, and it is why adding
     * a lamp, a grade, a floor and a wall texture in turn all failed to change the look.
     *
     * Measured on the emulator rather than argued: dropping it from 40k to 8k took the wall
     * from 50.3 to 8.4 in output luminance while the sleeves only fell from 147 to 50 — the
     * wall was six-sevenths ambient. The same test showed the shelf strips lifting sleeves 58%
     * while moving the wall by half a unit, which is exactly the behaviour wanted and was
     * invisible underneath 40k of fill.
     *
     * So it is now low enough that local light decides the image, and no lower: this is what
     * keeps the case bodies (`#2A2622`) and the wall (`#1A1410`) from going to absolute black,
     * and the reference photographs have shadow detail rather than holes.
     */
    const val AMBIENT_INTENSITY = 11_000f

    /**
     * Builds the room's image-based lighting — **with no asset files at all.**
     *
     * SceneView can load an IBL from a `.hdr` or `.ktx`, but that would mean shipping a
     * baked environment map, and ENGINEERING.md's geometry rule ("procedural, no downloaded
     * models") is the same instinct. `createEnvironment` takes an [IndirectLight] we
     * construct ourselves, so instead:
     *
     * - **Reflections** come from a cubemap generated in code by [buildRoomCubemap] — warm
     *   glow above, near-black below — prefiltered by Filament into a roughness mip chain.
     *   This is what finally gives the case's clear lid and the disc something to reflect;
     *   until now the lid rendered as a flat white strip because there was nothing there.
     * - **Ambient** comes from spherical harmonics, authored rather than baked. Only the
     *   constant band and a vertical term are used, which is all a single dim fill needs.
     *
     * No skybox: the room is a lit wall standing in blackness, and a skybox would paint
     * over that blackness.
     */
    /**
     * @param cubemapPx face size for the generated cubemap, from [QualityTier.cubemapPx].
     *   Chosen once at startup and never changed: the environment is built before the first
     *   frame and prefiltered into a mip chain, so rebuilding it to match a mid-session tier
     *   step would stall the very frames the step is trying to rescue.
     */
    fun createEnvironment(
        engine: Engine,
        cubemapPx: Int,
        /**
         * The room's colour. Read here rather than from this object's constants because the
         * cubemap and the spherical harmonics are baked once, before the first frame — so
         * the scheme has to be known at build time. That is also why a scheme change reaches
         * the ambient only on the next launch: see [com.jawon.app.ui.theme.RoomScheme].
         */
        scheme: RoomScheme,
        prefilter: (Texture) -> Texture
    ): Environment {
        val cubemap = buildRoomCubemap(engine, cubemapPx, scheme)
        val reflections = prefilter(cubemap)
        val indirectLight = IndirectLight.Builder()
            .reflections(reflections)
            .irradiance(SH_BANDS, ambientSphericalHarmonics(scheme))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    private const val SH_BANDS = 3

    /**
     * Cubemap face size.
     *
     * Was 64, which is where the room's reflections fell down: the specular prefilter turns
     * this into a roughness mip chain, and 64px leaves so few levels that anything glossy —
     * the case's clear lid at roughness 0.05, the disc at 0.22 — reflected a smeared blob
     * instead of a room. Raising it four-fold per axis gives the chain something to work
     * with.
     *
     * The cost is a one-off 6 × 256² = 393k texels of CPU fill at scene creation, sixteen
     * times what 64 asked for, which is why [buildRoomCubemap]'s inner loop no longer
     * allocates per texel. It stays on the calling thread deliberately: every Filament call
     * around it — the texture build, `setImage`, and the prefilter's GPU pass — belongs to
     * the engine thread, so moving the arithmetic away would buy a few milliseconds and
     * hand back a threading bug.
     *
     * **This is the room's only source of sharp reflections.** Screen-space reflections were
     * built and measured at a 7× cost, and are off. So if the lid reads flat, this is the
     * knob, along with the hot lamp and fill discs burned into the map below — there is
     * nothing else for a glossy surface to see.
     *
     * The value now lives on [QualityTier.cubemapPx], since it is the one lighting
     * parameter a weaker device benefits from dropping.
     */

    /**
     * How bright the reflected lamp and the reflected void are, in the cubemap's own HDR
     * range. The lamp sits above 1.0 deliberately — that headroom is what makes it read as
     * a light source in a reflection rather than as a pale grey patch.
     */
    private const val LAMP_LEVEL = 1.2f

    /**
     * The environment's floor — how bright the room is in the directions with no lamp in them.
     *
     * **This is why cover art disappeared as a case turned.** Reported as artwork being
     * invisible "at sharp angles", and it was neither culling, nor depth, nor the clear shell:
     * two of those were tried and the third was disproved by making the shell transparent and
     * watching the artwork stay dark. It is simply that a cover is a *lit surface*, and this
     * room has one lamp.
     *
     * At 0.15 against a `VOID_COLOR` already down at 0.071, the darkest direction in the
     * environment came out around 0.011 — one percent of what a surface facing the lamp
     * receives. So as a case turned its cover away from the light there was nothing else to
     * light it, and the artwork went to black. The owner spotted the physics before I did:
     * *"for the cover art to be invisible there should be a direct light from the background
     * but there is no any light beside the cd cases"* — quite right, and the absence of that
     * fill was the whole fault.
     *
     * 0.62 gives a real floor while keeping the lamp four times brighter than it, so the room
     * stays a dark shop with one hot source rather than becoming evenly lit. What it costs is
     * a little of the contrast between the lit and unlit sides of a case. What it buys is that
     * every record on the wall can be recognised at every angle, which is the one thing a wall
     * you browse by looking has to do.
     */
    private const val VOID_LEVEL = 0.62f

    /**
     * A small, fierce lamp burned into the cubemap — **the thing that makes glossy surfaces
     * look glossy.**
     *
     * Screen-space reflections are off (they cost 7x; see [RoomAtmosphere]), so every shiny
     * surface in this room reflects the image-based light and nothing else. The cubemap was
     * a smooth vertical gradient from void to warm, and a mirror reflecting a smooth gradient
     * looks matte however low you push its roughness — there is no bright *feature* for it to
     * catch. That, not the material parameters, is why the cases read as dull plastic no
     * matter how much transmission and clearcoat were thrown at them.
     *
     * A hot, small source fixes it: the case's clear shell now has a hard highlight to drag
     * across itself as it turns, which is what the eye reads as glass. [LAMP_ANGLE_COS] sets
     * how tight it is — tighter is sharper and more jewel-like, wider is softer and more
     * like a diffuse ceiling panel.
     *
     * Well above 1.0 on purpose. It is HDR, thresholded bloom keys off values over 1.0, and a
     * reflection of a dim lamp is a dim reflection.
     */
    private const val LAMP_HOT_LEVEL = 26f
    private const val LAMP_ANGLE_COS = 0.955f
    private const val LAMP_FEATHER = 0.030f

    /**
     * A second, much broader and cooler source opposite the lamp, so a turning case picks up
     * a *sequence* of highlights rather than one glint and then nothing for half a
     * revolution. Real rooms have more than one window.
     */
    private const val FILL_HOT_LEVEL = 3.2f
    private const val FILL_ANGLE_COS = 0.72f
    private const val FILL_FEATHER = 0.22f

    // ---- The strips, in the room the cases reflect --------------------------------
    //
    // **This is the thing that was missing, and it is why the strip read as a neon tube
    // floating in black rather than a fixture in a shop.** The visible bar above each row is
    // unlit geometry: it contributes nothing to reflections. So every glossy jewel case on
    // the wall was still reflecting this cubemap's *lamp* — a single overhead cone left over
    // from the room's earlier lighting design — and nothing anywhere reflected a strip.
    //
    // In every one of the owner's reference photographs the giveaway that a rack is lit by a
    // strip is not the strip itself, it is the **line of it dragged across the glossy face of
    // every sleeve and lid**. Filament has no real-time area lights, so that reflection can
    // never come from the light. It has to come from the environment, which is exactly what
    // an environment map is for — and it costs nothing per frame, because this is baked once
    // before the first frame and prefiltered into the roughness mip chain.
    //
    // Bands rather than one band: the wall is rows of shelves, each with its own strip, so a
    // case reflects several of them receding. The period is the row pitch seen from browsing
    // distance — `atan(ROW_PITCH / CAMERA_Z)` is 14.7°, and 16° is within the slop of an
    // approximation that treats the environment as infinitely far away in the first place.

    /** Angular spacing of the reflected strips, in degrees. See the note above. */
    private const val STRIP_BAND_PERIOD_DEG = 16f

    /** How thick each reflected line is. A strip, not a glowing horizon. */
    private const val STRIP_BAND_WIDTH_DEG = 2.4f

    /**
     * How hot the reflected strips are.
     *
     * HDR and well over 1.0, for the same reason [LAMP_HOT_LEVEL] is: a reflection of a dim
     * source is a dim reflection, and this one has to survive the prefilter blurring it down
     * the mip chain before anything rough enough to matter ever samples it.
     */
    private const val STRIP_BAND_LEVEL = 11f

    /**
     * Irradiance as spherical harmonics: 3 bands, 9 coefficients, RGB each.
     *
     * Hand-authored rather than convolved from an environment — a single dim warm fill has
     * no detail worth baking. Index 0 is the constant term (the ambient colour itself) and
     * index 1 is the vertical one, biased positive so the fill arrives from above like the
     * lamp does. The rest stay zero.
     *
     * The sign convention on the vertical term is worth checking on device rather than
     * trusting; if the room looks lit from the floor, flip it.
     */
    private fun ambientSphericalHarmonics(scheme: RoomScheme): FloatArray {
        val sh = FloatArray(SH_BANDS * SH_BANDS * 3)
        for (c in 0..2) {
            sh[c] = scheme.ambient[c]
            sh[3 + c] = scheme.ambient[c] * 0.35f
        }
        return sh
    }

    /**
     * A 64² cubemap of the room: warm light pooling above, falling to near-black below.
     *
     * Face order is Filament's: +X, -X, +Y, -Y, +Z, -Z. Each texel's direction is
     * reconstructed from its face and its position on that face, and the colour is a
     * function of how far up that direction points — which is the whole of the shop's
     * lighting story, and enough for a reflection to read as a room rather than a colour.
     */
    private val STRIP_BAND_PERIOD_RAD =
        Math.toRadians(STRIP_BAND_PERIOD_DEG.toDouble()).toFloat()
    private val STRIP_BAND_HALF_RAD =
        Math.toRadians(STRIP_BAND_WIDTH_DEG.toDouble() / 2.0).toFloat()

    private fun buildRoomCubemap(engine: Engine, size: Int, scheme: RoomScheme): Texture {
        // RGBA float. IBL wants HDR — Filament's specular prefilter rejects an LDR
        // single-level cubemap outright, and the lamp needs headroom above 1.0 anyway if it
        // is going to read as a light source rather than as a pale patch.
        val floatsPerTexel = 4
        val faceBytes = size * size * floatsPerTexel * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()

        // Hoisted: these are six multiplications, not 393k of them.
        val void = FloatArray(3) { scheme.void[it] * VOID_LEVEL }
        val lamp = FloatArray(3) { scheme.key[it] * LAMP_LEVEL }
        // Reused across every texel. `faceDirection` used to return a fresh array per
        // texel, which was survivable at 64px and is 393k throwaway allocations at 256.
        val dir = FloatArray(3)

        // Where the two sources sit, as unit directions. The lamp is the real one the scene
        // is lit by, so its reflection agrees with its shading; the fill is opposite and low.
        val lampLen = length(floatArrayOf(KEY_POSITION.x, KEY_POSITION.y, KEY_POSITION.z))
        val lampX = KEY_POSITION.x / lampLen
        val lampY = KEY_POSITION.y / lampLen
        val lampZ = KEY_POSITION.z / lampLen
        val fillLen = length(floatArrayOf(RIM_POSITION.x, RIM_POSITION.y, RIM_POSITION.z))
        val fillX = RIM_POSITION.x / fillLen
        val fillY = RIM_POSITION.y / fillLen
        val fillZ = RIM_POSITION.z / fillLen

        for (face in 0 until 6) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // Face-local coordinates in -1..1, then the direction they point.
                    val u = (x + 0.5f) / size * 2f - 1f
                    val v = (y + 0.5f) / size * 2f - 1f
                    faceDirection(face, u, v, dir)
                    val up = dir[1] / length(dir)

                    // Everything above the horizon warms toward the lamp; everything below
                    // falls into the void. Raised to a power so the lamp stays a pool of
                    // light rather than washing the whole upper hemisphere.
                    val t = ((up + 1f) * 0.5f).coerceIn(0f, 1f).pow(2.2f)

                    // Both sources, as smooth-edged discs in the reflected room. Feathered
                    // rather than hard-cut: a stepped edge here shows up as a jagged rim on
                    // every reflective surface once the prefilter blurs it into the mips.
                    val invLen = 1f / length(dir)
                    val nx = dir[0] * invLen
                    val ny = dir[1] * invLen
                    val nz = dir[2] * invLen

                    val lampDot = nx * lampX + ny * lampY + nz * lampZ
                    val lampHit = smoothStep(
                        LAMP_ANGLE_COS - LAMP_FEATHER, LAMP_ANGLE_COS + LAMP_FEATHER, lampDot
                    )
                    val fillDot = nx * fillX + ny * fillY + nz * fillZ
                    val fillHit = smoothStep(
                        FILL_ANGLE_COS - FILL_FEATHER, FILL_ANGLE_COS + FILL_FEATHER, fillDot
                    )

                    // The reflected strips: warm horizontal lines repeating up and down the
                    // wall. Elevation angle rather than `ny` directly, so the spacing stays
                    // even instead of bunching up toward the poles, and feathered for the
                    // same reason the two discs above are — a hard edge here becomes a jagged
                    // rim once the prefilter blurs it into the mips.
                    val elevation = asin(ny.coerceIn(-1f, 1f))
                    val cycle = elevation / STRIP_BAND_PERIOD_RAD
                    val offBand = abs(cycle - round(cycle)) * STRIP_BAND_PERIOD_RAD
                    val bandHit = 1f - smoothStep(
                        STRIP_BAND_HALF_RAD * 0.4f, STRIP_BAND_HALF_RAD, offBand
                    )

                    for (c in 0..2) {
                        val ambientTerm = void[c] + (lamp[c] - void[c]) * t
                        floats.put(
                            ambientTerm +
                                scheme.key[c] * LAMP_HOT_LEVEL * lampHit +
                                scheme.rim[c] * FILL_HOT_LEVEL * fillHit +
                                scheme.key[c] * STRIP_BAND_LEVEL * bandHit
                        )
                    }
                    floats.put(1f)
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
            // The prefilter renders through this, and needs the mip chain it walks.
            .usage(
                Texture.Usage.DEFAULT or
                    Texture.Usage.GEN_MIPMAPPABLE or
                    Texture.Usage.COLOR_ATTACHMENT
            )
            .build(engine)

        val faceOffsets = IntArray(6) { it * faceBytes }
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(
                buffer,
                Texture.Format.RGBA,
                Texture.Type.FLOAT
            ),
            faceOffsets
        )
        texture.generateMipmaps(engine)
        return texture
    }

    /**
     * The unit-ish direction a texel on [face] at face-local ([u], [v]) points, written
     * into [out] rather than returned — see the allocation note in [CUBEMAP_PX].
     */
    private fun faceDirection(face: Int, u: Float, v: Float, out: FloatArray) {
        when (face) {
            0 -> { out[0] = 1f; out[1] = -v; out[2] = -u }    // +X
            1 -> { out[0] = -1f; out[1] = -v; out[2] = u }    // -X
            2 -> { out[0] = u; out[1] = 1f; out[2] = v }      // +Y
            3 -> { out[0] = u; out[1] = -1f; out[2] = -v }    // -Y
            4 -> { out[0] = u; out[1] = -v; out[2] = 1f }     // +Z
            else -> { out[0] = -u; out[1] = -v; out[2] = -1f } // -Z
        }
    }

    /** Hermite fade between two edges — the standard smoothstep, feathering the sources. */
    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun length(v: FloatArray): Float =
        kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
