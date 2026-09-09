package com.jawon.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The three hand-written migrations, run against a real database.
 *
 * **This is the highest-severity gap this project had.** `JawonDatabase` is built with no
 * `fallbackToDestructiveMigration` — deliberately, and correctly: a shelf someone built and the
 * colours they picked cannot be recovered from their audio files, so a migration that disagrees
 * with the entities should crash loudly rather than wipe a stranger's collection quietly. The
 * consequence is that a wrong migration does not degrade, it *bricks*: an unrecoverable crash
 * loop on first launch after an update, on a phone that cannot downgrade.
 *
 * Until this file, nothing checked them at all.
 *
 * ## Why this does not use `MigrationTestHelper`
 *
 * The usual tool needs the exported schema JSON for the *starting* version, and
 * `app/schemas` holds only 2, 3 and 4. **`1.json` does not exist and never did** —
 * `exportSchema` was switched on in the same commit that introduced version 2
 * (`88a7936`), so there is nothing in git history to recover. Room computes a schema's
 * `identityHash` itself, and a hand-written 1.json would carry an invented one, which is a
 * worse foundation for a safety net than no 1.json at all.
 *
 * So the old database is built here from raw DDL instead. That is not a workaround, it is
 * arguably the better test: the v1 statements below are v2's exported `createSql` minus
 * exactly what `MIGRATION_1_2` adds, and the final test opens the result through **Room's own
 * production path**, which is the code that actually validates a migrated schema against the
 * entities. If a migration is wrong, that is where it throws — on a device and here alike.
 *
 * Robolectric runs the lot on the JVM in seconds, the same way `LibraryBackupTest` does.
 */
