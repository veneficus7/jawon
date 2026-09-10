# The Room — a design and graphics project

Research, not a plan of record. Nothing here is decided; it exists so the
decisions can be made against evidence rather than against my guesses, which
have now missed three times in a row on this exact subject.

Written 2026-09-10, against Filament **1.72.1** and SceneView **4.32.0**. Every
claim about what the engine can and cannot do was checked against the shipped
bytecode or the shipped `.filamat`, not against the documentation — because the
documentation describes *Filament*, and what Android gets is Filament seen
through a JNI binding that quietly drops things.

---

## 0. Why this is a separate project

The room is the product. Everything else in Jawon — the importer, the server,
the rack, the CD reader — is plumbing that a dozen other players also have. The
wall of cases under one lamp is the only part nobody else has, and the owner's
verdict on 2026-09-09 was that it *"looks so boring, it doesn't have
environment, atmosphere or special lightning, shades, atmospheric elements."*

That verdict is correct and it is not a bug report. It is an art-direction
problem wearing an engineering costume, and the three attempts made at it so far
failed in the same way: each changed a number, shipped, and asked whether it
looked better. Grading, a floor and a wall texture went in; the room came back
black, then grey, then acceptable-but-unchanged. **The lesson is that this
cannot be done one constant at a time against a device**, because the feedback
loop is a five-minute build and a subjective answer, and that loop cannot
converge.

So: a project, with a reference to aim at, a way to compare, and a staged order.

---

## 0.5 The reference, and what it actually shows

The owner supplied seven reference images on 2026-09-10. They are the target, and
they are described here rather than linked because a target that lives in a chat
log is not a target.

**What they are.** Two dark record-shop interiors where each shelf row is lit by
its own warm strip (one shot from the customer side, one from behind a DJ deck);
a near-black shelf with a single warm strip inside it grazing down a row of
sleeves; an offline 3D render of a turntable under a pendant lamp with a visible
light cone and dust; a cluttered loft lit only by a skylight; a shop with warm
track spotlights and records hung on the wall; a CD player's orange segment
display glowing out of focus in total blackness; and a CD rack under a strip
light with a lit orange genre sign.

**What every one of them has in common, and Jawon does not:**

1. **The light source is in the frame, and it is small and local.** A strip
   inside the shelf. A pendant with a visible cone. A track spot. A device's own
   display. Not one abstract key light somewhere off-camera — *practicals*, and
   you can see them.
2. **The records are lit; the room is not.** Eighty to ninety per cent of every
   frame is near-black. The lit area is small and precious, and it is always the
   sleeves.
3. **Light falls off fast and visibly.** Every image has a gradient running out
   of the lit zone into nothing. Nothing is uniformly lit.
4. **Colour comes from the artwork**, not from the lighting. The light is warm
   tungsten; the saturation on screen is the album covers.

### The reading that reverses a previous decision

The owner's original complaint was *"even the wall is empty brown, nothing
aesthetic"*, and the work that followed treated that as *the wall needs to be
more interesting* — a mottle texture, a grade, a floor.

**The reference says the opposite.** In all seven images the wall is not
interesting; the wall is *absent*. What reads is lit sleeve faces, a visible
source, and blackness. The problem with "empty brown" was never that the brown
was empty — it was that the wall was **visible at all**, filling the frame at
mid-tone, with the records no brighter than the surface behind them.

That reframes the whole project. The wall texture work already shipped is not
wrong, but it is far down the list; it is detail on a surface that should be
falling away into the dark.

### The lighting design the reference implies

This is a *lighting* change before it is a graphics one, and most of it needs no
new engine feature at all:

- **A practical strip per shelf row**, mounted under the board above, throwing
  down across the faces of the records — visible in frame as a bright line. This
  is the single most characteristic thing in the reference and Jawon has nothing
  like it. Filament's `LightManager` does spot and point lights with real
  falloff; a row of them along each board is ordinary work.
