package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.Album

/**
 * The order records hang in along a shelf.
 *
 * Until now there was exactly one: `slotIndex`, which is import order. That is fine for the
 * twenty albums a new library has and useless at two hundred, where the only way to find
 * something is to remember roughly when you added it.
 *
 * **This also finishes the crate flip.** `PLAN.md` §9 #7 describes flipping to alphabet
 * dividers, and [WallLayout.caseAtFraction] had to settle for showing what happens to be at
 * each position instead — an A–Z rail over an unsorted wall would send the wall lurching
 * backwards and forwards under a thumb moving steadily down. Sorted by [TITLE] or [ARTIST],
 * that rail *is* an alphabetical index, with no change to the flip itself.
 *
 * Sorting happens within a shelf, never across them. One shelf per album is a locked decision
 * and shelves are the user's own grouping; re-ordering records inside a crate is what a sort
 * means here, and shuffling them between crates would be overruling the person who filed them.
 */
enum class WallSort {

    /** As filed: `slotIndex`, which is import order until the shelf list is used to change it. */
    SHELF,

    /** By album title, articles ignored — the same rule the crate flip's dividers use. */
    TITLE,

    /** By artist, then by title within an artist, so a discography reads in order. */
    ARTIST,

    /** Oldest first. A shelf becomes a timeline. */
    YEAR,

    /** Most listened first, from the same `PlayEvent` totals the patina and stats read. */
    PLAYED,

    /** Most recently added first — what you have been collecting lately. */
    ADDED;

    val label: String
        get() = when (this) {
            SHELF -> "AS FILED"
            TITLE -> "ALBUM"
            ARTIST -> "ARTIST"
            YEAR -> "YEAR"
            PLAYED -> "MOST PLAYED"
            ADDED -> "RECENTLY ADDED"
        }

    /**
     * Whether the crate flip's letters read as an actual A–Z index in this order.
     *
     * Only the two alphabetical sorts make the rail an index; under the others it is still a
     * position scrubber that happens to be labelled, which is honest but is not the same
     * thing and should not be described as one.
     */
    val isAlphabetical: Boolean get() = this == TITLE || this == ARTIST

    companion object {
        fun fromName(name: String?): WallSort =
            entries.firstOrNull { it.name == name } ?: SHELF

        /**
         * Order one shelf's albums.
         *
         * Every comparator falls through to title as its last key, so the result is total:
         * two albums from the same year, or both never played, land in a stable order rather
         * than whichever one the database happened to return first. Without that, an unrelated
         * write could silently rearrange a shelf.
         *
         * @param listenedMs total listening per album id, for [PLAYED]. Absent means never.
         */
        fun order(
            albums: List<Album>,
            sort: WallSort,
            listenedMs: Map<String, Long> = emptyMap()
        ): List<Album> = when (sort) {
            SHELF -> albums.sortedBy { it.slotIndex }
            TITLE -> albums.sortedWith(compareBy({ filingTitle(it.title) }, { it.title }))
            ARTIST -> albums.sortedWith(
                compareBy({ filingTitle(it.artist) }, { filingTitle(it.title) })
            )
            // Albums with no year go last rather than to 1970, which is where a null would
            // land them if it were treated as zero.
            YEAR -> albums.sortedWith(
                compareBy({ it.year ?: Int.MAX_VALUE }, { filingTitle(it.title) })
            )
            PLAYED -> albums.sortedWith(
                compareByDescending<Album> { listenedMs[it.id] ?: 0L }
                    .thenBy { filingTitle(it.title) }
            )
            ADDED -> albums.sortedWith(
                compareByDescending<Album> { it.addedAt }.thenBy { filingTitle(it.title) }
            )
        }

        /**
         * What a title or artist files under: lower case, leading article dropped.
         *
         * The same rule as [WallLayout.initialOf], so the letter on the crate flip's rail and
         * the position the record actually sorts to can never disagree — which they would if
         * the rail stripped "The" and the sort did not.
         */
        private fun filingTitle(value: String): String {
            val trimmed = value.trim()
            val stripped = ARTICLES.firstOrNull { article ->
                trimmed.length > article.length &&
                    trimmed.regionMatches(0, article, 0, article.length, ignoreCase = true)
            }?.let { trimmed.substring(it.length).trim() } ?: trimmed
            return stripped.lowercase()
        }

        private val ARTICLES = listOf("The ", "A ", "An ")
    }
}

/**
 * Finding a record by typing, rather than by looking for it.
 *
 * The crate flip covers browsing — running the wall past your thumb until something catches
 * your eye. This covers the other case, where you already know what you want and the wall is
 * two hundred records long.
 *
 * Pure, so the matching rules are testable without a scene: what counts as a match is the sort
 * of thing that is obvious until someone types a lower-case letter, or an accent, or the word
 * "the".
 */
object WallSearch {

    /**
     * Albums matching [query], best first, or everything when the query is blank.
     *
     * Ranking, rather than a plain filter, because "kid" should put *Kid A* above *The Kids
     * Are Alright* and a filter has no opinion. In order: a title that starts with the query,
     * then an artist that does, then a title that contains it anywhere, then an artist. Within
     * a rank the wall's own order is kept, so searching never reshuffles what you are looking
     * at more than it has to.
     */
    fun rank(cases: List<CaseLayout>, query: String): List<CaseLayout> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return cases
        return cases
            .mapIndexedNotNull { index, case ->
                val score = score(case, q) ?: return@mapIndexedNotNull null
                Triple(score, index, case)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
    }

    /** The single best match, which is what a search box's "go" lands on. */
    fun best(cases: List<CaseLayout>, query: String): CaseLayout? =
        rank(cases, query).firstOrNull().takeIf { query.isNotBlank() }

    /**
     * Lower is better; `null` is no match.
     *
     * Articles are stripped from the *start of the field* but not from the query, so typing
     * "bends" finds "The Bends" and typing "the bends" finds it too — the second lands as a
     * contains-match rather than a prefix one, which is the right ranking rather than a
     * failure.
     */
    private fun score(case: CaseLayout, q: String): Int? {
        val title = case.title.lowercase()
        val artist = case.artist.lowercase()
        val filedTitle = strip(title)
        val filedArtist = strip(artist)
        return when {
            filedTitle.startsWith(q) || title.startsWith(q) -> 0
            filedArtist.startsWith(q) || artist.startsWith(q) -> 1
            title.contains(q) -> 2
            artist.contains(q) -> 3
            else -> null
        }
    }

    private fun strip(value: String): String {
        val t = value.trim()
        return listOf("the ", "a ", "an ").firstOrNull {
            t.length > it.length && t.startsWith(it)
        }?.let { t.substring(it.length).trim() } ?: t
    }
}
