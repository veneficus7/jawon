# Jawon — Privacy Policy

**Last updated: 8 September 2026**

Jawon is a music player for music you already own. It has no accounts, no server
of its own, and no analytics. This policy describes everything it does with your
data, which is not much — and the parts that are not "nothing" are set out plainly
rather than buried.

**What changed on 8 September 2026:** Jawon can now play from a music server that
**you** run, so a library too large for your phone does not have to be on it. That
is a fourth use of the network, and it is described in full in section 4. It is off
until you set it up, and the server it talks to is one you choose and control —
Jawon still operates no server of its own.

---

## The short version

Jawon **collects nothing about you.** There is no account to create, no profile,
no advertising, no tracking, no analytics, and no telemetry of any kind. Nobody —
including the developer — can see your library, your listening history, or that
you use the app at all.

Your music, your shelves, the covers you choose and your play history live **on
your phone** and nowhere else. Uninstalling Jawon deletes all of it.

Jawon makes network requests in exactly **four** situations, all described below.
Three of them can be switched off, and none of them sends anything that identifies
you to the developer or to anyone else.

The fourth — a music server of your own — is different in kind from the other
three, so it is worth saying plainly here rather than only in its own section: it
sends **your username and password** to **an address you typed in**, and streams
your music back from it. That is a service **you** run and control. Jawon does not
choose it, does not know what is on it, and never sees the credentials.

---

## What stays on your phone

All of this is stored in Jawon's private storage, which no other app can read:

- **Your library** — the shelves you create, the albums you import, their titles,
  artists, track lists and technical details, and where each case sits on the wall.
- **Cover images** — copied into Jawon's storage so the wall still renders if the
  original file moves.
- **Your listening history** — which album was played, when, and for how long. This
  is what the listening record and the wear on each case are drawn from.
- **Your settings** — room mode, crossfade, equaliser and effects, and the two
  lookup switches below.
- **Lyrics** fetched from LRCLIB, cached so the same track is never looked up twice.
- **Crash reports**, if the app has crashed. These stay on your phone unless you
  choose to send one — see below.
- **Your music server's address, username and password**, if you set one up. Stored
  in the same private storage as everything else here. The Subsonic protocol needs
  the password on every request, so it cannot be kept as a one-way hash the way a
  login secret could be — it is stored as you typed it. On a normal phone no other
  app can read it; on a rooted phone it is readable, and so is everything else in
  this list. Only ever sent to the server you nominated.
- **The catalogue from that server** — album and track titles, artists, lengths and
  one cover image each. This is what makes the wall render instantly and offline.
- **Recently streamed audio**, held in a size-limited cache so that turning the disc
  to scrub does not wait for the network. You set the size and can clear it at any
  time in Settings; the system may also delete it when your phone is short of space.

**None of this is transmitted to the developer, and none of it goes anywhere Jawon
chooses.** Jawon operates no server, so there is nowhere for it to go. The one
exception is the music server you set up yourself, which receives exactly what
section 4 describes.

If you make a backup (Settings → Your library), Jawon writes a `.zip` to a
location **you** pick with the system file picker. That file is yours; Jawon does
not upload it and cannot see where you put it.

---

## The four times Jawon uses the network

### 1. Cover art lookup — on by default, can be switched off

**When:** during import, and only for albums whose own files carry no artwork.

