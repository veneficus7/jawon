package com.jawon.app.data.importer

import android.net.Uri

/**
 * Plain read result for one scanned file — not the Room entity. IDs and
 * album association are assigned at persist time in [AlbumImportRepository].
 */
data class TrackMetadata(
    val fileUri: Uri,
    val displayName: String,
    val title: String,
    val album: String?,
    val artist: String?,
    val trackNumber: Int?,
    /** Which disc of a set, or null for a single-disc album. See Track.discNumber. */
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val mimeType: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val bitrateBps: Int
)
