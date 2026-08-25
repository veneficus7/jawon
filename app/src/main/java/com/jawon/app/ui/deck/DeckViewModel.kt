package com.jawon.app.ui.deck

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.AudioRouteReader
import com.jawon.app.data.playback.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeckViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playbackRepository: PlaybackRepository,
    audioRouteReader: AudioRouteReader
) : ViewModel() {

    private val albumId: String = checkNotNull(savedStateHandle["albumId"])
    private val route = audioRouteReader.currentRoute()

    val uiState: StateFlow<DeckUiState> = combine(
        albumDao.getById(albumId),
        trackDao.getForAlbum(albumId),
        playbackRepository.playback,
        playbackRepository.positionMs
    ) { album, tracks, playback, positionMs ->
        DeckUiState(
            album = album,
            tracks = tracks,
            currentTrackIndex = playback.currentIndex,
            isPlaying = playback.isPlaying,
            positionMs = positionMs,
            durationMs = playback.durationMs,
            routeLabel = route.routeLabel,
            outputSampleRateHz = route.outputSampleRateHz
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckUiState())

    init {
        viewModelScope.launch {
            val tracks = trackDao.getForAlbum(albumId).first()
            val trackIds = tracks.map { it.id }
            if (tracks.isNotEmpty() && playbackRepository.playback.value.queuedTrackIds != trackIds) {
                playbackRepository.playAlbum(tracks)
            }
        }
    }

    fun onPlayPause() = viewModelScope.launch { playbackRepository.playPause() }
    fun onNext() = viewModelScope.launch { playbackRepository.seekToNext() }
    fun onPrevious() = viewModelScope.launch { playbackRepository.seekToPrevious() }
    fun onSeek(positionMs: Long) = viewModelScope.launch { playbackRepository.seekTo(positionMs) }
    fun onSelectTrack(index: Int) = viewModelScope.launch { playbackRepository.seekToTrack(index) }
}
