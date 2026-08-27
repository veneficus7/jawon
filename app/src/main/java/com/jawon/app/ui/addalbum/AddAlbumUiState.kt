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
        val discFaceUri: Uri?,
        /** 0..1 round the wheel; 0f means "let the cover decide". */
        val spineHue: Float = 0f
    ) : AddAlbumUiState
    data object Saving : AddAlbumUiState
    data class Done(val albumId: String) : AddAlbumUiState
    data class Error(val message: String) : AddAlbumUiState
}
