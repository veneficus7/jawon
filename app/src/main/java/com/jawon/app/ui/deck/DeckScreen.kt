package com.jawon.app.ui.deck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.data.local.Track

@Composable
fun DeckScreen(
    onBack: () -> Unit,
    viewModel: DeckViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: playback works either way, notification just won't show if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(top = 24.dp)) {
                    val currentTrack = uiState.tracks.getOrNull(uiState.currentTrackIndex)
                    Text(
                        currentTrack?.title ?: uiState.album?.title ?: "Loading…",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(uiState.album?.artist.orEmpty())
                    if (currentTrack != null) {
                        Text(qualityReadoutLine1(currentTrack))
                        Text(qualityReadoutLine2(currentTrack, uiState.routeLabel, uiState.outputSampleRateHz))
                    }
                }
            }
            item {
                PositionSlider(
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    onSeek = viewModel::onSeek
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(onClick = viewModel::onPrevious) { Text("Prev") }
                    Button(onClick = viewModel::onPlayPause) { Text(if (uiState.isPlaying) "Pause" else "Play") }
                    Button(onClick = viewModel::onNext) { Text("Next") }
                }
            }
            itemsIndexed(uiState.tracks) { index, track ->
                TrackListRow(
                    track = track,
                    isCurrent = index == uiState.currentTrackIndex,
                    onClick = { viewModel.onSelectTrack(index) }
                )
            }
            item {
                Button(onClick = onBack, modifier = Modifier.padding(vertical = 16.dp)) { Text("Back") }
            }
        }
    }
}

@Composable
private fun PositionSlider(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val range = durationMs.coerceAtLeast(1L).toFloat()
    val displayed = if (isDragging) dragValue else positionMs.toFloat().coerceIn(0f, range)

    Column {
        Slider(
            value = displayed,
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragValue.toLong())
            },
            valueRange = 0f..range
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(displayed.toLong()))
            Text(formatDuration(durationMs))
        }
    }
}

@Composable
private fun TrackListRow(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (if (isCurrent) "▶ " else "") + track.title,
            modifier = Modifier.weight(1f)
        )
        Text(formatDuration(track.durationMs))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private val LOSSLESS_MIME_TYPES = setOf(
    "audio/flac", "audio/x-flac", "audio/alac", "audio/wav", "audio/x-wav", "audio/x-ms-wav", "audio/vnd.wave", "audio/raw"
)

private fun codecLabel(mimeType: String): String = when (mimeType) {
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/alac" -> "ALAC"
    "audio/wav", "audio/x-wav", "audio/x-ms-wav", "audio/vnd.wave" -> "WAV"
    "audio/mpeg" -> "MP3"
    "audio/mp4a-latm", "audio/mp4" -> "AAC"
    "audio/opus" -> "OPUS"
    "audio/ogg", "audio/vorbis" -> "OGG"
    else -> mimeType.substringAfter('/').uppercase()
}

private fun qualityReadoutLine1(track: Track): String {
    val bitDepthPart = track.bitDepth?.let { "$it-BIT / " } ?: ""
    val rateKhz = track.sampleRateHz / 1000f
    return "${codecLabel(track.mimeType)} $bitDepthPart${"%.1f".format(rateKhz)} kHz"
}

private fun qualityReadoutLine2(track: Track, routeLabel: String, outputSampleRateHz: Int): String {
    val losslessTag = if (track.mimeType in LOSSLESS_MIME_TYPES) "LOSSLESS" else "LOSSY"
    val resampled = outputSampleRateHz > 0 && outputSampleRateHz != track.sampleRateHz
    val outputPart = if (resampled) " → OUTPUT ${(outputSampleRateHz / 1000f).let { "%.0f".format(it) }} kHz" else ""
    return "$losslessTag · $routeLabel$outputPart"
}
