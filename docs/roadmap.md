# The road to 1.0

Written 2026-10-10, the day 0.7.1 shipped the pocket player to testers. **1.0 is the Google Play
release**; after it the project moves from building to marketing and maintenance. The owner's
brief for the whole road, in his words: *review UI, find weaknesses, uncomfortable parts, ugly or
unprofessional looking parts of the app and fix all the things … vinyl mode for 0.8, cassette mode
for 0.9, and for 1.0.0 EVERYTHING should work flawlessly and coherently without inconveniences.*

Read with `ENGINEERING.md` (the brief, the traps), `docs/formats.md` (the rooms) and `docs/release.md`
(shipping). This file is the order things happen in, and why.

---

## Standing rules for the whole road

These come from the owner, from this session, and apply to every milestone below.

- **Jawon is recognisable from every page.** Not a logo stamped in a corner: a brand system (§B).
- **Switching rooms is instant, smooth and satisfying, and spam-proof.** People will flick between
  rooms just because it feels good; it must never lag, freeze, stack up or crash (§S).
- **Use any sensor or permission that makes the app better.** Permissions are not a reason to
  hold a feature back. Every one is recorded in the privacy ledger (§P) as it lands, and the
  policy is brought up to date in one pass before 1.0 — `ENGINEERING.md`'s "same commit" rule is
  relaxed for this road, the ledger is what keeps it honest.
- **One thing at a time, checked on the owner's phone** (the working agreement), and the
  release check before every publish: room renders, a case opens, playback starts.
- Every creative idea from the 2026-10-09 research is on this road, each in a milestone (§C).

---

## Where it stands (0.7.1)

Two finished rooms (CD, pocket player), the showroom between them, local + Subsonic + Internet
Archive sources, CD ripping (never met a drive), 795 JVM tests, the self-updater, a public privacy
policy. Distributed as a signed APK to 15+ testers. Not on Play.

### The audit, 2026-10-09/10, on the S25 Ultra

What looks unfinished, is uncomfortable, or would not survive a reviewer's first five minutes.
Fixed items are struck through; the rest are assigned below.

| Where | Finding | Milestone |
|---|---|---|
| CD room | ~~Search icon under the status bar~~ — moved to the chrome row as FIND (0.7) | done |
| Record shop | ~~LIVE SHOWS matched nothing~~ (0.7) · ~~band name printed twice~~ (0.7) | done |
| Pocket player | ~~Every click froze the room~~ · ~~drawn at 60fps at rest~~ (0.7) | done |
| Deck | ~~"MOST" / "UNFIN" badges~~ (0.7) | done |
| **Settings** | Long single scroll of mono capitals; jargon ("volume levelling", "tidy the library", "how it is running"); tiny 9–10sp text; the owner finds it "unprofessional and hard to understand for someone new" | 0.7.2 |
| **Room switching** | Each room rebuilds Filament from nothing (~1.25s to first frame), behind a fade; the only way between rooms is Back → showroom → swipe → tap, or Settings | 0.7.2 |
| Type everywhere | The mono scale bottoms out at 9sp (`JawonType.Fine`) and the commonest size is 10sp — legible on a 6.9" flagship, small on a 6.1" phone and for older eyes | 0.7.2 |
| Scan results | ~~Rows say "COVER" but show no cover; a `Download` folder is offered as an album; one-track "albums" crowd out real records; Back skips the chooser and lands in the room~~ — covers in the rows, recordings and untagged folder-"albums" filtered (`ScanTidy`), singles in a folded section under the albums, Back to the chooser (0.7.4) | done |
| CD import | A paragraph of jargon ("bus-powered") and a LOOK AGAIN button; no steps, no picture, no automatic detection of a drive being plugged in | 0.7.3 |
| CD room | ~~Closing a case pauses, and a paused record leaves no bar on the wall~~ — it plays on, its case proud, a real bar at the foot of the wall (0.7.3) | done |
| CD room | The chrome's labels are 10sp dim mono under icons; only the 48dp icon is the target, not the label | 0.7.2 |
| Deck | ~~At the resting height only ~2 tracklist rows show; long titles truncate in the plate~~ — four rows (one spec line, slimmer transport, 40dp rows), and a long title runs along the glass (0.7.4) | done |
| Edit album | "UNDERLIGHT" names a light the room no longer has (it is the glow pool now) | 0.7.2 |
| Remove album | ~~A dialog, then gone: no undo~~ — the case still breaks, then UNDO for seven seconds before anything is deleted (0.7.4) | done |
| Showroom | Neighbouring captions peek in clipped at the screen edges ("DS", "CA") and read as a layout fault | 0.7.2 |
| Pocket player | ~~No Search menu (the classic had one)~~ — Search, the classic's letter strip (0.7.4); on 60Hz phones and low tiers, unmeasured | 0.7.5 |
| Android 9 | On the API 28 emulator the pocket player's screen and the CD room's shelf plates render **blank** — both reach the GPU as a `SurfaceTexture` stream; a software canvas instead of the hardware one changed nothing, so it looks like the emulator's GL translator, not the app. Unverified on a real Android 9–11 phone; the room, the cases, playback and an update from 0.4.2 all work there | 0.7.4 |
| First run | ~~Not re-checked since the rooms and the showroom arrived: a new user is not told the showroom exists, how to scratch, or that Back leaves the wall~~ — a first-run card in each room and the showroom, and Settings → Guide (0.7.4) | done |
| Heat | ~~The CD room holds the GPU near 100% even untouched (trap 28)~~ — it settles when left alone, and Graphics → Balanced draws fewer pixels and fewer frames (0.7.5); unmeasured on the owner's phone | done |

