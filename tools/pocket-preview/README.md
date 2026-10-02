# Looking at the pocket room without a phone

The pocket player's whole frame — the device at life size, the night window behind it, Filament's
grade over both — rendered on any machine, in about two minutes for the first frame and seconds
for each after it.

```
pip install bpy==5.0.1 numpy pillow          # bpy: Blender as a Python module
npm install playwright                        # Chromium's WebGL runs the window shader
tools/pocket-preview/preview.sh out/ 400 1500 3100
```

Each number is how far the train has travelled, in metres; each gives `out/frame_<metres>.png`.
Different distances are different stretches of line — town, open country, a lamp going by — and
the window is only judged fairly across several. Set `BPY_PYTHON` if bpy lives in a venv, and
`LCD_PNG` to put a picture of the screen on the device.

## What it is made of

| piece | what it does | faithful to the app? |
|---|---|---|
| `render_window.js` + `window.html` | Runs **`night_window.mat`'s own fragment block**, unchanged, in WebGL2, over exactly the patch of the plane the phone frames (`framing.json`), with each layer's scroll computed as `TrainMotion.scroll` computes it | Yes — the same code, in linear light, before the tonemapper |
| `frame_device.py` | The device in Cycles from the room's camera: 50mm on Filament's 24mm sensor, life-size distance, the 15° / −6° pose; lit by the carriage IBL at `AMBIENT_INTENSITY` and the key spot at its candela, at SceneView's sunny-16 exposure | Close — Cycles, not Filament; no passing lamps |
| `carriage_env.py` | `PocketLighting.radiance`, ported to numpy | **A copy — it drifts if `PocketLighting.kt` changes and this does not** |
| `grade.py` | Device over window in linear light, then bloom over the threshold, `ACESLegacy` (1/0.6, then ACES), a light vignette, sRGB | An approximation of `PocketAtmosphere`'s passes, calibrated against the phone (below) |

## What it is for, and what it is not

It answers **composition and palette**: where the horizon falls against the device, whether the
strips above and below it hold anything, whether the window reads as a place or as wallpaper.
The 2026-10-02 rework of the window was designed against it — the first frame it produced is
what showed the original discs spread evenly over the whole window, sky included.

**And it is calibrated against the phone.** With `grade.py` applying `ACESLegacy`'s 1/0.6 first,
the device's boxes landed within a few levels of the owner's S25 Ultra measurements of 2026-09-27:
front beside the screen 39 against 40, front at the foot 13 against 12, polished side 41 against
35. Without that factor everything read about 40% dark — which is worth knowing about any
preview of a Filament scene. So it is good for brightness *relative to that day*; the phone,
with fixed luminance boxes (`tools/emulator/README.md`), is still what settles it.

Framing is the S25 Ultra's (1440×3120 at ~498ppi). A smaller phone frames a slightly taller patch
of the plane, so the horizon sits nearer its top edge; change `framing.json` to look.
