# The pocket face without the phone

The pocket player's room is the device's face filling the phone's display (`docs/formats.md` §7,
step 5c). This renders that frame in Cycles: the shipped `pocket_face.glb`, straight on at life
size on the owner's S25 Ultra, in the room `PocketLighting.kt` builds, then graded the way
`PocketAtmosphere` grades it.

```
pip install bpy numpy pillow                 # bpy as a Python module; no Blender install needed
python tools/pocket-preview/frame.py out.npy 720 64 lcd.png silver
python tools/pocket-preview/grade.py out.npy out.png
```

`frame.py <out.npy> [width samples lcd.png finish]`: width in pixels (the height follows the
phone's 1440 × 3120), Cycles samples, a picture for the screen or `-` for the screen switched off,
and `silver`, `black` or `red`. It writes linear light; `grade.py` turns that into what Filament
would show. A 720px frame at 64 samples takes a few minutes on a CPU.

## What it is for

**Composition, palette and glare** — where the sheen falls across the face, whether a dark finish
reads against the room, whether anything bright lands in the band the screen's glass reflects. It
is not for a brightness argument: the grade is an approximation of Filament's passes, close
enough that the old device's boxes landed within a few levels of the phone's, never exactly. A
luminance dispute is settled on the phone (`tools/emulator/README.md`).

## Keeping it honest

Three copies of the same numbers exist and must agree:

- **The room**: `tools/models/pocket_light.py` is the one this renders with, and `PocketLighting.kt`
  is the app's. `PocketLightingTest` pins the Kotlin to the Python's output for ten directions —
  change one alone and it fails. Change the Python first, render, then port and re-pin.
- **The finishes**: `FINISHES` in `frame.py` against `PocketFinish.kt`. The roughness and metalness
  are *factors* on the wear map, as glTF's are, so they multiply it here too.
- **The frame**: `framing.json` (the display and the lens) and the picture's place in
  `frame.py` (`LCD_*`) against `PocketFace.kt` and `PocketRoomScreen`.

The face's own numbers — window, wheel, the floor behind the gaps — come from the model, so a
change to `build_pocket_face()` is seen here the moment the `.glb` is rebuilt.
