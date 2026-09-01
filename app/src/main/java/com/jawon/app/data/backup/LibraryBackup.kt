package com.jawon.app.data.backup

import android.content.Context
import android.net.Uri
import com.jawon.app.data.local.Album
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.PlayEvent
import com.jawon.app.data.local.PlayEventDao
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writing the collection out to a file, and reading it back.
 *
 * **The thing this protects is the curation, not the music.** Jawon never touches the audio —
 * it is on the phone or the card and it stays there. What lives only in this app's database is
 * the part that took work: which records are on which shelf, in what order, what the shelves
 * are called and what colour they glow, the titles and artists you corrected by hand, the
 * artwork you chose or that was looked up for you, and every hour of listening the wear on the
 * cases is drawn from. None of that could be reconstructed, and until now an uninstall, a
 * failed update or a new phone took all of it.
 *
 * ### What a restore can and cannot do
 *
 * On the same phone it is complete: everything above comes back exactly.
 *
 * **Onto a different phone, the shelves come back but the audio may not.** Every track is
 * stored as a content URI, and a content URI is not portable — a MediaStore id belongs to the
 * device that issued it, and a SAF grant belongs to the install that was given it. The shelf
 * arrangement, the artwork and the history all restore, and the tracks that cannot be resolved
 * simply will not play until those albums are imported again. That is worth being plain about
 * rather than discovering later, so the Settings screen says it too.
 *
 * ### The format
 *
 * A zip, because artwork is files: `library.json` for the rows, `covers/` for the images they
 * point at. JSON is written with `org.json`, which is in the platform — a backup is two
 * functions and does not justify a serialization dependency.
 *
 * Restoring **merges** rather than replaces. An album already present is overwritten by the
 * backup's copy; one that is not is added; one that exists only on the device is left alone.
 * A restore that wiped the current library would be a far more destructive button than
 * anything else in this app, and merging costs nothing because every row is keyed by a stable
 * id.
 */
