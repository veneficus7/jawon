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

The wall and its browsing, sorting and search; the five-beat open/close sequence;
the in-room deck and its three pages, `TRACKS · SOUND · LYRICS`; the disc,
including scratch-to-seek, haptics and press-and-hold-to-pause; playback with a
proper media notification, crossfade, ReplayGain levelling, the equaliser and the
effects rack; importing albums, both by folder and by scanning the phone, with
cover art looked up for the records that carry none; removing them, including the
shelf packing back up around the gap; resuming an album where it was left; editing
an album after import; the shelf list; the listening record; library backup; the
self-updater; the first-run screen; the four room modes; adaptive quality tiers.

**The R8 release build has been run on a device**, not just built — the room
renders, a case opens, playback starts. That check matters more here than in most
apps (see `docs/release.md`): obfuscation breaks Filament at *runtime*, by name,
and a green build says nothing about it.

### Known incomplete

| | |
|---|---|
| **Deletion animation** | **The fracture geometry exists** — `disc_shards.glb` holds the disc pre-cracked into 27 pieces, and `DiscardScene.kt` drives them. What is still open is whether it *reads* as breaking and being thrown away, which is a tuning and staging question now rather than a modelling one. Judge it on a device before touching `tools/models/build_models.py` again. |
| **Quality tiers** | Written and adaptive. **No longer the open question it was**: the app has since run on 15+ devices with no reported lag, down to a Redmi 9 and a Galaxy Note 8 — well below the S23-class hardware whose stutter reports the tiers were built for. Treat the tiers as working until something contradicts that, and note the corollary: the floor is now much lower than the code assumes, so the *lowest* tier has never been the one anybody needed. |
| **Slot reuse after a delete** | The discard animation's one previous bug was leaving a pool slot at zero scale forever. Fixed in two places, but the reuse path itself is still unverified — provoking it means deleting a real album. |
| **Stats screens — built** | Shipped as `ui/listening/`, reached from the room's chrome and routed as `JawonDestination.Listening`. `PlayEvent` is live and filling, written by `PlaybackRepository` so background listening counts too, and `PlayEventDao.albumTotals()` is the query behind it. The rows wear the same `Patina` value the wall wears the cases by, so the two can never disagree. |
| **Tests** | **334, across 30 files, all passing on the JVM.** Well past the four pure files this row used to name: the scratch tone and render, the DSP (`DjFilter`, `Echo`, `Drive`, `Varispeed`, a stress test), `RackState`, the lyrics parsers and the LRCLIB client, `LibraryBackup`, the wall's sort and search, and the room's cadence, alongside the original `CrossfadeTest`. Two caught real mistakes while being written. **The three migrations are now covered** — `MigrationTest` builds a v1 database from raw DDL and walks it to 4, and the net was proved by deliberately breaking `MIGRATION_2_3` and watching three tests catch it. **The remaining gap is `data/importer`**: 8 files, zero tests, and it is the code that touches a stranger's library. |
| **Not started** | Nothing on the original list. Crossfade, the equaliser and the stats screens are all built — the equaliser now lives in the rack rather than in Settings. Of the release work, migration tests, CI (`.github/workflows/ci.yml`) and crash reporting (`data/diagnostics/CrashLog.kt`) are done. **Still absent: a privacy policy, Play listing assets, and `REQUEST_INSTALL_PACKAGES` is still in the manifest.** See `docs/release.md` §5. |
| **Crash log — needs one check on a device** | Built and unit-tested, but only the *string handling* is covered. What no test can reach is whether the handler actually fires: force a crash, confirm Settings → PROBLEMS fills in, and confirm SEND opens a share sheet with a readable file. The `onPlayerError` path is the same question and can be provoked by pointing an album at a deleted file. Until someone does that, this is written-but-unverified. |
| **Lyrics — built, all three tiers** | Shipped as the third deck page, `TRACKS · SOUND · LYRICS`. **1.** Embedded `USLT`, parsed by hand — `MediaMetadataRetriever` does not expose lyrics and Media3 hands the frame back as raw bytes anyway. **2.** A `.lrc` sidecar, which needs *two* lookups because a library arrives two ways: a sibling document in a granted SAF tree, or the indexed path of a MediaStore-scanned file read off disk. The document route alone found nothing on a real library — everything here came from the scan. **3.** LRCLIB, behind a Settings switch, asked only when the page is opened for a track the device could not answer, cached in `Track.fetchedLyrics` (schema 4) with an empty string meaning "asked, none" so nothing is looked up twice. Verified live: synced lyrics, correct line highlighted. The original decision is below. |
| **Lyrics — the original decision** | Three tiers, in this order. **1.** Embedded `USLT` out of the file's own ID3 tag: offline, free, and the import already opens every file to read its other tags. **2.** A `.lrc` sidecar beside the audio — the embedded *synced* tag (`SYLT`) exists but almost nothing writes or reads it, while `.lrc` is what every tool actually produces. **3.** **LRCLIB** (`lrclib.net`) when neither exists: no API key, no registration, no rate limit, MIT, and built explicitly for open-source players. Not Musixmatch or Genius — this is a licensing question before a technical one, and LyricFind quotes $10k. **The wrinkle:** every file here is a content URI, never a path, so "the `.lrc` next to it" is a `DocumentFile` traversal of a granted SAF tree, and for MediaStore-scanned files there may be no reachable sibling at all. Tier 2 will work for some libraries and not others; design for that rather than discover it. Lyrics are a *view*, not a control, so they belong as a third page — `TRACKS · SOUND · LYRICS` — not as another thing crammed into the deck. |
| **Needs a decision first** | Vinyl/cassette/iPod modes — three more rooms before one is finished. |

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
   on exactly this mistake. Charging counts too — the battery sat at 42.7°C after an
   afternoon plugged in, which is most of a thermal 3 on its own.

