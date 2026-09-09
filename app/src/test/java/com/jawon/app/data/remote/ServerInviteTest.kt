package com.jawon.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric because this builds and reads `org.json`, which the bare JVM test runtime stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerInviteTest {

    private val server = SubsonicServer("https://music.example.net", "guest", "listen-only")

    @Test
    fun `a link survives the round trip`() {
        val decoded = ServerInvite.decode(ServerInvite.encode(server))!!
        assertEquals("https://music.example.net", decoded.url)
        assertEquals("guest", decoded.username)
        assertEquals("listen-only", decoded.password)
    }

    @Test
    fun `a link pasted with the mess a chat app adds still works`() {
        // The realistic failure. Copied out of a message, an invite arrives wrapped in
        // whitespace and newlines, and refusing a correct link over a trailing space would be
        // a self-inflicted support problem.
        val messy = "\n  " + ServerInvite.encode(server) + "  \n"
        assertEquals("guest", ServerInvite.decode(messy)!!.username)
    }

    @Test
    fun `a trailing slash from a linkifier is tolerated`() {
        assertEquals("guest", ServerInvite.decode(ServerInvite.encode(server) + "/")!!.username)
    }

    @Test
    fun `the encoded form has nothing in it that a URL or a QR code would mangle`() {
        val link = ServerInvite.encode(server)
        val payload = link.removePrefix(ServerInvite.SCHEME)
        // URL-safe alphabet, no padding: no +, / or = to be escaped, split or truncated on the
        // way through a chat app, a URL bar or a QR encoder.
        assertTrue(
            "payload must be URL-safe base64, was: $payload",
            payload.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        )
    }

    @Test
    fun `the password is not sitting in plain sight`() {
        // Not security — anyone can decode it, and the KDoc says so. But a password should not
        // be readable at a glance in a group chat, and this is the line that keeps it so.
        assertFalse(ServerInvite.encode(server).contains("listen-only"))
    }

    @Test
    fun `something that is not a link is refused`() {
        assertNull(ServerInvite.decode("https://music.example.net"))
        assertNull(ServerInvite.decode("hello"))
        assertNull(ServerInvite.decode(""))
        assertNull(ServerInvite.decode("   "))
    }

    @Test
    fun `a corrupted payload is refused rather than half-read`() {
        // Truncated in transit, which is what a chat app that wraps long strings does.
        assertNull(ServerInvite.decode(ServerInvite.SCHEME + "!!!not-base64!!!"))
    }

    @Test
    fun `a link carrying no address is refused, not turned into a broken server`() {
        // Decodes cleanly and is still useless. Accepting it would produce a server that fails
        // later with a confusing message instead of here with an accurate one.
        val empty = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"n":"guest","p":"x"}""".toByteArray())
        assertNull(ServerInvite.decode(ServerInvite.SCHEME + empty))
    }

    @Test
    fun `the scheme is recognised whatever case it arrives in`() {
        assertTrue(ServerInvite.looksLikeInvite("JAWON://abc"))
        assertTrue(ServerInvite.looksLikeInvite("  jawon://abc  "))
        assertFalse(ServerInvite.looksLikeInvite("jawonabc"))
    }

    @Test
    fun `an address with a port and a path survives`() {
        val odd = SubsonicServer("http://192.168.1.50:4533/music", "u", "p")
        assertEquals("http://192.168.1.50:4533/music", ServerInvite.decode(ServerInvite.encode(odd))!!.url)
    }

    @Test
    fun `a password with characters that would break a query string survives`() {
        // The reason this is a JSON payload rather than a query string: a password is allowed
        // to contain &, = and # and must come back exactly as it went in.
        val awkward = SubsonicServer("https://m.example.net", "u", "a&b=c#d /e+f%20g")
        assertEquals("a&b=c#d /e+f%20g", ServerInvite.decode(ServerInvite.encode(awkward))!!.password)
    }

    @Test
    fun `a decoded link is immediately usable`() {
        assertTrue(ServerInvite.decode(ServerInvite.encode(server))!!.isConfigured)
    }

    @Test
    fun `a link made by tools make_invite py decodes here`() {
        // **The cross-language contract, pinned.** `tools/make_invite.py` writes this format
        // independently — it has to, because the link is needed on the server before the app
        // has ever been configured — and two encoders of the same format drift the moment
        // nothing is checking. This literal is the actual output of:
        //
        //   python3 tools/make_invite.py https://music.example.net guest 'a-long-random-password'
        //
        // If a change here breaks it, the Python script needs the same change, and every
        // invite already handed out stops working.
        val fromPython = "jawon://eyJ1IjoiaHR0cHM6Ly9tdXNpYy5leGFtcGxlLm5ldCIsIm4iOiJndWVzdCIs" +
            "InAiOiJhLWxvbmctcmFuZG9tLXBhc3N3b3JkIn0"

        val decoded = ServerInvite.decode(fromPython)!!
        assertEquals("https://music.example.net", decoded.url)
        assertEquals("guest", decoded.username)
        assertEquals("a-long-random-password", decoded.password)
    }
}
