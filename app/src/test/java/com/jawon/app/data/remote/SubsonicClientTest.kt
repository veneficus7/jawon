package com.jawon.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric for the same reason `LrclibClientTest` needs it: this parses with `org.json`,
 * which the plain JVM test runtime stubs out into methods that all throw.
 *
 * The fixtures below are Navidrome's actual response shapes, trimmed to the fields Jawon reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicClientTest {

    // ---- the envelope ---------------------------------------------------------------------

    @Test
    fun `a refusal arrives as a successful HTTP response and must not read as success`() {
        // The trap this whole class is built around. Subsonic answers a wrong password with
        // HTTP 200 and a nested error, so anything checking the status code alone would report
        // a working login for credentials that do not work.
        val body = """
            {"subsonic-response":{"status":"failed","version":"1.16.1",
             "error":{"code":40,"message":"Wrong username or password"}}}
        """.trimIndent()

        assertEquals("Wrong username or password", SubsonicClient.error(body))

        val probe = SubsonicClient.parsePing(body)
        assertTrue("a refusal is not reachability", probe is ServerProbe.Refused)
        assertEquals("Wrong username or password", (probe as ServerProbe.Refused).reason)
    }

    @Test
    fun `a good response has no error`() {
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        assertNull(SubsonicClient.error(body))
    }

    @Test
    fun `something that is not a Subsonic server is unreachable, not refused`() {
        // Pointing the URL at a web root or a proxy login page. The distinction matters because
        // the two need opposite advice: check the address, versus check the password.
        val probe = SubsonicClient.parsePing("<html><body>Not found</body></html>")
        assertTrue(probe is ServerProbe.Unreachable)
    }

    @Test
    fun `valid JSON that is not a Subsonic envelope is still an error`() {
        assertEquals("Not a Subsonic server", SubsonicClient.error("""{"hello":"world"}"""))
    }

    @Test
    fun `a ping names the server`() {
        val probe = SubsonicClient.parsePing(
            """
            {"subsonic-response":{"status":"ok","version":"1.16.1",
             "type":"navidrome","serverVersion":"0.58.0"}}
            """.trimIndent()
        )
        probe as ServerProbe.Reachable
        assertEquals("navidrome", probe.serverType)
        assertEquals("0.58.0", probe.version)
    }

    @Test
    fun `a plain Subsonic server that sends no type still reports as reachable`() {
        // `type` and `serverVersion` are OpenSubsonic additions. A server that predates them is
        // not broken, and must not fall through to "unknown server".
        val probe = SubsonicClient.parsePing(
            """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        )
        probe as ServerProbe.Reachable
        assertEquals("Subsonic", probe.serverType)
        assertEquals("1.16.1", probe.version)
    }

    // ---- the album list -------------------------------------------------------------------

    @Test
    fun `an album list is read into albums`() {
        val albums = SubsonicClient.parseAlbumList(
            """
            {"subsonic-response":{"status":"ok","albumList2":{"album":[
              {"id":"al-1","name":"Kid A","artist":"Radiohead","year":2000,
               "coverArt":"al-1","songCount":10},
              {"id":"al-2","name":"Amnesiac","artist":"Radiohead","songCount":11}
            ]}}}
            """.trimIndent()
        )

        assertEquals(2, albums.size)
        assertEquals("Kid A", albums[0].title)
        assertEquals(2000, albums[0].year)
        assertEquals("al-1", albums[0].coverArtId)
        assertEquals(10, albums[0].trackCount)

        // A missing year is unknown, not the year zero. `optInt` returns 0 for an absent key,
        // and a case printed with "0" under its title would be worse than one printed with none.
        assertNull("a missing year must be null, not 0", albums[1].year)
        assertNull("a missing coverArt must be null", albums[1].coverArtId)
    }

    @Test
    fun `an empty library is an empty list, not a failure`() {
        // Navidrome omits the `album` array entirely rather than sending an empty one.
        val albums = SubsonicClient.parseAlbumList(
            """{"subsonic-response":{"status":"ok","albumList2":{}}}"""
        )
        assertTrue(albums.isEmpty())
    }

    @Test
    fun `a refused album list yields nothing rather than throwing`() {
        val albums = SubsonicClient.parseAlbumList(
            """
            {"subsonic-response":{"status":"failed",
             "error":{"code":40,"message":"Wrong username or password"}}}
            """.trimIndent()
        )
        assertTrue(albums.isEmpty())
    }

    // ---- one album, and the unit traps ----------------------------------------------------

    @Test
    fun `seconds become milliseconds and kilobits become bits`() {
        // The single most likely silent corruption in this file. Subsonic reports duration in
        // seconds and bitrate in kbps; everything in Jawon is milliseconds and bits per second.
        // Getting either wrong does not fail — it renders a four-minute track as four
        // milliseconds and a FLAC as a 900 bps stream, both of which look like a UI bug
        // somewhere else entirely.
        val detail = SubsonicClient.parseAlbum(
            """
            {"subsonic-response":{"status":"ok","album":{
              "id":"al-1","name":"Kid A","artist":"Radiohead","year":2000,"songCount":1,
              "song":[{"id":"tr-1","title":"Everything In Its Right Place","track":1,
                       "duration":251,"contentType":"audio/flac","bitRate":900,
                       "samplingRate":44100,"bitDepth":16}]}}}
            """.trimIndent()
        )!!

        val track = detail.tracks.single()
        assertEquals(251_000L, track.durationMs)
        assertEquals(900_000, track.bitrateBps)
        assertEquals(44_100, track.sampleRateHz)
        assertEquals(16, track.bitDepth)
    }

    @Test
    fun `a track with no separate credit falls back rather than claiming an empty artist`() {
        val detail = SubsonicClient.parseAlbum(
            """
            {"subsonic-response":{"status":"ok","album":{
              "id":"al-1","name":"Kid A","artist":"Radiohead","songCount":1,
              "song":[{"id":"tr-1","title":"Idioteque","duration":100,"contentType":"audio/flac"}]}}}
            """.trimIndent()
        )!!

        // Null, not "", so the deck falls back to the album artist exactly the way it does for
        // a local file that carries no per-track tag.
        assertNull(detail.tracks.single().artist)
    }

    @Test
    fun `a plain Subsonic server that omits sample rate reports not-known rather than 44100`() {
        val detail = SubsonicClient.parseAlbum(
            """
            {"subsonic-response":{"status":"ok","album":{
              "id":"al-1","name":"Kid A","artist":"Radiohead","songCount":1,
              "song":[{"id":"tr-1","title":"Idioteque","duration":100,
                       "contentType":"audio/mpeg","bitRate":320}]}}}
            """.trimIndent()
        )!!

        val track = detail.tracks.single()
        // The deck prints this. An invented 44100 is a confident lie; a blank is the truth.
        assertNull(track.sampleRateHz)
        assertNull(track.bitDepth)
    }

    @Test
    fun `disc numbers survive, and a single-disc album has none`() {
        val detail = SubsonicClient.parseAlbum(
            """
            {"subsonic-response":{"status":"ok","album":{
              "id":"al-1","name":"Sandinista","artist":"The Clash","songCount":2,
              "song":[
                {"id":"tr-1","title":"The Magnificent Seven","track":1,"discNumber":1,
                 "duration":100,"contentType":"audio/flac"},
                {"id":"tr-2","title":"Lose This Skin","track":1,
                 "duration":100,"contentType":"audio/flac"}]}}}
            """.trimIndent()
        )!!

        assertEquals(1, detail.tracks[0].discNumber)
        // Absent means "not part of a set", the same distinction Track.discNumber keeps locally.
        assertNull(detail.tracks[1].discNumber)
    }

    @Test
    fun `an album the server does not know is null, not an empty album`() {
        assertNull(
            SubsonicClient.parseAlbum(
                """
                {"subsonic-response":{"status":"failed",
                 "error":{"code":70,"message":"Album not found"}}}
                """.trimIndent()
            )
        )
    }

    @Test
    fun `an album with no songs parses, so the sync can skip it deliberately`() {
        val detail = SubsonicClient.parseAlbum(
            """
            {"subsonic-response":{"status":"ok","album":{
              "id":"al-1","name":"Empty","artist":"Nobody","songCount":0}}}
            """.trimIndent()
        )!!
        assertTrue(detail.tracks.isEmpty())
    }

    // ---- URLs and authentication ----------------------------------------------------------

    @Test
    fun `the password never appears in a request`() {
        // The point of Subsonic's salted-token scheme, and worth an assertion because a
        // refactor to "simpler" plain auth would be invisible in every other test here.
        val server = SubsonicServer("https://music.example.net", "sam", "hunter2")
        val query = server.authQuery(salt = "abcdef123")

        assertTrue(query.contains("u=sam"))
        assertTrue(query.contains("s=abcdef123"))
        assertTrue("the password itself must never be sent", !query.contains("hunter2"))
        // md5("hunter2" + "abcdef123"), which is the whole authenticator.
        assertEquals(SubsonicServer.md5("hunter2abcdef123"), tokenOf(query))
    }

    @Test
    fun `a trailing slash on the URL does not double the separator`() {
        val server = SubsonicServer("https://music.example.net/", "sam", "pw")
        val url = server.endpoint("ping.view", salt = "s")
        assertTrue(url.startsWith("https://music.example.net/rest/ping.view?"))
    }

    @Test
    fun `a username with characters that need escaping is encoded`() {
        val server = SubsonicServer("https://m.example.net", "sam jones+1", "pw")
        val query = server.authQuery(salt = "s")
        assertTrue(query.contains("u=sam+jones%2B1"))
    }

    @Test
    fun `a half-filled form is not configured`() {
        assertTrue(!SubsonicServer("https://m.example.net", "sam", "").isConfigured)
        assertTrue(!SubsonicServer("", "sam", "pw").isConfigured)
        assertTrue(!SubsonicServer.NONE.isConfigured)
        assertTrue(SubsonicServer("https://m.example.net", "sam", "pw").isConfigured)
    }

    @Test
    fun `two stream URLs for the same track differ, which is why caching cannot key on them`() {
        // This is the fact that forces `setCustomCacheKey` in PlaybackRepository. If this test
        // ever fails because the salt was made constant, the cache key can be simplified — and
        // if the salt stays random and the custom key is dropped, the cache silently stops
        // hitting. The two decisions are tied together, so the coupling is asserted here.
        val client = SubsonicClient()
        val server = SubsonicServer("https://m.example.net", "sam", "pw")

        val first = client.streamUrl(server, "tr-1")
        val second = client.streamUrl(server, "tr-1")

        assertTrue("both must address the same track", first.contains("id=tr-1"))
        assertTrue(second.contains("id=tr-1"))
        assertTrue("a fresh salt means a fresh URL every time", first != second)
    }

    @Test
    fun `a raw stream is asked for, so ReplayGain tags and the quality readout survive`() {
        val url = SubsonicClient().streamUrl(
            SubsonicServer("https://m.example.net", "sam", "pw"),
            "tr-1"
        )
        // A server-side transcode strips the ReplayGain tags ReplayGainReader depends on and
        // makes the deck's printed bit depth untrue.
        assertTrue(url.contains("format=raw"))
    }

    private fun tokenOf(query: String): String =
        query.split("&").first { it.startsWith("t=") }.removePrefix("t=")
}
