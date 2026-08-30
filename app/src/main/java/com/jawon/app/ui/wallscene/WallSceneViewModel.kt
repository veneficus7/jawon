package com.jawon.app.ui.wallscene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.CoverImageStore
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.AudioRouteReader
import com.jawon.app.data.playback.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the in-room deck needs to draw itself. */
data class DeckState(
    val tracks: List<Track> = emptyList(),
    val currentTrackIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val routeLabel: String = "",
    val outputSampleRateHz: Int = 0
) {
    val currentTrack: Track? get() = tracks.getOrNull(currentTrackIndex)
}

/**
 * Supplies the wall's *layout* and the deck's state, and owns playback for whichever case is
 * open. Pan, fling and the open sequence deliberately do not live here: per ENGINEERING.md,
 * high-frequency state must not drive Compose recomposition, so the scene owns it and writes
 * the nodes directly.
 */
@HiltViewModel
class WallSceneViewModel @Inject constructor(
    shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playbackRepository: PlaybackRepository,
    private val coverStore: CoverImageStore,
    audioRouteReader: AudioRouteReader
) : ViewModel() {

    private val route = audioRouteReader.currentRoute()

    val layout: StateFlow<WallLayout> =
        combine(shelfDao.getAll(), albumDao.getAll()) { shelves, albums ->
            val albumsByShelf = albums.groupBy { it.shelfId }
            WallLayout.from(
                shelves.map { shelf -> ShelfWithAlbums(shelf, albumsByShelf[shelf.id].orEmpty()) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallLayout())

    /** The album whose case should sit proud of its slot (PLAN.md §9 #3). */
    private val _nowPlayingAlbumId = MutableStateFlow<String?>(null)
    val nowPlayingAlbumId: StateFlow<String?> = _nowPlayingAlbumId.asStateFlow()

    /** Read only by the progress bar, so the 2Hz tick can't recompose the tracklist. */
    val positionMs: StateFlow<Long> = playbackRepository.positionMs

    @Suppress("OPT_IN_USAGE")
    val deck: StateFlow<DeckState> = _nowPlayingAlbumId
        .flatMapLatest { albumId ->
            if (albumId == null) flowOf(emptyList()) else trackDao.getForAlbum(albumId)
        }
        .combine(playbackRepository.playback) { tracks, playback ->
            DeckState(
                tracks = tracks,
                currentTrackIndex = playback.currentIndex,
                isPlaying = playback.isPlaying,
                durationMs = playback.durationMs,
                routeLabel = route.routeLabel,
                outputSampleRateHz = route.outputSampleRateHz
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckState())

    /**
     * Takes an album off the shelf.
     *
     * **The user's audio files are never touched, and the confirmation says so.** They live
     * in the user's own folders and Jawon holds only a read grant on them; an album is a
     * record *about* those files. What does get deleted is what Jawon made: the row, its
     * tracks, and the cover and disc images copied into private storage at import — leaving
     * those behind would slowly fill the phone with orphans nothing can reach.
     *
     * Stops playback first if this is the album playing, so the deck is not left driving a
     * queue whose album no longer exists.
     */
    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            if (_nowPlayingAlbumId.value == albumId) stop()
            val album = albumDao.findById(albumId)
            trackDao.deleteForAlbum(albumId)
            albumDao.deleteById(albumId)
            album?.let { coverStore.deleteAll(it.caseCoverUri, it.discFaceUri) }
        }
    }

    /** Outstanding [scheduleDiscard] timers, so [skipDiscard] can cut one short. */
    private val discardJobs = mutableMapOf<String, Job>()

    /**
     * Deletes [albumId] after [afterMs] rather than immediately — this is what lets the wall
     * play [CaseDiscard] to completion on the shelf before the album disappears from [layout].
     *
     * **On [viewModelScope], deliberately not on a scope owned by the composable that calls
     * it.** A `rememberCoroutineScope()` in `WallSceneScreen` is cancelled the moment that
     * composable leaves composition — which a confirmed deletion must survive. The room is the
     * app's start destination, but a user can still background the app or open Settings in the
     * roughly one second between confirming and this actually running, and the album has to
     * come off the shelf either way. `viewModelScope` lives with the ViewModel, not the screen.
     */
    fun scheduleDiscard(albumId: String, afterMs: Long) {
        discardJobs[albumId]?.cancel()
        discardJobs[albumId] = viewModelScope.launch {
            delay(afterMs)
            deleteAlbum(albumId)
            discardJobs.remove(albumId)
        }
    }

    /** A second tap on a case already breaking apart — finish now instead of waiting it out. */
    fun skipDiscard(albumId: String) {
        discardJobs.remove(albumId)?.cancel()
        deleteAlbum(albumId)
    }

    /**
     * Called when the open sequence lands — the disc is up and spinning.
     *
     * Picks up where the record was left. A CD player that forgets its place is one nobody
     * trusts, and this room is pretending to be a physical object: take a record off, put it
     * back on, and it should be where you left it.
     *
     * The saved position is dropped when it lands in the last few seconds of the final track,
     * because "resuming" an album you finished means starting it again, not hearing its last
     * breath and stopping.
     */
    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val tracks = trackDao.getForAlbum(albumId).first()
            if (tracks.isEmpty()) return@launch
            _nowPlayingAlbumId.value = albumId

            val album = albumDao.findById(albumId)
            val index = album?.lastTrackIndex?.coerceIn(0, tracks.lastIndex) ?: 0
            val position = album?.lastPositionMs ?: 0L
            val finished = index == tracks.lastIndex &&
                position > 0L &&
                position >= tracks[index].durationMs - FINISHED_TAIL_MS

            if (finished) {
                playbackRepository.playAlbum(tracks)
            } else {
                playbackRepository.playAlbum(tracks, startIndex = index, startPositionMs = position)
            }
        }
    }

    /** Called when the case closes. */
    fun stop() {
        viewModelScope.launch {
            if (playbackRepository.playback.value.isPlaying) playbackRepository.playPause()
            _nowPlayingAlbumId.value = null
        }
    }

    fun onPlayPause() = viewModelScope.launch { playbackRepository.playPause() }

    /**
     * Track titles for one album, for printing on its case back. One shot, off the main
     * thread — the wall bakes each back once and keeps the bitmap.
     */
    suspend fun trackTitles(albumId: String): List<String> = trackDao.titlesForAlbum(albumId)

    /** Explicit rather than a toggle — see `PlaybackRepository.setPlaying`. */
    fun setPlaying(playing: Boolean) =
        viewModelScope.launch { playbackRepository.setPlaying(playing) }
    fun onNext() = viewModelScope.launch { playbackRepository.seekToNext() }
    fun onPrevious() = viewModelScope.launch { playbackRepository.seekToPrevious() }
    fun onSeek(positionMs: Long) = viewModelScope.launch { playbackRepository.seekTo(positionMs) }
    fun onSelectTrack(index: Int) = viewModelScope.launch { playbackRepository.seekToTrack(index) }

    /**
     * Where the disc scratch has dragged playback to. Called at the scratch's throttled rate
     * rather than per touch move — see [Scratch.SEEK_THROTTLE_MS].
     */
    fun onScrub(trackIndex: Int, positionMs: Long) =
        viewModelScope.launch { playbackRepository.seekTo(trackIndex, positionMs) }

    private companion object {
        /** Within this of the end of the last track, the album counts as finished. */
        const val FINISHED_TAIL_MS = 5_000L
    }
}
