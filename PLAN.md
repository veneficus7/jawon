# CRATE — Product Definition & Build Plan (v2, ground-up)

> Supersedes all earlier ENGINEERING.md / DESIGN.md / milestone docs. The previous
> Android project is deleted; this is the plan for building from zero.
> `crate-shelf-v2.html` in this repo is the **visual and interaction spec** —
> when a behaviour is ambiguous, the prototype is the source of truth.

---

## 1. What CRATE is

A local-first Android music player that replaces the list with a **room**.

You browse a wall of shelves holding CD jewel cases, spines out, reading titles
off the spines the way you read book spines. Tap one and it physically comes out:
the case slides from its slot, turns to face you, the lid swings open, and the
disc lifts out and spins up into playback. Grab the spinning disc and turn it and
you scrub the audio, forward or back, like a DJ platter.

The library is **entirely the user's own**. Nothing ships pre-loaded. Users add
their own audio files, name their own shelves, and upload two images per album:
the **case cover** (front of the jewel case) and the **disc face** (the printed
label on the CD itself).

**The thesis:** streaming apps made music infinite and weightless. CRATE makes a
personal collection feel like it occupies space again — finite, arranged,
physical, and yours.

## 2. What CRATE is not

Scope discipline matters more than feature count here. CRATE is **not**:

- a streaming client — no catalogue, no accounts, no recommendations
- a library manager competing on bulk-editing power
- a social app
- a general-purpose player optimised for shuffling 40,000 tracks

The album is the atomic unit. If a feature only makes sense for shuffling
individual tracks across a huge library, it probably doesn't belong.

---

## 3. Core model

```
Shelf  ──1:N──▶  Album  ──1:N──▶  Track
```

- **Shelf** — a named, ordered section of the wall. "Alternative Rock",
  "Sunday Morning", "Dad's Jazz". Has a label plate rendered on the shelf's
  front edge. Albums live on exactly one shelf; moving is allowed, duplication
  is not.
- **Album** — one jewel case. Owns a case cover image, a disc face image, a
  spine appearance, and an ordered track list.
- **Track** — one audio file, with technical metadata (codec, sample rate, bit
  depth, bitrate) captured at import for the quality readout.

Shelves are the organising idea and the differentiator — they're playlists you
can *see the shape of*. A shelf that's half empty looks half empty.

---

## 4. Screens & flows

1. **The Wall** (home) — pan across shelves, read spines, tap to open.
2. **The Deck** (playing) — spinning disc, transport, track list, quality
   readout, scratch-to-seek.
3. **Add Album** — pick an audio folder, confirm detected tracks, upload case
   cover + disc face, assign to a shelf.
4. **Shelf Manager** — create, rename, reorder, recolour shelves; move albums.
5. **Settings** — playback options, audio output, appearance/room style.

**The empty state is the most important screen in the app.** A new user sees an
empty wall. It must read as "your shelves, waiting" rather than "broken app" —
one labelled empty shelf, warm lighting, a single obvious way to add the first
album.

---

## 5. Tech stack

### The central decision

Everything you now want — real shadows, depth of field, volumetric light, fog,
dust, an iridescent disc, a hinged case opening in perspective — is a
**real-time 3D scene**. Jetpack Compose cannot render this. `graphicsLayer` does
2D transforms with fake perspective; it has no depth buffer, no lights, no
render targets, no post-processing. This was the ceiling we kept hitting.

**Use Filament** — Google's real-time PBR renderer, the same one behind Sceneform
and ARCore. It is the right tool and it is not a compromise:

| You asked for | Filament gives you |
|---|---|
| Depth of field | `View.setDepthOfFieldOptions()` — built in |
| Glow / bloom | `View.setBloomOptions()` — built in |
| Real fog | `View.setFogOptions()` — built in |
| Real shadows | `LightManager` cast/receive, PCF & VSM soft shadows |
| Ambient occlusion | `View.setAmbientOcclusionOptions()` — SSAO built in |
| Iridescent CD | custom material in Filament's material language |
| Realistic reflections | IBL from an environment map (`cmgen`) |
| Smoothness | designed for 60fps on mobile; ~4MB added to APK |

