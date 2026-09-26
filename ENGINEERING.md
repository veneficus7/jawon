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

**And there is now more than one room.** A **format** is the medium the library is
handled as — CD, pocket player, vinyl, cassette — and each has a room of its own,
chosen by one switch for the whole library. The CD room is finished; the others are
not built. A *mode* (Vesper · Glacies · Vigilia · Officina) is the CD room's light and
nothing else. See `docs/formats.md`, and the standards below before building one.

---

## Where the project is

Phase 1 (a complete 2D music player) and Phase 2 (the room: M5–M9) are done.
**M11 · Realism** is where the work has been since: making the room look and feel
real rather than merely rendered. **On 2026-09-21 the owner called the CD room
finished**, and the next work is the other formats — vinyl, cassette and a pocket
player — planned in `docs/formats.md`. **The pocket player is under way**: its model is
committed and its room renders (debug builds only, Settings → THE ROOM).

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
| **Where the sources stand** | Three routes in, and `Features.kt` is the switch and the reasoning. **Local import** — the original, unchanged. **Subsonic** (`data/remote/`) — built, verified end to end on a device, parked briefly on 2026-09-09 and **back on the same day** (`Features.MUSIC_SERVER = true`, with the owner's reasoning in its KDoc) — it shipped switched on in 0.6 beta. **The Archive** (`data/archive/`) — the record shop, verified end to end on a device including a scratch on a streamed track. **Spotify was removed outright on 2026-09-21**, on the owner's call — deleted, not flagged, and `Features.kt` says why: Spotify never hands over the audio, so on a Spotify album the disc, the rack and the scratch had nothing to act on. What is left is `data/local/SpotifyCleanup.kt`, which at every launch (and after a restore) takes Spotify albums, their emptied shelf and the sign-in tokens off the phone — every launch rather than once, because an old backup can bring the albums back. **Run on the owner's phone on 2026-09-21, and it took 28 of his 33 albums off the wall** (POP 13→1, HIP HOP 8→3, ALT ROCK 12→1). There was no Spotify shelf; the albums were almost certainly Spotify syncs he had filed into his own genre shelves — the cleanup can select nothing else — and that was read as "no Spotify albums" before installing. See trap 31. **He accepted the loss the same day** — "its fine they're gone" — since those albums could not play without the route anyway. |
| **CD ripping — the whole route now, still no drive to test it on** | `data/cd/` reads an audio CD from a USB drive, and `ui/cd/` is the screen that drives it: find the drive, ask Android for it, wait for the disc to spin up, read the TOC, look the disc up at MusicBrainz by its disc id, rip to a folder the user picks, then hand that folder to the ordinary folder import. **Nothing about a ripped CD is a new import path** — it is a normal folder that happened to be written a minute ago, which is also why the rip writes through SAF rather than into app-private storage: a disc is ~600MB as WAV and it should land somewhere the owner has a path to. The pure half is well covered — `CdRipTest` has the chunking (whose last-chunk case is what stops the final track running into the lead-out), the file naming that is the *only* metadata a WAV carries, and the disc lookup that has to pick one medium out of a box set. `CdDrive` and `CdRipper`'s transfer loop are the untestable seam and are deliberately thin. **Still nothing here has met hardware.** The likeliest first failure is not a bug but power: a bus-powered drive the phone will not supply enough current to enumerate, which shows up as a device that appears in the USB list and then stops answering — the screen and the read-failure message both name it. |
| **Streaming — built and exercised on device** | `data/remote/` holds `SubsonicClient`, `RemoteLibrarySync`, `ServerInvite` and the credentials model; `playback/StreamCache.kt` puts a bounded LRU disk cache in front of the network so the scratch still seeks instantly; `Settings → A music server of your own` sets it up. Schema 6 adds `Album.sourceId`, `Album.remoteId` and `Track.remoteId` — all nullable, so an existing library stays local and untouched. **Verified on the S25 Ultra against a protocol-accurate stub server** (`tools/` is not where it lives; it was a throwaway): migration 5→6 over a real library, catalogue sync, an idempotent re-sync reporting *updated* rather than *new*, streamed playback with the quality readout correct, the cache filling to 50 MB, scrubbing a cached track, and the "gone from the server" deletion branch. R8 release build ran on the device. **Still unverified: an actual Navidrome** — the protocol is exercised, that specific server is not. |
| **Open access — built** | `ServerInvite` packs a server into one `jawon://…` string; Settings has a paste field that fills all three fields and tests immediately, and a SHARE A LINK button. `-Pjawon.serverInvite=…` at build time ships a build already pointed at a server, so a fresh install has a library and no setup — empty by default, and `FORGET` sticks permanently once the user has touched the setting. **This is a shared secret handed to strangers and the encoding is not security.** Whatever is served through it is a publishing decision: commercial releases are not distributable however legitimately they were bought, and the constraint is legal, not technical. See `docs/server.md`. |
| **The room's look — now its own project, and the reference moved twice** | See `docs/room-design.md` for the research and the staging. Stage 1 (lighting, no new materials) is in and on the owner's phone. **Shipped as 0.6.2 beta (versionCode 15) on 2026-09-14**, after a day of the owner
iterating on the look in near-real time. **What the room is now:** a rack of records against
painted brick, on a floor, under a neon sign, with its own things standing about — framed
records and screen-printed posters of his own albums on the wall, a clock above them, a
turntable and a plant on the floor. Every per-album *light* has been removed: three
arrangements were tried in one day (underlight, vitrine in front, side tubes) and all three
failed identically, because **a light bright enough to read a photograph by is bright enough
to desaturate it, and where you put it only decides which part of the sleeve pays.** What
survived is the emissive pool each record floats over, which lights nothing and therefore
cannot wash anything. The records are lit by the room: a shelf-edge rail from above (white
now, at `RAIL_ROOM_TINT` 0.06, because it is the only thing touching a sleeve and 30% of a
green room made every cover green), and ceiling track whose heads half wash the rack and half
point at the floor. Browsing has detents: one haptic tick per album, from the frame loop so
the fling ticks too. **The older record of the room's earlier passes:** a rack of records standing against a painted brick wall, on a floor, under a neon JAWON sign. Four fixtures, each with one job: a tube in the back corner of every compartment washing that room's colour down the panel; a **shelf-edge rail** under each shelf's front lip, near white, raking down the sleeves; ceiling spots on a fixed track, the room's only shadow casters; and **each album's vitrine** — two strips down the sides of its bay, in the album's own colour. **The underlight is gone**, on the owner's call ("maybe just remove all underlights"), and `ui/wallscene/VitrineGeometry.kt` is why the replacement works: the bay pitch is 150mm and a face-on cover is 125mm, so a strip in the 25mm channel between two records sits *outside* the cover's edge, where a flat glossy plate has no reflection of it — the vitrine can be the artwork's key light without writing a highlight across it, which nothing standing in front of a record can do. Evenness across a sleeve went 5.1:1 → 1.5:1. **And the covers are glossy again**: the plates are authored at `COVER_ROUGHNESS` 0.16, because clearing the shell to 8% opacity so the art could be seen through it left nothing on a case reflecting the room, which the owner read as "cases lost their shininess". Volumetric fog exists and is parked (`RoomAir.FOG_ENABLED`). **Four things cost time and none of them were taste.** (1) *The wall went pure black* — `reflectance = 0.04` in the belief it meant "4%, like a dielectric". Filament's `reflectance` is a 0..1 remap, `F0 = 0.16 * reflectance²`, so **0.5 is the 4% a dielectric actually has**; 0.04 is 0.026%. Trap 3. (2) *The grade washed out* — AgX lifts blacks by design, in a room that is almost entirely blacks, while shadow gammas above 1 crushed what was left. Now `ACESLegacy`, exposure 0, gammas that lean. (3) *The room was flat because of `AMBIENT_INTENSITY`*, not because of anything missing. Its own comment said it "has to carry most of the room", which was true and was the problem: uniform IBL fill lights every surface equally, so nothing falls off. 40k → 8k took the wall 50.3 → 8.4 while sleeves only fell 147 → 50 — the wall had been six-sevenths ambient. (4) *The shelf fixture was lighting panel the camera cannot see.* It sat 12mm above its own row's records and threw down **behind** them. The only back panel ever visible is the `ROW_PITCH - CASE_H - BOARD_H` = 52mm band **above** a row, and the fixture belongs at the top of that compartment, under the shelf above. Two rounds of re-aiming happened before this was found, both tuning something off-camera. **And the N·L trap has now cost time three separate times in `RoomLighting`**: a light directly above a vertical face contributes exactly zero, so a strip above a sleeve lit nothing (146.9 → 147.7), a light in the exact back corner makes that corner the *darkest* part of the panel, and a wash aimed at a wall it is travelling parallel to is worth nothing at any intensity. **The look is not testable and never will be** — `RoomGradeTest`, `RoomSurfaceTest` and `RoomFloorTest` cover only the arithmetic. What settles look arguments is `tools/emulator/README.md`: screenshot plus luminance over fixed boxes, which contradicted my own eye every time the two disagreed. |
| **Tests** | **744, across 67 files, all passing on the JVM** (703 until the Spotify suites went with the route on 2026-09-21, 674 after). The newest are the pocket player's seven — `ClickWheelTest`, `PocketMenuTest`, `ClickSoundTest`, `WheelRockTest`, `TrainMotionTest`, `PassingLampsTest`, `TiltFilterTest` — and the wheel's two subtlest rules, the seam at ±180° and the dead zone round the centre, were each proved by breaking them: exactly the one test written for each failed. Well past the four pure files this row used to name: the scratch tone and render, the DSP (`DjFilter`, `Echo`, `Drive`, `Varispeed`, a stress test), `RackState`, the lyrics parsers and the LRCLIB client, `LibraryBackup`, the wall's sort and search, and the room's cadence, alongside the original `CrossfadeTest`. Two caught real mistakes while being written. **All five migrations are now covered** — `MigrationTest` builds a v1 database from raw DDL and walks it to 6, and the net was proved by deliberately breaking `MIGRATION_2_3` and watching three tests catch it. **Every package now has tests.** `AlbumImportRepositoryTest` drives the real commit path against a real database; `PlayAccountingTest` covers the arithmetic that decides what goes into `PlayEvent`, which the patina and the badges are both computed from. Each new suite was proved by deliberately breaking the rule it guards and watching the right tests — and only those — fail. `RemoteLibrarySyncTest` is the newest to earn that: minting a fresh album id per sync, and letting the server reset `playCount`, each failed exactly the one test written for it. What is still untestable here is the scene itself, which needs a device. |
| **Not started** | Nothing on the original list. Crossfade, the equaliser and the stats screens are all built — the equaliser now lives in the rack rather than in Settings. Of the release work, migration tests, CI (`.github/workflows/ci.yml`), crash reporting (`data/diagnostics/CrashLog.kt`) and the privacy policy (`docs/privacy.md`) are done. **The policy is public** as `PRIVACY.md` in the `javon-releases` repo — and it is a *copy*, so it goes stale silently: it still had the 4 September text, naming three network uses, when 0.6 beta was built with seven. **Re-copy it on any release that changes `docs/privacy.md`** (`docs/release.md` §4). **Still absent: Play listing assets, and `REQUEST_INSTALL_PACKAGES` is still in the manifest.** See `docs/release.md` §5. |
| **Crash log — verified on a device, by accident** | On 2026-09-12 a launch crash (trap 23, "never construct a Filament object at file or object level") hit the owner's S25 Ultra twice. The handler fired both times, Settings → PROBLEMS held both reports with the right version, device and full stack, and SEND produced a readable file the owner shared back — the whole chain, end to end, on real hardware. **Still unverified: the `onPlayerError` path**, which can be provoked by pointing an album at a deleted file. |
| **Lyrics — built, all three tiers** | Shipped as the third deck page, `TRACKS · SOUND · LYRICS`. **1.** Embedded `USLT`, parsed by hand — `MediaMetadataRetriever` does not expose lyrics and Media3 hands the frame back as raw bytes anyway. **2.** A `.lrc` sidecar, which needs *two* lookups because a library arrives two ways: a sibling document in a granted SAF tree, or the indexed path of a MediaStore-scanned file read off disk. The document route alone found nothing on a real library — everything here came from the scan. **3.** LRCLIB, behind a Settings switch, asked only when the page is opened for a track the device could not answer, cached in `Track.fetchedLyrics` (schema 4) with an empty string meaning "asked, none" so nothing is looked up twice. Verified live: synced lyrics, correct line highlighted. The original decision is below. |
| **Lyrics — the original decision** | Three tiers, in this order. **1.** Embedded `USLT` out of the file's own ID3 tag: offline, free, and the import already opens every file to read its other tags. **2.** A `.lrc` sidecar beside the audio — the embedded *synced* tag (`SYLT`) exists but almost nothing writes or reads it, while `.lrc` is what every tool actually produces. **3.** **LRCLIB** (`lrclib.net`) when neither exists: no API key, no registration, no rate limit, MIT, and built explicitly for open-source players. Not Musixmatch or Genius — this is a licensing question before a technical one, and LyricFind quotes $10k. **The wrinkle:** every file here is a content URI, never a path, so "the `.lrc` next to it" is a `DocumentFile` traversal of a granted SAF tree, and for MediaStore-scanned files there may be no reachable sibling at all. Tier 2 will work for some libraries and not others; design for that rather than discover it. Lyrics are a *view*, not a control, so they belong as a third page — `TRACKS · SOUND · LYRICS` — not as another thing crammed into the deck. |
| **Formats — the pocket player under way** | **Where it stands (2026-09-26):** the device is modelled at the classic's envelope (`build_pocket()`, `docs/models.md`); the format switch exists (`Format.kt`, `JawonNavHost(format)`), offered only in debug builds; and `ui/pocketscene/` renders the device at *computed* life size under its own carriage IBL, checked on the flagship AVD. **The screen is live**: `PocketLcd` is Compose in a `ViewNode` parented to the device, sitting 0.1mm over the panel and under blended glass (traps 32 and `docs/models.md`'s solid-shell note are what that cost). **And it plays (2026-09-26): the click wheel works** — turn, select, MENU, Play/Pause, the sides, and every hold on them, by the classic's own rules (`ClickWheel.kt`, pure and tested), through real menus built from the library (`PocketMenu.kt`: Albums, Shelves as playlists, Artists, a tracklist, Now Playing with volume and the scrubber). Each step clicks through a static low-latency `AudioTrack` and ticks the vibrator. Checked on the flagship AVD end to end, from importing through the device's own Add Albums row to a track playing; **the click's latency and the haptic feel need the owner's phone** — the emulator has no fast mixer. The wheel is printed now (MENU and the transport glyphs, as geometry — `docs/models.md`), rocks toward the side a press lands on and lets the centre button go down (`WheelRock`, springs), and the backlight goes out after ten seconds untouched, as the classic's did. **The window is up** (2026-09-26): a hand-written `night_window.mat` behind the device — the night going past out of focus, at three depths, with poles, a rocking carriage and the ceiling light's reflection on the glass (`NightWindow.kt`, `docs/materials.md`). **And the device is lit by it**: the carriage's ceiling lamp as a key spot, and lamps going past as real lights on an irregular schedule — glints sliding across the glass from behind you, a rim along the edge from behind the device (`PassingLamps.kt`). Lights here have to be in the hundreds-to-thousands of candela to register at SceneView's default exposure; the first two were 2.6 and 3.4cd and did nothing. **And it answers the phone's tilt**: turn the phone and the carriage's reflections slide across the glass and the edge, as a turned mirror's do (`TiltFilter.kt`, the game rotation vector, listened to only while the room is on screen). **Cover Flow too**: the covers in perspective on a glossy floor, turned through by the wheel, a cover turned over by the centre to show its tracks on the back (`CoverFlowLcd.kt`). Not yet: imperfection maps, a way to the rack and the lyrics. **Two things this cost**: the camera arrived pitched −8.28° again (trap 8 — the room must overwrite the rotation every frame), and life size has to come from the scene's own pixel height, not `displayMetrics.heightPixels`, which excludes the system bars the room draws under. A pocket (iPod-style) player, vinyl and cassette. These sat here under *needs a decision first* for one reason — "three more rooms before one is finished" — and **on 2026-09-21 the owner called the CD room finished**, which removes it. He decided four things the same day, recorded in `docs/formats.md` §2: **the pocket player first**; **one switch for the whole library**; vinyl browsed by **crate-digging**; and **a room of its own per format**, which reverses the locked one-room decision below. Call them *formats*, not *modes* — a mode is the light (`RoomScheme`). The CD room is not made generic: a new room is a sibling of `WallSceneScreen`, sharing the view model, playback and the `Room*` rendering files. Step 1 is the format switch with only the CD behind it; step 2 is a spike measuring a *moving* `ViewNode` screen, the pocket player's one real technical risk. |

