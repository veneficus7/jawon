package com.jawon.app.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One list of what counts as music, shared with what another app hands over (`OpenedPlan`). */
private val AUDIO_EXTENSIONS = OpenedPlan.AUDIO_EXTENSIONS

/** Recursively walks a picked SAF tree for files that look like audio. */
class AudioScanner {

    suspend fun scan(context: Context, treeUri: Uri): List<DocumentFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val found = mutableListOf<DocumentFile>()
        walk(root, found)
        found
    }

    private fun walk(dir: DocumentFile, out: MutableList<DocumentFile>) {
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> walk(child, out)
                isAudio(child) -> out.add(child)
            }
        }
    }

    private fun isAudio(file: DocumentFile): Boolean {
        val mime = file.type
        if (mime != null && mime.startsWith("audio/")) return true
        val name = file.name ?: return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }
}
