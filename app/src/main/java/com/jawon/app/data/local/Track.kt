package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Track(
    @PrimaryKey val id: String,
    val albumId: String,
    val trackNumber: Int,
    val title: String,
    /**
     * This track's own credited artist, where the file carried one.
     *
     * Separate from the album's artist because on a compilation they are not the same thing —
     * a various-artists record, a guest feature, a soundtrack — and the deck should say who is
     * actually singing rather than who the shelf is filed under.
     *
     * Nullable, and that is a real state rather than a gap to paper over: plenty of files carry
     * no per-track credit, and every track imported before this column existed has none. The
     * deck falls back to the album's artist in that case.
     */
    val artist: String? = null,
    val durationMs: Long,
    val fileUri: String,
    // captured at import, drives the quality readout
    val mimeType: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val bitrateBps: Int
)
