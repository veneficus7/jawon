package com.jawon.app.data.importer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **"Open with Jawon"** (0.7.6): a song, or an album as a ZIP, handed over by another app — a
 * file manager, a browser's downloads, a share sheet.
 *
 * What is handed over is a *loan*. The address another app gives is good for as long as this
 * screen is up and no longer, so a record that is going to stand on a shelf needs its music
 * somewhere that lasts: it is copied into **`Music/Jawon`** on the phone's own storage — the
 * user's music folder, where a file manager and every other player can see it, and where it
 * stays if Jawon is ever uninstalled. (The same reasoning a ripped CD's folder follows.) A ZIP
 * has to be unpacked somewhere regardless; a song already in the phone's music index is left
 * where it is.
 *
 * No permission is asked for any of this: an app may add files of its own to shared storage and
 * read them back. On Android 9, which has no such route without a storage permission, the copy
 * goes to the app's own music folder instead.
 */
@Singleton
class OpenedMusic @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _pending = MutableStateFlow<List<Uri>>(emptyList())

    /** What has been handed over and not yet taken up by Add music. */
    val pending: StateFlow<List<Uri>> = _pending.asStateFlow()

    fun offer(uris: List<Uri>) {
        _pending.value = uris
    }

    /** Takes what is waiting, so it is not taken twice. */
    fun take(): List<Uri> = _pending.value.also { _pending.value = emptyList() }

    /** A file now somewhere that lasts, the album it belongs to, and that album's cover if it came with one. */
    class Kept(val file: DocumentFile, val group: String, val cover: Uri?)

    /**
     * Brings everything in [uris] somewhere that lasts and says what is there.
     *
     * [onProgress] is told how many songs have been kept so far — an archive does not say how
     * many it holds until it has been read to the end.
     */
    suspend fun keep(uris: List<Uri>, onProgress: (songs: Int) -> Unit): List<Kept> =
        withContext(Dispatchers.IO) {
            val kept = mutableListOf<Kept>()
            for (uri in uris) {
                val name = displayName(uri)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                when {
                    OpenedPlan.isZip(name, mime) -> kept += unpack(uri, name) { onProgress(kept.size + it) }
                    OpenedPlan.isAudio(name, mime) -> {
                        keepSong(uri, name, mime)?.let { kept += it }
                        onProgress(kept.size)
                    }
                }
            }
            kept
        }

    // ---- A song ------------------------------------------------------------------------------

    private suspend fun keepSong(uri: Uri, name: String?, mime: String?): Kept? {
        val group = "song:" + UUID.randomUUID()
        // Already in the phone's music index, and readable for good: it stays where it is.
        if (uri.authority == MediaStore.AUTHORITY && canReadAudio()) {
            return DocumentFile.fromSingleUri(context, uri)?.let { Kept(it, group, null) }
        }
        val safe = OpenedPlan.safeName(name ?: "Song")?.let { withExtension(it, mime) } ?: return null
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { write(FOLDER, safe, sizeOf(uri), it) }?.let { Kept(it, group, null) }
    }

    /** A handed-over file sometimes has no extension in its name; what it says it is supplies one. */
    private fun withExtension(name: String, mime: String?): String {
        if (name.substringAfterLast('.', "").lowercase() in OpenedPlan.AUDIO_EXTENSIONS) return name
        val ext = when (mime) {
            "audio/mpeg" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/aac", "audio/m4a" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/x-wav", "audio/wav" -> "wav"
            else -> return name
        }
        return "$name.$ext"
    }

    // ---- An album as a ZIP -------------------------------------------------------------------

    private suspend fun unpack(uri: Uri, archiveName: String?, onProgress: (songs: Int) -> Unit): List<Kept> {
        // Twice through: once to see what albums are inside — which decides the folders — and
        // once to write. An archive read from another app is a stream; it cannot be rewound.
        val groups = HashSet<String>()
        var entries = 0
        try {
            open(uri)?.use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > OpenedPlan.MAX_ENTRIES) throw IllegalStateException(TOO_BIG)
                    if (OpenedPlan.climbs(entry.name)) throw IllegalStateException(UNSAFE)
                    OpenedPlan.plan(entry.name, entry.isDirectory)
                        ?.takeIf { it.kind == OpenedPlan.Kind.AUDIO }
                        ?.let { groups += it.group }
                }
            } ?: return emptyList()
        } catch (e: java.util.zip.ZipException) {
            // Android 14 refuses an entry that climbs by itself, in its own words; and a file that
            // is not a ZIP at all, or half of one, ends here too.
            throw IllegalStateException(if (e.message?.contains("entry path") == true) UNSAFE else BROKEN)
        }
        if (groups.isEmpty()) return emptyList()

        val songs = mutableListOf<Pair<String, DocumentFile>>()
        val covers = HashMap<String, Pair<Int, Uri>>()
        var written = 0L
        open(uri)?.use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val planned = OpenedPlan.plan(entry.name, entry.isDirectory) ?: continue
                when (planned.kind) {
                    OpenedPlan.Kind.AUDIO -> {
                        val folder = "$FOLDER/" + OpenedPlan.folderFor(archiveName, planned.group, groups.size)
                        val counted = Counted(zip, OpenedPlan.MAX_BYTES - written)
                        write(folder, planned.name, entry.size, counted)?.let { songs += planned.group to it }
                        written += counted.read
                        onProgress(songs.size)
                    }
                    // The album's picture, kept aside until the album is: the highest-ranked one
                    // in its folder. Only for groups that have music in them.
                    OpenedPlan.Kind.COVER -> if (planned.group in groups &&
                        planned.coverRank > (covers[planned.group]?.first ?: 0)
                    ) {
                        val file = File(File(context.cacheDir, "opened").apply { mkdirs() }, UUID.randomUUID().toString())
                        file.outputStream().use { Counted(zip, MAX_COVER_BYTES).copyTo(it) }
                        covers[planned.group] = planned.coverRank to Uri.fromFile(file)
                    }
                }
            }
        }
        val archive = "zip:" + UUID.randomUUID()
        return songs.map { (group, file) -> Kept(file, "$archive/$group", covers[group]?.second) }
    }

    private fun open(uri: Uri): ZipInputStream? =
        context.contentResolver.openInputStream(uri)?.let { ZipInputStream(it.buffered()) }

    /** Reads through to another stream, and refuses to read past a limit — an archive can lie about its size. */
    private class Counted(private val from: InputStream, private val limit: Long) : InputStream() {
        var read = 0L
            private set

        override fun read(): Int = from.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            from.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(n: Int) {
            read += n
            if (read > limit) throw IllegalStateException(TOO_BIG)
        }
    }

    // ---- Writing -----------------------------------------------------------------------------

    /**
     * Writes one file into [folder] under the phone's music, and returns it as something the
     * import can read. A file of the same name and size already there is used as it is: opening
     * the same download twice must not fill the phone with copies.
     */
    private fun write(folder: String, name: String, size: Long, input: InputStream): DocumentFile? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), folder.removePrefix("$FOLDER/"))
                .apply { mkdirs() }
            val file = File(dir, name)
            if (!(file.isFile && size > 0 && file.length() == size)) {
                file.outputStream().use { input.copyTo(it) }
            }
            return DocumentFile.fromFile(file)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${Environment.DIRECTORY_MUSIC}/$folder/"
        if (size > 0) {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.Audio.Media.SIZE} = ?",
                arrayOf(path, name, size.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val existing = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                    return DocumentFile.fromSingleUri(context, existing)
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, OpenedPlan.mimeFor(name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, path)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val created = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(created)?.use { input.copyTo(it) } ?: throw IllegalStateException("No way to write")
        } catch (e: Exception) {
            // Half a song is worse than none: nothing is left behind.
            resolver.delete(created, null, null)
            throw e
        }
        resolver.update(created, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return DocumentFile.fromSingleUri(context, created)
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    private fun canReadAudio(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /** Where opened music is kept, under the phone's `Music`. */
        const val FOLDER = "Jawon"
        const val MAX_COVER_BYTES = 24L * 1024 * 1024
        const val TOO_BIG = "That ZIP is too large to be an album."
        const val UNSAFE = "That ZIP can't be opened safely: one of its files points outside it."
        const val BROKEN = "That ZIP couldn't be read. It may not have finished downloading."
    }
}
