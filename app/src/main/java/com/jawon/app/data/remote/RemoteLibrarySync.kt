package com.jawon.app.data.remote

import android.content.Context
import android.net.Uri
import com.jawon.app.data.importer.CoverImageStore
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where a sync has got to, for the settings screen to draw. */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val done: Int, val total: Int, val album: String) : SyncState
    data class Done(val added: Int, val updated: Int, val removed: Int) : SyncState
    data class Failed(val reason: String) : SyncState
}

/**
 * Pulls a music server's catalogue onto the wall.
 *
 * **What crosses the network is the catalogue, not the music.** This walks the server's albums
 * and writes a row per album and per track — titles, artists, durations, codecs — plus one
 * cover image each. The audio itself is never fetched here; a track row carries a [Track.remoteId]
 * and the bytes are pulled at the moment of playing. That is the whole point of the feature: a
 * library of hundreds of albums costs a few megabytes of artwork on the phone instead of tens
 * of gigabytes of audio.
 *
 * ## Re-syncing must not cost the user their history
 *
 * This is the part worth being careful about, because getting it wrong is invisible until it is
 * catastrophic. Jawon computes the patina on every case, the badges on the tracklist and the
 * whole listening record from `PlayEvent` rows keyed by album and track id. So:
 *
 * - **Ids are derived from the server's ids, not minted fresh.** [albumIdFor] and [trackIdFor]
 *   are pure functions of the remote id, so the same album is the same row on every sync, and
 *   the play history keeps pointing at it.
 * - **The server does not get to overwrite what it does not know.** A sync updates the fields
 *   the server is authoritative for — title, artist, year, tracklist — and carries the local
 *   ones across untouched: the slot the case sits in, its accent colour, the play count, and
 *   where the record was left. `@Insert(REPLACE)` on its own would silently reset every one of
 *   those, which is why [merge] exists rather than a bare copy.
 *
 * An album that has disappeared from the server is deleted here, along with its tracks. Its
 * `PlayEvent` rows are deliberately **not** deleted: the listening record is a record of what
 * was listened to, and a record that rewrites itself when a server changes is not a record.
 */
