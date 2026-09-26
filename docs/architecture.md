# The code, and where things live

A map, so a reader can find the right file without reading the tree. 140 Kotlin
files in `main`, about 40,700 lines (counted 2026-09-21). The 3D room is 20,119 of
them — about half — and the audio pipeline in `playback/` is another 3,676.
"Everything else is small" was true when this file was written and is not any more.

`ENGINEERING.md` holds the traps and the locked decisions. This is only *where things
are*.

---

## The shape of it

```
MainActivity → JawonNavHost ─┬─ WallSceneScreen   the CD room: browse + play
                             │   or PocketRoomScreen, when the format is POCKET
                             ├─ AddAlbumScreen    import
                             ├─ EditAlbumScreen   fix an album after import
                             ├─ ShelfManagerScreen
                             ├─ ListeningScreen   the play record, from PlayEvent
                             └─ SettingsScreen

WallSceneViewModel ── Room DAOs (library)
                   └─ PlaybackRepository ── MediaController ── PlaybackService
```

Compose owns the 2D screens, SceneView owns the room, and they meet only at the
view model. Playback lives behind a repository and is reached from nowhere else.

---

## `ui/wallscene` — the room

The bulk of the project. Split by *what owns the value*, which is the distinction
that matters here: pure maths files have no Android or Filament types in them and
can be reasoned about, and tested, without a device.

### Pure — no Android, no Filament

| File | Owns |
|---|---|
| `WallLayout.kt` | Where every album sits. Shelf/slot geometry, the pan bounds derived from content, and `nearestCase` — the magnet the wall settles onto. `WallDimens` holds every physical dimension of the room. |
| `OpenSequence.kt` | The five-beat open/close, driven entirely by one scalar `progress` in 0..1. Every curve is a **pure function of `t`** — that is load-bearing, not stylistic: closing runs `progress` backwards through the same curves, which is what makes interruption free and "never two cases mid-animation" structural. A stateful spring would break both. |
| `Scratch.kt` | Turning a finger's rotation about the disc into a position in the album, plus the haptic accumulator. |
| `CaseDiscard.kt` | The crack-and-throw curves for a removed album. |
| `RoomQuality.kt` | `QualityTier` and `FrameWatch` — how much rendering a device gets, guessed then corrected by measuring real frame times. |
| `Patina.kt` | The wear a case shows for how much it has been played. Shared with `ui/listening`, so the shelf and the record can never disagree. |
| `RoomCadence.kt` | How often the room redraws, and how much of it. The 0.5.1 work. |
| `WallSort.kt` | The wall's ordering and its search. |
| `Playhead.kt`, `DeckSheet.kt` | The deck's scrub cursor and its resizable sheet. |

All ten have test files. That is not a coincidence — it is what "pure" is *for*
here, and the way to keep a change testable is to find the value it computes and
move that into this list first.

### The scene

| File | Owns |
|---|---|
| `WallSceneScreen.kt` | **3,087 lines, and the one to be careful in** — one composable runs from line 608 to 2496. The scene graph, every gesture, and the per-frame driver that writes node transforms. Also the browse camera, the zoom, the magnet settle, and the deck's chrome. If a change is about *when* something happens, it is probably here. |
| `RackGrid.kt` | **2,723 lines**, the second file that outgrew being read in one sitting. The effects rack's whole UI: the pad grid, the faders, the segmented ring, the EQ curve editor, the patchbay sockets and the console line. Drawn by hand rather than composed from Material, which is why it is this size. Pairs with `playback/RackState.kt`, which holds what the controls mean. |
| `CaseSlots.kt` | The wall's pool of shelved cases, keyed **by index and never by album** — see trap 1. `bind()` points slot *i* at whichever album is visible there; the frame driver then hovers, turns and tilts it. Also the per-album colour, which the bay's two vitrine lights and its emissive strips both take. |
| `ActiveCase.kt` | The single case that comes off the shelf: tray, lid, disc, and the nodes the open sequence moves. |
| `DeckOverlay.kt` | The in-room deck — tracklist, transport, seek bar, quality readout. Compose, drawn over the scene. |
| `WallSceneViewModel.kt` | The wall's layout and the deck's state; owns playback for whichever case is open. Pan and sequence progress deliberately do **not** live here (architecture rule 3). |