- **Jawon's existing underlights point the wrong way.** They glow *up* from
  beneath each case, which is a games convention, not a shop. The reference lights
  from *above*, grazing down. Same feature, inverted, and the inverted version is
  the one that looks like a place.
- **The wall recedes.** Darker, and lit only by spill. Whatever the wall's
  material ends up being, it should be at the bottom of the frame's tonal range.
- **The lamp becomes visible.** Every reference shows its own source. A fixture
  in frame with a cone under it is worth more than any post-processing.
- **Emissive readouts, pushed hard.** The CD-display image is the strongest
  argument in the set for what this app already half has — the deck's CRT and
  PIXEL glass. Bright emissive against true black with heavy bloom is exactly the
  look, and it costs nothing.

---

## 1. The engine question, answered

The owner asked whether Filament is the limitation and whether something more
advanced is needed. **It is not, and the evidence is fairly one-sided.**

**Godot 4's mobile renderer offers exactly one form of indirect lighting:
`LightmapGI` — baked lightmaps.** SDFGI and VoxelGI are Forward+ only.
Volumetric fog is Forward+ only; mobile gets distance fog. Godot also uses
Filament's own horizon specular occlusion model. So switching to Godot would buy
the same technique this document already recommends for Filament — bake it — with
a full rewrite attached.

**Unreal on mobile is disqualified by what this app is.** It runs hot and drains
battery; Google ships an ADPF plugin specifically to manage its thermal
throttling. Jawon is a *music player* — it is left running with the screen on for
hours. This project's own trap 8 already records that thermal state invalidates
frame measurements on this device. An engine whose known mobile characteristic is
heat is the wrong engine for an app people listen to records on.

**Unity would be a full rewrite**, of an app whose entire architecture is
"Compose owns 2D, SceneView owns the room, they meet at a ViewModel".

And the sharpest point: **one of the seven references is an offline render.** The
turntable-under-a-pendant image is path-traced — minutes per frame, not
milliseconds. No real-time engine on any phone produces that image live. What
makes it reachable is that **Jawon's room is completely static**, which is the
condition under which you bake the expensive light once and read it back for
free. That is §3.1, and it is the same answer on every mobile engine.

The six photographs are not a rendering problem at all. They are lighting design,
contrast, and materials — and every one of those is available here today.

**Verdict: stay on Filament.** The gap is not the renderer. Revisit only if a
specific, named effect turns out to be impossible — and the audit below is what
that judgement should be made against.

---

## 2. The engine audit, and one finding that invalidates existing code

### 2.1 The trap that governs everything below

**A Filament option that the JNI binding does not forward is silently ignored.**
The Java class has the field, the setter accepts it, the code compiles, the app
runs, and nothing happens. This project has already been caught by it once —
`RoomAtmosphere` documents that `AmbientOcclusionOptions.aoType` is not
forwarded — but did not generalise the lesson.

The check is to decompile the setter and read which fields reach the `native`
call:

```
javap -c -p -classpath <filament-android-1.72.1 classes> com.google.android.filament.View
```

I audited the four option structs the room uses. The results change what is
worth doing.

### 2.2 `BloomOptions.quality` is never forwarded — and a measurement in the repo is wrong

`setBloomOptions` reads eighteen fields and passes them to
`nSetBloomOptions(JJFFIIIZZFZZFIFFFFF)`. In bytecode order they are: `dirt`,
`dirtStrength`, `strength`, `resolution`, `levels`, `blendMode`, `threshold`,
`enabled`, `highlight`, `lensFlare`, `starburst`, `chromaticAberration`,
`ghostCount`, `ghostSpacing`, `ghostThreshold`, `haloThickness`, `haloRadius`,
`haloThreshold`.

**`quality` is not among them.** It is never read.

`RoomAtmosphere` currently sets `quality = QualityLevel.LOW` and carries a
comment justifying it with a measurement: MEDIUM 234 jiffies, off 186, LOW 172,
concluding *"the whole of the saving is in the quality level."* That conclusion
cannot be true, and the data says so plainly if you look at it again — **LOW
measured *cheaper than switching bloom off entirely***. That is not a
discovery, it is the signature of a measurement that measured nothing. The
three runs differ by noise and thermal drift.

