package com.jawon.app.data.importer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a picked cover image's bytes into app-private storage. */
class CoverImageStore {

    suspend fun store(context: Context, sourceUri: Uri, fileName: String): String =
        withContext(Dispatchers.IO) {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val destFile = File(coversDir, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open $sourceUri")
            destFile.absolutePath
        }
}
