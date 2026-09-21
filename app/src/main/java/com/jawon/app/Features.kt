package com.jawon.app

/**
 * Which half-finished or parked features the app currently shows.
 *
 * **These are switches, not deletions, and the difference is the point.** Code that is
 * commented out rots: it stops compiling, its tests stop running, and by the time anyone wants
 * it back it is cheaper to rewrite than to revive. A flag keeps the whole thing built, tested
 * and honest on every commit, and turning it back on is one line rather than an archaeology
 * exercise.
 *
 * **Spotify is the one route that was deleted rather than switched off**, on 2026-09-21, and the
 * rule above is why the difference matters. A flag is for something that may come back. Spotify
 * never hands its audio to Jawon, so on a Spotify album the disc, the rack and the scratch had
 * nothing to act on — there was no version of it worth returning to.
 */
object Features {

    /**
     * The Subsonic music-server route — `data/remote/`, `StreamCache`, the Settings section.
     *
     * **Off since 2026-09-09, deliberately and temporarily.** The owner's decision was to park
     * self-hosting while the Spotify route is explored, and to come back to it "after some time
     * with careful consideration". Nothing about it was found wanting: it was verified end to
     * end on a device — migration, sync, streamed playback, a cached scrub, deletion — and its
     * 40-odd tests still run on every build.
     *
     * What this flag hides is the *entrance*: the Settings section that sets a server up. It
     * does **not** tear out the playback path, and that restraint is deliberate. Anyone who
     * already configured a server has albums on their wall, and making those silently
     * unplayable would be a worse outcome than leaving a working feature quietly running for
     * the few people using it. They keep working; there is simply no longer a way to add a new
     * one from the UI.
     *
     * **Back on as of 2026-09-09**, and the reasoning that brought it back is worth keeping.
     * The owner's own argument arrived at it: a collection ripped at full quality is hundreds of
     * gigabytes, no phone holds that, and the person who cares most about physical listening is
     * therefore the one who can least fit their records on the device. That is the central
     * problem of the product, and this is the only answer to it that is also unambiguously
     * legal — your own music, on your own machine, streamed to your own phone.
     *
     * See `docs/server.md`.
     */
    const val MUSIC_SERVER = true
}
