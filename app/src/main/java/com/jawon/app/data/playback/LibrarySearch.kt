package com.jawon.app.data.playback

import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.Track
import java.text.Normalizer
import java.util.Locale

/**
 * **"Play ⟨something⟩ on Jawon"** (0.7.6): what a spoken request means, in this library.
 *
 * The assistant hands over words — sometimes sorted into an album, an artist and a title, more
 * often not — and something has to be playing a moment later. The answer is always a *record*,
 * and where on it to start: an album by name, the record a song is on (from that song), a record
 * by an artist, a record off a shelf ("play hip hop" — shelves are very often genres), and for
 * no words at all, whatever was on last, from where it stopped.
 *
 * Speech is not typing: "love less" for *Loveless*, a dropped "The", punctuation gone. So the
 * comparison is on text with its case, accents, punctuation and articles taken off, and once more
 * with its spaces taken out as well.
 *
 * Pure. `QueueBuilder` turns a [Pick] into a queue.
 */
object LibrarySearch {

    /** What the assistant understood the request to be about, when it says. */
    data class Hints(
        val album: String? = null,
        val artist: String? = null,
        val title: String? = null
    ) {
        val isEmpty: Boolean get() = album.isNullOrBlank() && artist.isNullOrBlank() && title.isNullOrBlank()
    }

    /** A record, and where on it to start: at [trackId], where it was left ([resume]), or the top. */
    data class Pick(
        val albumId: String,
        val trackId: String? = null,
        val resume: Boolean = false
    )

    fun pick(
        query: String?,
        hints: Hints,
        shelves: List<Shelf>,
        albums: List<Album>,
        tracks: List<Track>
    ): Pick? {
        if (albums.isEmpty()) return null
        val byId = albums.associateBy { it.id }

        // What the assistant sorted out for us comes first: it knows a title from an artist.
        hints.album?.takeIf { it.isNotBlank() }?.let { wanted ->
            bestAlbum(wanted, hints.artist, albums)?.let { return Pick(it.id) }
        }
        hints.title?.takeIf { it.isNotBlank() }?.let { wanted ->
            bestTrack(wanted, hints.artist, tracks, byId)?.let { return Pick(it.albumId, it.id) }
        }
        hints.artist?.takeIf { it.isNotBlank() }?.let { wanted ->
            byArtist(wanted, albums)?.let { return Pick(it.id) }
        }

        val words = clean(query.orEmpty())
        // "Play music on Jawon": the record that was on, where it stopped.
        if (words.isEmpty()) {
            if (!hints.isEmpty) return null
            val last = albums.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! }
            return last?.let { Pick(it.id, resume = true) } ?: Pick(firstOnTheWall(shelves, albums).id)
        }

        // "⟨title⟩ by ⟨artist⟩", said in one breath.
        val by = words.split(" by ", limit = 2)
        if (by.size == 2 && by[0].isNotBlank() && by[1].isNotBlank()) {
            bestAlbum(by[0], by[1], albums, floor = STRONG)?.let { return Pick(it.id) }
            bestTrack(by[0], by[1], tracks, byId, floor = STRONG)?.let { return Pick(it.albumId, it.id) }
        }

        // Otherwise the words are weighed against everything, and the best thing wins. A record
        // beats a song of the same name, and both beat an artist or a shelf: the album is the unit.
        var best: Pick? = null
        var bestScore = FLOOR - 1
        fun offer(score: Int, pick: () -> Pick?) {
            if (score > bestScore) pick()?.let { best = it; bestScore = score }
        }

