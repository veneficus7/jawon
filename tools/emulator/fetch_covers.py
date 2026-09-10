# -*- coding: utf-8 -*-
"""
Fetch real album covers from the Cover Art Archive so the emulator has artwork to light.

Blank beige cases are the worst possible thing to judge a lighting change against — the
whole point of the shelf strips is that they light *artwork*. Real covers also exercise the
same path the app itself uses (MusicBrainz -> Cover Art Archive), so what shows up on the
wall is what a user would actually get.

Both APIs are public and unauthenticated. MusicBrainz asks clients to identify themselves
and rate-limits to roughly one request a second, so this does both.
"""
import io
import json
import os
import time
import urllib.parse
import urllib.request

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "covers")
os.makedirs(OUT, exist_ok=True)

UA = "Jawon-emulator-fixture/0.5 ( https://github.com/veneficus7/javon )"

ALBUMS = [
    ("Miles Davis", "Kind of Blue"),
    ("Pink Floyd", "The Dark Side of the Moon"),
    ("Kendrick Lamar", "good kid, m.A.A.d city"),
    ("Radiohead", "In Rainbows"),
    ("Portishead", "Dummy"),
    ("The Weeknd", "Starboy"),
    ("Bill Evans", "Sunday at the Village Vanguard"),
    ("Nina Simone", "Pastel Blues"),
    ("Massive Attack", "Mezzanine"),
    ("Tyler, the Creator", "Flower Boy"),
    ("Paramore", "This Is Why"),
    ("Billy Joel", "Piano Man"),
]


def get(url, accept=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    if accept:
        req.add_header("Accept", accept)
    return urllib.request.urlopen(req, timeout=25).read()


def release_group_mbid(artist, album):
    q = 'releasegroup:"%s" AND artist:"%s"' % (album, artist)
    url = ("https://musicbrainz.org/ws/2/release-group/?query=%s&fmt=json&limit=3"
           % urllib.parse.quote(q))
    data = json.loads(get(url, "application/json"))
    for rg in data.get("release-groups", []):
        return rg.get("id")
    return None


ok = 0
for artist, album in ALBUMS:
    safe = "".join(c if (c.isalnum() or c in " -_") else " " for c in album).strip()
    dest = os.path.join(OUT, "%s.jpg" % safe)
    if os.path.exists(dest):
        ok += 1
        continue
    try:
        mbid = release_group_mbid(artist, album)
        time.sleep(1.1)                      # MusicBrainz rate limit
        if not mbid:
            print("  no mbid   ", album)
            continue
        art = get("https://coverartarchive.org/release-group/%s/front-500" % mbid)
        with io.open(dest, "wb") as f:
            f.write(art)
        print("  %-34s %6.0f KB" % (album, len(art) / 1024.0))
        ok += 1
    except Exception as e:
        print("  failed    ", album, type(e).__name__, e)
    time.sleep(0.4)

print("\n%d/%d covers in %s" % (ok, len(ALBUMS), OUT))
