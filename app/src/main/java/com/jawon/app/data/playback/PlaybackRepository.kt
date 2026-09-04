package com.jawon.app.data.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import com.jawon.app.playback.SleepTimer
import com.jawon.app.playback.Varispeed
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import android.content.pm.ApplicationInfo
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.PlayEvent
import com.jawon.app.data.local.PlayEventDao
import com.jawon.app.data.local.Track
import com.jawon.app.playback.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
class PlaybackRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val playEventDao: PlayEventDao
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Debug builds only — see the history log below. Read once, not per event. */
    private val isDebuggable =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val connectMutex = Mutex()
    private var controller: MediaController? = null
    private var positionJob: Job? = null

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    // Kept separate from `playback` so position updates (every 500ms while
    // playing) don't force a wider recomposition scope than the progress bar.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /**
     * The album currently loaded into the queue, and the tracks it was built from.
     *
     * Held here rather than passed in per call because the things that end a stretch of
     * listening — a track finishing, the user closing the case, the service being stopped
     * from the notification — do not all come through a call that knows the album.
     */
    private var queuedTracks: List<Track> = emptyList()

    /** The stretch of listening in progress: which track, when it began, where it began. */
    private var segment: Segment? = null

    private data class Segment(
        val trackIndex: Int,
        val startedAt: Long,
        val fromPositionMs: Long
    )

    // ---- Sleep timer ---------------------------------------------------------------

    private val _sleepMode = MutableStateFlow<SleepTimer.Mode>(SleepTimer.Mode.Off)
    val sleepMode: StateFlow<SleepTimer.Mode> = _sleepMode.asStateFlow()

    /** When a countdown fires, in `System.currentTimeMillis`. Null for the other modes. */
    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt.asStateFlow()

    fun setSleepMode(mode: SleepTimer.Mode) {
        _sleepMode.value = mode
        _sleepEndsAt.value = when (mode) {
            is SleepTimer.Mode.After -> SleepTimer.endsAt(mode.minutes, System.currentTimeMillis())
            else -> null
        }
    }

    /**
     * Stop, and clear the timer.
     *
     * Pausing rather than releasing: someone who wakes up and reaches for the phone should
     * find the record where it stopped, which is the same promise the resume feature makes.
     */
    private suspend fun sleepNow() {
        _sleepMode.value = SleepTimer.Mode.Off
        _sleepEndsAt.value = null
        setPlaying(false)
    }

    /** Whether the player was already sitting at the end last time [playerListener] looked. */
    private var wasEnded = false

    private val _albumEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires once each time a record plays all the way through.
     *
     * **A `SharedFlow`, because this is an event and not a state.** The room closes the case
     * when it arrives, and a state would either re-close on every recomposition or need a
     * consumed-flag threaded through the scene to stop it.
     *
     * Deliberately says nothing about what should happen next. What the room does with the end
     * of a record is the room's decision, and putting "and then play something else" here would
     * be the first step toward a queue — which PLAN §2 rules out on purpose.
     */
    val albumEnded: SharedFlow<Unit> = _albumEnded.asSharedFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Before the state is replaced, so the *previous* index is still readable.
            noteTransition(player)
            updatePlaybackState(player)

            // End of the record. STATE_ENDED is the queue running out, and a queue here is
            // exactly one album — see SleepTimer.
            //
            // **Edge-triggered, not level.** `onEvents` fires repeatedly while the player sits
            // in STATE_ENDED, so anything acting on this needs to hear about it once. The flag
            // clears the moment the state moves off ENDED, which covers a replay of the same
            // record as well as a different one.
            val ended = player.playbackState == Player.STATE_ENDED
            if (ended && !wasEnded) {
                if (_sleepMode.value == SleepTimer.Mode.EndOfAlbum) scope.launch { sleepNow() }
                _albumEnded.tryEmit(Unit)
            }
            wasEnded = ended
        }
    }

    /**
     * Closes off a stretch of listening when the track changes or playback stops, and opens
     * the next one.
     *
     * Driven from `onEvents` rather than from `onMediaItemTransition` alone because a stretch
     * also ends when the user pauses and walks away — there is no transition then, and waiting
     * for one would drop the last track of every session.
     */
    private fun noteTransition(player: Player) {
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val open = segment

        val changedTrack = open != null && open.trackIndex != index
        val stopped = open != null && !player.isPlaying

        if (open != null && (changedTrack || stopped)) {
            // On a track change the previous item ran to its end; otherwise it reached
            // wherever the player is now. Either way this is time *in the track*, so a pause
            // does not inflate it and scrubbing backwards does not either.
            val endedAt = if (changedTrack) {
                player.duration.takeIf { it > 0 } ?: position
            } else {
                position
            }
            recordSegment(open, endedAt)
            segment = null
        }

        if (player.isPlaying && segment == null) {
            segment = Segment(index, System.currentTimeMillis(), position)
        }

        // Remember the place, on every event rather than only at the end. The process can be
        // killed without notice — a swipe from recents, a low-memory kill — and a resume
        // point only written on a clean exit is a resume point that is usually missing.
        rememberPosition(index, position)
    }

    private fun recordSegment(seg: Segment, endedAtMs: Long) {
        val track = queuedTracks.getOrNull(seg.trackIndex) ?: return
        val played = (endedAtMs - seg.fromPositionMs).coerceAtLeast(0L)
        // Under two seconds is skipping past something, not listening to it.
        if (played < MIN_COUNTED_MS) return
        scope.launch {
            playEventDao.insert(
                PlayEvent(
                    albumId = track.albumId,
                    trackId = track.id,
                    startedAt = seg.startedAt,
                    msPlayed = played
                )
            )
            // Debug builds only, and written to filesDir rather than logcat — on the test
            // device Samsung's telephony spam buries app logs (ENGINEERING.md trap 9). This is the
            // only way to see that history is accumulating before any screen reads it, and
            // history is the one thing here that cannot be reconstructed if it silently
            // fails. Read it back with:
            //   adb shell run-as com.jawon.app cat files/history.log
            if (isDebuggable) {
                runCatching {
                    java.io.File(context.filesDir, "history.log").appendText(
                        "${seg.startedAt} ${track.albumId} ${track.title} ${played}ms\n"
                    )
                }
            }
            val album = albumDao.findById(track.albumId) ?: return@launch
            albumDao.insert(
                album.copy(
                    playCount = album.playCount + 1,
                    lastPlayedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun rememberPosition(index: Int, positionMs: Long) {
        val track = queuedTracks.getOrNull(index) ?: return
        scope.launch {
            val album = albumDao.findById(track.albumId) ?: return@launch
            if (album.lastTrackIndex == index && album.lastPositionMs == positionMs) return@launch
            albumDao.insert(album.copy(lastTrackIndex = index, lastPositionMs = positionMs))
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
            var tick = 0
            while (true) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                // Save the place periodically, not only on player events. Nothing fires an
                // event while a track simply plays, so a resume point written only on events
                // would still say "start of track" when the process is killed ten minutes in
                // — and being killed without notice is the normal way an Android app dies.
                // Every ten ticks bounds the worst case to five seconds of lost position for
                // one small write.
                if (++tick % POSITION_SAVE_EVERY_TICKS == 0) {
                    rememberPosition(player.currentMediaItemIndex, _positionMs.value)
                }

                // The countdown, checked on the poll that is already running rather than on
                // a timer of its own. Half a second of overshoot is imperceptible on a
                // fifteen-minute sleep timer and it costs no second coroutine.
                if (SleepTimer.hasExpired(_sleepEndsAt.value, System.currentTimeMillis())) {
                    sleepNow()
                }
                delay(500)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * Load an album and start it, optionally partway in.
     *
     * [startPositionMs] is what makes a record pick up where it was left — see
     * `Album.lastTrackIndex`. It defaults to the beginning so callers that genuinely mean
     * "from the top" do not have to say so.
     */
    suspend fun playAlbum(
        tracks: List<Track>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        albumArtist: String? = null
    ) {
        val controller = ensureController()
        // Recorded before the queue is replaced, so the last stretch of the album being
        // swapped out is not lost — and so `queuedTracks` still describes it while we do.
        segment?.let { recordSegment(it, _positionMs.value) }
        segment = null
        queuedTracks = tracks
        controller.setMediaItems(
            tracks.map { it.toMediaItem(albumArtist) },
            startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
            startPositionMs.coerceAtLeast(0L)
        )
        controller.prepare()
        controller.play()
    }

    suspend fun playPause() {
        val controller = ensureController()
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    /**
     * Tempo and key, set independently.
     *
     * The player's own, not an [androidx.media3.common.audio.AudioProcessor]: Media3 time-
     * stretches with Sonic, which is a better time-stretcher than anything worth hand-writing
     * here, and it is the one part of the rack the platform already does properly.
     *
     * Both values pass through `Varispeed.safe` on the way out. Media3 refuses a non-positive
     * rate by *throwing*, and this call crosses into the service that is playing the music —
     * an exception here is not a wrong sound, it is the music stopping.
     */
    suspend fun setSpeedAndPitch(speed: Float, pitch: Float) {
        val controller = ensureController()
        runCatching {
            controller.playbackParameters =
                PlaybackParameters(Varispeed.safe(speed), Varispeed.safe(pitch))
        }
    }

    /**
     * Set playback on or off explicitly, rather than toggling.
     *
     * The platter needs this. Holding the disc pauses for as long as the finger is down, and
     * expressing that as two [playPause] calls races: the gesture can outlive a transport tap,
     * a track can end mid-hold, and either one flips the toggle out from under the release so
     * the disc is let go and the music stays silent. Asking for a state instead of a change is
     * idempotent, so a stray extra call costs nothing.
     */
    suspend fun setPlaying(playing: Boolean) {
        val controller = ensureController()
        if (playing) controller.play() else controller.pause()
    }

    suspend fun seekToNext() = ensureController().seekToNextMediaItem()

    suspend fun seekToPrevious() = ensureController().seekToPreviousMediaItem()

    suspend fun seekTo(positionMs: Long) = ensureController().seekTo(positionMs)

    /**
     * Choose a track from the list.
     *
     * **The `play()` is the whole point, and its absence was a real bug.** Seeking alone leaves
     * `playWhenReady` exactly as it was, so tapping a track while paused moved the playhead in
     * silence and looked, from the outside, like the tracklist had stopped working. That is
     * precisely what a record ending leaves you in: playback stops, and every tap after it
     * seeks and does nothing audible.
     *
     * Choosing a track is a request to *hear* it. No player in the world makes you tap the
     * track and then press play.
     */
    suspend fun seekToTrack(index: Int) {
        val controller = ensureController()
        controller.seekTo(index, 0L)
        controller.play()
    }

    /**
     * Seek to a position within a specific queue item — what the disc scratch needs, since
     * turning the platter far enough runs off the end of one track and into the next.
     *
     * Deliberately does not pause: seeking while playing is what makes a scrub audible.
     */
    suspend fun seekTo(index: Int, positionMs: Long) = ensureController().seekTo(index, positionMs)

    private companion object {
        /**
         * Below this, a track was skipped past rather than listened to.
         *
         * Two seconds is enough to sit through the gap between tracks while deciding, and
         * short enough that genuinely sampling something still counts. It exists so that
         * running a thumb down the tracklist does not write twenty rows claiming an album was
         * played twenty times.
         */
        const val MIN_COUNTED_MS = 2_000L

        /** Ten × the 500ms poll: the resume point is never more than five seconds stale. */
        const val POSITION_SAVE_EVERY_TICKS = 10
    }
}

/**
 * [albumArtist] is the fallback, not the answer: a track carries its own credit on a
 * compilation, and the notification should name whoever is actually singing. Files that
 * carry no per-track tag — and every track imported before that column existed — fall back
 * to the record's artist, which is the best thing known about them.
 */
private fun Track.toMediaItem(albumArtist: String?): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(fileUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist?.takeIf { it.isNotBlank() } ?: albumArtist)
                .build()
        )
        .build()
