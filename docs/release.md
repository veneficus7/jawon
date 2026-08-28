# Releasing Jawon

## 1. Create your upload key — you, not the build

The signing key is the app's identity on Play. Whoever holds it can ship updates
as Jawon; whoever loses it cannot. It is **not** in this repository and must not
be: `keystore.properties`, `*.jks` and `*.keystore` are gitignored, and a signing
key that reaches a repository has to be replaced.

Run this yourself, once, somewhere outside the repo:

```bash
keytool -genkeypair -v -keystore javon-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias javon
```

It will ask for a password and for your name and organisation. Keep the file and
the password somewhere you will still have them in five years — a password
manager, not this machine's disk alone.

Then create `keystore.properties` at the repository root:

```properties
storeFile=../javon-upload.jks
storePassword=…
keyAlias=javon
keyPassword=…
```

`storeFile` is resolved relative to the repository root. Point it outside the
working tree so an accidental `git add -A` cannot reach it.

**Enrol in Play App Signing** when you first upload. Google then holds the actual
app signing key and yours becomes an upload key — which can be reset if you lose
it. Without that, losing your key ends the app's update path permanently.

## 2. Build

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`, currently ~24MB.

**Upload the `.aab`, not an APK.** Play requires it, and it is also what keeps the
download small: the release APK is 37MB because it carries native Filament and
gltfio libraries for four architectures, including x86 and x86_64, which exist
only for emulators. Play splits the bundle per device, so a phone downloads its
own arm64 slice and nothing else.

`./gradlew assembleRelease` still produces an APK, for sideloading and for
testing the minified build on a device.

## 3. What the release build does differently

- **R8 with resource shrinking.** 103MB debug → 37MB release APK.
- **Signed** with your upload key when `keystore.properties` exists. Without it,
  the build falls back to the *debug* key so the minified build can still be
  installed and tested. Play rejects debug-signed uploads, so this cannot ship by
  accident — but check the log line if you are unsure which key was used.

### Read `proguard-rules.pro` before trimming it

Most of that file is Filament, and it is not defensive boilerplate. Filament's
JNI resolves Java fields **by name** — `View.setDepthOfFieldOptions` hands its
fields straight to native code. R8 renames by default, and the failure is not a
build error: it is a setting that silently stops applying, or a native abort
partway into a frame.

The minified build has been run on device and renders correctly. Any change to
those rules needs the same check — **a green `bundleRelease` proves nothing here.**

## 4. Still outstanding before a first public release

Honest list, in the order they matter:

1. ~~**No GPU quality tiers.**~~ **Built — see `ui/wallscene/RoomQuality.kt`.** The
   first five testers settled the question the measurements could not: three of
   them reported the app "not that smooth", on S23 and S23 Ultra hardware. That
   is a flagship one generation back, not a budget phone, so the single-S25-Ultra
   sample was optimistic and everything below that tier was worse than assumed.

   The tier both **guesses and measures**. `QualityTier.detect` picks a starting
   point from RAM class and API level, biased one notch low; `FrameWatch` then
   watches real frame intervals and steps down once if the guess was optimistic.
   A device database would have been the usual answer and is the wrong one — the
   same SoC throttles differently in different chassis, and the S25 already
   swung between 124fps cool and 88fps hot on one build.

   Two things do **not** follow a mid-session step, because both are baked before
   the first frame: the IBL cubemap and the shadow map size. They use the
   detected tier and take effect on the next launch. Everything on the Filament
   `View` — depth of field, AO resolution, SSCT, the shadow filter — re-applies
   immediately.

   **Still unverified on anything but an S25 Ultra**, which is the point: the
   adaptation exists precisely because those devices cannot be tested here.
2. ~~**The first-run screen.**~~ **Built.** `PLAN.md` §4 asks for one labelled
   empty shelf, warm lighting and a single obvious way to add the first album;
   that is what a new library now opens on. The fix underneath it was separating
   "nothing loaded yet" from "nothing in the library" — one `WallLayout.loaded`
   flag, previously conflated, which is why an empty library could never render
   anything.
3. **Effectively no tests.** One file, and it is the template's `assertEquals(4,
   2 + 2)`. `WallLayout.from`, `OpenSequence`, `ShelfHover` and `Scratch` are
   deliberately pure functions with no Android or Filament types in them — the
   shelf-bounds maths, the animation curves and the scrub cursor — and they are
   free to test.
4. **Play listing assets**: a 512×512 icon, a feature graphic, screenshots, and a
   privacy policy. Jawon collects nothing and has no network access, which makes
   the policy short but not optional.
5. **`versionCode` is still 1.** Bump it on every upload; Play refuses a repeat.

## 5. Devices it has actually run on

Five, all Samsung, all recent:

| device | result |
|---|---|
| Galaxy S23 ×2, S23 Ultra | runs; reported "not that smooth" |
| Galaxy S25 Ultra ×2 | runs; every number in `ENGINEERING.md` comes from these |
| Galaxy Note 9 | **would not install** |

### The Note 9 did not crash — it was refused

"There was a problem parsing the package" is the installer declining the APK
before any of this code runs. The cause is `minSdkVersion 29`: the Note 9's last
Android update is 10, which *is* API 29, so it installs only if that update was
taken. On Android 9 it cannot.

Signing is not the cause — the APK carries v2/v3 signatures and that device
supports both.

**Lowering the floor is a real decision, not a one-line fix.** minSdk 29 is a
locked decision in `ENGINEERING.md` because it is where scoped storage settles: at 28
the import path needs a second, legacy branch. And the device it would buy is a
2018 phone that would have to render this room — the tier system would put it at
LOW and it would still likely disappoint. Worth doing if Play Console later shows
a real population there; not worth doing for one tester who can take an OS
update instead.

## 6. On-device verification log

Galaxy S25 Ultra, debug build, 2026-08-27. Thermal Status 2 for most of it, so
**no performance numbers were taken** — see `ENGINEERING.md` on why hot-device fps is
worthless. These are behavioural checks only.

| check | result |
|---|---|
| Press and hold the disc, no movement | `PLAYING` → `PAUSED` (`speed=0.0`) → `PLAYING` on release. **The reported bug is fixed.** |
| Notification identity | `appIcon:Icon(pkg=com.jawon.app id=0x7f06000d)`, `songTitle:Find Your Love` — the app's own mark, not Media3's note |
| Magnet snap on a tap | Before fix: one tap moved a half-off-screen case to centre. After: wall does not move |
| Launch alignment | Before fix: opened with first case half off the left edge. After: opens framed on a record |
| Open sequence, settled | Disc sharp edge to edge, tray centred, background correctly defocused |
| Depth of field on the disc | No blur bleeding over the disc's top edge — the original M11 complaint stays fixed |
| Underlight per album | Coloured pools under each case, blooming |

**Not yet verified:** zoom limits, the empty/first-run screen (the test library has
albums), quality tiers on anything but this device, and the delete dialog's full
path now that the discard animation is reverted.

**One unexplained observation.** On the first open of a session the case opened
and then closed itself about two seconds later, with no second input. It did not
reproduce on the next three attempts and playback was running normally
afterwards. Not chased down; noted here so it is not forgotten if it resurfaces.

### Correction: album deletion is NOT verified

An earlier pass through this read as a successful deletion — a case confirmed via
the Remove dialog and then absent from the wall. Reading the database afterwards
shows all four albums still present, so **the case was not deleted, it had simply
scrolled out of frame** when the wall panned to another shelf. The library spans
four shelves and the camera frames roughly one row, so "the case is gone" is not
evidence of anything on its own.

Verify against the database, not the wall:

```bash
adb shell "run-as com.jawon.app cat databases/javon.db" > out.db
```

(`sqlite3` is not on the device; copy the file off and read it locally.)

What this leaves genuinely unknown: whether `deleteAlbum` runs at all. The dialog
appears and is correctly worded, but nothing past the confirm button has been
observed working. It was not chased further because the only albums available to
test on are real ones the owner would have to re-import.

## 7. Where import stands

Both of the things that made adding an album tedious are fixed, and neither has
been exercised against a large library.

- **Covers come from the files.** Embedded art is pulled out of the tracks' own
  tags during the scan, so the Add Album screen opens with the cover already
  chosen. Only the first five files are probed — art is a property of an album,
  not a track.
- **A folder of folders imports as many albums.** The scanner has always
  recursed; grouping by containing folder is what stops that collapsing a whole
  library into one enormous "album", which is what it used to do to anyone who
  pointed Jawon at their `Music` directory.

Untested at scale: a library of hundreds of albums, and any folder layout more
exotic than one-album-per-folder.
