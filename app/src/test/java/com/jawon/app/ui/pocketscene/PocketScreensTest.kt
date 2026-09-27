package com.jawon.app.ui.pocketscene

import com.jawon.app.data.local.Track
import com.jawon.app.ui.wallscene.DeckState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the device's screen shows. Each rule here is one a person would notice as the device
 * being wrong for a moment: a song chosen and "Nothing playing" answering, an empty floor where
 * the covers should be.
 */
class PocketScreensTest {

    private val library = PocketLibrary(
        albums = listOf(
            PocketLibrary.Album("a1", "Abbey Road", "The Beatles", null),
            PocketLibrary.Album("a2", "Blue", "Joni Mitchell", null)
        )
    )
    private val titles = mapOf("a1" to listOf("Come Together", "Something", "Maxwell's Silver Hammer"))

    private fun track(albumId: String, n: Int, title: String) = Track(
        id = "$albumId-$n",
        albumId = albumId,
        trackNumber = n,
        title = title,
        durationMs = 180_000L,
        fileUri = "content://test/$albumId/$n",
        mimeType = "audio/flac",
        sampleRateHz = 44_100,
        bitDepth = 16,
        bitrateBps = 900_000
    )

    private val abbeyRoad = listOf(
        track("a1", 1, "Come Together"),
        track("a1", 2, "Something"),
        track("a1", 3, "Maxwell's Silver Hammer")
    )

    private val nowPlaying = PocketNav().open(MenuKey.NowPlaying)

    private fun screen(
        nav: PocketNav,
        pending: PendingPlay? = null,
        deck: DeckState = DeckState(),
        albumsInOrder: List<PocketLibrary.Album> = library.albums,
        albumTitle: String = "",
        albumArtist: String = ""
    ): LcdScreen = lcdScreenFor(
        nav = nav,
        pending = pending,
        library = library,
        settings = PocketSettings(),
        slides = null,
        albumsInOrder = albumsInOrder,
        coverArt = emptyMap(),
        trackTitles = titles,
        hasNowPlaying = deck.tracks.isNotEmpty(),
        deck = deck,
        positionMs = 0L,
        albumTitle = albumTitle,
        albumArtist = albumArtist,
        cover = null,
        bar = LcdScreen.Bar.PROGRESS,
        volume = 0.5f,
        scrubMs = 0L,
        lyrics = null
    )

    // ---- Now Playing, before and after the player answers ----------------------------------

    @Test
    fun `a song just chosen is named on Now Playing before the player has answered`() {
        val shown = screen(
            nowPlaying,
            pending = PendingPlay("a1", 2, at = 0L),
            albumTitle = "Abbey Road",
            albumArtist = "The Beatles"
        ) as LcdScreen.NowPlaying
        assertEquals("Maxwell's Silver Hammer", shown.trackTitle)
        assertEquals(3, shown.trackNumber)
        assertEquals(3, shown.trackCount)
        assertEquals("Abbey Road", shown.album)
        assertEquals(0L, shown.positionMs)
    }

    @Test
    fun `while the player still names the song before, the one chosen is what shows`() {
        val playingBlue = DeckState(tracks = listOf(track("a2", 1, "All I Want")), currentTrackIndex = 0)
        val shown = screen(nowPlaying, pending = PendingPlay("a1", 0, at = 0L), deck = playingBlue)
            as LcdScreen.NowPlaying
        assertEquals("Come Together", shown.trackTitle)
    }

    @Test
    fun `an album resuming has no number until the player says which song it is`() {
        val shown = screen(nowPlaying, pending = PendingPlay("a1", null, at = 0L)) as LcdScreen.NowPlaying
        assertEquals("", shown.trackTitle)
        assertEquals(0, shown.trackNumber)
    }

    @Test
    fun `once the player answers, Now Playing is the player's`() {
        val deck = DeckState(tracks = abbeyRoad, currentTrackIndex = 1)
        val shown = screen(nowPlaying, deck = deck) as LcdScreen.NowPlaying
        assertEquals("Something", shown.trackTitle)
        assertEquals(2, shown.trackNumber)
        assertEquals(3, shown.trackCount)
    }

    @Test
    fun `with nothing playing and nothing asked for, Now Playing says so`() {
        val shown = screen(nowPlaying) as LcdScreen.Menu
        assertEquals("Nothing playing", shown.emptyText)
        assertTrue(shown.rows.isEmpty())
    }

    // ---- Cover Flow --------------------------------------------------------------------------

    @Test
    fun `Cover Flow with no albums says so rather than drawing an empty floor`() {
        val shown = screen(PocketNav().open(MenuKey.CoverFlow), albumsInOrder = emptyList()) as LcdScreen.Menu
        assertEquals("No albums yet", shown.emptyText)
    }

    @Test
    fun `Cover Flow draws the covers either side of the one chosen, and no further`() {
        val many = (0 until 12).map { PocketLibrary.Album("x$it", "Album $it", "Artist", null) }
        val flow = PocketNav().open(MenuKey.CoverFlow).move(6, rowCount = many.size)
        val shown = screen(flow, albumsInOrder = many) as LcdScreen.CoverFlow
        assertEquals((6 - COVER_WINDOW..6 + COVER_WINDOW).toList(), shown.covers.map { it.index })
        assertEquals("Album 6", shown.albumTitle)
        assertNull(shown.tracks)
    }

    @Test
    fun `a cover turned over lists its album's songs on the back`() {
        val flipped = PocketNav().open(MenuKey.CoverFlow).open(MenuKey.Flipped("a1"))
        val shown = screen(flipped) as LcdScreen.CoverFlow
        assertEquals(titles.getValue("a1"), shown.tracks?.rows?.map { it.label })
    }
}
