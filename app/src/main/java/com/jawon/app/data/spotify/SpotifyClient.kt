package com.jawon.app.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Either the thing, or why not. Thinner than kotlin.Result because the failures are a closed set. */
sealed interface SpotifyResult<out T> {
    data class Ok<T>(val value: T) : SpotifyResult<T>
    data class Failed(val failure: SpotifyFailure) : SpotifyResult<Nothing>
}

/**
 * Spotify's Web API — the catalogue, and the remote control.
 *
 * ## Why the Web API and not Spotify's Android SDK
 *
 * Spotify ships an `app-remote` library that talks to the installed Spotify app over IPC, and on
 * paper it is the better fit: lower latency, no dependence on a device being awake. It is not
 * used here for two reasons. It arrives as an `.aar` whose distribution has moved between
 * JCenter, Maven Central and JitPack and cannot be relied on to resolve; and adding a binary
 * dependency is exactly what `ENGINEERING.md`'s fourth architecture rule says to raise before doing.
 * The Web API is plain HTTP and JSON, needs nothing that is not already in the project, and —
 * the part that matters for this codebase — every response shape below is parsed by a pure
 * function that runs on the JVM in milliseconds.
 *
 * The cost is real and is not hidden: Web API playback needs an **active Spotify device**. See
 * [SpotifyFailure.NoDevice].
 *
 * ## What this can never do
 *
 * Spotify hands over metadata and accepts commands. It never hands over audio. So for a Spotify
 * album the equaliser, the effects rack, crossfade, ReplayGain and **the scratch** do not apply
 * — the disc turns and the audio does not follow, because the samples are in another process
 * belonging to another company. That is a licensing position rather than a technical one and no
 * amount of work here changes it.
 */
@Singleton
class SpotifyClient @Inject constructor() {

    suspend fun profile(token: String): SpotifyResult<SpotifyProfile> =
        get(token, "$API/me") { parseProfile(it) }

    /**
     * Every album the user has saved, walked a page at a time.
     *
     * 50 is Spotify's maximum for this endpoint and asking for more is an error rather than a
     * silent clamp, so the page size is not a tuning knob.
     */
    suspend fun savedAlbums(token: String): SpotifyResult<List<SpotifyAlbum>> =
        withContext(Dispatchers.IO) {
            val all = mutableListOf<SpotifyAlbum>()
            var offset = 0
            while (true) {
                when (val page = get(token, "$API/me/albums?limit=$PAGE&offset=$offset") {
                    parseSavedAlbums(it)
                }) {
                    is SpotifyResult.Failed -> return@withContext page
                    is SpotifyResult.Ok -> {
                        all += page.value
                        if (page.value.size < PAGE) break
                        offset += PAGE
                        if (offset > MAX_ALBUMS) break
                    }
                }
            }
            SpotifyResult.Ok(all)
        }

    suspend fun albumTracks(token: String, albumId: String): SpotifyResult<List<SpotifyTrack>> =
        get(token, "$API/albums/$albumId/tracks?limit=$PAGE") { parseTracks(it) }

    suspend fun devices(token: String): SpotifyResult<List<SpotifyDevice>> =
        get(token, "$API/me/player/devices") { parseDevices(it) }

    /**
     * What is playing, or null when Spotify is idle.
     *
     * **Spotify answers `204 No Content` for "nothing is playing"**, which is not an error and
     * must not be treated as one — an empty body parsed as JSON throws, and the deck would show
     * a failure every time playback stopped.
     */
    suspend fun playback(token: String): SpotifyResult<SpotifyPlayback?> =
        get(token, "$API/me/player", allowEmpty = true) {
            if (it.isBlank()) null else parsePlayback(it)
        }

    /** Starts an album, optionally partway in. */
    suspend fun play(
        token: String,
        albumUri: String,
        trackIndex: Int = 0,
        positionMs: Long = 0L,
        deviceId: String? = null
    ): SpotifyResult<Unit> {
        val body = JSONObject()
            .put("context_uri", albumUri)
            .put("offset", JSONObject().put("position", trackIndex))
            .put("position_ms", positionMs)
        return send(token, "PUT", playerUrl("play", deviceId), body.toString())
    }

