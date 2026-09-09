package com.jawon.app.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import com.jawon.app.data.local.Track
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.data.remote.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** How much of an album is on the phone. */
data class OfflineState(val cachedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0L) 0f else (cachedBytes.toFloat() / totalBytes)
    /** A little slack: a track can report a length a few bytes off what it actually stores. */
    val isComplete: Boolean get() = totalBytes > 0L && cachedBytes >= totalBytes - SLACK_BYTES

    private companion object {
        const val SLACK_BYTES = 64L * 1024L
    }
}

/**
 * Keeping a streamed album on the phone, deliberately, rather than by accident.
 *
 * ## Why this is not just "the cache, but bigger"
 *
 * [StreamCache] already keeps what has been played, and that is what makes the scratch work.
 * But it is a *cache*: it fills with whatever was most recent and evicts on that basis, so the
 * album you specifically want on a flight is exactly as likely to be thrown away as the one you
 * skipped through once. Wanting a record to be there is a different statement from having
 * happened to play it, and until now there was no way to say it.
 *
 * This writes the same cache, through the same keys, so a downloaded album needs no separate
 * playback path — it simply plays, and every seek is instant because every byte is already local.
 *
 * ## The honest limitation
 *
 * **A downloaded album can still be evicted.** It shares one `SimpleCache` with the streaming
 * cache and a least-recently-used evictor, which has no concept of "the user asked for this
 * one". Keeping downloads safe would mean a second cache with no evictor, and then playback
 * would have to read from two caches and decide between them — a real change to the data source
 * chain for a feature nobody has yet been able to use.
 *
 * So instead: [download] refuses to start unless the album fits in the space the cache is
 * *allowed* to grow into, and says how much room is needed when it does not. That turns the
 * failure from "it silently disappeared later" into "you need a bigger offline store, here is
 * the number" — which is a thing a person can act on. If downloads become common the second
 * cache is the right answer and this comment is where to start.
 */
@UnstableApi
@Singleton
class AlbumDownloader @Inject constructor(
    private val streamCache: StreamCache,
    private val subsonic: SubsonicClient,
    private val preferences: AppPreferences
) {

    /**
     * What a download attempt did, in words the UI can show.
     *
     * There is no `Cancelled` here on purpose: cancelling means cancelling the coroutine, and a
     * cancelled coroutine does not return a value — it unwinds. Inventing a result for it would
     * mean swallowing the `CancellationException`, which breaks structured concurrency for the
     * sake of an enum case nobody reads.
     */
    sealed interface Result {
        data object Done : Result
        data class TooLarge(val neededMb: Int, val limitMb: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * How much of [tracks] is already on the phone.
     *
     * Reads the cache's own record of each key rather than any state of ours, so it is true after
     * an eviction, a clear, or a download that was interrupted halfway — none of which we would
     * otherwise hear about.
     */
    fun stateOf(tracks: List<Track>): OfflineState {
        var cached = 0L
        var total = 0L
        for (track in tracks) {
            val metadata = streamCache.cache.getContentMetadata(track.id)
            val length = ContentMetadata.getContentLength(metadata)
            if (length != C.LENGTH_UNSET.toLong()) total += length
            cached += streamCache.cache.getCachedBytes(track.id, 0, C.LENGTH_UNSET.toLong())
        }
        // Before anything has been fetched the length is unknown, so fall back to what the
        // tracklist claims — an estimate is more useful here than a zero that reads as "nothing
        // to download".
        if (total == 0L) total = tracks.sumOf { estimatedBytes(it) }
        return OfflineState(cachedBytes = cached, totalBytes = total)
    }

    /**
     * Pulls a whole album into the cache, reporting progress as it goes.
     *
     * Cancellable between tracks and during one — see the note on [CacheWriter.cancel] below,
     * which is what stops a twenty-minute track rather than waiting it out. What is already
     * written stays: a part-downloaded album is a part-downloaded album, not a wasted one, and
     * resuming picks up where it stopped because the cache knows which spans it holds.
     */
    suspend fun download(
        tracks: List<Track>,
        onProgress: (OfflineState) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val server = preferences.musicServer.value
        if (!server.isConfigured) return@withContext Result.Failed("No server set up.")

        val state = stateOf(tracks)
        val limitBytes = preferences.streamCacheMb.value.toLong() * BYTES_PER_MB
        val needed = state.totalBytes - state.cachedBytes
        if (needed > limitBytes) {
            return@withContext Result.TooLarge(
                neededMb = ((state.totalBytes / BYTES_PER_MB) + 1).toInt(),
                limitMb = preferences.streamCacheMb.value
            )
        }

        val factory = streamCache.cacheDataSourceFactory()
        val buffer = ByteArray(BUFFER_BYTES)

        for (track in tracks) {
            coroutineContext.ensureActive()
            val remoteId = track.remoteId ?: continue

            val spec = DataSpec.Builder()
                .setUri(subsonic.streamUrl(server, remoteId))
                // **The same key playback uses, or this downloads into nothing.** The stream URL
                // carries a fresh salt every time, so a download keyed on the URL would be
                // written under a name the player never looks for — the bytes would be on the
                // phone and every play would fetch them again.
                .setKey(track.id)
                .build()

            val writer = CacheWriter(
                factory.createDataSource(),
                spec,
                buffer
            ) { _, _, _ -> onProgress(stateOf(tracks)) }

            // **What makes cancelling mid-track actually stop.** `CacheWriter.cache()` is a
            // blocking loop that answers to its own `cancel()` and to nothing else — not to
            // thread interruption, and certainly not to the coroutine being cancelled around it.
            // Without this, pressing cancel on a twenty-minute track would return control to the
            // UI immediately and leave the download running to the end of the file.
            val stopOnCancel = coroutineContext.job.invokeOnCompletion { writer.cancel() }
            try {
                writer.cache()
            } catch (e: Exception) {
                // Cancellation arrives here as an `InterruptedIOException`, because cancelling is
                // implemented by making the write fail. Ask the coroutine whether it is still
                // alive before calling that a failure — someone who pressed cancel did not hit an
                // error, and `ensureActive` rethrows properly rather than inventing a result.
                coroutineContext.ensureActive()
                return@withContext Result.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                stopOnCancel.dispose()
            }
        }
        onProgress(stateOf(tracks))
        Result.Done
    }

    /** Gives the space back. The album stays on the wall and goes back to streaming. */
    fun remove(tracks: List<Track>) {
        for (track in tracks) {
            runCatching { streamCache.cache.removeResource(track.id) }
        }
    }

    /**
     * A guess at a track's size, for before anything has been fetched.
     *
     * Bitrate times duration, which is exact for a constant-bitrate file and close enough for a
     * variable one. Falls back to a middling lossless rate when the server reported no bitrate —
     * over-estimating is the safe direction, since the number is used to decide whether an album
     * will fit.
     */
    private fun estimatedBytes(track: Track): Long {
        val bitrate = if (track.bitrateBps > 0) track.bitrateBps.toLong() else FALLBACK_BPS
        return bitrate * track.durationMs / 8_000L
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val BUFFER_BYTES = 128 * 1024
        const val FALLBACK_BPS = 900_000L
    }
}
