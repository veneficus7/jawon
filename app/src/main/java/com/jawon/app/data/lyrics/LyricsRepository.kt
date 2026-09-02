package com.jawon.app.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.jawon.app.data.local.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a track's words.
 *
 * Three places to look, and the order is the point: the device is asked before the network, so
 * a file carrying its own words is answered instantly, offline and privately, and nothing is
 * sent for a track whose lyrics were already sitting on the phone.
 *
 * 1. **A `.lrc` beside the audio** — the only offline source of *timed* lyrics, and what every
 *    tool in the wild writes.
 * 2. **The file's own `USLT` tag** — plain words, no timing, always reachable because it is
 *    inside a file Jawon already has permission to read.
 * 3. **LRCLIB**, and only if the first two found nothing. Free, unregistered, MIT-licensed and
 *    behind a Settings switch; what leaves the device is a title, an artist, an album and a
 *    duration. See [LrclibClient].
 *
 * **The wrinkle, which is Jawon's alone.** Every file here arrives as a content URI, never a
 * path, so "the file next to it" is not a directory listing. Two routes cover the two ways a
 * library arrives — a sibling document in a granted tree, or the indexed path of a scanned
 * file — and which one works is a property of the storage model rather than a choice.
 *
 * Cached twice over: in memory for the session, because the lyrics page is opened and closed
 * repeatedly while a record plays; and in the database for anything fetched, so a track is
 * never looked up twice.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val trackDao: com.jawon.app.data.local.TrackDao,
    private val preferences: com.jawon.app.data.prefs.AppPreferences,
    private val lrclib: LrclibClient
) {

    private val cache = HashMap<String, Lyrics?>()

    suspend fun lyricsFor(track: Track, albumTitle: String = ""): Lyrics? =
        withContext(Dispatchers.IO) {
            synchronized(cache) {
                if (cache.containsKey(track.id)) return@withContext cache[track.id]
            }
            // **Timing beats locality, and that is a change.**
            //
            // The order used to be sidecar, then tag, then network, with the first hit winning
            // outright — which meant a file carrying plain-text lyrics in its tag was answered
            // instantly and the network was never asked. That is correct for privacy and wrong
            // for the user: those lyrics can never follow the music, a timed version usually
            // exists online, and the page just sat there not scrolling. It is exactly what the
            // owner reported, twice.
            //
            // So local still goes first and still wins whenever it is *timed*. It is only when
            // the device's own answer has no timestamps that LRCLIB is asked, and only a synced
            // result displaces it — an untimed answer from the network is no better than the
            // untimed one already in hand, and the local copy is free and private, so it keeps
            // the slot on a tie.
            val local = sidecarFor(track.fileUri) ?: embeddedIn(track.fileUri)
            val found = if (local != null && local.synced) {
                local
            } else {
                online(track, albumTitle)?.takeIf { it.synced || local == null } ?: local
            }
            synchronized(cache) { cache[track.id] = found }
            found
        }

    /**
     * The third tier: ask LRCLIB, but only after the device has been asked first.
     *
     * **Order matters more than it looks.** A file that carries its own words is answered
     * instantly, offline, and privately; going to the network first would send a request for
     * every track whose lyrics were already sitting on the phone. So this runs only when both
     * local sources have come up empty.
     *
     * Cached in the database, and an empty string is cached too — that is how "we asked and
     * there are none" is told apart from "never asked", which is what stops a track without
     * lyrics being looked up again every time the page is opened.
     */
    private suspend fun online(track: Track, albumTitle: String): Lyrics? {
        val cached = runCatching { trackDao.fetchedLyrics(track.id) }.getOrNull()
        if (cached != null) return cached.takeIf { it.isNotBlank() }?.let { LrcParser.parse(it) }
            ?.copy(source = LyricSource.ONLINE)

        if (!preferences.fetchLyrics.value) return null
        val artist = track.artist?.takeIf { it.isNotBlank() } ?: return null

        val result = lrclib.fetch(
            title = track.title,
            artist = artist,
            album = albumTitle,
            durationSeconds = (track.durationMs / 1000).toInt()
        )
        val words = result?.best.orEmpty()
        runCatching { trackDao.setFetchedLyrics(track.id, words) }
        if (words.isBlank()) return null
        return LrcParser.parse(words)?.copy(source = LyricSource.ONLINE)
    }

    /** Forget everything, for when a library changes underneath us. */
    fun clear() = synchronized(cache) { cache.clear() }

    /**
     * The `.lrc` sitting next to the audio.
     *
     * Built by swapping the extension on the *document id* rather than on the URI, because the
     * id is the part that names the file — the URI around it is percent-encoded and rebuilding
     * it by hand produces something the provider will not resolve.
     */
    private fun sidecarFor(fileUri: String): Lyrics? {
        val uri = runCatching { fileUri.toUri() }.getOrNull() ?: return null
        val text = sidecarBytes(uri) ?: return null
        // Sidecars are written by many tools in many places; UTF-8 is overwhelmingly the norm
        // and a mis-decode shows as mojibake rather than as an error, so it is worth being
        // explicit rather than taking the platform default.
        return LrcParser.parse(String(text, Charsets.UTF_8))
    }

    /**
     * The sidecar's bytes, by whichever of the two routes this track's URI allows.
     *
     * **There are two because a library can arrive two ways.** Folders picked with the document
     * chooser give document URIs, where the sibling is another document in the same granted
     * tree. The device scan gives MediaStore URIs, which know nothing about neighbours — for
     * those the only handle on the folder is the indexed file path, so the sibling is read
     * straight off the filesystem. Whether that read is permitted depends on the Android
     * version and what the user granted, so it is attempted rather than assumed.
     */
    private fun sidecarBytes(uri: Uri): ByteArray? {
        siblingLrc(uri)?.let { sibling ->
            runCatching {
                context.contentResolver.openInputStream(sibling)?.use { it.readBytes() }
            }.getOrNull()?.let { return it }
        }
        return mediaStoreSidecar(uri)
    }

    private fun mediaStoreSidecar(uri: Uri): ByteArray? = runCatching {
        val path = context.contentResolver.query(
            uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: return null

        val dot = path.lastIndexOf('.')
        if (dot <= path.lastIndexOf('/')) return null
        val lrc = java.io.File(path.substring(0, dot) + ".lrc")
        if (!lrc.isFile || !lrc.canRead()) return null
        lrc.readBytes()
    }.onFailure { Log.i(TAG, "no readable sidecar beside $uri", it) }.getOrNull()

    private fun siblingLrc(uri: Uri): Uri? = runCatching {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val documentId = DocumentsContract.getDocumentId(uri)
        // No extension means nothing to swap, and a dot in a folder name is not an extension.
        val dot = documentId.lastIndexOf('.')
        if (dot <= documentId.lastIndexOf('/')) return null
        val lrcId = documentId.substring(0, dot) + ".lrc"
        DocumentsContract.buildDocumentUriUsingTree(uri, lrcId)
    }.getOrNull()

    /**
     * The `USLT` frame in the file's own tag.
     *
     * Only the first stretch of the file is read. The tag is at the front, and a track can be
     * eighty megabytes; reading all of it to find a few hundred words would be the kind of
     * main-thread-adjacent I/O that ENGINEERING.md rule 5 exists to prevent.
     */
    private fun embeddedIn(fileUri: String): Lyrics? {
        val uri = runCatching { fileUri.toUri() }.getOrNull() ?: return null
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(Id3Lyrics.SCAN_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        }.onFailure { Log.i(TAG, "could not read $uri for lyrics", it) }.getOrNull() ?: return null

        val words = Id3Lyrics.read(head) ?: return null

        // **Run the tag through the .lrc parser rather than assuming it is plain text.**
        //
        // `USLT` is the *unsynchronised* lyrics frame, and the obvious reading of that is that
        // anything found in it has no timing — which is what this did, hard-coding `synced =
        // false` and marking every line untimed. But the frame holds free text, and a great many
        // taggers write ordinary LRC into it, timestamps and all, because the synchronised frame
        // (`SYLT`) is barely supported by anything that would read it back. Treating those files
        // as plain text threw away timing that was sitting right there in the tag, and the owner
        // noticed exactly that: lyrics from the file's own tag never followed the music.
        //
        // The parser already tells timed from untimed and reports `synced` accordingly, so this
        // is simply asking it instead of deciding in advance. A tag that really is plain text
        // comes back untimed, as before.
        return LrcParser.parse(words)?.copy(source = LyricSource.EMBEDDED)
    }

    private companion object {
        const val TAG = "JawonLyrics"
    }
}