**Recommended entry point:** `io.github.sceneview:sceneview` — a Compose-friendly
Filament wrapper that handles engine lifecycle, the surface, and glTF loading, so
you don't write hundreds of lines of boilerplate before seeing a triangle. It
exposes the underlying Filament `Engine`, so you can drop to raw Filament APIs
for the custom disc material without switching frameworks later.

### Full stack

- **Language / UI** — Kotlin, Jetpack Compose for all 2D UI
- **3D** — Filament via SceneView, embedded in Compose with `AndroidView`
- **3D assets** — jewel case, shelf, and disc modelled in **Blender**, exported
  as glTF. Do not build this geometry procedurally in code — bevels, the hinge,
  plastic thickness and the tray are far easier to get beautiful in Blender, and
  the file becomes something you can iterate on visually.
- **Custom materials** — Filament material language (`.mat`). Compile ahead of
  time with `matc` in a Gradle task, or at runtime with `filamat-android`.
- **Playback** — Media3 / ExoPlayer + `MediaSessionService` (background, lock
  screen, bluetooth controls)
- **Database** — Room
- **Images** — Coil for loading user covers, then uploaded into Filament textures
- **Files** — Storage Access Framework for folder picking; covers copied into
  app-private storage
- **Async** — Coroutines + Flow
- **DI** — Hilt

### Honest assessment of difficulty

The 3D layer is roughly **two to three times the work of the entire 2D app**, and
Filament has a real learning curve — it's a low-level renderer, not a game engine
with a scene editor. That's why the milestone order below front-loads a complete,
shippable 2D player. If the 3D proves harder than expected, you still have a
working music player rather than half a renderer.

---

## 6. Data model

```kotlin
@Entity data class Shelf(
    @PrimaryKey val id: String,
    val name: String,           // "Alternative Rock"
    val order: Int,             // position on the wall
    val accentHue: Float,       // label plate + lighting tint
    val createdAt: Long
)

@Entity data class Album(
    @PrimaryKey val id: String,
    val shelfId: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val caseCoverUri: String?,  // user-uploaded: front of jewel case
    val discFaceUri: String?,   // user-uploaded: printed label on the CD
    val spineHue: Float,        // derived from cover, user-overridable
    val slotIndex: Int,         // position within its shelf
    val playCount: Int,
    val addedAt: Long
)

@Entity data class Track(
    @PrimaryKey val id: String,
    val albumId: String,
    val trackNumber: Int,
    val title: String,
    val durationMs: Long,
    val fileUri: String,
    // captured at import, drives the quality readout
    val mimeType: String,       // audio/flac, audio/mp4, audio/mpeg…
    val sampleRateHz: Int,
    val bitDepth: Int?,         // null for lossy
    val bitrateBps: Int
)
```

---

## 7. Audio quality readout — what Android can actually tell you

You asked for "16-bit lossless, wireless or wired". Here's what's genuinely
available, and one caveat worth knowing before you design the UI around it.

**From the file / decoder** — read ExoPlayer's `Format` on the active track:
- `sampleMimeType` → codec (FLAC, ALAC, WAV, AAC, MP3, Opus…)
- `sampleRate` → 44100, 48000, 96000, 192000
- `pcmEncoding` → bit depth
- `bitrate`, `channelCount`
- **Lossless** is inferred from the codec: FLAC / ALAC / WAV / APE are lossless,
  AAC / MP3 / Opus / Vorbis are lossy.

**From the output route** — `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`, then
`AudioDeviceInfo.getType()`:
- `TYPE_WIRED_HEADPHONES` / `TYPE_WIRED_HEADSET` → Wired
- `TYPE_USB_HEADSET` / `TYPE_USB_DEVICE` → USB DAC
- `TYPE_BLUETOOTH_A2DP` → Bluetooth
- `TYPE_BUILTIN_SPEAKER` → Speaker

