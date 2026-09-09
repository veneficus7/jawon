package com.jawon.app.data.spotify

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for `android.net.Uri` and `org.json`, both of which the bare JVM runtime stubs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotifyAuthTest {

    // ---- PKCE ------------------------------------------------------------------------------

    @Test
    fun `the code challenge matches the RFC 7636 worked example`() {
        // **Checked against the spec rather than against itself.** A challenge implementation
        // can be self-consistently wrong — base64 instead of base64url, padding left on, hashing
        // the bytes of the wrong encoding — and every round-trip test still passes while Spotify
        // rejects every sign-in with `invalid_grant`. This is the vector from RFC 7636
        // Appendix B, so it fails if this drifts from what the rest of the world computes.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expected, SpotifyAuth.challengeFor(verifier))
    }

    @Test
    fun `a verifier is within the length the spec allows`() {
        val verifier = SpotifyAuth.newVerifier()
        assertTrue("was ${verifier.length}", verifier.length in 43..128)
    }

    @Test
    fun `a verifier uses only characters the spec permits`() {
        // Unreserved characters only: no +, / or = to be escaped in a query string.
        val verifier = SpotifyAuth.newVerifier()
        assertTrue(
            "bad verifier: $verifier",
            verifier.all { it.isLetterOrDigit() || it in "-._~" }
        )
    }

    @Test
    fun `two sign-ins do not share a verifier`() {
        assertNotEquals(SpotifyAuth.newVerifier(), SpotifyAuth.newVerifier())
        assertNotEquals(SpotifyAuth.newState(), SpotifyAuth.newState())
    }

    // ---- the authorize URL -----------------------------------------------------------------

    @Test
    fun `the authorize URL carries everything Spotify needs`() {
        val url = SpotifyAuth.authorizeUrl("client-123", "verifier-abc", "state-xyz")

        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-xyz"))
        assertTrue(url.contains("code_challenge=${SpotifyAuth.challengeFor("verifier-abc")}"))
        // The verifier itself must never leave the device — sending it would defeat the whole
        // point of hashing it.
        assertFalse("the verifier must not be in the URL", url.contains("verifier-abc"))
    }

    @Test
    fun `the redirect URI is encoded, since it contains characters a query string would eat`() {
        val url = SpotifyAuth.authorizeUrl("id", "v", "s")
        assertTrue(url.contains("redirect_uri=jawon%3A%2F%2Fspotify"))
    }

    @Test
    fun `only the scopes that are actually used are asked for`() {
        // `streaming` is for the browser-only Web Playback SDK. Asking for a permission the app
        // cannot use makes the consent screen look more invasive than the app is.
        assertFalse(SpotifyAuth.SCOPES.contains("streaming"))
        assertTrue(SpotifyAuth.SCOPES.contains("user-library-read"))
        assertTrue(SpotifyAuth.SCOPES.contains("user-modify-playback-state"))
    }

    // ---- the redirect ----------------------------------------------------------------------

    @Test
    fun `a good redirect yields the code`() {
        val uri = Uri.parse("jawon://spotify?code=the-code&state=st")
        assertEquals("the-code", SpotifyAuth.codeFrom(uri, "st"))
    }

    @Test
    fun `a redirect whose state does not match is refused`() {
        // The cross-site guard. Without it, another app could hand this one a code it did not
        // ask for.
        val uri = Uri.parse("jawon://spotify?code=the-code&state=someone-elses")
        assertNull(SpotifyAuth.codeFrom(uri, "st"))
    }

    @Test
    fun `pressing cancel comes back with no code and is not a crash`() {
        val uri = Uri.parse("jawon://spotify?error=access_denied&state=st")
        assertNull(SpotifyAuth.codeFrom(uri, "st"))
    }

    @Test
    fun `only our own redirect is recognised`() {
        assertTrue(SpotifyAuth.isRedirect(Uri.parse("jawon://spotify?code=x")))
        assertFalse(SpotifyAuth.isRedirect(Uri.parse("https://example.com/callback")))
        assertFalse(SpotifyAuth.isRedirect(Uri.parse("jawon://something-else")))
    }

    // ---- tokens ----------------------------------------------------------------------------

    @Test
    fun `a token response is read, and the expiry is absolute`() {
        val tokens = SpotifyAuth.parseTokens(
            """{"access_token":"AAA","refresh_token":"RRR","expires_in":3600,"token_type":"Bearer"}""",
            now = 1_000_000L
        )!!

        assertEquals("AAA", tokens.accessToken)
        assertEquals("RRR", tokens.refreshToken)
        // 3600s minus a minute of slack, so a token that expires mid-request is refreshed
        // beforehand rather than failing once.
        assertEquals(1_000_000L + 3_540_000L, tokens.expiresAt)
    }

    @Test
    fun `a refresh that omits a new refresh token reports null, not an empty string`() {
        // **The dangerous one.** Spotify frequently returns no refresh_token on a refresh,
        // meaning "keep the one you have". If this came back as "" and were stored, the user
        // would be silently signed out about once an hour, and nothing about that symptom
        // points at this line. SpotifySession.store relies on this being null.
        val tokens = SpotifyAuth.parseTokens(
            """{"access_token":"BBB","expires_in":3600}""",
            now = 0L
        )!!

        assertEquals("BBB", tokens.accessToken)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a response with no access token is not a token`() {
        assertNull(SpotifyAuth.parseTokens("""{"error":"invalid_grant"}""", now = 0L))
    }

    @Test
    fun `Spotify's own error text is recovered for showing`() {
        assertEquals(
            "Invalid authorization code",
            SpotifyAuth.errorFrom(
                """{"error":"invalid_grant","error_description":"Invalid authorization code"}"""
            )
        )
        assertEquals("invalid_client", SpotifyAuth.errorFrom("""{"error":"invalid_client"}"""))
    }

    @Test
    fun `the redirect URI is the one the manifest registers`() {
        // These two drift apart silently, and Spotify reports the result as INVALID_CLIENT
        // without mentioning that the strings differ. AndroidManifest declares
        // scheme="jawon" host="spotify"; this is the other half of that pair.
        assertEquals("jawon://spotify", SpotifyAuth.REDIRECT_URI)
    }
}