### Look

| File | Owns |
|---|---|
| `RoomLighting.kt` | The key spot, the coloured bounce, and the image-based light — a cubemap **generated procedurally**, not shipped. The hot lamp discs burned into it are what make glossy surfaces shine, since reflections are off. |
| `RoomAtmosphere.kt` | Everything on Filament's `View`: fog, bloom, depth of field, ambient occlusion, vignette, shadow filter. Reads the quality tier. Also where SSR is switched off, with its measurements. |
| `DustGeometry.kt` | The dust cloud's vertices. Every mote animates in the vertex shader from one `time` uniform, so the CPU does nothing per particle. |
| `CaseArt.kt` | Bakes spines, covers and the generated back inlays (tracklist, barcode) to bitmaps. |
| `DiscArt.kt` | Bakes the printed disc face. |
| `CaseArtCache.kt` | Holds the baked artwork per album, and resolves each album's hue — stored, else the cover's dominant hue, else a stable fallback. |
| `RoomHaptics.kt` | The platter's vibration. Goes to the vibrator directly rather than through view haptic feedback; see its KDoc for why that mattered. |

---

## `ui/pocketscene` — the pocket player's room

The second format (`docs/formats.md`), a sibling of the CD room that shares its view model
rather than branching inside it. `JawonNavHost` shows it instead of `WallSceneScreen` when
`AppPreferences.format` is `POCKET` — offered in Settings on debug builds only, for now.