Two consequences: the line should go or be marked inert, and the room has been
paying full-quality bloom all along, which means the *real* cost of bloom has
never been established. That is worth re-measuring properly, because bloom on a
1440×3120 panel is genuinely expensive and it is the effect the underlights
depend on.

### 2.3 What is forwarded and completely unused

Everything else in `BloomOptions` reaches native. The room uses none of it, and
it is running the bloom chain already:

| Field | What it gives |
|---|---|
| `dirt` + `dirtStrength` | A **lens dirt texture** modulating the bloom. The single most recognisable "shot through a real lens" cue there is, and it costs one texture sample on a pass already running. |
| `lensFlare`, `ghostCount`, `ghostSpacing`, `ghostThreshold` | Anamorphic ghosts down the axis from a bright source — the lamp. |
| `starburst` | The streak a real iris makes on a point highlight. |
| `haloThickness`, `haloRadius`, `haloThreshold` | A ring around bright sources. |
| `chromaticAberration` | Colour fringing on the bloom only, not the whole frame. |
| `highlight` | Where the bloom's knee sits. |

For a dark room whose entire subject is *one bright lamp and small glowing
things*, this is the highest ratio of look-to-work available anywhere in the
engine. It needs no new geometry, no material, no asset except one dirt texture.

**Restraint applies.** DESIGN.md §4 says bloom should be *"subtle, on the lamp
and the disc's specular highlight only. Not a global haze."* Ghosts and
starbursts are exactly the kind of thing that reads as a demo. The dirt texture
is the one I would take first and possibly the only one.

### 2.4 What is not available at all

- **GTAO.** `setAmbientOcclusionOptions` forwards `quality`, `lowPassFilter`,
  `upsampling`, `bentNormals` and the rest — but not `aoType` and not any
  `gtao*` field. Android gets SAO, full stop. The existing comment is correct.
