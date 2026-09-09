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
        val exact = buildString {
            append(GET_ENDPOINT)
            append("?track_name=").append(encode(title))
            append("&artist_name=").append(encode(artist))
            if (album.isNotBlank()) append("&album_name=").append(encode(album))
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
        }
        request(exact)?.let { parse(it) }?.let { return it }

        // **The exact endpoint is exact, and a tag is not.**
        //
        // `/api/get` matches on all four fields at once, so it answers 404 for reasons that
        // have nothing to do with whether the words exist: an album tagged
        // *good kid, m.A.A.d city (Deluxe)* against a library that files it without the
        // suffix, an artist tagged *Kendrick Lamar feat. SZA* where the entry credits only the
        // first name, a rip two seconds off the length the service has. Every one of those is
        // a track whose lyrics are sitting right there behind a query that cannot reach them —
        // which is exactly what the owner reported, on exactly that artist.
        //
        // So when the exact question fails, ask the loose one. `/api/search` matches on title
        // and artist alone and returns candidates; [bestOf] picks between them on the two
        // things that actually identify a recording — its length, and whether the words are
        // timed. The album is deliberately not sent: it is the field most likely to be the
        // reason the first attempt failed.
        searchFor(title, searchableArtist(artist), durationSeconds)?.let { return it }

        // **Last resort, and last for a reason.** Some libraries tag a collaboration as
        // *Kendrick Lamar, SZA* with no feature word at all, and the only way to reach the
        // entry is to search the first name alone. But a separator is not evidence of a
        // collaboration — *Earth, Wind & Fire* and *Crosby, Stills & Nash* are one band with
        // punctuation in the name, and cutting them at the comma searches for an artist who
        // does not exist. So this runs only once everything else has come back empty, where
        // the alternative is showing nothing anyway.
        val lead = leadArtist(artist)
        if (lead == artist) return null
        return searchFor(title, lead, durationSeconds)
    }

    private fun searchFor(title: String, artist: String, durationSeconds: Int): LrclibResult? {
        val url = buildString {
            append(SEARCH_ENDPOINT)
            append("?track_name=").append(encode(title))
            append("&artist_name=").append(encode(artist))
        }
        return request(url)?.let { bestOf(it, durationSeconds) }
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
        private const val GET_ENDPOINT = "https://lrclib.net/api/get"
        private const val SEARCH_ENDPOINT = "https://lrclib.net/api/search"

        /**
         * How far a candidate's length may be from the file's before it is a different
         * recording.
         *
         * Wide enough to survive a rip with a different amount of silence on the end or a
         * transcode's rounding; far short of the gap between an album cut and a radio edit,
         * which is what this is here to refuse.
         */
        private const val DURATION_SLACK_S = 6
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

        /**
         * An artist a search can actually match on.
         *
         * A tag credits everyone on the recording; a lyrics entry usually credits the lead. So
         * an explicit feature word and everything after it comes off — *Kendrick Lamar feat.
         * SZA* becomes *Kendrick Lamar* — and the search matches the name the entry is filed
         * under.
         *
         * **Only the words that can only mean a guest credit.** A comma or an ampersand looks
         * like the same thing and is not: *Earth, Wind & Fire*, *Crosby, Stills & Nash* and
         * *Simon & Garfunkel* are single acts with punctuation in their names, and a rule that
         * cut them at the separator would search for an artist nobody has heard of — turning a
         * lookup that merely failed into one that fails while looking confident. Those go to
         * [leadArtist], which is tried only when everything else has already come back empty.
         *
         * Nothing is stripped when the result would be empty, because a bad narrowing is worse
         * than a wide search.
         */
        fun searchableArtist(artist: String): String {
            var name = artist
            for (marker in FEATURE_MARKERS) {
                val at = name.indexOf(marker, ignoreCase = true)
                if (at > 0) name = name.substring(0, at)
            }
            return name.trim().trimEnd(',', '&', '/').trim().ifBlank { artist }
        }

        private val FEATURE_MARKERS = listOf(
            " feat.", " feat ", " ft.", " ft ", " featuring ", " with "
        )

        /**
         * The first name in a credit list, for the last attempt only.
         *
         * Wrong for any band whose name contains a separator, which is why nothing calls this
         * until an exact lookup and a full-name search have both found nothing. At that point
         * the choice is between a guess and a blank page.
         */
        fun leadArtist(artist: String): String {
            val cut = CREDIT_SEPARATORS
                .mapNotNull { separator -> artist.indexOf(separator).takeIf { it > 0 } }
                .minOrNull() ?: return artist
            return artist.substring(0, cut).trim().ifBlank { artist }
        }

        private val CREDIT_SEPARATORS = listOf(", ", " & ", " / ", " x ", " X ")

        /**
         * Pick a recording out of a list of candidates.
         *
         * Length first, because that is what tells two recordings of one song apart, and a
         * wrong pick here shows as the right song scrolling out of time. Among candidates the
         * same distance away, a timed answer beats an untimed one — that is the whole reason
         * anybody asked the service rather than reading the file's own tag.
         *
         * Returns null rather than a guess when nothing is close enough. Some words are not
         * better than none when they belong to a different cut.
         */
        fun bestOf(body: String, durationSeconds: Int): LrclibResult? = runCatching {
            val array = org.json.JSONArray(body)
            var best: LrclibResult? = null
            var bestScore = Int.MAX_VALUE
            var bestSynced = false
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val synced = item.optStringOrNull("syncedLyrics")
                val plain = item.optStringOrNull("plainLyrics")
                if (synced == null && plain == null) continue

                val candidateDuration = item.optInt("duration", 0)
                val distance = if (durationSeconds <= 0 || candidateDuration <= 0) {
                    // Nothing to compare on. Acceptable, but ranked behind anything that could
                    // be checked, so a candidate with a real length always wins.
                    DURATION_SLACK_S
                } else {
                    kotlin.math.abs(candidateDuration - durationSeconds)
                }
                if (distance > DURATION_SLACK_S) continue

                val isSynced = synced != null
                val better = distance < bestScore || (distance == bestScore && isSynced && !bestSynced)
                if (better) {
                    best = LrclibResult(synced, plain)
                    bestScore = distance
                    bestSynced = isSynced
                }
            }
            best
        }.getOrNull()

        /** `optString` turns a JSON null into the string "null", which is its own kind of bug. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
