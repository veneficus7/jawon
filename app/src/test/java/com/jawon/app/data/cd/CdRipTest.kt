package com.jawon.app.data.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Everything about a rip that can be decided without a drive.
 *
 * There is still no CD reader to test `CdDrive` against, so this file is where the rip is
 * actually checked — the chunking that decides whether the last track runs into the lead-out,
 * the naming that is the *only* metadata a WAV will carry, and the disc lookup that has to pick
 * one medium out of a box set.
 *
 * Robolectric because the disc lookup parses with `org.json`, which the plain JVM test runtime
 * stubs out — every call throws, `runCatching` swallows it, and each of those tests would pass or
 * fail for reasons having nothing to do with the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CdRipTest {

    private fun toc(vararg tracks: CdTrack) = CdToc(tracks.toList(), leadOutLba = 200_000)

    private fun audio(number: Int, start: Int, sectors: Int) =
        CdTrack(number = number, startLba = start, sectors = sectors, isAudio = true)

    private fun data(number: Int, start: Int, sectors: Int) =
        CdTrack(number = number, startLba = start, sectors = sectors, isAudio = false)

    // ---- Chunking ------------------------------------------------------------------------

    @Test
    fun `a track that divides evenly is read in whole chunks`() {
        val chunks = CdRip.chunks(startLba = 0, sectors = 52, per = 26)

        assertEquals(2, chunks.size)
        assertEquals(listOf(0, 26), chunks.map { it[0] })
        assertTrue(chunks.all { it[1] == 26 })
    }

    @Test
    fun `the last chunk is short rather than overrunning the track`() {
        // **The bug this function exists to prevent.** Reading a fixed chunk every time overruns
        // the end of the last track into the lead-out, which on many drives is not an error —
        // just wrong audio at the end of every album.
        val chunks = CdRip.chunks(startLba = 100, sectors = 60, per = 26)

        assertEquals(3, chunks.size)
        assertEquals(8, chunks.last()[1])
        // And the ranges exactly cover the track: no gap, no overlap, no overrun.
        assertEquals(60, chunks.sumOf { it[1] })
        assertEquals(100, chunks.first()[0])
        assertEquals(152, chunks.last()[0])
    }

    @Test
    fun `a track shorter than one chunk is one short read`() {
        val chunks = CdRip.chunks(startLba = 5, sectors = 3, per = 26)

        assertEquals(1, chunks.size)
        assertEquals(listOf(5, 3), chunks.single().toList())
    }

    @Test
    fun `nonsense lengths ask the drive for nothing at all`() {
        assertTrue(CdRip.chunks(0, 0).isEmpty())
        assertTrue(CdRip.chunks(0, -5).isEmpty())
        assertTrue(CdRip.chunks(0, 100, per = 0).isEmpty())
    }

    // ---- The plan ------------------------------------------------------------------------

    @Test
    fun `a data track is never ripped`() {
        // A mixed-mode disc's data track read as audio produces a file of noise — worse than
        // failing, because it imports successfully and only shows itself on playback.
        val plan = CdRip.plan(toc(audio(1, 0, 100), data(2, 100, 50), audio(3, 150, 80)))

        assertEquals(listOf(1, 3), plan.map { it.number })
    }

    @Test
    fun `titles are matched by position, not by track number`() {
        // **The case that makes this non-obvious.** On a disc whose track 1 is data, the first
        // *audio* track is number 2 — and the release's tracklist still starts at its first
        // title. Matching on the number would shift every name by one.
        val plan = CdRip.plan(
            toc(data(1, 0, 50), audio(2, 50, 100), audio(3, 150, 100)),
            titles = listOf("Opening", "Second")
        )

        assertEquals(listOf("Opening", "Second"), plan.map { it.title })
        assertEquals("02 Opening.wav", plan.first().fileName)
    }

    @Test
    fun `no titles still produces a sortable plan`() {
        val plan = CdRip.plan(toc(audio(1, 0, 10), audio(2, 10, 10)))

        assertEquals(listOf("01.wav", "02.wav"), plan.map { it.fileName })
        assertTrue(plan.all { it.title == null })
    }

    @Test
    fun `more titles than tracks does not invent a track`() {
        val plan = CdRip.plan(toc(audio(1, 0, 10)), titles = listOf("A", "B", "C"))

        assertEquals(1, plan.size)
    }

    @Test
    fun `fewer titles than tracks leaves the rest numbered`() {
        val plan = CdRip.plan(toc(audio(1, 0, 10), audio(2, 10, 10)), titles = listOf("A"))

        assertEquals(listOf("01 A.wav", "02.wav"), plan.map { it.fileName })
    }

    // ---- Naming --------------------------------------------------------------------------

    @Test
    fun `the number is zero-padded so a folder listing sorts correctly`() {
        // Without this the importer, which sorts by name, puts track 10 before track 2.
        assertEquals("01.wav", CdRip.fileNameFor(1, null))
        assertEquals("09 Nine.wav", CdRip.fileNameFor(9, "Nine"))
        assertEquals("10 Ten.wav", CdRip.fileNameFor(10, "Ten"))

        val names = listOf(2, 10).map { CdRip.fileNameFor(it, null) }
        assertEquals(names, names.sorted())
    }

    @Test
    fun `a title a filesystem would reject is sanitised rather than losing the track`() {
        // Slashes and colons are ordinary in classical and in remix credits, and illegal in a
        // file name on most storage this will be written to.
        // The colon and the slash go; the full stops stay, because they are in the keep-list on
        // purpose — "m.A.A.d city" and "Mr." are titles, not punctuation to be scrubbed.
        assertEquals("Symphony No. 5 in C Cm", CdRip.sanitise("Symphony No. 5: in C/Cm"))
        assertEquals("m.A.A.d city", CdRip.sanitise("m.A.A.d city"))
        assertTrue(CdRip.fileNameFor(3, "A/B\\C*D?E").none { it in "/\\*?" })
    }

    @Test
    fun `punctuation worth keeping is kept`() {
        assertEquals("Don't Stop (Remix) - Live", CdRip.sanitise("Don't Stop (Remix) - Live"))
    }

    @Test
    fun `a title that sanitises to nothing falls back to the bare number`() {
        assertEquals("04.wav", CdRip.fileNameFor(4, "///"))
        assertEquals("04.wav", CdRip.fileNameFor(4, "   "))
    }

    @Test
    fun `an absurdly long title is truncated rather than rejected by the filesystem`() {
        val name = CdRip.fileNameFor(1, "x".repeat(400))

        assertTrue("${name.length}", name.length < 120)
    }

    // ---- Progress ------------------------------------------------------------------------

    @Test
    fun `progress is weighted by length, not by track count`() {
        // A disc of one twenty-minute side and three short tracks is not 25% done after the
        // first, and a bar that said so would sit still for a fifth of the rip.
        val all = CdRip.plan(toc(audio(1, 0, 900), audio(2, 900, 100)))

        assertEquals(0.9f, CdRip.progress(listOf(all[0]), all[1], 0, all), 0.001f)
    }

    @Test
    fun `progress runs from zero to one and never past it`() {
        val all = CdRip.plan(toc(audio(1, 0, 100), audio(2, 100, 100)))

        assertEquals(0f, CdRip.progress(emptyList(), all[0], 0, all), 0.001f)
        assertEquals(1f, CdRip.progress(all, null, 0, all), 0.001f)
        // A miscounted sector total must not push the bar past the end.
        assertEquals(1f, CdRip.progress(listOf(all[0]), all[1], 99_999, all), 0.001f)
    }

    @Test
    fun `an empty disc is zero rather than a division by zero`() {
        assertEquals(0f, CdRip.progress(emptyList(), null, 0, emptyList()), 0f)
    }

    // ---- The MusicBrainz lookup ------------------------------------------------------------

    private val discId = "diNWqexTwQawRtHN.qRi7vOg4_I-"

    @Test
    fun `a matched disc yields its titles in order`() {
        val body = """
            {"releases":[{
              "title":"Kind of Blue",
              "artist-credit":[{"name":"Miles Davis","joinphrase":""}],
              "media":[{
                "discs":[{"id":"$discId"}],
                "tracks":[{"title":"So What"},{"title":"Freddie Freeloader"}]
              }]
            }]}
        """.trimIndent()

        val release = CdRip.parseDiscLookup(body, discId)

        assertEquals("Kind of Blue", release?.title)
        assertEquals("Miles Davis", release?.artist)
        assertEquals(listOf("So What", "Freddie Freeloader"), release?.trackTitles)
    }

    @Test
    fun `only the medium carrying this disc id is used`() {
        // **The box-set trap.** A release lists every disc in it, and taking the first medium
        // would name a rip of disc two with the tracklist of disc one — an error that looks
        // completely plausible until you play it.
        val body = """
            {"releases":[{
              "title":"The Box",
              "artist-credit":[{"name":"Someone","joinphrase":""}],
              "media":[
                {"discs":[{"id":"other-disc-entirely"}],
                 "tracks":[{"title":"Wrong One"},{"title":"Also Wrong"}]},
                {"discs":[{"id":"$discId"}],
                 "tracks":[{"title":"Right One"},{"title":"Also Right"}]}
              ]
            }]}
        """.trimIndent()

        val release = CdRip.parseDiscLookup(body, discId)

        assertEquals(listOf("Right One", "Also Right"), release?.trackTitles)
    }

    @Test
    fun `a joined credit reads the way the release credits it`() {
        // joinphrase is the difference between "Simon & Garfunkel" and "Simon, Garfunkel".
        val body = """
            {"releases":[{
              "title":"Bookends",
              "artist-credit":[
                {"name":"Simon","joinphrase":" & "},
                {"name":"Garfunkel","joinphrase":""}
              ],
              "media":[{"discs":[{"id":"$discId"}],"tracks":[{"title":"America"}]}]
            }]}
        """.trimIndent()

        assertEquals("Simon & Garfunkel", CdRip.parseDiscLookup(body, discId)?.artist)
    }

    @Test
    fun `an unrecognised disc is null rather than a guess`() {
        // The normal case for anything home-made or obscure. The rip works fine without titles,
        // so guessing would be strictly worse than admitting it.
        val body = """{"releases":[]}"""

        assertNull(CdRip.parseDiscLookup(body, discId))
    }

    @Test
    fun `a release with no tracklist is skipped rather than returned empty`() {
        val body = """
            {"releases":[
              {"title":"No Tracks","media":[{"discs":[{"id":"$discId"}],"tracks":[]}]},
              {"title":"Good","artist-credit":[{"name":"A","joinphrase":""}],
               "media":[{"discs":[{"id":"$discId"}],"tracks":[{"title":"Real"}]}]}
            ]}
        """.trimIndent()

        assertEquals("Good", CdRip.parseDiscLookup(body, discId)?.title)
    }

    @Test
    fun `nonsense from the network never throws`() {
        // Same discipline as every other client in this app: a captive portal substitutes an
        // HTML login page for the JSON and it must read as "not found", not as a crash.
        assertNull(CdRip.parseDiscLookup("", discId))
        assertNull(CdRip.parseDiscLookup("not json", discId))
        assertNull(CdRip.parseDiscLookup("<html>Login</html>", discId))
        assertNull(CdRip.parseDiscLookup("{}", discId))
        assertNull(CdRip.parseDiscLookup("""{"releases":[{"media":null}]}""", discId))
    }

    @Test
    fun `the lookup url carries the disc id and asks for the tracklist`() {
        val url = CdRip.lookupUrl(discId)

        assertTrue(url, url.contains(discId))
        assertTrue(url, url.contains("recordings"))
        assertTrue(url, url.contains("fmt=json"))
    }

    // ---- Size ----------------------------------------------------------------------------

    @Test
    fun `the size quoted before a rip is the size of the files it will write`() {
        // Worth stating up front, because a CD is around 600MB as WAV and that is a surprise on
        // a phone. This has to be the real number, headers included, not audio bytes alone.
        val plan = CdRip.plan(toc(audio(1, 0, 1000)))

        assertEquals(
            WavWriter.fileBytesFor(1000),
            CdRip.totalBytes(plan)
        )
        assertTrue(CdRip.totalBytes(plan) > 1000L * 2352)
    }
}