---

## Building a new format room

**The CD room set the standard, and a new format is held to it.** It took M5–M11 and
every trap below to get there, so none of this is starting fresh — it is the same bar,
reached faster because the mistakes are already written down. The owner's bar on top of
that, for every new room: *"dont hold back at all … hyper realism and beauty … the
highest quality of 3d graphics and animation"*, and the renderer may change if Filament
cannot reach it.

1. **A room is the whole app for that format.** It is the start destination, it browses
   and it plays. No 2D list screen as a way in, and no second way to do what the room
   does. The album stays the atomic unit (PLAN §2) in every format, whatever the medium.
2. **A new room is a sibling, never a branch.** `WallSceneScreen` is ~3,200 lines and is
   finished; it does not grow a `when (format)`. A new room is its own scene, sharing the
   view model, `PlaybackRepository`, the deck and the `Room*` rendering files by calling
   them. Anything genuinely common moves out to a file of its own first.
3. **Geometry is generated by the committed Blender script**, at real-world dimensions,
   with real bevels, and every dimension a named constant that agrees with the app's own.
   Add the exported bounds to `docs/models.md` — those bounds are the cheap check that
   caught the thickness-axis and hinge-origin traps.
4. **Materials come through glTF.** gltfio 1.72.1 honours normal, occlusion, emissive,
   clearcoat (with its own normal map), sheen, specular, anisotropy, transmission, volume,
   IOR and iridescence, plus KTX2/Basis textures — checked in the shipped binary, not
   assumed. **Individually, not all together**: its ubershaders cannot combine clearcoat
   with transmission, volume, sheen or IOR, and a material asking for both keeps the coat
   and silently drops the rest. It says so once in logcat — *"… not supported in
   ubershader mode for clearcoat materials"* — which is how the pocket player's glass was
   caught. The clearcoat extension's mere *presence* counts, so `principled()` no longer
   emits an empty one. Hand-write a `.mat` only for what glTF cannot express (animated rain
   on glass, an LCD under glass), with the version-matched `matc` (`docs/materials.md`).
