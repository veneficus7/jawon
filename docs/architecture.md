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

**Since 2026-10-03 the phone is the player** (`docs/formats.md` §7, step 5c): the room draws the
player's face over the whole display, straight on — no carriage, no table, no still life. What
was built for those (the rest pose, the cloth, the cord, the ticket, the tea, the train) is in the
history before that date.

| File | Owns |
|---|---|
| `ClickWheel.kt` | **Pure.** The wheel's geometry (agreeing with `build_pocket()`), which button a point is on, and `WheelGesture` — one finger from landing to lifting, deciding between a turn, a press and a hold the way the classic's wheel did. Also `unproject`, the arithmetic that puts a touch on the glass back into the wheel's plane. |
| `PocketMenu.kt` | **Pure.** `MenuKey` (what a level *is*), `PocketMenus.rows` (what it lists, from a `PocketLibrary` snapshot and a `PocketSettings` one), and `PocketNav` — the stack of levels with each one's highlight and scroll, and its `Saver`. The device's own Settings live here too: About, EQ (Off and the phone's presets), Sound Check (the ReplayGain modes), Backlight, Clicker, and All Settings for the phone app's. |
| `PocketSound.kt` | **Pure.** The rack as the pocket player reaches it — Settings → Sound, each effect a row and each row a slider the wheel turns, as the classic's Brightness screen was. `SoundControl` names each fader, its travel, its step and the rack cell it belongs to; `PocketSound` nudges a fader onto the wheel's grid (so the middle of Speed is found exactly) and reads its value for a small screen. The same faders the CD room's rack draws. |
| `ClickSound.kt` | **Pure** `ClickSound.render`, the click's waveform; and `ClickPlayer`, the static low-latency `AudioTrack` that replays it — **on a thread of its own**, because starting it is a blocking call into `audioserver` that froze the room on every click while it ran on the main thread. |
| `WheelRock.kt` | **Pure.** The ring tipping toward a press and the centre button going down, as critically damped springs integrated exactly — the same path at any frame rate. The room poses the model's `pocket_wheel`, `pocket_print` and `pocket_button` from it. |
| `CoverFlowLcd.kt` | Cover Flow, drawn: the covers in perspective with their reflections on a black floor, and a turned-over cover's tracklist. **Drawn with `android.graphics.Camera` and `Matrix` onto the native canvas**, because the in-world screen is copied into its texture in software (trap 33) and a perspective bitmap draw is certain there; the flow position and the turn come in from the room. |
| `LcdPanel.kt` | The screen's plumbing: a detached `SurfaceTexture`, a Filament stream and an external texture (as `ViewNode` builds them), fed by a host view that draws its Compose child into `Surface.lockHardwareCanvas()` and redraws on any descendant's invalidation. Replaces `ViewNode` for the pocket player's screen — see trap 33 for why. Its teardown is ordered by declaration: the release effect sits before the plane that samples the texture. `framesDelivered` counts frames as they reach the texture, which is how the room knows the screen has something new to draw. |
| `PocketLcd.kt` | The device's screen, as Compose: menus with a scrollbar, and Now Playing with its progress, volume and scrubber strips. Sized in LCD pixels, not dp. **What it shows is decided by the room; how it moves between pages lives here** — pages slide as the classic's did, the animated state being the page and never the screen (its note says why). The main menu is the classic's split screen: the list on the left, the library's covers drifting and dissolving on the right, on a clock the room ticks at 20fps only while that menu is up and lit. |
| `PocketRoomScreen.kt` | The scene — the face (`pocket_face.glb`) scaled to the display, square on at life size; the key light; the screen on `LcdPanel`'s plane, drawn at 2× — and everything the screen shows, assembled from the library and the player. Read trap 33 before changing how the screen is drawn. |
| `PocketDevice.kt` | The device's behaviour: turns wheel events into menu moves and playback, and holds what the room animates from them — the backlight, the wheel's rock, the tilt — and the song just asked for, which stands in on Now Playing until the player names it. Touches reach the wheel through the face's own projection, in the classic's millimetres (`toWheel`). |
| `PocketFace.kt` | **Pure.** The face's numbers — `pocket_face.glb`'s, for the S25 Ultra's display — and `fit`, which scales it to any other: by the width, or by the height on a display wider for its height. Tested in `PocketFaceTest`, against the model's exported numbers and every common phone shape. |
| `PocketObject.kt` | The pocket player as an object, for the showroom's exhibit: `pocket.glb`'s screen and its polished steel. |
| `PocketFinish.kt` | **Which one you bought**: silver, black and red, chosen in the device's own Settings (Colour) and stored by name. Only factors on four parts' own materials — the front's colour, roughness and metalness, the wheel, the button, the print — so the wear maps are the same on every finish, on the face and on the showroom's exhibit alike. Silver's front is part-metal: a pure metal shows only what it reflects, and read black. |
| `PocketScreens.kt` | What the screen shows, from where the menus are, what is playing and the library: `lcdScreenFor`, `lyricsView`, `pocketLibraryOf`. **Pure**, and tested in `PocketScreensTest`. |
| `PocketLighting.kt` | The light the face is seen by — a room round a phone held in the hand, **placed for what a face seen straight on can show**: you, dark, so the glass never glares; a ceiling light above and behind you whose glow fades down the face; a dim band of you below the glass's angles, for the dark finishes; a lamp, a window and the zenith for the bevels and the wheel. One of two copies: `tools/models/pocket_light.py` is the other, and `PocketLightingTest` pins this one to its numbers. |
| `PocketAtmosphere.kt` | The room's grade: 4× MSAA, bloom over the HDR threshold, a light vignette, millimetre-scale ambient occlusion — re-applied whenever SceneView's own quality preset undoes it. |
| `TiltFilter.kt` | **Pure.** The phone's tilt *from how it is being held*: quaternions from the game rotation vector against a baseline that follows slowly, so any grip is neutral and a phone held upright — where Euler angles lock — is no special case. The room turns the environment by its inverse, so reflections slide across the face as the phone tilts. The sensor listens only while the room is resumed. |

The pure pieces have test files (`ClickWheelTest`, `PocketMenuTest`, `ClickSoundTest`, `WheelRockTest`, `TiltFilterTest`, `PocketSoundTest`, `PocketScreensTest`, `PocketFaceTest`, `PocketLightingTest`).

---

## `ui/showroom` — the way between formats

A destination of its own, `JawonDestination.Showroom` (`docs/formats.md` §7, step 5b): a dark
gallery with a plinth per format, reached by **Back from a room's top level** (no case open in
the CD room, the main menu in the pocket player), by a tap on the CD room's neon sign, or by two
fingers pinched together in the pocket room — wherever `Exhibit.reachable` says there is somewhere
else to go. Choosing a plinth sets `AppPreferences.format` and pops back to `Wall`, which is
whichever room the format now says. **The showroom is the app's top**: its own Back leaves the app
(`moveTaskToBack`), or Back would only go round between it and the room. The CD room's pinch never
leaves it any more — it caught the owner's zoom (2026-10-03).

