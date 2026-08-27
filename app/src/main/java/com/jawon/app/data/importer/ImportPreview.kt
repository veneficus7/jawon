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
    val embeddedCoverUri: Uri? = null
)