@Singleton
class LibraryBackup @Inject constructor(
    private val shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playEventDao: PlayEventDao
) {

    /** What a completed backup contained, for the message shown afterwards. */
    data class Written(val shelves: Int, val albums: Int, val tracks: Int, val covers: Int)

    /** What a restore put back. [skippedCovers] is artwork the zip did not carry. */
    data class Restored(
        val shelves: Int,
        val albums: Int,
        val tracks: Int,
        val covers: Int,
        val plays: Int
    )

    suspend fun writeTo(context: Context, destination: Uri): Written =
        withContext(Dispatchers.IO) {
            val shelves = shelfDao.listAll()
            val albums = albumDao.listAll()
            val tracks = trackDao.listAll()
            val plays = playEventDao.listAll()

            val root = JSONObject().apply {
                put("format", FORMAT_VERSION)
                put("app", "jawon")
                put("exportedAt", System.currentTimeMillis())
                put("shelves", JSONArray().apply { shelves.forEach { put(it.toJson()) } })
                put("albums", JSONArray().apply { albums.forEach { put(it.toJson()) } })
                put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
                put("playEvents", JSONArray().apply { plays.forEach { put(it.toJson()) } })
            }

            var covers = 0
            context.contentResolver.openOutputStream(destination)?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(root.toString(2).toByteArray())
                    zip.closeEntry()

                    // Both images an album can carry, de-duplicated: the same file is often
                    // both the case cover and the disc face.
                    val paths = albums
                        .flatMap { listOfNotNull(it.caseCoverUri, it.discFaceUri) }
                        .distinct()
                    for (path in paths) {
                        val file = File(path)
                        if (!file.isFile) continue
                        zip.putNextEntry(ZipEntry("$COVERS/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        covers++
                    }
                }
            } ?: error("Could not open the file to write to.")

            Written(shelves.size, albums.size, tracks.size, covers)
        }

    suspend fun readFrom(context: Context, source: Uri): Restored =
        withContext(Dispatchers.IO) {
            val coversDir = File(context.filesDir, COVERS).apply { mkdirs() }
            var manifest: JSONObject? = null
            var covers = 0

            context.contentResolver.openInputStream(source)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == MANIFEST ->
                                manifest = JSONObject(zip.readBytes().decodeToString())

                            name.startsWith("$COVERS/") && !entry.isDirectory -> {
                                // Basename only. A zip entry can name any path it likes,
                                // including one climbing out of this directory, and an archive
                                // is untrusted input however friendly its file picker looked.
                                val safe = File(name).name
                                if (safe.isNotEmpty()) {
                                    File(coversDir, safe).outputStream().use { zip.copyTo(it) }
                                    covers++
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Could not open that file.")

            val root = manifest ?: error("That is not a Jawon backup.")
            if (root.optString("app") != "jawon") error("That is not a Jawon backup.")
            if (root.optInt("format", 0) > FORMAT_VERSION) {
                error("That backup was written by a newer version of Jawon.")
            }

            val shelves = root.array("shelves").map { it.toShelf() }
            // Artwork paths are rewritten to this install's own covers directory: the absolute
            // path in the backup belongs to whichever install wrote it, and after a reinstall
            // that is not where the file now is.
            val albums = root.array("albums").map { it.toAlbum(coversDir) }
            val tracks = root.array("tracks").map { it.toTrack() }
            val plays = root.array("playEvents").map { it.toPlayEvent() }

            // Shelves first: an album's shelfId has to have somewhere to point.
            shelfDao.insertAll(shelves)
            albumDao.insertAll(albums)
            trackDao.insertAll(tracks)
            // Ids are re-issued rather than restored, so restoring the same file twice adds
            // the history twice — see toPlayEvent.
            playEventDao.insertAll(plays)

            Restored(shelves.size, albums.size, tracks.size, covers, plays.size)
        }

    // ---- row <-> json ----------------------------------------------------------------

    private fun Shelf.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("order", order)
        put("accentHue", accentHue.toDouble()); put("createdAt", createdAt)
    }

    private fun JSONObject.toShelf() = Shelf(
        id = getString("id"),
        name = optString("name"),
        order = optInt("order"),
        accentHue = optDouble("accentHue", 0.0).toFloat(),
        createdAt = optLong("createdAt")
    )

    private fun Album.toJson() = JSONObject().apply {
        put("id", id); put("shelfId", shelfId); put("title", title); put("artist", artist)
        put("year", year ?: JSONObject.NULL)
        // Stored as the file's name, not its path. The directory is this install's private
        // one and will be somewhere else after a reinstall.
        put("caseCover", caseCoverUri?.let { File(it).name } ?: JSONObject.NULL)
        put("discFace", discFaceUri?.let { File(it).name } ?: JSONObject.NULL)
        put("spineHue", spineHue.toDouble()); put("slotIndex", slotIndex)
        put("playCount", playCount); put("addedAt", addedAt)
        put("lastTrackIndex", lastTrackIndex); put("lastPositionMs", lastPositionMs)
        put("lastPlayedAt", lastPlayedAt ?: JSONObject.NULL)
    }

    private fun JSONObject.toAlbum(coversDir: File) = Album(
        id = getString("id"),
        shelfId = optString("shelfId"),
        title = optString("title"),
        artist = optString("artist"),
        year = if (isNull("year")) null else optInt("year"),
        caseCoverUri = optName("caseCover")?.let { File(coversDir, it).absolutePath },
        discFaceUri = optName("discFace")?.let { File(coversDir, it).absolutePath },
        spineHue = optDouble("spineHue", 0.0).toFloat(),
        slotIndex = optInt("slotIndex"),
        playCount = optInt("playCount"),
        addedAt = optLong("addedAt"),
        lastTrackIndex = optInt("lastTrackIndex"),
        lastPositionMs = optLong("lastPositionMs"),
        lastPlayedAt = if (isNull("lastPlayedAt")) null else optLong("lastPlayedAt")
    )

    private fun Track.toJson() = JSONObject().apply {
        put("id", id); put("albumId", albumId); put("trackNumber", trackNumber)
        put("title", title); put("durationMs", durationMs); put("fileUri", fileUri)
        put("mimeType", mimeType); put("sampleRateHz", sampleRateHz)
        put("bitDepth", bitDepth ?: JSONObject.NULL); put("bitrateBps", bitrateBps)
    }

    private fun JSONObject.toTrack() = Track(
        id = getString("id"),
        albumId = optString("albumId"),
        trackNumber = optInt("trackNumber"),
        title = optString("title"),
        durationMs = optLong("durationMs"),
        fileUri = optString("fileUri"),
        mimeType = optString("mimeType"),
        sampleRateHz = optInt("sampleRateHz"),
        bitDepth = if (isNull("bitDepth")) null else optInt("bitDepth"),
        bitrateBps = optInt("bitrateBps")
    )

    private fun PlayEvent.toJson() = JSONObject().apply {
        put("albumId", albumId); put("trackId", trackId)
        put("startedAt", startedAt); put("msPlayed", msPlayed)
    }

    /**
     * The id is deliberately not carried across. `PlayEvent.id` is auto-generated, so writing
     * one back would collide with a row this install issued for something else entirely and
     * silently overwrite a real listen. Leaving it at zero lets Room issue a fresh one.
     */
    private fun JSONObject.toPlayEvent() = PlayEvent(
        albumId = optString("albumId"),
        trackId = optString("trackId"),
        startedAt = optLong("startedAt"),
        msPlayed = optLong("msPlayed")
    )

    private fun JSONObject.array(key: String): List<JSONObject> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** A file name from the manifest, reduced to its basename and never empty. */
    private fun JSONObject.optName(key: String): String? {
        if (isNull(key)) return null
        return File(optString(key)).name.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST = "library.json"
        const val COVERS = "covers"
    }
}