**Bluetooth codec** — `BluetoothCodecStatus` (API 28+, needs `BLUETOOTH_CONNECT`)
gives SBC / AAC / aptX / aptX HD / LDAC. This is the detail audio people
actually care about, and almost no player surfaces it.

**The caveat:** Android's audio mixer resamples nearly everything to the device's
output rate — often 48 kHz. So a 24/96 FLAC is usually *not* reaching your ears
at 24/96 unless you're on a USB DAC in exclusive mode. Compare
`AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` against the source rate
and show both honestly:

```
FLAC 24-BIT / 96 kHz  ·  LOSSLESS
LDAC · BLUETOOTH  →  OUTPUT 48 kHz
```

Showing "24/96" alone when the system is resampling would be a lie, and the
audience for this feature is exactly the audience that would notice. Rendered in
the monospace VU-meter type, this readout becomes a signature detail rather than
a spec dump.

---

## 8. Milestone plan

### Phase 1 — a real music player, no 3D

- **M0 · Skeleton.** Project setup, module structure, Hilt, Room, Compose
  navigation shell. Nothing visible yet.
- **M1 · Import.** SAF folder picker → scan audio files → `MediaMetadataRetriever`
  for tags → group into albums → persist. Capture codec/rate/depth per track.
  Cover upload flow for both case cover and disc face.
- **M2 · Shelves.** Create, rename, reorder, recolour. Assign and move albums.
  Empty states throughout.
- **M3 · Playback.** ExoPlayer + `MediaSessionService`. Notification, lock screen,
  bluetooth buttons, audio focus, becoming-noisy, gapless, queue, quality readout.
- **M4 · 2D UI complete.** A plain but properly designed Compose interface:
  shelf list, album grid, player, track list, settings.

> **⇢ Checkpoint: at the end of M4 you have a finished, usable, shippable music
> player.** Everything after this is the experience layer. Do not start Phase 2
> until M4 genuinely works, because Phase 2 is where the risk lives.

### Phase 2 — the room

- **M5 · Filament bootstrap.** SceneView inside Compose, engine lifecycle tied to
  the composable, load a Blender-authored jewel case, render it lit. Prove the
  pipeline end to end with one object.
- **M6 · The wall.** Shelf geometry, many cases instanced, user cover textures
  applied to spines and case fronts, pan/fling browsing, shelf label plates.
- **M7 · Open & close.** The five-beat sequence forward and reverse: pull, turn,
  open, lift, spin — and back into the slot. Interruptible at any point.
- **M8 · The disc.** Iridescence material, spin bound to playback state,
  scratch-to-seek with haptics.
- **M9 · Atmosphere.** Lights, shadows, IBL, bloom, depth of field, fog, dust
  motes. Tune against the prototype's exported values.
- **M10 · Polish & release.** Performance pass, device/GPU fallbacks, app icon,
  onboarding, release config.

---

## 9. Recommended additional features

Ordered by how much they reinforce the core idea per unit of effort.

1. **Shelf label plates** — a typed/engraved card on each shelf's front edge, like
   a record-shop divider. Makes shelf naming *visible in the world* rather than
   buried in a settings screen. Cheap, high impact.
2. **Haptics on the scratch** — a subtle tick per rotation increment while
   scrubbing. This is what will make the platter feel real on a touchscreen, and
   it's a handful of lines with `HapticFeedbackConstants` / `VibrationEffect`.
3. **Now-playing case left out** — while music plays, that album's case sits
   proud of its slot. Browse away and you can still see what's on. Solves a real
   navigation problem *with the metaphor* instead of a persistent mini-player bar.
4. **Back-of-case liner notes** — flip the case to see the tracklist printed on
   the back inlay. Could replace the flat track list entirely, and is the single
   most "CRATE" idea in this list.
