# Releasing Jawon

## 1. Create your upload key — you, not the build

The signing key is the app's identity on Play. Whoever holds it can ship updates
as Jawon; whoever loses it cannot. It is **not** in this repository and must not
be: `keystore.properties`, `*.jks` and `*.keystore` are gitignored, and a signing
key that reaches a repository has to be replaced.

Run this yourself, once, somewhere outside the repo.

**`keytool` is not on the PATH on this machine.** There is no system JDK — the
one Java installation is the runtime bundled with Android Studio, which is also
why every Gradle command here needs `JAVA_HOME` pointed at it. Call `keytool` by
its full path:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore "C:\Users\loh\AndroidStudioProjects\javon-upload.jks" -keyalg RSA -keysize 4096 -validity 10000 -alias javon
```

It will prompt for a keystore password, then your name and organisation, then a
key password (pressing Enter reuses the keystore one, which is fine). **Run it in
a terminal you are typing in** — it reads the password from the console, so it
cannot be run from a script.

Keep the file and the password somewhere you will still have them in five years —
a password manager, not this machine's disk alone. Everything the app can ever be
updated to depends on this one file; see §4.

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

- **R8 with resource shrinking.** 104MB debug → 37MB release APK (0.3 beta).
- **Signed** with your upload key when `keystore.properties` exists. Without it,
  the build falls back to the *debug* key so the minified build can still be
  installed and tested. Play rejects debug-signed uploads, so this cannot ship by
  accident — but check the log line if you are unsure which key was used.

**CI runs this build on every push** — `.github/workflows/ci.yml`, alongside the
unit tests, and deliberately not only in the debug variant. A green debug build
says nothing about the risk in §3 below: Filament's JNI resolves Java fields by
name, so an over-eager R8 rule fails at *runtime*, on a device, as a native abort.
CI catches the half of that which is a build error, which is not the whole risk but
is the half that used to reach a phone before anyone noticed.

The fallback above is what makes this possible at all: `keystore.properties` is
gitignored and absent on CI, and the release build simply degrades to the debug
key rather than failing. Verified — the APK CI produces is signed
`CN=Android Debug`. It is uploaded as a build artifact for seven days, so a tester
can be handed a build without anyone opening Android Studio. **It is not a
release**, for the reason in the paragraph above it.

### Read `proguard-rules.pro` before trimming it

Most of that file is Filament, and it is not defensive boilerplate. Filament's
JNI resolves Java fields **by name** — `View.setDepthOfFieldOptions` hands its
fields straight to native code. R8 renames by default, and the failure is not a
build error: it is a setting that silently stops applying, or a native abort
partway into a frame.

The minified build has been run on device and renders correctly. Any change to
those rules needs the same check — **a green `bundleRelease` proves nothing here.**

## 4. Shipping an update to testers

Since 0.3 the app checks for its own updates: Settings → **Check for updates**
reads a small JSON file from a public repository, and offers to download and
install anything newer. See `data/update/UpdateChecker.kt`.

### The signing key is the whole thing

**Do not distribute a build signed with the debug key.** Android only allows an
app to be updated by an APK signed with the *same* key as the one installed. A
tester who installs a debug-signed Jawon can never be updated to a properly
signed one — they have to uninstall first, which destroys their library, their
shelves and their listening history, none of which can be recovered.

The debug key is also not yours in any meaningful sense: it is generated by the
SDK, its password is `android`, and it is identical in spirit on every machine.

So: do §1 before anyone but you installs anything. After that the key must never
change, for the life of the app.

This cuts the other way too, and it is the reason a self-updater is defensible at
all: because Android enforces the signature match, a tampered download cannot
replace Jawon with something else. It can only fail to install.

### The releases repository

Public, and separate from this one. The source stays private; an updater cannot
authenticate to a private repository without shipping a token inside the APK,
where anyone can read it back out.

```bash
gh repo create javon-releases --public --description "Jawon beta builds"
```

### Publishing a build

1. Bump `versionCode` **and** `versionName` in `app/build.gradle.kts`. The code
   is what decides whether something counts as an update; the name is only for
   people to read.
2. `./gradlew assembleRelease`, then confirm it is signed with your key, not the
   debug one:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

   If the certificate says `CN=Android Debug`, `keystore.properties` is missing
   and the build fell back — stop and fix that before going further.

   The real one, from 0.3 beta onward, is:

   ```
   CN=Bakhodirjon Murodillaev, OU=Jawon, O=Veneficus, L=Suwon, ST=Gyeonggi-do, C=KR
   SHA-256: 8c74a0400a79122041876e47c0bf98912ff257a084c656b20a6bb23a8f9f8766
   ```

   That digest is recorded here so a future build can be checked against it
   rather than against memory. **If it ever differs, stop** — a different key
   means every existing install has reached the end of its update path.

3. Run it on a device. R8 can break Filament by name at runtime, and a green
   build says nothing about it (§3).
4. Attach the APK to a GitHub release — **never commit it.** A 37MB binary in the
   tree is 37MB in every clone forever, and git cannot forget it without
   rewriting history. `*.apk` is gitignored in the releases repo for that reason.

```bash
gh release create v0.4 jawon-0.4-beta.apk --title "Jawon 0.4 beta" --notes "…"
```

5. Then write `latest.json` — after the release exists, so the asset URL is real:

```json
{
  "versionCode": 4,
  "versionName": "0.4 beta",
  "notes": "One or two lines, shown in Settings before the download.",
  "apkUrl": "https://github.com/veneficus7/javon-releases/releases/download/v0.4/jawon-0.4-beta.apk",
  "sizeBytes": 39280089
}
```

   `versionCode` must match the APK exactly. The app compares that number and
   nothing else, so getting it wrong either hides the release or offers it
   forever.

6. Push. `latest.json` is read from the default branch, not from the release, so
   unlike the APK it does have to be committed.

### It will not appear for about five minutes

`raw.githubusercontent.com` serves through a CDN with `Cache-Control: max-age=300`,
and it honours neither a cache-busting query string nor a `no-cache` request
header — both were tried. Edges also expire independently, so one device can see
the new manifest while another still sees the old one.

**This is not a bug and there is nothing to fix.** Nobody checks for an update in
the five minutes after a release they have not been told about yet. It is
recorded because the symptom — publishing a release and having the app insist it
is up to date — looks exactly like a broken comparison, and cost a round of
debugging here before the cause was measured.

The GitHub API returns the file uncached, and is how to confirm a push actually
landed:

```bash
gh api repos/veneficus7/javon-releases/contents/latest.json --jq '.content' | base64 -d
```

### What testers see

Nothing automatic. The check is a button, the download is a button, and the
install is the platform's own prompt — an app that quietly replaces itself is not
something to build without being asked.

Each tester grants "install unknown apps" for Jawon once, the first time. The
app detects that it lacks the permission and offers a link straight to the
setting rather than failing at the end of a download.

### Before any Play release

Remove `REQUEST_INSTALL_PACKAGES` from the manifest and drop this whole
mechanism. Play restricts that permission to apps whose core purpose is
installing others, and on Play the updating is Play's job anyway.

## 5. Still outstanding before a first public release

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
3. ~~**Almost no tests.**~~ **383, across 33 files.** `CrossfadeTest` is long
   past being alone: the wear curve, the shelf-bounds maths, the animation
   curves, the scrub cursor, the volume levelling, the whole rack DSP, the
   scratch tone, the lyrics parsers and the LRCLIB client, the wall's sort and
   search, and `LibraryBackup` — which this section used to name as untested and
   is not any more.

   **Two of them found real mistakes while being written**, both in the test
   rather than the code, which is its own kind of useful: an assertion that
   compared shard *positions* when the pieces go on drifting by design, and one
   that expected a −20dB tag to halve twice over when the clamp stops it at −15dB.
   A third pinned behaviour that had never been stated anywhere — that a zero
   length track does not trap the scrub cursor.

   What is still untested is everything touching Android or Filament, which is
   most of the app: the scene, the DAOs, the importer, the backup writer. Those
   need instrumentation or Robolectric, and neither is set up.
4. ~~**No way to hear about a crash.**~~ **Built — `data/diagnostics/CrashLog.kt`.**
   An uncaught-exception handler writes to `filesDir/diagnostics/crash.log`, and
   Settings → **PROBLEMS** shows the log and offers to share it.

   This was the largest hole in the tester loop and it is worth being clear about
   why. Fifteen-odd people have run Jawon, and the total signal that ever came back
   was three of them saying it was "not that smooth". A crash on any of those phones
   would have been *completely silent*: there is no analytics by design, testers are
   not going to run `adb`, and logcat is unusable on Samsung anyway (trap 11).

   Non-fatals go through the same file. `onPlayerError` is the motivating case —
   the wedged pipeline in trap 15 presents as a deck stuck at `0:00` and a hot
   phone, and the throwable behind it previously reached only `Log.e`.

   **Still needs one check on a device**, and no unit test can supply it: force a
   crash, confirm the section fills in, confirm SEND opens a share sheet with a
   readable file. Only the string handling is covered by tests.

5. **Play listing assets**: a 512×512 icon, a feature graphic, screenshots, and a
   privacy policy.

   ~~**The policy.**~~ **Written — `docs/privacy.md`.** But it is not *published*,
   and that is a real remaining step rather than a formality: **this repository is
   private**, so a link to the file here resolves to a 404 for Google's reviewer
   and for every user. Play requires a URL anyone can open.

   Publish it to the releases repository, which is already public and already
   exists for exactly this class of problem:

   ```bash
   gh api repos/veneficus7/javon-releases/contents/PRIVACY.md \
     -f message="Privacy policy" \
     -f content="$(base64 -w0 docs/privacy.md)"
   ```

   The URL for the Play console and for the listing is then
   `https://github.com/veneficus7/javon-releases/blob/main/PRIVACY.md`. GitHub
   Pages on that repository would be tidier if it ever wants a domain, but a
   rendered Markdown file at a stable public URL is sufficient and is what Play
   accepts.

   **Keep the copy in this repo as the source.** It has to be versioned alongside
   the code that makes it true — a policy that drifts from the app is worse than a
   missing one — and republishing is the one command above.

   The policy was written against the code, not from memory: every claim in it was
   checked in `CoverArtFetcher`, `LrclibClient`, `UpdateChecker`, `AppPreferences`
   and the manifest. Three things leave the device:

   - **Cover art lookup.** An album title and artist go to MusicBrainz, and an
     image comes back from the Cover Art Archive — only for albums whose own
     files carry no artwork, and only while the Settings switch is on. No id, no
     account, no listing of the library. See `data/importer/CoverArtFetcher.kt`.
   - **Lyrics lookup.** A title, an artist, an album and a duration go to
     **LRCLIB** for tracks whose own file carries no words and which have no
     `.lrc` sidecar. Asked only when the lyrics page is actually opened, and
     cached so a track is never looked up twice. See `data/lyrics/LrclibClient.kt`.
     **Note for the form: this switch defaults to *on*** — `KEY_FETCH_LYRICS` in
     `AppPreferences` is `true` — so it is opt-*out*, not opt-in, and the policy
     has to describe it that way.
   - **The update check.** A request to `raw.githubusercontent.com` for a version
     manifest, and a download from GitHub if the user asks for one. No data of
     the user's is sent. See `data/update/UpdateChecker.kt`.

   All three are user-initiated or switchable, none is analytics, and nothing is
   collected or retained by Jawon. But the **Play data-safety form has to say so**
   rather than claiming no network use, and the policy has to describe all three.

   Also declare, or remove, `REQUEST_INSTALL_PACKAGES` — Play restricts it to
   apps whose core purpose is installing others, which Jawon's is not. §4 says to
   drop the whole self-update mechanism for a Play build.
