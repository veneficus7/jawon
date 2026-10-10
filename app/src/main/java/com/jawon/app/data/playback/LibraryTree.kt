package com.jawon.app.data.playback

import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.Track
import java.util.Locale

/**
 * **The library as a tree something else can browse** (0.7.6) — a car's screen, mostly.
 *
 * Android Auto, the system's own media controls and anything else that browses a player all ask
 * the same question: *what are this node's children?* This is Jawon's answer, and it is the room
 * again in words — shelves, the records on them, the tracks on a record. The album stays the unit
 * (PLAN §2): a track is found under its record, and choosing one plays that record from there.
 *
 * Pure: ids in, entries out. `LibraryCallback` turns the entries into what Media3 hands across.
 */
object LibraryTree {

    const val ROOT = "jawon:root"
    const val RECENT = "jawon:recent"
    const val SHELVES = "jawon:shelves"
    const val ALBUMS = "jawon:albums"
    private const val SHELF = "jawon:shelf:"
    private const val ALBUM = "jawon:album:"

    fun shelfNode(shelfId: String): String = SHELF + shelfId
    fun albumNode(albumId: String): String = ALBUM + albumId

    sealed interface Node {
        data object Root : Node
        data object Recent : Node
        data object Shelves : Node
        data object Albums : Node
        data class OfShelf(val shelfId: String) : Node
        data class OfAlbum(val albumId: String) : Node

        /**
         * Anything that is not one of the above is a track, **under the id the queue already
         * knows it by** — so a track chosen in a car and a track chosen on the deck are the same
         * item to the player, and nothing has to translate between them.
         */
        data class OfTrack(val trackId: String) : Node
    }

    fun parse(mediaId: String): Node = when {
        mediaId == ROOT -> Node.Root
        mediaId == RECENT -> Node.Recent
        mediaId == SHELVES -> Node.Shelves
        mediaId == ALBUMS -> Node.Albums
        mediaId.startsWith(SHELF) -> Node.OfShelf(mediaId.removePrefix(SHELF))
        mediaId.startsWith(ALBUM) -> Node.OfAlbum(mediaId.removePrefix(ALBUM))
        else -> Node.OfTrack(mediaId)
    }

    enum class Kind {
        /** One of the root's own folders. */
        FOLDER,
        SHELF,
        ALBUM,
        TRACK
    }

    data class Entry(
        val mediaId: String,
        val title: String,
        val subtitle: String?,
        val kind: Kind,
        /** The album whose cover stands for this entry, if any. */
        val coverOf: String?,
        /** For a track: the record it is on. */
        val albumTitle: String? = null
    )

    /** How many records "Recently played" goes back. A car's screen is a glance, not a history. */
    const val RECENT_LIMIT = 12

    /**
     * The children of [node], or null if there is no such node.
     *
     * [tracks] is only read for an album's node, and is that album's tracks in playing order.
     */
    fun children(
        node: Node,
        shelves: List<Shelf>,
        albums: List<Album>,
        tracks: List<Track> = emptyList()
    ): List<Entry>? = when (node) {
        Node.Root -> buildList {
            val played = recent(albums)
            if (played.isNotEmpty()) {
                add(Entry(RECENT, "Recently played", null, Kind.FOLDER, played.first().coverId()))
            }
            // A single shelf is the whole library, which "Albums" already is.
            if (stocked(shelves, albums).size > 1) {
                add(Entry(SHELVES, "Shelves", null, Kind.FOLDER, null))
            }
            if (albums.isNotEmpty()) add(Entry(ALBUMS, "Albums", null, Kind.FOLDER, null))
        }

        Node.Recent -> recent(albums).map(::albumEntry)

        Node.Shelves -> stocked(shelves, albums).map { shelf ->
            val on = albums.filter { it.shelfId == shelf.id }.sortedBy { it.slotIndex }
            Entry(
                mediaId = shelfNode(shelf.id),
                title = shelf.name,
                subtitle = if (on.size == 1) "1 record" else "${on.size} records",
                kind = Kind.SHELF,
                coverOf = on.firstOrNull { it.caseCoverUri != null }?.id
            )
        }

        Node.Albums -> albums.sortedWith(compareBy({ filingKey(it.title) }, { filingKey(it.artist) }))
            .map(::albumEntry)

        is Node.OfShelf ->
            if (shelves.none { it.id == node.shelfId }) null
            else albums.filter { it.shelfId == node.shelfId }.sortedBy { it.slotIndex }.map(::albumEntry)

        is Node.OfAlbum -> albums.firstOrNull { it.id == node.albumId }?.let { album ->
            tracks.map { trackEntry(it, album) }
        }

        is Node.OfTrack -> null
    }

    /** The entry for one node on its own — what a browser asks for when it holds only an id. */
    fun entry(node: Node, shelves: List<Shelf>, albums: List<Album>, track: Track? = null): Entry? = when (node) {
        Node.Root -> Entry(ROOT, "Jawon", null, Kind.FOLDER, null)
        Node.Recent, Node.Shelves, Node.Albums ->
            children(Node.Root, shelves, albums)?.firstOrNull { parse(it.mediaId) == node }
        is Node.OfShelf -> children(Node.Shelves, shelves, albums)?.firstOrNull { it.mediaId == shelfNode(node.shelfId) }
        is Node.OfAlbum -> albums.firstOrNull { it.id == node.albumId }?.let(::albumEntry)
        is Node.OfTrack -> track?.let { t -> albums.firstOrNull { it.id == t.albumId }?.let { trackEntry(t, it) } }
    }

    fun albumEntry(album: Album): Entry =
        Entry(albumNode(album.id), album.title, album.artist, Kind.ALBUM, album.coverId())

    fun trackEntry(track: Track, album: Album): Entry = Entry(
        mediaId = track.id,
        title = track.title,
        // A compilation credits each track; everything else falls back to the record's artist.
        subtitle = track.artist?.takeIf { it.isNotBlank() } ?: album.artist,
        kind = Kind.TRACK,
        coverOf = album.coverId(),
        albumTitle = album.title
    )

    /** The records played most recently, newest first. */
    fun recent(albums: List<Album>): List<Album> =
        albums.filter { it.lastPlayedAt != null }
            .sortedByDescending { it.lastPlayedAt }
            .take(RECENT_LIMIT)

    /** The shelves with something on them, in the room's order. An empty shelf is nothing to browse. */
    private fun stocked(shelves: List<Shelf>, albums: List<Album>): List<Shelf> {
        val used = albums.mapTo(HashSet()) { it.shelfId }
        return shelves.filter { it.id in used }.sortedBy { it.order }
    }

    private fun Album.coverId(): String? = id.takeIf { caseCoverUri != null }

    /** How a title is filed: without its article, and without regard to case. */
    fun filingKey(text: String): String {
        val lower = text.trim().lowercase(Locale.ROOT)
        for (article in ARTICLES) if (lower.startsWith(article)) return lower.removePrefix(article)
        return lower
    }

    private val ARTICLES = listOf("the ", "a ", "an ")
}
