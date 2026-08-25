package com.jawon.app.ui.wallscene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Supplies the wall's *layout* only — which album sits at which row and column. Pan and
 * fling state deliberately do not live here: per ENGINEERING.md, high-frequency state must not
 * drive Compose recomposition, so the scene owns it and writes the camera directly.
 */
@HiltViewModel
class WallSceneViewModel @Inject constructor(
    shelfDao: ShelfDao,
    albumDao: AlbumDao
) : ViewModel() {

    val layout: StateFlow<WallLayout> =
        combine(shelfDao.getAll(), albumDao.getAll()) { shelves, albums ->
            val albumsByShelf = albums.groupBy { it.shelfId }
            WallLayout.from(
                shelves.map { shelf -> ShelfWithAlbums(shelf, albumsByShelf[shelf.id].orEmpty()) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallLayout())
}
