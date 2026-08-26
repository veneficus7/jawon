# Javon — Operating Brief

Read this first, every session. Full product spec is in `PLAN.md`.
Visual and interaction spec is `javon-shelf-prototype.html` — **open it and
read the JS before implementing any 3D behaviour.** When a behaviour is
ambiguous, the prototype is the source of truth, not your assumption.

---

## Status

**M0–M9 complete, each verified on-device on a Galaxy S25 Ultra.**
Phase 1 gave a finished music player. Phase 2 has since replaced its 2D front
end entirely: **the room is the app.** It is the start destination, it browses
the wall, and it plays. The 2D Wall and 2D Deck are deleted — deliberately, per
`PLAN.md` §2; they live on only in git history.

Working today: the wall (shelf geometry, spine and cover art, label plates,
pan/fling, content-derived bounds), the five-beat open/close sequence with
interruption from any point, the in-room deck (transport, tracklist, seek,
quality readout), and the now-playing case standing proud of its slot.

**M8 is complete**: the disc is a platter you can scratch to seek (with
haptics) and it shows the user's uploaded `discFaceUri` art.

**Its iridescence was built and then removed** — the owner did not want a
rainbow on the CD. `PLAN.md`'s technology table still lists an iridescent CD;
that line is overruled. The disc is plain silvered plastic carrying its label.
Do not add it back.

**M9 · Atmosphere is complete.** Part 1 gave the room its light: a warm
shadow-casting spot, an amber bounce, and image-based lighting built
procedurally in `ui/wallscene/RoomLighting.kt` — no environment map is shipped
or downloaded. Part 2 gave it air: fog, thresholded bloom, vignette,
GPU-animated dust (`ui/wallscene/DustGeometry.kt` + `materials/dust.mat`), and
`DESIGN.md`'s reduced-motion handling.

**Depth of field runs only while a case is open.** `DESIGN.md` asks for shelf
sections to the sides falling off, and a flat, square-on wall cannot deliver it
— everything sits at nearly the same depth, and at this scale any blur erased
the 10mm spines outright. Legibility of the wall wins. See `RoomAtmosphere.kt`.

## M11 · Realism — in progress

M9 lit the room but it does not look real, and the owner has called the graphics
and animation "unrealistic and ugly". This is a four-pass rebuild, one pass per
session per the working agreement below.

**Pass 1 · Render pipeline — done, awaiting on-device sign-off.** Pure Kotlin
across `RoomAtmosphere.kt`, `RoomLighting.kt` and the key light in
`WallSceneScreen.kt`. Four things were wrong and none of them were geometry:

- **No ambient occlusion at all** — nothing ever set `ambientOcclusionOptions`.
  Its absence is most of why objects read as pasted on rather than resting on
  anything. Now SAO at a 20mm radius, plus SSCT along the lamp's direction.
- **Shadows on bare defaults** — no `ShadowOptions` anywhere. Now a 2048 map
  with room-scaled depth hints and PCSS contact hardening.
- **A 64px IBL cubemap**, so glossy surfaces reflected a blur. Now 256.
- **Depth of field propped up by a `cocScale` of 2.4** against an aperture
  stopped down to 3.5mm — which deepens the field and then smears everything
  outside it 2.4× too far. That is what read as artificial. Now unity scale
  against a 6mm aperture, at native resolution with a median filter, which is
  what fixes blur bleeding over the top of the spinning disc.

**Pass 4 · Motion and camera — done, awaiting on-device sign-off.** Taken out of
order because Passes 2–3 are blocked on model assets and this needs none.
`OpenSequence.kt` had exactly two curves, both cubic, and every beat decelerated
onto its target and **stopped dead** — which nothing physical does. Now:

- `easeOutBack` on pull, turn, open and lift, so each beat carries past its mark
  and settles back. Overshoot is picked per beat from how far that beat actually
  travels, not by eye — 2.7mm on the pull, 2.1° on the turn, 3.7° on the lid,
  2.7mm on the disc. See the table in `easeOutBack`'s KDoc.
- **Anticipation** on the pull: the case sinks 1.8mm back into its slot before
  coming out, into ~6mm of clearance.
