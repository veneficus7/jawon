# Formats — a pocket player, vinyl, and cassette

**Status: planned, not started (2026-09-21).** The four decisions that shape it were
made by the owner on that date and are recorded in §2. Items still marked **Open** are
his to settle, each before the step that needs it.

Since the first version of `ENGINEERING.md`, "vinyl/cassette/iPod modes" sat under *needs a
decision first* for one stated reason: *three more rooms before one is finished.* On
2026-09-21 the owner called the CD room finished — "i have no more ideas to add to this
mode" — which removes that reason. This is what building the next three means in this
codebase.

---

## 1. A word first: *format*, not *mode*

The app already has four **modes** — Vesper, Glacies, Vigilia, Officina (`RoomScheme`) —
and a mode is the light. Calling vinyl a mode as well would put two unrelated switches
under one word, in Settings and in the code, and the first report saying "I changed the
mode" would not say which.

So: a **format** is the medium the library is handled as — CD, pocket player, vinyl,
cassette. A **mode** stays the light.

---

## 2. Decided, 2026-09-21

| Question | The owner's answer | What it means |
|---|---|---|
| Which format first? | **The pocket player** (iPod-style) | Built before vinyl and cassette. §4. |
| One switch, or a format per album? | **One switch for the whole library** | Choose a format and every album is handled that way. No schema change. |
| How is vinyl browsed? | **Crate-digging, from the start** | Records upright in bins, flicked through — not face-out on a wall. §5. |
| The same shop, or a room per format? | **A room of its own for each format** | **Reverses the locked "one room" decision** (`ENGINEERING.md`). The CD room stays exactly as it is; each new format gets its own. |
| Do the four light modes reach the new rooms? | **No** — "other rooms are separate" | Vesper · Glacies · Vigilia · Officina are the CD room's own. Each new room is lit its own way. |
| The pocket player's room? | **A train window** | §4. |
| Its controls? | **The iPod's, as closely as a touchscreen allows** | He has never used one; the point is the experience people remember as satisfying. §4 sets out what that experience was. |
| Its name? | **Later** | Not "iPod" either way (§4). |
| The visual bar? | **"Dont hold back at all … hyper realism and beauty … the highest quality of 3d graphics and animation"** | For every new room, and for animation as much as for stills. §4 sets out what that means for this one. |
| May the renderer change? | **Yes** — "we can switch renderer and tools if current doesn't achieve what we want and more" | Filament stays for the CD room. For the new rooms the renderer was **opened and then evaluated on evidence**: keep Filament here, upgrade it before considering an engine change. The audit is `docs/realism.md`. |

The last one is the biggest, and it makes the work *safer* rather than harder: nothing
about the CD room has to be made generic.

---

## 3. What a format is made of, and what the rooms share

Every row here is something the CD room has and each new room needs its own version of:

| Layer | The CD room | Where |
|---|---|---|
| Size and spacing | 142×125×10mm case in a 150mm bay — the case's *turning circle* | `WallDimens` |
| What you browse | `case_closed.glb` + three art plates, from an index-keyed pool that never destroys a node | `CaseSlots` (trap 1) |
| What you hold | tray, lid on a hinge, disc | `ActiveCase`, `case_tray/lid.glb`, `disc.glb` |
| The sequence | pull · turn · open · lift · spin, 1.9s | `OpenSequence` |
| The play surface | a platter under the finger, 6s of audio per turn, audible scratch | `Scratch`, `ScratchAudio`, `ScratchTone` |
| Artwork | spine, cover and back inlay; the printed disc face | `CaseArt`, `DiscArt` |
| Deletion | the disc breaking into 27 pieces | `CaseDiscard`, `DiscardScene`, `disc_shards.glb` |
| Models | the Blender script, committed `.glb` | `tools/models/build_models.py` |

**Because each format has a room of its own, none of that is touched.** `WallSceneScreen`
is ~3,200 lines inside one composable, and the alternative — a `when (format)` through all
of it — is how a finished room gets broken. A new room is a sibling, not a branch.

What the rooms share is everything underneath the scene, and all of it already lives in
files of its own:

- **The library and playback** — `Shelf → Album → Track`, `PlaybackRepository`, the
  listening record, lyrics, the rack, backup. A format is a way of *handling* a record, not a
  different library, and the album stays the atomic unit in every one (PLAN §2).
- **The view model.** `WallSceneViewModel` already holds the library, playback, the deck, the
  rack and lyrics. A second room can use it as it stands; splitting it can wait until two
  rooms actually pull it in different directions.
