# -*- coding: utf-8 -*-
"""
Build the emulator's library as tagged MP3s with embedded cover art.

The WAV attempt put ID3 in a RIFF `id3 ` chunk and Android ignored it — MediaStore came back
with the folder name as the album and `<unknown>` as the artist, which is exactly the state
that makes cover lookup impossible. MP3 is where ID3 lives natively and where every reader
on the platform expects to find it.

No encoder needed: a silent MPEG-1 Layer III stream is a run of identical frames whose side
info and main data are zero. Decoders render that as silence, and — the part that matters
here — MediaStore and MediaMetadataRetriever read the ID3 tag whether or not the audio is
interesting. The room is being looked at, not listened to.
"""
import io
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from albums import ALBUMS, safe  # noqa: E402  (needs the path above)
COVERS = os.path.join(HERE, "covers")
OUT = os.path.join(HERE, "mp3")

# MPEG-1 Layer III, 128 kbps, 44.1 kHz, mono, no CRC.
#   FF FB = sync + MPEG-1 + Layer III + no protection
#   90    = bitrate index 9 (128k) + samplerate index 0 (44100) + no padding
#   C0    = mono
FRAME_HEADER = b"\xFF\xFB\x90\xC0"
FRAME_BYTES = 144 * 128000 // 44100          # 417
SAMPLES_PER_FRAME = 1152
SECONDS = 2
FRAMES = int(SECONDS * 44100 / SAMPLES_PER_FRAME) + 1

# The album list lives in albums.py, shared with fetch_covers.py.


def latin1(t):
    return t.encode("latin-1", "replace")


def frame(fid, payload):
    return fid + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def text(fid, value):
    return frame(fid, b"\x00" + latin1(value))


def syncsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def tag(artist, album, title, track, year, jpeg):
    frames = b"".join([
        text(b"TIT2", title), text(b"TPE1", artist), text(b"TALB", album),
        text(b"TPE2", artist), text(b"TRCK", str(track)), text(b"TYER", str(year)),
    ])
    if jpeg:
        frames += frame(b"APIC",
                        b"\x00" + b"image/jpeg\x00" + b"\x03" + b"Front\x00" + jpeg)
    return b"ID3\x03\x00\x00" + syncsafe(len(frames)) + frames


SILENCE = (FRAME_HEADER + b"\x00" * (FRAME_BYTES - 4)) * FRAMES


def cover_for(album):
    """One spelling, because `albums.safe` is now what named the file too."""
    p = os.path.join(COVERS, "%s.jpg" % safe(album))
    return io.open(p, "rb").read() if os.path.exists(p) else None


written = 0
for artist, album, year, _shelf, titles in ALBUMS:
    jpeg = cover_for(album)
    folder = os.path.join(OUT, safe("%s - %s" % (artist, album)))
    os.makedirs(folder, exist_ok=True)
    for n, title in enumerate(titles, start=1):
        with io.open(os.path.join(folder, "%02d %s.mp3" % (n, safe(title))), "wb") as f:
            f.write(tag(artist, album, title, n, year, jpeg) + SILENCE)
        written += 1
    print("  %-32s %d tracks  %s" % (album, len(titles), "cover" if jpeg else "NO COVER"))

print("\n%d tracks, %d albums -> %s" % (written, len(ALBUMS), OUT))