@RunWith(RobolectricTestRunner::class)
// Matching LibraryBackupTest: Robolectric emulates up to SDK 35 and this app targets 37, so the
// platform has to be pinned. Nothing here is version-sensitive — it is SQL.
@Config(sdk = [34])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
    }

    // ---- version 1, reconstructed --------------------------------------------------------
    //
    // v2's exported createSql, minus the three Album columns and the PlayEvent table that
    // MIGRATION_1_2 adds. Nothing here is invented: Shelf and Track were untouched by that
    // migration, so their statements are v2's verbatim.

    private val v1Shelf = """
        CREATE TABLE IF NOT EXISTS `Shelf` (
            `id` TEXT NOT NULL, `name` TEXT NOT NULL, `order` INTEGER NOT NULL,
            `accentHue` REAL NOT NULL, `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    private val v1Album = """
        CREATE TABLE IF NOT EXISTS `Album` (
            `id` TEXT NOT NULL, `shelfId` TEXT NOT NULL, `title` TEXT NOT NULL,
            `artist` TEXT NOT NULL, `year` INTEGER, `caseCoverUri` TEXT,
            `discFaceUri` TEXT, `spineHue` REAL NOT NULL, `slotIndex` INTEGER NOT NULL,
            `playCount` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    private val v1Track = """
        CREATE TABLE IF NOT EXISTS `Track` (
            `id` TEXT NOT NULL, `albumId` TEXT NOT NULL, `trackNumber` INTEGER NOT NULL,
            `title` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `fileUri` TEXT NOT NULL,
            `mimeType` TEXT NOT NULL, `sampleRateHz` INTEGER NOT NULL, `bitDepth` INTEGER,
            `bitrateBps` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    /**
     * Opens the database file at [version], creating it with [create] the first time.
     *
     * Deliberately raw rather than through Room: the point is to stand up a schema Room's
     * current entities do not describe, which is precisely what Room refuses to do.
     */
    private fun openRaw(version: Int, create: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    /** A version 1 database holding one shelf, one album and one track. */
    private fun createV1(): SupportSQLiteDatabase = openRaw(1) { db ->
        db.execSQL(v1Shelf)
        db.execSQL(v1Album)
        db.execSQL(v1Track)
        db.execSQL(
            "INSERT INTO Shelf VALUES ('shelf-1', 'Late nights', 0, 0.58, 1700000000000)"
        )
        db.execSQL(
            """
            INSERT INTO Album VALUES (
                'album-1', 'shelf-1', 'Selected Ambient Works', 'Aphex Twin', 1992,
                'content://covers/1', NULL, 0.42, 3, 17, 1700000001000
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO Track VALUES (
                'track-1', 'album-1', 1, 'Xtal', 293000,
                'content://audio/1', 'audio/flac', 44100, 16, 987000
            )
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.columnsOf(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { c ->
            buildSet {
                val nameCol = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(nameCol))
            }
        }

    // ---- 1 → 2 ---------------------------------------------------------------------------

    @Test
    fun `migration 1 to 2 keeps the library and starts every album at the beginning`() {
        createV1().close()

        val db = openRaw(2) { }.also { MIGRATION_1_2.migrate(it) }

        db.query("SELECT * FROM Album WHERE id = 'album-1'").use { c ->
            assertTrue("the album did not survive the migration", c.moveToFirst())

            // The original row, untouched. A migration that adds columns must not disturb the
            // ones already there, and an ALTER TABLE that silently rewrote a row would show up
            // here rather than months later as a library that looks subtly wrong.
            assertEquals("Selected Ambient Works", c.getString(c.getColumnIndexOrThrow("title")))
            assertEquals("Aphex Twin", c.getString(c.getColumnIndexOrThrow("artist")))
            assertEquals(1992, c.getInt(c.getColumnIndexOrThrow("year")))
            assertEquals(17, c.getInt(c.getColumnIndexOrThrow("playCount")))
            assertEquals(3, c.getInt(c.getColumnIndexOrThrow("slotIndex")))

            // The new columns. Defaults of 0 mean every existing record simply starts at the
            // beginning, which is the honest answer for albums played before anything was
            // recorded.
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("lastTrackIndex")))
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("lastPositionMs")))

            // And the one that carries a decision: lastPlayedAt is NULL, not 0. "Never played"
            // is a real state and 0 would be a lie about 1970 — see the migration's own
            // comment. If this ever becomes 0, "recently played" starts listing everything.
            assertTrue(
                "lastPlayedAt must be NULL for an album that predates play recording",
                c.isNull(c.getColumnIndexOrThrow("lastPlayedAt"))
            )
        }
        db.close()
    }

    @Test
    fun `migration 1 to 2 creates PlayEvent and both of its indices`() {
        createV1().close()

        val db = openRaw(2) { }.also { MIGRATION_1_2.migrate(it) }

        assertEquals(
            setOf("id", "albumId", "trackId", "startedAt", "msPlayed"),
            db.columnsOf("PlayEvent")
        )

        // Both indices, by name. The table is queried by album for the stats screen and by time
        // for the listening record; a missing index is invisible until a library is large.
        val indices = db.query("PRAGMA index_list(`PlayEvent`)").use { c ->
            buildSet {
                val nameCol = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(nameCol))
            }
        }
        assertTrue("index_PlayEvent_albumId missing", "index_PlayEvent_albumId" in indices)
        assertTrue("index_PlayEvent_startedAt missing", "index_PlayEvent_startedAt" in indices)

        // And it actually takes a row, with the autoincrementing id the entity expects.
        db.execSQL("INSERT INTO PlayEvent (albumId, trackId, startedAt, msPlayed) VALUES ('album-1', 'track-1', 1700000002000, 293000)")
        db.query("SELECT id, msPlayed FROM PlayEvent").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(293000L, c.getLong(1))
        }
        db.close()
    }

    // ---- 2 → 3 ---------------------------------------------------------------------------

    @Test
    fun `migration 2 to 3 adds the track artist as unknown, not as the album's`() {
        createV1().close()
        openRaw(2) { }.also { MIGRATION_1_2.migrate(it) }.close()

        val db = openRaw(3) { }.also { MIGRATION_2_3.migrate(it) }

        db.query("SELECT * FROM Track WHERE id = 'track-1'").use { c ->
            assertTrue("the track did not survive the migration", c.moveToFirst())
            assertEquals("Xtal", c.getString(c.getColumnIndexOrThrow("title")))
            assertEquals(293000L, c.getLong(c.getColumnIndexOrThrow("durationMs")))
            assertEquals(44100, c.getInt(c.getColumnIndexOrThrow("sampleRateHz")))

            // Nullable with no default, and this is the whole point of the migration's comment:
            // a library imported before this column existed has never read the per-track tag,
            // and back-filling the album's artist would make a guess indistinguishable from
            // something the file actually said. Null means "not known"; the deck falls back to
            // the album for exactly those.
            assertTrue(
                "artist must be NULL rather than back-filled from the album",
                c.isNull(c.getColumnIndexOrThrow("artist"))
            )
        }
        db.close()
    }

    // ---- 3 → 4 ---------------------------------------------------------------------------

    @Test
    fun `migration 3 to 4 adds fetchedLyrics as not-yet-asked`() {
        createV1().close()
        openRaw(2) { }.also { MIGRATION_1_2.migrate(it) }.close()
        openRaw(3) { }.also { MIGRATION_2_3.migrate(it) }.close()

        val db = openRaw(4) { }.also { MIGRATION_3_4.migrate(it) }

        db.query("SELECT * FROM Track WHERE id = 'track-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Xtal", c.getString(c.getColumnIndexOrThrow("title")))

            // NULL means "not looked up"; an empty string means "asked, LRCLIB had none". The
            // two have to stay distinguishable or every track without lyrics gets looked up
            // again on every visit to the page. A DEFAULT '' here would be that bug.
            assertTrue(
                "fetchedLyrics must be NULL, which is distinct from the empty string",
                c.isNull(c.getColumnIndexOrThrow("fetchedLyrics"))
            )
        }
        db.close()
    }

    // ---- 4 → 5 ---------------------------------------------------------------------------

    @Test
    fun `migration 4 to 5 adds the disc number as not-part-of-a-set`() {
        createV1().close()
        openRaw(2) { }.also { MIGRATION_1_2.migrate(it) }.close()
        openRaw(3) { }.also { MIGRATION_2_3.migrate(it) }.close()
        openRaw(4) { }.also { MIGRATION_3_4.migrate(it) }.close()

        val db = openRaw(5) { }.also { MIGRATION_4_5.migrate(it) }

        db.query("SELECT * FROM Track WHERE id = 'track-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Xtal", c.getString(c.getColumnIndexOrThrow("title")))

            // NULL means "not part of a set", which is a different fact from "disc one" — a
            // single-disc album should not get a disc number printed on its case just because
            // the column now exists. A DEFAULT 1 here would put one on every record ever
            // imported.
            assertTrue(
                "discNumber must be NULL rather than defaulted to 1",
                c.isNull(c.getColumnIndexOrThrow("discNumber"))
            )
        }
        db.close()
    }

    @Test
    fun `migration 5 to 6 leaves an existing library local, not stranded on a server`() {
        createV1().close()
        openRaw(2) { }.also { MIGRATION_1_2.migrate(it) }.close()
        openRaw(3) { }.also { MIGRATION_2_3.migrate(it) }.close()
        openRaw(4) { }.also { MIGRATION_3_4.migrate(it) }.close()
        openRaw(5) { }.also { MIGRATION_4_5.migrate(it) }.close()

        val db = openRaw(6) { }.also { MIGRATION_5_6.migrate(it) }

        db.query("SELECT * FROM Album WHERE id = 'album-1'").use { c ->
            assertTrue(c.moveToFirst())
            // The album is untouched...
            assertEquals("Selected Ambient Works", c.getString(c.getColumnIndexOrThrow("title")))

            // ...and NULL is what says "this record is on this phone". Anything else here — a
            // default of '' or 'local' — would make every album that predates streaming look
            // like it came from a server, and `AlbumDao.forSource` would then hand the whole
            // pre-existing library to a sync that is entitled to delete what the server does
            // not list. That is the failure this assertion exists to catch.
            assertTrue(
                "sourceId must be NULL so an existing album stays local",
                c.isNull(c.getColumnIndexOrThrow("sourceId"))
            )
            assertTrue(c.isNull(c.getColumnIndexOrThrow("remoteId")))
        }

        db.query("SELECT * FROM Track WHERE id = 'track-1'").use { c ->
            assertTrue(c.moveToFirst())
            // The file is still the file. A track whose fileUri survived but whose remoteId was
            // set would be looked up on a server that has never heard of it.
            assertEquals(
                "content://audio/1",
                c.getString(c.getColumnIndexOrThrow("fileUri"))
            )
            assertTrue(
                "remoteId must be NULL for a track that has a file",
                c.isNull(c.getColumnIndexOrThrow("remoteId"))
            )
        }
        db.close()
    }

    // ---- the whole chain, through Room ----------------------------------------------------

    @Test
    fun `a version 1 library opens at the current version through Room's own validation`() {
        createV1().close()

        // The test that matters most, and the only one that reproduces the production failure.
        //
        // Room validates a migrated schema against the current entities when it opens the
        // database, and throws IllegalStateException if they disagree — that is what a user
        // would hit as an unrecoverable crash on first launch after an update. Touching
        // `writableDatabase` is what forces the open; building the database alone is lazy and
        // would pass no matter how wrong the migrations were.
        val db = Room.databaseBuilder(context, JawonDatabase::class.java, DB_NAME)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
            )
            .allowMainThreadQueries()
            .build()

        assertNotNull(db.openHelper.writableDatabase)
        assertEquals(6, db.openHelper.writableDatabase.version)
        db.close()
    }

    @Test
    fun `a library survives the whole chain intact, read back through the DAOs`() {
        // A second album and a second track, so the test would notice a migration that kept the
        // first row and lost the rest.
        createV1().also { db ->
            db.execSQL(
                """
                INSERT INTO Album VALUES (
                    'album-2', 'shelf-1', 'Music Has The Right', 'Boards of Canada', 1998,
                    NULL, NULL, 0.11, 4, 2, 1700000003000
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO Track VALUES (
                    'track-2', 'album-2', 1, 'Wildlife Analysis', 78000,
                    'content://audio/2', 'audio/mpeg', 48000, NULL, 320000
                )
                """.trimIndent()
            )
        }.close()

        val db = Room.databaseBuilder(context, JawonDatabase::class.java, DB_NAME)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
            )
            .allowMainThreadQueries()
            .build()

        db.openHelper.writableDatabase.query(
            "SELECT id, title, artist, year, spineHue, playCount FROM Album ORDER BY id"
        ).use { c ->
            assertEquals("both albums should survive", 2, c.count)

            assertTrue(c.moveToFirst())
            assertEquals("album-1", c.getString(0))
            assertEquals("Selected Ambient Works", c.getString(1))
            assertEquals("Aphex Twin", c.getString(2))
            assertEquals(1992, c.getInt(3))
            assertEquals(0.42f, c.getFloat(4), 0.0001f)
            assertEquals(17, c.getInt(5))

            assertTrue(c.moveToNext())
            assertEquals("album-2", c.getString(0))
            assertEquals("Boards of Canada", c.getString(2))
        }

        db.openHelper.writableDatabase.query(
            "SELECT id, title, bitDepth, artist, fetchedLyrics FROM Track ORDER BY id"
        ).use { c ->
            assertEquals("both tracks should survive", 2, c.count)

            assertTrue(c.moveToFirst())
            assertEquals("Xtal", c.getString(1))
            // A nullable column that held a value keeps it...
            assertEquals(16, c.getInt(2))

            assertTrue(c.moveToNext())
            assertEquals("Wildlife Analysis", c.getString(1))
            // ...and one that held NULL stays NULL rather than becoming 0. A lossy migration
            // would turn "unknown bit depth" into a confident, wrong 16 in the deck's readout.
            assertTrue("bitDepth must stay NULL for the MP3", c.isNull(2))
        }

        // The shelf, which nothing touched but everything depends on.
        db.openHelper.writableDatabase.query("SELECT name, accentHue FROM Shelf").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Late nights", c.getString(0))
            assertEquals(0.58f, c.getFloat(1), 0.0001f)
        }

        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