- **The rendering kit** — `RoomLighting`'s environment, `RoomGrade`, `RoomAtmosphere`,
  `RoomQuality`, `RoomCadence`, `RoomDiagnostics`, `RoomHaptics`. Reused by call, not copied.

**Switching format is already cheap to apply.** Leaving the room destroys the SceneView,
and returning rebuilds it from nothing (`7de6b33`), so a format chosen in Settings takes
effect on the way back — no mid-session teardown, and no brush with trap 1.

---

## 4. The pocket player — first

- **One object holds the whole library**, so there is no wall. Browsing happens on the
  device's own screen: albums as a flow of covers, and **your shelves as its playlists**, in
  the order you arranged them, so the curation survives even here. Pick an album, then a
  track, then Now Playing — the album is still the unit.
- **Life size fits, and that is the design.** A classic pocket player is about 104×62mm; the
  S25 Ultra's screen is about 73mm wide (6.9″ at 19.5:9). At 1:1 the device fits across the
  screen with a little to spare, and its wheel, about 40mm across, lands under a thumb exactly
  as the real one did. The phone
  *becomes* the player instead of showing a small one in a room — which argues for a camera
  close on the device, with its room falling away in depth of field behind it.
- **The controls are the iPod's — decided.** The owner has never used one; he wants back the
  experience people remember as satisfying, so faithfulness is the brief, not reinterpretation.
  What the click wheel did, to be checked point by point against Apple's own iPod classic user
  guide before step 6 is built — that manual is to this room what `javon-shelf-prototype.html`
  is to the CD room:
  - **Turning** moves the highlight one item per step — clockwise is down — and **accelerates**
    when spun fast through a long list.
  - **Every step clicks**, through the device's own tiny speaker. That click is most of what
    people mean by "satisfying", and on a phone it is a sound and a haptic tick together, locked
    to the step, with no lag at all.
  - **Centre** selects. **MENU**, at the top, goes back one level. **Play/Pause** at the
    bottom; held, it switches the player off. **Next** and **Previous** at the sides; held, they
    fast-forward and rewind within the track.
  - **On Now Playing, turning sets the volume.** One press of Centre brings up the scrubber and
    the wheel scrubs instead, falling back to volume after a few idle seconds.
  - **Cover Flow:** turn to flip through the albums; Centre turns the cover over to show its
    tracks.
  - On the phone: the finger's angle about the wheel's projected centre, which is exactly how
    the platter already works — `Scratch.angleDelta` has solved the wrap at ±π. The four compass
    points are taps, with a heavier press haptic than a step's tick.
- **The screen is a live in-world texture, and that is the one real technical risk.**
  `ViewNode` already renders Compose onto a plane in the CD room — the shelf label plates —
  so the mechanism is proven, but only for content that never moves. A cover flow animating
  under a thumb re-renders that texture every frame. Measure it first (trap 13: CPU per
  thread, because gfxinfo cannot see the room). If it is too expensive, the fallback is a 2D
  Compose layer drawn in place over the device's glass.
- **Not called iPod, and not shaped like one.** "iPod" is Apple's trademark and the
  product's look is Apple's. Its own name, proportions and face, with no Apple marks, keep
  the interaction without borrowing the brand. Not legal advice — check before shipping, the
  way `docs/server.md` treats what may be served. **Open:** the name.
- **Its room: a train window — decided.** The device fills the screen, so the room is what
  lies behind and around it. Proposed: **at night**, like the rest of Jawon — the dark window,
  lights outside streaking past as out-of-focus bokeh, and **their reflections sliding across
  the device's glass and its polished back**. That last part is the room: the passing lights
  are its lighting, and a glossy object under moving light is the most convincing thing a
  renderer can show. Day or night is the owner's call.
- **The bar is "as realistic and high quality as possible" — decided.** What that means here:
  - **It is seen closer than anything in the CD room.** At life size, filling the screen, every
    seam, bevel, the wheel's slight recess and the screen's inset glass is on show, so the model
    is built to real tolerances rather than to a shelf's distance.
  - **Real materials for each part** — anodised metal front, mirror-polished steel back, a
    matt wheel, a glass screen over a backlit display — one object per material, the lesson
    `build_turntable()` already learned.
  - **Normal maps may be needed**, for brushed or anodised grain. The stock materials cannot do
    them (`ENGINEERING.md`, custom materials), so this may be the first new hand-written `.mat`
    since `dust.mat` and `fog.mat`. Raise it when it comes, per architecture rule 4.
  - **The budget is spent differently.** The CD room draws hundreds of cases; this draws one
    device and a window. Per-pixel quality — render scale, MSAA, even screen-space
    reflections, which are off in the CD room at a 7× cost — can be reconsidered for this room
    alone, measured per traps 28–30.
