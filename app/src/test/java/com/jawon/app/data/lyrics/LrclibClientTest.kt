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
}
