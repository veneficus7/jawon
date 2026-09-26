package com.jawon.app.ui.pocketscene

/**
 * The device's menus: what each screen lists, and where the highlight is on it.
 *
 * Pure, like [ClickWheel]. A level of the menu is stored as *what it is* — [MenuKey] — and never
 * as the rows it showed when it was opened, so a list stays right while the library changes
 * under it: an album imported with the Albums screen open appears in it.
 *
 * The shape is the classic's, cut to what Jawon is. There is no Songs list across the whole
 * library and no shuffle of everything, because the album is the unit here (PLAN §2) — a song
 * list is only ever the inside of one album. **Shelves are the playlists**, in the order they
 * are arranged, so the curation done on the wall survives in a pocket.
 */
sealed interface MenuKey {
    data object Main : MenuKey
    data object Albums : MenuKey
    data object Shelves : MenuKey
    data object Artists : MenuKey
    data class Shelf(val shelfId: String) : MenuKey
    data class Artist(val name: String) : MenuKey
    data class Album(val albumId: String) : MenuKey
    data object NowPlaying : MenuKey
}

/** What choosing a row does. */
sealed interface RowAction {
    data class Open(val key: MenuKey) : RowAction
    /** Play [albumId] from its [index]th track. */
    data class PlayTrack(val albumId: String, val index: Int) : RowAction
    data object AddAlbums : RowAction
    data object OpenSettings : RowAction
}

data class MenuRow(val label: String, val action: RowAction) {
    /** Whether the row leads to another list — the chevron. A track plays instead. */
    val leadsOn: Boolean get() = action !is RowAction.PlayTrack
}

/** The library as the menus see it — built from the wall's layout, which is already loaded. */
data class PocketLibrary(
    /** In the order they stand on the wall, which is the order the owner arranged. */
    val shelves: List<Shelf> = emptyList(),
    val albums: List<Album> = emptyList()
) {
    data class Shelf(val id: String, val name: String, val albumIds: List<String>)
    data class Album(val id: String, val title: String, val artist: String, val coverPath: String?)

    private val byId: Map<String, Album> by lazy { albums.associateBy { it.id } }

    fun album(id: String): Album? = byId[id]
}

object PocketMenus {

    /** Rows on screen at once: 421px under the title bar at 50px a row. */
    const val VISIBLE_ROWS = 8

    /** The device's own name on its main menu — the owner is choosing a real one. */
    const val DEVICE_NAME = "Jawon"

    fun title(key: MenuKey, library: PocketLibrary): String = when (key) {
        MenuKey.Main -> DEVICE_NAME
        MenuKey.Albums -> "Albums"
        MenuKey.Shelves -> "Shelves"
        MenuKey.Artists -> "Artists"
        is MenuKey.Shelf -> library.shelves.firstOrNull { it.id == key.shelfId }?.name ?: "Shelf"
        is MenuKey.Artist -> key.name
        is MenuKey.Album -> library.album(key.albumId)?.title ?: "Album"
        MenuKey.NowPlaying -> "Now Playing"
    }

    /**
     * What a level lists.
     *
     * [tracks] holds the titles of the albums whose tracklists have been read — they are not
     * part of the wall's layout, so the room loads one when its album is opened and the level
     * is empty until it arrives. [hasNowPlaying] adds the main menu's Now Playing row, which on
     * the classic appeared only once there was something to go back to.
     */
    fun rows(
        key: MenuKey,
        library: PocketLibrary,
        tracks: Map<String, List<String>>,
        hasNowPlaying: Boolean
    ): List<MenuRow> = when (key) {
        MenuKey.Main -> buildList {
            add(MenuRow("Albums", RowAction.Open(MenuKey.Albums)))
            add(MenuRow("Shelves", RowAction.Open(MenuKey.Shelves)))
            add(MenuRow("Artists", RowAction.Open(MenuKey.Artists)))
            if (hasNowPlaying) add(MenuRow("Now Playing", RowAction.Open(MenuKey.NowPlaying)))
            add(MenuRow("Add Albums", RowAction.AddAlbums))
            add(MenuRow("Settings", RowAction.OpenSettings))
        }
        MenuKey.Albums -> library.albums
            .sortedWith(compareBy<PocketLibrary.Album> { sortKey(it.title) }.thenBy { sortKey(it.artist) })
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Album(it.id))) }
        MenuKey.Shelves -> library.shelves
            .map { MenuRow(it.name, RowAction.Open(MenuKey.Shelf(it.id))) }
        MenuKey.Artists -> library.albums
            .map { it.artist }
            .distinctBy { it.lowercase() }
            .sortedBy { sortKey(it) }
            .map { MenuRow(it, RowAction.Open(MenuKey.Artist(it))) }
        is MenuKey.Shelf -> library.shelves.firstOrNull { it.id == key.shelfId }
            ?.albumIds.orEmpty()
            .mapNotNull { library.album(it) }
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Album(it.id))) }
        is MenuKey.Artist -> library.albums
            .filter { it.artist.equals(key.name, ignoreCase = true) }
            .sortedBy { sortKey(it.title) }
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Album(it.id))) }
        is MenuKey.Album -> tracks[key.albumId].orEmpty()
            .mapIndexed { index, title -> MenuRow(title, RowAction.PlayTrack(key.albumId, index)) }
        MenuKey.NowPlaying -> emptyList()
    }

    /**
     * How the classic filed a name: case ignored, and a leading "The" or "A" set aside, so
     * The Beatles sit under B. Only when something follows it — a band called "The" is a T.
     */
    fun sortKey(name: String): String {
        val lower = name.trim().lowercase()
        for (article in ARTICLES) {
            if (lower.startsWith(article) && lower.length > article.length) {
                return lower.substring(article.length).trimStart()
            }
        }
        return lower
    }

    private val ARTICLES = listOf("the ", "a ", "an ")
}

