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

## 2. A library with real artwork in it

The room draws nothing without albums, and a library of blank cases is the worst possible thing
to judge lighting against: the whole purpose of the shelf strips is to light *artwork*.

```bash
python tools/emulator/fetch_covers.py     # MusicBrainz -> Cover Art Archive, CC-licensed art
python tools/emulator/make_library.py     # silent tagged MP3s carrying those covers
adb push <scratch>/mp3 /sdcard/Music/
adb shell content call --uri content://media/external --method scan_volume --arg external_primary
adb shell pm grant com.jawon.app android.permission.READ_MEDIA_AUDIO
```

Then **Add → Scan this phone → All → import**.

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