**What is sent:** the album's **title** and **artist**, to
[MusicBrainz](https://musicbrainz.org). If a confident match comes back, Jawon
downloads that album's cover image from the
[Cover Art Archive](https://coverartarchive.org).

**What is not sent:** no account, no device identifier, no list of what else is in
your library, and nothing at all for albums that already have artwork.

**Switch it off:** Settings → Your library → *Look up cover art*. With it off,
Jawon never contacts either service, and albums without artwork simply show none
until you set one yourself.

### 2. Lyrics lookup — on by default, can be switched off

**When:** only when you open the lyrics page for a track whose own file contains no
lyrics and which has no `.lrc` file beside it.

**What is sent:** the track's **title**, **artist**, **album** and **length**, to
[LRCLIB](https://lrclib.net). The length is what distinguishes an album cut from a
single edit.

**What is not sent:** no account, no device identifier, no listing of your library.
The answer is stored on your phone so the same track is never asked about twice —
including when the answer is "no lyrics found".

**Switch it off:** Settings → Your library → *Look up lyrics*.

### 3. Checking for a new version — only when you ask

**When:** only when you press *Check for updates* in Settings. Jawon never checks
on its own, in the background, or on launch.

**What is sent:** nothing about you. Jawon requests a small public file from
GitHub listing the newest version, and if you choose to update, downloads that
release. Your installed version is compared **on your phone**, not by a server.

This applies to builds installed directly. If you installed Jawon from an app
store, that store handles updates instead.

### 4. A music server of your own — off until you set it up

**When:** only if Jawon has a server to talk to. There are three ways it can get one,
and you can see and change all of them in Settings → *A music server of your own*:

1. **You type one in.** Nothing happens until you do.
2. **You paste a link** someone sent you, which fills in the same three fields at once.
3. **The copy of Jawon you installed came with one already set up.** Some builds are
   made for a specific shared library and ship pointed at it, so the app has music the
   first time it opens. When that is the case, the settings screen **says so**, and the
   address and username are shown to you there like any other. **FORGET** removes it
   permanently — it does not come back on the next launch.

With no server by any of those routes, none of this happens and Jawon behaves exactly as
it did before this feature existed.

**What this is for:** a library of hundreds of albums does not fit on most phones.
Pointing Jawon at a Subsonic-compatible server you run — Navidrome, Airsonic, Gonic,
Ampache — puts those albums on the wall without putting the audio on your phone.

**What is sent, and to whom:** your **username**, a **random salt**, and a
**one-way hash of your password combined with that salt**, to **the address you
entered and nowhere else**. This is how the Subsonic protocol authenticates; your
password itself does not cross the wire, and a captured request cannot be replayed.
Alongside it go requests for your album list, for cover images, and for the audio
of whatever you press play on.

**Who receives it:** whoever runs that server. If that is you, that is you. Jawon
does not know the address, does not report it anywhere, and has no relationship
with it. **This is the one place where what happens to your data depends on
somebody other than you** — so if the server is not yours, its operator can see
what you play and when, in the same way any server can. That applies most of all
to a build that arrived already pointed at somebody else's library: it is their
machine, their logs, and their rules, and Settings shows you the address so you can
decide whether you want to be on it.

**What is not sent:** nothing about your local library, nothing about albums stored
on the phone, no device identifier, and no account with the developer, because
there isn't one.

**Switch it off:** Settings → *A music server of your own* → **FORGET**. That
removes the address and credentials from your phone immediately. The albums stay on
the wall until you remove them, and simply stop playing — deleting hundreds of
records because a password was cleared would be a drastic reading of a small
action.

**A note on streaming services:** Jawon cannot play from Spotify, SoundCloud, Apple
Music or similar, and this is not an omission to be fixed later. Their developer
terms forbid an app from modifying the audio — and equalising it, filtering it,
and scratching it with the disc is the whole of what Jawon does. There is no
version of this app that works inside those rules.

### One thing that is true of all four

Any request over the internet necessarily reveals your device's **IP address** to
the server receiving it, along with a line identifying the app and its version.
This is how the internet works and is not something an app can avoid — but it
would be misleading to say "nothing leaves your device" without naming it.

MusicBrainz, the Cover Art Archive, LRCLIB and GitHub are independent
organisations with their own privacy policies. Jawon sends them only what is
described above. A music server you set up is not an independent organisation at
all — it is a machine you chose, and its privacy policy is whatever you decide it
is.

---

## Crash reports

If Jawon crashes, it writes what went wrong to a file on your phone: the error,
the version of the app, and your phone's model and Android version. **It is not
sent anywhere.**

Settings → *Problems* shows you that file in full, and offers a **Send** button
that hands it to an app you choose — email, a messenger, wherever you like. That
only ever happens because you pressed it, and you can read the entire contents
first. **Clear** deletes it.

Crash reports contain no personal information and no details of your library. They
contain a technical description of a fault, and they exist because there is
otherwise no way for the developer to learn that Jawon broke on your phone.

---

## Permissions, and why

| Permission | Why |
|---|---|
| **Music and audio** (`READ_MEDIA_AUDIO`, or `READ_EXTERNAL_STORAGE` on Android 9–12) | To find music already on your phone. Read-only, and **audio only** — Jawon never requests photos, video, or general file access. You can decline it and still add music with the folder picker. |
| **Internet** | The four uses above, and nothing else: cover art, lyrics, the update check, and streaming from a music server you set up yourself. |
| **Notifications** | To show the playback notification with the track and its controls. |
| **Foreground service** | To keep playing when the screen is off, which is what a music player is for. |
| **Vibrate** | The tick you feel when turning the disc to scrub. |
| **Modify audio settings** | To attach the equaliser and effects to Jawon's own playback. It cannot change any other app's audio. |
| **Install unknown apps** | Only for the self-update above, and only on directly-installed builds. Android always shows its own confirmation; Jawon cannot install anything silently. |

Jawon requests no location, no contacts, no camera, no microphone, and no phone
identifiers.

---

## Children

Jawon is not directed at children and collects no information from anyone,
including children.

---

## Deleting your data

Everything is on your phone, so you control all of it:

- Remove an album from the room, and its record and cover are deleted.
- Clear a crash report in Settings → Problems.
- Clear the streamed-audio cache in Settings → *Offline store*.
- **FORGET** your music server in Settings, which erases its address and your
  credentials from the phone.
- **Uninstall Jawon**, and every trace of it is removed — library, covers, history,
  settings, cached audio and server credentials. There is no copy anywhere else,
  because there is no anywhere else.

There is no account to close, and no request to make of anyone.

The one thing uninstalling does **not** reach is a music server you set up: it is
yours, it keeps whatever it keeps, and deleting anything on it is done there rather
than here.

---

## Changes to this policy

If Jawon ever does something new with data, this policy will be updated before
that version ships, and the date at the top will change. Anything that would send
something not listed here would be a new feature with its own switch, described
in the app before it did anything.

## Contact

Questions about this policy, or about anything above:
**bakhodirmurodillaev@gmail.com**
