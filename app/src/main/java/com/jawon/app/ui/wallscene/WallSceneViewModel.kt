package com.jawon.app.ui.wallscene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Supplies the wall's *layout* — which album sits at which row and column — and drives
 * playback for the case the user opens. Pan, fling and the open sequence deliberately do
 * not live here: per ENGINEERING.md, high-frequency state must not drive Compose recomposition,
 * so the scene owns it and writes the nodes directly.
 */
@HiltViewModel
class WallSceneViewModel @Inject constructor(
    shelfDao: ShelfDao,
    albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    val layout: StateFlow<WallLayout> =
        combine(shelfDao.getAll(), albumDao.getAll()) { shelves, albums ->
            val albumsByShelf = albums.groupBy { it.shelfId }
            WallLayout.from(
                shelves.map { shelf -> ShelfWithAlbums(shelf, albumsByShelf[shelf.id].orEmpty()) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallLayout())

    /** Drives the disc's spin, so it turns because music is playing rather than decoratively. */
    val isPlaying: StateFlow<Boolean> = playbackRepository.playback
        .map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Called when the open sequence lands — the disc is up and spinning. */
    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val tracks = trackDao.getForAlbum(albumId).first()
            if (tracks.isNotEmpty()) playbackRepository.playAlbum(tracks)
        }
    }

    /** Called when the case closes. */
    fun stop() {
        viewModelScope.launch {
            if (playbackRepository.playback.value.isPlaying) playbackRepository.playPause()
        }
    }
}
