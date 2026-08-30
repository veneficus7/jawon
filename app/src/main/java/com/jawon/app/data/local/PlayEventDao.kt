package com.jawon.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEvent)

    /**
     * Total listening time per album, most-listened first.
     *
     * Here now because it is the query the stats screens will be built on, and having it
     * written means the table's shape has been checked against a real question rather than
     * only against how it is written.
     */
    @Query(
        """
        SELECT albumId, SUM(msPlayed) AS totalMs, COUNT(*) AS plays
        FROM PlayEvent
        GROUP BY albumId
        ORDER BY totalMs DESC
        """
    )
    fun albumTotals(): Flow<List<AlbumListening>>

    @Query("SELECT * FROM PlayEvent ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<PlayEvent>>

    /** For the delete path: an album's history goes with it. */
    @Query("DELETE FROM PlayEvent WHERE albumId = :albumId")
    suspend fun deleteForAlbum(albumId: String)
}

/** Projection for [PlayEventDao.albumTotals]. */
data class AlbumListening(
    val albumId: String,
    val totalMs: Long,
    val plays: Int
)
