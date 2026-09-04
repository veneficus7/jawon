# Third-party notices

Jawon is built on open-source software and ships open-licensed typefaces. Each remains
under its own licence and its own copyright, none of which is affected by the `LICENSE`
covering the author's own work.

**This file is an obligation, not a courtesy.** Two of the licences below require that they
travel with the thing they cover — the SIL Open Font License says so explicitly for every
font in the app, and the Apache License requires attribution to be carried in any
distribution. Shipping the binaries without them is a licence breach regardless of intent.

---

## Typefaces

All five ship inside the APK, in `app/src/main/res/font/`. **Every one is licensed under
the SIL Open Font License, Version 1.1**, whose relevant term is that the font — original
or modified — may be freely used, embedded and redistributed, but *"must be distributed
entirely under this license"*, with the copyright notice retained.

| Typeface | Copyright | Where it is used |
|---|---|---|
| **Inter** | © The Inter Project Authors (github.com/rsms/inter) | Body text throughout |
| **Archivo** | © The Archivo Project Authors (github.com/Omnibus-Type/Archivo) | Titles and display |
| **JetBrains Mono** | © The JetBrains Mono Project Authors (github.com/JetBrains/JetBrainsMono) | Every mono readout — track numbers, durations, labels |
| **VT323** | © The VT323 Project Authors (Peter Hull) | The deck's window display |
| **Silkscreen** | © The Silkscreen Project Authors (Jason Kottke) | Small silkscreened plate lettering |

The full licence text is in [`licenses/OFL-1.1.txt`](licenses/OFL-1.1.txt).

---

## Libraries

Linked at build time and present in the distributed APK.

| Component | Copyright | Licence |
|---|---|---|
| **SceneView** (`io.github.sceneview:sceneview`) | © SceneView contributors | Apache License 2.0 |
| **Filament** (bundled by SceneView, including `gltfio`) | © Google LLC | Apache License 2.0 |
| **AndroidX**, including Compose, Room, Navigation, Lifecycle, DocumentFile | © The Android Open Source Project | Apache License 2.0 |
| **Media3 / ExoPlayer** | © The Android Open Source Project | Apache License 2.0 |
| **Hilt / Dagger** | © Google LLC | Apache License 2.0 |
| **Kotlin** and **kotlinx.coroutines** | © JetBrains s.r.o. and contributors | Apache License 2.0 |
| **Coil** | © Coil Contributors | Apache License 2.0 |

The full licence text is in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

---

## Services

Not shipped, and used only over the network — no code of theirs is in the app. Listed
because what Jawon sends them is described in [`docs/privacy.md`](docs/privacy.md).

- **MusicBrainz** and the **Cover Art Archive** — album art lookup.
- **LRCLIB** (lrclib.net) — lyrics lookup. MIT-licensed, unregistered, no API key.

---

## What is *not* covered here

The music. Jawon ships no audio of any kind: every record in the room is a file the user
already had, read from their own storage under a permission they granted, and never copied,
uploaded or transmitted. Cover art fetched from the Cover Art Archive is stored on the
user's device only.
