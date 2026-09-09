package com.jawon.app.data.spotify

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** What a token exchange produced. [refreshToken] is null on a refresh that did not rotate it. */
data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String?,
    /** Absolute wall-clock millis, not a duration — a duration is only useful at the moment it arrives. */
    val expiresAt: Long
)

/**
 * Signing in to Spotify, by the only flow a phone app is allowed to use.
 *
 * ## Why PKCE and not a client secret
 *
 * The Authorization Code flow with a client secret is the one every backend tutorial shows, and
 * it is unusable here: the secret would have to ship inside the APK, where it is not a secret.
 * PKCE replaces it with a value generated fresh for each sign-in — a random **verifier** kept in
 * memory, and its SHA-256 hash sent as the **challenge** when the browser is opened. Spotify
 * hands back a code that is worthless without the verifier, so intercepting the redirect gains
 * an attacker nothing. This is why there is no client secret anywhere in this app and why
 * adding one later would be a step backwards.
 *
 * ## What has to happen outside this class
 *
 * The user signs in **in a browser**, not in a WebView. That is a deliberate constraint of
 * OAuth, not a style choice: a WebView is inspectable by the host app, so a password typed into
 * one is a password handed to whoever wrote the app. The browser also already holds any
 * existing Spotify session, which usually turns signing in into one tap.
 *
 * Spotify redirects back to `jawon://spotify`, which `MainActivity` receives.
 *
 * ## The five-user ceiling
 *
 * Worth stating where someone will find it. Since February 2026 a Spotify app in development
 * mode is limited to **five users, each of whom must have Premium**, and anything past that
 * needs Spotify to grant extended quota. This is not a limit this code can raise. Anyone
 * planning to hand the app to more than a handful of people needs that approval first, and it
 * is not guaranteed.
 */
@Singleton
class SpotifyAuth @Inject constructor() {

    /**
     * Everything needed to open the browser: where to send the user, and the verifier that has
     * to survive until they come back.
     */
    data class Challenge(val authorizeUrl: String, val verifier: String, val state: String)

    fun beginLogin(clientId: String): Challenge {
        val verifier = newVerifier()
        val state = newState()
        return Challenge(
            authorizeUrl = authorizeUrl(clientId, verifier, state),
            verifier = verifier,
            state = state
        )
    }

    /**
     * Trades the code from the redirect for tokens.
     *
     * Form-encoded rather than JSON because the OAuth spec says so and Spotify enforces it —
     * posting JSON here returns an `unsupported_grant_type` that says nothing about the cause.
     */
    suspend fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
        now: Long = System.currentTimeMillis()
    ): SpotifyTokens? = withContext(Dispatchers.IO) {
        val body = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "code_verifier" to verifier
        )
        runCatching { parseTokens(post(TOKEN_URL, body), now) }.getOrNull()
    }

    /**
     * Swaps a refresh token for a fresh access token.
     *
     * **Spotify may or may not return a new refresh token**, and the caller has to keep the old
     * one when it does not — a null here means "keep what you have", never "you have been signed
     * out". Treating it as the latter logs the user out roughly every hour, which was worth a
     * comment because the symptom looks like anything but this.
     */
    suspend fun refresh(
        clientId: String,
        refreshToken: String,
        now: Long = System.currentTimeMillis()
    ): SpotifyTokens? = withContext(Dispatchers.IO) {
        val body = form(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId
        )
        runCatching { parseTokens(post(TOKEN_URL, body), now) }.getOrNull()
    }

    private fun post(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                // Spotify puts the useful part — "invalid_grant", "invalid_client" — in the
                // error body, and reading only the status code throws it away.
                connection.errorStream ?: connection.inputStream
            }
            return stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Must match the redirect URI registered in the Spotify dashboard **exactly**, including
         * case and any trailing slash. A mismatch is the single most common setup failure and
         * Spotify reports it as `INVALID_CLIENT: Invalid redirect URI`, which does not say that
         * the two strings differ by a slash.
         */
        const val REDIRECT_URI = "jawon://spotify"

        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        /**
         * The least that will do.
         *
         * `user-library-read` to see the albums the user has saved; the two `playback` scopes to
         * read what is playing and to control it. Notably **not** `streaming` — that is for the
         * Web Playback SDK, which is browser-only, and asking for a permission the app cannot
         * use is how a consent screen ends up looking more invasive than the app is.
         */
        val SCOPES = listOf(
            "user-library-read",
            "user-read-playback-state",
            "user-modify-playback-state"
        )

        private val RANDOM = SecureRandom()

        /**
         * A code verifier: 64 random bytes, URL-safe Base64, unpadded.
         *
         * The spec allows 43–128 characters; this lands at 86. Shorter is legal and there is no
         * reason to take it.
         */
        fun newVerifier(): String {
            val bytes = ByteArray(64)
            RANDOM.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Guards against a redirect that did not come from the request this app made. */
        fun newState(): String {
            val bytes = ByteArray(16)
            RANDOM.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** SHA-256 of the verifier, URL-safe Base64, unpadded — the S256 method. */
        fun challengeFor(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun authorizeUrl(clientId: String, verifier: String, state: String): String {
            val params = listOf(
                "client_id" to clientId,
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "code_challenge_method" to "S256",
                "code_challenge" to challengeFor(verifier),
                "state" to state,
                "scope" to SCOPES.joinToString(" ")
            )
            return AUTHORIZE_URL + "?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        }

        /**
         * Reads the code out of the redirect, checking the state matches.
         *
         * Returns null both for a mismatched state and for a user who pressed *Cancel* — the
         * redirect then carries `error=access_denied` and no code at all, which is an ordinary
         * outcome rather than a failure to report.
         */
        fun codeFrom(uri: Uri, expectedState: String): String? {
            if (uri.getQueryParameter("state") != expectedState) return null
            return uri.getQueryParameter("code")
        }

        /** True if this redirect is one of ours, so MainActivity can ignore anything else. */
        fun isRedirect(uri: Uri): Boolean =
            "${uri.scheme}://${uri.host}".equals(REDIRECT_URI, ignoreCase = true)

        fun parseTokens(body: String, now: Long): SpotifyTokens? {
            val json = JSONObject(body)
            val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            // Sixty seconds of slack, so a token that expires while a request is in flight is
            // refreshed before it is used rather than failing once and being retried.
            val expiresIn = json.optLong("expires_in", 3600L)
            return SpotifyTokens(
                accessToken = access,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresAt = now + (expiresIn - 60L) * 1000L
            )
        }

        /** The error Spotify reported, for showing rather than swallowing. */
        fun errorFrom(body: String): String? = runCatching {
            val json = JSONObject(body)
            json.optString("error_description").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun form(vararg pairs: Pair<String, String>) =
            pairs.joinToString("&") { (k, v) -> "$k=${enc(v)}" }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
