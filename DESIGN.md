# CRATE — Visual Design Spec

Binding design direction. Read before any UI, styling, lighting, or animation
work. Where this conflicts with existing code, flag the conflict and propose a
reconciliation — do not silently rewrite working logic.

**One room. Committed.** The prototype offered three; we ship one, done
excellently.

---

## 1. The room: Midnight Shop

A record shop after closing. One warm lamp over the racks, deep shadow
everywhere else, dust hanging in the light. Warm, quiet, and slightly private —
the feeling of being somewhere you're not strictly supposed to be after hours.

Chosen over the warmer daylight alternative for three reasons: the disc's
iridescence and the bloom read strongest against deep warm black; a dark room is
the right one for headphone listening at night, which is when this app gets
used; and it costs almost nothing on OLED.

**The single boldest element is the disc surface.** Every disc carries a real
diffraction sweep — the rainbow you see tilting a CD under a lamp. That is where
all the saturated colour in this app lives. Because the disc carries the colour,
everything around it stays warm, dark, and disciplined. Do not add competing
colour to the chrome.

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
