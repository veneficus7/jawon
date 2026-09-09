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

val MIGRATION_2_3 = Migration(2, 3) { db: SupportSQLiteDatabase ->
    // The track's own credited artist, which a compilation needs and the album's artist cannot
    // stand in for. Nullable with no default: a library imported before this column existed has
    // never read the per-track tag, and inventing the album's artist for every one of those
    // rows would make a guess indistinguishable from something the file actually said. Null
    // means "not known", and the deck falls back to the album for exactly those.
    db.execSQL("ALTER TABLE Track ADD COLUMN artist TEXT")
}

val MIGRATION_3_4 = Migration(3, 4) { db: SupportSQLiteDatabase ->
    // Lyrics fetched from LRCLIB, kept so a track is only ever looked up once. Nullable, and
    // null means "not looked up" rather than "has none" — the two need to stay distinguishable
    // or a track with genuinely no lyrics would be asked for again on every visit.
    //
    // Only *fetched* words live here. Embedded tags and `.lrc` sidecars are already on the
    // device and are read straight from it; caching a copy would just be a second thing to
    // keep in step with the file.
    db.execSQL("ALTER TABLE Track ADD COLUMN fetchedLyrics TEXT")
}

val MIGRATION_4_5 = Migration(4, 5) { db: SupportSQLiteDatabase ->
    // Which disc of a set a track came from. Nullable with no default, and the nullability is
    // the point rather than an accident of style: null means "not part of a set", which is a
    // different fact from "disc one", and a single-disc album should not get a disc number
    // printed on its case just because the column exists.
    //
    // Every existing row becomes null, which is right for the overwhelming majority and
    // unavoidable for the rest — the disc was thrown away at import (see
    // AlbumImportRepository) and there is nothing left in the database to recover it from.
    // A multi-disc album already imported has to be re-imported to get its discs back, and
    // that is worth saying out loud rather than pretending the migration fixed it.
    db.execSQL("ALTER TABLE Track ADD COLUMN discNumber INTEGER")
}

val MIGRATION_5_6 = Migration(5, 6) { db: SupportSQLiteDatabase ->
    // Where a record actually lives, now that it does not have to be on the phone.
    //
    // All three are nullable with no default, and null carries the meaning that was true
    // before streaming existed: this album is on this device, and its tracks are files. That
    // makes the migration a no-op for every library already out there rather than a rewrite
    // of it — no row is touched, nothing is reinterpreted, and an existing collection behaves
    // on the next launch exactly as it did on the last one.
    //
    // `sourceId` on the album and `remoteId` on both: the album records *which* server, each
    // track records *what to ask it for*. They are separate columns rather than one encoded
    // string because the two are queried differently — "show me everything from the server
    // I just removed" is an album question, and "how do I play this" is a track question.
    db.execSQL("ALTER TABLE Album ADD COLUMN sourceId TEXT")
    db.execSQL("ALTER TABLE Album ADD COLUMN remoteId TEXT")
    db.execSQL("ALTER TABLE Track ADD COLUMN remoteId TEXT")
}