5. **Light the room, do not decorate it.** Every fixture gets one job and a reason, and
   the result is *measured* — screenshot plus luminance over fixed boxes
   (`tools/emulator/README.md`) settles it, because the eye lost every argument it had
   with that method. Traps 5–7, 24 and 26–27 are all lighting, and all of them cost a day.
6. **Motion is springs and overlapped beats, reversible from any point** (`OpenSequence`
   is the worked example), with haptic detents on anything that turns or steps, and it
   holds up at 120Hz. Perfect periodicity and linear easing are what read as CG.
7. **Measure the cost as each piece lands**, per traps 28–30: the room already holds the
   GPU near 100%, `gpu_busy_percentage` and the render scale both saturate, and the only
   harness that moves with real cost is DRAWN under a controlled pan.
8. **Test what is pure, measure what is not.** Layout, sequence timing and gesture
   arithmetic are JVM tests; the look never will be.
9. **One thing at a time, checked on the owner's phone** — and before anything that can
   delete rows from his library, read trap 31.
10. **A room that touches the network, storage or a permission changes
    `docs/privacy.md` in the same commit.** It is a published promise, not a description.

---

## Traps

**These cost real time to find. Read them before debugging anything similar.**

**Cite a trap by its title as well as its number.** The numbers move whenever one is added,
and they have: an audit on 2026-09-11 found "Samsung's logcat is unusable" cited as trap 9,
trap 11 and trap 15 in three different files, each correct in the era it was written.

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
   Zeroing metallic does not stop a surface bouncing the lamp. **And `reflectance` is not a
   percentage** — it is a 0..1 remap, `F0 = 0.16 * reflectance²`, so the ~4% an ordinary
   dielectric reflects is written as **0.5**, which is also SceneView's default. Writing `0.04`
   because "a dielectric reflects 4%" gives 0.026% and a surface that returns essentially nothing
   from the lamp. On a wall whose albedo is 2–10% the specular *is* the image, so this turned the
   whole wall black — and it built, ran, and passed every test. See `WALL_REFLECTANCE`.

