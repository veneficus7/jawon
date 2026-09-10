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

## 1. The engine audit, and one finding that invalidates existing code

### 1.1 The trap that governs everything below

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

### 1.2 `BloomOptions.quality` is never forwarded — and a measurement in the repo is wrong

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

### 1.3 What is forwarded and completely unused

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

### 1.4 What is not available at all

- **GTAO.** `setAmbientOcclusionOptions` forwards `quality`, `lowPassFilter`,
  `upsampling`, `bentNormals` and the rest — but not `aoType` and not any
  `gtao*` field. Android gets SAO, full stop. The existing comment is correct.
- **Lightmaps.** Filament has no native lightmap support. The maintainer's
  stated reason is that a plain lightmap carries no directionality and they
  would rather do spherical harmonics or gaussians, plus it needs a baking tool
  ([discussion #4650](https://github.com/google/filament/discussions/4650)).
  **The maintainers' own suggested workaround is `postLightingColor` with
  `postLightingBlending: multiply`** — which is available to any hand-written
  material. See §3.1; this matters more here than anywhere else.
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

### 1.5 Quietly available and unused

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

## 2. Design ideas, in order of how much they change the image

Ordered by impact per unit of risk, and deliberately biased toward things that
work *with* the existing engine constraints rather than against them.

### 2.1 The room is 100% static — bake the light into it

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

### 2.2 Shape the light with an object, not with the light

There is no cookie support, so a shaped pool has to be cast. The room already
runs PCSS soft shadows with contact hardening; anything placed between the lamp
and the wall gets shadowed correctly for free.

A slatted shade, a grille, a half-open blind, the edge of a shelf unit just out
of frame — any of these turns a uniform elliptical pool into something with
*intent*. This is the cheapest available route to "special lighting" as the
owner used the phrase, and it is geometry rather than shader work.

### 2.3 The dust exists but has nothing to hang in

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

### 2.4 Surface imperfection is what the wall is actually missing

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

### 2.5 Give the room a corner

The room is one wall plane, some boards, and now a floor. It has no side walls,
no ceiling, no corner. A single perpendicular return wall at the edge of the pan
range would do a large amount of work: it gives the fog something to recede
*into*, it gives the lamp a second surface to fall off across, and it converts
"a backdrop" into "a place" more cheaply than any amount of props.

The constraint is the locked decision that shelf bounds are derived from
content — a return wall has to be derived the same way, like `WallLayout.floorY`
already is.

### 2.6 Props, and the argument against most of them

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

## 3. Advanced graphics elements worth evaluating

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

## 4. Tools

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

## 5. A staged order

Staged so each stage is independently judgeable and independently revertible,
and so the cheap verified wins land before the expensive speculative ones.

**Stage 0 — make comparison possible.** The debug pose and the A/B screenshot
habit. Also: pick the reference image. Nothing else should start first.

**Stage 1 — free wins, no new materials.**
Remove the inert bloom `quality` line and re-measure bloom's real cost. Add lens
dirt. Try the light-shaping geometry in front of the lamp. All three are hours,
not days, and two of them are pure art direction.

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

## 6. What I need from the owner before starting

1. **A reference.** One image. Everything above is technique; the target is
   taste, and it is not mine to pick.
2. **Whether the room may have hand-written materials.** Half this document is
   gated on that one locked decision. It is a real cost — `matc`, a manual build
   step, committed binaries — and a real ceiling if the answer is no.
3. **Whether "moody" means darker or richer.** These have pulled in opposite
   directions three times now. A dark room with more *contrast* and a dark room
   with more *light in it* are different projects.

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
