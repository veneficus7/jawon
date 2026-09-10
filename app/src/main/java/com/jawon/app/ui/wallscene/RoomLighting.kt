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
    /**
     * How far above the top of a row's cases the fixture hangs — **and this was the geometric
     * error underneath every other one on this fixture.**
     *
     * At 12mm the strip sat just above its own row's records, which means it threw down
     * *behind them*, onto panel the records themselves hide. Nothing lit the band of wall that
     * is actually visible, and every attempt to fix the gradient by re-aiming or re-levelling
     * the light was tuning something the camera could not see. Measured on a masked-out wall,
     * the visible band ran 63.6 at its top to 87.9 at its bottom — brightening *downward*,
     * away from the fixture, because it was being lit by spill rather than by the light meant
     * for it.
     *
     * A row occupies `CASE_H` of a `ROW_PITCH`, so the compartment above the records is
     * `ROW_PITCH - CASE_H - BOARD_H` = 52mm tall, and that band is the only back panel the
     * camera ever sees. The owner's target photograph puts the strip at the top of that
     * compartment, tucked under the shelf above — which lights the band directly, hottest at
     * the top and dying downward, and grazes the tops of the records on the way past.
     *
     * 48mm leaves the tube a few millimetres clear of the board above rather than intersecting
     * it.
     */
    const val SHELF_LIGHT_LIFT = 0.048f

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
     * Where the light that falls on the **records** hangs, front to back.
     *
     * This went to the back corner with the rest of the fixture, and had to come forward
     * again — the round trip is worth recording, because both positions are right for
     * different jobs and the mistake was thinking one light could do both.
     *
     * A record turned to face the camera has its normal at roughly +Z. A light mounted behind
     * it and throwing forward has `N·L` **negative** against that face: it lights the back of
     * the sleeve, the side against the wall. Proved rather than reasoned, after the fact —
     * raising this light's intensity from 320 to 1100 candela moved the cover art from 48.2 to
     * 47.7, which is nothing, and would have been baffling without the geometry to explain it.
     *
     * So the fixture is two lights now. [WASH_DIRECTION] stays at the back, close to the panel,
     * doing the gradient the target photograph is built on. This one hangs at the front lip of
     * the compartment at the same height and throws back and down across the artwork, which is
     * the only place a record's face can be lit from.
     */
    const val SHELF_LIGHT_Z = 0.140f

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
        // Back and down, from the front lip toward the wall — so a sleeve facing the camera
        // is lit from above-front, which is the only geometry that puts light on its face.
        -(SHELF_LIGHT_Z - WallDimens.CASE_D / 2f)
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
    const val SHELF_CANDELA = 1100f

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
     * Back and down, into the panel — and *mostly back*, which is the third correction to this
     * number and the one that finally aims it at the right place.
     *
     * The axis has to land near the **top** of the panel, because that is where the target
     * photograph is brightest. Where it lands is pure geometry: from [WASH_Z], 88mm out from
     * the panel, an axis of (0, -0.55, -1) meets it 49mm below the fixture — a third of the
     * way down a 142mm bay, which measured as a nearly flat panel (63.8 at the top, 74.0 at
     * the bottom) rather than the gradient in the photograph. Flattening the throw to -0.25
     * brings that down to about 22mm, which is inside the band of panel that is actually
     * visible above the records.
     */
    val WASH_DIRECTION: FloatArray = floatArrayOf(0f, -0.10f, -1f)

    /**
     * How far forward of the panel the wash hangs, and **the whole reason this is a separate
     * number from [SHELF_LIGHT_Z].**
     *
     * The owner's target photograph has the panel hottest at the very top of each bay, dying
     * downward toward the shelf below. A light in the exact back corner produces the *inverse*
     * of that, and it is the N·L trap for the third time in this file: a source 18mm from a
     * wall and aimed down grazes it, so `N·L` is near zero at the corner and only opens up
     * further down. Measured on a wall temporarily painted magenta so it could be masked out
     * of the frame and read on its own: 43.7 at the top of the bay, rising to 92 in the
     * middle. Exactly upside down.
     *
     * Moved forward into the bay, the geometry inverts with it. The top of the panel is now
     * nearly face-on to the light and close to it; the bottom is further away and oblique.
     * That is roughly a 4.5:1 falloff top to bottom before any cone or distance term, which is
     * the gradient in the photograph.
     */
    const val WASH_Z = 0.020f

    /**
     * **This is the light in the target photograph, not a spill off something else.**
     *
     * It was 95 — a supporting value, on the reading that the strip was the source and this
     * was overflow. The bookcase photograph inverts that: the panel is the brightest thing in
     * the bay, the records stand against it, and the fixture itself is invisible. So this
     * carries the bay now, and [SHELF_CANDELA] is what keeps the artwork off a silhouette.
     *
     * Much lower than the 520 it briefly was, and that is [WASH_Z]'s doing rather than a
     * change of mind: from the back corner the light was grazing the panel and most of it went
     * nowhere, so the number had to be enormous to show at all. Throwing across the bay puts
     * the same light on the surface at a useful angle and a fraction of the distance.
     */
    const val WASH_CANDELA = 800f

    /**
     * Narrow, and narrowed twice.
     *
     * A near-hemispherical cone spread the light evenly over the whole panel — measured at 90,
     * 90, 91, 90, 90 down the visible band, which is a lightbox rather than a gradient. The
     * compartment is only 52mm tall and the panel is 88mm away, so distance falloff alone
     * cannot grade anything over that span: the *cone edge* has to do it. Tight, aimed just
     * under the board above, so the band is hot where the photograph is hot and the light has
     * run out by the tops of the records.
     */
    const val WASH_CONE_INNER = 0.20f
    const val WASH_CONE_OUTER = 0.55f

    /**
     * Long enough to reach down the panel, short enough that the bottom of a bay is visibly
     * darker than the top. The gradient IS the picture — an evenly lit panel would be a
     * lightbox, which is the failure the visible bar already was.
     */
    const val WASH_FALLOFF = 0.22f

    // ---- The underlight: one real light per album ----------------------------------
    //
    // **The owner's lighting design, stated plainly: two lights and nothing else.** A backlight
    // in the top back corner of each shelf giving a gradient on the panel, and this — the
    // underlight of each CD, which is "the real light source that lets us see the artwork, and
    // shines through glass like material of CD cases". No ambient.
    //
    // The underlight has always been emissive geometry: one draw for the whole wall, which is
    // why every bay could have its own glow. It blooms and it lights nothing. Now it is an
    // actual Filament light per occupied slot — around a hundred of them at this camera, which
    // is the case clustered forward rendering exists for, provided each one is small and casts
    // no shadow. Both hold here.

    /**
     * How far above the board the bulb sits.
     *
     * Higher than it looks like it should be, and inverse-square is why. At 8mm the board
     * directly beneath the bulb is 8mm away while the middle of the sleeve is about 78mm away,
     * so the board receives roughly **95 times** the illuminance the artwork does — measured,
     * the pool clipped over a third of its pixels while the cover sat at 68. Both cannot be
     * exposed correctly at once from that height, and the artwork is the one that matters.
     *
     * Lifting it cuts the near distance far faster than the far one, so the ratio collapses
     * while the light on the sleeve barely moves. Still low enough to read as coming from under
     * the record rather than from the shelf above.
     */
    const val UNDER_LIFT = 0.024f

    /**
     * How far in FRONT of the case the bulb sits.
     *
     * A record's face is vertical, so a light directly beneath it contributes exactly nothing:
     * `N·L` is zero. This is the same geometry that has now cost time three separate times in
     * this file, and it is why the bulb cannot simply stand where the emissive cone stands. Far
     * enough forward to throw up across the artwork, close enough to still read as coming from
     * under that record rather than from the room.
     */
    const val UNDER_FORWARD = 0.052f

    /**
     * Bright, because this is a key light now rather than a decoration.
     *
     * It is the only thing lighting an album's cover, so what used to be a coloured accent has
     * to carry the artwork on its own.
     */
    const val UNDER_CANDELA = 340f

    /**
     * Short reach. The pool belongs to one record; a long falloff turns a hundred of these into
     * ambient light by another name, which is exactly what the owner asked to remove.
     */
    const val UNDER_FALLOFF = 0.20f

    /**
     * How much of the album's own hue the bulb takes, 0 = warm white, 1 = the full hue.
     *
     * Held well down on purpose, and this is the tension in the design. `DESIGN.md` wants an
     * album's colour in its underlight, and when the underlight was decoration that was free.
     * Now the same light is the only thing illuminating the sleeve, so a fully tinted bulb would
     * light every cover in a wash of its own dominant colour — a blue record lit blue, which
     * destroys the artwork it exists to show. A third of the way keeps a visibly coloured pool
     * on the board, where nothing else competes, and leaves the cover close to neutral.
     */
    const val UNDER_TINT = 0.34f

    /**
     * The same underlight, for the record being held.
     *
     * Brighter and longer-reaching than the shelf's, and both for the same reason: the disc
     * lifts clear of the case to close to full frame height, so what the shelf's light only
     * has to carry up the face of a sleeve this one has to carry a good deal further. It is
     * still the album's underlight, not a third kind of light.
     */
    const val HELD_UNDER_CANDELA = 520f
    const val HELD_UNDER_FALLOFF = 0.60f

    /** How far below the disc's rim its underlight sits. */
    const val DISC_UNDER_DROP = 0.030f

    /** And how far in front of the platter, so the label is lit rather than the edge. */
    const val DISC_UNDER_FORWARD = 0.055f

    /** The lamp the tint is mixed away toward: ordinary warm tungsten. */
    val UNDER_WHITE: FloatArray = floatArrayOf(1.0f, 0.86f, 0.72f)


    /**
     * How many wash lights each row gets, spread across the width of the frame.
     *
     * **One is a pool, not a band.** The shelf lights are positioned per *row* and ride the
     * browse carrier, so each row had exactly one wash sitting at whatever the user is looking
     * at — and once its cone was narrowed enough to grade the panel vertically, what it left
     * was a soft circular puddle in the middle of the shelf with darkness either side. The
     * target photograph has an evenly lit panel across the full width of every compartment,
     * because a real compartment is a closed box with a strip running its whole width.
     *
     * Overlapping cones are how a strip is faked out of point sources. Odd, so one of them
     * sits on the browse centre.
     *
     * **Five was not enough and the reason is worth keeping.** Filament's spots are round, so
     * there is no way to make one cone wide across the shelf and tight down the panel — and the
     * cone has to stay tight, because over a 52mm compartment at 88mm distance the cone edge is
     * the only thing that grades the gradient at all. Widening it to close the gaps flattens the
     * gradient, which is the whole picture. So the gaps close by adding sources instead: nine at
     * a little over half the old spacing, cores overlapping, which is also how a real strip
     * light is built.
     */
    const val WASH_COLUMNS = 9

    /**
     * How far apart those are. Close enough that the cones overlap into a continuous band at
     * panel distance rather than reading as five separate lamps.
     */
    const val WASH_COLUMN_SPACING = 0.058f

    const val SHELF_LIGHT_COUNT = 3

    // ---- There is no visible fixture, and that is the design ------------------------
    //
    // This is where an emissive bar and its housing used to be. They were added on the earlier
    // record-shop references, where the strip is in the frame and you can see it — a fair
    // reading of those photographs, and the constants and their reasoning are in git.
    //
    // The owner's bookcase photograph supersedes them: it has **no visible source at all**,
    // only a panel glowing warm behind the objects. The bar made the argument for itself on
    // the way out. Bright, it was a white-clipped tube hanging in black; warmed and dimmed
    // under bloom's threshold so it would stop glaring, it stopped being a light and became a
    // flat orange line painted across the wall. A fixture that reads as neither a lamp nor a
    // fitting is worse than no fixture, and the board above already casts the shadow line that
    // says where the light comes from.


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
    const val AMBIENT_INTENSITY = 6_500f

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
    /**
     * Irradiance as spherical harmonics — **all zero, on purpose.**
     *
     * The owner's lighting design is two lights and no ambient: a backlight per shelf and one
     * real underlight per album. This is where "no ambient" is actually enforced, and it is
     * worth being precise about what it removes, because `IndirectLight` does two separate
     * jobs through one object.
     *
     * The *irradiance* half is the ambient — a diffuse fill arriving from every direction at
     * once, which lands on every surface regardless of where it is or which way it faces. That
     * is what made the room flat: a fill with no position has no falloff, so nothing can be
     * further from the light than anything else. It was measured at six-sevenths of the wall's
     * brightness once. Zeroing these coefficients removes it completely.
     *
     * The *reflections* half is kept. `IndirectLight.reflections` is what a glossy surface has
     * to look at — without it a jewel case's shell mirrors nothing and reads as painted card,
     * which is most of "it looks like cheap plastic". That is not ambient light in the sense
     * being removed here: it adds no diffuse fill, and after [buildRoomCubemap] the only thing
     * in that environment is the backlight, so what the cases reflect is a light that genuinely
     * exists in the room.
     */
    private fun ambientSphericalHarmonics(scheme: RoomScheme): FloatArray =
        FloatArray(SH_BANDS * SH_BANDS * 3)

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

        // Hoisted: these are three multiplications, not 393k of them.
        val void = FloatArray(3) { scheme.void[it] * VOID_LEVEL }
        // Reused across every texel. `faceDirection` used to return a fresh array per
        // texel, which was survivable at 64px and is 393k throwaway allocations at 256.
        val dir = FloatArray(3)

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
                        // Void plus the backlight, and nothing else. There used to be two
                        // hot discs in here — a lamp and a fill — reflecting a key light and
                        // a rim that the room no longer has. A reflection of a light that
                        // does not exist is a lie the eye reads immediately on a glossy
                        // surface, and it was reflecting the room as it was two designs ago.
                        floats.put(void[c] + scheme.key[c] * STRIP_BAND_LEVEL * bandHit)
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
