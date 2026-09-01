package com.jawon.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Shelf::class, Album::class, Track::class, PlayEvent::class],
    version = 3,
    // On, so every schema version is committed as JSON under app/schemas and a hand-written
    // migration can be checked against what Room actually expects rather than against memory.
    exportSchema = true
)
abstract class JawonDatabase : RoomDatabase() {
    abstract fun shelfDao(): ShelfDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun playEventDao(): PlayEventDao
}
