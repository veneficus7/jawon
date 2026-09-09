package com.jawon.app.data.remote

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One music server, and the credentials to talk to it.
 *
 * **Why Subsonic and not a streaming service.** Every licensed catalogue — Spotify, SoundCloud,
 * Apple, Tidal — forbids the one thing this app is built to do. SoundCloud's API terms are the
 * clearest about it: an app must not "cache, download or persistently store any User Content",
 * and must not perform "adaptation, editing, excerpting, or creating derivative works". Jawon's
 * whole deck is adaptation of the audio — the equaliser, the filter, the drive, the echo, and
 * above all the scratch. A player that cannot touch the samples cannot be this player. The
 * catalogue door is not closed by engineering; it is closed by the fact that this is a
 * turntable.
 *
 * So the music stays the user's own, and only its *location* moves off the phone. Subsonic is
 * the open API for exactly that: documented, unencumbered, spoken by Navidrome, Airsonic,
 * Gonic, Ampache and Jellyfin, and — the part that matters for this app — already shaped
 * around albums rather than around a feed of loose tracks.
 *
 * ## Authentication
 *
 * Subsonic does not use a bearer token. Every request carries the username, a random salt, and
 * `md5(password + salt)` — so the password itself never crosses the wire, and a captured URL
 * is worth nothing once its salt is spent. That is also why [authQuery] takes the salt as a
 * parameter: the generated half is the only part that cannot be asserted about, and pushing it
 * to the caller makes the rest testable.
 *
 * **The salt changes per request, which means the stream URL changes per request.** Anything
 * caching by URL would therefore cache nothing and re-download everything. See
 * `PlaybackRepository`, where each remote track is given a stable custom cache key so the cache
 * keys off the track rather than off the signature.
 */
data class SubsonicServer(
    /** Scheme and host, with or without a trailing slash — `https://music.example.net`. */
    val url: String,
    val username: String,
    val password: String
) {
    /** Trailing slashes trimmed, so paths can be appended without doubling the separator. */
    val baseUrl: String get() = url.trim().trimEnd('/')

    /**
     * `true` when this looks complete enough to try. Not validation — the server is the only
     * thing that can say whether these credentials are right — just a guard against firing a
     * request at an empty form.
     */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /**
     * The address as a person would say it: `music.example.net`, not `https://music.example.net/`.
     *
     * For the one line of heading that says which server this is. The scheme and the trailing
     * slash are noise in that position — nobody reading a settings screen is checking whether
     * their own server is on TLS by looking at the label — and dropping them keeps a long
     * self-hosted address from wrapping onto a second line.
     */
    val displayHost: String
        get() = baseUrl
            .substringAfter("://")
            // A path can follow the host on a reverse-proxied install (`example.net/music`).
            // Keeping it would be more accurate and less readable; the full address is in the
            // field two rows below, unchanged.
            .substringBefore('/')
            .ifBlank { baseUrl }

    /** Everything after the `?`, minus the endpoint's own parameters. */
    fun authQuery(salt: String = newSalt()): String {
        val token = md5(password + salt)
        return "u=${enc(username)}&t=$token&s=${enc(salt)}&v=$API_VERSION&c=$CLIENT_NAME&f=json"
    }

    /**
     * A full URL for one endpoint.
     *
     * [params] is appended verbatim, so callers are responsible for encoding their own values —
     * which they do through [enc].
     */
    fun endpoint(view: String, params: String = "", salt: String = newSalt()): String {
        val query = authQuery(salt) + if (params.isBlank()) "" else "&$params"
        return "$baseUrl/rest/$view?$query"
    }

    companion object {
        /**
         * 1.16.1 rather than the newest. It is the last version of the original Subsonic API,
         * which is the floor every clone implements; asking for more excludes servers for the
         * sake of endpoints this app does not call.
         */
        const val API_VERSION = "1.16.1"

        /** Sent as `c`. Servers show it in their "now playing" and session lists. */
        const val CLIENT_NAME = "Jawon"

        private val RANDOM = SecureRandom()

        /** Empty is a real state — it means "no server set up", not "a server with no name". */
        val NONE = SubsonicServer(url = "", username = "", password = "")

        fun newSalt(): String {
            val bytes = ByteArray(9)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun md5(input: String): String =
            MessageDigest.getInstance("MD5")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
