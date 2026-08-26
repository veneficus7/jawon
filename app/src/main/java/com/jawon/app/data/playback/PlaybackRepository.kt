package com.jawon.app.data.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.jawon.app.data.local.Track
import com.jawon.app.playback.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The app-facing playback "repository" (see ENGINEERING.md's threading/architecture
 * rule). Owns a MediaController bound to PlaybackService's session — nothing
 * else in the app touches ExoPlayer or the service directly.
 */
@Singleton
class PlaybackRepository @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private var controller: MediaController? = null
    private var positionJob: Job? = null

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    // Kept separate from `playback` so position updates (every 500ms while
    // playing) don't force a wider recomposition scope than the progress bar.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlaybackState(player)
        }
    }

    private suspend fun ensureController(): MediaController = withContext(Dispatchers.Main.immediate) {
        controller?.let { return@withContext it }
        connectMutex.withLock {
            controller?.let { return@withLock it }
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val connected = suspendCancellableCoroutine { cont ->
                val future = MediaController.Builder(context, token).buildAsync()
                future.addListener(
                    {
                        try {
                            cont.resume(future.get())
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    },
                    MoreExecutors.directExecutor()
                )
            }
            connected.addListener(playerListener)
            controller = connected
            connected
        }
    }

    private fun updatePlaybackState(player: Player) {
        _playback.value = PlaybackState(
            isPlaying = player.isPlaying,
            currentIndex = player.currentMediaItemIndex,
            itemCount = player.mediaItemCount,
            durationMs = player.duration.coerceAtLeast(0L),
            queuedTrackIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        )
        if (player.isPlaying) startPositionPolling(player) else stopPositionPolling()
    }

    private fun startPositionPolling(player: Player) {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (true) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                delay(500)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }

    suspend fun playAlbum(tracks: List<Track>, startIndex: Int = 0) {
        val controller = ensureController()
        controller.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    suspend fun playPause() {
        val controller = ensureController()
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    suspend fun seekToNext() = ensureController().seekToNextMediaItem()

    suspend fun seekToPrevious() = ensureController().seekToPreviousMediaItem()

    suspend fun seekTo(positionMs: Long) = ensureController().seekTo(positionMs)

    suspend fun seekToTrack(index: Int) = ensureController().seekTo(index, 0L)

    /**
     * Seek to a position within a specific queue item — what the disc scratch needs, since
     * turning the platter far enough runs off the end of one track and into the next.
     *
     * Deliberately does not pause: seeking while playing is what makes a scrub audible.
     */
    suspend fun seekTo(index: Int, positionMs: Long) = ensureController().seekTo(index, positionMs)
}

private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(fileUri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()