6. **Permissions to account for on the form**, all added since 0.2:
   `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` (the device scan),
   `MODIFY_AUDIO_SETTINGS` (the equaliser), `INTERNET`, and
   `REQUEST_INSTALL_PACKAGES`.

### Answering the data safety form

The form is where an app with nothing to hide still gets this wrong, because its
questions are about *categories* rather than about what the code does. The answers
for Jawon, with the reasoning, so the next person filling it in does not have to
re-derive them:

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | "Collect" in Play's definition means transmitted off the device. Nothing is. The three lookups send an album title, an artist and a track length — which are facts about a published recording, not about the user, and Play's data types do not cover them. |
| Does your app use encryption in transit? | **Yes** | All three endpoints are HTTPS, and `UpdateChecker` explicitly refuses a non-HTTPS APK URL. |
| Can users request that data be deleted? | **Yes** | Uninstalling removes everything. There is no account and no server copy. |
| Data types: Files and docs / Music files | **Not collected** | Jawon *reads* audio the user points it at. Reading is not collecting; nothing about those files leaves the phone. |
| Crash logs / Diagnostics | **Not collected** | Deliberate, and worth being careful about. `CrashLog` writes to the device and stays there. A user may choose to share one through the system share sheet, but Jawon transmits nothing on its own, and the form asks about what the *app* does. If crash reporting is ever made automatic, **this answer changes**. |

