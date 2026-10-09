package com.jawon.app.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric for `org.json`, which the bare JVM runtime stubs.
 *
 * **The fixtures here are real.** They were taken from live `archive.org` responses rather than
 * written from the documentation, which matters more than usual for this API: the Archive's
 * metadata is user-supplied across three decades, and the shapes it actually returns are
 * messier than the shapes it describes. Every awkward case below was observed, not imagined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveClientTest {

    // ---- search ----------------------------------------------------------------------------

    /**
     * Checked against the live index on 2026-10-09: `collection:(etree) AND mediatype:(audio)`
     * found 0 items, `mediatype:(etree)` 295,627. One shared `audio` filter emptied the live tab.
     */
    @Test
    fun `a show's title loses the band's name, which is shown beside it`() {
        assertEquals(
            "Live at Red Rocks Amphitheater, Morrison, CO on 2026-06-28",
            ArchiveClient.withoutCreator(
                "Widespread Panic Live at Red Rocks Amphitheater, Morrison, CO on 2026-06-28",
                "Widespread Panic"
            )
        )
        assertEquals("Intergalactic EP", ArchiveClient.withoutCreator("Marino69 - Intergalactic EP", "Marino69"))
        // Read through to the parsed result, from the live fixture.
        assertEquals(
            "Live at Boondocks 2025-11-01",
            ArchiveClient.withoutCreator("Sunshine Daydream Live at Boondocks 2025-11-01", "Sunshine Daydream")
        )
    }

    @Test
    fun `a title is left alone unless the name ends at a break and leaves a real title`() {
        assertEquals("Phishbowl Sessions", ArchiveClient.withoutCreator("Phishbowl Sessions", "Phish"))
        assertEquals("Phish", ArchiveClient.withoutCreator("Phish", "Phish"))
        assertEquals("Phish - Live", ArchiveClient.withoutCreator("Phish - Live", "Phish"))
        assertEquals("Another Band Live", ArchiveClient.withoutCreator("Another Band Live", "Phish"))
        assertEquals("Unknown Live at X", ArchiveClient.withoutCreator("Unknown Live at X", "Unknown"))
    }

    @Test
    fun `each collection asks for the mediatype its items actually have`() {
        assertTrue(ArchiveCollection.LIVE_MUSIC.query.contains("mediatype:(etree)"))
        assertTrue(ArchiveCollection.NETLABELS.query.contains("mediatype:(audio)"))
    }

    @Test
    fun `a search result is read`() {
        val results = ArchiveClient.parseSearch(
            """
            {"responseHeader":{"status":0},"response":{"numFound":2,"start":0,"docs":[
              {"creator":"Sunshine Daydream","date":"2025-11-01T00:00:00Z",
               "identifier":"ssdd2025-11-01.zoom-recorder",
               "title":"Sunshine Daydream Live at Boondocks 2025-11-01","year":2025}
            ]}}
            """.trimIndent()
        )

        val result = results.single()
        assertEquals("ssdd2025-11-01.zoom-recorder", result.identifier)
        assertEquals("Sunshine Daydream", result.creator)
        assertEquals(2025, result.year)
    }

    @Test
    fun `a field that is an array is read as well as one that is a string`() {
        // **The single most common way a naive parser breaks on this API.** The Archive's index
        // is multi-valued: an item with two credited performers returns `creator` as an array,
        // one with a single performer returns a bare string, and both appear in the same result
        // set. Reading either shape alone fails on roughly half the catalogue.
        val results = ArchiveClient.parseSearch(
            """
            {"response":{"docs":[
              {"identifier":"a","title":"One","creator":"Solo Artist"},
              {"identifier":"b","title":["Two"],"creator":["First Band","Support Act"]}
            ]}}
            """.trimIndent()
        )

        assertEquals("Solo Artist", results[0].creator)
        assertEquals("First Band", results[1].creator)
        assertEquals("Two", results[1].title)
    }

    @Test
    fun `an item with no title falls back to its identifier rather than being dropped`() {
        val result = ArchiveClient.parseSearch(
            """{"response":{"docs":[{"identifier":"some-show-1999"}]}}"""
        ).single()
        assertEquals("some-show-1999", result.title)
        assertEquals("Unknown", result.creator)
        assertNull(result.year)
    }

    @Test
    fun `an empty search is empty rather than an error`() {
        assertTrue(ArchiveClient.parseSearch("""{"response":{"docs":[]}}""").isEmpty())
        assertTrue(ArchiveClient.parseSearch("""{"responseHeader":{"status":0}}""").isEmpty())
    }

    // ---- one item --------------------------------------------------------------------------

    /** Trimmed from the live response for `ssdd2025-11-01.zoom-recorder`. */
    private val liveShow = """
        {"metadata":{"identifier":"ssdd2025-11-01.zoom-recorder",
          "creator":"Sunshine Daydream","date":"2025-11-01",
          "title":"Sunshine Daydream Live at Boondocks 2025-11-01"},
         "files":[
           {"name":"01 Let It Rock.mp3","format":"VBR MP3","length":"05:06","bitrate":"140","size":"5348557"},
           {"name":"02 Bertha.mp3","format":"VBR MP3","length":"07:12","bitrate":"142","size":"7000000"},
           {"name":"01 Let It Rock.flac","format":"Flac","length":"05:06","size":"40000000"},
           {"name":"01 Let It Rock.png","format":"Spectrogram","size":"100"},
           {"name":"ssdd_meta.xml","format":"Metadata","size":"900"}
         ]}
    """.trimIndent()

    @Test
    fun `an item becomes an album with one format, not three copies of every song`() {
        // The trap this guards. An Archive item carries the same recording several times over —
        // MP3, FLAC, WAVE — plus spectrograms and waveform images. Taking every audio file
        // would put each song on the tracklist repeatedly.
        val album = ArchiveClient.parseAlbum("ssdd2025-11-01.zoom-recorder", liveShow)!!

        assertEquals(2, album.tracks.size)
        assertEquals("Sunshine Daydream", album.creator)
        assertEquals(2025, album.year)
        assertTrue(album.tracks.all { it.mimeType == "audio/mpeg" })
    }

    @Test
    fun `track titles come from the filename when the item has no tags`() {
        // Live Music Archive items very often have no per-file title or track tag at all — only
        // names like "01 Let It Rock.mp3". Falling back to the raw filename would print the
        // extension and the number on the deck.
        val album = ArchiveClient.parseAlbum("x", liveShow)!!

        assertEquals("Let It Rock", album.tracks[0].title)
        assertEquals(1, album.tracks[0].trackNumber)
        assertEquals("Bertha", album.tracks[1].title)
        assertEquals(2, album.tracks[1].trackNumber)
    }

    @Test
    fun `a tagged item prefers its tags over the filename`() {
        val album = ArchiveClient.parseAlbum(
            "y",
            """
            {"metadata":{"title":"Tagged","creator":"Someone"},
             "files":[{"name":"trk01.mp3","format":"VBR MP3","title":"The Real Title",
                       "track":"3/12","length":"200.5","bitrate":"128"}]}
            """.trimIndent()
        )!!

        val track = album.tracks.single()
        assertEquals("The Real Title", track.title)
        assertEquals(3, track.trackNumber)
    }

    @Test
    fun `an item holding only a ZIP is not an album`() {
        // **Observed, not hypothetical.** A large share of netlabel items ship the whole release
        // as one ZIP with no streamable track in it. Returning null is what keeps an empty case
        // off the wall — the search index cannot tell you this, only the metadata can.
        assertNull(
            ArchiveClient.parseAlbum(
                "zipped",
                """
                {"metadata":{"title":"A Release","creator":"A Label"},
                 "files":[{"name":"release.zip","format":"ZIP","size":"90000000"},
                          {"name":"cover.jpg","format":"JPEG Thumb","size":"2000"}]}
                """.trimIndent()
            )
        )
    }

    @Test
    fun `flac is used when there is no mp3`() {
        val album = ArchiveClient.parseAlbum(
            "lossless-only",
            """
            {"metadata":{"title":"T","creator":"C"},
             "files":[{"name":"01 Song.flac","format":"Flac","length":"03:00"}]}
            """.trimIndent()
        )!!
        assertEquals("audio/flac", album.tracks.single().mimeType)
    }

    // ---- the awkward little parsers ---------------------------------------------------------

    @Test
    fun `length is read in all three notations the Archive uses`() {
        // All three appear, sometimes within one collection. Reading only one gives durations
        // that are absent or wrong by a factor of sixty, and a tracklist that looks broken for
        // reasons that point nowhere near the parser.
        assertEquals(306_000L, ArchiveClient.parseLength("05:06"))
        assertEquals(3_723_000L, ArchiveClient.parseLength("1:02:03"))
        assertEquals(306_500L, ArchiveClient.parseLength("306.5"))
        assertEquals(0L, ArchiveClient.parseLength(null))
        assertEquals(0L, ArchiveClient.parseLength(""))
        assertEquals(0L, ArchiveClient.parseLength("not a length"))
    }

    @Test
    fun `a track number survives every form the tag takes`() {
        assertEquals(7, ArchiveClient.trackNumberOf("7"))
        assertEquals(7, ArchiveClient.trackNumberOf("07"))
        // The ID3 form. `toIntOrNull` alone returns null here, which would number every track
        // zero and leave the tracklist in whatever order the file list happened to be in.
        assertEquals(7, ArchiveClient.trackNumberOf("7/12"))
        assertEquals(12, ArchiveClient.trackNumberOf("12 Some Song.mp3"))
        assertNull(ArchiveClient.trackNumberOf("no digits here"))
    }

    @Test
    fun `a filename becomes a readable title`() {
        assertEquals("Let It Rock", ArchiveClient.titleFromFileName("01 Let It Rock.mp3"))
        assertEquals("Bertha", ArchiveClient.titleFromFileName("02-Bertha.flac"))
        assertEquals("Dark Star", ArchiveClient.titleFromFileName("03_Dark_Star.ogg"))
        assertEquals("Untitled", ArchiveClient.titleFromFileName("Untitled.mp3"))
    }

    @Test
    fun `a stream URL escapes the spaces that Archive filenames are full of`() {
        // Almost every Live Music Archive filename contains spaces. An unescaped URL is a
        // malformed request, and the player reports it as a source error rather than as a URL
        // that was never built properly.
        val url = ArchiveClient.streamUrl("gd1977-05-08", "01 Let It Rock.mp3")
        assertEquals("https://archive.org/download/gd1977-05-08/01%20Let%20It%20Rock.mp3", url)
        assertTrue("a raw + would be decoded as a space", !url.contains("+"))
    }
}
