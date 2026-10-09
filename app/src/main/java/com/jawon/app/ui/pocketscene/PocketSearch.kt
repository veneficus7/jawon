package com.jawon.app.ui.pocketscene

import java.util.Locale

/**
 * **Search**, the classic's (0.7.4): a strip of letters along the foot of the screen, turned through
 * by the wheel; the centre adds the letter under the cursor, ◀◀ takes the last one back, ▶▶ goes
 * up into what was found, and MENU comes back down to the letters. What is found is albums and
 * artists — the album is the unit here (PLAN §2), so a song is found by its record.
 *
 * Pure. The query itself lives in the menu key ([MenuKey.Search]), so it is saved with the rest of
 * where the device is; the cursor and which half has the wheel are the device's.
 */
object PocketSearch {

    /** The strip: the alphabet, the digits, a space, and the key that takes a letter back. */
    const val DELETE = "⌫"
    const val SPACE = "␣"
    val LETTERS: List<String> = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } +
        listOf(SPACE, DELETE)

    /** What choosing [letter] does to [query]. */
    fun type(query: String, letter: String): String = when (letter) {
        DELETE -> query.dropLast(1)
        SPACE -> if (query.isEmpty() || query.endsWith(' ')) query else "$query "
        else -> query + letter
    }

    /**
     * What [query] finds: albums whose title or artist matches, then artists whose name does.
     * A word that *starts* with the query ranks above one that merely contains it, so "BE" puts
     * The Beatles before Abbey Road's "Because"; a leading "The" or "A" is set aside as the
     * Albums list sets it aside.
     */
    fun results(query: String, library: PocketLibrary): List<MenuRow> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()

        fun rank(text: String): Int {
            val t = text.lowercase(Locale.ROOT)
            val key = PocketMenus.sortKey(text)
            return when {
                key.startsWith(q) || t.startsWith(q) -> 0
                t.split(' ', '-', '(', '/', '&').any { it.startsWith(q) } -> 1
                t.contains(q) -> 2
                else -> -1
            }
        }

        val albums = library.albums.mapNotNull { album ->
            val r = listOf(rank(album.title), rank(album.artist)).filter { it >= 0 }.minOrNull()
                ?: return@mapNotNull null
            Triple(r, PocketMenus.sortKey(album.title), album)
        }.sortedWith(compareBy({ it.first }, { it.second }))
            .map { (_, _, album) -> MenuRow("${album.title} · ${album.artist}", RowAction.Open(MenuKey.Album(album.id))) }

        val artists = library.albums.map { it.artist }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .mapNotNull { name -> rank(name).takeIf { it >= 0 }?.let { it to name } }
            .sortedWith(compareBy({ it.first }, { PocketMenus.sortKey(it.second) }))
            .map { (_, name) -> MenuRow(name, RowAction.Open(MenuKey.Artist(name)), value = "Artist") }

        return albums + artists
    }
}
