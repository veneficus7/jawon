# The code, and where things live

A map, so a reader can find the right file without reading the tree. 51 Kotlin
files; the 3D room is about 6,100 lines of them and everything else is small.

`ENGINEERING.md` holds the traps and the locked decisions. This is only *where things
are*.

---

## The shape of it

```
MainActivity → JawonNavHost ─┬─ WallSceneScreen   the room: browse + play
                             ├─ AddAlbumScreen    import
                             ├─ ShelfManagerScreen
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

### The scene

| File | Owns |
|---|---|
| `WallSceneScreen.kt` | **2,000 lines, and the one to be careful in.** The scene graph, every gesture, and the per-frame driver that writes node transforms. Also the browse camera, the zoom, the magnet settle, and the deck's chrome. If a change is about *when* something happens, it is probably here. |
| `CaseSlots.kt` | The wall's pool of shelved cases, keyed **by index and never by album** — see trap 1. `bind()` points slot *i* at whichever album is visible there; the frame driver then hovers, turns and tilts it. Also the per-album underlight colour. |
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

## `data` — the library and playback

| File | Owns |
|---|---|
| `local/JawonDatabase.kt` + entities | Room. `Shelf`, `Album`, `Track`, and their DAOs. **Version 1 with no migrations** — see below. |
| `importer/AlbumImportRepository.kt` | Scanning a picked folder into one preview *per album found in it*, and committing them. Grouping is by containing folder. |
| `importer/AudioScanner.kt` | Walks a SAF tree for audio. **It recurses** — that is deliberate now, and used to be a trap. |
| `importer/TrackMetadataReader.kt` | Tags via `MediaMetadataRetriever`, technical detail via `MediaExtractor`. Also pulls embedded cover art. |
| `importer/CoverImageStore.kt` | Copies chosen images into private storage, and removes them when an album goes. |
| `playback/PlaybackRepository.kt` | The `MediaController`. The only thing that talks to the session. |
| `playback/AudioRouteReader.kt` | What the audio is actually coming out of, for the deck's quality readout. |

`playback/PlaybackService.kt` (note: `playback`, not `data/playback`) is the
`MediaSessionService` — the only place ExoPlayer and the `MediaSession` are
constructed.

### The database is at version 1 and has no migrations

Fine today, because nobody has shipped. **The moment real users exist, any schema
change needs a migration or their libraries vanish on update.** The pending work
that touches the schema — play history for stats, and anything per-album — is
worth doing *before* launch for exactly this reason.

---

## Assets

| | |
|---|---|
| `assets/models/*.glb` | `case_tray`, `case_lid`, `disc` for the open case; `case_closed` for the wall (simpler — a shut case shows no interior, and the wall draws hundreds); `beam` for the underlight. All generated by `tools/models/build_models.py`. See `docs/models.md`. |
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
  restore (trap 5).
- **Two effects write to the active case's nodes**, and both are keyed on the model
  instances because the models load asynchronously. A new effect writing to those
  nodes needs the same key, or it runs against null handles and silently does
  nothing.
