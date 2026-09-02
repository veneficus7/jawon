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
 * Finds a track's words, offline.
 *
 * Two places to look, in this order:
 *
 * 1. **A `.lrc` beside the audio**, which is the only offline source of *timed* lyrics and is
 *    what every tool in the wild writes. Preferred when it exists, even though it is the less
 *    reliable of the two to reach.
 * 2. **The file's own `USLT` tag**, which is plain words with no timing. Always reachable,
 *    because it is inside the file Jawon already has permission to read.
 *
 * **The wrinkle, which is Jawon's alone.** Every file here arrives as a content URI, never a
 * path, so "the file next to it" is not a directory listing — it is a guess at a sibling
 * document id, and it only works for libraries imported through the folder picker. For tracks
 * found by the device scan there is often no reachable sibling at all, and those get the
 * embedded tag or nothing. That is a property of the storage model rather than a bug, and it is
 * why the source is shown to the user.
 *
 * Cached per track: the lyrics page is opened and closed repeatedly while a record plays, and
 * re-reading a megabyte of tag each time would be felt.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val cache = HashMap<String, Lyrics?>()

    suspend fun lyricsFor(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(track.id)) return@withContext cache[track.id] }
        val found = sidecarFor(track.fileUri) ?: embeddedIn(track.fileUri)
        synchronized(cache) { cache[track.id] = found }
        found
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
        return Lyrics(
            words.lines().map { LyricLine(LyricLine.UNTIMED, it) },
            synced = false,
            source = LyricSource.EMBEDDED
        )
    }

    private companion object {
        const val TAG = "JawonLyrics"
    }
}