4. **`ImageNode` is UNLIT, and nothing says so.** `MaterialLoader.createImageInstance`
   builds from `materials/image_texture.filamat`, whose `MAT_SHAD` chunk is `0x00`
   against `0x01` for `opaque_textured`. So every `ImageNode` renders its bitmap at
   flat texture brightness and **no light in the scene can touch it** — no gradient,
   no falloff, no reflection, no response to anything. All three of a case's art
   plates were `ImageNode`s, which meant every album cover on the wall was the one
   thing in the room that could not be lit, through four rounds of lighting work.
   The file sizes are the quick tell: a lit material compiles every lighting variant
   and runs 1.2–1.4MB, where the unlit ones are 15–115KB. The fix is to bind a lit
   instance to the node's own texture (`materialLoader.createTextureInstance(texture, …)`,
   then `materialInstance = it`), which is safe because `setBitmap` writes pixels into
   that `Texture` and never touches the material instance, and because
   `RenderableComponent` never calls `destroyMaterialInstance` — so rebinding cannot
   trip trap 1. Cache the instance; a node's `apply` runs on every recomposition.
   **The symptom to recognise: a measurement that will not move.** The cover art read
   106.1, 106.5, 106.9, 107.0 across four separate lighting changes, and that was
   read as the records holding steady while the room came up around them.

5. **A light that is part of the furniture must be positioned by the furniture.** The shelf
   ribbon's lights hung from a carrier the frame driver moved to `(panX, panY)` every frame,
   while the tube they belong to was drawn at a fixed place on each shelf. At rest they lined
   up, so every screenshot looked right; during any swipe the lit pattern slid across the
   fixed timber and the gradient detached from its tube. The owner described it exactly —
   "the back wall is moving separately from the shelf" — and it took a *held* drag to see
   (`adb shell input motionevent DOWN/MOVE` with no `UP`, so the wall cannot settle). A
   bounded set of lights that follows the view is fine; it must **snap to the geometry's own
   grid**, never sit at the raw pan. See `RoomNodes.placeRibbon`.

6. **Filament's `falloff` is a hard cutoff radius, not a soft decay.** Past it a light
   contributes exactly zero. A ribbon at 0.12 on a 19cm panel produced a clean gradient and
   then a dead-flat floor across the bottom 40% — which reads as a second, unlit wall.
   Make it longer than the surface and let inverse-square shape the gradient.