/**
 * Where the device is in its menus: a stack of levels, each remembering its highlight.
 *
 * Going back returns to the row you left — the classic did, and a menu that forgets its place
 * makes every trip into an album a walk back from the top.
 */
data class PocketNav(val stack: List<Level> = listOf(Level(MenuKey.Main))) {

    data class Level(val key: MenuKey, val selected: Int = 0, val top: Int = 0)

    val current: Level get() = stack.last()

    /**
     * Moves the highlight [items] rows down (negative is up), stopping at either end — the
     * classic does not wrap round — and scrolls the window only as far as keeps it in view.
     */
    fun move(items: Int, rowCount: Int): PocketNav {
        if (rowCount <= 0) return this
        val level = current
        val selected = (level.selected + items).coerceIn(0, rowCount - 1)
        if (selected == level.selected) return this
        return replaceTop(level.copy(selected = selected, top = windowTop(selected, level.top, rowCount)))
    }

    /** Keeps the highlight on a real row when the list under it shrinks. */
    fun clamp(rowCount: Int): PocketNav {
        val level = current
        val selected = level.selected.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        val top = windowTop(selected, level.top, rowCount)
        if (selected == level.selected && top == level.top) return this
        return replaceTop(level.copy(selected = selected, top = top))
    }

    fun open(key: MenuKey): PocketNav = PocketNav(stack + Level(key))

    /** One level up; the main menu is as far as it goes. */
    fun back(): PocketNav = if (stack.size > 1) PocketNav(stack.dropLast(1)) else this

    /** Straight to the main menu, keeping its highlight. */
    fun main(): PocketNav = PocketNav(listOf(stack.first()))

    private fun replaceTop(level: Level) = PocketNav(stack.dropLast(1) + level)

    companion object {
        /**
         * Kept across leaving the room — for Settings, or to add albums — as a list of plain
         * strings, one per level. The classic remembered where you were when you put it down.
         */
        val Saver: androidx.compose.runtime.saveable.Saver<PocketNav, Any> =
            androidx.compose.runtime.saveable.Saver(
                save = { nav -> ArrayList(nav.stack.map { encode(it) }) },
                restore = { saved ->
                    val levels = (saved as? List<*>)?.mapNotNull { (it as? String)?.let(::decode) }.orEmpty()
                    if (levels.isEmpty() || levels.first().key != MenuKey.Main) PocketNav() else PocketNav(levels)
                }
            )

        /** One level as `selected|top|kind|argument`. The argument is last, so it may hold a `|`. */
        fun encode(level: Level): String {
            val (kind, arg) = when (val k = level.key) {
                MenuKey.Main -> "main" to ""
                MenuKey.Albums -> "albums" to ""
                MenuKey.Shelves -> "shelves" to ""
                MenuKey.Artists -> "artists" to ""
                MenuKey.NowPlaying -> "nowplaying" to ""
                is MenuKey.Shelf -> "shelf" to k.shelfId
                is MenuKey.Artist -> "artist" to k.name
                is MenuKey.Album -> "album" to k.albumId
            }
            return "${level.selected}|${level.top}|$kind|$arg"
        }

        fun decode(text: String): Level? {
            val parts = text.split('|', limit = 4)
            if (parts.size != 4) return null
            val selected = parts[0].toIntOrNull() ?: return null
            val top = parts[1].toIntOrNull() ?: return null
            val arg = parts[3]
            val key = when (parts[2]) {
                "main" -> MenuKey.Main
                "albums" -> MenuKey.Albums
                "shelves" -> MenuKey.Shelves
                "artists" -> MenuKey.Artists
                "nowplaying" -> MenuKey.NowPlaying
                "shelf" -> MenuKey.Shelf(arg)
                "artist" -> MenuKey.Artist(arg)
                "album" -> MenuKey.Album(arg)
                else -> return null
            }
            return Level(key, selected, top)
        }

        /** The first visible row, moved as little as keeps [selected] on screen. */
        fun windowTop(selected: Int, top: Int, rowCount: Int): Int {
            val visible = PocketMenus.VISIBLE_ROWS
            var t = top
            if (selected < t) t = selected
            if (selected >= t + visible) t = selected - visible + 1
            return t.coerceIn(0, (rowCount - visible).coerceAtLeast(0))
        }
    }
}
