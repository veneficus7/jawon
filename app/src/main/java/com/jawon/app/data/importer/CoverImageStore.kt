package com.jawon.app.data.importer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Copies a picked cover image's bytes into app-private storage, and removes them again when
 * an album is deleted.
 *
 * Stateless, so `@Inject` costs nothing and lets the wall's view model reach it for deletion
 * without hand-constructing one the way the import repository does.
 */
class CoverImageStore @Inject constructor() {

    suspend fun store(context: Context, sourceUri: Uri, fileName: String): String =
        withContext(Dispatchers.IO) {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val destFile = File(coversDir, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open $sourceUri")
            destFile.absolutePath
        }

    /**
     * Removes cover images belonging to a deleted album.
     *
     * **Only ever files this class wrote**, which is why it checks the path is inside the
     * covers directory before unlinking anything. These paths come from a database column,
     * and a delete path that will unlink whatever absolute path it is handed is one bad row
     * away from deleting something else entirely.
     *
     * The user's *audio* is never in here. That stays in their own folders, reached through
     * a read-only document grant — this is only the copies Jawon made at import.
     */
    suspend fun deleteAll(vararg paths: String?) = withContext(Dispatchers.IO) {
        paths.filterNotNull().forEach { path ->
            val file = File(path)
            if (file.parentFile?.name == "covers" && file.isFile) file.delete()
        }
    }
}
