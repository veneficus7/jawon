# Looking at the pocket room without a phone

The pocket player's whole frame — the device at life size lying on its desk, from the room's own
camera, Filament's grade over it — rendered on any machine in a few minutes.

```
pip install bpy==5.0.1 numpy pillow          # bpy: Blender as a Python module
tools/pocket-preview/preview.sh out/ 720 64   # width, samples
```

That writes `out/frame.png`. Set `BPY_PYTHON` if bpy lives in a venv, and `LCD_PNG` to put a
picture of the screen on the device. 1440 wide is the S25 Ultra's own resolution, and what a
judgement about the wood's grain needs.

## What it is made of

| piece | what it does | faithful to the app? |
|---|---|---|
| `frame.py` | The shipped `pocket.glb` and `pocket_desk.glb` in Cycles, from the room's camera: 50mm on Filament's 24mm sensor, life-size distance, the device's 15° / −6° pose. The desk is drawn as the app draws it — its baked plate, unlit. The device and the cable are lit by the lamp at its candela and the room at `AMBIENT_INTENSITY`, at SceneView's sunny-16 exposure | Close — Cycles, not Filament; and the lamp is Cycles' spot, whose falloff the app's is fitted to rather than identical with |
| `frame.py --probe` | The device as a perfect mirror in an environment coloured by direction: prints the zenith and azimuth each part of it reflects, in the device's frame | Exact — it is geometry |
| `tools/models/pocket_room.py` | The room itself: the lamp, the room as light, the walnut, the cable's path. **Shared** with the bake in `build_models.py`, so the preview cannot drift from the desk | The app's copy is `PocketLighting.kt`, and `PocketLightingTest` pins it to this one's numbers |
| `grade.py` | Bloom over the threshold, `ACESLegacy` (1/0.6, then ACES), a light vignette, sRGB | An approximation of `PocketAtmosphere`'s passes, calibrated against the phone (below) |

## What it is for, and what it is not

It answers **composition, palette and the relative light on the device**: where the lamp's pool
falls, whether the device sits in the pool or its falloff, whether the wheel reads black against
the anodising, how the cable crosses the strip above. The desk room of 2026-10-02 was designed
against it end to end — the lamp's place, its aim and its cone were all settled here before a
line of Kotlin was written.

**It is calibrated against the phone.** With `grade.py` applying `ACESLegacy`'s 1/0.6 first, the
device's boxes landed within a few levels of the owner's S25 Ultra measurements of 2026-09-27
(then in the train room): front beside the screen 39 against 40, front at the foot 13 against 12,
polished side 41 against 35. Without that factor everything read about 40% dark — which is worth
knowing about any preview of a Filament scene. The phone, with fixed luminance boxes
(`tools/emulator/README.md`), is still what settles it.

**Before moving a light, probe.** `python frame.py --probe` says what each part of the device
actually reflects. At this pose the face looks 11–23° from straight up and off to the right —
which is why the room's ceiling glow, not the lamp, is what lights it — the wheel ~13°, and the
polished left side down at the desk beside it. An earlier round on the train room spent a day on
fixtures aimed at the device's edges by hand-worked angles before the probe showed those walls
face away.

**The pose is SceneView's**: `Rotation(x = -6, y = 15)` is `Ry(15) · Rx(-6)` through kotlin-math,
Blender's `XYZ` order here. It was `ZXY` — the other way round — until 2026-10-02, a degree and a
half off at the corners.

Framing is the S25 Ultra's (1440×3120 at ~498ppi). A smaller phone frames a slightly smaller patch
of the desk round the same life-size device; change `framing.json` to look.