| File | Owns |
|---|---|
| `Exhibit.kt` | **Pure.** What stands on the plinths, in order — every format the app is going to have, the unbuilt ones as *coming* with no room behind them — which may open (the rule Settings' picker follows), and whether the showroom is reachable at all. |
| `PullOut.kt` | **Pure.** Pinching out of a room: past the room's own limit, the pinch's log-span accumulates against a spring, and far enough on it lets go, once. The pocket room's alone since 2026-10-03, where no other gesture uses two fingers; the CD room's pinch is its zoom and nothing else. |
| `ShowroomCarousel.kt` | **Pure.** The row under the finger: one-to-one while held, a rubber band past either end, a flick that always moves on, a critically damped settle, and one haptic detent per plinth crossed. |
| `ShowroomCamera.kt` | **Pure.** `ShowroomStage` (the model's numbers) and the camera's three framings — hero, travel, close — blended by the director's springs; and `lookAngles`, a lens aimed from one point at another. |
| `Wander.kt` | **Pure.** A never-repeating drift, smooth value noise over time — the exhibits' float. Tested, with `lookAngles`, in `ShowroomFloatTest`. |
| `ShowroomDirector.kt` | The frame loop and the touch: the carousel, the springs, the exhibits' float (`Wander`, never a loop), the arrival that waits for the scene and is revealed from black, and the push into a room that ends in black. |
| `ShowroomScreen.kt` | The scene: the gallery model, a shadow-casting spot and a wall wash per plinth, the CD's case (the held case's tray and lid, holding the record played last), the pocket player (its steel, and its screen lit with its own main menu on `LcdPanel`) and the vinyl's seven-inch single (`vinyl_single.glb`: the same record on its sleeve and its label, its faces in `vinyl.mat`, dressed in a `SideEffect` because `apply` runs before anything is printed), the captions, and the black. |
| `ShowroomLighting.kt` | The gallery IBL, placed for what the exhibits' faces reflect — two strips behind the camera, a ceiling glow for fill, slots and a low wall wash; computed off the main thread. `radiance()` is pure and tested. |
| `ShowroomAtmosphere.kt` | Its grade: 4× MSAA, bloom over the threshold, a heavier vignette, centimetre-scale AO. |
| `ShowroomViewModel.kt` | The format and its switch, and the record the exhibits wear (`lastRecord`: its cover for the case and the single's sleeve, its name for the single's label). |

Tested in `ShowroomTest` (the row, the pinch, the plinths, the camera) and `ShowroomLightingTest`.

---

## `ui/intro` — the opening

**One sequence, not two** (0.7.6). Until then the system's splash wrote the mark in 0.9s, cut to a
different black, and a second card faded a wordmark up and away — testers called it "two intros,
and both of them fast". Now the splash is the first beat of one opening: `MainActivity.takeOverSplash`
takes the system's splash in hand (an exit listener), the card under it draws the same mark in the
same place, and the splash is taken away with nothing on the screen changing. Then the tube catches,
light laps the rim and runs down the J into the hole, the name arrives, and the way out is through
the hole — it opens onto the room.

| File | Owns |
|---|---|
| `IntroTimeline.kt` | **Pure.** Every beat as a function of milliseconds, split into two clocks (`beat`, `exit`) so a tap reaches the way out from any moment; `MarkPath`, the mark's line as something a light can run along; and `splashGlow`, the splash animator's breath again in code, so the card picks the glow up where the splash left it. |
| `ui/vinylscene/` | **The vinyl room** (`docs/formats.md` §5), a sibling of the CD and pocket rooms on the same view model and player. Begun 2026-10-11, finished 2026-10-18. `VinylDeck.kt` is the pure half: `VinylDimens` (the deck's numbers, twins of the model script's `V_*`), `VinylArm` (the turn that puts the *stylus* — not the tube — on a groove), `Side`/`Sides` (an album cut between tracks into two sides, or four, as evenly as its tracks allow) and `VinylBands` (a side as roughness along its radius). `VinylCamera.kt`: two shots from straight above that are one zoom — the whole deck with the sleeve laid out, and the record wider than the phone — and the same lens run backwards to put a finger on the table (`onPlane`). `VinylLighting.kt`: one lamp, as a casting spot *and* painted into the environment, because the record's streak is a reflection of it; and the lit ceiling over the deck, without which metal seen from above is black. `VinylArt.kt`: the labels, a side's band map, the sleeve's front and its back — the back is the tracklist, laid out by `SleeveBackLayout` so that drawing a line and finding it with a finger are the same arithmetic. `VinylRoomScreen.kt`: the scene, with `VinylStage` as its frame loop — the platter turns under a held record (that is what a slipmat is for), the record is the scratch at exactly 1.8s a turn through the same `ScratchAudio` as the CD's disc, the arm sits over the place being heard. `vinyl.mat` is the record's surface. **The crate** is two more files. `Crate.kt` is pure: `Crate` (the library as things standing in a bin — records, a tabbed card per shelf, the card at the back that adds one; where a hand may rest; where a sideways swipe goes), `CrateLayout` (how everything stands as a function of the one number that says which thing is being read — both piles counted from the wood they lean on — and the ring that makes thing *i* always sleeve *i mod 24*) and `CrateMotion` (a hand going through it: dragged it is where the finger is, let go it comes to rest *on* a record on a spring that does not come back). `CrateScene.kt` is what is drawn and touched: `CrateTextures` (a small cover per sleeve of the pool, three large ones for the record being read and its neighbours, a label per card — allocated once, written as the crate is gone through) and `VinylCrate` (the pools' nodes, the frame, the ticks, a finger). `VinylStage` carries the camera between the two places on one spring and hands a touch to whichever it is at. **Putting a record on** is `PutOn.kt`, pure: `PutOn` (one clock from the touch in the crate — the sleeve laid down, the record out of its mouth and over to the spindle, the card after it, the motor, when the arm sets off — every place a function of that clock, so that "never through the arm" is a test and a hurried sequence is the same one faster), `ArmMove`/`Needle` (the arm going anywhere: lifted, swung while it is clear, lowered when it is there — the moment it lands is a number, and is when there is music) and `PlatterSpin` (a platter under a motor). `NeedleSound.kt` is what a needle sounds like coming down, made rather than recorded, and `NeedlePlayer` plays it off the main thread at the music's own volume. `VinylStage` runs the clock, waits at the table's edge until the record that was taken exists and its sleeve is printed, starts the music when the needle lands (the record is loaded silent: `PlaybackRepository.playAlbum(play = false)`), and finishes a half-done sequence when the room stops being drawn. **The turn of the record** is `TurnOver` (in `PutOn.kt`): lifted by more than its radius, turned about the line from you to the back of the deck, lowered — the turn waiting for the lift and the lowering for the turn. `VinylStage` notices a side's end from the player itself (`ScratchLink.stopAtEndOfTrack` and `endedTrack`, which is also why `CrossfadeEngine` treats such a track as having nothing after it), sends the arm home, and turns the record when it is touched — or unasked, with `AppPreferences.vinylTurnsItself` — and withdraws the request to stop whenever the room is not being drawn (`unseen`). The record's two nodes are which side is up (outer, a turn about the world's z) and its going round (inner), so that the turn is the same turn wherever the record stopped. ROOMS and SETTINGS at the crate are `ui/common/RoomChrome.kt`'s `ChromeAction`, the CD room's, moved out for both. **The next record of a set** is `ChangeRecord` (in `PutOn.kt`): `PutOn`'s clock run back from the mat to the middle of the sleeve and forward again; `VinylStage` runs it in place of a turn whenever the side asked for is on another record, sends the player to that record silently while it is under the cover (`onCue`), and lets it out only once the labels say they are printed for it (`printedRecord`). `VinylSurface` (in `VinylArt.kt`) dresses `vinyl.mat`, for the room's record and the showroom's single. **TalkBack** is `VinylRoomSemantics`, a composable of its own over the scene, fed from Compose state — the crate's `VinylCrate.front`, the deck, `VinylStage.awaiting` — with actions that call what a finger does (`VinylCrate.step`/`shelf`/`takeInFront`, `VinylStage.startStop`/`turnOverNow`/`choose`). Finished 2026-10-18; `Format.VINYL` is built. **Never on a phone.** |
| `ui/common/EnvironmentCubemap.kt` | A room to reflect, painted from a function of direction: the loop the CD room and the pocket player each have a copy of, written out once for the third room. |
| `ui/common/RoomCalm.kt` | **The rooms stand aside while Android is moving the app** (0.7.8). The owner saw opening, closing and returning to Jawon drop frames on his S25 Ultra where other apps slide cleanly: a room holds the GPU near its limit, and the launcher draws its slide on the same GPU while the app is still resumed and still drawing. Two rules. *Leaving*, a room stops the moment its window loses focus (the swipe up, recents, the shade) — measured on the emulator, the render thread goes from 54 jiffies in two seconds to 0 with the shade down, where it used to carry on until paused. *Arriving*, it draws its first three frames at once, so a new surface is never shown empty, and then at 30fps until 550ms have passed. `MainActivity` tells it (`onWindowFocusChanged`, `onResume`, `onPause`); every room asks it in its frame loop and reads `away` through `LocalRoomParked`. Side by side with another app, losing focus is not leaving. `Calm` is the pure half and `RoomCalmTest` holds it — which is all that can be held: whether the slide is now clean is a question for one GPU on one phone. |
| `IntroOverlay.kt` | The card: one `Canvas` reading one clock. It holds the lit mark still until the frames under it run evenly (`awaitCalm` — the room is building), parks the room while the card is whole (`LocalRoomParked`), and uncovers it a moment before the hole opens. `SplashHandOver` is what `MainActivity` tells it about the system's splash. |

Three things the captures found, all in the hand-over: the system draws its mark **1.5× larger
than the view it reports** (a 288dp drawing through a 192dp view); removing the splash outright
leaves **one black frame** (its icon lives on a surface of its own, released at once), so it is
made see-through first and removed a few frames later; and the splash's ground has to be the
card's black (`opening_black`), not the crate's. Played once per process — coming back to an app
still in memory goes straight to the room — and Settings → Short intro, or a tap, cuts it to the
mark catching and the hole opening. With the phone's animations removed the card is shown whole,
held and faded. Tested in `IntroTimelineTest`.

---

## Outside the rooms — the player as other things reach it (0.7.6)

Until 0.7.6 only a room could start a record. Now a car, the assistant, the system's media
controls, a widget, a tile, a shortcut and a file handed over by another app all can, and each
has to end with the **same queue a room would have built** — the deck, the listening record and
the resume point all read the queue.

| File | Owns |
|---|---|
| `data/playback/QueueBuilder.kt` | A record as media items and a place to start. The one place a queue is made, for every caller. |
| `data/playback/ResumePoint.kt` | **Pure.** Where a record picks up: where it was left, unless that is the last breath of its last track. |
| `data/playback/LibraryTree.kt` | **Pure.** The library as a tree to browse: recently played, shelves → records → tracks. A track keeps the id the queue knows it by. |
| `data/playback/LibrarySearch.kt` | **Pure.** What a spoken request means here: a record, a song's record from that song, an artist's, something off a shelf, or — for no words — the record that was on. Compared as speech arrives. |
| `playback/LibraryCallback.kt` | The session's callback: who may connect, browsing, turning a bare id or a query into a queue, resumption. |
| `playback/SessionGate.kt` | **Pure.** Who may connect: Jawon, a car, what the phone already trusts with media. Narrower than Media3's default, because the service is exported. |
| `playback/CoverProvider.kt` | A record's cover as `content://com.jawon.app.covers/<album id>`. Exported, read-only, nothing to list. |
| `playback/ResumeButtonReceiver.kt`, `HeadphoneResume.kt` | The one opt-in behind everything that could start music without a touch. |
| `surfaces/Surfaces.kt` | One listener keeping the widget, the tile and the shortcuts in step with the player. Started with the process, which a tap on any of them may be what starts. |
| `surfaces/RecordWidget.kt`, `WidgetArt.kt` | The widget: `RemoteViews`, and the record drawn as a jewel case into a bitmap. |
| `surfaces/PlayerTile.kt`, `Shortcuts.kt`, `SurfaceState.kt` | The tile (an *active* tile), the icon's shortcuts, and what all three show — including the record that was on last, when nothing is running. |
| `Launch.kt`, `MainActivity.act` | The ways in that are not the icon, as intent actions; `singleTask`, so a file opened from another app does not build a second Jawon in that app's task. |
| `data/importer/OpenedMusic.kt`, `OpenedPlan.kt` | "Open with Jawon". The plan is **pure**: an archive's paths only ever group files into albums, never decide where anything is written. |

`PlaybackRepository` takes on a queue it did not build (`adoptQueue`), so a record started from
a car is counted and has its place remembered, and `PlaybackService` wakes the repository
itself, since no room may ever open. **Everything a caller outside a room can reach runs on the
player's thread** (`onPlayerThread`): a `MediaController` may only be touched on the thread it was
built on, and a widget's buttons arrive off it.

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
| `Platter.kt` | **A record with weight** (0.8, rebuilt from the owner's "can we make that CD scratching sound more satisfying and cool"). The scratch used to turn a finger's speed straight into a playback rate and sounded like a sample being resampled; what makes a record under a hand sound like one is how the *record* moves. `Platter` is that: it slips and winds down when touched, is held to the hand's **place** (never its speed, so the sound and the disc cannot drift and a finger that stops is silence), and let go is taken back by the motor — a wind-up, or from a pull backwards a backspin. It leaves the hand no faster than a hand can throw one (`letGoAt`), and the motor's speed is the deck's tempo (`nominal`). `HandTrack`, in the same file, is where the finger is *between* touch reports: a glide to each report in the time the finger took, not a guess carried past it — measured four times smoother. Pure; shared with the vinyl room's platter, which is why it was built first. |
| `ScratchEngine.kt` | The needle: reads decoded audio at wherever the platter is. Pitch is speed, through a reader that stays clean at any rate (a curve between samples when the record crawls, an average across them when it races); loudness is speed too, gently, so every stroke swells and dies; a little grit only while handled; and **exactly the record, sample for sample, at the speed it plays at** — which is what lets it hand back to the player unheard. Past four times speed it gets *quieter*: that is a disc being spun to find a place, and it should sound like a tape being cued. Pure, and `ScratchRenderTest` renders it to a WAV with a scripted hand for listening off the device. |
| `ScratchAudio.kt` | Everything round the engine that has to be true on a phone, and the two hand-overs. **Taking the record**: for as long as the disc can be touched the room says so every frame (`arm`, a heartbeat — a room that has stopped drawing cannot say it has), and a few seconds round the playhead are kept decoded (`RollingDecoder`), so a touch is answered in the same breath instead of after a decoder has started (130–200ms on the emulator before, 0 after). The engine starts where the player truly is, at speed, and crosses with it over a fiftieth of a second. **Giving it back**: the engine winds the record up and *goes on playing it*; the player is parked, silent, a third of a second further on, and started as the record arrives — by as much as this phone's player has been found to need, learnt from the player's own clock a second and a half later and kept. Nothing is steered by the player's clock while it happens: for its first second that clock is two estimates blending. The output is a low-latency `AudioTrack` at the mixer's own rate. **Set down, or thrown** (0.7.8): a record let go by a hand that was spinning the disc to find a place, or had stopped, is *set down* where it is, at speed, with no wind-up and no run-on (`Platter.setDown`, `Platter.place`) — the owner: "its taking some to exactly continue from the part of the song that it skipped to, i need it to be instant" — and only a finger still moving in a record's own gearing throws it. While it is held the keeper's decoder follows the *record* instead of the player, so a disc spun somewhere has sound there within a few hundredths of a second; set down where there is none yet, the stretch and the player are both sent for and whichever arrives first is heard. The stopped player is walked along behind the disc **straight through `ScratchLink`** (`walk`), because a seek sent through the session lands a frame late — after the hand-back has put the player where the record really is. **Not checked on hardware**: how far behind the finger it is and how tight the hand-back lands — the emulator's audio clocks are noise at that scale. `logcat -s Jawon.Scratch` says what each touch did. |
| `ScratchLink.kt` | What the scratch needs from the real player: its true position (a controller's estimate runs ahead of a player still starting, which is exactly when it matters), its speed, its ReplayGain level — the scratch plays at that level too — and `seek`/`play` *straight to the player*, because the hand-back is timed in thousandths of a second and the session's way round costs a frame. The service leaves the player here while it lives. |
| `RollingStretch.kt` | `RollingDecoder`, which follows the player a few seconds ahead with a decoder of its own (about 2% of a core beside a room that uses a whole one), and `RollingStretch`, the pure buffer it keeps its audio in — tested, because a stretch that says it starts where it does not is a record that has silently slipped. |
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
| `assets/models/*.glb` | `case_tray`, `case_lid`, `disc` for the open case; `case_closed` for the wall (simpler — a shut case shows no interior, and the wall draws hundreds); `beam` for a bay's two vitrine strips (still called `beam`, from when it was an underlight); `disc_shards`, the disc pre-cracked into 27 wedges for the discard animation (451 KB). For the pocket player, `pocket_face` (the face the room draws, ~460 KB) and `pocket` (the whole device, the showroom's exhibit); and `showroom`. All generated by `tools/models/build_models.py`. See `docs/models.md`. |
| `assets/materials/*.filamat` | The hand-written materials, compiled by hand: `dust`, `fog` and `neon` in the CD room, and `polished_steel` for the pocket player's back on its showroom plinth. See `docs/materials.md`. |
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
