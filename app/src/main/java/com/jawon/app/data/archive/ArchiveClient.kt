package com.jawon.app.data.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Internet Archive, as a record shop.
 *
 * No API key, no registration, no account, no rate limit worth working around — which is the
 * whole reason this is the catalogue Jawon browses. It is also the only kind of catalogue where
 * this app can be itself: the material here is openly licensed, so the equaliser, the rack and
 * the scratch may all touch it. Every licensed service forbids exactly that.
 *
 * Two endpoints:
 *
 * * `advancedsearch.php` — the index. Fast, and answers with identifiers and titles.
 * * `/metadata/{identifier}` — everything about one item, including its file list.
 *
 * **The second is not optional and cannot be skipped.** The search index says an item is audio;
 * it does not say the item has audio *files* in it. Netlabel releases in particular are often a
 * single ZIP with no streamable track, and there is no way to tell from a search result. So a
 * result is a candidate, and [album] is what turns one into something playable — or into null.
 */
@Singleton
class ArchiveClient @Inject constructor() {

    /**
     * One page of [collection] matching [query], or the collection's newest for a blank one.
     *
     * **Null when the Archive could not be reached**, as opposed to an empty list, which is an
     * answer. The shop used to say "Nothing found. Try fewer words." for both — on a phone with
     * no signal, that is advice to change a search that never ran.
     */
    suspend fun search(
        collection: ArchiveCollection,
        query: String,
        page: Int = 1
    ): List<ArchiveResult>? = withContext(Dispatchers.IO) {
        val terms = buildString {
            // The collection names its own mediatype; see LIVE_MUSIC for why one cannot be shared.
            append(collection.query)
            if (query.isNotBlank()) {
                // Quoted, so a search containing a colon or a bracket is read as words rather
                // than as query syntax — a user typing "AC/DC" should not produce a parse error.
                append(" AND (${'"'}${query.trim().replace("\"", "")}${'"'})")
            }
        }
        val url = buildString {
            append(SEARCH)
            append("?q=").append(enc(terms))
            FIELDS.forEach { append("&fl%5B%5D=").append(it) }
            append("&rows=").append(PAGE_SIZE)
            append("&page=").append(page.coerceAtLeast(1))
            append("&output=json")
        }
        runCatching { parseSearch(get(url)) }.getOrNull()
    }

