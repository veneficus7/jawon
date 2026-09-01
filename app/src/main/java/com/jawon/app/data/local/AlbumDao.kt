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

    @Query("SELECT * FROM Album WHERE id = :id")
    fun getById(id: String): Flow<Album?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: Album)

    @Delete
    suspend fun delete(album: Album)

    /** One-shot read, for the delete path — which needs the row once, not a subscription. */
    @Query("SELECT * FROM Album WHERE id = :id")
    suspend fun findById(id: String): Album?

    @Query("DELETE FROM Album WHERE id = :id")
    suspend fun deleteById(id: String)
}
