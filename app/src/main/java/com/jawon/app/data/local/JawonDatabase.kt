package com.jawon.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Shelf::class, Album::class, Track::class],
    version = 1,
    exportSchema = false
)
abstract class JawonDatabase : RoomDatabase() {
    abstract fun shelfDao(): ShelfDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
}
