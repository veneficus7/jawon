# Custom Filament materials

**Jawon ships five.** Three in the CD room — dust, fog and the neon sign (`neon.mat`,
additive, drawn from a two-channel mask) — and two in the pocket player's:

- `materials/night_window.mat` → `assets/materials/night_window.filamat`, the train window
  behind the device: out-of-focus lights at three depths, poles, the sky, and the carriage
  light's reflection on the glass, **drawn as a lens renders them** rather than as scenery to be
  blurred. A depth-of-field pass cannot reach the circle of confusion a 50mm lens focused at
  30cm throws on a light at infinity — about a fifth of the frame — and this is a handful of
  discs per pixel. The room integrates the train's speed on the CPU and hands each layer's
  scroll in, wrapped at exactly the shader's `PERIOD` of cells so the value stays small and the
  wrap is seamless (`NightWindow`, `TrainMotion`). **SceneView's `PlaneNode` runs V downward**:
  the first build hung the sky under the device, which the luminance boxes caught first.
  **And the lens clips them toward its edge**: each disc is the overlap of two circles, the
  second offset outward by up to 0.7 of its radius, so a light is round in the middle of the
  picture and a cat's eye lying along the top and bottom edges, as a real lens's defocused
  lights are. `frame` — half the frame's height at the window's distance — is the only thing
  it needs from the room, which works it out from the camera. **And the stations**: when the
  music pauses the train stands at a platform (`TrainMotion`), and `station` — how far ahead of
  the window the platform's middle is, and a clock — draws it: fluorescent tubes as capsules
  under a canopy, a blue name sign, backlit posters, a waiting room, the concrete pooled under
  each tube and its yellow edge line, pillars, each part scrolled by its own depth. Only
  evaluated when a station is within 90m. **Laid out for what the room's views frame, which is
  very little**: the screen is portrait, so only about ±0.1m of the picture is seen across — at
  rest x −0.08…+0.15, y −0.27…+0.01; in the hand, the margins round the device. The first two
  layouts were drawn for the window's 0.8m opening and put everything out of frame; a numpy port
  of the station, cropped to those two views, is what found where things had to go. **And the
  tunnels** (`tunnel`: the mouth and the exit ahead of the window, and the speed): the wall close
  outside, near black, two cable runs, and its sodium lamps — drawn as the streak a 1/60s shutter
  makes of each at speed, the same light spread along the distance it moved, and as a disc at a
  crawl. At 16m/s a lamp crosses the frame in about a frame, which is what a tunnel's lights do.
- `materials/polished_steel.mat` → `assets/materials/polished_steel.filamat`, the device's steel
  shell and the bead round its face, put on that one part in place of the glTF's own material
  when the model loads. It exists for one line of shader: **roughness floored at the pixel's
  footprint on the curve**, `footprint × length(fwidth(normal))`. The bead turns 90° in ~16px, so
  a ceiling run reflected in it is thinner than a pixel and rasterises as a dashed line that
  crawls. gltfio's own `base_lit_opaque` lists `_specularAntiAliasingVariance` among its
  parameters, and raising it changed nothing — a probe at 1000 left every pixel on the bead as it
  was — so the filtering is done where it can be seen to work. Flat steel keeps its 0.055.

And the CD room's first two:

- `materials/dust.mat` → `assets/materials/dust.filamat`, which animates every
  dust mote in the vertex shader from a single `time` uniform — and, since
  2026-09-12, lights each mote in its fragment shader from where the room's
  fittings are (the strips and the ceiling spots), because the owner asked for
  dust that catches the light and an unlit additive sprite cannot be shaded by
  Filament's own lights.
- `materials/fog.mat` → `assets/materials/fog.filamat`, a raymarched volumetric
  fog lit by the same fittings. **Built and parked**: switched off at the owner's
  call behind `RoomAir.FOG_ENABLED`, after a first tuning that veiled the room.

Both use `getUserWorldPosition()` for anything positioned in the room: Filament's
own world space in shaders is camera-relative, so comparing it against a fitting's
position would be wrong by exactly where the camera is. Everything else in the
room uses SceneView's stock materials.

The `matc` used for both was Filament 1.72.1's, extracted to
`C:\Users\loh\tools\filament-1.72.1\bin\matc.exe` on the owner's machine (outside
the repo, as below).

## What the stock materials can and cannot do

Worth knowing before reaching for `matc`, because it decides whether you need it
at all. The `MAT_UIB` chunk of SceneView 4.32.0's stock materials lists the
entire settable parameter set:

| material | parameters |
|---|---|
| `opaque_colored` | `color`, `metallic`, `roughness`, `reflectance` |
| `opaque_textured` | `metallic`, `roughness`, `reflectance`, + `texture` sampler |

**That is all.** There is no `clearCoat`, no `anisotropy`, no normal map, no
emissive.

**And a third stock material matters more than either of those: `image_texture`,
which `ImageNode` uses, is UNLIT.** Its `MAT_SHAD` chunk reads `0x00` where
`opaque_textured`'s reads `0x01`. Nothing in the API says so, and the consequence
is total — an `ImageNode` writes its bitmap straight to the output and no light in
the scene reaches it. Every album cover in the room was rendered that way through
four rounds of lighting work. If a surface has to respond to the room, it cannot be
a plain `ImageNode`; bind a lit instance to the node's own texture instead, which is
what `CaseSlots` now does. The shading model is the first thing to check when a
surface refuses to respond to light, and the quickest tell is size — a lit material
compiles every lighting variant and runs 1.2-1.4MB, an unlit one 15-115KB.

