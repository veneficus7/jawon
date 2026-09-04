package com.jawon.app.data.local

import com.jawon.app.data.importer.CoverImageStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything one album leaves behind, deleted in one place.
 *
 * **This exists because the steps were about to be copied.** Removing a record touches four
 * things — its tracks, its listening history, its row, and the images copied into private
 * storage at import — and until now that sequence lived inline in the room's view model as the
 * only caller. The shelf manager needs it too, and four steps duplicated into a second method
 * is precisely how one of them gets forgotten.
 *
 * It had already happened. [PlayEventDao.deleteForAlbum] exists, documents itself as *"for the
 * delete path: an album's history goes with it"*, and was **never called from anywhere**. Every
 * album ever removed left its entire listening history behind, keyed by a UUID that can never
 * match a record again — invisible, unreachable, and growing.
 *
 * **The user's audio files are never touched.** They live in the user's own folders and Jawon
 * holds only a read grant on them; an album here is a record *about* those files. What goes is
 * only what Jawon made.
 */
@Singleton
class AlbumRemover @Inject constructor(
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playEventDao: PlayEventDao,
    private val coverStore: CoverImageStore
) {

    /**
     * Removes one album and everything Jawon stored about it.
     *
     * Ordered so nothing is orphaned by a failure part-way: the children go before the row they
     * point at, and the images last, because a cover left on disk is recoverable noise while a
     * row whose tracks are gone is a record that cannot be played or removed again.
     */
    suspend fun remove(albumId: String) {
        val album = albumDao.findById(albumId)
        trackDao.deleteForAlbum(albumId)
        playEventDao.deleteForAlbum(albumId)
        albumDao.deleteById(albumId)
        album?.let { coverStore.deleteAll(it.caseCoverUri, it.discFaceUri) }
    }

    /** The same, for several at once. See the shelf manager's selection mode. */
    suspend fun removeAll(albumIds: Collection<String>) = albumIds.forEach { remove(it) }
}