- **The deck** — **Open**. The CD room's deck (TRACKS · SOUND · LYRICS) is a 2D sheet over
  the scene. On this device Now Playing and the tracklist belong on its own screen; the SOUND
  rack and LYRICS need a way in that does not turn the player into a phone app in costume.

One thing to design knowing: this is the device that made music weightless — "a thousand
songs in your pocket" — which is the opposite of the thesis in PLAN §1. It belongs here as
the tactile object it was, and it is a nostalgia object rather than a collection one.

---

## 5. Vinyl — crate-digging

The case for vinyl, whenever it comes:

1. **The scratch is already a vinyl gesture.** `ScratchAudio` and `ScratchTone` were tuned
   to sound "like a record, not a resampler" (`9ed28f6`). On an LP it stops being a metaphor.
2. **The sequence maps beat for beat:** pull the sleeve · turn it to face you · slide the
   record out · lower it onto the platter · drop the needle.
3. **The existing turntable is too small to reuse.** `build_turntable()` is a 235mm portable
   with a 172mm platter, a 7-inch single's size; an LP is 302mm across. What carries over is
   the approach that made the prop read as real — spun aluminium with anisotropy, a felt mat
   to be matt against it, a chromed arm, one object per material.

What vinyl can do that a CD cannot:

- **Real speed.** At 33⅓ rpm a turn is 1.8s of audio against the CD's chosen 6s, so a hand
  can move exactly as it would on a deck. Try both — 1.8s may be too twitchy on a phone.
- **The tonearm is the seek.** A real LP shows its tracks as bands; needle on a band chooses
  that track, and the arm's angle *is* how far through the side you are.
- **The grooves are the "no rainbow, yes anisotropy" decision done properly**, with the label
  as `DiscArt` already bakes a disc face, at 100mm on a 302mm record.
- **Sides.** Digital albums carry none, so they would be cut at the track boundary nearest
  half the running time, no more than ~22 minutes a side. **Open:** is flipping the record
  required, offered, or off?

**Browsing, decided: the crate.** Records stand upright in bins, and a flick tilts them
forward one at a time, the front one showing its sleeve. That is a new gesture, a new camera
and a new pool — none of the CD wall's panning carries over, though its detent haptics and
its never-destroy pool pattern (trap 1) do. The crate draws hundreds of sleeves edge on, so
measure its tier cost as it lands. **Open:** its room.

---

## 6. Cassette

- **The object:** a Norelco box, about 110×70×17mm, with a J-card; inside, a shell with two
  hubs and the tape visible between them.
- **The player** — **Open**, because they are different objects: a hi-fi tape deck with a
  door and transport keys, or a Walkman in the hand.
- **The reels are the progress bar, literally.** Tape leaves one hub and fills the other, so
  the two radii say where you are in the side without a line of UI.
- **Seeking is FF and REW**, held, with the cue squeal. This is *not* a reuse of the scratch:
  `ScratchAudio` decodes a few seconds either side of the playhead, and ten or twenty times
  speed eats that window in a fraction of a second. It needs a decoder that streams ahead —
  the cursor-and-rate idea carries over, the buffering does not.
- **No instant track jumps** is the format's character. **Open:** honour it, or keep the
  tracklist as a shortcut.
- Sides again, with auto-reverse as the obvious option.

---

## 7. Staging

One thing at a time, checked on the owner's phone between each (the working agreement):

1. **The format switch — done (2026-09-26).** `Format` (CD, POCKET), a preference, and
   `JawonNavHost(format)` choosing the room; CD shows `WallSceneScreen` untouched. The picker is
   in Settings → THE ROOM in **debug builds only** until a second room is finished, so an
   unfinished one is never a tap away on a release build. The pocket room's first pass
   (`ui/pocketscene/`) sits behind it: the device at computed life size under its own carriage
   IBL. Checked on the flagship AVD, both ways — into the pocket room and back to the CD room.
