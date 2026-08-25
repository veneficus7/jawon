package com.jawon.app.data.playback

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = -1,
    val itemCount: Int = 0,
    val durationMs: Long = 0L,
    val queuedTrackIds: List<String> = emptyList()
)