7. **Light channels are the tool for "this light may only touch these surfaces".** A day
   went into constraining the album underlights with cone angles, and every fix traded one
   surface for another — as points they washed the back panel, as upward spots they put a
   hotspot in every case's glass. `LightManager.Builder.lightChannel` and
   `RenderableManager.setLightChannel` both forward to native in this Filament; a light
   reaches only renderables sharing a channel with it. Reach for these before re-aiming a
   light. See `RoomLighting.UNDER_LIGHT_CHANNEL`.

8. **SceneView's `CameraNode` re-aims itself at the world origin.** Panning away
   from centre tilts the lens back toward it — measured at −8.28° — which shifts
   the *entire view* vertically and makes every position-related bug look like a
   layout bug. The rotation is written every frame; see `LOOK_STRAIGHT_AHEAD`.

9. **Anything the frame driver writes to a pooled node, `bind()` must restore.**
   `bind()` handles position, visibility and artwork. The discard animation wrote
   *scale*, nothing put it back, and every album that later reused that slot
   rendered invisible.

10. **`OnGestureListener` has no `onUp`** — nineteen callbacks, not one a plain
   release. Use `SceneView(onTouchEvent = …)` for that. And the ordering between
   the gesture detector and the raw touch stream is **not guaranteed**, so never
   let two handlers drive the same `Animatable`.

11. **The glTF thickness axis is negated on export**, and getting it wrong renders
   without error. See `docs/models.md`.

### Measuring on device

12. **Check the thermal state before believing any frame rate.**
   `adb shell dumpsys thermalservice | grep "Thermal Status"` must read 0. The same
   build measures 124fps cool and 88fps hot; a 22% "regression" was once recorded
   on exactly this mistake. Charging counts too — the battery sat at 42.7°C after an
   afternoon plugged in, which is most of a thermal 3 on its own.

13. **`dumpsys gfxinfo` cannot see this app.** The room is rendered by Filament into its
   own surface and never goes through the View system, so gfxinfo counts only the
   Compose overlay: it reported *0 frames* with the deck closed and *43% jank* with it
   open, neither of which meant anything. Measure CPU instead — fields 14+15 of
   `/proc/<pid>/stat` over a fixed interval, and the same per thread under `task/<tid>/`
   to see which part is burning it. With the deck open that separates cleanly into
   `FEngine::loop` (the room, a flat ~0.39 of a core), `RenderThread` (Compose drawing)
   and `main` (composition). Compare **back to back in one session**: absolutes move a
   long way with thermal state, so an A/B a minute apart is trustworthy where two runs
   an hour apart are not.

14. **An animation that recomposes nothing can still be the most expensive thing on
    screen.** Reading an animated value inside `graphicsLayer` is the standard advice and
    it is right about *recomposition* — but a large translucent layer over the Filament
    surface still has to be re-blended by the compositor every time its alpha changes.
    The deck's window halo cost a third of the whole panel that way (868 → 576
    jiffies/10s when made static) for a pulse that was close to invisible. Animate small
    layers, not screen-wide washes.

15. **Samsung's logcat is unusable for app logs** — telephony spam buries them.
   Write to `context.filesDir` and read it back with
   `adb shell run-as com.jawon.app cat files/<name>.log`.

16. **`sqlite3` is not on the device.** Copy the database off instead:
    `adb shell "run-as com.jawon.app cat databases/javon.db" > out.db`.

17. **A case vanishing from the wall proves nothing.** The library spans several
    shelves and the camera frames about one row, so "it disappeared" usually means
    it scrolled. Verify against the database.

18. **One log line beats three screenshots.** Screenshots are expensive and
    ambiguous; `worldToScreenPoint` and a text log settle position questions
    outright. Reach for instrumentation first, not last.

### The audio pipeline

19. **Toggling `PlaybackParameters` in and out of neutral wedges the player.**
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

20. **Measure the playback thread, not the process.** `top` is useless here — the
    whole process looked idle while one thread burned a core. Find the thread and
    diff `utime+stime` from `/proc/<pid>/task/<tid>/stat` over a fixed interval;
    600 jiffies per six seconds is one full core. `state=R` with an empty
    `syscall` file proves a userspace spin rather than a block.

21. **Nothing was listening for `onPlayerError`,** so a wedged pipeline surfaced
    only as a stuck deck and a hot phone. `PlaybackService` now logs it. Without
    that listener the exception genuinely goes nowhere — Media3 does not log every
    one itself.

22. **Bisect by deleting, not by reading.** Three separate wrong causes were
    argued convincingly from the code before the empty-`setAudioProcessors` test
    settled it in one run: the app's own DSP was innocent the whole time.

### Filament and SceneView, continued

*Numbered at the end so the numbers above stay where they are cited.*

