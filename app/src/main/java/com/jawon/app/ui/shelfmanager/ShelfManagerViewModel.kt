package com.jawon.app.ui.shelfmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.ui.wallscene.WallSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ShelfManagerViewModel @Inject constructor(
    private val shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val albumRemover: com.jawon.app.data.local.AlbumRemover,
    private val preferences: AppPreferences
) : ViewModel() {

    /**
     * How records are filed along a shelf.
     *
     * Lives here as well as in the wall's own ViewModel because both read the same
     * preference — this screen sets it, the room reacts to it, and neither owns a copy.
     */
    val sort: StateFlow<WallSort> = preferences.wallSort
        .map { WallSort.fromName(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallSort.SHELF)

    fun onSortChanged(value: WallSort) = preferences.setWallSort(value.name)

    // ---- selecting several records to remove ----------------------------------------------
    //
    // **The flip side of the import being good.** The device scan made adding a library
    // effortless and left removing one exactly as manual as it ever was — one album, one
    // dialog, one fracture animation — so the easy path now creates a problem only the slow
    // path can solve. Someone who scans a phone with two hundred records on it has no
    // practical way back out.
    //
    // Selection lives here rather than in the composable so it survives a rotation and a
    // process death mid-decision, which is exactly when losing forty ticks would hurt most.

    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    /** Album ids ticked for removal. Empty means the screen is in its ordinary browsing mode. */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** Whether the list is in selection mode at all — entered by a long press on any record. */
    val selecting: StateFlow<Boolean> = _selection
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onToggleSelected(albumId: String) {
        _selection.value = _selection.value.let {
            if (albumId in it) it - albumId else it + albumId
        }
    }

    fun onClearSelection() {
        _selection.value = emptySet()
    }

    /**
     * Removes everything ticked.
     *
     * **No discard animation, deliberately.** The fracture is a small ceremony for a deliberate
     * act, and running it forty times turns it into a wait — the user is not looking at the
     * wall here, they are on a list, having already decided.
     *
     * Goes through [com.jawon.app.data.local.AlbumRemover] rather than deleting rows directly,
     * so the tracks, the listening history and the copied cover images go with each record. The
     * room's own delete path uses the same object for the same reason.
     */
    fun onRemoveSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        _selection.value = emptySet()
        viewModelScope.launch { albumRemover.removeAll(ids) }
    }

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

    /**
     * Removes a shelf. The albums on it are **kept**, and land on another shelf.
     *
     * Deleting a shelf is a statement about how the library is organised, not about which
     * records are in it — nobody reaching for "remove this shelf" means "and destroy the
     * fourteen albums filed under it". So the records move to whichever shelf comes next,
     * which is the same thing that happens to books when a shelf comes off the wall.
     *
     * The last shelf cannot go: every album has to be somewhere, and a library with records
     * and no shelves has nowhere to put them. The UI hides the control rather than letting
     * this fail silently.
     */
    fun onDeleteShelf(shelfId: String) {
        val all = shelves.value
        if (all.size <= 1) return
        val doomed = all.firstOrNull { it.shelf.id == shelfId } ?: return
        // Prefer the shelf above, so albums drift up the wall rather than to an arbitrary
        // one; falling back to the shelf below covers deleting the topmost.
        val index = all.indexOf(doomed)
        val destination = (all.getOrNull(index - 1) ?: all.getOrNull(index + 1))?.shelf ?: return

        viewModelScope.launch {
            var slot = destination.let { dest ->
                all.first { it.shelf.id == dest.id }.albums.size
            }
            doomed.albums.forEach { album ->
                albumDao.insert(album.copy(shelfId = destination.id, slotIndex = slot))
                slot++
            }
            shelfDao.delete(doomed.shelf)
        }
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