    /** One item, with its tracks — or null if it holds nothing this app can play. */
    suspend fun album(identifier: String): ArchiveAlbum? = withContext(Dispatchers.IO) {
        runCatching { parseAlbum(identifier, get("$METADATA/$identifier")) }.getOrNull()
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val SEARCH = "https://archive.org/advancedsearch.php"
        private const val METADATA = "https://archive.org/metadata"
        private const val DOWNLOAD = "https://archive.org/download"
        private const val USER_AGENT = "Jawon/1.0"
        private const val PAGE_SIZE = 40

        private val FIELDS = listOf("identifier", "title", "creator", "year")

        /**
         * Formats worth playing, best first.
         *
         * MP3 ahead of FLAC deliberately, and it is a bandwidth decision rather than a taste
         * one: a lossless concert is several hundred megabytes and this is streamed over a
         * phone connection. The Archive derives an MP3 for essentially every item, so
         * preferring it also maximises how much of the collection is playable at all.
         */
        private val FORMATS = listOf(
            "VBR MP3" to "audio/mpeg",
            "128Kbps MP3" to "audio/mpeg",
            "64Kbps MP3" to "audio/mpeg",
            "Flac" to "audio/flac",
            "Ogg Vorbis" to "audio/ogg"
        )

        /** A direct, range-request-capable URL for one file. This is what the player opens. */
        fun streamUrl(identifier: String, fileName: String): String =
            "$DOWNLOAD/$identifier/${enc(fileName)}"

        fun parseSearch(body: String): List<ArchiveResult> {
            val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
                ?: return emptyList()
            return (0 until docs.length()).mapNotNull { resultOf(docs.optJSONObject(it)) }
        }

        private fun resultOf(json: JSONObject?): ArchiveResult? {
            if (json == null) return null
            val id = json.optString("identifier").takeIf { it.isNotBlank() } ?: return null
            val creator = firstString(json, "creator") ?: "Unknown"
            return ArchiveResult(
                identifier = id,
                title = firstString(json, "title")?.let { withoutCreator(it, creator) } ?: id,
                creator = creator,
                year = json.optInt("year").takeIf { it in 1000..2999 }
            )
        }

        /**
         * A title without the performer's name in front of it, since the performer is always
         * shown beside it — under the title in the shop, and as the artist on the case.
         *
         * The Live Music Archive titles every show "<band> Live at <venue> on <date>", so each
         * result read "Widespread Panic Live at Red Rocks…" above "Widespread Panic · 2026", and
         * the band's name took the first line of a two-line title. Left alone when what would
         * remain is too short to be a title, or the title does not start with the name at all.
         */
        fun withoutCreator(title: String, creator: String): String {
            val name = creator.trim()
            if (name.isEmpty() || name == "Unknown" || !title.startsWith(name, ignoreCase = true)) {
                return title
            }
            val rest = title.substring(name.length).trimStart(' ', '-', '–', '—', ':', ',', '_')
            // "Phish" must not be taken off "Phishbowl Sessions": the name has to end at a break.
            val atBreak = title.length > name.length && !title[name.length].isLetterOrDigit()
            if (!atBreak || rest.length < MIN_TITLE_CHARS) return title
            return rest.replaceFirstChar { it.uppercaseChar() }
        }

        private const val MIN_TITLE_CHARS = 6

        /**
         * A field that is sometimes a string and sometimes an array of them.
         *
         * The Archive's index is multi-valued: an item with two credited performers returns
         * `creator` as a JSON array, and one with a single performer returns a bare string. Both
         * shapes appear in the same result set, so reading either alone throws on roughly half
         * the catalogue.
         */
        fun firstString(json: JSONObject, key: String): String? {
            json.optJSONArray(key)?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let { return it }
                }
                return null
            }
            return json.optString(key).takeIf { it.isNotBlank() }
        }

        fun parseAlbum(identifier: String, body: String): ArchiveAlbum? {
            val root = JSONObject(body)
            val metadata = root.optJSONObject("metadata") ?: return null
            val files = root.optJSONArray("files") ?: return null

            val tracks = pickTracks(files)
            // An item with no playable file is not an album. Netlabel releases distributed as a
            // single ZIP land here, and returning null is what keeps them out of the library
            // rather than putting an empty case on the wall.
            if (tracks.isEmpty()) return null

            val creator = firstString(metadata, "creator") ?: "Unknown"
            return ArchiveAlbum(
                identifier = identifier,
                title = firstString(metadata, "title")?.let { withoutCreator(it, creator) } ?: identifier,
                creator = creator,
                year = firstString(metadata, "date")?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 },
                tracks = tracks
            )
        }

        /**
         * Chooses one format and returns its files as a tracklist.
         *
         * An Archive item carries the *same* recording several times over — FLAC, MP3, WAVE,
         * plus spectrograms and waveform images. Taking every audio file would produce a
         * tracklist with each song on it three times, so exactly one format is chosen for the
         * whole item and the rest ignored.
         */
        private fun pickTracks(files: JSONArray): List<ArchiveTrack> {
            for ((format, mime) in FORMATS) {
                val matching = (0 until files.length())
                    .mapNotNull { files.optJSONObject(it) }
                    .filter { it.optString("format") == format }
                    .mapNotNull { trackOf(it, mime) }
                if (matching.isNotEmpty()) {
                    return matching.sortedBy { it.trackNumber }
                }
            }
            return emptyList()
        }

        private fun trackOf(json: JSONObject, mime: String): ArchiveTrack? {
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            val tagged = firstString(json, "title")
            return ArchiveTrack(
                fileName = name,
                // The tag when the item has one, the filename when it does not — and a great
                // many Live Music Archive items have no per-file tags at all, only names like
                // "01 Let It Rock.mp3". Falling back to the identifier would be useless; falling
                // back to the tidied filename is what the file was actually called.
                title = tagged ?: titleFromFileName(name),
                trackNumber = firstString(json, "track")?.let(::trackNumberOf)
                    ?: trackNumberOf(name)
                    ?: 0,
                durationMs = parseLength(firstString(json, "length")),
                mimeType = mime,
                // Reported in kilobits, and only for the derived formats.
                bitrateBps = (firstString(json, "bitrate")?.toIntOrNull() ?: 0) * 1000
            )
        }

        /**
         * `"7"`, `"07"`, or `"7/12"` — all mean track seven.
         *
         * The last form is what an ID3 tag carries, and `toIntOrNull` on it returns null, which
         * would silently number every track zero and leave the tracklist in filename order.
         */
        fun trackNumberOf(raw: String): Int? =
            raw.substringBefore('/').trim().trimStart('0').toIntOrNull()
                ?: Regex("""^\s*(\d{1,3})\b""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

        /** `"01 Let It Rock.mp3"` becomes `"Let It Rock"`. */
        fun titleFromFileName(name: String): String =
            name.substringBeforeLast('.')
                .replace(Regex("""^\s*\d{1,3}\s*[-._)]?\s*"""), "")
                .replace('_', ' ')
                .trim()
                .ifBlank { name }

        /**
         * Length, in whichever of the Archive's three notations this item used.
         *
         * `"05:06"`, `"1:02:03"` and `"306.5"` all appear, sometimes across items in the same
         * collection. Reading only one of them gives a tracklist whose durations are absent or,
         * worse, wrong by a factor of sixty.
         */
        fun parseLength(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            if (!raw.contains(':')) {
                return ((raw.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
            }
            val parts = raw.split(':').map { it.trim().toDoubleOrNull() ?: return 0L }
            val seconds = when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> return 0L
            }
            return (seconds * 1000).toLong().coerceAtLeast(0L)
        }

        fun enc(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
