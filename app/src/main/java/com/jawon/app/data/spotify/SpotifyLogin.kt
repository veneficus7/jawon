package com.jawon.app.data.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the sign-in is doing, for a screen to draw. */
sealed interface SpotifyLoginState {
    data object Idle : SpotifyLoginState
    data object WaitingForBrowser : SpotifyLoginState
    data class Connected(val displayName: String, val isPremium: Boolean) : SpotifyLoginState
    data class Failed(val reason: String) : SpotifyLoginState
}

/**
 * Drives the browser round trip, and survives the app being killed while it happens.
 *
 * **That last part is the whole reason this is a singleton rather than state in a ViewModel.**
 * Signing in leaves Jawon entirely: the browser opens, and Android is free to destroy this
 * process while it is in the foreground. The PKCE verifier generated before the browser opened
 * must still be there when the redirect arrives, or the code that comes back cannot be
 * exchanged for anything and the user sees a failure with no cause.
 *
 * Held in memory here and *not* written to preferences, which is the deliberate trade: a
 * process death mid-sign-in means starting the sign-in again, which is one tap, and is better
 * than persisting a credential-adjacent secret to disk for the sake of that rare case.
 */
@Singleton
class SpotifyLogin @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: SpotifyAuth,
    private val session: SpotifySession,
    private val client: SpotifyClient
) {

    private var pending: SpotifyAuth.Challenge? = null

    private val _state = MutableStateFlow<SpotifyLoginState>(SpotifyLoginState.Idle)
    val state: StateFlow<SpotifyLoginState> = _state.asStateFlow()

    /**
     * Opens the browser at Spotify's consent screen.
     *
     * `FLAG_ACTIVITY_NEW_TASK` because this is launched from an application context, not an
     * activity one — without it the launch throws, and it throws at the moment the user taps
     * Connect rather than anywhere near here.
     */
    fun begin(clientId: String): Boolean {
        if (clientId.isBlank()) {
            _state.value = SpotifyLoginState.Failed("Enter your Spotify app's Client ID first.")
            return false
        }
        val challenge = auth.beginLogin(clientId)
        pending = challenge
        _state.value = SpotifyLoginState.WaitingForBrowser
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(challenge.authorizeUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrElse {
            _state.value = SpotifyLoginState.Failed("No browser available to sign in with.")
            false
        }
    }

    /**
     * Handles the redirect. Returns true if it was ours and was dealt with.
     *
     * A user who pressed *Cancel* comes back with an error and no code. That is an ordinary
     * outcome — they changed their mind — so it resets to idle rather than reporting a fault.
     */
    suspend fun onRedirect(uri: Uri): Boolean {
        if (!SpotifyAuth.isRedirect(uri)) return false
        val challenge = pending ?: run {
            _state.value = SpotifyLoginState.Failed("That sign-in expired. Try again.")
            return true
        }

        val code = SpotifyAuth.codeFrom(uri, challenge.state)
        if (code == null) {
            pending = null
            _state.value = if (uri.getQueryParameter("error") != null) {
                SpotifyLoginState.Idle
            } else {
                // A state mismatch, which means this redirect did not come from the request
                // this app made. Refused rather than reported in detail.
                SpotifyLoginState.Failed("That sign-in could not be verified. Try again.")
            }
            return true
        }

        val tokens = auth.exchangeCode(session.clientId, code, challenge.verifier)
        pending = null
        if (tokens == null) {
            _state.value = SpotifyLoginState.Failed(
                "Spotify refused the sign-in. Check the Client ID and that the redirect URI " +
                    "in your Spotify dashboard is exactly ${SpotifyAuth.REDIRECT_URI}"
            )
            return true
        }
        session.store(tokens)

        // Asked immediately, because whether the account is Premium decides whether anything
        // can be played at all, and finding out on the first tap of a case is worse.
        _state.value = when (val profile = client.profile(tokens.accessToken)) {
            is SpotifyResult.Ok ->
                SpotifyLoginState.Connected(profile.value.displayName, profile.value.isPremium)
            is SpotifyResult.Failed ->
                SpotifyLoginState.Connected("Spotify", isPremium = false)
        }
        return true
    }

    fun signOut() {
        session.signOut()
        pending = null
        _state.value = SpotifyLoginState.Idle
    }
}
