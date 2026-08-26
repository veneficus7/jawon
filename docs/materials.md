# Custom Filament materials

**Javon currently has none.** Everything in the room uses SceneView's stock
materials.

This document is kept because the procedure was worked out the hard way and is
not obvious, so anyone adding a custom shader later does not have to rediscover
it.

## History

M8 shipped a hand-written iridescent CD surface — `disc_iridescent.mat` — which
`PLAN.md`'s technology table asks for. **It was removed at the owner's request:
the rainbow was not wanted on the disc.** With the effect gone, SceneView's stock
lit textured material did everything that remained, so the custom material was
removed rather than kept switched off, which also took 577 KB of `.filamat` out
of the APK.

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
