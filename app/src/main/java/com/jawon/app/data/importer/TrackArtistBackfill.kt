package com.jawon.app.data.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Reads the per-track artist out of files that were imported before there was a column to
 * keep it in.
 *
 * **Why this exists at all.** Adding `Track.artist` only helps albums imported *after* the
 * update — every row already in the library migrates in with a null, so a user who imported
 * their collection last month would install the update, look at a compilation, and see the
 * album's artist exactly as before. The feature would be indistinguishable from not shipping
 * it. The tags are still sitting in the files; this goes and reads them.
 *
 * Runs once, in the background, and is deliberately unhurried: it holds no lock the rest of
 * the app wants, writes one column at a time, and a library it does not finish is simply
 * picked up on the next launch, because the flag is only set once every track has been seen.
 */
@Singleton
class TrackArtistBackfill @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val preferences: AppPreferences
) {

    suspend fun runOnce() = withContext(Dispatchers.IO) {
        if (preferences.trackArtistBackfillDone) return@withContext

        val pending = trackDao.withoutArtist()
        if (pending.isEmpty()) {
            preferences.trackArtistBackfillDone = true
            return@withContext
        }

        for (track in pending) {
            // The app can be killed mid-pass; the flag below is what makes that safe.
            coroutineContext.ensureActive()
            val artist = readArtist(track.fileUri) ?: continue
            trackDao.setArtist(track.id, artist)
        }

        // Set even though some tracks may still be null, and that is the point: a file that
        // genuinely carries no artist tag, or one whose URI is no longer readable, will be
        // null on every future pass too. Retrying those on every launch would re-open every
        // such file forever to learn the same thing. A re-import is the way to revisit them.
        preferences.trackArtistBackfillDone = true
    }

    /**
     * Null rather than an exception for anything that goes wrong, because everything that can
     * go wrong here is ordinary: the user revoked a folder permission, moved a file, plugged
     * the SD card into something else. None of that is worth failing a background pass over,
     * and none of it should be allowed to take the app down at launch.
     */
    private fun readArtist(fileUri: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri.toUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
