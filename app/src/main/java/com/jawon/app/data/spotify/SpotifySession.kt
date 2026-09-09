package com.jawon.app.data.spotify

import com.jawon.app.data.prefs.AppPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Spotify sign-in and hands out an access token that is currently valid.
 *
 * **Everything that talks to Spotify goes through [token] and nothing keeps its own copy.** A
 * Spotify access token lives an hour; a refresh token lives until it is revoked. The failure
 * this design exists to prevent is the ordinary one — several parts of the app noticing the
 * expiry at the same moment, each firing its own refresh, and Spotify invalidating all but one
 * of the results. The mutex means the first caller refreshes and the rest wait for that answer.
 *
 * The tokens sit in `SharedPreferences`, in the app's private storage, next to the music
 * server's password and with the same trade recorded there: readable on a rooted phone,
 * unreadable to other apps on a normal one, and not encryptable in any way that survives a
 * restore without locking the user out of their own library.
 */
@Singleton
class SpotifySession @Inject constructor(
    private val preferences: AppPreferences,
    private val auth: SpotifyAuth
) {

    private val refreshLock = Mutex()

    val isConnected: Boolean get() = preferences.spotifyRefreshToken != null

    val clientId: String get() = preferences.spotifyClientId.value

    /** True once there is both an app id to sign in with and a sign-in to use. */
    val isReady: Boolean get() = clientId.isNotBlank() && isConnected

    /**
     * A token good for at least the next minute, refreshing first if needed.
     *
     * Null means the user has to sign in again — the refresh token was revoked, or was never
     * there. Callers should surface that rather than retrying, because nothing about it improves
     * on its own.
     */
    suspend fun token(now: Long = System.currentTimeMillis()): String? = refreshLock.withLock {
        val access = preferences.spotifyAccessToken
        if (access != null && now < preferences.spotifyExpiresAt) return access

        val refresh = preferences.spotifyRefreshToken ?: return null
        val id = clientId.ifBlank { return null }
        val fresh = auth.refresh(id, refresh, now) ?: return null
        store(fresh, now)
        fresh.accessToken
    }

    /** Records the result of a sign-in or a refresh. */
    fun store(tokens: SpotifyTokens, now: Long = System.currentTimeMillis()) {
        preferences.spotifyAccessToken = tokens.accessToken
        preferences.spotifyExpiresAt = tokens.expiresAt
        // **Only when Spotify sent one.** A refresh response often omits it, meaning "the one you
        // have is still good"; writing the null through would sign the user out about once an
        // hour, and the symptom — random sign-outs — points nowhere near this line.
        tokens.refreshToken?.let { preferences.spotifyRefreshToken = it }
    }

    fun signOut() = preferences.clearSpotifyTokens()
}
