package com.jawon.app.data.spotify

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

/** Where a Spotify sync has got to, for the settings screen. */
sealed interface SpotifySyncState {
    data object Idle : SpotifySyncState
    data class Running(val done: Int, val total: Int, val album: String) : SpotifySyncState
    data class Done(val added: Int, val updated: Int, val removed: Int) : SpotifySyncState
    data class Failed(val reason: String) : SpotifySyncState
}

/**
 * Puts the albums saved in a Spotify account onto the wall.
 *
 * **No migration was needed for any of this**, which is worth saying because it looks like it
 * should have been. Schema 6 added `Album.sourceId`, `Album.remoteId` and `Track.remoteId` for
 * the Subsonic route, and they were deliberately written as "which source, and what is this
 * called there" rather than "which Subsonic server". Spotify is a second value in the same
 * column. The one place that shape is visibly strained is the technical fields — Spotify reports
 * no codec, bitrate, sample rate or bit depth, so those are stored as empty and zero, and the
 * deck's quality readout renders nothing rather than a fiction.
 *
 * The re-sync rules are the same as `RemoteLibrarySync`'s and for the same reason: ids are
 * derived from Spotify's, never minted, so a refresh updates a row rather than inserting a
 * duplicate and orphaning the play history hanging off it; and [merge] carries every local field
 * across untouched, because Spotify has no opinion about which slot a case sits in or how many
 * times it has been played on this phone.
 *
 * An album unsaved in Spotify is removed here. Its `PlayEvent` rows are kept — the listening
 * record records what was listened to, and one that edits itself when a library changes is not a
 * record.
 */
@Singleton
class SpotifyLibrarySync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: SpotifyClient,
    private val session: SpotifySession,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val shelfDao: ShelfDao,
    private val coverStore: CoverImageStore
) {

    private val _state = MutableStateFlow<SpotifySyncState>(SpotifySyncState.Idle)
    val state: StateFlow<SpotifySyncState> = _state.asStateFlow()

    suspend fun sync(): SpotifySyncState = withContext(Dispatchers.IO) {
        val token = session.token()
            ?: return@withContext fail("Sign in to Spotify first")

        val saved = when (val result = client.savedAlbums(token)) {
            is SpotifyResult.Failed -> return@withContext fail(describe(result.failure))
            is SpotifyResult.Ok -> result.value
        }

        val shelfId = ensureShelf()
        val existing = albumDao.forSource(SOURCE_ID).associateBy { it.id }
        var nextSlot = (albumDao.getForShelf(shelfId).first().maxOfOrNull { it.slotIndex } ?: -1) + 1

        var added = 0
        var updated = 0

        saved.forEachIndexed { index, album ->
            _state.value = SpotifySyncState.Running(index, saved.size, album.title)

            val tracks = when (val result = client.albumTracks(token, album.id)) {
                // One album the API stumbles on costs that album, not the other four hundred —
                // the same rule the importer applies to an unreadable folder.
                is SpotifyResult.Failed -> return@forEachIndexed
                is SpotifyResult.Ok -> result.value
            }
            if (tracks.isEmpty()) return@forEachIndexed

            val albumId = albumIdFor(album.id)
            val previous = existing[albumId]
            val coverPath = previous?.caseCoverUri
                ?: album.coverUrl?.let { downloadCover(it, albumId) }

            albumDao.insert(
                merge(previous, album, albumId, shelfId, coverPath, nextSlot, System.currentTimeMillis())
            )
            if (previous == null) { added++; nextSlot++ } else updated++

            trackDao.deleteForAlbum(albumId)
            trackDao.insertAll(
                tracks.mapIndexed { position, track ->
                    Track(
                        id = trackIdFor(track.id),
                        albumId = albumId,
                        trackNumber = track.trackNumber ?: (position + 1),
                        discNumber = track.discNumber,
                        title = track.title,
                        // Null where it equals the album's credit, so the deck falls back the way
                        // it does everywhere else rather than repeating the artist on every line.
                        artist = track.artist?.takeIf { it != album.artist },
                        durationMs = track.durationMs,
                        // Never played through ExoPlayer, so there is no URI to hold. Spotify
                        // plays this in its own process; see SpotifyPlayer.
                        fileUri = "",
                        remoteId = track.id,
                        // Everything Spotify declines to say about the audio.
                        mimeType = "",
                        sampleRateHz = 0,
                        bitDepth = null,
                        bitrateBps = 0
                    )
                }
            )
        }

        val seen = saved.map { albumIdFor(it.id) }.toSet()
        val vanished = existing.keys - seen
        vanished.forEach {
            trackDao.deleteForAlbum(it)
            albumDao.deleteById(it)
        }

        SpotifySyncState.Done(added, updated, vanished.size).also { _state.value = it }
    }

    private fun fail(reason: String) =
        SpotifySyncState.Failed(reason).also { _state.value = it }

    private suspend fun downloadCover(url: String, albumId: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        val bytes = try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) return null
        val staging = File(context.cacheDir, "spotify").apply { mkdirs() }
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
        const val SOURCE_ID = "spotify"
        private const val SHELF_NAME = "Spotify"

        fun albumIdFor(spotifyId: String) = "$SOURCE_ID:album:$spotifyId"

        fun trackIdFor(spotifyId: String) = "$SOURCE_ID:track:$spotifyId"

        /**
         * Spotify's facts over the phone's, and nothing else touched.
         *
         * Same contract as `RemoteLibrarySync.merge`, enumerated rather than copied for the same
         * reason: each `previous?.x ?: y` below is a value that a plain `@Insert(REPLACE)` would
         * silently reset on every refresh, and the damage would only ever show up as a wall that
         * had forgotten how much it had been played.
         */
        fun merge(
            previous: Album?,
            album: SpotifyAlbum,
            albumId: String,
            shelfId: String,
            coverPath: String?,
            slotIndex: Int,
            now: Long
        ) = Album(
            id = albumId,
            shelfId = previous?.shelfId ?: shelfId,
            title = album.title,
            artist = album.artist,
            year = album.year,
            caseCoverUri = coverPath,
            discFaceUri = previous?.discFaceUri,
            spineHue = previous?.spineHue ?: 0f,
            slotIndex = previous?.slotIndex ?: slotIndex,
            playCount = previous?.playCount ?: 0,
            addedAt = previous?.addedAt ?: now,
            lastTrackIndex = previous?.lastTrackIndex ?: 0,
            lastPositionMs = previous?.lastPositionMs ?: 0L,
            lastPlayedAt = previous?.lastPlayedAt,
            sourceId = SOURCE_ID,
            // Spotify's own id, not the URI — the URI is derivable from it and storing both
            // would be two things to keep in step.
            remoteId = album.id
        )

        fun describe(failure: SpotifyFailure): String = when (failure) {
            SpotifyFailure.NeedsLogin -> "Spotify signed you out. Connect again."
            SpotifyFailure.NeedsPremium -> "Spotify Premium is required to play from the API."
            SpotifyFailure.NoDevice -> "Open the Spotify app once so it can be played to."
            is SpotifyFailure.RateLimited ->
                "Spotify asked to wait ${failure.retryAfterSeconds}s. Try again shortly."
            is SpotifyFailure.Other -> failure.message
        }
    }
}
