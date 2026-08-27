package com.jawon.app.ui.addalbum

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.AlbumImportRepository
import com.jawon.app.data.importer.ImportPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddAlbumViewModel @Inject constructor(
    private val repository: AlbumImportRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddAlbumUiState>(AddAlbumUiState.Idle)
    val uiState: StateFlow<AddAlbumUiState> = _uiState.asStateFlow()

    fun onFolderPicked(uri: Uri) {
        _uiState.value = AddAlbumUiState.Scanning
        viewModelScope.launch {
            _uiState.value = try {
                val found = repository.scanTree(uri)
                when {
                    found.isEmpty() ->
                        AddAlbumUiState.Error("No audio files in that folder")
            // More than one album under the picked folder — offer the batch, since
            // nobody is going to fill in a form forty times.
                    found.size > 1 -> AddAlbumUiState.MultiPreview(
                        albums = found,
                        selected = found.indices.toSet()
                    )
                    else -> singlePreview(found.first())
                }
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't scan that folder")
            }
        }
    }

    fun onToggleAlbum(index: Int) {
        val current = _uiState.value
        if (current !is AddAlbumUiState.MultiPreview) return
        _uiState.value = current.copy(
            selected = if (index in current.selected) current.selected - index
            else current.selected + index
        )
    }

    fun onImportSelected() {
        val current = _uiState.value
        if (current !is AddAlbumUiState.MultiPreview || current.selected.isEmpty()) return
        val chosen = current.selected.sorted().mapNotNull { current.albums.getOrNull(it) }
        _uiState.value = AddAlbumUiState.Saving
        viewModelScope.launch {
            _uiState.value = try {
                AddAlbumUiState.DoneMany(repository.commitAll(chosen), chosen.size)
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't import those albums")
            }
        }
    }

    private fun singlePreview(preview: ImportPreview): AddAlbumUiState.Preview =
        AddAlbumUiState.Preview(
            preview = preview,
            title = preview.suggestedTitle,
            artist = preview.suggestedArtist,
            year = preview.suggestedYear?.toString().orEmpty(),
            // Already filled in when the tracks carried their own art, which is the
            // common case for anything ripped or downloaded. The user can replace it;
            // they just no longer have to go and find it.
                    //
            // Only the case cover. The disc face is left unset on purpose — the disc
            // already falls back to the cover when it renders (see DiscArt.bakeFace),
            // so seeding it here would buy nothing and would destroy the distinction
            // between "no disc face" and "a disc face that happens to match".
            caseCoverUri = preview.embeddedCoverUri,
            discFaceUri = null,
            spineHue = 0f
        )

    fun onTitleChanged(value: String) = updatePreview { it.copy(title = value) }
    fun onArtistChanged(value: String) = updatePreview { it.copy(artist = value) }
    fun onYearChanged(value: String) = updatePreview { it.copy(year = value) }
    fun onCaseCoverPicked(uri: Uri?) = updatePreview { it.copy(caseCoverUri = uri) }
    fun onDiscFacePicked(uri: Uri?) = updatePreview { it.copy(discFaceUri = uri) }

    /** The album's accent, which becomes its underlight on the shelf. */
    fun onSpineHueChanged(value: Float) = updatePreview { it.copy(spineHue = value) }

    private fun updatePreview(transform: (AddAlbumUiState.Preview) -> AddAlbumUiState.Preview) {
        val current = _uiState.value
        if (current is AddAlbumUiState.Preview) _uiState.value = transform(current)
    }

    fun onSave() {
        val current = _uiState.value
        if (current !is AddAlbumUiState.Preview) return
        _uiState.value = AddAlbumUiState.Saving
        viewModelScope.launch {
            _uiState.value = try {
                val albumId = repository.commit(
                    preview = current.preview,
                    title = current.title,
                    artist = current.artist,
                    year = current.year.toIntOrNull(),
                    caseCoverUri = current.caseCoverUri,
                    discFaceUri = current.discFaceUri,
                    spineHue = current.spineHue
                )
                AddAlbumUiState.Done(albumId)
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't save that album")
            }
        }
    }
}