- **Lightmaps.** Filament has no native lightmap support. The maintainer's
  stated reason is that a plain lightmap carries no directionality and they
  would rather do spherical harmonics or gaussians, plus it needs a baking tool
  ([discussion #4650](https://github.com/google/filament/discussions/4650)).
  **The maintainers' own suggested workaround is `postLightingColor` with
  `postLightingBlending: multiply`** — which is available to any hand-written
  material. See §4; this matters more here than anywhere else.
- **Light cookies / gobos / IES profiles.** `LightManager.Builder` has
  `position`, `direction`, `color`, `intensity`, `falloff`, `spotLightCone`,
  `sunAngularRadius/HaloSize/HaloFalloff`, `castShadows`, `shadowOptions`,
  `lightChannel` — and no texture of any kind. A shaped light pool cannot come
  from the light. It *can* come from geometry in front of the lamp casting a
  real shadow, which is free because the PCSS shadow map already exists (§2.2).
- **Local light probes.** Distant IBL only; the docs say local probes are too
  expensive for mobile.
- **Normal maps through SceneView's stock materials.** `opaque_textured` exposes
  exactly `metallic`, `roughness`, `reflectance` and one `texture` sampler —
  read out of the shipped `.filamat`'s `MAT_UIB` chunk. Everything else in
  Filament's material model needs a hand-written `.mat`.

### 2.5 Quietly available and unused

- **`lightChannel`** — eight channels. A light can be made to affect only some
  objects. This is how you light the *cases* differently from the *room*
  without physically moving anything, and it is the standard art-direction
  escape hatch when physical lighting and good-looking lighting disagree.
- **`View.setMaterialGlobal(int, float[])`** — four global float4s pushed to
  every material. A clean way to drive custom shaders from the frame loop
  (time, camera-relative values, scheme colour) without per-instance parameter
  writes.
- **`secondRoughness` / `secondRoughnessWeight`** in the lit model — a second
  specular lobe. This is what makes lacquered and worn surfaces read correctly,
  and a CD's label is exactly that.
- **`bentNormals`** in AO — already `true` at the fine tier, and it improves
  indirect lighting rather than just darkening creases. Worth knowing it is on.

---

## 3. Design ideas, in order of how much they change the image

Ordered by impact per unit of risk, and deliberately biased toward things that
work *with* the existing engine constraints rather than against them.

### 3.1 The room is 100% static — bake the light into it

Nothing in the room moves except the cases, the disc and the dust. The wall, the
shelf boards, the floor and the lamp are fixed relative to each other, forever.
That is the condition under which offline global illumination is free at
runtime, and it is the single largest visual gap between this room and a room
from a game.

Right now every surface is lit by one spot, one rim, and a constant ambient
term. There is no bounce: the underside of a shelf is dark because nothing
tells it that a lit board is 40mm below it, and the corner where wall meets
floor has no contact darkening beyond a 20mm SAO radius.

The path, given no native lightmap support:

1. Model the room's static geometry in Blender (it is a handful of boxes).
2. Bake full GI to a lightmap in Cycles — a second UV channel, non-overlapping
   islands. Blender 5.0 exports a dedicated lightmap UV channel to glTF without
   workarounds.
3. Write one `.mat` that samples it and applies it via `postLightingColor` with
   `postLightingBlending: multiply` — the maintainers' own recommended
   workaround.

The caveat they name is real: the lightmap multiplies emissive surfaces too. In
this room the emissive things are the underlights, which sit *in front of* the
shelves rather than on them, so it is close to harmless — but it wants checking
rather than assuming.

**This is the recommendation I would rank first.** It is also the largest piece
of work in this document, and it is the one that most needs a real decision
before starting.

### 3.2 Shape the light with an object, not with the light

There is no cookie support, so a shaped pool has to be cast. The room already
runs PCSS soft shadows with contact hardening; anything placed between the lamp
and the wall gets shadowed correctly for free.

A slatted shade, a grille, a half-open blind, the edge of a shelf unit just out
of frame — any of these turns a uniform elliptical pool into something with
*intent*. This is the cheapest available route to "special lighting" as the
owner used the phrase, and it is geometry rather than shader work.

### 3.3 The dust exists but has nothing to hang in

DESIGN.md §4 says: *"Dust — slow-drifting motes, visible mainly in the light
shaft."* **There is no light shaft.** `DustGeometry` scatters 220–900 motes
across the whole wall volume and the lamp's cone is invisible, so the motes read
as noise rather than as air.

Two routes, in increasing cost:

- **A cone of additive transparent geometry** from the lamp, with a soft radial
  falloff. Old, cheap, and how most games did it before volumetrics. Needs one
  `.mat` with `blending: add` and depth-write off.
- **Ray-marched volumetrics in the fragment shader.** Correct, and probably
  beyond the frame budget on a phone at 1440×3120.

The first is almost certainly right. It also gives the dust its reason to
exist, which is otherwise a spec line the implementation does not honour.

### 3.4 Surface imperfection is what the wall is actually missing

The wall is now a generated mottle at ±8.5% — real variation, and still only
*albedo*. The stock material has no normal map, so the surface has no relief at
all: no micro-shadowing, no highlight breakup, nothing that changes as the
camera pans.

The literature on why renders look fake is unanimous and worth taking
seriously: it is not resolution, it is that *every surface is flawless*. Real
walls carry scuffs, nail holes, a wiped patch at hand height, roughness that
varies where something was leaned against it. The fix named everywhere is the
same — subtle roughness variation, micro-scratch overlays, faint grunge, all
"barely noticeable at normal viewing distance".

For this room that means a hand-written material with, at minimum, a **normal
map** and a **roughness map**. That is one `.mat` and two textures, and it
would do more for the wall than any amount of further grading.

### 3.5 Give the room a corner

The room is one wall plane, some boards, and now a floor. It has no side walls,
no ceiling, no corner. A single perpendicular return wall at the edge of the pan
range would do a large amount of work: it gives the fog something to recede
*into*, it gives the lamp a second surface to fall off across, and it converts
"a backdrop" into "a place" more cheaply than any amount of props.

The constraint is the locked decision that shelf bounds are derived from
content — a return wall has to be derived the same way, like `WallLayout.floorY`
already is.

### 3.6 Props, and the argument against most of them

Environmental storytelling is the obvious next thought and mostly the wrong one
here: geometry comes from a committed Blender script, every prop is modelling
plus a material plus a draw call, and the room's whole discipline is that the
records are the subject. A crate on the floor, a stack of sleeves leaning
against the shelf end, a cable — one or two, at the edge of frame, out of focus.
Not a shop.

The exception worth arguing for is anything that **occludes** — foreground
geometry passing in front of the lens as you pan does more for depth than a
dozen objects sitting in the middle distance.

---

## 4. Advanced graphics elements worth evaluating

Each of these needs a hand-written `.mat`, which is currently a locked decision
(`dust.mat` is the only one). That decision is worth revisiting *once*, as a
project decision, rather than being nibbled at.

| Element | What it buys | Cost / risk |
|---|---|---|
| **Lightmap via `postLightingColor`** | Bounce, contact shading, the whole reason a real room looks lit rather than illuminated. | One `.mat`, a Blender bake pipeline, a second UV set, and the emissive caveat. Highest value, highest effort. |
| **Normal + roughness maps on wall/floor/boards** | Surface relief that responds to the camera. The thing the mottle cannot do. | One `.mat`, two textures per surface. Low risk. |
| **Anisotropy on the disc** | The colourless directional sweep a real CD makes. Explicitly still wanted — the locked decision ruled out *iridescence*, not anisotropy. | `anisotropy` + `anisotropyDirection` on a disc material. Needs tangents. |
| **`secondRoughness` on the disc label** | Printed ink under lacquer is two lobes, not one. Currently approximated by flattening it to matte. | Same material as above. |
| **Light shaft cone** | The dust gets a reason to exist; the lamp becomes visible as a source. | One additive `.mat`, one cone mesh. |
| **Lens dirt on bloom** | The "real lens" cue, on a pass already running. | One texture. **No `.mat` needed.** Cheapest thing in this table. |
| **Screen-space reflections** | A floor that reflects. | **Measured at 7× with a case open (124→17fps).** Locked off. Do not revisit without a tier plan. |
| **Iridescence / dispersion** | — | Ruled out by the owner. Listed only so nobody proposes it again. |

---

## 5. Tools

### Already in the project
- **Blender** + the committed `tools/models/build_models.py`. All geometry
  comes from here; anything modelled must go in the script, not into a stray
  `.glb`.
- **`matc`** — Filament's material compiler. `docs/materials.md` records the
  cost honestly: a ~735MB download, run by hand, and both the `.mat` and the
  built `.filamat` are committed because nothing in the build can produce it.

### Filament's own toolchain, unused
- **`cmgen`** — generates the IBL from an HDRI, including the spherical-harmonic
  irradiance. The room currently builds its cubemap procedurally in
  `RoomLighting`; a real HDRI baked through `cmgen` would give the ambient
  actual structure instead of a constant.
- **`mipgen`** — mip chains, including compressed variants. Relevant the moment
  real textures arrive.
- **`roughness-prefilter`** — pre-filters a roughness map against its normal map
  to stop specular aliasing. Exactly the artefact a mottled wall at a grazing
  angle will produce.
- **`normal-blending`**, **`filamesh`**, **`resgen`**.

### Assets
- **[Poly Haven](https://polyhaven.com/)** — CC0, 4K/8K, full PBR map sets, plus
  HDRIs. The HDRI library is the relevant half: a dim interior HDRI through
  `cmgen` is the fastest route to a believable ambient.
- **[ambientCG](https://ambientcg.com/)** — CC0, 2000+ materials. Plaster,
  painted board, concrete, worn wood — everything the wall and floor want.
- **[3Dassets.one](https://3dassets.one/)** — searches both at once.

CC0 matters here beyond convenience: this app is heading for a paid tier, and
anything with a non-commercial or attribution clause becomes a licensing problem
later.

### Reference and comparison
The missing piece in all three failed attempts was **a way to compare**. Judging
"is this better" from memory, five minutes apart, on a phone, is not possible.
Cheap fixes, in order of usefulness:

- A **reference image** pinned to aim at — one frame from a game or a photograph
  that has the quality wanted. Without one, "moody" is not a target.
- **Screenshot A/B**: the app can already be driven by `adb exec-out screencap`.
  Two builds, two PNGs, side by side, is the whole tool.
- A **debug room** — a build flag that parks the camera at a fixed pose with a
  fixed library, so two screenshots are actually comparable. Nothing this
  session compared was framed the same way twice.

---

## 6. A staged order

Staged so each stage is independently judgeable and independently revertible,
and so the cheap verified wins land before the expensive speculative ones.

**Stage 0 — make comparison possible.** The debug pose and the A/B screenshot
habit. Also: pick the reference image. Nothing else should start first.

**Stage 1 — the lighting design, no new materials.** This is where the reference
actually lives. A practical strip per shelf row lighting the sleeves from above;
the existing underlights inverted to match; the wall pushed down the tonal range
until it recedes; the lamp given a visible fixture. Then the free post wins:
remove the inert bloom `quality` line and re-measure bloom properly, and try lens
dirt. Not one of these needs a `.mat`, and together they are most of the
difference between the current room and the photographs.

**Stage 2 — the material decision.** Decide, once, whether this project writes
`.mat` files. If yes: wall/floor normal + roughness first, because it is the
lowest-risk use of the new capability and the wall is the largest thing on
screen. The disc's anisotropy follows.

**Stage 3 — the light shaft.** Needs Stage 2's toolchain. Gives the dust its
purpose and the lamp its presence.

**Stage 4 — baked GI.** The big one. Only worth starting once the Blender→glTF
→`matc` pipeline from Stages 2–3 is proven, because it depends on all of it.

**Not in scope:** SSR (measured at 7×), local probes (unsupported), GTAO
(unreachable from Android), iridescence (ruled out), a second room (locked
decision).

---

## 7. What I still need from the owner

1. ~~A reference.~~ **Supplied 2026-09-10** — seven images, analysed in §0.5.
   They answered the third question below on their own: *darker*, with the light
   moved onto the records.
2. **Whether the room may have hand-written materials.** Still open, and still
   the gate on half this document. It is a real cost — `matc`, a manual build
   step, committed binaries — and a real ceiling if the answer is no. Note that
   Stage 1 no longer depends on it.
3. **Whether the shelf practicals may change the room's fiction.** The reference
   lights records from a strip *above* them; Jawon lights them from a pool
   *below*. The underlight is in `DESIGN.md` as where the app's colour lives, so
   inverting it is a design decision rather than a tuning one.

---

## Sources

- [Filament Materials guide](https://google.github.io/filament/main/materials.html)
  — shading models and the full lit-model parameter list
- [Filament core documentation](https://google.github.io/filament/main/filament.html)
  — IBL, distant light probes, why local probes are excluded on mobile
- [Filament discussion #4650 — static lightmaps](https://github.com/google/filament/discussions/4650)
  — no native support, and the `postLightingColor`/multiply workaround
- [Filament tools](https://github.com/google/filament/tree/main/tools) — cmgen,
  matc, mipgen, roughness-prefilter, normal-blending, filamesh, resgen
- [Poly Haven](https://polyhaven.com/) and [ambientCG](https://ambientcg.com/) —
  CC0 textures and HDRIs
- [Blender glTF export](https://docs.blender.org/manual/en/latest/addons/import_export/scene_gltf2.html)
  — occlusion maps and lightmap UV channels
- Everything about *this* build — forwarded fields, stock material parameters,
  bloom's dropped `quality` — was read out of the shipped
  `filament-android-1.72.1` bytecode and the shipped `opaque_textured.filamat`,
  not from documentation.
