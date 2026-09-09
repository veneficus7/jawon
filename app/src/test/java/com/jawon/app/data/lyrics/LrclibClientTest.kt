package com.jawon.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric because this parses with `org.json`, which the plain JVM test runtime stubs out —
 * every call throws, `runCatching` swallows it, and every one of these would pass or fail for
 * reasons having nothing to do with the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LrclibClientTest {

    @Test
    fun `takes the timed words when both are offered`() {
        val result = LrclibClient.parse(
            """
            {"id":1,"trackName":"2007","artistName":"JID",
             "plainLyrics":"plain words",
             "syncedLyrics":"[00:10.00]timed words"}
            """.trimIndent()
        )!!
        assertEquals("[00:10.00]timed words", result.synced)
        assertEquals("plain words", result.plain)
        // Timed is the whole reason to ask, so it wins.
        assertEquals("[00:10.00]timed words", result.best)
    }

    @Test
    fun `falls back to the plain words`() {
        val result = LrclibClient.parse(
            """{"id":1,"plainLyrics":"just words","syncedLyrics":null}"""
        )!!
        assertNull(result.synced)
        assertEquals("just words", result.best)
    }

    @Test
    fun `a JSON null is not the word null`() {
        // `optString` turns a JSON null into the four-character string "null", which would show
        // up on screen as the entire lyric of the song.
        val result = LrclibClient.parse("""{"id":1,"plainLyrics":"real","syncedLyrics":null}""")!!
        assertNull(result.synced)
        assertEquals("real", result.best)
    }

    @Test
    fun `an instrumental has an entry and no words`() {
        // A real answer rather than a failure, and it must not be mistaken for one.
        assertNull(
            LrclibClient.parse(
                """{"id":1,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""
            )
        )
    }

    @Test
    fun `a miss is not lyrics`() {
        // The service answers "we do not have this" with a status object rather than a track.
        assertNull(
            LrclibClient.parse("""{"statusCode":404,"error":"Not Found","message":"..."}""")
        )
    }

    @Test
    fun `blank fields count as absent`() {
        assertNull(LrclibClient.parse("""{"id":1,"plainLyrics":"","syncedLyrics":"   "}"""))
    }

    @Test
    fun `nonsense in never throws`() {
        // This parses whatever a server hands back, including whatever a captive portal
        // substitutes for it.
        assertNull(LrclibClient.parse(""))
        assertNull(LrclibClient.parse("not json"))
        assertNull(LrclibClient.parse("[]"))
        assertNull(LrclibClient.parse("<html>Login required</html>"))
    }

    // ---- The loose search, for when the exact question fails ------------------------------

    @Test
    fun `an explicit feature word comes off the artist`() {
        // The tag credits everyone; the lyrics entry is filed under the lead.
        assertEquals("Kendrick Lamar", LrclibClient.searchableArtist("Kendrick Lamar feat. SZA"))
        assertEquals("Kendrick Lamar", LrclibClient.searchableArtist("Kendrick Lamar ft. SZA"))
        assertEquals(
            "Kendrick Lamar",
            LrclibClient.searchableArtist("Kendrick Lamar featuring SZA")
        )
    }

    /**
     * **The one this rule exists to protect.** Punctuation in a band's name looks exactly like
     * a credit list and is not, and a rule that cut here would search for an artist nobody has
     * heard of — which fails while looking like it worked.
     */
    @Test
    fun `a band whose name contains a separator is left whole`() {
        assertEquals("Pink Floyd", LrclibClient.searchableArtist("Pink Floyd"))
        assertEquals("Earth, Wind & Fire", LrclibClient.searchableArtist("Earth, Wind & Fire"))
        assertEquals(
            "Crosby, Stills & Nash",
            LrclibClient.searchableArtist("Crosby, Stills & Nash")
        )
        assertEquals("Simon & Garfunkel", LrclibClient.searchableArtist("Simon & Garfunkel"))
        assertEquals("Kendrick Lamar, SZA", LrclibClient.searchableArtist("Kendrick Lamar, SZA"))
    }

    @Test
    fun `stripping never leaves an empty artist`() {
        // A leading marker would otherwise cut the whole name away and search for nothing.
        assertEquals("feat. SZA", LrclibClient.searchableArtist("feat. SZA"))
    }

    @Test
    fun `the last resort takes the first name in a credit list`() {
        assertEquals("Kendrick Lamar", LrclibClient.leadArtist("Kendrick Lamar, SZA"))
        assertEquals("Kendrick Lamar", LrclibClient.leadArtist("Kendrick Lamar & SZA"))
        // Unchanged when there is nothing to cut, which is what tells the caller not to spend
        // a second request repeating the search it just made.
        assertEquals("Pink Floyd", LrclibClient.leadArtist("Pink Floyd"))
        // And yes, this is wrong for a band with a comma in its name — deliberately, and only
        // ever after a full-name search has already come back empty.
        assertEquals("Earth", LrclibClient.leadArtist("Earth, Wind & Fire"))
    }

    @Test
    fun `the closest length wins`() {
        val body = """
            [
              {"id":1,"duration":260,"plainLyrics":"wrong cut"},
              {"id":2,"duration":243,"plainLyrics":"right cut"},
              {"id":3,"duration":600,"plainLyrics":"much longer"}
            ]
        """.trimIndent()

        assertEquals("right cut", LrclibClient.bestOf(body, durationSeconds = 244)?.best)
    }

    @Test
    fun `a timed answer beats an untimed one at the same distance`() {
        val body = """
            [
              {"id":1,"duration":243,"plainLyrics":"plain"},
              {"id":2,"duration":243,"syncedLyrics":"[00:01.00] timed"}
            ]
        """.trimIndent()

        val best = LrclibClient.bestOf(body, durationSeconds = 243)
        assertEquals("[00:01.00] timed", best?.synced)
    }

    @Test
    fun `nothing close enough is nothing, not the nearest miss`() {
        // **The property worth having.** A radio edit's words scrolling against an album cut is
        // worse than no words: it looks like the feature is broken rather than absent, and the
        // user has no way to tell which. Half a minute out is a different recording.
        val body = """[{"id":1,"duration":180,"syncedLyrics":"[00:01.00] a different cut"}]"""

        assertNull(LrclibClient.bestOf(body, durationSeconds = 243))
    }

    @Test
    fun `a candidate with no length is accepted but ranked last`() {
        val body = """
            [
              {"id":1,"syncedLyrics":"[00:01.00] unknown length"},
              {"id":2,"duration":245,"plainLyrics":"known length"}
            ]
        """.trimIndent()

        // The one that could be checked wins, even though the other is timed — a length that
        // matches is stronger evidence of the same recording than timestamps are.
        assertEquals("known length", LrclibClient.bestOf(body, durationSeconds = 243)?.best)
    }

    @Test
    fun `an instrumental in the list is skipped rather than returned empty`() {
        val body = """
            [
              {"id":1,"duration":243,"plainLyrics":null,"syncedLyrics":null},
              {"id":2,"duration":246,"plainLyrics":"words"}
            ]
        """.trimIndent()

        assertEquals("words", LrclibClient.bestOf(body, durationSeconds = 243)?.best)
    }

    @Test
    fun `a search that returns nonsense never throws`() {
        assertNull(LrclibClient.bestOf("", 200))
        assertNull(LrclibClient.bestOf("{}", 200))
        assertNull(LrclibClient.bestOf("[]", 200))
        assertNull(LrclibClient.bestOf("<html>captive portal</html>", 200))
    }
}
