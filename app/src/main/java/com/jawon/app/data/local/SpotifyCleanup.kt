package com.jawon.app.data.local

import com.jawon.app.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes away what the Spotify route left on a phone, now that the route itself is gone.
 *
 * **Deleting the code was not enough on its own**, because the code was not the only thing it
 * wrote. Every album a Spotify sync put on the wall is still a row, and without the route those
 * rows are records that cannot play: their tracks have no file behind them — Spotify decoded them
 * in its own process — so the player either fails on an empty URI or, on a phone that also has a
 * music server, asks that server for a Spotify id it has never heard of. So they go the way any
 * album goes, through [AlbumRemover], which takes the tracks, the listening history and the cover
 * images with it.
 *
 * The listening history is not a loss worth guarding. `ListeningViewModel` only counts plays
 * whose album still exists, so a Spotify album's plays were invisible the moment the album went;
 * kept, they would be exactly the unreachable, growing rows `AlbumRemover` exists to prevent.
 *
 * Then the shelf the sync made for them, if that emptied it, and the sign-in tokens, which would
 * otherwise sit in private storage with no button left anywhere to clear them.
 *
 * ## Every launch, not once behind a flag
 *
 * `TrackArtistBackfill` runs once and remembers that it has. This deliberately does not, because
 * Spotify rows can arrive *after* it has run: from a backup written while the route existed, and
 * from Android restoring this app's preferences onto a new phone. Checking costs one query on a
 * small table, and finds nothing on every phone that never used Spotify.
 *
 * Deletable once nobody can still be carrying a Spotify album — which, because of backups, is
 * later than the last install that had the route.
 */
@Singleton
class SpotifyCleanup @Inject constructor(
    private val albumDao: AlbumDao,
    private val shelfDao: ShelfDao,
    private val remover: AlbumRemover,
    private val preferences: AppPreferences
) {

    /** Removes every Spotify album, and returns how many there were. */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        preferences.forgetSpotify()

        val albums = albumDao.forSource(SOURCE_ID)
        if (albums.isEmpty()) return@withContext 0
        remover.removeAll(albums.map { it.id })

        // Only a shelf the removal left empty. One still holding records is being used for
        // something, whatever it is called. An empty library with no shelves is a fresh install,
        // and the first import makes a shelf of its own.
        val occupied = albumDao.listAll().mapTo(HashSet()) { it.shelfId }
        shelfDao.listAll()
            .filter { it.name == SHELF_NAME && it.id !in occupied }
            .forEach { shelfDao.delete(it) }

        albums.size
    }

    private companion object {
        /** What the sync wrote into `Album.sourceId`. */
        const val SOURCE_ID = "spotify"

        /** The shelf it created to put them on. */
        const val SHELF_NAME = "Spotify"
    }
}
