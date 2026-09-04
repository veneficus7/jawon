package com.jawon.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cue sheet parser.
 *
 * These files are written by a dozen different rippers and hand-edited by people, so most of
 * what follows is about surviving real-world sheets rather than the happy path. The happy path
 * is four lines and was never the risk.
 */
class CueParserTest {

    private val wholeDisc = """
        REM GENRE Rock
        REM DATE 1973
        PERFORMER "Pink Floyd"
        TITLE "The Dark Side of the Moon"
        FILE "Dark Side of the Moon.flac" WAVE
          TRACK 01 AUDIO
            TITLE "Speak to Me"
            PERFORMER "Pink Floyd"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Breathe"
            INDEX 00 01:05:20
            INDEX 01 01:07:35
          TRACK 03 AUDIO
            TITLE "On the Run"
            INDEX 01 03:56:12
    """.trimIndent()

    @Test
    fun `a whole-disc sheet yields the album and every track in order`() {
        val cue = CueParser.parse(wholeDisc)!!

        assertEquals("Pink Floyd", cue.performer)
        assertEquals("The Dark Side of the Moon", cue.title)
        assertEquals(1, cue.files.size)
        assertEquals("Dark Side of the Moon.flac", cue.files[0].name)

        val tracks = cue.tracks
        assertEquals(3, tracks.size)
        assertEquals(listOf(1, 2, 3), tracks.map { it.number })
        assertEquals(listOf("Speak to Me", "Breathe", "On the Run"), tracks.map { it.title })
    }

    @Test
    fun `the third field is frames, not hundredths`() {
        // The trap that would otherwise put every boundary on a long album progressively out of
        // place. 00:02:37 is two seconds and thirty-seven CD frames at 75/s — 2493ms — and
        // reading it as hundredths gives 2370.
        assertEquals(2493L, CueParser.parseTime("00:02:37"))
        assertEquals(0L, CueParser.parseTime("00:00:00"))
        assertEquals(1000L, CueParser.parseTime("00:01:00"))
        // One frame is 13.33ms.
        assertEquals(13L, CueParser.parseTime("00:00:01"))
    }

    @Test
    fun `minutes are not capped at 59, because a cue counts from the start of the disc`() {
        // A seventy-minute record's last track legitimately begins past the hour mark, written
        // as minutes rather than rolling over into an hours field that does not exist.
        assertEquals((68L * 60 + 14) * 1000 + 22 * 1000 / 75, CueParser.parseTime("68:14:22"))
    }

    @Test
    fun `a malformed time is rejected rather than guessed at`() {
        assertNull(CueParser.parseTime("00:02"))
        assertNull(CueParser.parseTime("aa:bb:cc"))
        assertNull(CueParser.parseTime(""))
        assertNull(CueParser.parseTime("-1:00:00"))
    }

    @Test
    fun `the pregap is kept separately from the track start`() {
        val breathe = CueParser.parse(wholeDisc)!!.tracks[1]

        // INDEX 01 — where a listener would say the track starts, and what a tracklist shows.
        assertEquals((60L + 7) * 1000 + 35 * 1000 / 75, breathe.startMs)
        // INDEX 00 — the run-out of the previous track. Losing this distinction loses real
        // audio: the applause between two live songs, or a hidden track in front of track 1.
        assertEquals((60L + 5) * 1000 + 20 * 1000 / 75, breathe.pregapStartMs)
    }

    @Test
    fun `a track with no pregap says so rather than inventing one`() {
        val speakToMe = CueParser.parse(wholeDisc)!!.tracks[0]
        assertEquals(0L, speakToMe.startMs)
        assertNull(speakToMe.pregapStartMs)
    }

    @Test
    fun `track titles do not leak into the album title`() {
        // The single most likely parsing bug in this format: TITLE means the album before the
        // first TRACK and the track after it, and the same word does both jobs.
        val cue = CueParser.parse(wholeDisc)!!
        assertEquals("The Dark Side of the Moon", cue.title)
        assertEquals("Speak to Me", cue.tracks[0].title)
    }

    @Test
    fun `a track performer overrides the album's, for a compilation`() {
        val cue = CueParser.parse(wholeDisc)!!
        assertEquals("Pink Floyd", cue.tracks[0].performer)
        // Not stated on track 2, which is the common case — the importer falls back to the
        // album, and null is what tells it to.
        assertNull(cue.tracks[1].performer)
    }

