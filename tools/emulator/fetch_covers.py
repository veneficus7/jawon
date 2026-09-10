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
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from albums import ALBUMS, safe  # noqa: E402  (needs the path above)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "covers")
os.makedirs(OUT, exist_ok=True)

UA = "Jawon-emulator-fixture/0.5 ( https://github.com/veneficus7/javon )"


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


def with_retry(url, tries=4):
    """The Cover Art Archive redirects to archive.org, which 503s under any burst —
    thirteen of thirty failed that way on the first run of the thirty-album list, and
    every one of them succeeded on a second attempt. Transient, so back off and retry
    rather than making the caller run the script twice."""
    for attempt in range(tries):
        try:
            return get(url)
        except Exception:
            if attempt == tries - 1:
                raise
            time.sleep(2.0 * (attempt + 1))


ok = 0
for artist, album, _year, _shelf, _tracks in ALBUMS:
    dest = os.path.join(OUT, "%s.jpg" % safe(album))
    if os.path.exists(dest):
        ok += 1
        continue
    try:
        mbid = release_group_mbid(artist, album)
        time.sleep(1.1)                      # MusicBrainz rate limit
        if not mbid:
            print("  no mbid   ", album)
            continue
        art = with_retry("https://coverartarchive.org/release-group/%s/front-500" % mbid)
        with io.open(dest, "wb") as f:
            f.write(art)
        print("  %-34s %6.0f KB" % (album, len(art) / 1024.0))
        ok += 1
    except Exception as e:
        print("  failed    ", album, type(e).__name__, e)
    time.sleep(0.4)

print("\n%d/%d covers in %s" % (ok, len(ALBUMS), OUT))