**The one that needs a decision, not an answer:** `REQUEST_INSTALL_PACKAGES`. Play
restricts it to apps whose core purpose is installing other apps, which Jawon's is
not, and declaring it invites a rejection that costs a review cycle. §4 says to
drop the self-updater entirely for a Play build — on Play, updating is Play's job
and the mechanism is redundant. Do that before the first upload rather than
arguing the exemption.

Note the consequence for existing testers: they are on directly-installed builds
whose update path *is* the self-updater. Removing it does not break their install,
but a Play build and a sideloaded build are then two populations, and only one of
them updates itself.

## 6. Devices it has actually run on

**Over fifteen now**, and no longer all Samsung or all recent. This section used to
read "five, all Samsung, all recent" and it was the biggest open risk in the
project; it is not any more.

| device | result |
|---|---|
| Galaxy S25 Ultra ×2 | runs; every measured number in `ENGINEERING.md` comes from these |
| Galaxy S23 ×2, S23 Ultra | ran, and reported "not that smooth" — the reports that caused the quality tiers to be built |
| Galaxy Note 8 | **runs with no reported lag.** Android 9 class hardware, 2017. |
| Redmi 9 | **runs with no reported lag.** Budget hardware, Helio G80. |
| ~8 more | run; no lag reported |

**What this changes.** The tiers were built because three of five testers on
S23-class hardware reported stutter, and the honest position afterwards was that
nothing below an S25 Ultra had been confirmed. That has now been answered from the
other end: the app runs on a Note 8 and a Redmi 9, both far below anything the
tiers were designed to rescue. Two consequences worth writing down —

