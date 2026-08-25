package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Track(
    @PrimaryKey val id: String,
    val albumId: String,
    val trackNumber: Int,
    val title: String,
    val durationMs: Long,
    val fileUri: String,
    // captured at import, drives the quality readout
    val mimeType: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val bitrateBps: Int
)
