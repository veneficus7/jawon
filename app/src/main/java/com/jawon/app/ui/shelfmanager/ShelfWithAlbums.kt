package com.jawon.app.ui.shelfmanager

import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Shelf

data class ShelfWithAlbums(
    val shelf: Shelf,
    val albums: List<Album>
)