        for (album in albums) {
            val score = maxOf(
                score(album.title, words),
                // "loveless my bloody valentine", either way round.
                score("${album.title} ${album.artist}", words) - 2,
                score("${album.artist} ${album.title}", words) - 2
            )
            offer(score) { Pick(album.id) }
        }
        for (track in tracks) {
            offer(score(track.title, words) - 5) { byId[track.albumId]?.let { Pick(it.id, track.id) } }
        }
        for (artist in albums.map { it.artist }.distinctBy(::clean)) {
            offer(score(artist, words) - 10) { byArtist(artist, albums)?.let { Pick(it.id) } }
        }
        for (shelf in shelves) {
            offer(score(shelf.name, words) - 12) { fromShelf(shelf, albums)?.let { Pick(it.id) } }
        }
        return best
    }

    /** One thing a search turned up: a record, or a song on one. */
    data class Found(val album: Album, val track: Track?)

    /**
     * What typing or saying [query] finds, best first, records before songs — for a list on a
     * car's screen, where [pick] is for when something must simply start.
     */
    fun results(query: String, albums: List<Album>, tracks: List<Track>, limit: Int = 30): List<Found> {
        val words = clean(query)
        if (words.isEmpty()) return emptyList()
        val byId = albums.associateBy { it.id }
        val records = albums.mapNotNull { album ->
            val score = maxOf(score(album.title, words), score(album.artist, words) - 10)
            if (score >= FLOOR) score to Found(album, null) else null
        }.sortedWith(compareBy({ -it.first }, { LibraryTree.filingKey(it.second.album.title) }))
        val songs = tracks.mapNotNull { track ->
            val album = byId[track.albumId] ?: return@mapNotNull null
            val score = score(track.title, words)
            if (score >= FLOOR) score to Found(album, track) else null
        }.sortedWith(compareBy({ -it.first }, { it.second.track!!.title.lowercase(Locale.ROOT) }))
        return (records + songs).map { it.second }.take(limit)
    }

    // ---- Choosing ------------------------------------------------------------------------------

    private fun bestAlbum(title: String, artist: String?, albums: List<Album>, floor: Int = FLOOR): Album? {
        val wanted = clean(title)
        val by = artist?.let(::clean).orEmpty()
        return albums.map { album ->
            val agrees = by.isNotEmpty() && score(album.artist, by) >= FLOOR
            (score(album.title, wanted) + if (agrees) ARTIST_AGREES else 0) to album
        }.filter { it.first >= floor }.maxByOrNull { it.first }?.second
    }

    private fun bestTrack(
        title: String,
        artist: String?,
        tracks: List<Track>,
        albums: Map<String, Album>,
        floor: Int = FLOOR
    ): Track? {
        val wanted = clean(title)
        val by = artist?.let(::clean).orEmpty()
        return tracks.mapNotNull { track ->
            val album = albums[track.albumId] ?: return@mapNotNull null
            val credit = track.artist?.takeIf { it.isNotBlank() } ?: album.artist
            val agrees = by.isNotEmpty() && score(credit, by) >= FLOOR
            (score(track.title, wanted) + if (agrees) ARTIST_AGREES else 0) to track
        }.filter { it.first >= floor }.maxByOrNull { it.first }?.second
    }

    /** A record by [artist]: the one played most recently, else the first by title. */
    private fun byArtist(artist: String, albums: List<Album>): Album? {
        val wanted = clean(artist)
        val theirs = albums.map { score(it.artist, wanted) to it }.filter { it.first >= FLOOR }
        val top = theirs.maxOfOrNull { it.first } ?: return null
        val closest = theirs.filter { it.first == top }.map { it.second }
        return closest.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! }
            ?: closest.minByOrNull { LibraryTree.filingKey(it.title) }
    }

    /**
     * A record off [shelf]: one never played, else the one played longest ago — "something from
     * this shelf" should not be the same thing every time, and should not need a dice.
     */
    private fun fromShelf(shelf: Shelf, albums: List<Album>): Album? {
        val on = albums.filter { it.shelfId == shelf.id }.sortedBy { it.slotIndex }
        return on.firstOrNull { it.lastPlayedAt == null } ?: on.minByOrNull { it.lastPlayedAt!! }
    }

    private fun firstOnTheWall(shelves: List<Shelf>, albums: List<Album>): Album {
        val order = shelves.associate { it.id to it.order }
        return albums.minWith(compareBy({ order[it.shelfId] ?: Int.MAX_VALUE }, { it.slotIndex }))
    }

    // ---- Comparing -----------------------------------------------------------------------------

    /** Below this, two pieces of text are not the same thing said differently. */
    const val FLOOR = 40

    /** At or above this, they very nearly are. */
    private const val STRONG = 60

    private const val ARTIST_AGREES = 15

    /**
     * How well [candidate] answers [wanted] (already [clean]): 100 for the same words, down
     * through "starts with", "has a word that starts with" and "contains", to a share of the
     * wanted words being present; 0 for nothing in common.
     */
    fun score(candidate: String, wanted: String): Int {
        val text = clean(candidate)
        if (text.isEmpty() || wanted.isEmpty()) return 0
        if (text == wanted) return 100
        val joined = text.replace(" ", "")
        val wantedJoined = wanted.replace(" ", "")
        if (joined == wantedJoined) return 95
        if (text.startsWith("$wanted ")) return 80
        if (" $text ".contains(" $wanted ")) return 70
        if (text.startsWith(wanted)) return 65
        if (text.split(' ').any { it.startsWith(wanted) }) return 60
        if (text.contains(wanted)) return 50
        if (wantedJoined.length >= 5 && joined.contains(wantedJoined)) return 45
        val asked = wanted.split(' ').filter { it.isNotEmpty() }
        val have = text.split(' ').toSet()
        val present = asked.count { it in have }
        return when {
            asked.size >= 2 && present == asked.size -> 55
            asked.size >= 2 && present * 2 > asked.size -> 20 + 20 * present / asked.size
            else -> 0
        }
    }

    /**
     * Text as it is compared: lower case, accents and punctuation off, a leading article off, the
     * filler a spoken request arrives wrapped in off ("the album …", "… album"), spaces single.
     */
    fun clean(text: String): String {
        var out = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            // Dots and apostrophes close up rather than open a gap: "R.E.M." is "rem" and
            // "Don't" is "dont", which is also how they come back from speech.
            .replace(INSIDE_WORDS, "")
            .replace(NOT_WORDS, " ")
            .replace(SPACES, " ")
            .trim()
        for (lead in LEADS) if (out.startsWith(lead)) out = out.removePrefix(lead)
        for (tail in TAILS) if (out.endsWith(tail)) out = out.removeSuffix(tail)
        return out.trim()
    }

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORDS = Regex("[^\\p{L}\\p{N} ]+")
    private val INSIDE_WORDS = Regex("[.'’]")
    private val SPACES = Regex(" +")
    private val LEADS = listOf("the album ", "album ", "the record ", "the song ", "song ", "the ", "a ", "an ")
    private val TAILS = listOf(" the album", " album", " record")
}
