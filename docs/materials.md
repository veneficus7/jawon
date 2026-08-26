# Custom Filament materials

Javon has one hand-written Filament material: the iridescent CD surface.

| | |
|---|---|
| Source | `app/src/main/materials/disc_iridescent.mat` |
| Built output | `app/src/main/assets/materials/disc_iridescent.filamat` |
| Loaded by | `ui/wallscene/WallSceneScreen.kt` (`DISC_MATERIAL_ASSET`) |

**Both files are committed.** The `.mat` is the reviewable artefact; the
`.filamat` is a build output that is checked in because nothing in the build can
produce it (see below). SceneView ships its own materials the same way, under
`assets/materials/`.

## Why there is no Gradle task for this

Filament's official `filament-gradle-plugin` — the thing that would compile
`.mat` files as part of the build — **is not published to Maven Central or to the
Gradle Plugin Portal.** Both return 404. There is no artifact to depend on, at
any version.

So the material is compiled by hand, and the output committed. A build that
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

## Rebuilding the material

From the repository root, after editing the `.mat`:

```
matc.exe --api opengl --api vulkan --platform mobile -Os \
  -o app/src/main/assets/materials/disc_iridescent.filamat \
     app/src/main/materials/disc_iridescent.mat
```

Note it is `-Os` / `--optimize-size`, **not** `--optimize size` — the latter makes
`matc` read `size` as a second input file and fail with "Only one input file
should be specified".

`--api opengl --api vulkan` covers what Filament uses on Android; `metal` would
only bloat the blob. `matc` prints nothing on success.

To check the parameter names the Kotlin side must use:

```
matc.exe --reflect parameters app/src/main/materials/disc_iridescent.mat
```

Then rebuild the app normally — the `.filamat` is picked up as an ordinary asset.

## If a Filament upgrade breaks the disc

Bumping SceneView (and with it `filament-android`) can change the `.filamat`
format. The symptom is the disc failing to load its material at runtime. The fix
is to download the `matc` matching the *new* Filament version and rerun the
command above; the `.mat` source itself will usually be unchanged.