The audit is not finished: the deck's SOUND and LYRICS pages, shelf editing, the music-server
setup, backup/restore and the first-run screen still need the same pass (0.7.2 starts with them).

---

## §S — Switching rooms, re-engineered (0.7.2, the headline)

**The goal, measured:** from a tap to the new room's first frame in under 250ms; no frame over
33ms during a switch; twenty switches as fast as a thumb can manage with no crash, no leak, no
queue of switches playing out afterwards. A script drives it on the phone (`adb` taps in a loop,
per-thread CPU and memory before and after) and is committed under `tools/`.

**Why it is slow now.** Every room is its own SceneView destination, and every destination makes
its own Filament `Engine`, loads its models, compiles its materials' first variants and bakes or
uploads its IBL — `docs/formats.md` §7 step 5b measured the showroom's first frame at ~1.25s, and
going into a room costs the same again. The NavHost fade hides the black. The fix the docs already
name: **an engine that outlives a destination.**

1. **One `Engine` for the app**, application-scoped, with shared model and material loaders. A
   room borrows it; leaving a room destroys its scene, never the engine. (Watch trap 35 — scene
   objects are disposed inside the scene's content — and trap 1, which a shared engine makes
   stricter, not looser.)
2. **Warm the other rooms in the background**: load their `.glb`s and build their IBLs once, off
   the main thread, at launch and after a room settles; compile their materials' variants with a
   throwaway frame so the first real one is not paying for shader compilation.
3. **Keep the last room's scene alive** where memory allows (tier-gated): going back is a camera
   move, not a rebuild.
4. **A switch is a state machine, not a navigation.** Requests coalesce: a new target cancels the
   one in flight and the transition reverses from where it is (the `OpenSequence` rule — every
   beat a pure function of one progress value, reversible from any point). Spamming produces one
   smooth motion that follows the thumb, never a backlog.
5. **A faster way between rooms, as well as the showroom.** Candidates, to be prototyped and put
   to the owner: a swipe up from the bottom edge that pulls the showroom up over the room like a
   drawer; a long-press on the room's own brand mark (the neon sign, the player's logo) that spins
   a dial of rooms; or the showroom itself made instant by step 3. Whichever wins, a haptic detent
   per room and a sound make it satisfying to do for its own sake.

## §B — The brand system (0.7.2, then every room)

Jawon has a wordmark (Archivo Expanded, `JawonType.Masthead`) and a neon sign, and nothing that
travels. The brand should be obvious from any page without being stamped on it.

- **A monogram**: a J set in a disc's centre hole — the one object every format shares (a CD's
  hole, a record's spindle, a cassette's hub, the click wheel's centre button). Used as the app
  icon (adaptive, with a monochrome layer for themed icons), the notification's small icon, the
  widget, the top bar of every flat screen, and the splash.
- **The top bar carries it.** `ScreenBar` gains the monogram beside the title, so every 2D screen
  is visibly Jawon's; the room's accent colours it.
