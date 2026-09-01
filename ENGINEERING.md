# Jawon — operating brief

Read this first, every session. It is deliberately short; detail lives in the
documents listed at the bottom, which you read **when the task touches them**,
not on principle.

---

## What Jawon is

A local-first Android music player where the library is a **room**, not a list.
A wall of shelves holds CD jewel cases that levitate, turn, and glow in their own
underlight. Tap one and it pulls out of its slot, turns to face you, opens, and
lifts the disc out to spin up into playback. Turn the spinning disc to scrub.

The library is **entirely user-supplied** — nothing ships pre-loaded. The album is
the atomic unit; features that only make sense for shuffling single tracks across a
huge library do not belong.

**The room is the app.** It is the start destination, it browses, and it plays.
The old 2D Wall and Deck screens are deleted, on purpose. Add Album, Shelf Manager
and Settings are still Compose and still current.

---

## Where the project is

Phase 1 (a complete 2D music player) and Phase 2 (the room: M5–M9) are done.
**M11 · Realism** is where the work has been since: making the room look and feel
real rather than merely rendered.

### Working, and checked on a device

The wall and its browsing; the five-beat open/close sequence; the in-room deck;
the disc, including scratch-to-seek, haptics and press-and-hold-to-pause; playback
with a proper media notification; importing albums, both by folder and by scanning
the phone, with cover art looked up for the records that carry none; removing them,
including the shelf packing back up around the gap; resuming an album where it was
left; editing an album after import; the shelf list; the first-run screen;
adaptive quality tiers.

**The R8 release build has been run on a device**, not just built — the room
renders, a case opens, playback starts. That check matters more here than in most
apps (see `docs/release.md`): obfuscation breaks Filament at *runtime*, by name,
and a green build says nothing about it.

### Known incomplete

| | |
|---|---|
| **Deletion animation** | Works, but reads as weak. The owner wants the disc visibly *breaking* and being thrown away, which needs fracture geometry and something to throw it into — a modelling job in `tools/models/build_models.py`, not tuning. Plan it before coding. |
| **Quality tiers** | Written and adaptive, but only ever run on a Galaxy S25 Ultra. Three of five testers on S23-class hardware reported stutter; that is what the tiers exist for and none of it is confirmed. |
| **Slot reuse after a delete** | The discard animation's one previous bug was leaving a pool slot at zero scale forever. Fixed in two places, but the reuse path itself is still unverified — provoking it means deleting a real album. |
| **Stats screens** | The data is no longer the problem: `PlayEvent` is live and filling, written by `PlaybackRepository` so background listening counts too, and `PlayEventDao.albumTotals()` is the query they will be built on. Only the screens are missing. |
| **Tests** | **108, and all four named pure files are covered.** `Patina`, `WallLayout` (the crate flip), `CaseDiscard`, `Scratch`, `ShelfHover`, `OpenSequence` and `ReplayGain` alongside the original `CrossfadeTest`. Two of them caught real mistakes while being written. Still untested: everything touching Android or Filament, which is most of the app. |
| **Not started** | Colour schemes · crossfade · equaliser · stats screens (the `PlayEvent` table they read is live and collecting) |
| **Needs a decision first** | Lyrics · online collecting · vinyl/cassette/iPod modes. The first two extend the app's network use; the last is three more rooms before one is finished. |

---

## Traps

**These cost real time to find. Read them before debugging anything similar.**

### Filament and SceneView

1. **Never destroy an `ImageNode`.** On the pinned SceneView version, `destroy()`
   hands `MaterialLoader.destroyMaterialInstance()` a wrapper re-fetched via
   `getMaterialInstanceAt(0)` rather than the tracked instance, so the instance
   survives and Filament aborts the *process* on the next commit with *"Invalid
   texture still bound to MaterialInstance."* Hide nodes and swap `bitmap` in
   place. This is why the wall renders from an index-keyed pool
   (`ui/wallscene/CaseSlots.kt`) and never a keyed loop over visible albums.

2. **Filament ignores an unknown material parameter silently.** A typo is
   indistinguishable from a value that had no effect, which cost three rounds on
   the disc's washed-out label. Check against `Material.getParameters()` before
   concluding a value is wrong. The disc's material is gltfio's `base_lit_opaque`.

3. **On a non-metal, specular comes from `reflectance`, not `metallicFactor`.**
   Zeroing metallic does not stop a surface bouncing the lamp.

4. **SceneView's `CameraNode` re-aims itself at the world origin.** Panning away
   from centre tilts the lens back toward it — measured at −8.28° — which shifts
   the *entire view* vertically and makes every position-related bug look like a
   layout bug. The rotation is written every frame; see `LOOK_STRAIGHT_AHEAD`.

5. **Anything the frame driver writes to a pooled node, `bind()` must restore.**
   `bind()` handles position, visibility and artwork. The discard animation wrote
   *scale*, nothing put it back, and every album that later reused that slot
   rendered invisible.

6. **`OnGestureListener` has no `onUp`** — nineteen callbacks, not one a plain
   release. Use `SceneView(onTouchEvent = …)` for that. And the ordering between
   the gesture detector and the raw touch stream is **not guaranteed**, so never
   let two handlers drive the same `Animatable`.

7. **The glTF thickness axis is negated on export**, and getting it wrong renders
   without error. See `docs/models.md`.

### Measuring on device

8. **Check the thermal state before believing any frame rate.**
   `adb shell dumpsys thermalservice | grep "Thermal Status"` must read 0. The same
   build measures 124fps cool and 88fps hot; a 22% "regression" was once recorded
   on exactly this mistake.

9. **Samsung's logcat is unusable for app logs** — telephony spam buries them.
   Write to `context.filesDir` and read it back with
   `adb shell run-as com.jawon.app cat files/<name>.log`.