23. **Never construct a Filament object at file or object level.** A top-level
    `private val HAZE_SAMPLER = TextureSampler(…)` in `WallSceneScreen.kt` crashed
    the app **on launch** on the owner's phone — `UnsatisfiedLinkError: No
    implementation found for … TextureSampler.nCreateSampler` — because a file's
    top-level vals are built in its static initialiser, which runs the first time
    anything in the file is touched, before Filament has loaded its native library.
    It built, and all 662 unit tests passed, because none of them loads Filament;
    only a launch finds it. Use `by lazy`, or build it inside the scene. The same
    applies to any `Texture`, `MaterialInstance`, `Material` or light builder in an
    `object` or companion.

24. **Hiding a node does not switch off a light in it.** In SceneView 4.32,
    `Node.updateVisibility()` only recurses into the children, and `LightNode` never
    overrides it — visibility reaches renderables and stops there (checked in the
    bytecode). So every "hidden" light in the room went on shining: the ribbon for
    rows that do not exist, empty pool slots' underlights, the held case's two when
    no record was out. It was invisible for as long as the room had no surface
    where those lights landed; a brick wall above the rack showed a strip light with
    no strip. Switch a light off with `setIntensityCandela(0f)`, and back on with
    its real value. `isVisible` is still right for the renderables beside it.

### The network

25. **`HttpURLConnection.disconnect()` is not `close()`, and calling it empties the
    connection pool.** Android's own documentation says it "may close the underlying
    socket if a persistent connection is otherwise idle", which is exactly what it
    does. Every request in `SpotifyClient` (the Spotify route, removed since) ended in `finally { connection.disconnect() }`
    — which looks like tidy resource handling and meant the *next* request paid a
    fresh DNS lookup, TCP handshake and **TLS handshake** to the same host. With the
    deck polling Spotify once a second for as long as a record is open, that is one
    full TLS handshake per second while the room is rendering, and it surfaced as the
    owner reporting that "the spinning disc is lagging a little bit" — a rendering
    complaint with a networking cause. What actually returns a socket to the pool is
    **reading the response body to the end**; `use {}` does that. Keep `disconnect()`
    for the failure path only, where the socket is not worth reusing.

26. **A surface lit by the ambient IBL has exactly one exposure control, and it is
    albedo.** Light channels (trap 7) gate *lights*; they do not gate `IndirectLight`,
    which reaches every renderable there is. The open disc turned out to be lit almost
    entirely by ambient, and three delete tests on a device are what proved it: its own
    lamp switched fully **off** moved the face 239.7 — 239.9, taking it off the room's
    light channel moved it 239.9 — 240.5, and only dropping its albedo moved anything
    (240.5 — 91.4). Five rounds of "the disc looks washed" had been spent re-aiming and
    re-tuning lamps that were contributing a rounding error.

27. **The tonemapper's shoulder makes a large change measure as nothing, and "washed" is
    usually the shoulder rather than a highlight.** Probing one surface at both ends fit
    `out = 336 * albedo^0.336` — so flat at the top that *halving* the albedo is worth
    about 1%, while an 18x cut is worth a factor of 2.6. Three separate fixes were
    abandoned as ineffective on moves of 139 — 134 and 243 — 240 that were in fact the
    curve behaving normally. **So a null result high on the curve proves nothing** —
    bracket the response with a deliberately extreme probe *first* (drive the value to
    0.05, or paint the region pure red), fit the two points, and read the value you want
    off the curve instead of stepping toward it. Stepping costs a build each time and the
    first three steps are all inside the flat part. This is also what desaturation is: ACES
    folds the top of the range toward white, so an over-exposed surface loses its colour
    before it loses its brightness, and the complaint arrives as "washed", not "too bright".

28. **The room is not janky; it is hot, and those need different measurements.** "The app runs
    heavy" was investigated on 2026-09-14 and the room turned out to draw **81fps while being
    panned continuously, with 0.8% slow frames** — smooth by any standard. What is true is that
    it holds the GPU at **~100% busy forever**, including with nobody touching it, which on the
    owner's S25 Ultra means a thermally capped 832MHz of 1200 and a phone at 41°C. Heat and
    battery, not stutter. Do not go looking for dropped frames.

29. **`gpu_busy_percentage` saturates, and so does the render scale — neither can A/B anything.**
    `/sys/class/kgsl/kgsl-3d0/gpubusy` read 99.9% with shadows on, with shadows off, with the
    frame rate halved, and with the fog off: once the GPU is the bottleneck it reads 100% no
    matter what you change. `ROOM SCALE` is worse than useless — Filament's dynamic resolution
    **always sits at exactly `minRenderScale`**, so it reported 60% in every configuration and
    80% the moment the floor was raised to 0.80. It is reporting the floor, not the load.
    **The harness that does work**: Settings -> HOW IT IS RUNNING -> MEASURE AGAIN to reset,
    then pan continuously for ~20s, then read **DRAWN**. That is throughput under a controlled
    load and it moves with real cost. Everything below was measured that way, back to back.

30. **The fog is the dominant per-pixel cost, and per-*pixel* is the operative word.** Measured:
    fog off **96fps** drawn / 0.3% slow / worst 33.3ms, fog on **81fps** / 0.8% / worst 41.7ms.
    It is 14 raymarch steps on a quad that covers the frame, and `RoomQuality.fogSteps` says so
    in its own first line. **It was switched on after the tier was validated** — `FOG_ENABLED`
    was false when HIGH was signed off, the owner asked for "a little fog back" on 2026-09-13,
    and nothing re-checked HIGH afterwards; the render scale went from the 81% recorded in
    `RoomAtmosphere` to its 60% floor. Two things were falsified while finding this. **Shadows
    are not the cost** — all four casting lights off changed nothing measurable, so the four
    shadow passes are cheap. **And MSAA is genuinely near-free while resolution is not**: trading
    4x MSAA at a 0.60 scale for no MSAA at 0.80 is 2.25x fewer *samples* and measured **slower**
    (81 -> 75fps), because the cost scales with shaded pixels and each pixel pays the fog march.
    So the samples-vs-resolution trade runs the opposite way to the intuition.

### Filament and SceneView, continued again

32. **Screen-space refraction sees only the opaque pass.** A transmissive material in front of
    anything *transparent* — a `ViewNode`, a blended plate — refracts whatever is behind that
    thing and paints it over it. The pocket player's glass hid its own live screen this way:
    it faithfully refracted the dark LCD panel underneath and drew that on top of the menu.
    Two probes that hid the wrong things changed nothing; hiding the glass showed the screen
    at once. A flat cover bends nothing visible anyway, so it is a blended surface at a few
    percent alpha now, which keeps its full specular and sorts after the screen. (A
    `"Sampler descriptor 1 of MaterialInstance is not set"` warning at launch comes from a
    `ViewNode` before its first frame arrives, and is harmless.)

33. **A `ViewNode` puts its first frames on the texture and then stops, unless told.** SceneView
    4.32 copies a `ViewNode`'s children into its texture only from its own container's
    `dispatchDraw` (`ViewNode$Layout`, which locks the `Surface` and draws them in software).
    Under hardware acceleration a child's `invalidate()` re-records the child's own display list
    and never calls the parent's `dispatchDraw` — so Compose inside a `ViewNode` recomposes
    perfectly, and the texture keeps showing the frame it drew when it was laid out. The pocket
    player's menu moved on every turn of the wheel, with probes proving both compositions had
    the new row, while the glass showed the first highlight forever. **Invalidate
    `viewNode.layout` after each recomposition** (a `SideEffect` inside the content) — once per
    change, never per frame. **And read everything the screen shows in that same scope**: the
    backlight's fade was first animated *inside* `PocketLcd`, so each frame of it recomposed only
    that inner scope, the `SideEffect` never ran, and the screen never dimmed. The screen
    composable stays stateless and the room animates. The CD room's shelf plates never met this
    because they never change after they are laid out; **a plate whose text changed in place
    would show the same fault.**

### The owner's library

31. **Count what a change will take from the owner's library before it reaches his phone — from
    the database, not from what the shelves look like — and know that his build will not let
    you.** His phone runs the published, release-signed build: `run-as` refuses a non-debuggable
    app, and a debug APK will not install over it without an uninstall, which would wipe the
    library. So there is no database copy to take. And **a shelf is not a source**: deleting a
    shelf moves its albums to a neighbour (`ShelfManagerViewModel.onDeleteShelf`), so albums from
    any source can sit on any shelf under any name. On 2026-09-21 the Spotify cleanup took 28 of
    33 albums off his wall because "there is no Spotify shelf" was read as "there are no Spotify
    albums". Before anything that deletes rows goes to his phone: ask him to write a library
    backup first (Settings → Your library — the one backup a release build allows), and have the
    change say what it would take before it takes it, or ask him to look.

---

## Locked decisions — do not relitigate

| Decision | Value |
|---|---|
| minSdk | **28** (Android 9), since 0.2 beta. It was 29, on the reasoning that this is "where scoped storage settles into one code path" and that going lower means a second legacy storage branch. That turned out not to apply to this app: every file it touches arrives through SAF or MediaStore as a content URI, never as a filesystem path, so there was no legacy branch to write. Lowering it was one line. (The device scan added later needs one `Build.VERSION` check to pick between `READ_MEDIA_AUDIO` and `READ_EXTERNAL_STORAGE` — a permission name, not a second code path.) A tester's Note 9 could not install the app; now it can. **Unverified below a Galaxy S23** — it installs and it builds, but nobody has run the room on Android 9 hardware. |
| 3D renderer | Filament via **SceneView** (`io.github.sceneview:sceneview`) — **for the CD room.** For the new format rooms the owner opened this on 2026-09-21: "we can switch renderer and tools if current doesn't achieve what we want and more", with the bar "hyper realism and beauty … the highest quality of 3d graphics and animation". Because every format has a separate room, a different renderer could serve the new rooms alone. **Evaluated on 2026-09-22 (`docs/realism.md` §4): keep Filament.** Godot's Android renderer has no SSR, SSAO or volumetric fog; Unity as a Library renders full screen only, allows one runtime per process and is worst at exactly what this app does (destroy and rebuild the room on every navigation); Unreal cannot be embedded in an existing app. The cheap lever, if something is missing, is **upgrading Filament** — 1.76 added multiple directional lights and a second specular lobe for hazy materials — which SceneView pins, so it is a dependency override plus a `matc` re-match. |
| Geometry | **Imported glTF**, generated by the committed Blender script `tools/models/build_models.py`. The wall must use instancing so the index-keyed pool keeps its never-destroy guarantee. |
| Album ↔ shelf | **One shelf per album.** A CD is physical; it cannot be in two places. |
| Room style | **A room per format, and four modes in the CD room.** Reversed from "one room" on 2026-09-21, on the owner's call: each *format* — CD, pocket player, vinyl, cassette — gets a room of its own (`docs/formats.md`). Inside the CD room nothing changed: the geometry, the lamp position, the shelving, the dust and the open sequence are identical in all four modes, and a mode is a whole design, not a bulb — it owns the room's light, the 2D palette every flat screen reads (`Palette` in `ui/theme/Color.kt`), the deck's material and the glass in its window. **Midnight Shop · Cold Storage · After Hours · Workshop** (on screen, Vesper · Glacies · Vigilia · Officina). **The modes are the CD room's alone** — the owner, same day: "other rooms are seperate" — so each new room is lit its own way. See `DESIGN.md`. |
| Disc surface | **No rainbow.** Iridescence was built and removed on the owner's call. This rules out *iridescence* (diffraction) and **not** *anisotropy* (the colourless directional sweep a real CD makes), which is wanted. Only the label side is ever seen. |
| Playback | Media3 / ExoPlayer + `MediaSessionService` |
| Network | **Cover art, and now metadata enrichment.** `INTERNET` was added in 0.2 beta for `CoverArtFetcher`: an album title and artist go to MusicBrainz for records whose own files carry no artwork, and an image comes back from the Cover Art Archive. **The owner has since decided to widen this** (see the row below) to award and credit lookups. What has not changed is the shape of it: only a title and an artist ever leave the device — no id, no account, no library listing — every lookup happens at import rather than at play time, results are cached to the database so nothing repeats, the import screen says so before it happens, and Settings has a switch. **This changes the Play data-safety form and the privacy policy**, and that is now a debt to pay before any listing, not a hypothetical. |
| Metadata enrichment | **Decided 2026-09-01: yes, online.** Track credits (producer, writer, features) from MusicBrainz relationships, and awards from **Wikidata** — MusicBrainz does not carry Grammy data, so this is a *second* external service and a second dependency to keep working. Feeds the badges on the tracklist. The offline badges (`MOST PLAYED`, `NEVER FINISHED`, `LOSSLESS · 24-BIT`, `FIRST PLAYED`) come from `PlayEvent` and the import metadata, cost nothing and need no network — build those alongside, because they work when the lookup fails and they are the ones that are true only on this user's phone. |
| Monetization | **Design toward a pay-once rack unlock.** Free: the room, playback, the scratch, the equaliser, import, backup. Paid: the full effects rack, badges, colour schemes. One-time purchase, never a subscription — Poweramp, Musicolet, AIMP and Vox have already settled that argument in this category, and a subscription on a player that touches no server reads as insulting. **No paywall plumbing ships until there is a listing to sell through**; this is a direction for what goes in which tier, not a feature to build now. |
| Library discovery | **MediaStore scan and SAF, both.** The scan is the default route and needs `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (28–32). The folder picker is not a fallback for the stubborn: MediaStore only indexes what the media scanner has seen, so cloud and USB storage are reachable only through SAF. **And now a third route: a Subsonic server** — see the row below. |
| Streaming | **Decided 2026-09-08: a Subsonic server the user runs, and nothing else.** The trigger was a real constraint — a library of hundreds of albums does not fit on the phone. The library is still *entirely the user's own*; only its location moved. **No streaming service can ever be added**, and this is a licensing fact rather than an engineering one: SoundCloud's API terms forbid an app to "cache, download or persistently store any User Content" and forbid "adaptation, editing, excerpting, or creating derivative works", and Spotify's playback SDK is remote-control only. Jawon's deck *is* adaptation of the audio — the equaliser, the rack, and above all the scratch — so a catalogue client is not a version of this app. Registration for SoundCloud's API has also been closed to case-by-case review since 2016. Telegram-as-storage was investigated and rejected on its own Bot Developer ToS §5.2(e), which names "cloud storage sites" as the example of prohibited use, plus a 20 MB `getFile` ceiling smaller than one FLAC. Jamendo is rejected because its API terms forbid caching and offline access — which is exactly what the scratch needs — and restrict the free tier to non-commercial use, colliding with the rack unlock. **Spotify was built anyway, as a remote control over its Web API (2026-09-09), and removed on 2026-09-21**: it proved the paragraph above in practice — a record whose audio is in another company's process is one the disc cannot scratch. Do not rebuild it. |
| Database · DI | Room · Hilt |
| Custom materials | `app/src/main/materials/dust.mat` was the only hand-written one until 2026-09-12, when the owner asked for dust that catches the light and for volumetric fog; `fog.mat` is the second (parked, `RoomAir.FOG_ENABLED = false`). Both compiled by hand with a version-matched `matc` — see `docs/materials.md`. Model materials arrive via glTF `KHR_materials_*`, so no new `.mat` is expected. **Still true after the wall and floor were textured**, and worth knowing why: SceneView's stock `opaque_textured` exposes `metallic`, `roughness`, `reflectance` **and a `texture` sampler** — verified by reading the `MAT_UIB` chunk of the shipped `.filamat`, not assumed — and `MaterialLoader.createTextureInstance` binds it. So an albedo-textured lit surface needs no `matc` at all. What the stock set still cannot do is a **normal map**; `RoomSurface` is luminance variation in the albedo only, and that is the ceiling on how real those two surfaces can look without a hand-written material. **That ceiling is SceneView's stock materials, not the renderer** (checked 2026-09-22 in the shipped gltfio 1.72.1): a model imported as glTF carries its own materials, which expose `normalMap`, `normalScale`, `clearCoatNormalMap`, `occlusionMap`, `metallicRoughnessMap`, `emissiveMap` and `sheenColorMap`, and gltfio honours `KHR_materials_anisotropy`, `clearcoat`, `sheen`, `specular`, `ior`, `transmission`, `volume` and `KHR_texture_basisu`. A hero object does not need a hand-written `.mat` to be normal-mapped — see `docs/realism.md`. |
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
   something seems to need another library. **For the new format rooms this is loosened
   (2026-09-21)**: the owner said to use whatever tool reaches hyper-realism, renderer
   included. Offline tools — Blender and Cycles bakes, CC0 asset libraries, new
   hand-written materials — are cleared. A new *runtime* library or renderer is still
   raised with its measured cost first, because it is also an APK-size, R8 and thermal
   decision, not only a look.