    @Test
    fun `REM lines cannot be mistaken for commands`() {
        val cue = CueParser.parse(
            """
            REM TITLE "Not The Album Title"
            REM PERFORMER "Not The Artist"
            TITLE "Real Title"
            FILE "a.flac" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            """.trimIndent()
        )!!
        assertEquals("Real Title", cue.title)
        assertNull(cue.performer)
    }

    @Test
    fun `a sheet split across several files still parses`() {
        // Legal, and what you get when a rip was split per track but kept its cue. No clipping
        // is needed for these, but refusing to read them would be worse than useless.
        val cue = CueParser.parse(
            """
            TITLE "Split Rip"
            FILE "01 - One.flac" WAVE
              TRACK 01 AUDIO
                TITLE "One"
                INDEX 01 00:00:00
            FILE "02 - Two.flac" WAVE
              TRACK 02 AUDIO
                TITLE "Two"
                INDEX 01 00:00:00
            """.trimIndent()
        )!!
        assertEquals(2, cue.files.size)
        assertEquals("01 - One.flac", cue.files[0].name)
        assertEquals("02 - Two.flac", cue.files[1].name)
        assertEquals(2, cue.tracks.size)
    }

    @Test
    fun `a byte order mark does not swallow the first command`() {
        // UTF-8 with a BOM is common from Windows rippers, and the BOM lands on the very first
        // line — usually PERFORMER or FILE, so getting this wrong loses the album, not a detail.
        val cue = CueParser.parse("﻿TITLE \"With BOM\"\nFILE \"a.flac\" WAVE\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00")!!
        assertEquals("With BOM", cue.title)
    }

    @Test
    fun `an unquoted filename with spaces survives`() {
        val cue = CueParser.parse(
            """
            FILE My Whole Album.flac WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            """.trimIndent()
        )!!
        assertEquals("My Whole Album.flac", cue.files[0].name)
    }

    @Test
    fun `lowercase and ragged indentation are accepted`() {
        // Hand-edited sheets are not consistently cased and rippers disagree about indentation.
        val cue = CueParser.parse(
            """
            title "Lowercase"
            file "a.flac" wave
            track 01 audio
            index 01 00:00:00
            """.trimIndent()
        )!!
        assertEquals("Lowercase", cue.title)
        assertEquals(1, cue.tracks.size)
    }

    @Test
    fun `a track with no index at all is kept rather than dropping the rest of the album`() {
        val cue = CueParser.parse(
            """
            FILE "a.flac" WAVE
              TRACK 01 AUDIO
                TITLE "No Index"
              TRACK 02 AUDIO
                TITLE "Fine"
                INDEX 01 00:30:00
            """.trimIndent()
        )!!
        assertEquals(2, cue.tracks.size)
        assertEquals(0L, cue.tracks[0].startMs)
        assertEquals(30_000L, cue.tracks[1].startMs)
    }

    @Test
    fun `text that is not a cue sheet is rejected`() {
        assertNull(CueParser.parse(""))
        assertNull(CueParser.parse("just some words\nand more words"))
        // A FILE with no tracks is not an album.
        assertNull(CueParser.parse("FILE \"a.flac\" WAVE"))
    }

    @Test
    fun `an unknown command is ignored rather than fatal`() {
        // FLAGS, ISRC, POSTGAP, CATALOG and SONGWRITER all appear in the wild, and more will.
        val cue = CueParser.parse(
            """
            CATALOG 0602517723030
            TITLE "Has Extras"
            FILE "a.flac" WAVE
              TRACK 01 AUDIO
                ISRC GBAYE0000123
                FLAGS DCP
                TITLE "Still Read"
                INDEX 01 00:00:00
                POSTGAP 00:02:00
            """.trimIndent()
        )!!
        assertEquals("Has Extras", cue.title)
        assertEquals("Still Read", cue.tracks[0].title)
    }

    @Test
    fun `track starts come out ascending, which is what a clip range depends on`() {
        val tracks = CueParser.parse(wholeDisc)!!.tracks
        assertTrue(
            "starts must ascend or a track's end would precede its beginning",
            tracks.zipWithNext().all { (a, b) -> a.startMs < b.startMs }
        )
    }
}
