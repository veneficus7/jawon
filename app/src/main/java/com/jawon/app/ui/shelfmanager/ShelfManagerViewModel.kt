package com.jawon.app.ui.shelfmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ShelfManagerViewModel @Inject constructor(
    private val shelfDao: ShelfDao,
    private val albumDao: AlbumDao
) : ViewModel() {

    val shelves: StateFlow<List<ShelfWithAlbums>> =
        combine(shelfDao.getAll(), albumDao.getAll()) { shelves, albums ->
            val albumsByShelf = albums.groupBy { it.shelfId }
            shelves.map { shelf -> ShelfWithAlbums(shelf, albumsByShelf[shelf.id].orEmpty()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onCreateShelf(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            shelfDao.insert(
                Shelf(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    order = shelves.value.size,
                    accentHue = 0f,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun onRename(shelfId: String, name: String) {
        val shelf = shelves.value.find { it.shelf.id == shelfId }?.shelf ?: return
        viewModelScope.launch { shelfDao.insert(shelf.copy(name = name)) }
    }

    fun onRecolour(shelfId: String, hue: Float) {
        val shelf = shelves.value.find { it.shelf.id == shelfId }?.shelf ?: return
        viewModelScope.launch { shelfDao.insert(shelf.copy(accentHue = hue)) }
    }

    fun onMoveUp(shelfId: String) = swapWithNeighbor(shelfId, offset = -1)

    fun onMoveDown(shelfId: String) = swapWithNeighbor(shelfId, offset = 1)

    private fun swapWithNeighbor(shelfId: String, offset: Int) {
        val ordered = shelves.value.map { it.shelf }
        val index = ordered.indexOfFirst { it.id == shelfId }
        val neighborIndex = index + offset
        if (index < 0 || neighborIndex !in ordered.indices) return
        val current = ordered[index]
        val neighbor = ordered[neighborIndex]
        viewModelScope.launch {
            shelfDao.insertAll(
                listOf(
                    current.copy(order = neighbor.order),
                    neighbor.copy(order = current.order)
                )
            )
        }
    }

}
