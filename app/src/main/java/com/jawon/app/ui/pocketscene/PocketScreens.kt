package com.jawon.app.ui.pocketscene

import androidx.compose.ui.graphics.ImageBitmap
import com.jawon.app.data.lyrics.LyricsState
import com.jawon.app.ui.wallscene.WallLayout

/** The wall's layout, as shelves-as-playlists and albums. The placeholder board is not a shelf. */
internal fun pocketLibraryOf(layout: WallLayout): PocketLibrary {
    val byRow = layout.cases.groupBy { it.row }
    val shelves = layout.rows
        .filter { it.shelfId != WallLayout.WAITING_SHELF_ID }
        .sortedBy { it.row }
        .map { row ->
            PocketLibrary.Shelf(
                id = row.shelfId,
                name = row.name,
                albumIds = byRow[row.row].orEmpty().sortedBy { it.col }.map { it.albumId }
            )
        }
    val albums = layout.cases.map {
        PocketLibrary.Album(id = it.albumId, title = it.title, artist = it.artist, coverPath = it.coverPath)
    }
    return PocketLibrary(shelves, albums)
}

/**
 * What the screen shows, from where the menus are, what is playing and the library. The device
 * decides where the menus go and the room gathers the rest; this only turns the two into a
 * picture — which is why it can be tested without a phone.
 */
internal fun lcdScreenFor(
    nav: PocketNav,
    pending: PendingPlay?,
    library: PocketLibrary,
    settings: PocketSettings,
    slides: LcdScreen.ArtSlides?,
    albumsInOrder: List<PocketLibrary.Album>,
    coverArt: Map<String, ImageBitmap?>,
    trackTitles: Map<String, List<String>>,
    hasNowPlaying: Boolean,
    deck: com.jawon.app.ui.wallscene.DeckState,
    positionMs: Long,
    albumTitle: String,
    albumArtist: String,
    cover: ImageBitmap?,
    bar: LcdScreen.Bar,
    volume: Float,
    scrubMs: Long,
    lyrics: LcdScreen.LyricsView?,
    /** Search's letter cursor, and whether the wheel is up in its results. */
    searchLetter: Int = 0,
    searchInResults: Boolean = false
): LcdScreen {
    val key = nav.current.key
    if (key == MenuKey.NowPlaying && pending != null) {
        // What was asked for, until the player says it is on: no time yet, and no number when
        // the album resumes from wherever it was left.
        val titles = trackTitles[pending.albumId]
        return LcdScreen.NowPlaying(
            trackTitle = pending.index?.let { titles?.getOrNull(it) }.orEmpty(),
            artist = albumArtist,
            album = albumTitle,
            trackNumber = (pending.index ?: -1) + 1,
            trackCount = titles?.size ?: 0,
            positionMs = 0L,
            durationMs = 0L,
            cover = cover,
            bar = LcdScreen.Bar.PROGRESS,
            volume = volume,
            scrubMs = 0L,
            lyrics = null
        )
    }
    if (key == MenuKey.NowPlaying) {
        val track = deck.currentTrack
        if (track == null || deck.tracks.isEmpty()) {
            return LcdScreen.Menu("Now Playing", emptyList(), -1, 0, 0, emptyText = "Nothing playing")
        }
        return LcdScreen.NowPlaying(
            trackTitle = track.title,
            artist = track.artist?.takeIf { it.isNotBlank() } ?: albumArtist,
            album = albumTitle,
            trackNumber = deck.currentTrackIndex + 1,
            trackCount = deck.tracks.size,
            positionMs = positionMs,
            durationMs = deck.durationMs.takeIf { it > 0 } ?: track.durationMs,
            cover = cover,
            bar = bar,
            volume = volume,
            scrubMs = scrubMs,
            lyrics = lyrics
        )
    }
    if (key == MenuKey.CoverFlow || key is MenuKey.Flipped) {
        if (albumsInOrder.isEmpty()) {
            return LcdScreen.Menu("Cover Flow", emptyList(), -1, 0, 0, emptyText = "No albums yet")
        }
        val flowLevel = if (key is MenuKey.Flipped) nav.stack[nav.stack.size - 2] else nav.current
        val centre = flowLevel.selected.coerceIn(0, albumsInOrder.lastIndex)
        val from = (centre - COVER_WINDOW).coerceAtLeast(0)
        val to = (centre + COVER_WINDOW).coerceAtMost(albumsInOrder.lastIndex)
        val covers = (from..to).map { i ->
            val album = albumsInOrder[i]
            LcdCover(album.id, i, coverArt[album.id])
        }
        val back = if (key is MenuKey.Flipped) {
            val rows = PocketMenus.rows(key, library, trackTitles, hasNowPlaying)
            val level = nav.clamp(rows.size).current
            val end = minOf(level.top + PocketMenus.VISIBLE_ROWS, rows.size)
            LcdScreen.Menu(
                title = albumsInOrder[centre].title,
                rows = (if (level.top < end) rows.subList(level.top, end) else emptyList())
                    .map { LcdRow(it.label, it.leadsOn) },
                selected = level.selected - level.top,
                top = level.top,
                total = rows.size,
                emptyText = if (key.albumId in trackTitles) "No tracks" else ""
            )
        } else {
            null
        }
        return LcdScreen.CoverFlow(covers, albumsInOrder[centre].title, albumsInOrder[centre].artist, back)
    }
    if (key == MenuKey.Brick) return LcdScreen.Brick
    if (key is MenuKey.Search) {
        val rows = PocketSearch.results(key.query, library)
        val selected = nav.current.selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        // Its own window: five rows above the letters, not a whole screen's eight.
        val top = (selected - SEARCH_ROWS + 1).coerceAtLeast(0)
            .coerceAtMost((rows.size - SEARCH_ROWS).coerceAtLeast(0))
        val window = rows.drop(top).take(SEARCH_ROWS)
        return LcdScreen.Search(
            query = key.query,
            letter = searchLetter,
            rows = window.map { LcdRow(it.label, it.leadsOn, it.value) },
            selected = if (searchInResults && rows.isNotEmpty()) selected - top else -1,
            top = top,
            total = rows.size
        )
    }
    if (key is MenuKey.Adjust) {
        val control = key.control
        val position = settings.sound[control] ?: 0f
        val (low, high) = PocketSound.ends(control)
        return LcdScreen.Slider(
            title = control.title,
            position = position,
            bipolar = control.bipolar,
            valueText = PocketSound.label(control, position),
            lowEnd = low,
            highEnd = high
        )
    }
    val rows = PocketMenus.rows(key, library, trackTitles, hasNowPlaying, settings)
    val level = nav.clamp(rows.size).current
    val end = minOf(level.top + PocketMenus.VISIBLE_ROWS, rows.size)
    val window = if (level.top < end) rows.subList(level.top, end) else emptyList()
    return LcdScreen.Menu(
        title = PocketMenus.title(key, library),
        rows = window.map { LcdRow(it.label, it.leadsOn, it.value, it.checked) },
        selected = level.selected - level.top,
        top = level.top,
        total = rows.size,
        art = if (key == MenuKey.Main) slides else null,
        emptyText = when (key) {
            MenuKey.Albums, MenuKey.Artists -> "No albums yet"
            MenuKey.Shelves -> "No shelves yet"
            is MenuKey.Album -> if (key.albumId in trackTitles) "No tracks" else ""
            else -> ""
        }
    )
}

