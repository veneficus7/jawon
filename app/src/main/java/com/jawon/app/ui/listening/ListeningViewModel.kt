package com.jawon.app.ui.listening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.PlayEventDao
import com.jawon.app.ui.wallscene.Patina
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One album's place in the listening record. */
data class ListeningRow(
    val albumId: String,
    val title: String,
    val artist: String,
    val coverPath: String?,
    val totalMs: Long,
    val plays: Int,
    /** The same value the wall wears the case by, so the two can never disagree. */
    val wear: Float,
    /** Share of the longest-listened album, `0f`..`1f` — the bar's length. */
    val relative: Float
)

data class ListeningState(
    val rows: List<ListeningRow> = emptyList(),
    val totalMs: Long = 0L,
    val albumsHeard: Int = 0,
    val loaded: Boolean = false
)

/**
 * What the collection has actually been listened to.
 *
 * Reads `PlayEventDao.albumTotals()` — the query that has been written, documented and
 * called by nothing since the resume work went in. Everything here is derived; no new
 * columns and no new writes.
 *
 * The listening record is deliberately *not* a leaderboard of tracks. `PLAN.md` §2 puts the
 * album at the centre of this app, and a screen ranking individual songs would be the first
 * place that stopped being true.
 */
@HiltViewModel
class ListeningViewModel @Inject constructor(
    playEventDao: PlayEventDao,
    albumDao: AlbumDao
) : ViewModel() {

    val state: StateFlow<ListeningState> =
        combine(playEventDao.albumTotals(), albumDao.getAll()) { totals, albums ->
            val byId = albums.associateBy { it.id }
            // An album can be deleted while its history survives the session; the DAO clears
            // history on delete, but a row read mid-flight would otherwise have no title.
            val rows = totals
                .filter { it.totalMs > 0L && byId.containsKey(it.albumId) }
            val longest = rows.maxOfOrNull { it.totalMs } ?: 0L

            ListeningState(
                rows = rows.map { total ->
                    val album = byId.getValue(total.albumId)
                    ListeningRow(
                        albumId = total.albumId,
                        title = album.title,
                        artist = album.artist,
                        coverPath = album.caseCoverUri,
                        totalMs = total.totalMs,
                        plays = total.plays,
                        wear = Patina.forListening(total.totalMs),
                        relative = if (longest <= 0L) 0f
                        else (total.totalMs.toFloat() / longest).coerceIn(0f, 1f)
                    )
                },
                totalMs = rows.sumOf { it.totalMs },
                albumsHeard = rows.size,
                loaded = true
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningState())
}
