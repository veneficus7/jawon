package com.jawon.app.ui.addalbum

import android.net.Uri
import com.jawon.app.data.importer.ImportPreview

sealed interface AddAlbumUiState {
    data object Idle : AddAlbumUiState
    data object Scanning : AddAlbumUiState
    data class Preview(
        val preview: ImportPreview,
        val title: String,
        val artist: String,
        val year: String,
        val caseCoverUri: Uri?,
        val discFaceUri: Uri?
    ) : AddAlbumUiState
    data object Saving : AddAlbumUiState
    data class Done(val albumId: String) : AddAlbumUiState
    data class Error(val message: String) : AddAlbumUiState
}