- **Each room signs itself in its own material**: the CD room's neon sign; the pocket player's
  engraved logo on its back and "Jawon" in its title bar (already); a Jawon label on every record
  in the vinyl room and a pressing-plant mark on the sleeve; an embossed shell and a Jawon J-card
  in the cassette room.
- **Sound and touch**: one signature sound (the CD's tray closing) and one haptic, used for "you are
  in Jawon" moments — launch, a room arriving.
- **Words**: one voice across every screen — plain, warm, brief; no jargon on any first-level row
  (Settings §U).

## §U — Settings, redesigned (0.7.2)

Research: Apple's own guidance is that Settings is a **hierarchy of lists**, options that change
often belong on the main screen and the rest one level down, one idea per row, related rows grouped
under a section header, destructive actions last and confirmed. Premium themed apps (Not Boring's
suite) keep one structure and let a *skin* change the look.

**Decision: one structure everywhere, dressed by the room you came from.**

- **The top is a brand card**: the monogram, JAWON, the version, and "Check for updates" — where
  iOS puts your account.
- **Grouped sections of rows**: an icon tile, a plain title in sentence case (the sleeve's face, not
  the mono label face), the current value at the right, a chevron to a detail page. Switches stay
  inline. The long explanations move into the detail pages, where today's `MoreText` paragraphs
  live on unchanged — nothing that is promised in `docs/privacy.md` gets shorter.
- **Sections**, in the order people need them: **Rooms** (which room, with live previews; the CD
  room's light; how cases move) · **Playback** (crossfade, even out loudness, gapless, sleep timer,
  resume when headphones connect) · **Library** (add music, covers and lyrics lookups, fix
  duplicates, backup and restore) · **Your music server** · **Online** (one page listing every way
  Jawon uses the network, each with its switch) · **About** (version, licences, privacy policy,
  "how it is running" and problem reports, tucked here rather than on the front page).
- **A search field** at the top, as iOS has.
- **Dressed by the room**: the CD room's four modes keep their palettes (`Palette`); the pocket
  player gets a palette from its finish (silver, black, red) — light, like its LCD; vinyl and
  cassette get theirs when they land. Structure and words never change between rooms; only colour
  and material do. That is the "different look for each mode" the owner liked, without making a
  new user learn four Settings.
- The 2D type scale goes up a step (Fine 10, Caption 11, Body 12, Title 14) and is checked at the
  system's largest font size.

---

## 0.7.x — polish, switching, convenience (October–November 2026)

| Release | Contents |
|---|---|
| **0.7.2** | §S switching (engine, warm rooms, coalescing, a faster way between rooms) · §B brand system · §U Settings redesign · type scale up a step · the rest of the audit pass · "UNDERLIGHT" → glow · showroom captions |
| **0.7.3** — shipped | **Jawon's mark** as the app icon, the themed icon, the notification and a splash that writes it · **the record plays on** at the foot of the wall (`NowPlayingBar`: the track, progress, swipe to change track, down to put it away) · the pocket player's sensors, §C below: **Tilt & Turn** (sideways Cover Flow, leaning through the covers), **Hold**, a backlight that answers the room, the phone's own battery in the title bar, **Shuffle Albums**, **Brick** · the activity held in portrait · a wall fault fixed on the way: a parked pool slot swallowing taps |
| **0.7.4** — shipped | **Scan results** with covers, junk filtered (`ScanTidy`: recordings, voice notes, a `Download` folder as an "album"), **singles** folded under the albums, Back to the chooser · **undo** for removing an album · **four tracks** at the deck's resting height, long titles that run · the pocket player's **Search** · **Settings → Guide** (asked for by the owner, who could not tell what Tilt & Turn meant) and a **first-run card** in each room · moved to 0.7.6: CD import with steps and drive detection, "Open with Jawon" |
| **0.7.5** — shipped | **Optimisation and accessibility — the owner's call, 2026-10-10**. Shipped: the APK 41 → 26MB (ARM only, SceneView's unused environment out); the wall **settles** when left alone (RoomSettle: idle 0.67 → 0.23 of a core); the showroom at 30fps at rest; **Settings → Graphics** — Balanced by default (85% resolution, lighter fog, the playing disc at 40fps on 120Hz), Best looking, Light (the low tier on any phone, 30fps cap, and on with battery saver) — a record playing went 1.05 → 0.63 (Balanced) / 0.45 (Light) of a core; the now-playing bar's disc at 30 steps a second; the deck's plate drawn as one tile; **TalkBack** through all three 3D places (descriptions and actions), the bar's labels and tracklist at 2x text, Glacies' dim text to 4.6:1. Still open from the plan below: cold start, a battery saver tier beyond Light, and why old Redmis fail (needs the owner's model and what happens). The plan as written: "its really struggling in my phone which is s25 ultra and wont even work in some budget phones like old redmis". Measured before and after, on the flagship and the low-end emulators: the CD room drawing only when something changes (the pocket room's 0.60 → 0.32 core, applied to the wall); the GPU's per-pixel cost (fog, post, render scale) and a real low tier that a 2–3GB Redmi can run; cold start to first frame; memory with all rooms kept; **the APK's weight** (emulator ABIs out of the shipped APK, assets, fonts, models); a battery saver tier · **accessibility**: TalkBack through every flat screen and a way into every record without the 3D surface, the system font size honoured, contrast, reduced motion |
| **0.7.6** — shipped | **Convenience, and one opening.** Shipped: **one intro instead of two** — testers saw the splash and the title card as "two intros, and both of them fast", so the system's splash is now the first beat of a single sequence that ends by going through the hole in the disc into the room (`ui/intro/`); Settings → Short intro, and once per process · the player **reachable from outside the rooms** (`MediaLibraryService`, exported, `SessionGate`): **Android Auto** (recently played · shelves → albums → tracks, covers through `CoverProvider`), **"Play ⟨album⟩ on Jawon"** by voice (`LibrarySearch`), the system's **resume** card · a home-screen **widget** (a small jewel case drawn into a bitmap, play/pause/next, the record's own colour) — **`RemoteViews`, not Glance**: two layouts and a bitmap did not earn a library a release after the APK lost 15MB · a **Quick Settings tile** · **shortcuts** (Resume ⟨record⟩ · A record from your shelves · Add music), published from code because a manifest shortcut clears the task · **"Open with Jawon"** for a song or an album **ZIP**, copied into `Music/Jawon` (`OpenedMusic`, `OpenedPlan`) · **CD import** as five steps with a drawing that is also the status, the drive noticed as it is plugged in and pulled out · **resume with headphones**, opt-in, **without a Bluetooth permission**: an audio-output listener while the player is alive, and a media-button receiver that answers only with the switch on — nothing else can start playback · the **sleep timer** in the pocket player's Extras (the CD room's deck already had "end of record"). Not checked on hardware: Android Auto itself (the tree was walked through a real `MediaBrowser`, and the system's resumption probe connected), headphones dropping out, a CD drive |

## 0.8 — vinyl (November 2026)

The room `docs/formats.md` §5 already decided: **crate-digging**. Flip through a crate of LPs with
the thumb, pull one out, slide the record from its sleeve onto a turntable, drop the needle;
**side A and side B**, with the flip as a ritual; the platter scratches as the CD's disc does; the
tonearm follows the record. Geometry from the Blender script (sleeve, inner sleeve, LP, turntable,
crate), sleeve art from the covers, a label printed per record. Lit as its own room.

Also in 0.8: **liner notes** — the credits and awards lookup decided on 2026-09-01 (MusicBrainz
relationships, Wikidata awards), printed on the back of the CD case and the LP's back sleeve ·
**feel the music** — audio-coupled haptics through the platter and the disc while a finger rests
on them (`HapticGenerator` where the phone supports it, onset detection driving the vibrator where
it does not) · **dust on neglected records** across both rooms · **ListenBrainz / Last.fm
scrobbling** (opt-in; remote albums skipped when the server already scrobbles).

**0.8.x, late November — Year in records.** Before Spotify Wrapped: the year's top records drawn
as cases and sleeves with their wear, the longest run, the hour you listen; rendered on the phone
from `PlayEvent`, shared as images through the share sheet. Plus **share a shelf** as an image.

## 0.9 — cassette (December 2026)

A tape deck and a shelf of cassettes, `docs/formats.md` §6. The tape physically winds: fast-forward
and rewind with the reels' speed changing as the tape moves from one to the other, the click of the
keys, the hiss and wow you can switch off. **Mixtapes** — the one place the album-first rule bends,
because the cassette is mixtape culture: a compilation you make, with a **handwritten J-card**.

Also in 0.9: **NFC tags** on your real CDs, records and tapes — tap the phone to a physical case
and Jawon pulls that album from the wall and plays it · **bit-perfect USB DAC** output when the rack
is flat (Android 14's mixer attributes) · the **CD rip validated on a real drive** — "buy a CD, rip
it, it is on your wall" is the launch story (a drive needs buying; to be raised with the owner).

## 1.0 — Google Play (January 2027)

Nothing new; everything coherent, fast and forgiving.

- **A Play build**: a product flavour without the self-updater and `REQUEST_INSTALL_PACKAGES`; the
  direct-APK flavour keeps both for testers.
- **The pay-once unlock** (`ENGINEERING.md`, monetisation): free — the rooms, playback, the scratch, the
  equaliser, import, backup; paid — the full rack, badges, colour schemes; ~$4.99, the category's
  price (Symfonium, Poweramp, NostalgicPod). Play Billing, one product, restore on reinstall.
- **The closed test**: a personal account made after Nov 2023 needs 12+ testers opted in for 14
  continuous days before production access — start it the day the Play build exists; the existing
  testers are enough.
- **The privacy policy brought up to date from the ledger (§P)**, the data-safety form from
  `docs/release.md`, listing assets (icon from §B, feature graphic, screenshots of all four rooms).
- **Accessibility**: TalkBack through every flat screen and a way into every record without the 3D
  surface (search already is one); the largest font size; contrast.
- **Localisation**: English plus Korean, Russian and Uzbek.
- **The bar**: every row of this file's audit closed, no crash in the logs of a week's testing, the
  room cool on a Redmi 9, every switch under 250ms.

### The pocket player and Apple

Apple removed Rewound (2019) and RetroPod (2023) from the App Store for looking like an iPod; Google
Play currently lists NostalgicPod (€4.99, mid-2026), so the genre is allowed there. Keep every Apple
mark off it — never "iPod", no Apple logo, no Apple typefaces — and give it **a name of its own**
before 1.0 (still open in `docs/formats.md`).

---

## §C — Every creative idea, and where it lands

| Idea | Milestone |
|---|---|
| Liner notes with real credits and awards | 0.8 |
| Dust on records you have not played in months, wiped by playing them | 0.8 |
| Feel the bass through the disc and the platter | 0.8 |
| Year in records, and share a shelf as an image | 0.8.x (late November) |
| Mixtapes with a handwritten J-card | 0.9 |
| NFC tags on your physical records | 0.9 |
| "Buy a CD, rip it, it is on your wall" (the rip, on real hardware) | 0.9 |
| Album ZIPs and audio files opened with Jawon | 0.7.3 |
| Widget, Quick Settings tile, app shortcuts, Android Auto, voice | 0.7.5 |
| ListenBrainz / Last.fm scrobbling | 0.8 |
| Bit-perfect USB DAC output | 0.9 |

### The pocket player's sensors (0.7.3–0.7.5, as each is ready)

The owner asked for more of the phone's own sensors in the pocket player. In order of how much they
add per unit of work:

1. ~~**Turn it sideways for Cover Flow**~~ — **0.7.3** (`Sideways.kt`, `SidewaysCoverFlow.kt`).
   The activity is held in portrait, so the turn is read from gravity and Cover Flow is drawn
   turned to meet the phone: swiped through with a throw that settles on a cover, a tap on the
   middle cover turns it over to its tracks, a tap on a track plays it, and back upright the player
   is on Now Playing or on the cover the row was left at.
2. ~~**Hold, from the proximity sensor**~~ — **0.7.3**: covered for 0.4s, the wheel takes touches
   and does nothing, the backlight goes out and a lock shows in the title bar; uncovered, it lets go
   at once.
3. ~~**Shake for a record**~~ — shake became the way between rooms (0.7.2), so a random record is
   **Shuffle Albums** on the main menu instead (0.7.3): a whole album, never the one already on.
4. ~~**Tilt Cover Flow**~~ — **0.7.3**: the lean (`leanOf`, absolute, from gravity — not the
   reflections' tilt, whose neutral follows the phone) drifts the covers, and past about 20° flicks
   to the next, again every half second while held. Off with Tilt & Turn in the device's Settings.
5. ~~**A backlight that answers the room**~~ — **0.7.3**: below about 60 lux the screen dims, up to
   a third in the dark, along the log of the light.
6. **Lyrics that follow a tilt** when the wheel is not being turned — not yet.
7. ~~**Brick**~~ — **0.7.3**, Extras → Brick (`BrickGame`, pure and tested): the wheel moves the
   paddle and so does leaning the phone; the best score is kept.

And one the list did not have: **the battery in the title bar is the phone's own** (0.7.3), from
the system's battery broadcast — no permission.

---

## §P — The privacy ledger

Everything this road adds that the published policy will have to describe. Updated as each lands;
the policy is rewritten from this list once, before 1.0.

| Added | What | Leaves the phone? |
|---|---|---|
| 0.7.1 | Motion sensor (pocket player's light) — **already in the policy** | no |
| 0.7.2 | Linear acceleration (shake to switch rooms), proximity (no shake in a pocket) | no |
| 0.7.3 | Gravity (sideways Cover Flow, leaning through it, Brick's paddle), proximity (Hold), ambient light (the pocket screen's backlight), battery level (the title bar, a system broadcast — no permission) — all read on the phone while the room is on screen, none stored or sent. **In the policy since 0.7.3**, with 0.7.2's shake: the published table said the motion sensor was used "only in the pocket player", which shake made untrue | no |
| planned | NFC (tags on physical records) | no |
| planned | Bluetooth connection events (resume when headphones connect) | no |
| planned | Scrobbling to ListenBrainz / Last.fm | yes — what played, to the user's own account |
| planned | Credits and awards lookups (MusicBrainz, Wikidata) | yes — title and artist |
| 0.7.6 | **The player is reachable from outside the app**: the playback service is exported, so a car (Android Auto), the assistant and the system's media controls can browse shelves → records → tracks, see titles and covers, and start a record. Who is let in is `SessionGate`: Jawon, a car, and what the phone already trusts with media (the system; apps given notification access). A record's cover is readable by its album id through `CoverProvider`. Nothing goes to a server of Jawon's; what a car or the assistant does with a title is theirs | to other apps on the phone, and to a connected car |
| 0.7.6 | **Open with Jawon**: a song or an album ZIP handed over by another app is copied into `Music/Jawon` on the phone's shared storage (the app's own music folder on Android 9) — no permission, since an app may add its own files there and read them back. Nothing is read but what was handed over | no — but the copies are visible to other apps that can read the phone's music, as any music file is |
| 0.7.6 | Resume with headphones (opt-in): an audio-output listener while the player is alive — **no Bluetooth permission**, so the planned row above is not needed — and a media-button receiver that answers only with the switch on | no |
| planned | Play Billing (Play build only) | yes — Google's purchase flow |

---

## After 1.0 — marketing and maintenance

**Marketing.** The story is already written by the market: CD sales up 16% (US, H1 2026), Gen Z
buying iPods and CDs for distraction-free, owned listening. Short videos of the five-beat open, the
scratch and the click wheel; a press kit (Android Authority wrote up NostalgicPod — pitch the same
desk); r/androidapps, r/vinyl, r/cd, r/ipod; Product Hunt; Korean communities. The CD-rip story and
NFC tags are the demos nobody else can do.

**Maintenance.** A monthly release rhythm; crash reports through the existing PROBLEMS → SEND path
and Play vitals; the closed-test group kept as a beta channel; the Filament/SceneView pin reviewed
each quarter (`docs/realism.md` — 1.76 brings things the rooms want).

---

Sources for the research behind this file: the 2026-10-09 session's search results — RIAA/Luminate
H1 2026 via [Consequence](https://concerts.consequence.net/articles/the-cd-revival-is-getting-hard-to-ignore);
[TechRepublic on the iPod revival](https://www.techrepublic.com/article/news-gen-z-ipod-revival/);
[Android Authority on NostalgicPod](https://www.androidauthority.com/nostalgicpod-android-phone-virtual-ipod-3682104/);
[9to5Mac on RetroPod's removal](https://9to5mac.com/2023/01/13/retro-pod-ipod-app-store-download/);
[Google Play testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB);
[Apple HIG, lists and tables](https://developer-rno.apple.com/design/human-interface-guidelines/components/layout-and-organization/lists-and-tables);
[AOSP preferred mixer attributes](https://source.android.com/docs/core/audio/preferred-mixer-attr);
[Media3 MediaLibraryService](https://developer.android.com/media/media3/session/serve-content);
[Google Assistant and media apps](https://developer.android.com/media/implement/assistant);
[HapticGenerator](https://developer.android.com/reference/android/media/audiofx/HapticGenerator).
