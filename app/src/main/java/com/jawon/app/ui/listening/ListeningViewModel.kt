package com.jawon.app.ui.listening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.PlayEvent
import com.jawon.app.data.local.PlayEventDao
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import com.jawon.app.ui.wallscene.Patina
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
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

/** A line in one of the two plain leaderboards: an artist, or a track. */
data class TallyRow(
    val name: String,
    /** The artist, for a track row. Empty for an artist row, which is already a name. */
    val under: String,
    val totalMs: Long,
    val plays: Int,
    val relative: Float
)

data class ListeningState(
    val period: ListeningStats.Period = ListeningStats.Period.ALL_TIME,
    val rows: List<ListeningRow> = emptyList(),
    val artists: List<TallyRow> = emptyList(),
    val tracks: List<TallyRow> = emptyList(),
    val totalMs: Long = 0L,
    val albumsHeard: Int = 0,
    /** Listening per hour of the local day, 24 long. Drives the clock. */
    val byHour: LongArray = LongArray(24),
    val peakHour: Int? = null,
    val daysListened: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val loaded: Boolean = false
) {
    // A data class holding an array needs these spelled out — the generated pair compare the
    // array by identity, so two identical states would differ and the screen would recompose on
    // every emission of an unchanged record.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ListeningState) return false
        return period == other.period &&
            rows == other.rows &&
            artists == other.artists &&
            tracks == other.tracks &&
            totalMs == other.totalMs &&
            albumsHeard == other.albumsHeard &&
            byHour.contentEquals(other.byHour) &&
            peakHour == other.peakHour &&
            daysListened == other.daysListened &&
            currentStreak == other.currentStreak &&
            longestStreak == other.longestStreak &&
            loaded == other.loaded
    }

    override fun hashCode(): Int {
        var result = period.hashCode()
        result = 31 * result + rows.hashCode()
        result = 31 * result + artists.hashCode()
        result = 31 * result + tracks.hashCode()
        result = 31 * result + totalMs.hashCode()
        result = 31 * result + albumsHeard
        result = 31 * result + byHour.contentHashCode()
        result = 31 * result + (peakHour ?: -1)
        result = 31 * result + daysListened
        result = 31 * result + currentStreak
        result = 31 * result + longestStreak
        result = 31 * result + loaded.hashCode()
        return result
    }
}

/**
 * What the collection has actually been listened to.
 *
 * Everything is derived from `PlayEvent`; no new columns and no new writes. The arithmetic lives
 * in [ListeningStats], which is pure and tested — this only gathers what it needs and joins the
 * results back to titles.
 *
 * ## What changed, and the rule that did not
 *
 * This file used to say the record is deliberately *not* a leaderboard of tracks, because
 * `PLAN.md` §2 puts the album at the centre of the app. The owner asked for stats.fm-style
 * features and top tracks are most of what that means, so the rule is restated rather than
 * dropped: **the album is the unit you play; a track is something the record can report on.**
 * The album leaderboard is still first on the screen, and nothing here offers to play a single
 * track. The line is between a control and a report.
 *
 * ## Why the whole history is read at once
 *
 * One flow of every row, filtered per period in memory — see [PlayEventDao.watchAll]. That makes
 * the period selector instant, which is most of what the control is for, and it is what lets the
 * streak and the clock be computed from the same pass. The join and the arithmetic run on
 * [Dispatchers.Default] rather than the main thread, because a long history is a real amount of
 * grouping and this is a screen that opens on a tap.
 */