- **The camera no longer shares curves with the objects.** It used to be driven
  straight off the turn and lift beats; now that those overshoot, that would rock
  the lens along with the case. It has its own jerk-free `smootherStep` channels
  (`Beats.turnCamera` / `liftCamera`) — do not "simplify" those away.
- Disc spin-up ramps on `smootherStep` rather than stepping its acceleration.

**All easing stays a pure function of `t`.** That is load-bearing, not
stylistic: closing runs `progress` backwards through the same curves, which is
what makes interruption free and the "never two cases mid-animation" guarantee
structural. A stateful spring would be more physical and would break both. The
happy side effect is that overshoot reversed *is* anticipation, so the close
reads correctly with no second set of curves.

**Pass 2 · Case and disc models — done, awaiting on-device sign-off.** No model
was bought or downloaded in the end. They are **generated by a committed Blender
script**, `tools/models/build_models.py`, run headless — which is parametric and
licence-free like the old procedural geometry, but gets real bevels, correct
shading normals and glTF material extensions from a real modelling tool. Full
procedure and the traps in **`docs/models.md`; read it before touching the
models.**

- `case_tray.glb` — moulded shell with wall thickness, a well, a spindle
  rosette and hinge knuckles, replacing one `CubeNode`.
- `case_lid.glb` — clear front with a rim and hinge tabs. **Its origin is the
  hinge axis**, so `ActiveCase.kt` parents it at local zero and the pivot is
  right by construction.
- `disc.glb` — 1.2mm thick with a broken edge, 192 segments, replacing the flat
  annulus whose every normal was `+Z`.
- Clearcoat on all three via `KHR_materials_clearcoat` — the polished skin over
  moulded plastic, and the lacquer over the disc's print. SceneView's stock
  material has no parameter for it.

Bevels are the point. Every edge used to be a perfect 90° corner, and a perfect
corner catches no specular highlight.

`DiscGeometry.kt` is deleted; the disc's radial UV convention is preserved by the
model, so `DiscArt` did not change.

**Pass 3 · Shelf and room models** — not started. The wall's shelved cases
(`CaseSlots.kt`) are still `CubeNode`s; only the active case is a model. They are
seen spine-on at ~25px so the mismatch barely shows, but it is a mismatch.

### Two bugs this pass created, both worth remembering

**Async models broke two effects that had never had to be async-safe.** The
active-case group used to exist from frame one, so `WallSceneScreen`'s visibility
effect could hide it and its artwork effect could fill it. Loading models made
the group appear several frames late, after both effects had already run against
null handles and done nothing — so it arrived **visible**, parked at the wall's
origin as a grey slab across the shelves. Fixed by mounting hidden and keying
both effects on the model instances. Any new effect writing to those nodes needs
the same key.

**The thickness axis is negated on glTF export**, and getting it wrong renders
without error. See `docs/models.md`.

**Two locked decisions below are superseded by this work, on the owner's call:**
geometry moves from procedural to imported glTF models (Pass 2), and the
"one custom material" row no longer binds — though glTF's `KHR_materials_*`
extensions mean no *new* hand-written `.mat` is expected either. See
`docs/materials.md`.

**M10 · Polish & release** (performance, GPU tiers, icon, onboarding) follows
this, not the other way round — tiering a look that is still being rebuilt would
be wasted work.

### Measured, on a Galaxy S25 Ultra, with a case open

Everything in pass 1 is essentially free **except screen-space reflections**,
which are so expensive they are switched off. Frame pacing via
`dumpsys SurfaceFlinger --timestats`:

| configuration | fps |
|---|---|
| pre-M11 baseline | 124 |
| SSR on, 0.6m reach, stride 2 | **17** |
| SSR on, 0.25m reach, stride 8 | 86 |
| SSR off — everything else on | **124** |

Full-resolution DOF, full-resolution AO with SSCT, PCSS, a 2048 shadow map and
the 256px IBL together cost nothing measurable. SSR alone was a 7x regression.

**It looks innocent while browsing**, which is the trap: browsing holds 124fps
either way, because SSR raymarches per glossy pixel and the wall has almost
none. Open a case and the lid and disc fill the frame, so nearly every pixel
traces. Measure the *open* state, not the wall.

`RoomAtmosphere.SSR_ENABLED` is false and the constants are kept beside it.
Turning it back on is an M10 tier decision, not a default.

