package com.jawon.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to a Subsonic-compatible music server.
 *
 * Written on `HttpURLConnection` and `org.json`, for the same reason `CoverArtFetcher` is: this
 * is four endpoints returning small JSON documents, and that does not justify adding OkHttp or
 * Ktor to a project that has kept its dependencies deliberately few.
 *
 * ## The parsing is separated from the fetching, on purpose
 *
 * Every `parse*` function below is pure and static. That is what makes this testable without a
 * server: the JSON shapes are asserted against fixtures on the JVM, and the only untested part
 * is the socket. `LrclibClient` is built the same way and for the same reason.
 *
 * ## The envelope
 *
 * Subsonic wraps everything in a `subsonic-response` object carrying a `status` of `ok` or
 * `failed`. **A failure is HTTP 200.** Wrong password, unknown album, unsupported API version —
 * all of them arrive as a perfectly successful response with a nested error, so checking the
 * status code alone reports success for a login that did not work. [error] is what reads that,
 * and every entry point consults it before touching the payload.
 */
@Singleton
class SubsonicClient @Inject constructor() {

    /**
     * Asks the server whether it is there and whether the credentials work.
     *
     * Distinguishes refused from unreachable because they need different advice: a wrong
     * password is a typo in the form, and a connection failure is a URL, a port, or a server
     * that is switched off. Reporting both as "could not connect" is what makes a settings
     * screen useless.
     */
    suspend fun ping(server: SubsonicServer): ServerProbe = withContext(Dispatchers.IO) {
        if (!server.isConfigured) return@withContext ServerProbe.Refused("Fill in all three fields")
        try {
            parsePing(get(server.endpoint("ping.view")))
        } catch (e: Exception) {
            ServerProbe.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Every album on the server, walked a page at a time.
     *
     * `alphabeticalByName` rather than the server's own "recently added": the wall is sorted by
     * Jawon and the order things arrive in decides nothing, so a stable, total ordering is worth
     * more than a fashionable one — it means a re-sync walks the same sequence and a partial
     * failure resumes somewhere predictable.
     *
     * [PAGE_SIZE] is 500 because that is the documented maximum for `getAlbumList2`; asking for
     * more is silently clamped, which would make the loop below never terminate on a server that
     * returned exactly what was asked for.
     */
    suspend fun albums(server: SubsonicServer): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        val all = mutableListOf<RemoteAlbum>()
        var offset = 0
        while (true) {
            val page = parseAlbumList(
                get(
                    server.endpoint(
                        "getAlbumList2.view",
                        "type=alphabeticalByName&size=$PAGE_SIZE&offset=$offset"
                    )
                )
            )
            all += page
            // A short page is the last page. Also the guard against a server that ignores
            // `offset` — without it, a page that always came back full would loop forever.
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
            if (offset > MAX_ALBUMS) break
        }
        all
    }

    /** One album and its tracklist, or null if the server does not know the id. */
    suspend fun album(server: SubsonicServer, albumId: String): RemoteAlbumDetail? =
        withContext(Dispatchers.IO) {
            runCatching {
                parseAlbum(get(server.endpoint("getAlbum.view", "id=${SubsonicServer.enc(albumId)}")))
            }.getOrNull()
        }

    /** Artwork, sized down server-side so the wall is not fed a 3000px JPEG per case. */
    fun coverUrl(server: SubsonicServer, coverArtId: String, size: Int = COVER_SIZE): String =
        server.endpoint(
            "getCoverArt.view",
            "id=${SubsonicServer.enc(coverArtId)}&size=$size"
        )

    /**
     * The audio itself.
     *
     * `format=raw` asks the server not to transcode. Jawon reads ReplayGain out of the file's
     * own tags (`ReplayGainReader`) and prints the codec and bit depth on the deck, and a
     * server-side transcode would strip the first and make the second a lie. A user who wants
     * transcoding to save data can set it per-client on the server, which is where that
     * decision belongs.
     *
     * **This URL is single-use.** It carries a fresh salt, so calling this twice for the same
     * track yields two different URLs — see the note on caching in [SubsonicServer].
     */
    fun streamUrl(server: SubsonicServer, trackId: String): String =
        server.endpoint("stream.view", "id=${SubsonicServer.enc(trackId)}&format=raw")

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PAGE_SIZE = 500

        /**
         * A ceiling on the walk, so a server that misbehaves costs a bounded number of requests
         * rather than an unbounded one. Twenty thousand albums is far past any personal
         * collection and still only forty pages.
         */
        private const val MAX_ALBUMS = 20_000

        private const val COVER_SIZE = 512
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "Jawon/1.0"

        /**
         * The nested error, or null when the response is good.
         *
         * Returns the server's own message rather than a code, because Subsonic's messages are
         * already written for a person — "Wrong username or password" needs no translation.
         */
        fun error(body: String): String? {
            val response = JSONObject(body).optJSONObject("subsonic-response")
                ?: return "Not a Subsonic server"
            if (response.optString("status") == "ok") return null
            val error = response.optJSONObject("error")
            return error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "The server refused the request"
        }

        fun parsePing(body: String): ServerProbe {
            val refusal = runCatching { error(body) }.getOrElse {
                // Not JSON at all. Almost always a URL pointing at something that is not a
                // Subsonic server — a reverse proxy's login page, or a plain web root.
                return ServerProbe.Unreachable("That address did not answer as a music server")
            }
            if (refusal != null) return ServerProbe.Refused(refusal)
            val response = JSONObject(body).getJSONObject("subsonic-response")
            return ServerProbe.Reachable(
                // `type` is an OpenSubsonic addition; a plain Subsonic server sends neither.
                serverType = response.optString("type").ifBlank { "Subsonic" },
                version = response.optString("serverVersion")
                    .ifBlank { response.optString("version") }
                    .ifBlank { "unknown" }
            )
        }

        fun parseAlbumList(body: String): List<RemoteAlbum> {
            if (error(body) != null) return emptyList()
            val array = JSONObject(body)
                .getJSONObject("subsonic-response")
                .optJSONObject("albumList2")
                ?.optJSONArray("album")
                ?: return emptyList()
            return (0 until array.length()).mapNotNull { albumOf(array.optJSONObject(it)) }
        }

        fun parseAlbum(body: String): RemoteAlbumDetail? {
            if (error(body) != null) return null
            val album = JSONObject(body)
                .getJSONObject("subsonic-response")
                .optJSONObject("album") ?: return null
            val header = albumOf(album) ?: return null
            val songs = album.optJSONArray("song")
            val tracks = (0 until (songs?.length() ?: 0))
                .mapNotNull { trackOf(songs?.optJSONObject(it)) }
            return RemoteAlbumDetail(header, tracks)
        }

        private fun albumOf(json: JSONObject?): RemoteAlbum? {
            if (json == null) return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            return RemoteAlbum(
                id = id,
                title = json.optString("name").ifBlank { "Untitled" },
                artist = json.optString("artist").ifBlank { "Unknown artist" },
                // `optInt` returns 0 for a missing key, and year zero is not a year.
                year = json.optInt("year").takeIf { it > 0 },
                coverArtId = json.optString("coverArt").takeIf { it.isNotBlank() },
                trackCount = json.optInt("songCount")
            )
        }

        private fun trackOf(json: JSONObject?): RemoteTrack? {
            if (json == null) return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            return RemoteTrack(
                id = id,
                title = json.optString("title").ifBlank { "Untitled" },
                // Blank means the server has no separate credit, which on a single-artist album
                // is the normal case. Null, so the deck falls back to the album the same way it
                // does for a local file with no per-track tag.
                artist = json.optString("artist").takeIf { it.isNotBlank() },
                trackNumber = json.optInt("track").takeIf { it > 0 },
                discNumber = json.optInt("discNumber").takeIf { it > 0 },
                // Subsonic reports seconds. Everything in Jawon is milliseconds, and mixing the
                // two silently gives a tracklist whose durations are all a thousandth of the
                // truth.
                durationMs = json.optLong("duration") * 1000L,
                mimeType = json.optString("contentType").ifBlank { "audio/*" },
                // And kilobits. Same trap, same conversion.
                bitrateBps = json.optInt("bitRate") * 1000,
                sampleRateHz = json.optInt("samplingRate").takeIf { it > 0 },
                bitDepth = json.optInt("bitDepth").takeIf { it > 0 }
            )
        }
    }
}
