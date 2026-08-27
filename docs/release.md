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
2. **The first-run screen.** `PLAN.md` §4 calls the empty state "the most
   important screen in the app" and asks for one labelled empty shelf, warm
   lighting and a single obvious way to add the first album. What ships is one
   line of centred text over a black void.
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
