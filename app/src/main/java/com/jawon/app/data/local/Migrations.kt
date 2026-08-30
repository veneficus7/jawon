package com.jawon.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations, written by hand and applied in order.
 *
 * **There is deliberately no `fallbackToDestructiveMigration`.** A user's library is the work
 * they put in — the shelves they made, the covers they chose, the colours they picked — and
 * none of it can be recovered from their audio files. A missing migration should crash loudly
 * in testing, not wipe a stranger's collection quietly in the field.
 *
 * The SQL here has to match what Room generates for the entities *exactly*, down to column
 * order not mattering but affinity and nullability very much doing. Room validates the result
 * on first open and throws if it disagrees, so a mistake shows up immediately rather than as
 * corruption later. `exportSchema` is on (see `app/build.gradle.kts`) so the generated JSON in
 * `app/schemas` is the reference to check new migrations against.
 */
val MIGRATION_1_2 = Migration(1, 2) { db: SupportSQLiteDatabase ->
    // Resume position, per album. Defaults mean every existing record simply starts at the
    // beginning, which is the honest answer for albums played before anything was recorded.
    db.execSQL("ALTER TABLE Album ADD COLUMN lastTrackIndex INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE Album ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0")
    // Nullable: "never played" is a real state and 0 would be a lie about 1970.
    db.execSQL("ALTER TABLE Album ADD COLUMN lastPlayedAt INTEGER")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS PlayEvent (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            albumId TEXT NOT NULL,
            trackId TEXT NOT NULL,
            startedAt INTEGER NOT NULL,
            msPlayed INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_PlayEvent_albumId ON PlayEvent (albumId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_PlayEvent_startedAt ON PlayEvent (startedAt)")
}