- The "unverified below a Galaxy S23" caveat throughout these documents is spent.
  Stop repeating it.
- The *lowest* tier has still never been the one anybody needed, which means it is
  the least-exercised code path in `RoomQuality.kt` rather than the most. If a
  device ever does drop to it, treat it as untested.

The one thing this does **not** establish is what the S23 reports were actually
about, since those devices sit above the ones now running smoothly. Thermal state
(trap 8) is the obvious candidate and was never ruled out.

### The Note 9 did not crash — it was refused

"There was a problem parsing the package" is the installer declining the APK
before any of this code runs. The cause was `minSdkVersion 29`: the Note 9's last
Android update is 10, which *is* API 29, so it installed only if that update had
been taken. On Android 9 it could not.

Signing is not the cause — the APK carries v2/v3 signatures and that device
supports both.

**This section used to argue against lowering the floor, and the argument was
wrong.** It said minSdk 29 was where scoped storage settles and that going to 28
meant a second legacy storage branch in the import path. That is true of many
apps and was never true of this one: every file Jawon touches arrives as a
content URI through SAF or MediaStore, never as a filesystem path, so there was
no legacy branch to write. Lowering it was one line, and it is now **28**.

What that does *not* buy is confidence. It bought an install that crashed.

**Every release from 0.2 to 0.4.1 died on launch on any device below API 31**, and
the floor was never exercised until 0.4.2. `drawable/splash_static.xml` wrapped a
VectorDrawable in `<bitmap android:src>`, which is an inflation failure rather than
a degradation; it is `android:windowBackground` in `values/themes.xml`, so the
framework resolved it inside `PhoneWindow.generateLayout` and the process died
before any of this app's code ran. `values-v31/themes.xml` overrides
`windowBackground` with the real SplashScreen API, so API 31+ never inflates the
file — and every device this had ever been run on was API 31+. The Note 9 tester
was handed an app that installed and then instantly closed.

