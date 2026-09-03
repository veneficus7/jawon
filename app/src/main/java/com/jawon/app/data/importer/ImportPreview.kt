package com.jawon.app.data.importer

import android.net.Uri

data class ImportPreview(
    val treeUri: Uri,
    val tracks: List<TrackMetadata>,
    val suggestedTitle: String,
    val suggestedArtist: String,
    val suggestedYear: Int?,
    val failedCount: Int,

    /**
     * Cover art lifted out of the tracks' own tags, if any of them carried it.
     *
     * The Add Album screen offers this as the cover already chosen, so the common case — a
     * ripped or downloaded album with art embedded in every file — costs the user nothing.
     * They can still replace it; it is a default, not a decision.
     *
     * Points at a file in the cache directory. `commit` copies whatever cover is finally
     * chosen into private storage, so this being cache-backed is fine: it only has to survive
     * the length of the import screen.
     */
    val embeddedCoverUri: Uri? = null,

    /**
     * True when the library already holds a record with this title and artist.
     *
     * **Only the scan used to check.** Duplicate detection lived entirely in the MediaStore
     * path, where it is unavoidable — every scan after the first re-offers the whole library —
     * and the folder picker had none at all. So picking a folder you had already imported, by
     * either route, silently produced a second copy of the album, and the two are impossible to
     * tell apart afterwards because a file's URI depends on how it was reached.
     *
     * It is a flag rather than a filter here, which is the difference between the two routes. A
     * scan is the app volunteering albums, so quietly skipping one it has already offered is
     * right. The folder picker is the user pointing at something specific and asking for it —
     * dropping that silently would look broken, and it is also the escape hatch for the case the
     * heuristic gets wrong, two different records sharing a name. So it is shown, said, and left
     * unticked.
     */
    val alreadyInLibrary: Boolean = false
)
