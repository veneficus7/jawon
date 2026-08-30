package com.jawon.app.data.importer

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.jawon.app.data.local.Album
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val UNSORTED_SHELF_NAME = "Unsorted"

/** How many files to check for embedded art before concluding the album has none. */
private const val EMBEDDED_ART_PROBE_LIMIT = 5

/**
 * What MediaStore puts in the album and artist columns when a file has no tag for it.
 *
 * A literal, not a missing value — so a phone full of untagged files produces albums named
 * "&lt;unknown&gt;" unless this is checked for.
 */
private const val MEDIASTORE_UNKNOWN = "<unknown>"

@Singleton
class AlbumImportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val coverArtFetcher: CoverArtFetcher,
    private val preferences: AppPreferences
) {
    private val scanner = AudioScanner()
    private val metadataReader = TrackMetadataReader()
    private val coverStore = CoverImageStore()

    /**
     * Scans a picked folder and returns **one preview per album found in it**.
     *
     * **The scanner has always recursed, and that used to be a trap.** Everything it found
     * anywhere under the picked folder was collapsed into a single album — so a user who
     * pointed Jawon at their `Music` directory, which is the obvious thing to do, got one
     * "album" containing their entire library, with the track numbers of forty records
     * fighting each other. The only way to import properly was to add albums one folder at a
     * time and know that in advance.
     *
     * Files are grouped by the folder that contains them, which is how people actually
     * organise music and is more reliable than the album tag: untagged rips still group
     * correctly, and two different albums that share a title do not merge.
     *
     * Returns a single-element list for the ordinary "picked one album folder" case, so the
     * caller can treat one and many the same way.
     */
    suspend fun scanTree(treeUri: Uri): List<ImportPreview> = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val files = scanner.scan(context, treeUri)
        if (files.isEmpty()) return@withContext emptyList()

        val rootName = DocumentFile.fromTreeUri(context, treeUri)?.name
        files
            .groupBy { it.parentFile?.uri?.toString().orEmpty() }
            .values
            .map { group -> buildPreview(treeUri, group, rootName) }
            .sortedBy { it.suggestedTitle.lowercase() }
    }

    /**
     * Finds every album on the phone, without the user pointing at anything.
     *
     * **This is the answer to the import being "time and effort".** The folder picker asks the
     * user to know where their music lives and to hand over one directory at a time, which is
     * a question about their filesystem rather than about their records. Android already keeps
     * an index of every audio file on the device; this reads it.
     *
     * The folder picker stays, because MediaStore only indexes what the media scanner has
     * seen — files in app-private directories, and anything on a provider like Drive or a USB
     * stick, are reachable through SAF and invisible here.
     *
     * **Grouped by MediaStore's own album id, not by folder.** The folder importer groups by
     * directory because that is all it has, and it is a good heuristic for a ripped
     * collection. Here the platform has already done the grouping from the tags, and it is
     * better: it copes with an album split across directories, and with a directory holding
     * more than one album.
     *
     * **Deliberately cheap.** Only MediaStore columns are read — no file is opened. The tags,
     * track numbers and durations are all in the index, which answers for a whole library in
     * well under a second; sample rate and bit depth are not, so those are filled in at
     * import time for the albums actually chosen. See [TrackMetadataReader.withTechnical].
     */
    suspend fun scanDevice(): List<ImportPreview> = withContext(Dispatchers.IO) {
        val known = trackDao.allFileUris().toHashSet()

        // Second, blunter filter for albums already on the shelves.
        //
        // **The file URI is not enough, and cannot be.** The same file has a different URI
        // depending on how it was reached: the folder picker yields a SAF document URI under
        // `com.android.externalstorage`, while this scan yields `content://media/external/…`.
        // Nothing in either one exposes the other's identity, so an album imported by folder
        // last week looks brand new to the scan — and offering someone a second copy of a
        // record they already have is the one way an automatic scan can make a library worse.
        //
        // Matching on title and artist instead. It is a heuristic: two genuinely different
        // records with the same name by the same artist would be collapsed. That is rare, and
        // the folder picker is still there for it — whereas duplicates are what you get on
        // every single scan after the first.
        val alreadyShelved = albumDao.getAll().first()
            .mapTo(HashSet()) { albumKey(it.title, it.artist) }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        val grouped = LinkedHashMap<String, MutableList<TrackMetadata>>()
        val albumNames = HashMap<String, Pair<String?, String?>>()

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            // Excludes ringtones, notifications and alarms, which are audio files on every
            // phone and are not records.
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.ALBUM_ID} ASC, ${MediaStore.Audio.Media.TRACK} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol)
                )
                if (uri.toString() in known) continue

                val album = cursor.getStringOrNull(albumCol)
                val artist = cursor.getStringOrNull(artistCol)
                // Album id groups a real album correctly even when it spans folders. Falling
                // back to the names keeps loose files together sensibly rather than making
                // every one of them its own single-track "album".
                val key = cursor.getLongOrNull(albumIdCol)?.toString()
                    ?: "name:${album.orEmpty()}/${artist.orEmpty()}"

                albumNames.putIfAbsent(key, album to artist)
                grouped.getOrPut(key) { mutableListOf() }.add(
                    TrackMetadata(
                        fileUri = uri,
                        displayName = cursor.getStringOrNull(nameCol).orEmpty(),
                        title = cursor.getStringOrNull(titleCol)
                            ?: cursor.getStringOrNull(nameCol)?.substringBeforeLast('.')
                            ?: "Untitled",
                        album = album,
                        artist = artist,
                        // MediaStore packs the disc into the thousands place — 1006 is disc 1
                        // track 6. Taken raw, a two-disc set numbers its second half 1001,
                        // 1002 and sorts fine but reads as nonsense on the case.
                        trackNumber = cursor.getIntOrNull(trackCol)
                            ?.let { if (it > 1000) it % 1000 else it }
                            ?.takeIf { it > 0 },
                        year = cursor.getIntOrNull(yearCol)?.takeIf { it > 0 },
                        durationMs = cursor.getLongOrNull(durationCol) ?: 0L,
                        mimeType = cursor.getStringOrNull(mimeCol) ?: "audio/unknown",
                        // Not in the index. Filled in at import — see the KDoc above.
                        sampleRateHz = 0,
                        bitDepth = null,
                        bitrateBps = 0
                    )
                )
            }
        }

        grouped.entries.mapNotNull { (key, tracks) ->
            val ordered = tracks.sortedWith(
                compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.displayName })
            )
            val (album, artist) = albumNames[key] ?: (null to null)
            val title = album?.takeIf { it.isNotBlank() && it != MEDIASTORE_UNKNOWN }
                ?: mostCommon(ordered.mapNotNull { it.album })
                ?: "Untitled Album"
            val artistName = artist?.takeIf { it.isNotBlank() && it != MEDIASTORE_UNKNOWN }
                ?: "Unknown Artist"
            if (albumKey(title, artistName) in alreadyShelved) return@mapNotNull null

            ImportPreview(
                // No picked tree here. Nothing reads this for a device scan — it exists for
                // the SAF grant the folder path needs — so it points at the first track,
                // which is at least true rather than a placeholder.
                treeUri = ordered.first().fileUri,
                tracks = ordered,
                suggestedTitle = title,
                suggestedArtist = artistName,
                suggestedYear = mostCommon(ordered.mapNotNull { it.year }),
                failedCount = 0,
                embeddedCoverUri = extractEmbeddedCover(ordered.map { it.fileUri })
            )
        }.sortedBy { it.suggestedTitle.lowercase() }
    }

    /**
     * A loose identity for an album, for spotting one the library already has.
     *
     * Case and punctuation are dropped because the same record is tagged half a dozen ways
     * across rips — "Mr. Morale & The Big Steppers" against "Mr Morale and the Big Steppers"
     * — and a duplicate check that only catches exact matches catches almost nothing.
     */
    private fun albumKey(title: String, artist: String): String =
        (title + ' ' + artist).lowercase().filter { it.isLetterOrDigit() }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private suspend fun buildPreview(
        treeUri: Uri,
        files: List<DocumentFile>,
        rootName: String?
    ): ImportPreview {
        val read = mutableListOf<TrackMetadata>()
        var failedCount = 0
        for (file in files) {
            try {
                read.add(metadataReader.read(context, file))
            } catch (_: Exception) {
                failedCount++
            }
        }

        val ordered = read.sortedWith(
            compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.displayName })
        )
        // This group's own folder, not the picked root — with nested folders they differ, and
        // the containing folder is what names the album when the tags do not.
        val folderName = files.firstOrNull()?.parentFile?.name ?: rootName

        return ImportPreview(
            treeUri = treeUri,
            tracks = ordered,
            suggestedTitle = mostCommon(ordered.mapNotNull { it.album })
                ?: folderName
                ?: "Untitled Album",
            suggestedArtist = mostCommon(ordered.mapNotNull { it.artist }) ?: "Unknown Artist",
            suggestedYear = mostCommon(ordered.mapNotNull { it.year }),
            failedCount = failedCount,
            embeddedCoverUri = extractEmbeddedCover(files.map { it.uri })
        )
    }

    /**
     * Pulls the album's own cover out of the first track that has one.
     *
     * **Stops after [EMBEDDED_ART_PROBE_LIMIT] files.** Reading embedded art means opening
     * each file with a fresh `MediaMetadataRetriever`, which is not cheap, and art is a
     * property of the album rather than the track — if the first handful do not have it, the
     * rest almost certainly do not either. Scanning a 40-track box set to prove a negative
     * would make every artless import visibly slower for nothing.
     *
     * Written to the cache directory rather than straight into the album's storage, because
     * at scan time there is no album yet — the user is still looking at the preview and may
     * back out. `commit` copies whatever cover survives that screen into private storage.
     */
    private suspend fun extractEmbeddedCover(uris: List<Uri>): Uri? {
        for (uri in uris.take(EMBEDDED_ART_PROBE_LIMIT)) {
            val bytes = metadataReader.embeddedPicture(context, uri) ?: continue
            return withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "import").apply { mkdirs() }
                    val out = File(dir, "cover-${UUID.randomUUID()}.img")
                    out.writeBytes(bytes)
                    Uri.fromFile(out)
                }.getOrNull()
            }
        }
        return null
    }

    suspend fun commit(
        preview: ImportPreview,
        title: String,
        artist: String,
        year: Int?,
        caseCoverUri: Uri?,
        discFaceUri: Uri?,
        /**
         * The album's accent, 0..1 round the colour wheel, or `0f` to leave it unset.
         *
         * Unset is the useful default rather than a missing value: `CaseArtCache` falls back
         * to the cover's own dominant hue, which is usually a better answer than anything a
         * user would pick from a slider, and costs them nothing. This is the override for
         * when it is not.
         */
        spineHue: Float
    ): String = withContext(Dispatchers.IO) {
        val shelfId = ensureDefaultShelf()
        val albumId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val caseCoverPath = caseCoverUri?.let { coverStore.store(context, it, "$albumId-cover.jpg") }
        val discFacePath = discFaceUri?.let { coverStore.store(context, it, "$albumId-disc.jpg") }

        // One past the highest slot in use, NOT the album count.
        //
        // Those differ the moment anything is removed, and the difference matters now that a
        // deleted album leaves its gap on the shelf rather than the rest closing up: counting
        // would hand a new album the index of a record that is gone, dropping it into the
        // hole instead of at the end of the shelf.
        val slotIndex = (albumDao.getForShelf(shelfId).first().maxOfOrNull { it.slotIndex } ?: -1) + 1

        albumDao.insert(
            Album(
                id = albumId,
                shelfId = shelfId,
                title = title,
                artist = artist,
                year = year,
                caseCoverUri = caseCoverPath,
                discFaceUri = discFacePath,
                spineHue = spineHue,
                slotIndex = slotIndex,
                playCount = 0,
                addedAt = now
            )
        )

        preview.tracks.forEachIndexed { index, scanned ->
            // Opens the file only when the scan could not supply the codec details — which
            // is the device-scan path, and only for the albums actually being imported.
            val track = metadataReader.withTechnical(context, scanned)
            trackDao.insert(
                Track(
                    id = UUID.randomUUID().toString(),
                    albumId = albumId,
                    trackNumber = track.trackNumber ?: (index + 1),
                    title = track.title,
                    durationMs = track.durationMs,
                    fileUri = track.fileUri.toString(),
                    mimeType = track.mimeType,
                    sampleRateHz = track.sampleRateHz,
                    bitDepth = track.bitDepth,
                    bitrateBps = track.bitrateBps
                )
            )
        }

        albumId
    }

    /**
     * Imports several albums in one go, using each one's own detected title, artist, year and
     * embedded cover.
     *
     * The point of the batch path is that it asks nothing. A user adding forty albums is not
     * going to type forty titles, and the tags are almost always right — anything wrong can be
     * corrected afterwards on the album itself. The single-album path keeps its editing form,
     * because someone adding one record often does want to fix its name.
     *
     * Failures are per-album rather than fatal: one unreadable folder in a library of forty
     * should cost that folder, not the other thirty-nine. Returns how many actually landed.
     */
    suspend fun commitAll(previews: List<ImportPreview>): Int = withContext(Dispatchers.IO) {
        var imported = 0
        for (preview in previews) {
            runCatching {
                commit(
                    preview = preview,
                    title = preview.suggestedTitle,
                    artist = preview.suggestedArtist,
                    year = preview.suggestedYear,
                    // Only ever for an album whose own files carry no art, and only when the
                    // user has left the lookup on. An album that brought its cover with it
                    // never reaches the network — which, on a normally-tagged library, is
                    // almost all of them.
                    caseCoverUri = preview.embeddedCoverUri ?: lookedUpCover(preview),
                    discFaceUri = null,
                    spineHue = 0f
                )
            }.onSuccess { imported++ }
        }
        imported
    }

    /**
     * How many of these albums would need a cover looked up online.
     *
     * The import screen says this out loud before anything is sent, so the app's only network
     * call is never a surprise — and when the answer is zero it says nothing at all, which on
     * a well-tagged library is the usual case.
     */
    fun coversToLookUp(previews: List<ImportPreview>): Int =
        if (!preferences.fetchCoverArt.value) 0
        else previews.count { it.embeddedCoverUri == null }

    private suspend fun lookedUpCover(preview: ImportPreview): Uri? {
        if (!preferences.fetchCoverArt.value) return null
        return coverArtFetcher.fetch(context, preview.suggestedTitle, preview.suggestedArtist)
    }

    private suspend fun ensureDefaultShelf(): String {
        val existing = shelfDao.getAll().first()
        existing.firstOrNull { it.name == UNSORTED_SHELF_NAME }?.let { return it.id }

        val shelf = Shelf(
            id = UUID.randomUUID().toString(),
            name = UNSORTED_SHELF_NAME,
            order = existing.size,
            accentHue = 0f,
            createdAt = System.currentTimeMillis()
        )
        shelfDao.insert(shelf)
        return shelf.id
    }

    private fun <T> mostCommon(values: List<T>): T? =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