The lesson is not about splash screens. It is that **a version-qualified resource
directory is an untested code path by construction**, and this project had a
`values-v31` with nothing below it ever run.

### The Android 9 emulator, 2026-08-31

Now part of the routine, because it costs a few minutes and it caught the above.

AVD: `system-images;android-28;default;x86_64`, `hw.ramSize=3584` so `MemTotal`
reports 3.36GB and `QualityTier.detect` takes its `totalGb < 4.0` branch to **LOW** —
the same branch a real 4GB Redmi 9 or Galaxy S9 takes, both of which report ~3.7GB.
1080×2340. **`GLESDynamicVersion` must be on** (`~/.android/advancedFeatures.ini`
or `-feature GLESDynamicVersion`) or the guest hands Filament an OpenGL ES 2.0
context and it aborts the process on `Texture format 46 not supported` at feature
level 0. That abort is an emulator artifact — every device at this API floor is
GLES 3.0+ — but it is indistinguishable from a real failure if you have not seen it
before.

| check | result |
|---|---|
| Install on API 28 | yes |
| Launch | **crashed before the fix; clean after** |
| Filament init | GLES 3.1, feature level 1, no R8 name breakage |
| First-run screen | renders, room lit behind it |
| Device scan | 1 album, 8 tracks found |
| Import, incl. the cover-art disclosure | album landed on the shelf |
| Wall | case levitating and turning, underlight pooling, dust |
| Open sequence | full five beats, disc lifted and spinning |
| Playback | `PlaybackState state=3`, speed 1.0 |
| Quality readout | `RAW 16-BIT / 22.0 KHZ` — correct for the source |
| Memory | native heap 102MB, Dalvik 12MB, **zero low-memory kills** |

### The Android 10 emulator, same day

`system-images;android-29;default;x86_64`, same 3584MB and 1080×2340, `hw.lcd.density=400`
— a Redmi 9 profile. Same `GLESDynamicVersion` requirement.

Worth running **as well as** API 28, not instead of it, for one reason: API 29 is where
scoped storage begins, which is the one platform change that could plausibly break the
MediaStore scan. It does not — the scan, the import and playback all work — but that was
worth knowing rather than assuming.

The published 0.4.1 was installed here first, deliberately, and **crashed identically**:
same `XmlPullParserException`, same line 12. The blast-radius claim above is therefore
measured on two API levels, not extrapolated from one.

| check | result |
|---|---|
| 0.4.1 (published) | **crashed on launch, identically to API 28** |
| 0.4.2 | clean |
| MediaStore scan under scoped storage | works — album found, 8 tracks |
| Import | landed on the shelf |
| Wall, open sequence, disc | all correct |
| Playback + auto-advance to track 2 | `state=3`, speed 1.0 |
| Equaliser | 5 bands read off the device, presets, gains applied, no `audiofx` errors |
| Quality readout | `RAW 16-BIT / 44.1 KHZ · LOSSLESS · SPEAKER` |
| Memory | native 126MB, Dalvik 9MB, zero low-memory kills |

