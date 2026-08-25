package com.jawon.app.ui.addalbum

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.jawon.app.data.importer.TrackMetadata
import com.jawon.app.ui.theme.JavonTextStyle

@Composable
fun AddAlbumScreen(
    onDone: (albumId: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: AddAlbumViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onFolderPicked) }

    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AddAlbumUiState.Done) onDone(state.albumId)
    }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val state = uiState) {
                is AddAlbumUiState.Idle -> IdleContent(
                    onChooseFolder = { folderLauncher.launch(null) },
                    onCancel = onCancel
                )
                is AddAlbumUiState.Scanning -> CenteredMessage("Scanning folder…")
                is AddAlbumUiState.Preview -> PreviewContent(
                    state = state,
                    onTitleChanged = viewModel::onTitleChanged,
                    onArtistChanged = viewModel::onArtistChanged,
                    onYearChanged = viewModel::onYearChanged,
                    onCaseCoverPicked = viewModel::onCaseCoverPicked,
                    onDiscFacePicked = viewModel::onDiscFacePicked,
                    onSave = viewModel::onSave
                )
                is AddAlbumUiState.Saving -> CenteredMessage("Saving…")
                is AddAlbumUiState.Done -> Unit // handled by LaunchedEffect above
                is AddAlbumUiState.Error -> CenteredMessage("${state.message} — go back and try again")
            }
        }
    }
}

@Composable
private fun IdleContent(onChooseFolder: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Add Album", style = MaterialTheme.typography.displaySmall)
        Text("Pick a folder containing one album's audio files.")
        Button(onClick = onChooseFolder) { Text("Choose folder") }
        Button(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(text, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun PreviewContent(
    state: AddAlbumUiState.Preview,
    onTitleChanged: (String) -> Unit,
    onArtistChanged: (String) -> Unit,
    onYearChanged: (String) -> Unit,
    onCaseCoverPicked: (android.net.Uri?) -> Unit,
    onDiscFacePicked: (android.net.Uri?) -> Unit,
    onSave: () -> Unit
) {
    val caseCoverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> onCaseCoverPicked(uri) }
    val discFaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> onDiscFacePicked(uri) }
    val imageOnlyRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "${state.preview.tracks.size} tracks detected" +
                    if (state.preview.failedCount > 0) " · ${state.preview.failedCount} skipped" else "",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChanged,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.artist,
                onValueChange = onArtistChanged,
                label = { Text("Artist") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.year,
                onValueChange = onYearChanged,
                label = { Text("Year") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoverPicker(
                    label = "Case cover",
                    uri = state.caseCoverUri,
                    modifier = Modifier.weight(1f),
                    onPick = { caseCoverLauncher.launch(imageOnlyRequest) }
                )
                CoverPicker(
                    label = "Disc face",
                    uri = state.discFaceUri,
                    modifier = Modifier.weight(1f),
                    onPick = { discFaceLauncher.launch(imageOnlyRequest) }
                )
            }
        }
        items(state.preview.tracks) { track -> TrackRow(track) }
        item {
            Button(onClick = onSave, modifier = Modifier.padding(vertical = 16.dp)) {
                Text("Save album")
            }
        }
    }
}

@Composable
private fun CoverPicker(
    label: String,
    uri: android.net.Uri?,
    modifier: Modifier = Modifier,
    onPick: () -> Unit
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Tap to choose")
            }
        }
    }
}

@Composable
private fun TrackRow(track: TrackMetadata) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(track.title, modifier = Modifier.weight(1f))
        Text(formatDuration(track.durationMs), style = JavonTextStyle.mono)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
