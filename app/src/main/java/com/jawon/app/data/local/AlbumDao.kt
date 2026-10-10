package com.jawon.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM Album WHERE shelfId = :shelfId ORDER BY slotIndex")
    fun getForShelf(shelfId: String): Flow<List<Album>>

    @Query("SELECT * FROM Album ORDER BY shelfId, slotIndex")
    fun getAll(): Flow<List<Album>>

    /** One-shot, for the backup writer. */
    @Query("SELECT * FROM Album ORDER BY shelfId, slotIndex")
    suspend fun listAll(): List<Album>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<Album>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: Album)

    @Delete
    suspend fun delete(album: Album)

    /** One-shot read, for the delete path — which needs the row once, not a subscription. */
    @Query("SELECT * FROM Album WHERE id = :id")
    suspend fun findById(id: String): Album?

    /** The record that was on last — what "resume" means when nothing is loaded. */
    @Query("SELECT * FROM Album WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun lastPlayed(): Album?

    @Query("DELETE FROM Album WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Everything that came from one music server.
     *
     * The sync needs this to tell three cases apart: an album it already has and should update,
     * one it has never seen, and one that has gone from the server and should go from the wall.
     * `sourceId IS NULL` is the local library and is never returned here, which is what keeps a
     * sync from touching records that live on the phone.
     */
    @Query("SELECT * FROM Album WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: String): List<Album>

    /**
     * How many albums came from one source, watched rather than asked.
     *
     * For the settings screen's one-line answer to "is this working" — a server section whose
     * heading says *412 albums* has said everything the three paragraphs under it were saying,
     * and it updates itself the moment a sync finishes without anyone having to remember to
     * refresh it.
     */
    @Query("SELECT COUNT(*) FROM Album WHERE sourceId = :sourceId")
    fun countForSource(sourceId: String): Flow<Int>
}
