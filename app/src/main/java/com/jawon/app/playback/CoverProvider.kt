package com.jawon.app.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.jawon.app.data.local.AlbumDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands a record's cover to whoever is showing what is playing — a car's screen, the system's
 * media controls — as `content://com.jawon.app.covers/<album id>`.
 *
 * Covers are files in the app's private storage, which is right, and which nothing outside the
 * app can open. A browse list cannot carry the pictures themselves either: fifty covers in one
 * binder transaction is a crash. So the lists carry these addresses, and the picture is opened
 * here, one at a time, by whoever actually draws it.
 *
 * **Exported, and deliberately able to do one thing only.** Read-only; an address is an album's id
 * and nothing else — it is looked up in the database, never joined onto a path — and what comes
 * back is that album's cover or nothing. There is nothing to list, so knowing one id reveals no
 * other. A cover is the least private thing in the library; the shelves' titles are not handed out
 * here at all (see `SessionGate` for who may read those).
 */
class CoverProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun albumDao(): AlbumDao
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Covers are read-only")
        val context = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        if (segments.size != 1) throw FileNotFoundException("No such cover")
        val albums = EntryPointAccessors
            .fromApplication(context.applicationContext, Access::class.java)
            .albumDao()
        // A binder thread, never the main one: blocking here is how a provider is meant to answer.
        val path = runBlocking { albums.findById(segments[0]) }?.caseCoverUri
            ?: throw FileNotFoundException("No such cover")
        val file = File(path)
        // Belt and braces: the path came from the app's own database, and it still has to be a
        // file the app itself stored.
        val root = File(context.filesDir, "covers").canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(root) || !file.isFile) throw FileNotFoundException("No such cover")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
