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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val UNSORTED_SHELF_NAME = "Unsorted"

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

    suspend fun scanFolder(treeUri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val files = scanner.scan(context, treeUri)
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
        val folderName = DocumentFile.fromTreeUri(context, treeUri)?.name

        ImportPreview(
            treeUri = treeUri,
            tracks = ordered,
            suggestedTitle = mostCommon(ordered.mapNotNull { it.album })
                ?: folderName
                ?: "Untitled Album",
            suggestedArtist = mostCommon(ordered.mapNotNull { it.artist }) ?: "Unknown Artist",
            suggestedYear = mostCommon(ordered.mapNotNull { it.year }),
            failedCount = failedCount
        )
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