9. **`dumpsys gfxinfo` cannot see this app.** The room is rendered by Filament into its
   own surface and never goes through the View system, so gfxinfo counts only the
   Compose overlay: it reported *0 frames* with the deck closed and *43% jank* with it
   open, neither of which meant anything. Measure CPU instead — fields 14+15 of
   `/proc/<pid>/stat` over a fixed interval, and the same per thread under `task/<tid>/`
   to see which part is burning it. With the deck open that separates cleanly into
   `FEngine::loop` (the room, a flat ~0.39 of a core), `RenderThread` (Compose drawing)
   and `main` (composition). Compare **back to back in one session**: absolutes move a
   long way with thermal state, so an A/B a minute apart is trustworthy where two runs
   an hour apart are not.

10. **An animation that recomposes nothing can still be the most expensive thing on
    screen.** Reading an animated value inside `graphicsLayer` is the standard advice and
    it is right about *recomposition* — but a large translucent layer over the Filament
    surface still has to be re-blended by the compositor every time its alpha changes.
    The deck's window halo cost a third of the whole panel that way (868 → 576
    jiffies/10s when made static) for a pulse that was close to invisible. Animate small
    layers, not screen-wide washes.

11. **Samsung's logcat is unusable for app logs** — telephony spam buries them.
   Write to `context.filesDir` and read it back with
   `adb shell run-as com.jawon.app cat files/<name>.log`.

12. **`sqlite3` is not on the device.** Copy the database off instead:
    `adb shell "run-as com.jawon.app cat databases/javon.db" > out.db`.

13. **A case vanishing from the wall proves nothing.** The library spans several
    shelves and the camera frames about one row, so "it disappeared" usually means
    it scrolled. Verify against the database.

14. **One log line beats three screenshots.** Screenshots are expensive and
    ambiguous; `worldToScreenPoint` and a text log settle position questions
    outright. Reach for instrumentation first, not last.

### The audio pipeline

15. **Toggling `PlaybackParameters` in and out of neutral wedges the player.**
    Setting a pitch or speed *away from* 1.0 is cheap at any amount — an octave
    down measured 14 jiffies per six seconds against a baseline of 10. Crossing
    back and forth through neutral is not: each crossing adds or removes Media3's
    time-stretcher from the pipeline, which is a full reconfiguration, and doing it
    a few dozen times a second leaves the `ExoPlayer:Playback` thread spinning at a
    whole core with **no progress at all** — 607 against a healthy 8 — until
    Media3's watchdog reports *"Player stuck playing with no progress for 10000
    ms"*. The deck sits at `0:00`, refusing to play, and the phone reaches thermal
    status 3. Throttling the call rate to 8/second did **not** help; it is the
    crossing, not the frequency. The pitch control is withdrawn until there is a way
    to change it that cannot thrash the pipeline.

16. **Measure the playback thread, not the process.** `top` is useless here — the
    whole process looked idle while one thread burned a core. Find the thread and
    diff `utime+stime` from `/proc/<pid>/task/<tid>/stat` over a fixed interval;
    600 jiffies per six seconds is one full core. `state=R` with an empty
    `syscall` file proves a userspace spin rather than a block.

