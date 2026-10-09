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
| The pocket player's room? | **A train window** — and then **none** (2026-10-03): the phone's display is the device's face | §4; §7, step 5c. |
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
- **Its room: a train window — decided, and retired on 2026-10-03** for the face (§7, step 5c):
  the phone's whole display is the player now, and there is nowhere left to stand it. What
  follows is the record of the room it had, kept for what it learned. The device fills the
  screen, so the room is what lies behind and around it. Proposed: **at night**, like the rest of Jawon — the dark window,
  lights outside streaking past as out-of-focus bokeh, and **their reflections sliding across
  the device's glass and its polished back**. That last part is the room: the passing lights
  are its lighting, and a glossy object under moving light is the most convincing thing a
  renderer can show. Day or night is the owner's call.
- **The room around it — decided (2026-09-27).** The owner asked for the background, the
  environment and the atmosphere next — "animations, movements, little wiggles, levitations,
  camera movements, some magical effects, some realistic moody elements like lights and
  windows" — and that it **not look cheap**. Three calls, made that day:
  - **A classic night train**: dark wood table and panelling, a warm brass reading lamp,
    deep-coloured velvet seats, curtains. The moodiest of the three offered.
  - **The train follows the music**: pause, and it slows into a station — platform lamps holding
    still, lighting the device from one side — and play pulls it out again.
  - **Two states.** *In hand* is the device as it is: life size, still, the phone *is* the device
    (the sway note in `PocketRoomScreen`). *At rest* — the phone laid flat, or twenty seconds
    untouched — the camera eases back into the carriage and the device lifts off the table and
    floats in the lamp's light, its shadow moving beneath it; a touch, or picking the phone up,
    flies it back into the hand. The motion the owner asked for lives in the second state and the
    move between them, so it never fights the hand holding the phone.
  **Not cheap** is read as: no lens flares, sparkles or glow for its own sake; every effect
  something light or motion really does; springs and noise, never a visible loop; each piece
  measured on the phone as it lands. Built in four phases (§7, step 5a).
  **Two more, the same day:** the device wears **earphones** — "if its pocket player it should
  be connected to a wire … just to make it more realistic" — a **white** cord, the classic's,
  from a plug in the top edge; and **switching between formats becomes a showroom**: pinch out in
  any room and the camera pulls back into a dark gallery, each format on its own pedestal under a
  spotlight — swipe between them, tap one to fly into its room. Chosen over objects hidden in
  each room and over a menu row, because it scales as the formats arrive and it is a moment, not
  a setting. It replaces Settings → THE ROOM as the way between rooms.
  **And four from his reference photos, later the same day** — flat lays of iPods on linen, denim
  and blankets, mostly silver classics with white wheels, EarPods and white cords, soft or warm
  light. Asked rather than guessed, because they reversed built work: **its colour is chosen in its
  own Settings** — silver, black and red, white later — "which one you bought" (`PocketFinish`,
  silver by default); **at rest it lies flat, seen from above**, and lifts off only when picked
  up — the float is replaced; **it lies on a knitted blanket** on the train table; and **the cord
  keeps running off-frame**, the earbuds never seen. He offered more references on request, and
  looking them up is cleared too.
  **The blanket became a linen sheet (2026-09-28)**, on his brief the next day — "fix the visual
  issues … I don't mind if you change the visual look … give the most reasonable and professional
  looking end product; do research" — after the first flat lay drew three complaints: the cord
  wiggled, the wood under it shone like no wood does, and the scene was not aesthetic, its lights,
  reflections and shadows not agreeing. The research was a search for iPods photographed on
  bedding, and it came back overwhelmingly as **crumpled cream cotton and linen sheets under warm,
  low light** — the knit was the minority, and drawn as a normal map on a flat sheet it read as a
  printed pattern however it was drawn. So the sheet is **crumpled by a cloth simulation** in the
  model script (`build_sheet()`, `docs/models.md`), and what was wrong with the light is fixed at
  its root rather than tuned round:
  - **The cord is never simulated in the move** (`CordRig`): a simulation settling on cloth froze in
    zigzags or never stopped moving, and dragged from the sheet to your ears as the device was picked
    up it shivered for a second and a half — the owner saw it the same day. It lies on the sheet's own
    surface (`SheetSurface`, the simulated sheet's height map) in one eased curve, hangs in the hand in
    the chain's own settled shape, and eases between the two, the end at the plug first and the far end
    trailing, on springs that cannot overshoot.
  - **One lamp, everywhere.** The carriage's environment is placed for the hand, so on the table
    its softbox lit the sheet from the bottom right while the lamp's shadow fell from the top right
    and the glass reflected a ceiling light neither knew of. Now the environment turns as the device
    goes down (`RestLight`) — to show the resting device the room it sees in the hand, then swung
    45° so the softbox sits at the frame's upper right — and the lamp moves to hang where the
    softbox lands. Measured on the emulator: the face is brighter toward the lamp (192 at its top
    right, 162 at its bottom left), the glass's sheen sits in its top right corner, and the shadow
    falls down and to the left.
  - **Soft shadows** (PCSS), from a lamp whose cone narrows at rest so the shadow map's texels
    are spent on the frame — at the hand's width the device's shadow came out in steps.
  - **Three times the fill at rest**: at the hand's level the lamp did nine-tenths of the
    lighting and the flat lay read as one spotlight in a black room.
  Depth of field was tried and left off: it softened the sheet beautifully and broke the wheel's
  hairlines into dots.
  **The sheet became a still life (2026-09-29).** Shown the linen sheet, he found the device lying
  on it "not aesthetic enough" and asked for a better setting and better animation; offered four
  directions, he picked **a still life seen from above**: the table's white linen, pressed into
  creases (`build_cloth()`, `docs/models.md`), the camera over it widened so the device lies right
  of the frame's middle with the window's sill along the top, and room on the left for what is to
  come — a glass of tea in a metal holder with its spoon, cropped by the frame's edge, and a paper
  ticket whose destination is the album playing; then life at rest (steam drifting with the train,
  the tea trembling, the spoon rattling as the train brakes into a station) and a set-down with
  weight (a settle, a soft tap, a haptic). **The cloth and the props are in** (`build_tea()`,
  `build_ticket()`, `docs/models.md`): the glass in its holder, placed by a Cycles render from the
  room's own rest camera before anything reached the app, and the ticket, drawn by the room
  (`TicketArt`) from the album playing. The holder first rendered as a black mug — the carriage's
  environment, turned for the table, has the night window where the cloth should be — and wears a
  material of its own now that reflects the cloth (`table_metal.mat`, `docs/materials.md`), which
  is also how it came out that Filament turns the whole frame it shades in with the environment.
  Life at rest and the set-down's weight are next.
  **Inspected frame by frame (2026-09-29)**, on his ask — "you will see some animation flaws" —
  from a 40-second screen recording on his phone: every frame's timing, and every frame of the two
  flights and the page slides laid out as contact sheets. What it found, and what was done:
  - **The set-down hovered.** One critically damped spring drove the flight, and a spring's last
    tenth is an exponential tail: the device hung 2cm over the cloth and sank for two more seconds;
    picked up, it was home while the camera still rose, so the window slid in behind it for a
    second after. Now the camera's share and the device's each run on a clock of their own for a
    fixed time, eased (`RestFlight`) — down in 1.2s, up in 0.55s with both arriving together — and
    nothing moves after.
  - **The light pumped.** The lamp, its cone, the ambient fill and the environment's turn were
    eased with the device, which lags the camera going down: with the cloth already in view the
    lamp was still widening and brightening, so the table brightened past its resting level and
    dimmed back for a second after landing, and the shadow slid for reasons of its own. They follow
    the camera now; the device comes down through a light that has stopped, and its shadow comes
    to meet it as a real one does.
  - **The flight stuttered.** Drawn at every vsync, the room over the cloth costs more than 8ms, so
    frames came 8, 16, 8, 16ms apart — an uneven 60-to-120 where the move is fastest. The flight is
    drawn at an even 60 now; only a thumb on the wheel draws at 120.
  - **The cord hooked and swept.** Easing node by node from the hand's shape toward a resting curve
    laid afresh from the moving plug, it bent into a hook above the plug going down and, coming up,
    ran down across the device's face. It **drapes** now (`CordRig`): the resting curve laid once,
    where the device will land, the cord hanging from the plug to where it touches it, and the
    swing out of the hand's shape over before the camera can see behind the device. The rig was
    filmed frame by frame off the device, from above and from the side, before it went near the
    phone.
  - **Pages hesitated.** The title changed on the press while the list, eased in, sat still for
    50ms. It leaves at once now and slows into place.
  - **Every click froze the room for 42–67ms** — fixed on 2026-10-09, and it was the click. A
    system trace on the owner's phone (`atrace` with `sched` and `binder_driver`, read as text —
    there is no trace processor on this machine and none is needed) put the main thread asleep in
    a synchronous binder call to `audioserver` on every step: `AudioTrack.play()` starting the
    output that the static track had let go when its last click ended. 3.8ms with the phone on
    silent; with a speaker to wake, the 42–67ms. `ClickPlayer` now does all of it on a thread of
    its own at audio priority and the wheel only posts; traced again, the main thread made no
    call to `audioserver` in an eight-step turn and a step's touch handling fell from 5.2ms to
    under 1.8ms.
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
   **Relit for the phone (2026-09-27), by fixed luminance boxes on the owner's S25 Ultra.** The
   first environment was a ring of window, and the device read as a flat black slab: a flat face
   reflects one small patch of the world, and nothing bright was in it. `PocketLighting` now
   places each light from the device's own pose — the front and the glass reflect ~60° round and
   ~0.2 rad up, so the reading lamp is a softbox there; the polished left side reflects the window
   behind the device; a dimmer ring of the carriage's other windows keeps the wheel's rim and the
   chamfers lit from every side, which is most of what separates a dark device from a dark room.
   The front went from a flat 16–20 of 255 to a gradient, ~40 beside the screen falling to ~12 at
   the foot. Three things learned: dimming the window to a fifth took the side from 35 to 3 — it
   is what gives the edges their sheen; a vignette at a midpoint of 0.42 took the top of the
   window from 23 to 3; and **no light can put a readable glare on the screen's glass** without
   flooding the rough front first, which a pure-red probe showed plainly (`PocketLighting`'s
   note). `PocketAtmosphere` is the room's own grade: 4× MSAA, a restrained bloom, a light
   vignette, and ambient occlusion at a 3.5mm radius for the wheel's recess and the screen's
   inset. The screen itself reads as a backlit panel now: a cool never-quite-white, a falloff to
   its corners, and the window lip's shadow along its top and left edges.
   **And an outline (2026-09-26, on the AVD):** the steel comes round the edge as a polished bead
   0.8mm across, and the ceiling is two runs of light traced down the carriage rather than a ring
   of points — worked out from where each part of the bead looks, so a line of light runs along
   every visible edge and the lower corners no longer dissolve into the dark. The steel wears its
   own material so those lines stay lines instead of breaking into dashes (ENGINEERING.md, trap 34).
   Not yet judged on the phone. **And judge the lower face on the phone, never the AVD**: at
   rest the AVD renders the front plate beside the wheel at ~2 of 255 where the phone's last
   screenshot read 13, with a gentler slope all the way down (44 → 2 against 32 → 7). A red
   probe showed the reflection geometry is the same on both — the lower face looks at eye level
   behind your right shoulder, where the carriage has little that is bright — so the difference
   is in how the two render it, and lighting tuned there would be tuned to the wrong device.
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
   the right — on a clock that ticks at 20fps only while that menu is up and lit. **And pages
   slide**, as the classic's did (2026-09-27): select, and the new page comes in from the right
   and pushes the old one off to the left in 260ms; MENU runs it back. The title bar stays put
   and only its words change; a highlight moving within a page is the same page, and turning a
   cover over is Cover Flow's own page (it turns, it does not slide) — `PocketNav.page`, tested.
   **The cost is measured now**: Cover Flow moving put the main thread at 0.71 of a core while
   `ViewNode` copied the screen in software, and 0.24 once `LcdPanel` drew it on the GPU (trap 33).
