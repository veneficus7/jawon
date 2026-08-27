package com.jawon.app.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jawon.app.data.local.Album
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

@Singleton
class AlbumImportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao
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
            embeddedCoverUri = extractEmbeddedCover(files)
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
    private suspend fun extractEmbeddedCover(files: List<DocumentFile>): Uri? {
        for (file in files.take(EMBEDDED_ART_PROBE_LIMIT)) {
            val bytes = metadataReader.embeddedPicture(context, file.uri) ?: continue
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

        val slotIndex = albumDao.getForShelf(shelfId).first().size

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

        preview.tracks.forEachIndexed { index, track ->
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
                    caseCoverUri = preview.embeddedCoverUri,
                    discFaceUri = null,
                    spineHue = 0f
                )
            }.onSuccess { imported++ }
        }
        imported
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