Reading these chunks is a dozen lines of Python and beats guessing. The `.filamat`
container is a flat sequence of chunks: an 8-byte type stored as a little-endian
`uint64` (so the ASCII reads *reversed* in a hex dump — `SREV_TAM` is `MAT_VERS`),
then a `uint32` size, then the body. Those names *do* appear elsewhere in the `.filamat` blob, around offset
40k — that is Filament's `MaterialInputs` GLSL struct, which every standard
material declares whether or not it exposes the field. Grepping the blob for a
parameter name will mislead you; read the `MAT_UIB` chunk in the header instead.

The practical consequence: realistic jewel-case plastic (which wants clearcoat)
and a realistic CD surface (which wants tangential anisotropy for its circular
sweep highlight) are **impossible with stock materials**.

There are two ways out, and the second is usually better:

1. Write a custom `.mat` — the procedure below, and a ~735MB `matc` download.
2. **Import a glTF model instead.** `gltfio` 1.72.1 supports
   `KHR_materials_clearcoat`, `KHR_materials_anisotropy`, `_ior`, `_specular`,
   `_transmission`, `_volume`, `_sheen` and `_iridescence`, so the asset carries
   its own materials and no `matc` is involved. Also
   `KHR_draco_mesh_compression` and `KHR_texture_basisu` for size.

## History

M8 shipped a hand-written iridescent CD surface — `disc_iridescent.mat` — which
`PLAN.md`'s technology table asks for. **It was removed at the owner's request:
the rainbow was not wanted on the disc.** With the effect gone, SceneView's stock
lit textured material did everything that remained, so the custom material was
removed rather than kept switched off, which also took 577 KB of `.filamat` out
of the APK.

Note that the removed effect was *iridescence* — diffraction from the data
spiral, which throws colour. That is not the same as **anisotropy**, the
direction-dependent stretching of a highlight along the track, which is
colourless and is what makes a real CD's reflection sweep in an arc. The owner
ruled out the rainbow, not the sweep. The latter is still wanted.

It is in git at commit `9df705b` if it is ever worth revisiting.

If you add a material, commit **both** the `.mat` (the reviewable artefact) and
the built `.filamat`, the latter because nothing in the build can produce it (see
below). SceneView ships its own materials the same way, under `assets/materials/`.

## Why there is no Gradle task for this

Filament's official `filament-gradle-plugin` — the thing that would compile
`.mat` files as part of the build — **is not published to Maven Central or to the
Gradle Plugin Portal.** Both return 404. There is no artifact to depend on, at
any version.

So a material has to be compiled by hand and its output committed. A build that
silently depended on a 735 MB tool no clone of this repo has would be worse than
a documented manual step for a file that changes maybe twice a year.

This does **not** violate ENGINEERING.md rule 4 ("no new 3D or animation
dependencies"). `matc` is a build-time tool run by hand; nothing is added to the
app, the APK, or the Gradle build.

## Getting matc

`matc` ships only inside Filament's platform release tarballs, and **its version
must match the Filament runtime.** SceneView 4.32.0 pulls
`com.google.android.filament:filament-android:1.72.1`. A `.filamat` blob carries
a format version, and a blob built by a different release of `matc` is rejected
at load time.

Download the matching release from
<https://github.com/google/filament/releases/tag/v1.72.1>:

- Windows: `filament-v1.72.1-windows.tgz` (~735 MB)
- Linux: `filament-v1.72.1-linux.tgz` (~53 MB)
- macOS: `filament-v1.72.1-mac.tgz` (~45 MB)

Only `bin/matc` (or `bin/matc.exe`) is needed — extract just that and discard the
rest:

```
tar -xzf filament-v1.72.1-windows.tgz bin/matc.exe
```

**Do not commit the tarball or the binary.** Keep them outside the repo.

## Compiling a material

From the repository root, with the `.mat` under `app/src/main/materials/` and the
output going to `app/src/main/assets/materials/`:

```
matc.exe --api opengl --api vulkan --platform mobile -Os \
  -o app/src/main/assets/materials/<name>.filamat \
     app/src/main/materials/<name>.mat
```

Note it is `-Os` / `--optimize-size`, **not** `--optimize size` — the latter makes
`matc` read `size` as a second input file and fail with "Only one input file
should be specified".

`--api opengl --api vulkan` covers what Filament uses on Android; `metal` would
only bloat the blob. `matc` prints nothing on success.

To check the parameter names the Kotlin side must use:

```
matc.exe --reflect parameters app/src/main/materials/<name>.mat
```

Then rebuild the app normally — the `.filamat` is picked up as an ordinary asset.

Load it with `materialLoader.createMaterial("materials/<name>.filamat")` followed
by `createInstance(...)`; samplers are set with
`setParameter(name, texture, TextureSampler())`.

## If a Filament upgrade breaks a material

Bumping SceneView (and with it `filament-android`) can change the `.filamat`
format. The symptom is the material failing to load at runtime. The fix is to
download the `matc` matching the *new* Filament version and recompile; the `.mat`
source itself will usually be unchanged.

## Traps worth knowing before writing a shader

Found while building (and then removing) the disc material:

- **Fresnel weighting does not port from the prototype.** Its disc is a lathe
  with a curved rim, so its normals vary. `DiscGeometry` is a flat annulus —
  every normal is `+Z`, and a 120 mm disc at 0.55 m spans `dot(N,V)` of 1.0 down
  to 0.994. Any `pow(1 - |N·V|, k)` term is ~0 across the whole face.
- **`atan2` leaves a seam.** An angular term wraps at ±π and the discontinuity is
  plainly visible once the disc fills the frame.
- **`emissive` is in nits.** A value of 1.0 is enormous — it buried the album art
  entirely. Effects layered over artwork want something nearer 0.05.
- Polar coordinates are free from `DiscGeometry`'s UVs: they are radial and
  normalised, so `uv * 2 - 1` gives a vector whose length is `r/R`.
