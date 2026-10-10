package com.jawon.app.playback

import android.app.SearchManager
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.LibrarySearch
import com.jawon.app.data.playback.LibraryTree
import com.jawon.app.data.playback.QueueBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the player says to anything that reaches it from outside the rooms (0.7.6): a car browsing
 * the shelves, the assistant asked to "play Loveless on Jawon", the system's media controls asking
 * what there is to resume.
 *
 * Four jobs, and the album is the unit in all of them (PLAN §2):
 *
 * - **Who may connect** — [SessionGate].
 * - **Browsing** — [LibraryTree], turned into media items here.
 * - **A request to play something it was only given a name or an id for** — every such request
 *   becomes a whole record with a place to start ([QueueBuilder]). A track chosen on a car's screen
 *   plays its record from that track, exactly as choosing it on the deck does.
 * - **Resumption** — the record that was on last, where it stopped.
 *
 * The rooms' own requests pass straight through: they arrive as finished media items, with their
 * addresses, and are already what [QueueBuilder] would have made.
 */
@UnstableApi
class LibraryCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val queues: QueueBuilder
) : MediaLibrarySession.Callback {

    // ---- Who may connect ---------------------------------------------------------------------

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val admitted = SessionGate.admits(
            ownApp = controller.packageName == context.packageName ||
                session.isMediaNotificationController(controller),
            car = session.isAutomotiveController(controller) || session.isAutoCompanionController(controller),
            trusted = controller.isTrusted
        )
        return if (admitted) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
    }

    // ---- Browsing ----------------------------------------------------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Records as a grid of their covers, songs as a list: said once here, for the whole tree.
        val style = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        val root = MediaItem.Builder()
            .setMediaId(LibraryTree.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Jawon")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
        return Futures.immediateFuture(
            LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(style).build())
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
        val node = LibraryTree.parse(parentId)
        val tracks = if (node is LibraryTree.Node.OfAlbum) trackDao.listForAlbum(node.albumId) else emptyList()
        val entries = LibraryTree.children(node, shelfDao.listAll(), albumDao.listAll(), tracks)
            ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItemList(paged(entries.map(::browseItem), page, pageSize), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = answer {
        val node = LibraryTree.parse(mediaId)
        val track = (node as? LibraryTree.Node.OfTrack)?.let { trackDao.findById(it.trackId) }
        val entry = LibraryTree.entry(node, shelfDao.listAll(), albumDao.listAll(), track)
            ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItem(browseItem(entry), null)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val count = withContext(Dispatchers.IO) { found(query).size }
            session.notifySearchResultChanged(browser, query, count, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
        LibraryResult.ofItemList(paged(found(query), page, pageSize), params)
    }

    private suspend fun found(query: String): List<MediaItem> =
        LibrarySearch.results(query, albumDao.listAll(), trackDao.listAll()).map { hit ->
            browseItem(
                hit.track?.let { LibraryTree.trackEntry(it, hit.album) } ?: LibraryTree.albumEntry(hit.album)
            )
        }

    // ---- Being asked to play -----------------------------------------------------------------

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaItemsWithStartPosition> {
        // The rooms hand over finished items; nothing to work out.
        if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration != null }) {
            return Futures.immediateFuture(MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
        }
        return resolve {
            val asked = mediaItems.getOrNull(startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)))
                ?: mediaItems.firstOrNull()
            val queue = asked?.let { queueFor(it) }
                ?: throw UnsupportedOperationException("Nothing in the library answers that")
            MediaItemsWithStartPosition(queue.items, queue.startIndex, queue.startPositionMs)
        }
    }

    /**
     * Adding to the queue is not something Jawon does — a queue here is exactly one record — but a
     * controller that asks for it must not be left with an exception: it gets the record the item
     * belongs to, which is the nearest true answer.
     */
    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration != null }) {
            return Futures.immediateFuture(mediaItems)
        }
        return resolve {
            mediaItems.firstOrNull()?.let { queueFor(it) }?.items
                ?: throw UnsupportedOperationException("Nothing in the library answers that")
        }
    }

    private suspend fun queueFor(item: MediaItem): QueueBuilder.Queue? {
        val request = item.requestMetadata
        // "Play ⟨something⟩ on Jawon": words, and sometimes what kind of thing each word is.
        if (request.searchQuery != null || (item.mediaId.isEmpty() && request.extras != null)) {
            return queues.forSearch(request.searchQuery, hintsFrom(request.extras))
        }
        return when (val node = LibraryTree.parse(item.mediaId)) {
            // A record chosen whole picks up where it was left, as taking it off the wall does.
            is LibraryTree.Node.OfAlbum -> queues.album(node.albumId, QueueBuilder.From.WhereItWasLeft)
            // A track chosen by name plays its record from that track.
            is LibraryTree.Node.OfTrack -> queues.forTrack(node.trackId)
            // A shelf, or the root: something from it — the same answer "play music" gets.
            else -> queues.forSearch(null, LibrarySearch.Hints())
        }
    }

    private fun hintsFrom(extras: Bundle?): LibrarySearch.Hints = LibrarySearch.Hints(
        album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
        artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
        title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
    )

    // ---- Resumption --------------------------------------------------------------------------

    /**
     * What to play when something says "play" and nothing is loaded: the record that was on last,
     * where it stopped. Also what the system asks, without playing anything, to draw its "resume"
     * card after the app has gone.
     *
     * Whether a *button* may wake a closed player at all is decided before this is ever reached —
     * `ResumeButtonReceiver`. By here, somebody asked.
     */
    override fun onPlaybackResumption(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaItemsWithStartPosition> = resolve {
        val queue = queues.lastPlayed() ?: throw UnsupportedOperationException("Nothing has been played yet")
        MediaItemsWithStartPosition(queue.items, queue.startIndex, queue.startPositionMs)
    }

    // ---- Plumbing ----------------------------------------------------------------------------

    private fun browseItem(entry: LibraryTree.Entry): MediaItem {
        val folder = entry.kind != LibraryTree.Kind.TRACK
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setSubtitle(entry.subtitle)
            .setArtist(entry.subtitle.takeIf { entry.kind == LibraryTree.Kind.ALBUM || entry.kind == LibraryTree.Kind.TRACK })
            .setAlbumTitle(entry.albumTitle)
            .setArtworkUri(entry.coverOf?.let(QueueBuilder::coverUri))
            .setIsBrowsable(folder)
            .setIsPlayable(!folder)
            .setMediaType(
                when (entry.kind) {
                    LibraryTree.Kind.FOLDER -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    LibraryTree.Kind.SHELF -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                    LibraryTree.Kind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
                    LibraryTree.Kind.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
                }
            )
        // The list of shelves is a list — names, with a cover beside each — where everything else
        // that opens is a grid of records.
        if (entry.mediaId == LibraryTree.SHELVES) {
            metadata.setExtras(
                Bundle().apply {
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                    )
                }
            )
        }
        return MediaItem.Builder().setMediaId(entry.mediaId).setMediaMetadata(metadata.build()).build()
    }

    private fun paged(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return items
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt())
    }

    /** A library answer, worked out off the main thread. */
    private fun <T : Any> answer(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        scope.launch {
            try {
                future.set(withContext(Dispatchers.IO) { block() })
            } catch (e: Exception) {
                future.set(LibraryResult.ofError(SessionError.ERROR_IO))
            }
        }
        return future
    }

    /** A queue, worked out off the main thread; a failure is the future's, as Media3 expects. */
    private fun <T : Any> resolve(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(withContext(Dispatchers.IO) { block() })
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    companion object {
        /** The words of a spoken request, when it arrives as an intent rather than through the session. */
        fun queryOf(extras: Bundle?): String? = extras?.getString(SearchManager.QUERY)
    }
}
