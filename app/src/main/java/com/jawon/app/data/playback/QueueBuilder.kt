package com.jawon.app.data.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.data.remote.SubsonicClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a record into something the player can be handed: its tracks as media items, and where to
 * start.
 *
 * **One place, because there is no longer one caller** (0.7.6). Until now only the rooms started a
 * record, through `PlaybackRepository.playAlbum`. Now a car asks for a track by its id, the
 * assistant asks for "Loveless", the system asks what to resume, and the widget, the tile and the
 * shortcuts each ask for the record that was on — and every one of them has to end with the same
 * queue the room would have built, or the deck, the listening record and the resume point (all of
 * which read the queue) each see a slightly different thing.
 */
@Singleton
class QueueBuilder @Inject constructor(
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val shelfDao: ShelfDao,
    /**
     * Only ever asked to *build a URL*, never to fetch anything — see [SubsonicClient.streamUrl].
     */
    private val subsonic: SubsonicClient,
    private val preferences: AppPreferences
) {

    /** A record ready to play: its tracks, the same tracks as media items, and where to begin. */
    class Queue(
        val album: Album,
        val tracks: List<Track>,
        val items: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long
    )

    /** Where on a record to start. */
    sealed interface From {
        /** Where it was left — the room's own rule, [ResumePoint]. */
        data object WhereItWasLeft : From

        data object TheTop : From

        /** A track chosen by name: that one, from its beginning. */
        data class Track(val trackId: String) : From
    }

    suspend fun album(albumId: String, from: From): Queue? {
        val album = albumDao.findById(albumId) ?: return null
        val tracks = trackDao.listForAlbum(albumId)
        if (tracks.isEmpty()) return null
        val at = when (from) {
            From.TheTop -> ResumePoint.TOP
            From.WhereItWasLeft ->
                ResumePoint.of(album.lastTrackIndex, album.lastPositionMs, tracks.map { it.durationMs })
            is From.Track ->
                ResumePoint.At(tracks.indexOfFirst { it.id == from.trackId }.coerceAtLeast(0), 0L)
        }
        return Queue(album, tracks, tracks.map { item(it, album) }, at.index, at.positionMs)
    }

    /** The record a track is on, starting at that track. */
    suspend fun forTrack(trackId: String): Queue? {
        val albumId = trackDao.albumIdOf(trackId) ?: return null
        return album(albumId, From.Track(trackId))
    }

    /** The record that was on last, where it stopped. */
    suspend fun lastPlayed(): Queue? =
        albumDao.lastPlayed()?.let { album(it.id, From.WhereItWasLeft) }

    /** What a spoken or typed request means here — see [LibrarySearch]. */
    suspend fun forSearch(query: String?, hints: LibrarySearch.Hints): Queue? {
        val pick = LibrarySearch.pick(
            query = query,
            hints = hints,
            shelves = shelfDao.listAll(),
            albums = albumDao.listAll(),
            tracks = trackDao.listAll()
        ) ?: return null
        val from = when {
            pick.trackId != null -> From.Track(pick.trackId)
            pick.resume -> From.WhereItWasLeft
            else -> From.TheTop
        }
        return album(pick.albumId, from)
    }

    /**
     * One track as the player takes it, wherever it happens to live.
     *
     * The branch is on [Track.remoteId] rather than on the album's `sourceId`, because the track
     * is the thing that knows whether it has a file behind it. A remote track with no server
     * configured falls through to the local path and gets an empty URI, which the player reports
     * as an error rather than failing silently — the right outcome for a state that should not be
     * reachable.
     *
     * **For a streamed track the URI is built now, not stored**, and the cache key is the track,
     * not the URL: a Subsonic stream URL carries a one-shot salt, so the same track would
     * otherwise arrive under a different cache key on every play — a cache that fills and never
     * hits, and a scratch that goes to the network for every seek.
     */
    fun item(track: Track, album: Album?): MediaItem {
        val remoteId = track.remoteId
        val server = preferences.musicServer.value
        val builder = MediaItem.Builder().setMediaId(track.id)
        if (remoteId != null && server.isConfigured) {
            builder.setUri(subsonic.streamUrl(server, remoteId)).setCustomCacheKey(track.id)
        } else {
            builder.setUri(track.fileUri)
        }
        return builder.setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                // The track's own credit first: on a compilation the notification should name
                // whoever is actually singing. Files with no per-track tag fall back to the record's.
                .setArtist(track.artist?.takeIf { it.isNotBlank() } ?: album?.artist)
                .setAlbumTitle(album?.title)
                .setAlbumArtist(album?.artist)
                // The record's own cover, for the notification, the lock screen and a car — the
                // one the wall shows, including a cover that was looked up because the files
                // carry none.
                .setArtworkUri(album?.takeIf { it.caseCoverUri != null }?.let { coverUri(it.id) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        ).build()
    }

    companion object {
        /** `content://com.jawon.app.covers/<album id>` — served by `CoverProvider`. */
        const val COVER_AUTHORITY = "com.jawon.app.covers"

        fun coverUri(albumId: String): Uri =
            Uri.Builder().scheme("content").authority(COVER_AUTHORITY).appendPath(albumId).build()
    }
}