@Singleton
class RemoteLibrarySync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: SubsonicClient,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val shelfDao: ShelfDao,
    private val coverStore: CoverImageStore,
    private val preferences: AppPreferences
) {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * How many albums the server has put on the wall, live.
     *
     * The honest answer to "is this set up and working", and better than the last sync's summary
     * because it survives leaving the screen — a count of what is *there* rather than a report of
     * what happened once.
     */
    val albumCount: kotlinx.coroutines.flow.Flow<Int> = albumDao.countForSource(SOURCE_ID)

    /**
     * Brings the local catalogue in line with the server's.
     *
     * Failures are per-album rather than fatal, the same way `AlbumImportRepository.commitAll`
     * treats an unreadable folder: one album the server cannot describe should cost that album,
     * not the other four hundred.
     */
    suspend fun sync(): SyncState = withContext(Dispatchers.IO) {
        val server = preferences.musicServer.value
        if (!server.isConfigured) {
            return@withContext SyncState.Failed("No server set up").also { _state.value = it }
        }

        when (val probe = client.ping(server)) {
            is ServerProbe.Refused -> return@withContext SyncState.Failed(probe.reason)
                .also { _state.value = it }
            is ServerProbe.Unreachable -> return@withContext SyncState.Failed(probe.reason)
                .also { _state.value = it }
            is ServerProbe.Reachable -> Unit
        }

        val remote = runCatching { client.albums(server) }.getOrElse {
            return@withContext SyncState.Failed(it.message ?: "Could not read the catalogue")
                .also { failure -> _state.value = failure }
        }

        val shelfId = ensureShelf()
        val existing = albumDao.forSource(SOURCE_ID).associateBy { it.id }
        // One past the highest slot in use rather than the album count — the same reasoning as
        // the importer's: a removed album leaves its gap, so counting would drop a new record
        // into the hole instead of at the end of the shelf.
        var nextSlot = (albumDao.getForShelf(shelfId).first().maxOfOrNull { it.slotIndex } ?: -1) + 1

        var added = 0
        var updated = 0

        remote.forEachIndexed { index, summary ->
            _state.value = SyncState.Running(index, remote.size, summary.title)
            val detail = client.album(server, summary.id) ?: return@forEachIndexed
            if (detail.tracks.isEmpty()) return@forEachIndexed

            val albumId = albumIdFor(summary.id)
            val previous = existing[albumId]

            val coverPath = previous?.caseCoverUri
                // Fetched once, then left alone. Re-downloading every cover on every sync would
                // make a routine refresh cost as much as the first one, for artwork that almost
                // never changes.
                ?: summary.coverArtId?.let { downloadCover(server, it, albumId) }

            albumDao.insert(
                merge(
                    previous = previous,
                    summary = summary,
                    albumId = albumId,
                    shelfId = shelfId,
                    coverPath = coverPath,
                    slotIndex = nextSlot,
                    now = System.currentTimeMillis()
                )
            )
            if (previous == null) {
                added++
                nextSlot++
            } else {
                updated++
            }

            // Replaced wholesale rather than diffed. A tracklist is small, the server is
            // authoritative for all of it, and a diff would have to reason about a track being
            // renamed versus replaced — a distinction the API cannot express anyway.
            trackDao.deleteForAlbum(albumId)
            trackDao.insertAll(
                detail.tracks.mapIndexed { position, track ->
                    Track(
                        id = trackIdFor(track.id),
                        albumId = albumId,
                        trackNumber = track.trackNumber ?: (position + 1),
                        discNumber = track.discNumber,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        // Empty on purpose: a stream URL carries a one-shot salt and would be
                        // stale before it was ever read. See Track.fileUri.
                        fileUri = "",
                        remoteId = track.id,
                        mimeType = track.mimeType,
                        // Zero means "the server did not say", which plain Subsonic never does.
                        sampleRateHz = track.sampleRateHz ?: 0,
                        bitDepth = track.bitDepth,
                        bitrateBps = track.bitrateBps
                    )
                }
            )
        }

        // Gone from the server, so gone from the wall. Tracks go with it; PlayEvent rows do not.
        val seen = remote.map { albumIdFor(it.id) }.toSet()
        val vanished = existing.keys - seen
        vanished.forEach { albumId ->
            trackDao.deleteForAlbum(albumId)
            albumDao.deleteById(albumId)
        }

        SyncState.Done(added = added, updated = updated, removed = vanished.size)
            .also { _state.value = it }
    }

    /**
     * Pulls one cover into the app's own storage.
     *
     * Downloaded rather than linked so the wall renders instantly and offline. A case is drawn
     * every frame; fetching artwork over the network to do that would make scrolling the wall
     * depend on the connection, which is the one part of this feature that has no reason to.
     */
    private suspend fun downloadCover(
        server: SubsonicServer,
        coverArtId: String,
        albumId: String
    ): String? = runCatching {
        val connection = (URL(client.coverUrl(server, coverArtId)).openConnection()
                as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        val bytes = try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) return null

        // Staged through the cache directory so this uses the same store as every other cover
        // in the app — one place that owns what is in `files/covers`, one place that deletes it.
        val staging = File(context.cacheDir, "remote").apply { mkdirs() }
        val temp = File(staging, "cover-${UUID.randomUUID()}.img")
        temp.writeBytes(bytes)
        val stored = coverStore.store(context, Uri.fromFile(temp), "$albumId-cover.jpg")
        temp.delete()
        stored
    }.getOrNull()

    /**
     * The shelf streamed records land on.
     *
     * A shelf of its own rather than the importer's "Unsorted", because where a record lives is
     * a real distinction here — this is the shelf that goes quiet when the network does — and
     * because mixing them would make the first sync scatter four hundred cases through a shelf
     * the user had arranged by hand.
     */
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
        /**
         * One server is supported today. The column is a string rather than a flag so a second
         * one is a new value here and not another migration.
         */
        const val SOURCE_ID = "subsonic"

        private const val SHELF_NAME = "Streaming"

        /**
         * Derived, never random — this is what makes a re-sync an update instead of a duplicate,
         * and what keeps a play history attached to the album it belongs to.
         */
        fun albumIdFor(remoteId: String) = "$SOURCE_ID:album:$remoteId"

        fun trackIdFor(remoteId: String) = "$SOURCE_ID:track:$remoteId"

        /**
         * The row to write: the server's facts, over the top of the phone's.
         *
         * **Pure, static, and tested, because the failure mode here is silent and permanent.**
         * The server is authoritative for the title, the artist, the year and the tracklist. It
         * knows nothing about the slot the case sits in, the accent the user picked, how many
         * times the record has been played or where it was left — and a plain
         * `@Insert(REPLACE)` would reset every one of those on each sync, quietly, with the
         * damage only visible as a wall that has forgotten itself.
         *
         * Each `previous?.x ?: y` below is one of those facts. They are enumerated rather than
         * copied wholesale so that adding a column to [Album] and forgetting it here shows up as
         * a compile error in this function rather than as data loss in the field.
         *
         * [now] is a parameter rather than a call to the clock so this can be asserted about.
         */
        fun merge(
            previous: Album?,
            summary: RemoteAlbum,
            albumId: String,
            shelfId: String,
            coverPath: String?,
            slotIndex: Int,
            now: Long
        ) = Album(
            id = albumId,
            // Once the user has moved a case, the shelf is their arrangement — an existing
            // album keeps whichever shelf it is on rather than being dragged back to the
            // default on the next refresh.
            shelfId = previous?.shelfId ?: shelfId,
            title = summary.title,
            artist = summary.artist,
            year = summary.year,
            caseCoverUri = coverPath,
            // The server has no disc face and never will; it is a Jawon concept. Carried
            // across so a picture the user chose is not wiped by a routine sync.
            discFaceUri = previous?.discFaceUri,
            spineHue = previous?.spineHue ?: 0f,
            slotIndex = previous?.slotIndex ?: slotIndex,
            playCount = previous?.playCount ?: 0,
            // "When did this join the library" is about this phone, not about the server's
            // own idea of when the files were added.
            addedAt = previous?.addedAt ?: now,
            lastTrackIndex = previous?.lastTrackIndex ?: 0,
            lastPositionMs = previous?.lastPositionMs ?: 0L,
            lastPlayedAt = previous?.lastPlayedAt,
            sourceId = SOURCE_ID,
            remoteId = summary.id
        )
    }
}