    suspend fun pause(token: String) = send(token, "PUT", playerUrl("pause"), null)

    suspend fun resume(token: String) = send(token, "PUT", playerUrl("play"), null)

    suspend fun next(token: String) = send(token, "POST", playerUrl("next"), null)

    suspend fun previous(token: String) = send(token, "POST", playerUrl("previous"), null)

    suspend fun seek(token: String, positionMs: Long) =
        send(token, "PUT", "$API/me/player/seek?position_ms=$positionMs", null)

    /**
     * Moves playback to a device without starting it.
     *
     * `play=false` so transferring does not begin blasting whatever was queued — the caller
     * decides what plays, and a transfer that started music by itself would be startling.
     */
    suspend fun transferTo(token: String, deviceId: String): SpotifyResult<Unit> =
        send(
            token, "PUT", "$API/me/player",
            JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", false).toString()
        )

    // ---- plumbing --------------------------------------------------------------------------

    private suspend fun <T> get(
        token: String,
        url: String,
        allowEmpty: Boolean = false,
        parse: (String) -> T
    ): SpotifyResult<T> = withContext(Dispatchers.IO) {
        val connection = open(url, "GET", token)
        try {
            val code = connection.responseCode
            if (code == 204 && allowEmpty) return@withContext SpotifyResult.Ok(parse(""))
            if (code !in 200..299) return@withContext SpotifyResult.Failed(failureFor(connection))
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            SpotifyResult.Ok(parse(body))
        } catch (e: Exception) {
            SpotifyResult.Failed(SpotifyFailure.Other(e.message ?: e.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun send(
        token: String,
        method: String,
        url: String,
        body: String?
    ): SpotifyResult<Unit> = withContext(Dispatchers.IO) {
        val connection = open(url, method, token)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else if (method == "PUT" || method == "POST") {
                // Spotify rejects a PUT with no Content-Length on some endpoints, and the
                // resulting 411 does not mention it.
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.use { }
            }
            val code = connection.responseCode
            if (code in 200..299) SpotifyResult.Ok(Unit)
            else SpotifyResult.Failed(failureFor(connection))
        } catch (e: Exception) {
            SpotifyResult.Failed(SpotifyFailure.Other(e.message ?: e.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, token: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun failureFor(connection: HttpURLConnection): SpotifyFailure {
        val code = connection.responseCode
        val body = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 5
        return failureFor(code, body, retryAfter)
    }

    private fun playerUrl(action: String, deviceId: String? = null) =
        "$API/me/player/$action" + if (deviceId == null) "" else "?device_id=$deviceId"

    companion object {
        private const val API = "https://api.spotify.com/v1"
        private const val PAGE = 50
        private const val MAX_ALBUMS = 10_000

        /**
         * Turning a status code into something worth telling the user.
         *
         * The three that matter are distinguished because they need opposite responses, and
         * lumping them into "Spotify error" is what makes an app feel broken: 401 means sign in
         * again, 403 with a premium message means nothing will ever work until the account is
         * upgraded, and 404 from a player endpoint means the Spotify app is not open — which is
         * not an error at all, just a thing to say.
         */
        fun failureFor(code: Int, body: String, retryAfterSeconds: Int = 5): SpotifyFailure {
            val reason = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("reason").orEmpty()
            }.getOrDefault("")
            val message = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("")

            return when {
                code == 401 -> SpotifyFailure.NeedsLogin
                code == 429 -> SpotifyFailure.RateLimited(retryAfterSeconds)
                reason == "PREMIUM_REQUIRED" -> SpotifyFailure.NeedsPremium
                code == 403 && message.contains("premium", ignoreCase = true) ->
                    SpotifyFailure.NeedsPremium
                reason == "NO_ACTIVE_DEVICE" || code == 404 -> SpotifyFailure.NoDevice
                message.isNotBlank() -> SpotifyFailure.Other(message)
                else -> SpotifyFailure.Other("Spotify returned $code")
            }
        }

        fun parseProfile(body: String): SpotifyProfile {
            val json = JSONObject(body)
            return SpotifyProfile(
                displayName = json.optString("display_name").ifBlank { "Spotify user" },
                product = json.optString("product").ifBlank { "unknown" }
            )
        }

        /** `/me/albums` wraps each album in a `{ added_at, album }` envelope. */
        fun parseSavedAlbums(body: String): List<SpotifyAlbum> {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull {
                albumOf(items.optJSONObject(it)?.optJSONObject("album"))
            }
        }

        fun parseTracks(body: String): List<SpotifyTrack> {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { trackOf(items.optJSONObject(it)) }
        }

        fun parseDevices(body: String): List<SpotifyDevice> {
            val items = JSONObject(body).optJSONArray("devices") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val json = items.optJSONObject(i) ?: return@mapNotNull null
                SpotifyDevice(
                    id = json.optString("id").takeIf { it.isNotBlank() },
                    name = json.optString("name").ifBlank { "Unknown device" },
                    type = json.optString("type").ifBlank { "Unknown" },
                    isActive = json.optBoolean("is_active"),
                    isRestricted = json.optBoolean("is_restricted")
                )
            }
        }

        fun parsePlayback(body: String): SpotifyPlayback? {
            val json = JSONObject(body)
            val item = json.optJSONObject("item")
            return SpotifyPlayback(
                isPlaying = json.optBoolean("is_playing"),
                trackId = item?.optString("id")?.takeIf { it.isNotBlank() },
                trackUri = item?.optString("uri")?.takeIf { it.isNotBlank() },
                positionMs = json.optLong("progress_ms"),
                durationMs = item?.optLong("duration_ms") ?: 0L,
                deviceName = json.optJSONObject("device")?.optString("name")
                    ?.takeIf { it.isNotBlank() }
            )
        }

        private fun albumOf(json: JSONObject?): SpotifyAlbum? {
            if (json == null) return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val artists = json.optJSONArray("artists")
            return SpotifyAlbum(
                id = id,
                uri = json.optString("uri").ifBlank { "spotify:album:$id" },
                title = json.optString("name").ifBlank { "Untitled" },
                // Several artists on a collaboration; joined rather than dropped, because the
                // case has one line for it and "and" is what a printed sleeve would say.
                artist = (0 until (artists?.length() ?: 0))
                    .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifBlank { "Unknown artist" },
                // `release_date` is "1997", "1997-03" or "1997-03-24" depending on precision,
                // so the year is the first four characters and never a parse of the whole thing.
                year = json.optString("release_date").take(4).toIntOrNull()?.takeIf { it > 0 },
                coverUrl = largestImage(json.optJSONArray("images")),
                trackCount = json.optInt("total_tracks")
            )
        }

        /**
         * Spotify returns images widest-first, so the first is the biggest.
         *
         * The largest is wanted here: it is fetched once at sync and then baked onto a case that
         * fills a phone screen, and an upscaled 64px thumbnail would look exactly as bad as it
         * sounds.
         */
        private fun largestImage(images: JSONArray?): String? {
            if (images == null || images.length() == 0) return null
            return images.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        }

        private fun trackOf(json: JSONObject?): SpotifyTrack? {
            if (json == null) return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val artists = json.optJSONArray("artists")
            return SpotifyTrack(
                id = id,
                uri = json.optString("uri").ifBlank { "spotify:track:$id" },
                title = json.optString("name").ifBlank { "Untitled" },
                artist = (0 until (artists?.length() ?: 0))
                    .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() },
                trackNumber = json.optInt("track_number").takeIf { it > 0 },
                discNumber = json.optInt("disc_number").takeIf { it > 0 },
                durationMs = json.optLong("duration_ms")
            )
        }
    }
}
