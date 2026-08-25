package com.jawon.app.data.importer

import android.net.Uri

data class ImportPreview(
    val treeUri: Uri,
    val tracks: List<TrackMetadata>,
    val suggestedTitle: String,
    val suggestedArtist: String,
    val suggestedYear: Int?,
    val failedCount: Int
)
