# Jawon — Visual Design Spec

Binding design direction. Read before any UI, styling, lighting, or animation
work. Where this conflicts with existing code, flag the conflict and propose a
reconciliation — do not silently rewrite working logic.

**One room. Committed.** The prototype offered three; we ship one, done
excellently.

> **Still true, and worth saying precisely.** The room has since gained four
> *modes* — **Midnight Shop · Cold Storage · After Hours · Workshop** — and that
> is not a reversal of this line. The geometry, the lamp position, the shelving,
> the dust and the open sequence are identical in all four; a mode changes the
> light, the 2D palette every flat screen reads (`Palette` in
> `ui/theme/Color.kt`), the deck's material and the glass in its window. One room,
> lit four ways. §1 below describes Midnight Shop, which is the default and the
> one the rest of this document is written against.

> **Since 2026-09-21, one room per format.** The owner gave each format — CD,
> pocket player, vinyl, cassette — a room of its own (`docs/formats.md`). This
> document is the CD room's. The new rooms will need design sections of their own,
> and the four modes do not reach them: each is lit its own way.

---

## 1. The room: Midnight Shop

A record shop after closing. One warm lamp over the racks, deep shadow
everywhere else, dust hanging in the light. Warm, quiet, and slightly private —
the feeling of being somewhere you're not strictly supposed to be after hours.

Chosen over the warmer daylight alternative for three reasons: the bloom and the
lit bays read strongest against deep warm black; a dark room is the right one
for headphone listening at night, which is when this app gets used; and it costs
almost nothing on OLED.

**Where the saturated colour lives: each album's own bay.** Every album stands
in its own light, coloured from the cover's dominant hue unless the user picks
one. That is the app's colour, and because the shelves carry it, everything else
stays warm, dark and disciplined. Do not add competing colour to the chrome.

> **The fixture changed on 2026-09-13; the principle did not.** It used to be an
> *underlight* — a pool on the board beneath each record, with a bulb in front of
> the artwork throwing up across it. The owner asked for it to go: "maybe just
> remove all underlights and add some other creative type exclusive light for
> each album?" Offered four arrangements that real rooms use, he chose the
> **vitrine**: two strips down the sides of the bay, the way a museum lights an
> object in a case.
>
> Three reasons it is better, and only the first is taste. Nothing in a shop is
> lit from below — it is the angle a torch under the chin makes. One light under
> a sleeve puts five times as much on the brightest part of the artwork as on the
> dimmest, which is what "the underlights for CD cases are washing the CD cover
> arts" was; two at the sides bring that to 1.5. And a light in front of a record
> writes a hard reflection of itself into the artwork now that the covers are
> glossy again, while a light out in the 25mm channel between two records is
> *beyond the cover's edge*, where it has no reflection on it at all.
>
> The arithmetic is in `ui/wallscene/VitrineGeometry.kt` and its test, because
> all three of those claims can be checked without a device.

> **The disc used to carry it, and no longer does.** This section originally made
> a rainbow diffraction sweep the boldest element in the app. It was built in M8
> and **removed on the owner's call** — see `ENGINEERING.md`'s locked decisions. Note
> what that rules out and what it does not: *iridescence* (diffraction, which
> throws colour) is out; *anisotropy* — the colourless directional sweep a real
> CD's highlight makes — is still wanted. Only the label side is ever seen.
>
> The label is also deliberately close to matte. It is printed ink under lacquer,
> and letting it behave like polished metal meant the room's lamp bleached the
> artwork off it.

---

## 2. Colour tokens

Define as a proper Compose colour scheme. Do **not** use Material 3 dynamic
colour or the default purple baseline — that is the single biggest source of a
templated, generic look.

| Token | Hex | Use |
|---|---|---|
| `crateBlack` | `#17120F` | Base background. Warm near-black — never `#000000`. |
| `crateRaised` | `#241C17` | Surfaces above base: sheets, deck, dialogs. |
| `crateEdge` | `#33291F` | Hairlines, dividers, borders. |
| `labelCream` | `#EFE3CC` | Primary text. Aged label paper — never `#FFFFFF`. |
| `labelDim` | `#9A8B76` | Secondary text, inactive states. |
| `lampAmber` | `#F0A93C` | Primary accent: active state, now-playing, progress. |
| `oxideTeal` | `#2F7D71` | Secondary accent. Sparingly, for contrast against amber. |