Still never properly checked: **inter-case shadowing**, which wants a shelf with
adjacent albums.

Never assume a milestone is complete. If this status line looks stale or
contradicts the code, ask — do not infer from the repo.

### One hard-won constraint

**Never destroy an `ImageNode`** on the SceneView version we are pinned to.
`ImageNode.destroy()` enqueues its texture for destruction but hands
`MaterialLoader.destroyMaterialInstance()` a wrapper re-fetched via
`getMaterialInstanceAt(0)` — not the object the loader tracks — so the instance
survives and Filament aborts the process on the next commit with *"Invalid
texture still bound to MaterialInstance."* Hide nodes and swap `bitmap` in
place instead.

This predicted M6's culling as the place it would bite, and it did: a hard
vertical fling pulled whole rows out of the frustum window, destroyed their art
nodes and killed the app. **The wall therefore renders from a pool of case
slots keyed by index, never by album id** (`ui/wallscene/CaseSlots.kt`), so no
case node is ever destroyed. Do not turn that back into a keyed loop over the
visible cases.

---

## What Javon is

A local-first Android music player where the library is a **room**, not a list.
The user browses a wall of shelves holding CD jewel cases, spines out, reading
titles off the spines like book spines. Tapping one pulls the case from its slot,
turns it to face the user, opens the lid, and lifts the disc out to spin up into
playback. Turning the spinning disc scrubs the audio like a DJ platter.

The library is **entirely user-supplied**. Nothing ships pre-loaded. Users add
their own audio files, name their own shelves, and upload two images per album:
the case cover and the disc face.

The album is the atomic unit. Features that only make sense for shuffling single
tracks across a huge library do not belong.

---

## Locked decisions — do not relitigate

| Decision | Value |
|---|---|
| minSdk | 29 (scoped storage settled; `BluetoothCodecStatus` needs 28+) |
| 3D renderer | Filament via **SceneView** (`io.github.sceneview:sceneview`) |
| Geometry | ~~**Procedural** via SceneView primitives + custom buffers. No Blender, no downloaded models.~~ **Superseded by M11 Pass 2** — the case, disc and shelves move to imported glTF models, on the owner's call. The wall must use `AssetLoader.createInstancedAsset` so `CaseSlots.kt`'s index-keyed pool keeps its never-destroy guarantee. |
| Shelf capacity | Unlimited, grows with content, bounded by content (see below) |
| Album ↔ shelf | **One shelf per album.** A CD is physical; it cannot be in two places. |
| Room style | **Midnight Shop only.** One room, done excellently. See `DESIGN.md`. |
| Playback | Media3 / ExoPlayer + `MediaSessionService` |
| Database | Room |
| DI | Hilt |
| Disc surface | **No rainbow.** Iridescence was built in M8 and removed on the owner's call. Do not reintroduce it. Note this rules out *iridescence* (diffraction, which throws colour) and **not** *anisotropy* (the colourless directional sweep a real CD's highlight makes) — the latter is wanted. Only the label side is ever seen. |
| Custom materials | `materials/dust.mat` is the only hand-written one, compiled **by hand** with a version-matched `matc` — see `docs/materials.md`. From M11 Pass 2, model materials arrive via glTF `KHR_materials_*` extensions instead, so no new `.mat` is expected. |

### Custom shaders (`.filamat`)

One custom material is in the tree: `materials/dust.mat`, compiled to
`assets/materials/dust.filamat`. It animates every dust mote in the vertex
shader from a single `time` uniform, so the CPU does nothing per particle.
Everything else uses SceneView's stock materials.

Worth knowing if that changes: Filament's Gradle plugin is **not published to
Maven Central or the Gradle Plugin Portal**, so nothing in the build can compile
a `.mat`. Materials have to be compiled manually with a `matc` matching the
`filament-android` SceneView pulls, and their output committed alongside the
source. That is not a rule-4 violation — `matc` is a build-time tool run by hand,
never a dependency. Full procedure and the shader traps already discovered are in
`docs/materials.md`.

### Shelf bounds ("defence against infinite scroll")
The wall's scrollable extent is **derived from content**, never fixed or
unbounded: `bounds = occupied slots + one empty slot of margin` in each
direction. The user physically cannot pan past the last album. Adding albums
grows the wall. Additionally, only case nodes within or near the camera frustum
are instantiated — never emit nodes for the whole library.

