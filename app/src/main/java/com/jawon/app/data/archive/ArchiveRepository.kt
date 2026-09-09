package com.jawon.app.data.archive

import android.content.Context
import android.net.Uri
import com.jawon.app.data.importer.CoverImageStore
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Taking a record home from the shop.
 *
 * ## Why this needed no changes to playback at all
 *
 * An Internet Archive file URL is **permanent and unauthenticated**. There is no token, no
 * salt, and no expiry, which makes it unlike either of the other remote sources this app has
 * met. The consequence is worth stating plainly because it is the reason this integration is
 * small: the URL can simply be stored in [Track.fileUri] and everything downstream already
 * works. `DefaultDataSource` opens `https://` exactly as it opens `content://`, `StreamCache`
 * caches it because it is network, and — because the URL never changes — the cache keys on it
 * correctly with no custom key needed. The scratch, the equaliser and the whole rack apply,
 * because this is audio the app genuinely receives.
 *
 * That is the difference between a record shop and a streaming service, and it is not a
 * metaphor: what you take home is yours, it sits on your wall, and everything the room does to
 * a record works on it.
 *
 * [Track.remoteId] is deliberately left null for the same reason. It exists to say "ask the
 * server for this"; here there is nothing to ask.
 */
@Singleton
class ArchiveRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val shelfDao: ShelfDao,
    private val coverStore: CoverImageStore
) {

    /** Identifiers already on the wall, so the shop can mark what has been taken. */
    suspend fun ownedIdentifiers(): Set<String> = withContext(Dispatchers.IO) {
        albumDao.forSource(SOURCE_ID).mapNotNull { it.remoteId }.toSet()
    }

    /**
     * Adds one Archive item to the library, and returns the album id.
     *
     * Idempotent: taking the same record twice returns the copy already on the shelf rather
     * than putting a second one beside it. The importer enforces the same rule for the same
     * reason — a duplicate is not a small annoyance here, it is a second case and a play
     * history split in half.
     */
    suspend fun addToLibrary(album: ArchiveAlbum): String = withContext(Dispatchers.IO) {
        val albumId = albumIdFor(album.identifier)
        albumDao.findById(albumId)?.let { return@withContext it.id }

        val shelfId = ensureShelf()
        val slotIndex =
            (albumDao.getForShelf(shelfId).first().maxOfOrNull { it.slotIndex } ?: -1) + 1
        val coverPath = downloadCover(album.coverUrl, albumId)

        albumDao.insert(
            Album(
                id = albumId,
                shelfId = shelfId,
                title = album.title,
                artist = album.creator,
                year = album.year,
                caseCoverUri = coverPath,
                discFaceUri = null,
                spineHue = 0f,
                slotIndex = slotIndex,
                playCount = 0,
                addedAt = System.currentTimeMillis(),
                sourceId = SOURCE_ID,
                remoteId = album.identifier
            )
        )

        trackDao.insertAll(
            album.tracks.mapIndexed { index, track ->
                Track(
                    id = trackIdFor(album.identifier, track.fileName),
                    albumId = albumId,
                    trackNumber = track.trackNumber.takeIf { it > 0 } ?: (index + 1),
                    discNumber = null,
                    title = track.title,
                    artist = null,
                    durationMs = track.durationMs,
                    // The permanent URL, stored as if it were a file — because for every
                    // purpose downstream of here, it behaves like one.
                    fileUri = ArchiveClient.streamUrl(album.identifier, track.fileName),
                    remoteId = null,
                    mimeType = track.mimeType,
                    // The Archive reports neither for its derivatives. Zero and null render as
                    // a blank in the deck's readout rather than as an invented number.
                    sampleRateHz = 0,
                    bitDepth = null,
                    bitrateBps = track.bitrateBps
                )
            }
        )

        albumId
    }

    private suspend fun downloadCover(url: String, albumId: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        val bytes = try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) return null
        val staging = File(context.cacheDir, "archive").apply { mkdirs() }
        val temp = File(staging, "cover-${UUID.randomUUID()}.img")
        temp.writeBytes(bytes)
        val stored = coverStore.store(context, Uri.fromFile(temp), "$albumId-cover.jpg")
        temp.delete()
        stored
    }.getOrNull()

    private suspend fun ensureShelf(): String {
        val existing = shelfDao.listAll()
        existing.firstOrNull { it.name == SHELF_NAME }?.let { return it.id }
        val shelf = Shelf(
            id = UUID.randomUUID().toString(),
            name = SHELF_NAME,
            order = existing.size,
            accentHue = 0f,
            createdAt = System.currentTimeMillis()
        )
        shelfDao.insert(shelf)
        return shelf.id
    }

    companion object {
        const val SOURCE_ID = "archive"
        private const val SHELF_NAME = "From the Archive"

        fun albumIdFor(identifier: String) = "$SOURCE_ID:album:$identifier"

        /**
         * Keyed by file name as well as item, because an identifier alone is the *album*.
         *
         * Derived rather than random for the usual reason: it is what makes taking the same
         * record home twice a no-op instead of a duplicate, and what keeps a play history
         * attached to the track it belongs to.
         */
        fun trackIdFor(identifier: String, fileName: String) =
            "$SOURCE_ID:track:$identifier/$fileName"
    }
}
