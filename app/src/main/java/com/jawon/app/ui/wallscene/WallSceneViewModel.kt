package com.jawon.app.ui.wallscene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.AudioRouteReader
import com.jawon.app.data.playback.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playbackRepository: PlaybackRepository,
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

    /** Called when the open sequence lands — the disc is up and spinning. */
    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val tracks = trackDao.getForAlbum(albumId).first()
            if (tracks.isEmpty()) return@launch
            _nowPlayingAlbumId.value = albumId
            playbackRepository.playAlbum(tracks)
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
}