5. **Gapless playback + album-first ethos** — no cross-album shuffle. Shuffle
   only within an album, if at all. A deliberate stance that matches the product.
6. **Patina from play count** — heavily played cases show slight spine wear and
   scuffing. Your listening history becomes visible on the shelf. Purely
   decorative and quietly wonderful.
7. **Flip-to-letter jump** — a fast scrub along the wall that snaps to alphabet
   dividers, like flipping a crate with your thumb.
8. **ReplayGain / volume normalisation** — reads existing tags, matters for
   people with ripped libraries.

Suggestions 4 and 6 are the ones I'd fight to keep if scope has to shrink — they
do work no other music player is doing.

---

## 10. Decisions — LOCKED

| # | Decision | Rationale |
|---|---|---|
| 1 | **minSdk 29** | Scoped storage settled (one code path, no legacy paths). `BluetoothCodecStatus` needs 28+. Test device is a Galaxy S25 Ultra — flagship, so GPU is not a constraint during development, but M10 must add quality tiers for lower-end hardware. |
| 2 | **Procedural geometry, no Blender** | Every object in CRATE is a box or a solid of revolution — the easiest possible category. SceneView ships geometry primitives. Downloaded models are actively worse here: a stock jewel case won't have lid and tray as separate meshes, so it couldn't hinge open. |
| 3 | **Unlimited shelves, content-derived bounds** | Wall grows with content in both axes. Scroll extent = occupied slots + one empty slot of margin, so the user physically cannot pan into void. Only cases near the frustum are instantiated. |
| 4 | **One shelf per album** | A CD is a physical object; it cannot be in two places. Moving allowed, duplication not. |
| 5 | **One room: Midnight Shop** | Iridescence and bloom read strongest against deep warm black; a dark room suits night headphone listening; cheap on OLED. See `docs/DESIGN.md`. |

---

## 11. Tooling

### Required

- **Android Studio** — the IDE.
- **SceneView's source and samples** — read before writing any 3D code: the real
  API, instead of guessed method signatures.
- **Git + GitHub** — non-negotiable. Commit at every milestone boundary, so a bad
  day is one `git reset` away.
- **A physical device** — the Galaxy S25 Ultra over USB or wireless debugging.
  The emulator's GPU is not representative; Filament performance must be judged
  on real hardware.

### Later, when needed

- **Poly Haven** (polyhaven.com) — free CC0 HDRIs. One warm indoor HDRI gives the
  disc and case plastic realistic reflections via image-based lighting. This is
  the single highest-value asset download in the project, and it isn't a model.
- **Filament tools** (`matc`, `cmgen`) — from the Filament GitHub releases.
  `matc` compiles custom `.mat` materials ahead of time; `cmgen` converts an HDRI
  into the IBL format Filament wants. Only needed at M8/M9.
- **Google Fonts** — Archivo (variable, has a width axis), Inter, JetBrains Mono.

### Explicitly not needed

- Blender, Maya, or any 3D modelling tool
- AI 3D generators (Meshy, Tripo, Rodin) — they produce organic meshes, which is
  the opposite of what a rectangular jewel case with a working hinge needs
- Sketchfab or other model marketplaces
- Unity, Godot, or any game engine

---

## 12. Where to start

1. Create the Android Studio project (Empty Activity, Compose, Kotlin, minSdk 29).
2. `git init` and make the first commit before anything else.
3. Copy `ENGINEERING.md`, `docs/`, and `prototype/` into the project root.
4. Read SceneView's samples.
5. Open `prototype/crate-shelf-v2.html` in a browser once more and tune the
   sliders until the motion feels right, then hit **Copy Kotlin values** and
   paste the result into `docs/TUNING.md`. Do this now, while the prototype is
   fresh — those numbers are the starting point for M7 and M8.
6. Start M0. Plan first; don't write code yet.
