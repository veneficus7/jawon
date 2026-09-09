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

**Storage is what decides this**, and per-GB prices differ by nearly twenty times between
options at the same provider. A music library is mostly disk and very little CPU, which
is the opposite of what most VPS plans are priced for.

**Recommended: netcup RS 1000 G12** — €10.74/month for 4 dedicated EPYC cores, 8 GB of
DDR5 ECC RAM and **256 GB of NVMe**. Dedicated cores rather than shared matters more than
the number suggests: shared-vCPU budget hosts oversell, and the symptom is CPU steal that
turns a library scan into a crawl at the wrong moment. 256 GB is roughly 2,800 albums as
MP3 or 730 as FLAC. One-month terms and a 30-day refund, so the downside is capped.

If the library outgrows it, add a **Hetzner Storage Box** (1 TB, €3.20/month) and mount it
at the music folder. That is €0.0032/GB against €0.0572/GB for a Hetzner Cloud volume —
the same company, eighteen times the price, which is why no plan here puts music on block
storage.

**Better, if you own a machine that can stay on.** A box at home with a Cloudflare Tunnel
costs about $3/month in electricity, uses a disk you already have, and — on a symmetric
connection — will out-serve a budget VPS. Korean residential fibre averages 191 Mbit/s
upload, which is roughly 190 simultaneous lossless streams. Run `server_setup.sh --no-tls`
and put `cloudflared` in front. The catch is that it is your machine: it goes down when
you unplug it.

**Ruled out: Oracle Always Free.** It reclaims free instances whose CPU sits below 20% for
seven days, and a personal music server is idle almost all the time — exactly the profile
that gets deleted. Its ARM capacity also frequently refuses to provision at all.

**Ruled out: Telegram as storage.** Its Bot Developer ToS names cloud storage as the
prohibited example, downloads cap at 20 MB, and — the practical objection — it offers no
HTTP range requests, so every project doing this runs a middleware server to translate
them. You end up renting a server anyway and adding a layer that can be switched off.

## 2. One command

Copy `tools/` onto the new machine and run:

```bash
sudo bash server_setup.sh --domain music.example.net
```

That installs **Navidrome** (the music server — it watches a folder and speaks the API
Jawon uses), **album_drop** (uploads, below) and **Caddy** in front of both for automatic
HTTPS, wires all three into systemd so they come back after a reboot, and opens the host
firewall. It picks the right binary for ARM or x86, which matters because the Oracle free
tier is ARM and most guides assume otherwise.

Re-running it upgrades in place and keeps your existing upload token, so it is also how
you update later.

For a server that never leaves your own network, `--no-tls` skips Caddy and binds the
services directly. **Do not use it for anything reachable from the internet**: the invite
link carries a password, and over plain `http` that password crosses the wire in the
clear on every request.

### The one thing the script cannot do

**Your cloud provider has a second firewall**, in its web console, that nothing running on
the machine can reach. The script opens the one on the host; you have to open the other:

- **Oracle** — Networking → VCN → Subnet → Security List → Add Ingress Rule
- **AWS** — the instance's Security Group
- **GCP** — VPC network → Firewall

Open TCP **80** and **443** from `0.0.0.0/0` (or the two service ports if you used
`--no-tls`). If the address times out after a clean install, this is why — it is the most
common failure by a wide margin.

With `--domain`, also point that name and `drop.<name>` at the machine. Caddy fetches
certificates by itself once DNS resolves.

## 3. Two accounts, and only one of them gets shared

Open the address the script prints. **The first visit creates the admin account** —
Navidrome does this through its own web UI and nothing can script around it.

Then, in *Settings → Users*, add a **second, non-admin user**. Call it `guest`, give it a
long random password, and never use it for anything else.

That is the account that goes into the invite. An invite is a shared secret handed to
strangers: anyone with the link, or with the APK it was baked into, reads the password
back out in seconds. That is a property of the situation and not of the encoding, which
is why the account must be one whose compromise costs nothing.

**Revoking access means changing that password and issuing a new link.** Every old link
stops working at once. There is no per-person revocation — another reason it must never
be the admin account.

## 4. Letting people in without typing anything

Make the link:

```bash
python3 tools/make_invite.py https://music.example.net guest 'the-guest-password'
```

**A link.** Send that string to someone. They paste it into Jawon under *PASTE A LINK*
and the address, username and password fill themselves in and test immediately — nothing
to mistype. (In the app, **SHARE A LINK** produces the same thing for a server already
set up.)

**A build that already knows.** To hand someone an APK that simply has a library when it
opens:

```bash
./gradlew assembleRelease -Pjawon.serverInvite=jawon://eyJ1Ijoi...
```

A fresh install starts already pointed at the server and the user never sees a settings
screen. `FORGET` still works and still sticks — the first time a user touches the
setting, their answer wins permanently, including when their answer is "none". See
`AppPreferences.readServer`.

Left unset — the default, and the default for every build that ships — the app behaves
exactly as it always has: no server, nothing sent anywhere.

The format is shared between `make_invite.py` and `data/remote/ServerInvite.kt`, and
`ServerInviteTest` pins the exact bytes so the two cannot drift.

## 5. Getting albums onto it

### The comfortable way: `album_drop`

Already installed and running — `server_setup.sh` did it. The address is printed at the
end of the script, and it looks like:

```
https://drop.music.example.net/?t=<token>
```

**Bookmark that on your phone and your laptop.** Open it, drag an album folder onto the
page, and it uploads file by file and asks Navidrome to rescan when it finishes. Then hit
**SYNC LIBRARY** in Jawon and the case is on the wall.

The token in the URL *is* the password — treat the bookmark accordingly. It is kept in
`/etc/navidrome/album_drop.token` if you need it again, and re-running the setup script
does not change it.

It is deliberately narrow: audio, artwork and `.lrc`/`.cue` only, every path component
sanitised, a size cap, and a token that is required rather than optional — it refuses to
start without one. It binds to localhost and is reached only through Caddy, so it is
never exposed on its own port.

To stop accepting uploads entirely: `sudo systemctl stop album-drop`.

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
