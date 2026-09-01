package com.jawon.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {
    @Query("SELECT * FROM Shelf ORDER BY `order`")
    fun getAll(): Flow<List<Shelf>>

    /** One-shot, for the backup writer — it needs a snapshot, not a subscription. */
    @Query("SELECT * FROM Shelf ORDER BY `order`")
    suspend fun listAll(): List<Shelf>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shelf: Shelf)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shelves: List<Shelf>)

    @Delete
    suspend fun delete(shelf: Shelf)
}