2. **Spike: a live screen on a 3D object.** A bare plane carrying a `ViewNode` with a list
   scrolling at full frame rate, measured per thread against the room at rest, on the S25
   Ultra and on the lowest device to hand. Decides whether the screen is in-world or drawn
   over the glass (§4). **Smaller than it looked**: `ViewNode` already renders Compose through
   a `SurfaceTexture` → `Stream` → `Texture`, the path video takes, so what is being measured
   is the cost of the Compose redraw, not of getting pixels into the scene
   (`docs/realism.md` §1.4).
3. **The bake pipeline** — Cycles AO, normal and curvature bakes added to the committed model
   script. Proven headless on this machine at 2.7s for a 1024² AO map, so it is a day's work,
   not a project, and every later model benefits (`docs/realism.md` §1.5).
4. **The device model** — body, wheel, centre button, glass and bezel, at real size — in
   `build_models.py`, with bounds checks added to `docs/models.md` the way the case's are.
5. **Its room** — the train window (§4), lit by the lights going past
   (`docs/realism.md` §3). **The window is up (2026-09-26)**: `night_window.mat`, the night
   out of focus at three depths with poles and a rocking carriage, behind the device. **And
   the lights going past are light**: a key spot for the carriage's ceiling lamp, and a pool of
   lamps on an irregular schedule whose glints slide across the glass and whose light rims the
   edge (`PassingLamps`). First tuning on the AVD: a lamp behind you at 900–2600cd washed the
   whole anodised face bronze (16 -> 60 of 255), so it runs at 380–1100cd; the key light took
   the wheel from 10 to 14 and its print from 32 to 51. **Judged on the phone, not here.**
   **And the phone's tilt turns the world** (`TiltFilter`): the environment and the passing
   lamps rotate against how far the phone has just been tilted, so reflections slide across
   the glass and the edge — first-order, twice the tilt, as a turned mirror's do. The window
   behind is left still on purpose: for a phone turning about its own middle, a world-fixed
   picture behind it barely moves, and making it swing would be the stylised parallax of a
   wallpaper, not a device in the hand.
6. **Browsing on its screen — done (2026-09-26).** Cover Flow first on the main menu — the
   covers in perspective on a glossy black floor, eased to the wheel, a cover turned over by
   the centre to list its tracks on the back — then Albums (filed as iTunes filed them, "The"
   and "A" set aside), Shelves as playlists in their wall order, Artists, an album's tracks,
   and Now Playing. `PocketMenu.kt`, `CoverFlowLcd.kt`. The main menu is the classic's split
   screen — the list on the left, covers from the library pushed in, drifted and dissolved on
   the right — on a clock that ticks at 20fps only while that menu is up and lit. **Cost unmeasured**: the flow redraws
   the screen in software every frame it moves (trap 33's copy), which the phone has to answer.
7. **The wheel — done (2026-09-26), bar the feel.** Turning, with acceleration on long lists;
   the centre; MENU (held: the main menu); Play/Pause (held: off, the screen dark until any
   button); the sides (held: cue through the track); on Now Playing the volume, and the
   scrubber on a press of the centre. Each step clicks and ticks, and at the end of a list both
   stop. The ring tips 0.3mm toward a press, pivoting on its far edge, and the centre button
   goes down 0.25mm; the backlight goes out after ten seconds. Checked end to end on the flagship AVD. **What the emulator cannot answer** is the
   feel: the click's latency (it has no fast mixer), the haptic, and whether 15° a step is the
   right size under a real thumb — all of that is the owner's phone.
   **Step 2's spike answered itself along the way:** a `ViewNode` does render a moving
   screen, once it is told to (trap 33) — the cost per redraw is still unmeasured.
8. **Now Playing, and a way to the rack and the lyrics — mostly done (2026-09-26).** The
   lyrics are the classic's own route: the centre's third press on Now Playing, after the
   scrubber, follows timed words line by line and lets the wheel scroll them; they are asked
   for only while that page is up, which keeps LRCLIB out of the background. The *sound* is
   reached the classic's way too, through the device's own Settings — EQ over the phone's
   presets and Sound Check as Jawon's ReplayGain modes, beside Backlight and Clicker. **Still
   open: the rest of the rack** (filter, echo, drive, varispeed) has no way in from this room.
9. **Vinyl** (§5), then **cassette** (§6), each its own room and built the same way.

Each of 3–8 is about a session's work. **Measure every tier as it lands** (traps 28–30).
The lowest tier has never been the one anybody needed, but every new room is a new scene to
pay for.

**Still open, across all of it:** the pocket player's name, and night or day at its window;
how the rack and the lyrics are reached from it; vinyl's room and its sides; cassette's player.
