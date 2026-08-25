package com.jawon.app.ui.wall

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

@HiltViewModel
class WallViewModel @Inject constructor(
    shelfDao: ShelfDao,
    albumDao: AlbumDao
) : ViewModel() {

    val shelves: StateFlow<List<ShelfWithAlbums>> =
        combine(shelfDao.getAll(), albumDao.getAll()) { shelves, albums ->
            val albumsByShelf = albums.groupBy { it.shelfId }
            shelves.map { shelf -> ShelfWithAlbums(shelf, albumsByShelf[shelf.id].orEmpty()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
