package com.jawon.app.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.archive.ArchiveAlbum
import com.jawon.app.data.archive.ArchiveClient
import com.jawon.app.data.archive.ArchiveCollection
import com.jawon.app.data.archive.ArchiveRepository
import com.jawon.app.data.archive.ArchiveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the shop is showing. */
data class RecordShopState(
    val collection: ArchiveCollection = ArchiveCollection.LIVE_MUSIC,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<ArchiveResult> = emptyList(),
    /** Identifiers already on a shelf, so the shop can say so rather than offering them twice. */
    val owned: Set<String> = emptySet(),
    /** The item being examined, once its metadata has confirmed it is playable. */
    val opened: ArchiveAlbum? = null,
    val opening: String? = null,
    val message: String? = null
)

@HiltViewModel
class RecordShopViewModel @Inject constructor(
    private val client: ArchiveClient,
    private val repository: ArchiveRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RecordShopState())
    val state: StateFlow<RecordShopState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshOwned()
        search()
    }

    /**
     * Types are debounced rather than searched.
     *
     * Every keystroke is otherwise a request to archive.org, which is both rude to a service
     * that asks nothing of us and visibly worse for the user — results flickering through
     * partial words. Three hundred milliseconds is about the gap between typing and pausing.
     */
    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            search()
        }
    }

    fun onCollectionChanged(collection: ArchiveCollection) {
        _state.value = _state.value.copy(collection = collection)
        search()
    }

    private fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val current = _state.value
            _state.value = current.copy(searching = true, message = null)
            val results = client.search(current.collection, current.query)
            _state.value = _state.value.copy(
                searching = false,
                results = results,
                message = if (results.isEmpty()) "Nothing found. Try fewer words." else null
            )
        }
    }

    /**
     * Fetches one item's real contents.
     *
     * **This is where a result becomes a record, or is revealed not to be one.** The search
     * index cannot say whether an item holds streamable tracks; a good share of netlabel
     * releases are a single ZIP. Null here means exactly that, and the message says so rather
     * than leaving a tap that appears to do nothing.
     */
    fun onOpen(result: ArchiveResult) {
        viewModelScope.launch {
            _state.value = _state.value.copy(opening = result.identifier, message = null)
            val album = client.album(result.identifier)
            _state.value = _state.value.copy(
                opening = null,
                opened = album,
                message = if (album == null) {
                    "That one has no playable tracks — it is packaged as a download."
                } else {
                    null
                }
            )
        }
    }

    fun onCloseOpened() {
        _state.value = _state.value.copy(opened = null)
    }

    /** Takes the open record home. Returns to the shop with it marked as owned. */
    fun onTakeHome(album: ArchiveAlbum) {
        viewModelScope.launch {
            repository.addToLibrary(album)
            _state.value = _state.value.copy(
                opened = null,
                owned = _state.value.owned + album.identifier,
                message = "${album.title} is on your wall."
            )
        }
    }

    private fun refreshOwned() {
        viewModelScope.launch {
            _state.value = _state.value.copy(owned = repository.ownedIdentifiers())
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
