package com.jawon.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `reads an ordinary timed file`() {
        val lyrics = LrcParser.parse(
            """
            [ti:Money]
            [ar:JID]
            [00:12.00]First line
            [00:15.50]Second line
            [01:02.25]Third line
            """.trimIndent()
        )!!
        assertTrue(lyrics.synced)
        assertEquals(LyricSource.SIDECAR, lyrics.source)
        assertEquals(3, lyrics.lines.size)
        assertEquals(12_000L, lyrics.lines[0].timeMs)
        assertEquals("First line", lyrics.lines[0].text)
        assertEquals(15_500L, lyrics.lines[1].timeMs)
        assertEquals(62_250L, lyrics.lines[2].timeMs)
    }

    @Test
    fun `metadata tags are not sung`() {
        // `[ti:...]` and friends look exactly like a lyric line to a naive parser, and a sheet
        // that opens with "Money" and "JID" as its first two lines is obviously wrong.
        val lyrics = LrcParser.parse(
            """
            [ti:Money]
            [ar:JID]
            [al:The Forever Story]
            [by:someone]
            [re:LRCGET]
            [ve:1.0]
            [00:01.00]The only real line
            """.trimIndent()
        )!!
        assertEquals(1, lyrics.lines.size)
        assertEquals("The only real line", lyrics.lines[0].text)
    }

    @Test
    fun `two digits after the point are hundredths, three are milliseconds`() {
        // Reading `.50` as half a millisecond would put every line half a second early.
        val lyrics = LrcParser.parse(
            """
            [00:10.5]a
            [00:20.50]b
            [00:30.500]c
            [00:40]d
            """.trimIndent()
        )!!
        assertEquals(10_500L, lyrics.lines[0].timeMs)
        assertEquals(20_500L, lyrics.lines[1].timeMs)
        assertEquals(30_500L, lyrics.lines[2].timeMs)
        assertEquals(40_000L, lyrics.lines[3].timeMs)
    }

    @Test
    fun `a line may carry several timestamps`() {
        // A repeated chorus is written once, stamped for each time it comes round.
        val lyrics = LrcParser.parse("[00:10.00][01:10.00][02:10.00]Chorus")!!
        assertEquals(3, lyrics.lines.size)
        assertTrue(lyrics.lines.all { it.text == "Chorus" })
        assertEquals(listOf(10_000L, 70_000L, 130_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `lines come back in time order however they were written`() {
        val lyrics = LrcParser.parse(
            """
            [00:30.00]third
            [00:10.00]first
            [00:20.00]second
            """.trimIndent()
        )!!
        assertEquals(listOf("first", "second", "third"), lyrics.lines.map { it.text })
    }

    @Test
    fun `offset shifts the words, and in the direction the tag means`() {
        // The tag describes the file's error, not the correction: a positive offset means the
        // words are late in the file and must be pulled earlier.
        val late = LrcParser.parse("[offset:+500]\n[00:10.00]a")!!
        assertEquals(9_500L, late.lines[0].timeMs)

        val early = LrcParser.parse("[offset:-500]\n[00:10.00]a")!!
        assertEquals(10_500L, early.lines[0].timeMs)
    }

    @Test
    fun `an offset can never push a line before the start of the song`() {
        val lyrics = LrcParser.parse("[offset:+99000]\n[00:01.00]a")!!
        assertEquals(0L, lyrics.lines[0].timeMs)
    }

    @Test
    fun `plain text with no stamps is still worth showing`() {
        // Some files in the wild are lyrics with the wrong extension. Showing them beats
        // pretending the song has no words.
        val lyrics = LrcParser.parse("First line\nSecond line")!!
        assertTrue(!lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(LyricLine.UNTIMED, lyrics.lines[0].timeMs)
    }

    @Test
    fun `nothing usable gives nothing`() {
        assertNull(LrcParser.parse(""))
        assertNull(LrcParser.parse("   \n\n  \n"))
        assertNull(LrcParser.parse("[ti:Only metadata]\n[ar:Nobody]"))
    }

    @Test
    fun `an empty lyric line keeps its timing`() {
        // Instrumental gaps are stamped with no words, and dropping them makes the previous
        // line hang on screen through the solo.
        val lyrics = LrcParser.parse("[00:10.00]words\n[00:20.00]")!!
        assertEquals(2, lyrics.lines.size)
        assertEquals("", lyrics.lines[1].text)
        assertEquals(20_000L, lyrics.lines[1].timeMs)
    }

    @Test
    fun `finds the line being sung`() {
        val lyrics = LrcParser.parse(
            """
            [00:10.00]one
            [00:20.00]two
            [00:30.00]three
            """.trimIndent()
        )!!
        assertEquals(-1, lyrics.lineAt(0))
        assertEquals(-1, lyrics.lineAt(9_999))
        assertEquals(0, lyrics.lineAt(10_000))
        assertEquals(0, lyrics.lineAt(19_999))
        assertEquals(1, lyrics.lineAt(20_000))
        assertEquals(2, lyrics.lineAt(999_999))
    }

    @Test
    fun `untimed lyrics never claim a current line`() {
        val lyrics = LrcParser.parse("just words")!!
        assertEquals(-1, lyrics.lineAt(0))
        assertEquals(-1, lyrics.lineAt(60_000))
    }

    @Test
    fun `long songs and odd stamps do not break it`() {
        val lyrics = LrcParser.parse("[100:59.99]late\n[0:5]early")!!
        assertEquals(5_000L, lyrics.lines[0].timeMs)
        assertEquals(6_059_990L, lyrics.lines[1].timeMs)
    }
}
