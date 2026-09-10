# -*- coding: utf-8 -*-
"""
Sort the emulator's imported library onto the shelves `albums.py` names.

**Why this exists at all.** The room draws one row per shelf — `WallLayout` computes
`rowCount = shelves.size` — and an import puts everything on one shelf called
UNSORTED. So however many albums the fixture has, the wall is a single lit ledge
with an empty frame above and below it, which is the opposite of what the room is
being tuned toward and is not what a real library looks like. Four shelves gives
the camera the rows it was framed for.

**Why it edits the database instead of driving the UI.** Assigning thirty albums
through the shelf manager is around a hundred taps, none of which are being
tested. This is fixture setup, not a test of the app, and it runs only against an
emulator — the check below refuses anything else, because the same commands
pointed at a real phone would rewrite somebody's library.

Run after importing, with the app installed and the library already on the wall:

    python tools/emulator/make_shelves.py

`adb root` is required (a `default` system image allows it; a Google Play image
does not).
"""
import os
import shutil
import subprocess
import sqlite3
import sys
import tempfile
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from albums import ALBUMS  # noqa: E402  (needs the path above)

PACKAGE = "com.jawon.app"
DB_DIR = "/data/data/%s/databases" % PACKAGE
DB = "javon.db"

# Distinct enough that adjacent rows do not read as the same shelf, and all of them
# warm or cool rather than saturated — the shelf accent tints a label plate, not the
# room.
ACCENT_HUE = {
    "JAZZ": 0.09,
    "ELECTRONIC": 0.55,
    "HIP-HOP & SOUL": 0.78,
    "ROCK & POP": 0.33,
}

# The order rows appear in, top to bottom.
SHELF_ORDER = ["JAZZ", "ELECTRONIC", "HIP-HOP & SOUL", "ROCK & POP"]


def adb_exe():
    """`adb` is often not on PATH on Windows even with the SDK installed."""
    exe = os.environ.get("ADB")
    if exe:
        return exe
    sdk = (os.environ.get("ANDROID_HOME")
           or os.environ.get("ANDROID_SDK_ROOT")
           or os.path.join(os.path.expanduser("~"), "AppData", "Local",
                           "Android", "Sdk"))
    candidate = os.path.join(sdk, "platform-tools", "adb.exe")
    return candidate if os.path.exists(candidate) else "adb"


def adb(*args, **kw):
    out = subprocess.run([adb_exe(), "-s", device()] + list(args),
                         capture_output=True, text=True)
    if kw.get("check", True) and out.returncode != 0:
        raise SystemExit("adb %s failed: %s" % (" ".join(args), out.stderr.strip()))
    return out.stdout.strip()


_device = None


def device():
    """The one emulator, and only an emulator.

    A serial that does not start with `emulator-` is refused rather than guessed
    at: everything below force-stops an app and overwrites its database, and the
    owner's phone is routinely attached to the same machine over Wi-Fi debugging.
    """
    global _device
    if _device:
        return _device
    listing = subprocess.run([adb_exe(), "devices"],
                             capture_output=True, text=True).stdout
    serials = [ln.split()[0] for ln in listing.splitlines()[1:]
               if ln.strip() and ln.split()[-1] == "device"]
    emus = [s for s in serials if s.startswith("emulator-")]
    if len(emus) != 1:
        raise SystemExit(
            "need exactly one running emulator, found %r (attached: %r).\n"
            "This script only ever touches an emulator." % (emus, serials))
    _device = emus[0]
    return _device


def main():
    print("device:", device())
    adb("root")
    adb("wait-for-device")
    adb("shell", "am", "force-stop", PACKAGE)

    work = tempfile.mkdtemp(prefix="jawon-shelves-")
    local = os.path.join(work, DB)
    try:
        # The write-ahead log holds most of a fresh import, so it has to come too —
        # opening the database locally is what checkpoints it back into the file.
        for suffix in ("", "-wal", "-shm"):
            adb("pull", "%s/%s%s" % (DB_DIR, DB, suffix), local + suffix, check=False)
        if not os.path.exists(local):
            raise SystemExit("no database on the device — import the library first")

        db = sqlite3.connect(local)
        titles = {title: shelf for _artist, title, _year, shelf, _tracks in ALBUMS}

        rows = db.execute("select id, title from Album").fetchall()
        if not rows:
            raise SystemExit("no albums in the database — import the library first")

        shelf_ids = {}
        db.execute("delete from Shelf")
        for order, name in enumerate(SHELF_ORDER):
            shelf_ids[name] = str(uuid.uuid4())
            db.execute(
                "insert into Shelf (id, name, `order`, accentHue, createdAt)"
                " values (?, ?, ?, ?, ?)",
                (shelf_ids[name], name, order, ACCENT_HUE[name], 1_700_000_000_000 + order))

        # Slot index is per shelf and has to be dense: the wall's bounds are derived
        # from occupied slots, so a gap is a hole in the row.
        used = {name: 0 for name in SHELF_ORDER}
        unknown = []
        for album_id, title in sorted(rows, key=lambda r: r[1].lower()):
            shelf = titles.get(title)
            if shelf is None:
                unknown.append(title)
                shelf = SHELF_ORDER[-1]
            db.execute("update Album set shelfId = ?, slotIndex = ? where id = ?",
                       (shelf_ids[shelf], used[shelf], album_id))
            used[shelf] += 1

        db.commit()
        db.close()

        for name in SHELF_ORDER:
            print("  %-16s %2d albums" % (name, used[name]))
        if unknown:
            print("  not in albums.py, left on %s: %s"
                  % (SHELF_ORDER[-1], ", ".join(unknown)))

        adb("push", local, "%s/%s" % (DB_DIR, DB))
        # The pushed file is the whole truth now; a stale log would replay over it.
        adb("shell", "rm", "-f", "%s/%s-wal" % (DB_DIR, DB), "%s/%s-shm" % (DB_DIR, DB))
        adb("shell", "chown", "-R", "--reference=%s" % DB_DIR, DB_DIR, check=False)
        print("\n%d albums on %d shelves" % (len(rows), len(SHELF_ORDER)))
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
