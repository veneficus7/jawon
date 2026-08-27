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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.jawon.app.data.importer.TrackMetadata
import com.jawon.app.ui.common.formatDuration
import com.jawon.app.ui.theme.JawonTextStyle

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
                    onSpineHueChanged = viewModel::onSpineHueChanged,
                    onSave = viewModel::onSave
                )
                is AddAlbumUiState.MultiPreview -> MultiPreviewContent(
                    state = state,
                    onToggle = viewModel::onToggleAlbum,
                    onImport = viewModel::onImportSelected
                )
                is AddAlbumUiState.DoneMany -> {
                    // Nothing to open — a batch has no single album to land on, so this
                    // returns to the room where the new records are now on the shelf.
                    LaunchedEffect(state) { onCancel() }
                }
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
        // Says what actually happens now. It used to read "a folder containing one album's
        // audio files", which was both a rule the user had to obey and a lie about the
        // scanner — it always recursed, it just merged everything it found into one album.
        Text("Pick a folder. One album, or a whole library — Jawon sorts it out.")
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

/**
 * What you get when the picked folder held more than one album.
 *
 * **No text fields, on purpose.** Someone importing a whole library is not going to type
 * forty titles, and the tags are almost always right — so this shows what was found, lets
 * them untick anything they do not want, and imports the rest in one press. Getting a title
 * wrong is recoverable; making the user do forty rounds of a form is not.
 */
@Composable
private fun MultiPreviewContent(
    state: AddAlbumUiState.MultiPreview,
    onToggle: (Int) -> Unit,
    onImport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${state.albums.size} albums found",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "Grouped by folder. Untick anything you'd rather not add.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(state.albums) { index, album ->
                val checked = index in state.selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(index) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(album.suggestedTitle, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${album.suggestedArtist} · ${album.tracks.size} tracks" +
                                if (album.embeddedCoverUri != null) " · cover found" else "",
                            style = JawonTextStyle.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Button(
            onClick = onImport,
            enabled = state.selectedCount > 0,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                if (state.selectedCount == 1) "Add 1 album"
                else "Add ${state.selectedCount} albums"
            )
        }
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
    onSpineHueChanged: (Float) -> Unit,
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
        item { UnderlightPicker(hue = state.spineHue, onHueChanged = onSpineHueChanged) }
        items(state.preview.tracks) { track -> TrackRow(track) }
        item {
            Button(onClick = onSave, modifier = Modifier.padding(vertical = 16.dp)) {
                Text("Save album")
            }
        }
    }
}

/**
 * The album's accent colour, which becomes the underlight it stands in on the shelf.
 *
 * **Off by default, and that is the point.** Jawon already takes the dominant hue from the
 * cover art, which is usually a better answer than anything picked off a slider and asks the
 * user for nothing — the same reasoning that ruled out asking for a third uploaded image.
 * This exists for the album where that guess is wrong, so it opens on "From the cover" and
 * only starts overriding once the strip is actually touched.
 */
@Composable
private fun UnderlightPicker(hue: Float, onHueChanged: (Float) -> Unit) {
    val chosen = hue != 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shelf underlight", style = MaterialTheme.typography.bodyLarge)
            if (chosen) {
                TextButton(onClick = { onHueChanged(0f) }) { Text("From the cover") }
            } else {
                Text(
                    "From the cover",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(
                    // The wheel itself, so the control shows what it does rather than
                    // needing a swatch beside it to explain.
                    Brush.horizontalGradient(
                        (0..6).map { Color.hsv(it * 60f % 360f, 0.62f, 0.92f) }
                    )
                )
        )
        Slider(
            value = if (chosen) hue else 0f,
            // Nudged off zero on first touch, because zero is the sentinel for "unset" and a
            // user dragging to the far left should still get red, not the cover's colour.
            onValueChange = { onHueChanged(it.coerceAtLeast(0.001f)) },
            valueRange = 0f..1f
        )
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
        Text(formatDuration(track.durationMs), style = JawonTextStyle.mono)
    }
}