/** Lyric lines on the page at once. */
internal const val LYRIC_ROWS = 7

/**
 * The lyrics page's window onto the words: following the line being sung — kept third from the
 * top so what comes next is in view — unless the wheel has scrolled it, in which case [top].
 */
internal fun lyricsView(
    state: LyricsState?,
    positionMs: Long,
    top: Int,
    following: Boolean
): LcdScreen.LyricsView = when (state) {
    null, LyricsState.Idle, LyricsState.Looking -> LcdScreen.LyricsView(emptyList(), -1, "Looking for the words…")
    LyricsState.None -> LcdScreen.LyricsView(emptyList(), -1, "No lyrics for this song")
    is LyricsState.Ready -> {
        val lines = state.lyrics.lines
        val current = state.lyrics.lineAt(positionMs)
        val first = if (following && current >= 0) current - 2 else top
        val from = first.coerceIn(0, (lines.size - LYRIC_ROWS).coerceAtLeast(0))
        val shown = lines.subList(from, minOf(from + LYRIC_ROWS, lines.size)).map { it.text }
        LcdScreen.LyricsView(shown, if (current >= 0) current - from else -1, null, top = from)
    }
}

/**
 * The main menu as the device shows it switched on and left alone — for the showroom, where it
 * stands on its plinth with its screen lit: the list at its first row, and [cover] in the split
 * screen's right half, holding still.
 */
internal fun mainMenuAtRest(cover: ImageBitmap?): LcdScreen {
    val rows = PocketMenus.rows(MenuKey.Main, PocketLibrary(), emptyMap(), hasNowPlaying = false)
    return LcdScreen.Menu(
        title = PocketMenus.title(MenuKey.Main, PocketLibrary()),
        rows = rows.take(PocketMenus.VISIBLE_ROWS).map { LcdRow(it.label, it.leadsOn) },
        selected = 0,
        top = 0,
        total = rows.size,
        art = cover?.let { LcdScreen.ArtSlides(current = it, next = null, seed = 0) }
    )
}
