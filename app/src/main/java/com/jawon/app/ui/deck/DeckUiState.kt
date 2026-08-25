package com.jawon.app.ui.deck

import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Track

data class DeckUiState(
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    val currentTrackIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val routeLabel: String = "",
    val outputSampleRateHz: Int = 0
)
