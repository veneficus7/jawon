package com.jawon.app.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fixes what is already wrong in a library, rather than only stopping it happening again.
 *
 * **This exists because a guard is not a repair, and the difference is the whole point.** The
 * importer has deduplicated by file URI for a while now, and both scan routes refuse albums
 * they recognise — so no new duplicate should arrive. None of that helps a library that
 * already has them: those rows were written by an older build and will sit there forever,
 * because nothing ever revisits a track that imported successfully. A fix that only applies to
 * future imports reads, to the person looking at the duplicate, as no fix at all.
 *
 * ## What counts as the same track
 *
 * Not the file URI, which is what the importer uses — by the time a duplicate is in the
 * database, the two rows usually point at genuinely different files. The real cases are a
 * folder holding both a FLAC and an MP3 of one record, an album imported once by folder and
 * once by the device scan, and a re-import under a build that had no guard. What those share
 * is the *music*: same album, same disc, same position, same title.
 *
 * So the key is album, disc, track number and a loosened title — and, when the tracks carry no
 * numbering at all, the title and the duration together, so an album of untracked files does
 * not collapse into one row.
 *
 * ## What it keeps
 *
 * The first of each group in playing order, which is the row with the lowest disc and track
 * number and therefore the one the tracklist was already showing. Deliberately *not* the
 * highest quality file: choosing between a FLAC and an MP3 is a decision the user might have
 * views about, and this is a repair with no undo, so it should surprise nobody. Anyone who
 * wants the lossless copy specifically can re-import that folder once the duplicate is gone.
 *
 * **The one real cost, stated plainly:** `PlayEvent` rows point at a track id, and deleting a
 * duplicate orphans whichever plays were recorded against *that* row. Those plays still count
 * toward the album — `albumTotals()` groups by album — so the wall's patina and the listening
 * record do not move. What can shrink is a per-track count on the tracklist's badges, for a
 * track that was being counted twice under two ids. That is a truer number than the one it
 * replaces.
 */
@Singleton
class LibraryRepair @Inject constructor(
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao
) {

    /** What a tidy-up did, for the settings screen to report. */
    data class Result(val duplicatesRemoved: Int, val albumsTouched: Int)

    /**
     * Removes duplicate tracks from every album in the library.
     *
     * Walks album by album rather than over one big list, because "the same track" is only
     * ever a question within one record: two albums can legitimately carry a song with the
     * same name, the same length and the same position, and a live version, a remaster and a
     * greatest-hits compilation are three records that would otherwise eat each other.
     */
    suspend fun removeDuplicateTracks(): Result = withContext(Dispatchers.IO) {
        var removed = 0
        var albums = 0
        for (album in albumDao.listAll()) {
            val tracks = trackDao.listForAlbum(album.id)
            val doomed = duplicatesIn(tracks)
            if (doomed.isEmpty()) continue
            doomed.forEach { trackDao.delete(it) }
            removed += doomed.size
            albums++
        }
        Result(duplicatesRemoved = removed, albumsTouched = albums)
    }

    companion object {

        /**
         * Which of one album's tracks are copies of another — pure, so it can be argued with.
         *
         * Returns the rows to delete, never the ones to keep, so a caller cannot accidentally
         * invert it and empty the library. Order is preserved from the input, which is playing
         * order, so the survivor of each group is the one that was listed first.
         */
        fun duplicatesIn(tracks: List<Track>): List<Track> {
            val seen = HashSet<String>()
            val doomed = mutableListOf<Track>()
            for (track in tracks) {
                if (!seen.add(keyOf(track))) doomed += track
            }
            return doomed
        }

        /**
         * What makes two rows the same recording.
         *
         * Numbered tracks key on their position and title: a number is the strongest statement
         * a tag makes about where a track belongs, and two rows claiming disc 1 track 4 of one
         * album with the same name are the same track however they were imported.
         *
         * Unnumbered tracks — a folder of loose files, which is most of what a scan finds —
         * have no position to key on, so the length stands in for it, rounded to the second.
         * Without that, an album whose files carry no numbers would key entirely on title, and
         * two genuinely different takes with one name would be treated as a duplicate.
         */
        private fun keyOf(track: Track): String {
            val title = normalise(track.title)
            val disc = track.discNumber ?: 1
            return if (track.trackNumber > 0) {
                "$disc/${track.trackNumber}/$title"
            } else {
                "?/$title/${track.durationMs / 1000L}"
            }
        }

        /**
         * A title with the ways one file names a track and another names the same one taken
         * out: case, surrounding space, and the punctuation a tagger sprinkles differently.
         *
         * Deliberately conservative — it does not touch bracketed suffixes, so
         * *Money Trees (Remix)* stays distinct from *Money Trees*. Those really are two
         * recordings, and merging them would be the kind of "helpful" that loses music.
         */
        fun normalise(title: String): String =
            title.trim().lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
                .replace(WHITESPACE, " ")
                .trim()

        private val WHITESPACE = Regex("\\s+")
    }
}
