# Running a library other people can listen to

Jawon can play from a Subsonic-compatible server. This is how to stand one up, how to
let people in without making them type anything, and how to get albums onto it without
touching a terminal every time.

---

## Before anything else: what you are allowed to serve

A server everyone with the app can reach is **publishing**. Not sharing with friends —
publishing, to strangers, at whatever scale the app reaches.

That is fine for:

- **Music you made or own the rights to.**
- **Creative Commons** material, within its licence (attribution, and check whether the
  licence forbids commercial use — a paid app tier around a CC-NC library is a problem).
- **Public domain** recordings.

It is **not** fine for commercial releases you bought. Owning a copy is a licence to
listen, never a licence to redistribute, and "it is only my own collection" is not a
defence that has ever worked. The practical consequences, in order of how fast they
arrive: the hosting account is terminated on the first DMCA notice, the Play listing is
removed, and the liability is personal.

Nothing in the code enforces this. It is a decision you make when you choose what to
upload, and it is the reason this section is first.

---

## 1. Somewhere to run it

**Oracle Cloud Always Free** is the one worth using: 2 ARM cores, 12 GB RAM, **200 GB of
storage** and 10 TB/month of traffic, free with no expiry. That is roughly 2,000 albums
as MP3 or 400 as FLAC.

A laptop works for testing and is the wrong answer for anything else — it is off most of
the time, and the whole point here is a library that is there when someone opens the app.

Anything else with a public address and a disk is equally fine: a VPS, a NAS, a
Raspberry Pi on a home connection with a tunnel.

## 2. Navidrome

Navidrome is the server. It reads a folder of music, exposes the Subsonic API that Jawon
speaks, and handles scanning, cover art and transcoding.

Install it however that platform prefers — a container or the single binary from its own
releases. Point `MusicFolder` at a directory you have created for the purpose, e.g.
`/music`.

Two settings worth getting right at the start:

- **Put it behind HTTPS.** Jawon works over plain `http` — that is verified, and it is
  what a home LAN usually looks like — but an invite link travels through chat apps, and
  a password crossing the open internet unencrypted is a bad trade for saving an
  afternoon. A reverse proxy with a free certificate is the usual route.
- **Turn the scanner's schedule down** if you upload often, or rely on `album_drop`
  below to trigger a scan when it finishes.

## 3. A guest account

**Make a second, non-admin user.** Call it `guest`, give it a long random password, and
never use it for anything else.

This is the account that goes into the invite, and an invite is a shared secret handed to
strangers — anyone who has the link, or the APK it was built into, can read the password
out of it in seconds. That is a property of the situation, not of the encoding, and it is
why the account must be one whose compromise costs nothing.

**Revoking access means changing that password** and publishing a new invite. Every old
link stops working at once. There is no per-person revocation, which is another reason
the account should never be an admin.

## 4. Letting people in without typing anything

Two routes, and they compose.

**A link.** In Jawon: Settings → *A music server of your own* → **SHARE A LINK**. That
produces a single `jawon://…` string carrying the address and the sign-in. Whoever
receives it pastes it into the *PASTE A LINK* field and is listening — no address, no
username, no password to get wrong. The app tests the connection as soon as it is pasted,
so a bad link says so immediately.

**A build that already knows.** To hand someone an APK that simply has a library when it
opens, bake the invite in at build time:

```bash
./gradlew assembleRelease -Pjawon.serverInvite=jawon://eyJ1Ijoi...
```

With that set, a fresh install starts already pointed at the server and the user never
sees a settings screen. `FORGET` still works and still sticks — the first time a user
touches the setting, their answer wins permanently, including when their answer is
"none". See `AppPreferences.readServer`.

Left unset — which is the default, and the default for every build that ships — the app
behaves exactly as it always has: no server, nothing sent anywhere.

## 5. Getting albums onto it

### The comfortable way: `album_drop`

`tools/album_drop.py` is a single file with no dependencies. Run it next to Navidrome:

```bash
python3 tools/album_drop.py --music-root /music \
  --token "$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')" \
  --navidrome-url http://localhost:4533 \
  --navidrome-user admin --navidrome-pass '…'
```

Open `http://<server>:880/?t=<token>` on a laptop or a phone, drag an album folder in,
and it uploads file by file and asks Navidrome to rescan when it finishes. Then hit
**SYNC LIBRARY** in Jawon and the case is on the wall.

It is deliberately narrow: audio, artwork and `.lrc`/`.cue` only, every path component
sanitised, a size cap, and a token that is required rather than optional — it refuses to
start without one. Put it behind the same HTTPS proxy as Navidrome, and do not leave it
running when you are not using it.

### The set-and-forget way: Syncthing

For a library you add to often, [Syncthing](https://syncthing.net) is better than any
upload page: install it on the server and on your laptop, share one folder, and an album
dropped in locally is on the server a minute later with no step in between. Works through
NAT without port forwarding.

Use both — Syncthing from the desk, `album_drop` from a phone.

### What actually matters more than the transfer

**Tag the files before they go up.** Navidrome reads tags, not folder names, and Jawon
shows whatever Navidrome reports. Untagged files become a wall of *Unknown artist*, and
fixing that afterwards is worse than doing it first.
[MusicBrainz Picard](https://picard.musicbrainz.org) is the standard tool and does it
mostly automatically.

Lay files out as `Artist/Album/01 Track.flac`. Navidrome copes with other shapes, but
this one keeps multi-disc sets unambiguous, and Jawon reads disc numbers straight from
the server.

---

## What Jawon needs from the server, exactly

Four endpoints, all standard Subsonic (`docs/architecture.md`, `data/remote/`):

| Endpoint | Used for |
|---|---|
| `ping.view` | The TEST button |
| `getAlbumList2.view` | Walking the catalogue, 500 at a time |
| `getAlbum.view` | One album's tracklist |
| `getCoverArt.view` | Artwork, fetched once and stored on the phone |
| `stream.view` | The audio, with `format=raw` |

`format=raw` matters: Jawon reads ReplayGain out of the file's own tags and prints the
codec and bit depth on the deck, and a server-side transcode would strip the first and
make the second untrue.

Anything implementing Subsonic 1.16.1 works — Navidrome, Airsonic, Gonic, Ampache, and
Jellyfin through its Subsonic layer. OpenSubsonic servers additionally report sample rate
and bit depth, which is why the deck can show them against Navidrome and shows a blank
against an older server rather than inventing a number.