7. **The wheel — done (2026-09-26), bar the feel.** Turning, with acceleration on long lists;
   the centre; MENU (held: the main menu); Play/Pause (held: off, the screen dark until any
   button); the sides (held: cue through the track); on Now Playing the volume, and the
   scrubber on a press of the centre. Each step clicks and ticks, and at the end of a list both
   stop. The ring tips 0.3mm toward a press, pivoting on its far edge, and the centre button
   goes down 0.25mm; the backlight goes out after ten seconds. Checked end to end on the flagship AVD. **What the emulator cannot answer** is the
   feel: the click's latency (it has no fast mixer), the haptic, and whether 15° a step is the
   right size under a real thumb — all of that is the owner's phone. **The click's path is
   confirmed on it (2026-09-27)**: `dumpsys media.audio_flinger` lists the click as fast-mixer
   track `F3` — static, 48kHz mono, 576 frames, sonification, to the speaker — the lowest-latency
   route Android has; each click is mixed in about 5ms after its routing patch and released when
   it ends, so nothing is held open between clicks. The feel itself is the owner's to judge.
   **Step 2's spike answered itself along the way:** a `ViewNode` does render a moving
   screen, once it is told to (trap 33) — the cost per redraw is still unmeasured.
8. **Now Playing, and a way to the rack and the lyrics — mostly done (2026-09-26).** The
   lyrics are the classic's own route: the centre's third press on Now Playing, after the
   scrubber, follows timed words line by line and lets the wheel scroll them; they are asked
   for only while that page is up, which keeps LRCLIB out of the background. The *sound* is
   reached the classic's way too, through the device's own Settings — EQ over the phone's
   presets and Sound Check as Jawon's ReplayGain modes, beside Backlight and Clicker. **And the
   rest of the rack** — filter, speed, drive, echo, bass, width, reverb — is Settings → Sound: a
   row each with its reading, a slider each that the wheel turns, the classic's Brightness
   screen with Jawon's rack behind it. Touching an effect switches its cell in; Flat puts them
   all back.