@HiltViewModel
class ListeningViewModel @Inject constructor(
    playEventDao: PlayEventDao,
    albumDao: AlbumDao,
    trackDao: TrackDao
) : ViewModel() {

    private val _period = MutableStateFlow(ListeningStats.Period.ALL_TIME)
    val period: StateFlow<ListeningStats.Period> = _period.asStateFlow()

    fun onPeriodChanged(period: ListeningStats.Period) {
        _period.value = period
    }

    /**
     * The device's zone, read once.
     *
     * Read here rather than deep in the arithmetic so [ListeningStats] stays pure and testable —
     * every date question in it takes the zone as a parameter, which is what let the histogram
     * and the streak be checked against a `+9` zone where a UTC implementation gives different
     * answers for every case.
     */
    private val zone: ZoneId = ZoneId.systemDefault()

    val state: StateFlow<ListeningState> =
        combine(
            playEventDao.watchAll(),
            albumDao.getAll(),
            trackDao.listAllFlow(),
            _period
        ) { events, albums, tracks, period ->
            build(events, albums, tracks, period)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningState())

    private fun build(
        events: List<PlayEvent>,
        albums: List<Album>,
        tracks: List<Track>,
        period: ListeningStats.Period
    ): ListeningState {
        val now = System.currentTimeMillis()
        val albumById = albums.associateBy { it.id }
        val trackById = tracks.associateBy { it.id }

        // An album can be deleted while its history is still in flight; the DAO clears history on
        // delete, but a row read mid-delete would otherwise have no title to show.
        val within = ListeningStats.within(events, period, now, zone)
            .filter { albumById.containsKey(it.albumId) }

        val albumTallies = ListeningStats.tally(within) { it.albumId }
        val longestAlbum = albumTallies.firstOrNull()?.totalMs ?: 0L

        val artistTallies = ListeningStats.tally(within) { albumById[it.albumId]?.artist }
        val longestArtist = artistTallies.firstOrNull()?.totalMs ?: 0L

        val trackTallies = ListeningStats.tally(within) { event ->
            // Keyed on the id, not the title: two albums can carry a song of the same name and
            // they are different recordings. The title is looked up afterwards for display.
            event.trackId.takeIf { trackById.containsKey(it) }
        }
        val longestTrack = trackTallies.firstOrNull()?.totalMs ?: 0L

        val hours = ListeningStats.byHour(within, zone)
        val days = ListeningStats.daysListened(within, zone)

        return ListeningState(
            period = period,
            rows = albumTallies.map { tally ->
                val album = albumById.getValue(tally.key)
                ListeningRow(
                    albumId = tally.key,
                    title = album.title,
                    artist = album.artist,
                    coverPath = album.caseCoverUri,
                    totalMs = tally.totalMs,
                    plays = tally.plays,
                    wear = Patina.forListening(tally.totalMs),
                    relative = share(tally.totalMs, longestAlbum)
                )
            },
            artists = artistTallies.take(TOP_N).map { tally ->
                TallyRow(
                    name = tally.key,
                    under = "",
                    totalMs = tally.totalMs,
                    plays = tally.plays,
                    relative = share(tally.totalMs, longestArtist)
                )
            },
            tracks = trackTallies.take(TOP_N).map { tally ->
                val track = trackById.getValue(tally.key)
                TallyRow(
                    name = track.title,
                    // The track's own credit if the file gave one, the album's otherwise — the
                    // same fallback the deck and the lyrics lookup use.
                    under = track.artist?.takeIf { it.isNotBlank() }
                        ?: albumById[track.albumId]?.artist.orEmpty(),
                    totalMs = tally.totalMs,
                    plays = tally.plays,
                    relative = share(tally.totalMs, longestTrack)
                )
            },
            totalMs = within.sumOf { it.msPlayed },
            albumsHeard = albumTallies.size,
            byHour = hours,
            peakHour = ListeningStats.peakHour(hours),
            daysListened = days.size,
            currentStreak = ListeningStats.currentStreak(
                days,
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            ),
            longestStreak = ListeningStats.longestStreak(days),
            loaded = true
        )
    }

    private fun share(value: Long, of: Long): Float =
        if (of <= 0L) 0f else (value.toFloat() / of).coerceIn(0f, 1f)

    private companion object {
        /** How many rows the two plain leaderboards show. Enough to be a list, short enough to read. */
        const val TOP_N = 10
    }
}