**Scene colours** (Filament, linear space — convert from these sRGB values):

| Element | Hex |
|---|---|
| Wall | `#1A1410` |
| Shelf timber | `#2F2318` |
| Fog / background | `#120E0B` |
| Key light (lamp) | `#FFD2A0` |
| Ambient fill | `#40312A` |
| Rim / bounce | `#F0A93C` |

**Diffraction sweep** (disc surface only):
`#F0A93C → #E85D9E → #5B7FE8 → #3FD9A8 → #F0A93C`

Layered over the user's disc-face image at moderate strength so their artwork
stays readable — it's a sheen on top, not a replacement.

**Dynamic accent:** extract the dominant colour from the playing album's cover
and bleed it as a soft glow behind the spinning disc. Clamp saturation and
luminance so extracted colours can never break the warm-dark palette.

---

## 3. Typography

Three roles, deliberately distinct. Ship real fonts; do not fall back to the
platform default for the display role.

- **Display** — a *wide/expanded* heavy grotesque (Archivo Expanded). Album and
  artist titles. Wide, not condensed: reads as a record sleeve rather than a gig
  poster. Tight line height, generous letter spacing at large sizes.
- **Body** — a neutral grotesque (Inter). UI labels and descriptions. Stays quiet.
- **Utility / metadata** — **monospace** (JetBrains Mono). Non-negotiable and a
  signature of the design: track durations, year, track numbers, elapsed time,
  and the entire quality readout render in mono, in `lampAmber`, uppercase,
  small, letter-spaced. This is the hi-fi equipment display language and it does
  most of the vintage work.

Set an intentional type scale with real jumps. A flat ramp where display and body
sit within a few sp of each other reads as dull.

---

## 4. The scene

- **Lighting** — one warm shadow-casting spot standing in for the shop lamp,
  low ambient fill, one coloured bounce light. Not a flat evenly-lit room.
- **Shadows** — soft (PCF or VSM). Cases shadow onto each other and the shelf;
  this inter-case shadowing is where the sense of depth on a packed shelf comes
  from.
- **Depth of field** — focus tracks the case nearest screen centre while
  browsing, and the disc while playing. Shelf sections to the sides fall off.
- **Bloom** — subtle, on the lamp and the disc's specular highlight only. Not a
  global haze.
- **Fog** — warm, matching the background, so the wall recedes into darkness at
  its edges rather than ending at a hard boundary.
- **Dust** — slow-drifting motes, visible mainly in the light shaft. Animate in
  the shader; the CPU should do nothing per frame.
- **Ambient rotation** — the playing disc turns continuously. Barely perceptible
  at rest, but it stops the screen feeling frozen.

Respect reduced-motion settings: drop ambient rotation, parallax, and dust; keep
press feedback and the open sequence.

---

## 5. Motion

Springs, never linear tweens, for anything responding to touch.

- **Press** — spring scale up, elevation rise, and a tilt toward the touch point.
  Underdamped so it visibly overshoots and settles rather than snapping.
- **Open/close** — the five beats in `ENGINEERING.md`, timing fractions from the
  prototype. Reversible from any point.
- **Scratch** — the disc follows the finger exactly, 1:1, with haptic ticks per
  increment. Release resumes spin from the current position.

---

## 6. Anti-goals

Treat each as a bug:

- Material 3 default theming or dynamic-colour purple
- Pure `#000000` backgrounds or pure `#FFFFFF` text
- Flat single-layer shadows, or a flatly-lit scene
- Linear tweens on touch feedback
- A flat type scale
- Competing saturated colour anywhere outside the disc surface
- A quality readout that reports source format without reporting actual output
  rate (see `docs/PLAN.md` §7 — it would be a lie)
