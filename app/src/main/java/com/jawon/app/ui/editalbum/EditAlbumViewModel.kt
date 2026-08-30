package com.jawon.app.ui.editalbum

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.CoverArtFetcher
import com.jawon.app.data.importer.CoverImageStore
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The form's state.
 *
 * Held as one immutable value rather than a bag of `mutableStateOf`s in the composable, so
 * that leaving the screen and coming back cannot half-apply an edit, and so the save path has
 * a single object to read.
 */
data class EditAlbumUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val year: String = "",
    /** Already on disk, from a previous import or edit. Shown until a new one is picked. */
    val caseCoverPath: String? = null,
    val discFacePath: String? = null,
    /** Picked this session and not yet saved — these win in the preview. */
    val pendingCaseCover: Uri? = null,
    val pendingDiscFace: Uri? = null,
    /** `0f` means "take it from the cover", the same sentinel the importer uses. */
    val spineHue: Float = 0f,
    val shelfId: String = "",
    val shelves: List<Shelf> = emptyList(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** A cover lookup is in flight. */
    val findingCover: Boolean = false,
    /** Set when a lookup came back empty, so the button can say so instead of doing nothing. */
    val coverLookupFailed: Boolean = false
) {
    val shelfName: String get() = shelves.firstOrNull { it.id == shelfId }?.name.orEmpty()
}

/**
 * Editing an album that is already on a shelf.
 *
 * Everything the importer asks for at add time is editable here, plus which shelf the record
 * lives on. Two of the tester requests land on this screen: *"when the app automatically picks
 * albums itself, there is no customization"* — a batch import takes the tags as found, and
 * this is where they get fixed — and *"after uploading the albums, there should also be some
 * way to edit it, like the cover art, underlight color and etc."*
 *
 * The audio is never touched. Jawon holds a read-only grant on the user's files and this
 * screen edits the record *about* them, exactly as the removal dialog promises.
 */
@HiltViewModel
class EditAlbumViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val shelfDao: ShelfDao,
    private val coverStore: CoverImageStore,
    private val coverArtFetcher: CoverArtFetcher,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val albumId: String = savedStateHandle["albumId"] ?: ""

    private val _uiState = MutableStateFlow(EditAlbumUiState())
    val uiState: StateFlow<EditAlbumUiState> = _uiState.asStateFlow()

    /** The row as it was when the screen opened — what the save path diffs against. */
    private var original: Album? = null

    init {
        viewModelScope.launch {
            val album = albumDao.findById(albumId)
            // One-shot, not a Flow. Collecting would let a write from anywhere else in the
            // app overwrite whatever the user is halfway through typing.
            if (album == null) {
                _uiState.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            original = album
            val shelves = shelfDao.getAll().first()
            _uiState.update {
                it.copy(
                    loading = false,
                    title = album.title,
                    artist = album.artist,
                    year = album.year?.toString().orEmpty(),
                    caseCoverPath = album.caseCoverUri,
                    discFacePath = album.discFaceUri,
                    spineHue = album.spineHue,
                    shelfId = album.shelfId,
                    shelves = shelves
                )
            }
        }
    }

    fun onTitleChanged(value: String) = _uiState.update { it.copy(title = value) }

    fun onArtistChanged(value: String) = _uiState.update { it.copy(artist = value) }

    /** Digits only, and at most four of them — anything else is not a year. */
    fun onYearChanged(value: String) = _uiState.update {
        it.copy(year = value.filter(Char::isDigit).take(4))
    }

    fun onCaseCoverPicked(uri: Uri?) {
        if (uri == null) return
        _uiState.update { it.copy(pendingCaseCover = uri) }
    }

    fun onDiscFacePicked(uri: Uri?) {
        if (uri == null) return
        _uiState.update { it.copy(pendingDiscFace = uri) }
    }

    /**
     * Look this one album's cover up online, on request.
     *
     * The import does this automatically for albums with no artwork, but a lookup can come
     * back empty — the tags may be wrong, or the record may simply not be in MusicBrainz. This
     * is the retry: correct the title and artist above, then ask again. It uses whatever is
     * currently typed in the form rather than what is saved, which is the whole point.
     *
     * Deliberately a button rather than something that happens on opening the screen. Editing
     * an album should not make a network request on its own.
     */
    fun onFindCoverArt() {
        val state = _uiState.value
        if (state.findingCover) return
        _uiState.update { it.copy(findingCover = true, coverLookupFailed = false) }
        viewModelScope.launch {
            val found = coverArtFetcher.fetch(context, state.title.trim(), state.artist.trim())
            _uiState.update {
                it.copy(
                    findingCover = false,
                    coverLookupFailed = found == null,
                    pendingCaseCover = found ?: it.pendingCaseCover
                )
            }
        }
    }

    fun onHueChanged(value: Float) = _uiState.update { it.copy(spineHue = value) }

    /** Back to taking the underlight from the cover art. */
    fun onHueCleared() = _uiState.update { it.copy(spineHue = 0f) }

    fun onShelfChanged(shelfId: String) = _uiState.update { it.copy(shelfId = shelfId) }

    fun onSave() {
        val album = original ?: return
        val state = _uiState.value
        if (state.saving) return
        _uiState.update { it.copy(saving = true) }

        viewModelScope.launch {
            // A new file name per save, rather than overwriting `<id>-cover.jpg` in place.
            //
            // The path is what every cache in the app keys on — Coil's, and the wall's own
            // CaseArtCache signature — so writing new bytes under the old name would leave
            // the album showing its previous artwork until something evicted it. A path that
            // changes when the picture changes makes that impossible to get wrong.
            val stamp = System.currentTimeMillis()
            val newCoverPath = state.pendingCaseCover?.let {
                coverStore.store(context, it, "$albumId-cover-$stamp.jpg")
            }
            val newDiscPath = state.pendingDiscFace?.let {
                coverStore.store(context, it, "$albumId-disc-$stamp.jpg")
            }

            // Moving shelves puts the record at the end of its new one, which is where a
            // record you just filed by hand would be.
            val movedShelf = state.shelfId != album.shelfId
            val slotIndex = if (movedShelf) {
                albumDao.getForShelf(state.shelfId).first().size
            } else {
                album.slotIndex
            }

            albumDao.insert(
                album.copy(
                    title = state.title.trim().ifEmpty { album.title },
                    artist = state.artist.trim().ifEmpty { album.artist },
                    year = state.year.toIntOrNull(),
                    caseCoverUri = newCoverPath ?: album.caseCoverUri,
                    discFaceUri = newDiscPath ?: album.discFaceUri,
                    spineHue = state.spineHue,
                    shelfId = state.shelfId,
                    slotIndex = slotIndex
                )
            )

            // Only after the row points at the new files, so a crash mid-save leaves an
            // album with its old artwork rather than an album with none.
            if (newCoverPath != null) coverStore.deleteAll(album.caseCoverUri)
            if (newDiscPath != null) coverStore.deleteAll(album.discFaceUri)

            _uiState.update { it.copy(saving = false, saved = true) }
        }
    }
}
