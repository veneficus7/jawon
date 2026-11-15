# Looking at the room without the phone

Stage 0 of `docs/room-design.md` asked for "a way to compare", because three attempts at the
room's look failed for the same reason: judging *is this better* from memory, five minutes
apart, on a device, is not possible. This is that.

It is also how the room gets looked at when the phone is not plugged in.

---

## 1. An emulator that reaches the same quality tier as the phone

**This matters more than it sounds.** `RoomQuality.detect` gives `HIGH` only to a device with
**more than 7 GB of RAM and API 33+**. The two AVDs this project already had — `jawon_lowend_api28`
and `jawon_redmi9_api29` — are deliberately low-spec and land on `LOW`: no ambient occlusion, PCF
instead of PCSS shadows, a reduced render scale. Screenshots from them are useful for *contrast*
and useless for *finish*, and it is easy to forget which you are looking at.

```bash
sdkmanager "system-images;android-34;default;x86_64"
avdmanager create avd -n jawon_flagship_api34 \
    -k "system-images;android-34;default;x86_64" -d pixel_6_pro
```

Then edit `~/.android/avd/jawon_flagship_api34.avd/config.ini`:

```ini
hw.ramSize=8192          # > 7 GB, or RoomQuality drops to MEDIUM
hw.lcd.width=1440        # the S25 Ultra's panel, so render scale and bloom cost are comparable
hw.lcd.height=3120
hw.lcd.density=560
hw.gpu.mode=host         # passthrough: the look is real, the frame rate is not
```

`-gpu host` renders through the machine's own GPU, so **colour, lighting and post are
representative**. Frame timings are not — never take a performance number from here.

---

## 2. A library with real artwork in it, on more than one shelf

The room draws nothing without albums, and a library of blank cases is the worst possible thing
to judge lighting against: the whole purpose of the shelf strips is to light *artwork*.

`albums.py` is the one list all of this is built from — thirty albums, each with an artist, a
year, a shelf and a tracklist. It used to be two lists that disagreed (twelve albums of covers
fetched, seven albums of library built), which meant five covers were downloaded and never used.
The covers are the albums' real artwork and belong to their rights holders, so they are fetched
on demand into `covers/` and never committed.

```bash
python tools/emulator/fetch_covers.py     # MusicBrainz -> Cover Art Archive, into covers/ (gitignored)
python tools/emulator/make_library.py     # silent tagged MP3s carrying those covers
adb push tools/emulator/mp3/. /sdcard/Music/
adb shell content call --uri content://media/external --method scan_volume --arg external_primary
adb shell pm grant com.jawon.app android.permission.READ_MEDIA_AUDIO
```

Then **Add → Scan this phone → All → import**, and finally:

```bash
python tools/emulator/make_shelves.py     # sorts the import onto four shelves
```

**Do not skip that last step, and this is the part worth understanding.** `WallLayout` builds
one row per *shelf* — `rowCount = shelves.size` — and an import puts everything on one shelf
called UNSORTED. So a single-shelf library is one row however many albums it holds: one lit
ledge with two thirds of the frame empty above and below it.

The first fixture here was seven albums on one shelf, and a whole round of judgement was spent
on a room that "looked empty" and a frame that was "45% black". Both were true and neither was
about the room. Thirty albums on four shelves is what puts rows above and below the one being
browsed, which is what the camera was framed for.

`make_shelves.py` edits the database directly rather than driving the shelf manager — a hundred
taps that test nothing — and refuses to run against anything whose serial does not start with
`emulator-`, because the same commands pointed at the owner's phone would rewrite his library.
It needs `adb root`, which a `default` system image allows and a Google Play image does not.

**Cover fetching is flaky and that is not a bug in the script.** The Cover Art Archive redirects
to archive.org, which 503s under any burst — thirteen of thirty failed on the first run of the
thirty-album list and every one succeeded on a retry. `fetch_covers.py` backs off and retries,
and skips anything already on disk, so re-running it is cheap and safe. One album (Kraftwerk's
*Trans-Europe Express*) genuinely has no front cover at that endpoint, which is a useful thing
to have in the fixture: it exercises the app's own lookup path.

**Why MP3 and not WAV.** The first attempt wrote WAVs with the ID3 tag in a RIFF `id3 ` chunk.
Android ignores it: MediaStore came back with the folder name as the album and `<unknown>` as the
artist, which also makes cover lookup impossible. MP3 is where ID3 lives natively. No encoder is
needed — a silent MPEG-1 Layer III stream is a run of identical frames whose side info and main
data are zero, and tag reading does not care whether the audio is interesting.

---

## 3. Measuring instead of squinting

The part that actually settles arguments. `adb exec-out screencap -p` plus a luminance average
over fixed boxes:

```python
def lum(p): return 0.2126*p[0] + 0.7152*p[1] + 0.0722*p[2]
```

This is what caught the shelf strips doing **nothing** on their first build — sleeve 146.9 → 147.7,
wall unchanged to a tenth of a unit, while my eye said "brighter". It is also what found the real
cause: dropping `AMBIENT_INTENSITY` from 40k to 8k took the wall from 50.3 to 8.4 while the sleeves
only fell 147 → 50, which is how we know the wall was six-sevenths uniform fill.

Two rules learned the hard way:

- **Frame the same thing twice.** Force-stop, launch, wait a fixed time, screenshot, and do not
  touch the screen. Nothing compared before this was framed the same way twice, and that alone
  invalidated three rounds of judgement.
- **Read the number before trusting the eye.** Every time they disagreed here, the number was right.
- **Two launches do not frame the same thing.** A comparison across two runs once read a case
  body at 197 and then 60, which no added light can do — the wall had settled on a different
  album. Boxes are only comparable within a frame, or across frames you have confirmed are
  framed identically.
- **Mask the surface you are arguing about.** Three rounds went into a wall gradient measured
  through boxes that turned out to contain boards and case edges as well as wall. Painting the
  wall pure magenta for one build, then averaging only the pixels that pass a magenta test,
  gives the gradient down that surface alone and settled it immediately. It also proves the
  surface is where you think it is on screen, which was itself wrong once.
