package com.jawon.app.data.remote

/**
 * What a music server says it has, in the only shape this app has a use for.
 *
 * Deliberately thinner than what Subsonic returns. The server offers genres, ratings, play
 * counts, starred flags and its own idea of when something was added; Jawon keeps its own
 * answers to all of those — the patina on a case is computed from `PlayEvent` on *this* phone,
 * and a server's play count would quietly disagree with it. Pulling only what the app cannot
 * know for itself keeps the two from fighting.
 */
data class RemoteAlbum(
    /** The server's id. Stable across syncs, which is what makes re-syncing an update. */
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    /** Id to fetch artwork with, not a URL — the URL needs credentials and is built later. */
    val coverArtId: String?,
    val trackCount: Int
)

/** One album with its tracklist, as returned by `getAlbum`. */
data class RemoteAlbumDetail(
    val album: RemoteAlbum,
    val tracks: List<RemoteTrack>
)

data class RemoteTrack(
    val id: String,
    val title: String,
    /** The track's own credit, where the server carries one. Null on most single-artist albums. */
    val artist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    /** `audio/flac`, `audio/mpeg`. Feeds the deck's quality readout the same as a local file. */
    val mimeType: String,
    val bitrateBps: Int,
    /**
     * Sample rate, where the server bothers to report it.
     *
     * Subsonic's `getAlbum` does not include it and OpenSubsonic's extension does, so this is
     * null against a plain Subsonic server and populated against Navidrome. Null rather than a
     * guessed 44100: the deck prints this, and a printed number that was invented is worse than
     * a blank.
     */
    val sampleRateHz: Int?,
    val bitDepth: Int?
)

/** What a connection attempt found, for the settings screen to report. */
sealed interface ServerProbe {
    data class Reachable(val serverType: String, val version: String) : ServerProbe
    data class Refused(val reason: String) : ServerProbe
    data class Unreachable(val reason: String) : ServerProbe
}
