package com.jawon.app.data.importer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up an album's cover art online, for the records whose own files do not carry one.
 *
 * **This is the app's only network call, and the only place it leaves the device.** Jawon is
 * local-first by design (`ENGINEERING.md`), so this is deliberately narrow: it sends an album title
 * and an artist name, it receives an image, and it does so only for albums that have no art
 * embedded in their own files. Most ripped or purchased music carries its cover, so on a
 * normal library this runs for a handful of albums or none at all.
 *
 * Nothing identifying goes with the request. No device id, no account, no library contents —
 * one title and one artist per album that needs a picture, and only when the user has left
 * cover lookup switched on.
 *
 * **Why MusicBrainz and the Cover Art Archive.** They are open, need no API key and no
 * account, and neither requires registering an application that could be tied back to a user.
 * The commercial alternatives all want a key, which would mean shipping a shared secret in the
 * APK and giving one company a view of what everyone's library contains.
 *
 * Written on `HttpURLConnection` rather than by adding OkHttp or Ktor. Two GETs per album does
 * not justify a networking dependency in a project that has deliberately kept them few.
 */
@Singleton
class CoverArtFetcher @Inject constructor() {

    /**
     * Finds cover art for one album, or returns null.
     *
     * Null is an ordinary outcome, not a failure: plenty of music is not in MusicBrainz, the
     * tags may be wrong, and the network may be off. The album imports either way and keeps
     * whatever the user gives it later.
     *
     * Writes to the cache directory, the same as [AlbumImportRepository]'s embedded-art path,
     * so a lookup for an album the user then backs out of costs nothing permanent.
     */
    suspend fun fetch(context: Context, title: String, artist: String): Uri? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            try {
                diag(context, "look up '$title' / '$artist'")
                val mbid = findReleaseGroup(context, title, artist)
                if (mbid == null) {
                    diag(context, "  no confident release-group match")
                    return@withContext null
                }
                val bytes = downloadFront(context, mbid)
                if (bytes == null) {
                    diag(context, "  release-group $mbid has no front image")
                    return@withContext null
                }
                val dir = File(context.cacheDir, "import").apply { mkdirs() }
                val out = File(dir, "fetched-${UUID.randomUUID()}.img")
                out.writeBytes(bytes)
                diag(context, "  got ${bytes.size} bytes -> ${out.name}")
                Uri.fromFile(out)
            } catch (e: Exception) {
                // Never fatal: the album imports without a cover, which is what would have
                // happened anyway. Recorded rather than swallowed, because "it silently did
                // nothing" is indistinguishable from "the feature is not wired up".
                diag(context, "  failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    /**
     * Diagnostic trail, in debug builds, to a file rather than to logcat.
     *
     * `ENGINEERING.md` trap 9: on the Samsung test device telephony spam buries app logs, so a
     * `Log.w` here is effectively invisible. Read it back with:
     *   `adb shell run-as com.jawon.app cat files/coverart.log`
     */
    private fun diag(context: Context, message: String) {
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        runCatching { File(context.filesDir, "coverart.log").appendText("$message\n") }
    }

    /**
     * The MusicBrainz id for the *release group* — the album as a work, rather than one
     * particular pressing of it.
     *
     * Release groups are the right level here. Searching releases returns every CD, vinyl and
     * regional reissue separately, most of which have no art in the archive; the group has one
     * cover chosen as the front, which is the picture a person means when they say "the album
     * cover".
     */
    private fun findReleaseGroup(context: Context, title: String, artist: String): String? {
        // Lucene syntax, so the values have to be quoted — an unquoted title containing AND,
        // OR or a colon is parsed as query structure and returns nonsense or a 400.
        val query = buildString {
            append("releasegroup:\"").append(escape(title)).append('"')
            if (artist.isNotBlank() && artist != "Unknown Artist") {
                append(" AND artist:\"").append(escape(artist)).append('"')
            }
        }
        val url = "https://musicbrainz.org/ws/2/release-group/" +
            "?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=3"

        val body = get(context, url)?.decodeToString() ?: return null
        val groups = JSONObject(body).optJSONArray("release-groups") ?: return null
        if (groups.length() == 0) return null

        // Only trust a confident match. MusicBrainz scores 0..100 and always returns its best
        // guesses, so a library full of obscure or mistagged files would otherwise be given
        // confidently wrong covers — which is worse than no cover, because a missing one
        // invites the user to set it and a wrong one looks deliberate.
        val best = groups.getJSONObject(0)
        if (best.optInt("score") < MIN_SCORE) return null
        return best.optString("id").takeIf { it.isNotEmpty() }
    }

    private fun downloadFront(context: Context, releaseGroupMbid: String): ByteArray? =
        get(context, "https://coverartarchive.org/release-group/$releaseGroupMbid/front-500")
            ?.takeIf { it.size > MIN_IMAGE_BYTES }

    private fun get(context: Context, url: String): ByteArray? {
        // MusicBrainz throttles anonymous callers to roughly one call a second and blocks
        // clients that ignore it. Sleeping before every request is cruder than a token bucket
        // and entirely adequate: this runs over a handful of albums, in the background, while
        // the user is looking at an import that has already finished the part they care about.
        Thread.sleep(RATE_LIMIT_MS)
        var current = URL(url)
        // Followed by hand because the archive answers with a 307 to a different host, and
        // HttpURLConnection silently refuses to follow a redirect that changes protocol.
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                // Required by MusicBrainz: a real application name, version and contact, or
                // the request is refused. Deliberately the project's own URL — nothing here
                // identifies the person running the app.
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
            }
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> return connection.inputStream.use { it.readBytes() }
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: return null
                        current = URL(current, location)
                    }
                    // 404 is the ordinary "no art for this album" answer, not an error.
                    else -> {
                        if (code != HttpURLConnection.HTTP_NOT_FOUND) {
                            diag(context, "  HTTP $code from $current")
                        }
                        return null
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** Escapes the Lucene metacharacters that appear in real album titles. */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val TAG = "CoverArtFetcher"
        const val USER_AGENT = "Jawon/0.2 ( https://github.com/veneficus7/javon )"
        const val RATE_LIMIT_MS = 1_100L
        const val TIMEOUT_MS = 10_000
        const val MAX_REDIRECTS = 4

        /** MusicBrainz's own 0..100 confidence. Below this the match is a guess. */
        const val MIN_SCORE = 90

        /** Smaller than this is an error page or a placeholder, not a cover. */
        const val MIN_IMAGE_BYTES = 1_000
    }
}
