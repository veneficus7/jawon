package com.jawon.app.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.ShelfWithAlbums
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallScreen(
    onAddAlbum: () -> Unit,
    onOpenShelfManager: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: WallViewModel = hiltViewModel()
) {
    val shelves by viewModel.shelves.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Javon", style = MaterialTheme.typography.displaySmall) },
                actions = {
                    IconButton(onClick = onAddAlbum) {
                        Icon(Icons.Default.Add, contentDescription = "Add Album")
                    }
                    IconButton(onClick = onOpenShelfManager) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Shelf Manager")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (shelves.isEmpty()) {
            WallEmptyState(onAddAlbum = onAddAlbum, modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(shelves, key = { it.shelf.id }) { shelfWithAlbums ->
                    ShelfSection(shelfWithAlbums = shelfWithAlbums, onOpenAlbum = onOpenAlbum)
                }
            }
        }
    }
}

@Composable
private fun WallEmptyState(onAddAlbum: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your shelves are waiting", style = MaterialTheme.typography.displaySmall)
        Text(
            "Nothing here yet. Add your first album and it'll get its own shelf.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onAddAlbum) { Text("Add Album") }
    }
}

@Composable
private fun ShelfSection(shelfWithAlbums: ShelfWithAlbums, onOpenAlbum: (String) -> Unit) {
    val shelf = shelfWithAlbums.shelf
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.hsv(shelf.accentHue * 360f, 0.6f, 0.7f))
            )
            Text(shelf.name, style = MaterialTheme.typography.displaySmall)
        }
        if (shelfWithAlbums.albums.isEmpty()) {
            Text(
                "No albums yet",
                modifier = Modifier.padding(start = 24.dp, top = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyRow(
                modifier = Modifier.padding(top = 12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shelfWithAlbums.albums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album.id) })
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(Color.hsv(album.spineHue * 360f, 0.45f, 0.35f))
        ) {
            val coverPath = album.caseCoverUri
            if (coverPath != null) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            album.title,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