9. **No file I/O, metadata parsing or image decoding on the main thread.** Ever.
6. Use SceneView's **Composable-as-texture** for in-world UI (shelf label plates)
   rather than baking text into bitmaps by hand.

---

## Working agreement

- **One thing at a time, checked on a device between changes.** This was ignored
  once — nine commits, one check at the end — and the check found a bug the first
  time the feature was actually exercised.
- **Plan before coding** anything touching the 3D scene, gestures or animation
  timing.
- **Instrument before guessing.** See traps 18 and 22.
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
| **Running a server, letting others in, or getting albums onto it** | `docs/server.md` — and read its first section before pointing anyone at a library, because what may be served is a legal question and nothing in the code enforces it |
| **Adding anything that touches the network, storage or a permission** | `docs/privacy.md` — it is a published promise, not a description. If a change would make a sentence in it untrue, the policy changes *with* that change, in the same commit, or it should not ship. |
| Questions about the product's intent | `PLAN.md` |
| **Anything about vinyl, cassette or the pocket player** | `docs/formats.md` — what the CD is made of in code, the seam that has to come first, and the decisions still open |
| Questions about how the room should look | `DESIGN.md` |
| **Reaching for realism in a new room, or asking whether the renderer is enough** | `docs/realism.md` — what Filament 1.72.1 forwards, ignores and cannot do, read out of the shipped binaries; the techniques the train window needs; and why the renderer stays for now |
| **Looking at the room without the phone** | `tools/emulator/README.md` — an AVD that reaches the same quality tier the phone does (`HIGH` needs >7GB RAM *and* API 33+, so the two low-spec AVDs land on `LOW`), a library with real cover art in it, and the screenshot-plus-luminance method that settles look arguments the eye gets wrong |
| **Working on the room's look, or reaching for a Filament feature** | `docs/room-design.md` — the research behind treating the room as its own project. Read §1 before proposing any View option: it records which fields the JNI binding actually forwards, and found that `BloomOptions.quality` is **not** one of them, which makes a measurement in `RoomAtmosphere` wrong. |
| Exact animation timing and feel | `javon-shelf-prototype.html` — **open it and read the JS**; when a 3D behaviour is ambiguous, the prototype is the source of truth, not your assumption |
