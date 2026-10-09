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
    /**
     * The picked folder held more than one album.
     *
     * A separate state from [Preview] rather than a list of them, because the two ask
     * different things of the user. Adding one record, they often want to correct its title;
     * adding forty, they want to press one button. So this offers a checklist and no text
     * fields — anything the tags got wrong is fixable on the album afterwards.
     */
    data class MultiPreview(
        /** The albums first, then the singles — see [singlesStart] and `ScanTidy.albumsThenSingles`. */
        val albums: List<ImportPreview>,
        val selected: Set<Int>,
        /**
         * Where the singles begin in [albums] (0.7.4): records of one or two tracks, listed under
         * the albums in a section of their own so they no longer crowd out the real records.
         * [albums]' size when there are none.
         */
        val singlesStart: Int = albums.size,
        /**
         * How many of the *ticked* albums have no artwork of their own and would have their
         * cover looked up online.
         *
         * Shown on the screen before the import runs, so the app's only network call is
         * something the user is told about while they can still change their mind — rather
         * than something they find out about from the manifest.
         */
        val coversToLookUp: Int = 0
    ) : AddAlbumUiState {
        val selectedCount: Int get() = selected.size
        val albumCount: Int get() = singlesStart
        val singleCount: Int get() = albums.size - singlesStart
    }

    data object Saving : AddAlbumUiState
    data class Done(val albumId: String) : AddAlbumUiState

    /** Finished a batch. [imported] can be short of what was asked if a folder failed. */
    data class DoneMany(val imported: Int, val requested: Int) : AddAlbumUiState
    data class Error(val message: String) : AddAlbumUiState
}
