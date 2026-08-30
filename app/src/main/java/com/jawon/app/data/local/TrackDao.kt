package com.jawon.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM Track WHERE albumId = :albumId ORDER BY trackNumber")
    fun getForAlbum(albumId: String): Flow<List<Track>>

    /**
     * Just the titles, once — what the wall needs to print a tracklist on a case back.
     *
     * A one-shot rather than [getForAlbum]'s Flow: the back inlay is baked once per album
     * and cached as a bitmap, so subscribing every visible album to live track updates would
     * be dozens of open queries to redraw art that never changes.
     */
    @Query("SELECT title FROM Track WHERE albumId = :albumId ORDER BY trackNumber")
    suspend fun titlesForAlbum(albumId: String): List<String>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

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
