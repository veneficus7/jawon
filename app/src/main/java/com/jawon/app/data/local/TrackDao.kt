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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Delete
    suspend fun delete(track: Track)
}