Note the output rate differs between the two AVDs (48 kHz on API 28, 44 kHz on API 29).
That is the emulator's audio config, not a bug — the readout is reporting what the device
actually gives it, which is the whole point of that line.

**What none of this tells you is speed.** The emulator draws on the host GPU — here an
RTX 5060 — so any frame rate it reports is fiction for a Mali-G52 or a Mali-G72. LOW tier
is now known to *work*, on two API levels, end to end; whether it is *smooth* on 2018–2020
hardware is still untested, and is still the open question the tiers exist for. Only a real
Redmi 9 or S9 answers it.

## 7. On-device verification log

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
albums), and the delete dialog's full path. Quality tiers are no longer on this
list: the app has since run on 15+ devices with no reported lag, down to a Redmi 9
and a Galaxy Note 8.

**One unexplained observation.** On the first open of a session the case opened
and then closed itself about two seconds later, with no second input. It did not
reproduce on the next three attempts and playback was running normally
afterwards. Not chased down; noted here so it is not forgotten if it resurfaces.

### The lesson that produced this section still stands

An early pass read as a successful deletion — a case confirmed via the Remove
dialog and then absent from the wall — and the database afterwards showed every
album still present. **The case had not been deleted, it had scrolled out of
frame.** The library spans several shelves and the camera frames roughly one row,
so "the case is gone" is not evidence of anything on its own.

Verify against the database, not the wall:

```bash
adb shell "run-as com.jawon.app cat databases/javon.db" > out.db
```

(`sqlite3` is not on the device; copy the file off and read it locally. Note the
`-wal` file alongside it holds recent writes — the main file alone can be stale.)

Deletion itself **is** verified now, repeatedly and destructively: the discard
animation, the row and its tracks going, the cover files being unlinked, and the
shelf packing back up around the gap with the neighbours sliding over. `run-as`
only works on a debuggable build, so a release APK cannot be inspected this way.

### Verified since, on the same device

| check | result |
|---|---|
| Room migration 1→2 against a real library | applied, collection intact |
| Resume | album force-stopped mid-track reopened at 0:58; a fresh process cannot have kept playing |
| Play history | `files/history.log` recorded 11639ms for the track actually heard |
| Device scan | 22 albums found, 15 after dedupe against the existing library |
| Import from scan | landed on the shelf and played with a real `MP3 44.1 KHZ` readout |
| Cover art lookup | correct sleeves fetched for two albums, checked by eye against the artwork |
| Editing an album | underlight and the baked back inlay both changed on the wall with no restart, and reverted |
| Colour schemes | all four — Midnight Shop, Cold Storage, After Hours, Workshop — each after a relaunch, artwork still legible |
| Equaliser | five bands read off the device, presets, FLAT, settings persisted |
| Crossfade | confirmed by ear by the owner |

## 8. Where import stands

Nothing about adding an album now requires knowing where files live.

- **Scanning the phone is the default route.** MediaStore is read directly and
  grouped into albums by its own album id, so nothing has to be pointed at. No
  file is opened during the scan — tags, track numbers and durations are all in
  the index — and sample rate and bit depth are filled in at import, only for the
  albums actually chosen.
- **The folder picker remains, and is not a fallback for the stubborn.**
  MediaStore only indexes what the media scanner has seen, so music on a cloud
  provider or a memory card is reachable only through SAF.
- **Covers come from the files.** Embedded art is pulled out of the tracks' own
  tags, so the import opens with the cover already chosen. Only the first five
  files are probed — art is a property of an album, not a track.
- **What has no cover is looked up**, if the Settings switch is on. See §5 for
  what that means for the privacy policy.
- **Duplicates are filtered twice.** File URIs catch what the scan already
  imported; title and artist catch albums added by folder, because the same file
  has a different URI depending on which route reached it.
- **Anything the tags got wrong is fixable afterwards** on the Edit Album screen,
  which is why the batch import asks nothing.

Untested at scale: a library of hundreds of albums. The largest real test was 22
albums found and 15 offered.
