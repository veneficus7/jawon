# Javon — Operating Brief

Read this first, every session. Full product spec is in `PLAN.md`.
Visual and interaction spec is `javon-shelf-prototype.html` — **open it and
read the JS before implementing any 3D behaviour.** When a behaviour is
ambiguous, the prototype is the source of truth, not your assumption.

---

## Status

**M0–M8 complete, each verified on-device on a Galaxy S25 Ultra.**
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

**M9 · Atmosphere is half done.** Part 1 (the light) has landed: a warm
shadow-casting spot, an amber bounce, and image-based lighting built
procedurally in `ui/wallscene/RoomLighting.kt` — no environment map is shipped
or downloaded. The room now reads as one lamp over the racks with deep shadow
elsewhere, and the clear lid finally has something to reflect.

Currently starting **M9 part 2 · the air** — bloom, depth of field, fog,
vignette, dust, plus `DESIGN.md`'s reduced-motion handling.

Two things from part 1 are still unverified and want a real check: **inter-case
shadowing** (the test library is too small to have adjacent cases) and **frame
pacing** (`gfxinfo` cannot see SceneView, which renders on its own thread).

Never assume a milestone is complete. If this status line looks stale or
contradicts the code, ask — do not infer from the repo.

### One hard-won constraint

**Never destroy an `ImageNode`** on the SceneView version we are pinned to.
`ImageNode.destroy()` enqueues its texture for destruction but hands
`MaterialLoader.destroyMaterialInstance()` a wrapper re-fetched via
`getMaterialInstanceAt(0)` — not the object the loader tracks — so the instance
survives and Filament aborts the process on the next commit with *"Invalid
texture still bound to MaterialInstance."* Hide nodes and swap `bitmap` in
place instead. This cost most of a session to root-cause and will constrain
M6's frustum culling as the library grows.

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
| Geometry | **Procedural** via SceneView primitives + custom buffers. No Blender, no downloaded models. |
| Shelf capacity | Unlimited, grows with content, bounded by content (see below) |
| Album ↔ shelf | **One shelf per album.** A CD is physical; it cannot be in two places. |
| Room style | **Midnight Shop only.** One room, done excellently. See `DESIGN.md`. |
| Playback | Media3 / ExoPlayer + `MediaSessionService` |
| Database | Room |
| DI | Hilt |
| Disc surface | **No rainbow.** Iridescence was built in M8 and removed on the owner's call. Do not reintroduce it. |
| Custom materials | None in the tree. If one is ever needed, it is compiled **by hand** with a version-matched `matc` — see `docs/materials.md`. |

### Custom shaders (`.filamat`)

There are currently no custom materials; everything uses SceneView's stock ones.

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
