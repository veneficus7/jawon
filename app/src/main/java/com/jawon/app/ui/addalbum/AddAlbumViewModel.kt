package com.jawon.app.ui.addalbum

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.AlbumImportRepository
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
                val preview = repository.scanFolder(uri)
                AddAlbumUiState.Preview(
                    preview = preview,
                    title = preview.suggestedTitle,
                    artist = preview.suggestedArtist,
                    year = preview.suggestedYear?.toString().orEmpty(),
                    caseCoverUri = null,
                    discFaceUri = null
                )
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't scan that folder")
            }
        }
    }

    fun onTitleChanged(value: String) = updatePreview { it.copy(title = value) }
    fun onArtistChanged(value: String) = updatePreview { it.copy(artist = value) }
    fun onYearChanged(value: String) = updatePreview { it.copy(year = value) }
    fun onCaseCoverPicked(uri: Uri?) = updatePreview { it.copy(caseCoverUri = uri) }
    fun onDiscFacePicked(uri: Uri?) = updatePreview { it.copy(discFaceUri = uri) }

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
                    discFaceUri = current.discFaceUri
                )
                AddAlbumUiState.Done(albumId)
            } catch (e: Exception) {
                AddAlbumUiState.Error(e.message ?: "Couldn't save that album")
            }
        }
    }
}
