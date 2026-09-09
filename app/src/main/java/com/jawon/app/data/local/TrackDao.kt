package com.jawon.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM Track WHERE albumId = :albumId ORDER BY discNumber, trackNumber")
    fun getForAlbum(albumId: String): Flow<List<Track>>

    /**
     * Just the titles, once — what the wall needs to print a tracklist on a case back.
     *
     * A one-shot rather than [getForAlbum]'s Flow: the back inlay is baked once per album
     * and cached as a bitmap, so subscribing every visible album to live track updates would
     * be dozens of open queries to redraw art that never changes.
     */
    /**
     * Every file already in the library, for the device scan to skip.
     *
     * Scanning the phone finds everything, including albums the user imported by hand last
     * week. Offering those again as new is how a library ends up with each record twice, and
     * the file URI is the only thing that identifies a track unambiguously — two rips of the
     * same album share a title but not a path.
     */
    @Query("SELECT fileUri FROM Track")
    suspend fun allFileUris(): List<String>

    /** One-shot, for the backup writer. */
    @Query("SELECT * FROM Track ORDER BY albumId, discNumber, trackNumber")
    suspend fun listAll(): List<Track>

    /**
     * One album's tracks, once, in playing order.
     *
     * The same order [getForAlbum] gives, which is what makes the survivor of a de-duplication
     * the row the tracklist was already showing. See `LibraryRepair`.
     */
    @Query("SELECT * FROM Track WHERE albumId = :albumId ORDER BY discNumber, trackNumber")
    suspend fun listForAlbum(albumId: String): List<Track>

    /**
     * Tracks whose own artist was never read — everything imported before that column existed.
     *
     * See `TrackArtistBackfill`: without a pass over these, per-track credits would only ever
     * appear on albums added after the update, and an existing library would look unchanged.
     */
    @Query("SELECT * FROM Track WHERE artist IS NULL")
    suspend fun withoutArtist(): List<Track>

    @Query("UPDATE Track SET artist = :artist WHERE id = :id")
    suspend fun setArtist(id: String, artist: String)

    /** Remember what a lookup returned, including that it returned nothing. */
    @Query("UPDATE Track SET fetchedLyrics = :lyrics WHERE id = :id")
    suspend fun setFetchedLyrics(id: String, lyrics: String)

    @Query("SELECT fetchedLyrics FROM Track WHERE id = :id")
    suspend fun fetchedLyrics(id: String): String?

    /**
     * Forget every *empty* answer, keeping every real one.
     *
     * An empty string means "we asked and there were none", and it is what stops a track being
     * looked up on every visit to the page. That is right while the question stays the same —
     * and wrong the moment it changes. Turning a lyrics switch on, or improving how the lookup
     * matches, asks a different question of the same track, and a cached "no" from the old one
     * would answer it forever.
     *
     * Deliberately narrow: rows holding actual words are untouched, so this costs a lookup for
     * the tracks that failed and nothing at all for the tracks that worked.
     */
    @Query("UPDATE Track SET fetchedLyrics = NULL WHERE fetchedLyrics = ''")
    suspend fun forgetEmptyLyricLookups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<Track>)

    @Delete
    suspend fun delete(track: Track)

    /**
     * Forgets every track on an album.
     *
     * **This removes Jawon's record of the files, never the files.** They are the user's own
     * audio, in their own folders, reached through a document URI that only grants read
     * access — deleting an album takes it off the shelf and leaves the music exactly where
     * the user put it. Anything else would be a music player deleting music.
     */
    @Query("DELETE FROM Track WHERE albumId = :albumId")
    suspend fun deleteForAlbum(albumId: String)
}