17. **Nothing was listening for `onPlayerError`,** so a wedged pipeline surfaced
    only as a stuck deck and a hot phone. `PlaybackService` now logs it. Without
    that listener the exception genuinely goes nowhere — Media3 does not log every
    one itself.

18. **Bisect by deleting, not by reading.** Three separate wrong causes were
    argued convincingly from the code before the empty-`setAudioProcessors` test
    settled it in one run: the app's own DSP was innocent the whole time.

---

## Locked decisions — do not relitigate

| Decision | Value |
|---|---|
| minSdk | **28** (Android 9), since 0.2 beta. It was 29, on the reasoning that this is "where scoped storage settles into one code path" and that going lower means a second legacy storage branch. That turned out not to apply to this app: every file it touches arrives through SAF or MediaStore as a content URI, never as a filesystem path, so there was no legacy branch to write. Lowering it was one line. (The device scan added later needs one `Build.VERSION` check to pick between `READ_MEDIA_AUDIO` and `READ_EXTERNAL_STORAGE` — a permission name, not a second code path.) A tester's Note 9 could not install the app; now it can. **Unverified below a Galaxy S23** — it installs and it builds, but nobody has run the room on Android 9 hardware. |
| 3D renderer | Filament via **SceneView** (`io.github.sceneview:sceneview`) |
| Geometry | **Imported glTF**, generated by the committed Blender script `tools/models/build_models.py`. The wall must use instancing so the index-keyed pool keeps its never-destroy guarantee. |
| Album ↔ shelf | **One shelf per album.** A CD is physical; it cannot be in two places. |
| Room style | **One room, four modes.** The geometry, the lamp position, the shelving, the dust and the open sequence are identical in all of them — the locked decision rules out building *more rooms*, not lighting the one room differently. A mode is a whole design, not a bulb: it owns the room's light, the 2D palette every flat screen reads (`Palette` in `ui/theme/Color.kt`), the deck's material and the glass in its window. **Midnight Shop · Cold Storage · After Hours · Workshop.** See `DESIGN.md`. |
| Disc surface | **No rainbow.** Iridescence was built and removed on the owner's call. This rules out *iridescence* (diffraction) and **not** *anisotropy* (the colourless directional sweep a real CD makes), which is wanted. Only the label side is ever seen. |
| Playback | Media3 / ExoPlayer + `MediaSessionService` |
| Network | **Cover art, and now metadata enrichment.** `INTERNET` was added in 0.2 beta for `CoverArtFetcher`: an album title and artist go to MusicBrainz for records whose own files carry no artwork, and an image comes back from the Cover Art Archive. **The owner has since decided to widen this** (see the row below) to award and credit lookups. What has not changed is the shape of it: only a title and an artist ever leave the device — no id, no account, no library listing — every lookup happens at import rather than at play time, results are cached to the database so nothing repeats, the import screen says so before it happens, and Settings has a switch. **This changes the Play data-safety form and the privacy policy**, and that is now a debt to pay before any listing, not a hypothetical. |
| Metadata enrichment | **Decided 2026-09-01: yes, online.** Track credits (producer, writer, features) from MusicBrainz relationships, and awards from **Wikidata** — MusicBrainz does not carry Grammy data, so this is a *second* external service and a second dependency to keep working. Feeds the badges on the tracklist. The offline badges (`MOST PLAYED`, `NEVER FINISHED`, `LOSSLESS · 24-BIT`, `FIRST PLAYED`) come from `PlayEvent` and the import metadata, cost nothing and need no network — build those alongside, because they work when the lookup fails and they are the ones that are true only on this user's phone. |
| Monetization | **Design toward a pay-once rack unlock.** Free: the room, playback, the scratch, the equaliser, import, backup. Paid: the full effects rack, badges, colour schemes. One-time purchase, never a subscription — Poweramp, Musicolet, AIMP and Vox have already settled that argument in this category, and a subscription on a player that touches no server reads as insulting. **No paywall plumbing ships until there is a listing to sell through**; this is a direction for what goes in which tier, not a feature to build now. |
| Library discovery | **MediaStore scan and SAF, both.** The scan is the default route and needs `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (28–32). The folder picker is not a fallback for the stubborn: MediaStore only indexes what the media scanner has seen, so cloud and USB storage are reachable only through SAF. |
| Database · DI | Room · Hilt |
| Custom materials | `app/src/main/materials/dust.mat` is the only hand-written one, compiled by hand with a version-matched `matc` — see `docs/materials.md`. Model materials arrive via glTF `KHR_materials_*`, so no new `.mat` is expected. |
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
- **Instrument before guessing.** See traps 14 and 18.
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
