package com.jawon.app.data.spotify

/**
 * What Spotify says about an album, reduced to what a room full of jewel cases can use.
 *
 * **Notice what is missing: everything technical.** Spotify reports no codec, no bitrate, no
 * sample rate and no bit depth, because it never intends to hand over a file. The deck's quality
 * readout is therefore blank for a Spotify album — not zeroed, blank — which is the honest
 * rendering of "not known" and is why `qualityReadoutSource` was taught to omit a rate of 0
 * rather than print `0.0 kHz`.
 */
data class SpotifyAlbum(
    /** Spotify's id, e.g. `4LH4d3cOWNNsVw41Gqt2kv`. Stable, and what `Album.remoteId` stores. */
    val id: String,
    /** `spotify:album:…` — what the player is actually asked to play. */
    val uri: String,
    val title: String,
    val artist: String,
    val year: Int?,
    /** A full https URL, unlike Subsonic's opaque id. Downloaded once at sync. */
    val coverUrl: String?,
    val trackCount: Int
)

data class SpotifyTrack(
    val id: String,
    val uri: String,
    val title: String,
    /** Null when it matches the album's artist, so the deck falls back the same way it always does. */
    val artist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long
)

data class SpotifyAlbumDetail(val album: SpotifyAlbum, val tracks: List<SpotifyTrack>)

/** One place Spotify could play, from `/me/player/devices`. */
data class SpotifyDevice(
    val id: String?,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean
)

/** What Spotify is doing right now, from `/me/player`. */
data class SpotifyPlayback(
    val isPlaying: Boolean,
    val trackId: String?,
    val trackUri: String?,
    val positionMs: Long,
    val durationMs: Long,
    val deviceName: String?
)

/** Who is signed in, and — the part that decides whether playback works at all — whether they pay. */
data class SpotifyProfile(
    val displayName: String,
    /**
     * `premium`, `free`, or `open`.
     *
     * **Every playback endpoint returns 403 for a non-Premium account**, so knowing this up
     * front is the difference between saying "Spotify Premium is required" on the settings
     * screen and letting someone discover it by tapping a case that silently does nothing.
     */
    val product: String
) {
    val isPremium: Boolean get() = product.equals("premium", ignoreCase = true)
}

/** Why a Spotify call failed, in words worth showing someone. */
sealed interface SpotifyFailure {
    /** Signed out, or the refresh token was revoked. The only cure is signing in again. */
    data object NeedsLogin : SpotifyFailure

    /** The account is not Premium. No amount of retrying changes this. */
    data object NeedsPremium : SpotifyFailure

    /**
     * Spotify has no device to play on.
     *
     * The Web API cannot conjure one: something has to be running the Spotify app. This is the
     * standing cost of controlling Spotify rather than playing audio directly, and the message
     * has to say so plainly or it reads as a bug.
     */
    data object NoDevice : SpotifyFailure

    /** Too many calls in a rolling 30-second window; [retryAfterSeconds] is Spotify's own answer. */
    data class RateLimited(val retryAfterSeconds: Int) : SpotifyFailure

    data class Other(val message: String) : SpotifyFailure
}