---

## Architecture rules

1. **Two layers, cleanly separated.** Compose owns all 2D UI. SceneView owns the
   3D room. They communicate through shared ViewModel state — the 3D layer never
   owns app state, and Compose never reaches into Filament directly.
2. **Playback logic never lives in composables or in the scene.** A repository
   owns the ExoPlayer instance. Everything else reads state and emits events.
3. **High-frequency state** (playback position, disc spin angle, pan offset) must
   not trigger Compose recomposition of anything but the smallest possible scope.
   Prefer driving the scene directly over recomposing.
4. **No new 3D or animation dependencies.** SceneView + Filament covers bloom,
   DOF, SSAO, fog, vignette, colour grading, shadows and custom `.filamat`
   materials. If something seems to need another library, raise it first.
5. **Threading.** No file I/O, metadata parsing, or image decoding on the main
   thread. Ever.
6. Use SceneView's **Composable-as-texture** capability for in-world UI (shelf
   label plates, liner notes on the case back) rather than baking text into
   textures manually.

---

## Milestones

### Phase 1 — a complete music player, no 3D at all

- **M0 · Skeleton** — project setup, modules, Hilt, Room, Compose nav shell.
- **M1 · Import** — SAF folder picker, audio scan, `MediaMetadataRetriever`
  tags, album grouping, per-track codec/rate/depth capture, cover upload
  (case cover + disc face).
- **M2 · Shelves** — create, rename, reorder, recolour, assign/move albums,
  empty states.
- **M3 · Playback** — ExoPlayer, `MediaSessionService`, notification, lock
  screen, bluetooth buttons, audio focus, becoming-noisy, gapless, queue,
  quality readout.
- **M4 · 2D UI complete** — plain but properly designed Compose interface.
  *Superseded by M7: the Wall and Deck screens are gone and the room does both.
  Add Album, Shelf Manager and Settings are still Compose and still current.*

> **CHECKPOINT.** At the end of M4 Javon is a finished, shippable music player.
> Do not begin Phase 2 until M4 genuinely works on-device. Everything after this
> is the experience layer, and it is where the risk lives.

### Phase 2 — the room

- **M5 · SceneView bootstrap** — scene in Compose, engine lifecycle, one lit
  jewel case built from primitives. Prove the pipeline end to end.
- **M6 · The wall** — shelf geometry, many cases, user textures on spines and
  case fronts, pan/fling browsing, label plates, content-derived bounds,
  frustum-based instantiation.
- **M7 · Open & close** — the five-beat sequence, forward and reverse,
  interruptible at any point (see beats below).
- **M8 · The disc** — iridescence material, spin bound to playback state,
  scratch-to-seek with haptics.
- **M9 · Atmosphere** — lights, shadows, IBL, bloom, DOF, fog, dust.
- **M10 · Polish & release** — performance, GPU tiers, icon, onboarding.

### The five beats (M7)
Reference `javon-shelf-prototype.html` for exact timing fractions.

1. **Pull** — case slides out of its slot toward the viewer
2. **Turn** — rotates 90° from spine-on to cover-on
3. **Open** — lid swings on its hinge
4. **Lift** — disc rises out of the tray, case drifts back and down
5. **Spin** — disc squares up, spins to playback speed, UI reveals

Closing is the same sequence reversed, not a cut. It must be interruptible from
any point and must never leave two cases mid-animation.

---

## Working agreement

- **One milestone per session.** Never bundle. Visual and interaction work has to
  be felt on a device between changes, not reviewed as one large diff.
- **Use Plan Mode** for anything touching the 3D scene, gesture handling, or
  animation timing. Propose before writing.
- **Read SceneView's source** for
  API questions instead of guessing at signatures.
- Values exported from the prototype are **starting points for tuning**, not
  fixed requirements. Expect to iterate on-device.
- After each milestone, stop for a build and a manual check before continuing.
- Test target is a **Samsung Galaxy S25 Ultra** — flagship hardware. Do not treat
  smooth performance there as proof it runs everywhere; note anything expensive
  so M10 can add quality tiers.
