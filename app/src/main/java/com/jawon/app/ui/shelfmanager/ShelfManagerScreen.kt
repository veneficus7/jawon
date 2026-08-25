package com.jawon.app.ui.shelfmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Shelf

@Composable
fun ShelfManagerScreen(
    onBack: () -> Unit,
    onPlayAlbum: (Album) -> Unit,
    viewModel: ShelfManagerViewModel = hiltViewModel()
) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Shelf Manager",
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            item { NewShelfRow(onCreate = viewModel::onCreateShelf) }
            if (shelves.isEmpty()) {
                item { Text("No shelves yet — create one above.") }
            } else {
                items(shelves, key = { it.shelf.id }) { shelfWithAlbums ->
                    val index = shelves.indexOf(shelfWithAlbums)
                    ShelfCard(
                        shelfWithAlbums = shelfWithAlbums,
                        otherShelves = shelves.map { it.shelf }.filter { it.id != shelfWithAlbums.shelf.id },
                        isFirst = index == 0,
                        isLast = index == shelves.lastIndex,
                        onRename = viewModel::onRename,
                        onRecolour = viewModel::onRecolour,
                        onMoveUp = viewModel::onMoveUp,
                        onMoveDown = viewModel::onMoveDown,
                        onMoveAlbum = viewModel::onMoveAlbum,
                        onPlayAlbum = onPlayAlbum
                    )
                }
            }
            item { Button(onClick = onBack, modifier = Modifier.padding(vertical = 16.dp)) { Text("Back") } }
        }
    }
}

@Composable
private fun NewShelfRow(onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("New shelf name") },
            modifier = Modifier.weight(1f)
        )
        Button(onClick = {
            onCreate(name)
            name = ""
        }) { Text("Create") }
    }
}

@Composable
private fun ShelfCard(
    shelfWithAlbums: ShelfWithAlbums,
    otherShelves: List<Shelf>,
    isFirst: Boolean,
    isLast: Boolean,
    onRename: (String, String) -> Unit,
    onRecolour: (String, Float) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onMoveAlbum: (Album, String) -> Unit,
    onPlayAlbum: (Album) -> Unit
) {
    val shelf = shelfWithAlbums.shelf
    var name by remember(shelf.id) { mutableStateOf(shelf.name) }
    var hue by remember(shelf.id) { mutableFloatStateOf(shelf.accentHue) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onRename(shelf.id, it)
                },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { onMoveUp(shelf.id) }, enabled = !isFirst) { Text("Up") }
            Button(onClick = { onMoveDown(shelf.id) }, enabled = !isLast) { Text("Down") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.hsv(hue * 360f, 0.6f, 0.7f))
            )
            Slider(
                value = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = { onRecolour(shelf.id, hue) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
        }
        if (shelfWithAlbums.albums.isEmpty()) {
            Text("No albums yet", style = MaterialTheme.typography.bodyLarge)
        } else {
            shelfWithAlbums.albums.forEach { album ->
                AlbumRow(
                    album = album,
                    otherShelves = otherShelves,
                    onMoveAlbum = onMoveAlbum,
                    onPlayAlbum = onPlayAlbum
                )
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: Album,
    otherShelves: List<Shelf>,
    onMoveAlbum: (Album, String) -> Unit,
    onPlayAlbum: (Album) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(album.title, modifier = Modifier.weight(1f))
        Button(onClick = { onPlayAlbum(album) }) { Text("Play") }
        Box {
            Button(onClick = { menuOpen = true }, enabled = otherShelves.isNotEmpty()) { Text("Move") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                otherShelves.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.name) },
                        onClick = {
                            onMoveAlbum(album, target.id)
                            menuOpen = false
                        }
                    )
                }
            }
        }
    }
}
