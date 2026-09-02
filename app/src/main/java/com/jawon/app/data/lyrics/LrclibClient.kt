package com.jawon.app.data.lyrics

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What LRCLIB returned: the timed words if it has them, the plain ones if not.
 *
 * Both can be absent — an instrumental has an entry with neither, which is a real answer and
 * not a failure.
 */
data class LrclibResult(val synced: String?, val plain: String?) {
    val best: String? get() = synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
}

/**
 * Looks a track's words up at LRCLIB.
 *
 * **Why this service and not the famous ones.** Lyrics are copyrighted, so this is a licensing
 * question before it is a technical one: the commercial APIs come with commercial terms, and
 * LyricFind's fee is quoted at five figures. LRCLIB is MIT-licensed, needs no key, registers
 * nobody and rate-limits nothing, and exists specifically to serve open-source players. No key
 * also means no secret shipped in the APK and nothing that can be revoked out from under the
 * app.
 *
 * **What leaves the device**, and it is the same shape as the cover-art lookup: a track title,
 * an artist, an album and a duration. No identifier, no account, no listing of what else is on
 * the shelf. `HttpURLConnection` for the same reason as `CoverArtFetcher` — one GET does not
 * justify a networking dependency.
 */
@Singleton
class LrclibClient @Inject constructor() {

    /**
     * Ask for one track, or null if there is no answer.
     *
     * [durationSeconds] is what makes this an exact match rather than a guess: several
     * recordings share a title and an artist, and the length is what separates the album cut
     * from the single edit. LRCLIB allows a couple of seconds of slack itself.
     */
    fun fetch(title: String, artist: String, album: String, durationSeconds: Int): LrclibResult? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = buildString {
            append(ENDPOINT)
            append("?track_name=").append(encode(title))
            append("&artist_name=").append(encode(artist))
            if (album.isNotBlank()) append("&album_name=").append(encode(album))
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
        }
        return request(url)?.let { parse(it) }
    }

    private fun request(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // LRCLIB asks clients to identify themselves rather than arrive anonymously, and
            // being a good guest of a free service costs one header.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        connection.use { open ->
            // 404 is the ordinary answer for "we do not have this one" and is not worth logging.
            if (open.responseCode != HttpURLConnection.HTTP_OK) return null
            open.inputStream.bufferedReader().readText()
        }
    }.onFailure { Log.i(TAG, "lyrics lookup failed", it) }.getOrNull()

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val ENDPOINT = "https://lrclib.net/api/get"
        private const val TIMEOUT_MS = 8_000
        private const val TAG = "JawonLyrics"

        /** Named, with a way to reach the project, which is what a free service is owed. */
        private const val USER_AGENT = "Jawon/0.4 (https://github.com/veneficus7/javon)"

        /**
         * Pull the words out of a response.
         *
         * Separated from the request so the shapes this has to survive — an instrumental with
         * both fields null, a JSON null rather than an absent key, an error object — are
         * testable without a network.
         */
        fun parse(body: String): LrclibResult? = runCatching {
            val json = JSONObject(body)
            // The service answers a miss with an object carrying a status code rather than the
            // track, and reading that as lyrics would show the word "null" as a song.
            if (json.has("statusCode") && !json.has("id")) return null
            val synced = json.optStringOrNull("syncedLyrics")
            val plain = json.optStringOrNull("plainLyrics")
            if (synced == null && plain == null) return null
            LrclibResult(synced, plain)
        }.getOrNull()

        /** `optString` turns a JSON null into the string "null", which is its own kind of bug. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