10. **`sqlite3` is not on the device.** Copy the database off instead:
    `adb shell "run-as com.jawon.app cat databases/javon.db" > out.db`.

11. **A case vanishing from the wall proves nothing.** The library spans several
    shelves and the camera frames about one row, so "it disappeared" usually means
    it scrolled. Verify against the database.

12. **One log line beats three screenshots.** Screenshots are expensive and
    ambiguous; `worldToScreenPoint` and a text log settle position questions
    outright. Reach for instrumentation first, not last.

---

## Locked decisions — do not relitigate

| Decision | Value |
|---|---|
| minSdk | **28** (Android 9), since 0.2 beta. It was 29, on the reasoning that this is "where scoped storage settles into one code path" and that going lower means a second legacy storage branch. That turned out not to apply to this app: every file it touches arrives through SAF or MediaStore as a content URI, never as a filesystem path, so there was no legacy branch to write. Lowering it was one line. (The device scan added later needs one `Build.VERSION` check to pick between `READ_MEDIA_AUDIO` and `READ_EXTERNAL_STORAGE` — a permission name, not a second code path.) A tester's Note 9 could not install the app; now it can. **Unverified below a Galaxy S23** — it installs and it builds, but nobody has run the room on Android 9 hardware. |
| 3D renderer | Filament via **SceneView** (`io.github.sceneview:sceneview`) |
| Geometry | **Imported glTF**, generated by the committed Blender script `tools/models/build_models.py`. The wall must use instancing so the index-keyed pool keeps its never-destroy guarantee. |
| Album ↔ shelf | **One shelf per album.** A CD is physical; it cannot be in two places. |
| Room style | **Midnight Shop only.** One room, done excellently. See `DESIGN.md`. |
| Disc surface | **No rainbow.** Iridescence was built and removed on the owner's call. This rules out *iridescence* (diffraction) and **not** *anisotropy* (the colourless directional sweep a real CD makes), which is wanted. Only the label side is ever seen. |
| Playback | Media3 / ExoPlayer + `MediaSessionService` |
| Network | **Cover art lookup only**, and it stays that way without a decision. `INTERNET` was added in 0.2 beta for `CoverArtFetcher`: an album title and artist go to MusicBrainz for records whose own files carry no artwork, and an image comes back from the Cover Art Archive. Nothing else leaves the device — no id, no account, no library listing — it runs only for albums that need it, the import screen says so before it happens, and Settings has a switch. On `HttpURLConnection`; two GETs per album does not justify a networking dependency. **This changes the Play data-safety form and the privacy policy.** |
| Library discovery | **MediaStore scan and SAF, both.** The scan is the default route and needs `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (28–32). The folder picker is not a fallback for the stubborn: MediaStore only indexes what the media scanner has seen, so cloud and USB storage are reachable only through SAF. |
| Database · DI | Room · Hilt |
| Custom materials | `materials/dust.mat` is the only hand-written one, compiled by hand with a version-matched `matc` — see `docs/materials.md`. Model materials arrive via glTF `KHR_materials_*`, so no new `.mat` is expected. |
| Screen-space reflections | **Off.** Measured at a 7× cost with a case open (124fps → 17). Constants are kept beside the switch in `RoomAtmosphere`; turning them back on is a tier decision, not a default. |
| Shelf bounds | Derived from content, never unbounded: occupied slots plus one empty slot of margin. The user physically cannot pan past the last album. |

---

## Architecture rules

1. **Two layers, cleanly separated.** Compose owns all 2D UI; SceneView owns the
   3D room. They meet only through shared ViewModel state — the scene never owns
   app state, and Compose never reaches into Filament.
2. **Playback logic lives in neither composables nor the scene.** A repository owns
   the ExoPlayer instance; everything else reads state and emits events.
3. **High-frequency state** — playback position, spin angle, pan offset — must not
   recompose anything but the smallest possible scope. Prefer driving the scene
   directly.
4. **No new 3D or animation dependencies.** SceneView and Filament cover bloom,
   DOF, AO, fog, vignette, grading, shadows and custom materials. Raise it first if
   something seems to need another library.
5. **No file I/O, metadata parsing or image decoding on the main thread.** Ever.
6. Use SceneView's **Composable-as-texture** for in-world UI (shelf label plates)
   rather than baking text into bitmaps by hand.

---

## Working agreement

- **One thing at a time, checked on a device between changes.** This was ignored
  once — nine commits, one check at the end — and the check found a bug the first
  time the feature was actually exercised.
- **Plan before coding** anything touching the 3D scene, gestures or animation
  timing.
- **Instrument before guessing.** See trap 12.
- **Read SceneView's source** for API questions rather than guessing at signatures.
- Values exported from `javon-shelf-prototype.html` are **starting points for
  tuning**, not requirements.
- Test target is a **Galaxy S25 Ultra** — flagship. Smooth there is not proof it
  runs anywhere; note anything expensive so the tiers can use it.
- Never assume a milestone is complete. If this file looks stale or contradicts
  the code, **ask** — do not infer from the repo.

---

## Where the detail is

| Read when | Document |
|---|---|
| Orienting in the code — what each file does | `docs/architecture.md` |
| Touching the case, disc or beam models | `docs/models.md` |
| Touching `dust.mat` or any shader | `docs/materials.md` |
| Building, signing or shipping | `docs/release.md` |
| Questions about the product's intent | `PLAN.md` |
| Questions about how the room should look | `DESIGN.md` |
| Exact animation timing and feel | `javon-shelf-prototype.html` — **open it and read the JS**; when a 3D behaviour is ambiguous, the prototype is the source of truth, not your assumption |
