package com.jawon.app.ui.wall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WallScreen(
    onAddAlbum: () -> Unit,
    onOpenDeck: () -> Unit,
    onOpenShelfManager: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("The Wall", style = MaterialTheme.typography.bodyLarge)
            Text("No shelves yet — tap Add Album to import one.")
            Button(onClick = onAddAlbum) { Text("Add Album") }
            Button(onClick = onOpenDeck) { Text("Deck (placeholder)") }
            Button(onClick = onOpenShelfManager) { Text("Shelf Manager (placeholder)") }
            Button(onClick = onOpenSettings) { Text("Settings (placeholder)") }
        }
    }
}
