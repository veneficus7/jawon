package com.jawon.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEvent)

    /** One-shot, for the backup writer. */
    @Query("SELECT * FROM PlayEvent ORDER BY startedAt")
    suspend fun listAll(): List<PlayEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<PlayEvent>)

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

    /**
     * How each track of one album has actually been listened to, for the tracklist badges.
     *
     * `MAX(msPlayed)` rather than `SUM`: the question a badge asks is "was this ever finished",
     * and a track played four times to the two-minute mark has eight minutes against it while
     * never having been heard through. Summing would mark it complete on the strength of never
     * completing it. See [TrackBadge].
     *
     * Scoped to one album because that is the unit a tracklist shows, and because every badge
     * here is relative to its own record — the most-played track of an album, not of a library.
     */
    @Query(
        """
        SELECT trackId,
               COUNT(*)        AS plays,
               MAX(msPlayed)   AS furthestMs,
               MIN(startedAt)  AS firstStartedAt
        FROM PlayEvent
        WHERE albumId = :albumId
        GROUP BY trackId
        """
    )
    fun trackStatsForAlbum(albumId: String): Flow<List<TrackPlayStats>>

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