| File | Owns |
|---|---|
| `ClickWheel.kt` | **Pure.** The wheel's geometry (agreeing with `build_pocket()`), which button a point is on, and `WheelGesture` — one finger from landing to lifting, deciding between a turn, a press and a hold the way the classic's wheel did. Also `unproject`, the arithmetic that puts a touch on the glass back into the wheel's plane. |
| `PocketMenu.kt` | **Pure.** `MenuKey` (what a level *is*), `PocketMenus.rows` (what it lists, from a `PocketLibrary` snapshot and a `PocketSettings` one), and `PocketNav` — the stack of levels with each one's highlight and scroll, and its `Saver`. The device's own Settings live here too: About, EQ (Off and the phone's presets), Sound Check (the ReplayGain modes), Backlight, Clicker, and All Settings for the phone app's. |
| `PocketSound.kt` | **Pure.** The rack as the pocket player reaches it — Settings → Sound, each effect a row and each row a slider the wheel turns, as the classic's Brightness screen was. `SoundControl` names each fader, its travel, its step and the rack cell it belongs to; `PocketSound` nudges a fader onto the wheel's grid (so the middle of Speed is found exactly) and reads its value for a small screen. The same faders the CD room's rack draws. |
| `ClickSound.kt` | **Pure** `ClickSound.render`, the click's waveform; and `ClickPlayer`, the static low-latency `AudioTrack` that replays it. |
| `WheelRock.kt` | **Pure.** The ring tipping toward a press and the centre button going down, as critically damped springs integrated exactly — the same path at any frame rate. The room poses the model's `pocket_wheel`, `pocket_print` and `pocket_button` from it. |
| `CoverFlowLcd.kt` | Cover Flow, drawn: the covers in perspective with their reflections on a black floor, and a turned-over cover's tracklist. **Drawn with `android.graphics.Camera` and `Matrix` onto the native canvas**, because the in-world screen is copied into its texture in software (trap 33) and a perspective bitmap draw is certain there; the flow position and the turn come in from the room. |
| `LcdPanel.kt` | The screen's plumbing: a detached `SurfaceTexture`, a Filament stream and an external texture (as `ViewNode` builds them), fed by a host view that draws its Compose child into `Surface.lockHardwareCanvas()` and redraws on any descendant's invalidation. Replaces `ViewNode` for the pocket player's screen — see trap 33 for why. Its teardown is ordered by declaration: the release effect sits before the plane that samples the texture. |
| `PocketLcd.kt` | The device's screen, as Compose: menus with a scrollbar, and Now Playing with its progress, volume and scrubber strips. Sized in LCD pixels, not dp. **Stateless on purpose** — anything animated inside it never reaches the glass (trap 33). The main menu is the classic's split screen: the list on the left, the library's covers drifting and dissolving on the right, on a clock the room ticks at 20fps only while that menu is up and lit. |
| `PocketRoomScreen.kt` | The scene — the device at computed life size, its lights, the screen as a `ViewNode` — and `PocketDevice`, which turns wheel events into menu moves and playback. Read trap 33 before changing how the screen is drawn. |
| `PocketLighting.kt` | The carriage IBL, **placed from the device's pose**: a reading-lamp softbox where the front and the glass reflect, the window where the polished side does, a ring of the carriage's other windows for the rims, ceiling lamps for the chamfers. `radiance()` is pure, so where a light sits can be reasoned about. |
| `PocketAtmosphere.kt` | The room's grade: 4× MSAA, bloom over the HDR threshold, a light vignette, millimetre-scale ambient occlusion — re-applied whenever SceneView's own quality preset undoes it. |
| `PassingLamps.kt` | **Pure.** The lights going past the train *as light*: a pool of four, started on an irregular schedule (exponential gaps, the odd cluster, the odd dark stretch), each running right to left behind you — its glint slides across the glass — or behind the device, rim-lighting its edge. The room writes them onto four `LightNode`s and switches an idle one off by intensity (trap 24). |
| `TiltFilter.kt` | **Pure.** The phone's tilt *from how it is being held*: quaternions from the game rotation vector against a baseline that follows slowly, so any grip is neutral and a phone held upright — where Euler angles lock — is no special case. The room turns the environment and the passing lamps by its inverse, so reflections slide as the phone tilts. The sensor listens only while the room is resumed. |
| `NightWindow.kt` | The window behind the device — where its picture hangs, each layer's depth and cell size (which must agree with `night_window.mat`) — and **pure** `TrainMotion`: the train's drifting speed, each layer's parallax scroll, the carriage's rock and its rail-joint jolt. |

The eight pure pieces have test files (`ClickWheelTest`, `PocketMenuTest`, `ClickSoundTest`, `WheelRockTest`, `TrainMotionTest`, `PassingLampsTest`, `TiltFilterTest`, `PocketSoundTest`).

---

## `data` — the library and playback

| File | Owns |
|---|---|
| `local/JawonDatabase.kt` + entities | Room. `Shelf`, `Album`, `Track`, `PlayEvent`, and their DAOs. **Version 6, with five hand-written migrations** — see below. |
| `local/Migrations.kt` | `MIGRATION_1_2` (resume position, and the `PlayEvent` table), `MIGRATION_2_3` (`Track.artist`, for compilations), `MIGRATION_3_4` (`Track.fetchedLyrics`), `MIGRATION_4_5` (`Track.discNumber`), `MIGRATION_5_6` (`Album.sourceId`, `Album.remoteId`, `Track.remoteId` — where a record lives). Read its KDoc before adding a sixth: there is deliberately no destructive fallback. |
| `remote/SubsonicClient.kt` | Four endpoints against a Subsonic-compatible server: `ping`, `getAlbumList2`, `getAlbum`, and the cover and stream URLs. Every `parse*` is pure and static, which is what makes the JSON shapes testable without a server. **A refusal arrives as HTTP 200** with a nested error — see its KDoc. |
| `remote/SubsonicServer.kt` | The address and credentials, and the salted-token authentication. The password never crosses the wire; the salt is fresh per request, which is why the stream URL changes every time and the cache cannot key on it. |
| `local/SpotifyCleanup.kt` | All that is left of the Spotify route, **removed on 2026-09-21** because Spotify never hands over audio and so the disc, the rack and the scratch had nothing to act on. At launch and after a restore it takes Spotify albums, their emptied shelf and the sign-in tokens off the phone. Deliberately every launch rather than once, because an old backup can bring the albums back. |
| `archive/` | The record shop. `ArchiveClient` searches and reads Internet Archive items; `ArchiveRepository` takes one home. **Archive URLs are permanent and unauthenticated**, which is why this needed no playback changes at all — the URL goes straight into `Track.fileUri`. |
| `cd/` | Reading an audio CD from a USB drive. An audio CD has **no filesystem**, so this is SCSI MMC over USB Bulk-Only Transport: `ScsiCommands` builds the bytes, `CdToc` parses the table of contents and computes the MusicBrainz disc id, `WavWriter` wraps raw sectors, and `CdDrive` is the one untestable part. **Unverified — no drive has been attached yet.** |
| `remote/ServerInvite.kt` | One pasteable string carrying a whole server, so nobody types three fields — and the `-Pjawon.serverInvite` build flag that ships a build already pointed at one. **Obfuscation, not security**: anyone with the link or the APK can read the credentials back out, which is why the account behind it must be read-only and disposable. Read its KDoc before using it for anything. |
| `remote/RemoteLibrarySync.kt` | Walks the server's catalogue into Room — rows and one cover per album, never audio. `merge()` is the careful part: the server is authoritative for the title, artist, year and tracklist, and for **nothing else**. Ids are derived from the server's, so a re-sync updates rather than duplicating and the play history stays attached. |
| `importer/AlbumImportRepository.kt` | Two ways in, both producing one preview *per album found*: `scanTree` walks a picked SAF folder, `scanDevice` queries MediaStore for what the phone already indexed. Grouping is by containing folder. Also `commit`/`commitAll`, and the cover-art lookup for records that carry none. |
| `importer/AudioScanner.kt` | Walks a SAF tree for audio. **It recurses** — that is deliberate now, and used to be a trap. |
| `importer/TrackMetadataReader.kt` | Tags via `MediaMetadataRetriever`, technical detail via `MediaExtractor`. Also pulls embedded cover art. |
| `importer/CoverImageStore.kt` | Copies chosen images into private storage, and removes them when an album goes. |
| `playback/PlaybackRepository.kt` | The `MediaController`. The only thing that talks to the session. |
| `playback/AudioRouteReader.kt` | What the audio is actually coming out of, for the deck's quality readout. |

`playback/PlaybackService.kt` (note: `playback`, not `data/playback`) is the
`MediaSessionService` — the only place ExoPlayer and the `MediaSession` are
constructed.

### `playback` — the audio pipeline

Eighteen files, 2,737 lines, and the part of the project with the best test
coverage: everything here is pure enough to run on the JVM, and most of it does.
Split the same way `ui/wallscene` is — the maths is separate from the plumbing.

| File | Owns |
|---|---|
| `ScratchAudio.kt`, `ScratchTone.kt` | The scratch you can *hear*. Resampling a real record rather than a chipmunk, which took two attempts; `ScratchToneTest` is the largest test file in the project at 27 cases. |
| `DjFilter.kt`, `Echo.kt`, `Drive.kt`, `Varispeed.kt` | The rack's DSP, each a pure function over a buffer with its own test file. |
| `*AudioProcessor.kt`, `FrameCopy.kt` | The Media3 `AudioProcessor` wrappers that put the above into ExoPlayer's chain. The thin, untestable half. |
| `CrossfadeEngine.kt`, `Crossfade.kt` | Album-internal crossfade. |
| `ReplayGain.kt`, `ReplayGainReader.kt` | Tag-driven level matching, clamped at −15 dB. |
| `RackState.kt` | What every rack control currently means. The bridge to `ui/wallscene/RackGrid.kt`. |
| `SleepTimer.kt`, `PlayerVolume.kt` | Small, and exactly what they say. |
| `StreamCache.kt` | The bounded LRU disk cache streamed audio is read through, and the `DataSource.Factory` the player and the crossfade tail share. **Only the network branch is cached** — local `content://` files go straight through, since copying them would be the wrong failure on a phone short of space. This is what makes the scratch seek instantly on a streamed track. |

**Read trap 19 in `ENGINEERING.md` before touching anything that sets
`PlaybackParameters`.** The pitch control was withdrawn because crossing 1.0
repeatedly reconfigures Media3's time-stretcher and wedges the playback thread at
a full core with no progress at all. That is the most expensive lesson in this
package and it is not obvious from the code.

### The database is at version 4, and the migrations are the riskiest code here

This section used to say version 1 with no migrations, and to warn that shipping
without them would lose people's libraries. The migrations now exist —
`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` — and the warning has simply
moved along one step rather than gone away.

**There is deliberately no `fallbackToDestructiveMigration`.** That is the right
call: a shelf someone built and the colours they picked cannot be recovered from
their audio files, so a wrong migration should crash loudly in testing rather than
wipe a stranger's collection quietly. The consequence is that a wrong migration
*ships as a crash loop*, on a phone that cannot downgrade.

**They are now tested** — `app/src/test/.../data/local/MigrationTest.kt`, six cases,
on the JVM under Robolectric.

It does **not** use `MigrationTestHelper`, and the reason is worth knowing before
anyone "fixes" that. The helper needs the exported schema JSON for the *starting*
version, and `app/schemas` holds only 2, 3 and 4 — **`1.json` does not exist and
never did**: `exportSchema` was switched on in the same commit that introduced
version 2 (`88a7936`), so there is nothing in git history to recover. Room computes
a schema's `identityHash` itself, and a hand-written `1.json` would carry an
invented one, which is a worse foundation for a safety net than none.

So the test builds version 1 from raw DDL instead — v2's exported `createSql` minus
exactly what `MIGRATION_1_2` adds — and then opens the migrated result through
**Room's own production path**, which is the code that really validates a migrated
schema against the entities. That last test is the one that matters: it reproduces
the exact failure a user would hit.

**The net was checked rather than assumed.** Breaking `MIGRATION_2_3` on purpose
(making `artist` `NOT NULL DEFAULT ''` instead of nullable) fails three of the six,
including Room's own `Migration didn't properly handle: Track`. A migration test
that only asserts "it did not throw" would have passed that.

---

## Assets

| | |
|---|---|
| `assets/models/*.glb` | `case_tray`, `case_lid`, `disc` for the open case; `case_closed` for the wall (simpler — a shut case shows no interior, and the wall draws hundreds); `beam` for a bay's two vitrine strips (still called `beam`, from when it was an underlight); `disc_shards`, the disc pre-cracked into 27 wedges for the discard animation, and at 451 KB the largest asset in the app. All generated by `tools/models/build_models.py`. See `docs/models.md`. |
| `assets/materials/dust.filamat` | The only hand-written material, compiled by hand. See `docs/materials.md`. |
| `javon-shelf-prototype.html` | The original interaction prototype. Still the source of truth for timing and feel. |

---

## Things worth knowing before you edit

- **`WallSceneScreen.kt` is where complexity accumulates.** It is the only file
  that touches gestures, the scene graph and the frame loop at once, so almost
  every 3D bug leads here. When adding to it, check whether the value belongs in
  one of the pure files instead — that is how `OpenSequence`, `WallLayout` and
  `Scratch` got their shape.
- **The frame driver and `bind()` share the pooled nodes.** Binding decides *which
  album and where*; the frame decides *how it is floating right now*. Anything one
  writes, the other must not stomp — and anything the frame writes, `bind` must
  restore (trap 9).
- **Two effects write to the active case's nodes**, and both are keyed on the model
  instances because the models load asynchronously. A new effect writing to those
  nodes needs the same key, or it runs against null handles and silently does
  nothing.