5a. **The room around the device (planned 2026-09-27, §4).** In four phases, each checked on the
   owner's phone and measured before the next — the idle GPU clock is 342–443MHz after the
   ambient occlusion went to half resolution, so there is room, and the CD room's fog showed how
   fast it goes:
   1. **The two states and a blockout of the carriage** — the camera pulling back, the table and
      the window frame, the device levitating with a real shadow from the reading lamp, and the
      flight between the table and the hand, reversible from any point. **Built (2026-09-27) and
      running on the owner's phone**: `PocketRest.kt` (the state, the flight, the float, the
      put-down detector — tested), `carriage.glb` (walnut table, mahogany boards, frame, sill —
      `build_carriage()`), and a shadow-casting reading lamp over the window's corner at 15,000cd,
      which is what this room's exposure asks of a lamp that pools on dark wood. Lying on the
      desk, the phone went to rest by itself and floated the device over the table. **Cost at
      rest**: 0.50 of a core, GPU 443–525MHz, against 342–443 in the hand. **And the earphones**:
      a white plug in the top edge's jack (`build_earphone_plug()`) and a simulated cord from it
      (`PocketCord.kt`) that rises out of the frame in the hand and sways over the table at rest.
      Two things it took: white plastic in a room exposed for polished metal renders grey, so the
      cord and plug have a light channel of their own with one light, from the reading lamp's
      direction; and a touch on a phone lying flat must not send the device straight back to the
      table — lying flat is disarmed by a touch until the phone is lifted and put down again.
   2. **The glass and what passes it** — rain and grime (a hand-written material, `docs/realism.md`
      §3), the carriage and the device's screen reflected in the pane, and events outside:
      tunnels, bridges, a level crossing, showers, and **stations**, tied to pause and play.
      **Stations built on 2026-09-27, on the AVD**: the train follows the music. Paused, it brakes
      into a station — placed ahead exactly where the brake ends, so the platform stops at the
      window — on the S-curve a driver brakes to, speed `1 − smoothstep`: no jolt going in, none
      as it stands, ten seconds from speed (a critically damped spring was first, and still crept
      twelve seconds on). Played, it pulls away over nine; paused just after leaving, it stops
      alongside the same platform, as a real train would. Standing, the carriage is still — the
      sway and the joints scale with speed — after one settle on its springs. The window draws the
      platform (`docs/materials.md`), and its cold fluorescent light comes in at the window, a
      spot over the window's top at 8,000cd that lays a cool cast on the walnut. Opening the room
      with nothing playing finds the train already standing. The kinematics were checked on the
      AVD end to end by log (departure 0.4 → 17.4m/s in ~9s, the album ending, a stop 10.3s later
      with the platform's middle at 0.0); **the look of the stop and the moving platform is still
      the owner's to judge on his phone**, as is the cost of the platform's shader (A/B on the AVD
      moved nothing measurable).
      **Tunnels, the same day, on the AVD** (`NightLine`): a few hundred metres long, a couple of
      kilometres apart, the first a way down the line so the room never opens in one. Inside, the
      window is the wall with its lamps streaking by, the carriage's ambient dips by 30%, the
      night's lamps stop and the tunnel's sweep through instead, one per lamp the window passes:
      bright from behind the device — on the table and the device's edges, which is where they
      read at rest — and at the night's own level from behind you, because at 5,000cd those
      washed the whole black front beige in the hand. A pause in a tunnel runs out of it first
      and stands at a platform clear of the mouth; a tunnel the stop would run into is taken off
      the line before it is reached, where nobody can see it go. Seen through on the AVD with the
      first tunnel moved to 40m for the test; the lamps' beat is still his to judge.
   3. **Light, and the small magic** — the screen's glow spilling onto the table in the colour of
      what it shows, a reading lamp that switches off with a tap, dust in its beam, passing
      lights sweeping the whole carriage.
   4. **Motion and the bake** — the carriage's sway under the camera at rest, the float on springs
      and noise, wiggles on a track change and a landing, tilt parallax, a drag to look round, and
      Cycles baking the interior's bounce light and shadow into lightmaps.
5b. **The showroom — the way between formats (planned 2026-09-27, §4).** A scene and a destination
   of its own (`ui/showroom/`), reached by **pinching out of a room**: in the CD room, pinching on
   past the camera's far limit (`ZOOM_FAR`) — you pull back out of the room into the gallery — and
   in the pocket room, a pinch, which nothing else there uses. A dark gallery: a glossy black
   floor, a row of plinths each under its own shadow-casting spot, and on each the format's object
   hovering and slowly turning — a jewel case, the pocket player, and for vinyl and cassette an
   empty plinth under a dim light with its name and *coming*. The camera starts close on the room
   you left and eases back; a horizontal drag carries it along the row, released it springs to the
   nearest plinth with a haptic tick per plinth (`ShowroomCarousel`, pure and tested); a tap on a
   built format pushes the camera in, fades, and opens that room. Back returns to the room you
   came from. The format's name sits under it in the app's own type. Once it works on the phone it
   replaces Settings → THE ROOM.
   **Built on 2026-09-27, on the emulator — the phone was off** (`ui/showroom/`, `docs/architecture.md`).
   Both rooms pinched out into it (`PullOut`): the CD room gave 8cm past `ZOOM_FAR` against a spring
   and darkened, and let go with the fingers a third closer again; the pocket room takes two fingers
   together, which the wheel then never sees. Only where there is somewhere else to go — a release
   build with one finished room pinches exactly as before.
   **Changed on 2026-10-03, on the owner's report**: zooming out in the CD room to see more of the
   wall kept throwing him into the showroom. The CD room's pinch is its zoom and nothing else now.
   The way out of either room is **Back from its top level** — no case open, or the main menu —
   and in the CD room **a tap on the neon sign** too, the shop's name as its door; the pocket room
   keeps its pinch, which nothing else there uses. **The showroom is the app's top**: Back from it
   leaves the app (to the background, as a root activity's Back does), because Back going back into
   the room would make the two a circle with no way out. The CD's plinth holds the held case with
   **the record played last** in it; the pocket player's screen is **switched on**, its own main menu
   on `LcdPanel` with the same record beside it — dark, it barely read on a dark plinth. Vinyl and
   cassette are empty plinths under dim light, marked *coming*. The four things the look cost, in
   the order they were found, measured as screenshots and luminance boxes:
   - **Black materials returned nothing at this exposure.** Black lacquer plinths and a black stone
     floor rendered as a void: a surface this dark is seen only by what it reflects, and a spot's
     reflection off a horizontal cap goes away from the camera. Charcoal satin and polished
     concrete, which a spot can show.
   - **A shadow straight under a floating object reads as a stand.** With the spot in front, each
     exhibit's shadow fell directly beneath it as the camera sees it — a black block it sat on. The
     spots come from the front right now, and the camera sits lower, so the gap under the float
     shows; a ceiling glow in the environment fills the shadow, which was otherwise a hole.
   - **A face turned to a camera above it reflects a direction *below* level.** The first strips
     behind the camera stopped just above it and the black anodised front never caught anything; a
     doorway-sized softbox filled the CD's clear lid at once and laid a grey veil over the cover.
     Tall strips either side, from well below eye level: as an exhibit turns, a sheen slides across
     it. And the lid, authored at 22% alpha for the CD room, went milky under a spot this strong —
     the showroom sets its alpha to 5%, which keeps its reflections and drops the veil.
   - **Black all round has no depth.** A plaster wall behind the row, with a warm wash on it behind
     each plinth: the exhibit stands against a glow, and the wash rims it from behind.
   **Startup, measured on the AVD**: the showroom's first frame arrives ~1.25s after it composes —
   models, SceneView's setup and first-use shader compilation. It opens black and is revealed as the
   camera starts back, so the arrival is a move that is seen; the environment's 393k directions are
   worked out off the main thread (a quarter of a second it used to block). Going into a room costs
   the same again for that room. **The real fix is an engine that outlives a destination** — every
   room rebuilds Filament from nothing, which is also the black the NavHost's return fade masks.
   **Not yet**: checked on the phone, its cost measured, floor reflections, and Settings → THE ROOM
   retired.
5c. **The phone is the player (2026-10-03).** Asked to "rethink the mode's UI completely … research
   and choose the best one", with prototypes to choose between: every iPod-style app that shipped
   (Rewound, ClassiPod, RetroPod, Elvin Hu's) fills the phone with the face, and the one that drew a
   small device drew reviews asking for the full width. The device in its room put a 44mm screen and
   a 39mm wheel on a 73mm display. Four prototypes went to the owner, from that to the face alone,
   and **he chose the face**: the device's front, running past every edge of the display, the window
   across the top and the wheel where a thumb rests.
   - **What it is**: `pocket_face.glb` (`build_pocket_face()`, `docs/models.md`), the device's
     parts under their own names so the finish, the wheel's rock and the screen bind as they did,
     laid out at life size on the owner's S25 Ultra and scaled uniformly to any other display
     (`PocketFace.fit` — by the width, or the height on a wide one). The screen is 58.9 × 43.7mm
     and the wheel 51mm, against the device's 44.3 × 32.9 and 39. The camera looks straight at it;
     the wheel's touch maps through the face's own centre and radius onto `ClickWheel`'s.
   - **The room is the studio the face reflects**, and nothing else is drawn
     (`tools/models/pocket_light.py` and `PocketLighting.kt`, pinned together by
     `PocketLightingTest`): a softbox overhead and a brighter zenith, a warm lamp low on the left, a
     cool window on the right, a dim floor, and a band of lit room behind the viewer — and **nothing
     bright between 3.3° and 11° above level**, the band the screen's glass looks back at straight
     on, which is why the menu carries no glare. The phone's tilt turns it (`TiltFilter`), so a
     turned hand slides the sheen across the face. Designed and measured in Cycles at the app's
     camera and exposure (`tools/pocket-preview/`): the silver face 137 at its foot to 160, black
     24–37, red 31–38, the wheel 167–181 on silver and red, 48–67 on black.
   - **What went**: the carriage and its window, the train and its stations and tunnels, the rest
     state and its flight, the earphones and their cord, the tablecloth, the tea and the ticket —
     with `night_window.mat`, `table_metal.mat` and the soft shadows they needed. **What stayed is
     everything a thumb touches**: the wheel and its clicks, the menus, Cover Flow, Now Playing and
     its lyrics, the device's Settings and Sound, the three finishes, the backlight, and the pinch
     out to the showroom, where the whole device still stands (`pocket.glb`, `PocketObject`).
   **On the phone since 2026-10-09** (a release build with `-Pjawon.showFormats=true`): the face
   renders as the Cycles previews said it would, the wheel's touch mapping lands — an injected
   eight-step turn moved the highlight row by row and stopped at the end of the list — and Cover
   Flow and the showroom's exhibit both work. **The cost was not under the train's**: idle on the
   main menu it took 0.60 of a core (Filament 0.30, main 0.16) with the GPU 85% busy, against the
   carriage's 0.54 — because it was still drawn at 60 a second for a picture that, untouched,
   changes only when the screen does or the phone tilts. It is now drawn only then (the room's
   `onFrame`, `LcdPanel.framesDelivered`, `PocketDevice.changedSinceDrawn`).
   **Still the owner's to judge**: the look, and whether a 51mm wheel suits his thumb.
9. **Vinyl** (§5), then **cassette** (§6), each its own room and built the same way.

Each of 3–8 is about a session's work. **Measure every tier as it lands** (traps 28–30).

**First measured on the owner's S25 Ultra, 2026-09-27** (release build, thermal status 0, on USB):
the pocket room idle on its main menu — window drifting, slideshow ticking — costs **0.54 of a
core** (Filament's thread 0.36, main 0.10), about the CD room's own. It is drawn at **60fps unless
a thumb is on the wheel** (`RoomCadence`, reused), and the GPU clock is the measurement that shows
why: **342–525MHz idle at 60 against 734–967MHz at every vsync** with a finger held. Busy stays
~80% either way — the governor trades clock for it, which is trap 29 in another form — so read
the clock, not the percentage. `dumpsys SurfaceFlinger --latency` reports nothing for this layer
on this Android, BLAST or not.
The lowest tier has never been the one anybody needed, but every new room is a new scene to
pay for.

**Still open, across all of it:** the pocket player's name;
how the rack and the lyrics are reached from it; vinyl's room and its sides; cassette's player.
