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
     * Where the LED ribbon sits, front to back: **at the front-bottom edge of the tube.**
     *
     * The tube is the fixture and the ribbon is what is inside it, so the light has to leave
     * from the tube — anywhere else and the gradient on the panel belongs to some other light.
     * The tube now lies hard against the back panel (see [STRIP_Z]), 16mm deep, and its
     * diffuser faces down; the front edge of that diffuser is where a real strip's light
     * appears to come from, 15mm off the panel.
     *
     * This used to be its own number, and the tube, the bracket and the ribbon each had their
     * own idea of where the corner was. The tube ended up 15mm in front of the plate it was
     * meant to be screwed to, and the owner's read — that the light on the panel "is not coming
     * from the tube" — was correct. Stated in terms of [WallDimens.WALL_Z] now, the same way the
     * tube is, so the two cannot drift apart.
     */
    const val CORNER_Z = WallDimens.WALL_Z + 0.015f

    /**
     * How far above the top of a row's records the fixture hangs: **flush against the
     * underside of the board above.**
     *
     * A row occupies `CASE_H` of a `ROW_PITCH`, so the compartment over the records is
     * `ROW_PITCH - CASE_H - BOARD_H` = 52mm tall, and the top of that compartment is the
     * underside of the next board up. The tube's centre sits half a tube below it, so its top
     * face touches the board: attached to the shelf above and, through [STRIP_Z], to the panel
     * behind — both surfaces of the corner at once, which is what "in the corner" means.
     *
     * History worth keeping. At 12mm the fixture sat just above its own row's records and
     * threw its light down *behind* them, onto panel the camera cannot see. At 48mm it hung 3mm
     * under the board with a dark bracket bridging the gap and — the part that mattered — that
     * bracket also covered 8.7mm of panel *below* the tube, so the gradient began after a dark
     * band. That band is what made the light read as coming from somewhere else.
     *
     * Every row has a board above it now, including the top one: see the rack's cap in
     * `WallSceneScreen`.
     */
    const val SHELF_LIGHT_LIFT =
        WallDimens.ROW_PITCH - WallDimens.CASE_H - WallDimens.BOARD_H - 0.0011f

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
     * So the fixture is two lights now. The ribbon stays in the corner, close to the panel,
     * doing the gradient. This one hangs at the front lip of the compartment at the same height
     * and throws back and down across the artwork, which is the only place a record's face can
     * be lit from.
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

    // ---- The ribbon emits DOWNWARD, into a hemisphere ------------------------------
    //
    // It was briefly a POINT light, on the reasoning that an LED ribbon emits into the
    // hemisphere in front of it and the gradient should come from distance rather than from a
    // cone edge. The second half of that is right and the first half is exactly backwards: a
    // point light radiates into a *sphere*, so every diode was lighting the underside of the
    // shelf board directly above it just as hard as the panel below. Twenty-one of them, 11mm
    // under a board, put twenty-one small bright pools on it — a visible row of dots along the
    // fixture, which is precisely what a strip light must not look like.
    //
    // A wide downward cone fixes it geometrically rather than by tuning. The board above sits
    // at 90° from straight down, so an outer angle short of that excludes it completely, while
    // the panel below is reached from about 3mm under the fixture downward. This is also what
    // the real thing does: a ribbon is a strip of diodes on an opaque backing, and it emits
    // down.
    //
    // The cone is radially symmetric about the down axis, so it does nothing to the spread
    // along the shelf — horizontal continuity is still overlap between neighbours, and the
    // cone edge lands far enough away to leave no scallop.

    /** Straight down, parallel to the panel it grazes. */
    val WASH_DIRECTION: FloatArray = floatArrayOf(0f, -1f, 0f)

    /**
     * How wide the ribbon throws, as angles from straight down.
     *
     * The outer angle is the load-bearing one: 1.50 rad is 86°, short of the 90° where the shelf
     * board above begins.
     *
     * **The inner angle has to be nearly as wide, or the brightest strip of wall is the wrong
     * one.** At 60° inner, the panel within ~9mm under the diodes sat in the cone's soft edge —
     * a point 5mm down and 15mm back is 72° off straight down — so the profile measured 79, 72
     * right under the tube and only peaked at 152 further down. A gradient that starts with a
     * dip reads as light arriving from somewhere below the fixture. At 74° inner the panel
     * touching the tube is at full strength, which is where a ribbon's light is brightest.
     */
    const val WASH_CONE_INNER = 1.30f
    const val WASH_CONE_OUTER = 1.50f


    /**
     * Where the ribbon sits, front to back: **in the corner, with the tube.**
     *
     * This was moved forward into the bay once and has come back, and the round trip is the
     * useful part.
     *
     * The argument for moving it out was that a light in the exact back corner grazes the panel
     * beside it — `N·L` goes to zero as a light approaches the plane of a surface — so the
     * corner would be the *darkest* part of it. That is true of a light *in* the plane and it is
     * not true of one 18.5mm out: the panel 10mm below such a light sees it at `N·L` = 0.88, at
     * 30mm below 0.53, at 50mm below 0.35, with the inverse-square term falling away underneath
     * all of that. The result is a very hard gradient, hottest immediately under the fixture and
     * dying downward — which is what an LED ribbon tucked into a bookcase corner actually does.
     *
     * What the forward position bought was a *soft* gradient, because from 88mm out the whole
     * panel is roughly equidistant. Smooth, easy to make continuous, and wrong: it reads as a
     * room lit from somewhere rather than a strip stuck to a shelf.
     */
    const val WASH_Z = CORNER_Z

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
    const val WASH_CANDELA = 64f

    /**
     * Narrow, narrowed twice, and then widened again once the tube came back.
     *
     * The tightness was doing two jobs: grading the panel vertically, and standing in for a
     * fixture that was not being drawn. Only the first is its job. With a visible tube above it
     * the eye takes the gradient's shape from the tube and its bloom, so these can open up far
     * enough to stop scalloping — which is what made nine separate lamps readable as nine
     * separate lamps instead of one strip.
     *
     * The original note, still true of why it was ever narrow:
     *
     * A near-hemispherical cone spread the light evenly over the whole panel — measured at 90,
     * 90, 91, 90, 90 down the visible band, which is a lightbox rather than a gradient. The
     * compartment is only 52mm tall and the panel is 88mm away, so distance falloff alone
     * cannot grade anything over that span: the *cone edge* has to do it. Tight, aimed just
     * under the board above, so the band is hot where the photograph is hot and the light has
     * run out by the tops of the records.
     */

    /**
     * How far each diode reaches, and **why it has to be longer than the compartment is tall.**
     *
     * Filament's `falloff` is not a soft decay — it is the radius at which the light is cut to
     * exactly zero. At 0.12m the ribbon measured a clean gradient from the tube down, and then a
     * dead-flat 5.5 for the whole bottom 40% of the panel: the panel runs about 19cm from the
     * tube to the board below, and everything past 12cm was outside every diode's reach at
     * once. That flat floor is what made the lower half of each compartment read as a separate,
     * unlit wall.
     *
     * Longer than the compartment, so the shape of the gradient is left to inverse-square and
     * incidence — which already make it steep near the tube and long at the bottom — and never
     * to a cutoff.
     */
    const val WASH_FALLOFF = 0.26f

    /**
     * How far below the tube's centre the diodes sit: just clear of its bottom face, which is
     * the diffuser the light leaves through. Half the tube's 2.2mm plus a millimetre.
     */
    const val WASH_DROP = 0.0021f

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
    const val UNDER_CANDELA = 780f

    /**
     * Short reach. The pool belongs to one record; a long falloff turns a hundred of these into
     * ambient light by another name, which is exactly what the owner asked to remove.
     */
    const val UNDER_FALLOFF = 0.20f

    /**
     * The light channel the album underlights live on — and the end of a day spent fighting
     * this light with cone angles.
     *
     * The underlight has one job, lighting the artwork, and kept doing three others. As a point
     * light it washed the back panel from 120mm in front of it, so the gradient the tube is
     * supposed to own came mostly from a hundred records. Narrowed to an upward spot it stopped
     * doing that and kept putting its own reflection — a bright blob — into the middle of every
     * case's glossy shell, over the art. Each fix by geometry traded one surface for another,
     * because geometry cannot say *which surfaces* a light is allowed to touch.
     *
     * Light channels can. A Filament light only reaches renderables that share a channel with
     * it, so the underlight lives on this channel alone and only the things it exists for opt
     * in: the three printed plates of every case, the held case's plates, and the disc label.
     * The back panel, the shelf boards and the case body never do — so the panel's gradient is
     * the tube's and nothing else's, and the shell reflects the room rather than a bulb 50mm
     * away. `RenderableManager.setLightChannel` and `LightManager.Builder.lightChannel` both
     * forward to native in this Filament (checked in the bytecode, per trap 2's lesson).
     *
     * So it is a POINT light again: within its channel there is nothing it can reach that it
     * should not, and a point lights a record whichever way it has turned.
     */
    const val UNDER_LIGHT_CHANNEL = 1

    /**
     * How much of the album's own hue the bulb takes, 0 = warm white, 1 = the full hue.
     *
     * **Brought down twice, the second time because the covers still were not vivid.** Every
     * step away from white here is a colour cast on album art, and album art is the one thing on
     * the wall whose colours are somebody else's work. What is left is a hint on the shelf board,
     * where nothing else competes.
     *
     * The original reasoning: With this as the only light on an
     * album, every tint it carries is a tint on the artwork, and a third of the way toward a
     * warm bulb was enough to pull the colour out of every sleeve on the wall at once. What
     * looks like a modest accent on a shelf board is a colour cast on a photograph.
     *
     * The rest of the original reasoning, which still holds: `DESIGN.md` wants an
     * album's colour in its underlight, and when the underlight was decoration that was free.
     * Now the same light is the only thing illuminating the sleeve, so a fully tinted bulb would
     * light every cover in a wash of its own dominant colour — a blue record lit blue, which
     * destroys the artwork it exists to show. A third of the way keeps a visibly coloured pool
     * on the board, where nothing else competes, and leaves the cover close to neutral.
     */
    const val UNDER_TINT = 0.11f

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

    /**
     * The lamp the tint is mixed away toward.
     *
     * Was full warm tungsten (1.0, 0.86, 0.72), which is the right colour for a shop and the
     * wrong colour for the only light falling on a photograph. Between that and the tint above,
     * every cover on the wall was rendering through a heavy amber filter and reading grey.
     * Barely warm now: enough that the room is not lit by a fluorescent tube, little enough
     * that album art keeps its own colour. The room's warmth comes from the ribbon, which lights
     * the wall rather than the artwork.
     */
    val UNDER_WHITE: FloatArray = floatArrayOf(1.0f, 0.98f, 0.95f)


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
     * **Nine was not enough either, and moving into the corner is why.**
     *
     * A light 88mm out from the panel spreads a wide pool, so nine of them overlapped into
     * something nearly continuous. A light 18.5mm out does not: the panel 20mm below it is 27mm
     * away, while the panel 60mm to the side is 66mm away *and* seen at a third the incidence —
     * about fourteen times darker. Close to a surface a point source has a small footprint, and
     * that is the price of the hard gradient the corner buys.
     *
     * So the spacing follows the distance to the wall rather than the width of the shelf:
     * twenty-one at 24mm, which is roughly the pitch of the diodes on a real ribbon, for exactly
     * the same reason a real ribbon has them that close together.
     *
     * The earlier note, on why five was not enough: Filament's spots are round, so
     * there is no way to make one cone wide across the shelf and tight down the panel — and the
     * cone has to stay tight, because over a 52mm compartment at 88mm distance the cone edge is
     * the only thing that grades the gradient at all. Widening it to close the gaps flattens the
     * gradient, which is the whole picture. So the gaps close by adding sources instead: nine at
     * a little over half the old spacing, cores overlapping, which is also how a real strip
     * light is built.
     */
    const val WASH_COLUMNS = 27

    /**
     * How far apart those are. Close enough that the cones overlap into a continuous band at
     * panel distance rather than reading as five separate lamps.
     */
    const val WASH_COLUMN_SPACING = 0.019f

    /**
     * Which diode in each ribbon casts shadows — the middle one, and only that one.
     *
     * Removing the key light removed the room's only shadow caster, and shadows are most of
     * what makes objects sit in a place rather than float in front of a picture of one. They
     * have to come back from a light that actually exists, and the ribbon is the one with a
     * useful angle on the records: it is above and behind them, so what it throws runs down the
     * panel behind each sleeve.
     *
     * One of twenty-seven, because a shadow map is per casting light and twenty-seven of them
     * per row is not a budget, it is a joke. The other twenty-six fill the shadow in, which is
     * exactly what happens under a real ribbon: a soft, shallow shadow rather than a hard one.
     * `RoomAtmosphere`'s soft-shadow settings — PCSS and the penumbra scales — have been sitting
     * unused since the key light went and apply to this.
     */
    const val WASH_SHADOW_COLUMN = WASH_COLUMNS / 2

    /**
     * How many rows of the rack carry a live ribbon at once — four, and never positioned by the
     * camera.
     *
     * **The ribbon used to ride the camera, and that was the bug under most of this fixture's
     * complaints.** Every ribbon light hung from one carrier the frame driver moved to
     * `(panX, panY)` every frame, while the tube it belongs to was drawn at a fixed place on
     * each shelf. At rest the two lined up. During any swipe the whole lit pattern slid across
     * the fixed timber with the camera — the owner's "the back wall is moving separately from
     * the shelf" — and on a vertical swipe the gradient visibly detached from the tube and
     * drifted, which is "the light is not coming from the tube".
     *
     * Now the carrier is **snapped to the rack**: horizontally to the diode pitch, so every diode
     * sits at a fixed place on the shelf and a pan only swaps which diodes exist at the far
     * edges, well out of frame; vertically to the midpoint between the two rows either side of
     * the view, so the four ribbon rows land exactly on four real tubes. Four rather than three
     * because the frame is about 2.8 rows tall: with three, the moment the nearest row changed
     * mid-swipe a partly visible compartment at the edge of the frame lost its light. With four,
     * the row that changes is always outside the frame.
     *
     * A row of the rack that does not exist — above the top shelf, below the bottom one — has
     * its ribbon switched off rather than left lighting empty space.
     */
    const val SHELF_LIGHT_COUNT = 4

    // ---- The tube you can see ------------------------------------------------------
    //
    // **Three goes at this, and the third is a correction of my own over-correction.**
    //
    // It started as an emissive bar on the record-shop references, where every fixture is in
    // frame. It clipped to pure white and hung in the black like a neon sign — measured, 100%
    // of its pixels blown, with no channel separation left on the ACES shoulder. Warming it
    // fixed that. Then the bookcase photograph arrived with no visible source anywhere, and I
    // read that as "conceal the fixture", dimmed the bar under bloom's threshold, watched it
    // turn into a flat orange line painted on the wall, and removed it.
    //
    // That was wrong, and the owner's word for it was that the room had lost its mood. A
    // *backlight* you cannot see is not a neon strip; it is an unexplained glow. The bookcase
    // hides its LED because a bookcase has a deep lip to hide it behind — this shelf does not,
    // and the thing being asked for was never concealment. It is one long tube, continuous
    // across the whole shelf, mounted in the top back corner, visibly attached to the board
    // above it.
    //
    // So: bright enough to bloom, warm enough not to clip to white, and with a housing, which
    // is the part that was missing every previous time. A bar on its own floats. A bar in a
    // channel that meets the board above it is a fitting.

    /**
     * How thick the tube is.
     *
     * Halved from 4.2mm at the owner's call. A strip light is a *line* — the thing that reads
     * as neon is a hard thin edge with a bloom around it, not a bar with area. Thinner also
     * means the emissive can go brighter for the same amount of glare, which is why these two
     * moved together.
     */
    const val STRIP_H = 0.0022f

    /** How deep, front to back. */
    const val STRIP_D = 0.016f


    /**
     * How bright the tube is, and **what colour it survives as**.
     *
     * Well over bloom's 1.0 threshold so it glows hard, with the channels held far apart so the
     * tonemapper's shoulder cannot compress it back to white — the ratio is what keeps a bright
     * tube amber instead of a white bar, and it is the first thing lost when the level is raised.
     * Raised with [STRIP_H] halved: a thinner source can carry more emission for the same glare,
     * because glare is area times brightness. The first version of this was
     * (2.1, 1.65, 1.15) and rendered a pure white bar: three channels comfortably over 1.0 all
     * arrive at the same place on an ACES curve, and a warm tungsten strip — the single most
     * recognisable thing in every reference — was being thrown away in the last step of the
     * pipeline.
     */
    val STRIP_EMISSIVE: FloatArray = floatArrayOf(3.10f, 1.62f, 0.62f)


    /**
     * Where the tube sits, front to back: **lying against the back panel**, half a millimetre
     * off it.
     *
     * There was a bracket plate between the two, and it did more harm than anything else in the
     * fixture: unlit and dark, it covered the panel from 4.3mm above the tube to 8.7mm below it,
     * so the brightest part of the gradient — the panel immediately under the diodes, where a
     * ribbon's light is strongest — was hidden behind it, and what showed was a dark band and
     * then a glow. Removed. The tube touches the panel behind it and the board above it
     * ([SHELF_LIGHT_LIFT]), and the panel is lit right up to its lower edge.
     */
    const val STRIP_Z = WallDimens.WALL_Z + STRIP_D / 2f + 0.0005f



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
     * How much ambient the room gets, as a fraction of the scheme's own ambient colour.
     *
     * **Zero for a while, and a little is better than none.** The owner asked for the ambient
     * out, and taking it out was right: an irradiance term arrives from every direction at once,
     * lands on every surface regardless of position or facing, and therefore cannot fall off.
     * It was measured at six-sevenths of the wall's brightness once, and it is what made the
     * room flat.
     *
     * What removing it entirely also does is put every surface the two lights do not reach at
     * literally zero, which is not what a dark room looks like — a dark room has shadow detail,
     * and film that clips its blacks reads as cheap rather than as moody. A sixth of what was
     * there lifts the floor off absolute black without competing with either light.
     */
    private const val AMBIENT_FILL = 0.17f

    /**
     * Irradiance as spherical harmonics: 3 bands, 9 coefficients, RGB each.
     *
     * Hand-authored rather than convolved from an environment — a single dim fill has no detail
     * worth baking. Index 0 is the constant term, index 1 the vertical one, biased positive so
     * what little fill there is arrives from above. The rest stay zero.
     *
     * The *reflections* half of `IndirectLight` is a separate thing from this and is not scaled
     * by [AMBIENT_FILL]: it adds no diffuse fill, it is what a glossy surface looks at, and
     * without it a jewel case mirrors nothing and reads as painted card.
     */
    private fun ambientSphericalHarmonics(scheme: RoomScheme): FloatArray {
        val sh = FloatArray(SH_BANDS * SH_BANDS * 3)
        for (c in 0..2) {
            sh[c] = scheme.ambient[c] * AMBIENT_FILL
            sh[3 + c] = scheme.ambient[c] * AMBIENT_FILL * 0.35f
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
