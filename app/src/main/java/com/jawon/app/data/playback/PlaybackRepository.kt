package com.jawon.app.data.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
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
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.data.remote.SubsonicClient
import com.jawon.app.data.remote.SubsonicServer
import com.jawon.app.data.spotify.SpotifyFailure
import com.jawon.app.data.spotify.SpotifyPlayer
import com.jawon.app.data.spotify.SpotifyResult
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
import kotlin.math.abs

/**
 * The app-facing playback "repository" (see ENGINEERING.md's threading/architecture
 * rule). Owns a MediaController bound to PlaybackService's session — nothing
 * else in the app touches ExoPlayer or the service directly.
 */
@Singleton
class PlaybackRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val playEventDao: PlayEventDao,
    /**
     * Only ever asked to *build a URL*, never to fetch anything. Resolving a streamed track to
     * an address is a string operation — see [SubsonicClient.streamUrl] — so this stays off the
     * network and the queue is assembled as fast for a remote album as for a local one.
     */
    private val subsonic: SubsonicClient,
    private val preferences: AppPreferences,
    /**
     * The other kind of playback.
     *
     * A Spotify album is not played by this app at all — Spotify's own process decodes it and
     * this sends commands. So the repository has two modes rather than one player with two
     * sources, and [spotifyAlbumUri] is which mode it is in. Keeping both behind this one class
     * is what preserves the second architecture rule: everything else in the app still reads
     * playback from exactly one place and does not know which kind it is watching.
     */
    private val spotifyPlayer: SpotifyPlayer
) {

    /**
     * Non-null while a Spotify album is loaded, and the flag that routes every transport
     * control. Cleared whenever a local or streamed album takes over.
     */
    private var activeSpotifyAlbum: String? = null

    /** Polls Spotify for what it is doing, only while a Spotify album is open. */
    private var spotifyJob: Job? = null

    // ---- the Spotify clock ------------------------------------------------------------
    //
    // See [startSpotifyPolling]. Between them these four say where the playhead is without
    // asking the network, which is the difference between a position that steps once a second
    // and one that moves.

    /** Where Spotify was at [spotifyAnchorAt], corrected for the round trip. */
    private var spotifyAnchorMs = 0L

    /** `elapsedRealtime` at which [spotifyAnchorMs] was true. Monotonic, unlike wall time. */
    private var spotifyAnchorAt = 0L

    /** When this app last told Spotify to move. See the grace window in [startSpotifyPolling]. */
    private var spotifyCommandAt = 0L

    /** Consecutive polls that came back "nothing is playing". */
    private var spotifyIdleReports = 0

    /**
     * `elapsedRealtime` before which nothing may be sent to Spotify, because Spotify said so.
     *
     * **Nothing used to read `RateLimited` on this path at all** — the poll tested for
     * `is SpotifyResult.Ok` and dropped every failure on the floor, so a 429 meant the app
     * carried on asking once a second for as long as the record was open, which is the one
     * behaviour guaranteed to keep the ban alive. See [holdOff].
     */
    private var spotifyHoldOffUntil = 0L

    /** The transport command waiting to be sent, and the job that will send it. See [sendSoon]. */
    private var spotifySendJob: Job? = null

    /**
     * What the room wants the user told, once.
     *
     * A Spotify play that fails is otherwise **completely silent**: the case opens, the disc
     * lifts out and spins, and no sound arrives with no explanation anywhere on screen. That is
     * most of what "Spotify listening is not working" is from the outside, whatever the actual
     * fault underneath was, and it is why every failure on this path now ends up here.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

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

        // The decisions live in PlayAccounting, which is pure and tested. This method's job is
        // reading the player and writing the row; what any of it *means* is not decided here.
        if (open != null &&
            PlayAccounting.closes(
                hasOpenSegment = true,
                trackChanged = changedTrack,
                isPlaying = player.isPlaying
            )
        ) {
            recordSegment(
                open,
                PlayAccounting.reachedMs(changedTrack, player.duration, position)
            )
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
        // Under two seconds is skipping past something, not listening to it. See PlayAccounting.
        val played = PlayAccounting.countedMs(seg.fromPositionMs, endedAtMs) ?: return
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
            // device Samsung's telephony spam buries app logs (ENGINEERING.md trap 15). This is the
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
        albumArtist: String? = null,
        /**
         * `spotify:album:…` when this record lives in a Spotify account, and null otherwise.
         *
         * Passed in rather than derived, because a track knows it is remote but not *whose*
         * remote it is — `Track.remoteId` is set for both a Subsonic server and Spotify, and the
         * album is the only row carrying `sourceId`. The caller has the album; this does not.
         */
        spotifyAlbumUri: String? = null
    ) {
        if (spotifyAlbumUri != null) {
            playSpotifyAlbum(tracks, spotifyAlbumUri, startIndex, startPositionMs)
            return
        }
        // Any previous Spotify album stops being the thing the transport controls talk to.
        // Not paused — a local record taking over is not a reason to silence something the
        // user may be listening to on another device.
        activeSpotifyAlbum = null
        spotifyJob?.cancel()
        spotifyJob = null

        val controller = ensureController()
        // Recorded before the queue is replaced, so the last stretch of the album being
        // swapped out is not lost — and so `queuedTracks` still describes it while we do.
        segment?.let { recordSegment(it, _positionMs.value) }
        segment = null
        queuedTracks = tracks
        controller.setMediaItems(
            tracks.map { mediaItemFor(it, albumArtist) },
            startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
            startPositionMs.coerceAtLeast(0L)
        )
        controller.prepare()
        controller.play()
    }

    /**
     * Turns one track into something the player can open, wherever it happens to live.
     *
     * The branch is on [Track.remoteId] rather than on the album's `sourceId`, because this is
     * handed a list of tracks and not the album they came from — and because the track is the
     * thing that knows whether it has a file behind it.
     *
     * A remote track with no server configured falls through to the local path and gets an empty
     * URI, which the player reports as an error rather than failing silently. That is the right
     * outcome for a state that should not be reachable: the only way to reach it is to remove
     * the server while its albums are still on the wall, and a track that cannot say where it
     * is should say so.
     */
    private fun mediaItemFor(track: Track, albumArtist: String?): MediaItem {
        val remoteId = track.remoteId
        val server = preferences.musicServer.value
        return if (remoteId != null && server.isConfigured) {
            track.toStreamedMediaItem(albumArtist, subsonic.streamUrl(server, remoteId))
        } else {
            track.toMediaItem(albumArtist)
        }
    }

    /**
     * Turns a Spotify failure into something worth reading, and honours a 429.
     *
     * Returns true if the call succeeded. The wording is deliberately about what to *do*:
     * "there is nowhere to play" is not a sentence anyone can act on, and the one thing that
     * fixes it is opening Spotify.
     */
    private fun <T> handleSpotify(result: SpotifyResult<T>): Boolean {
        if (result is SpotifyResult.Ok) return true
        val failure = (result as SpotifyResult.Failed).failure
        _notice.value = when (failure) {
            is SpotifyFailure.NeedsLogin ->
                "Spotify needs you to sign in again — Settings, then Spotify."
            is SpotifyFailure.NeedsPremium ->
                "Spotify only lets Premium accounts play from another app."
            is SpotifyFailure.NoDevice ->
                "Open Spotify once so it has somewhere to play, then try again."
            is SpotifyFailure.RateLimited -> {
                holdOff(failure.retryAfterSeconds)
                "Spotify is asking for a pause — back in ${failure.retryAfterSeconds}s."
            }
            is SpotifyFailure.Other -> "Spotify said: ${failure.message}"
        }
        return false
    }

    /** Stops everything talking to Spotify for [seconds], and stops the clock pretending. */
    private fun holdOff(seconds: Int) {
        spotifyHoldOffUntil = SystemClock.elapsedRealtime() + seconds.coerceIn(1, 60) * 1000L
    }

    private fun heldOff(): Boolean = SystemClock.elapsedRealtime() < spotifyHoldOffUntil

    /**
     * Sends ONE transport command after the finger stops moving, replacing any still waiting.
     *
     * **This is the fix for a request storm this app created.** The scratch calls
     * `onScrub` every `Scratch.SEEK_THROTTLE_MS` — 80ms, 12.5 a second — which was free while
     * those seeks went to the local player, and stopped being free the moment they were
     * routed to Spotify: a two-second scratch became twenty-five Web API calls, and each one
     * that crossed a track boundary was a `playAlbum`, which resolves a device first and can
     * be three calls on its own. Spotify counts requests over a rolling thirty seconds, so a
     * few seconds of scratching earns a 429 and then *nothing* Spotify works — not playback,
     * not the poll, not the next album.
     *
     * The clock is anchored the instant the finger moves, so the deck, the disc and the
     * readout are all live throughout; what is debounced is only the one thing that costs a
     * request. Which is also what the design always said this gesture means on a Spotify
     * record: the caller sends where the finger *landed*, not where it passed.
     */
    private fun sendSoon(block: suspend () -> SpotifyResult<*>) {
        spotifySendJob?.cancel()
        spotifySendJob = scope.launch {
            delay(SPOTIFY_SEND_DEBOUNCE_MS)
            if (heldOff()) return@launch
            // Re-stamped here rather than when the command was queued: the grace window
            // exists to keep the poll from overwriting a command Spotify has not caught up
            // with yet, and that window starts when the command is actually sent.
            spotifyCommandAt = SystemClock.elapsedRealtime()
            handleSpotify(block())
        }
    }

    /**
     * Where the Spotify clock says the playhead is, right now.
     *
     * The anchor plus however long it has been running, clamped to the track. This is what
     * every reader of [positionMs] gets while a Spotify record is open — the poll's job is to
     * correct it, not to be it.
     */
    private fun spotifyPositionNow(now: Long = SystemClock.elapsedRealtime()): Long {
        val running = if (_playback.value.isPlaying) now - spotifyAnchorAt else 0L
        val duration = _playback.value.durationMs
        val position = (spotifyAnchorMs + running).coerceAtLeast(0L)
        return if (duration > 0L) position.coerceAtMost(duration) else position
    }

    /**
     * Re-anchors the clock, which is how a local command takes effect at once instead of a
     * second later.
     *
     * [ownCommand] marks it as *ours* and starts the grace window. Everything Spotify says for
     * the next [SPOTIFY_COMMAND_GRACE_MS] is then known to be stale — its answer was sampled
     * before it had processed the seek — and a poll that overwrote the clock with it is
     * exactly why a scrub sprang back to where it started.
     */
    private fun anchorSpotify(positionMs: Long, ownCommand: Boolean) {
        val now = SystemClock.elapsedRealtime()
        spotifyAnchorMs = positionMs.coerceAtLeast(0L)
        spotifyAnchorAt = now
        if (ownCommand) spotifyCommandAt = now
        _positionMs.value = spotifyAnchorMs
    }

    /**
     * Starts a Spotify album, and begins mirroring what Spotify reports into [playback].
     *
     * The local player is paused first. Two things playing at once is the obvious failure here,
     * and it is not hypothetical: the ExoPlayer queue survives a case closing, so opening a
     * Spotify record while a local one is loaded would have both making sound.
     */
    private suspend fun playSpotifyAlbum(
        tracks: List<Track>,
        albumUri: String,
        startIndex: Int,
        startPositionMs: Long
    ) {
        runCatching { ensureController().pause() }

        segment?.let { recordSegment(it, _positionMs.value) }
        segment = null
        queuedTracks = tracks
        activeSpotifyAlbum = albumUri

        anchorSpotify(startPositionMs, ownCommand = true)
        // Any pending scrub belongs to the record that was open a moment ago.
        spotifySendJob?.cancel()
        _notice.value = null
        // **The result is read now.** It used to be discarded, so "there is nowhere to play"
        // and "your account cannot" both looked exactly like a record that opened and then
        // sat there in silence.
        handleSpotify(
            spotifyPlayer.playAlbum(
                albumUri, startIndex.coerceAtLeast(0), startPositionMs.coerceAtLeast(0L)
            )
        )
        startSpotifyPolling()
    }

    /**
     * Runs the Spotify clock, and asks Spotify once a second whether the clock is right.
     *
     * **This is the price of not having the audio.** The local player reports its own position
     * for free; Spotify has to be asked over the network, and a poll per second is a real share
     * of a rate limit computed over a rolling thirty seconds. It therefore runs only while a
     * Spotify album is actually open, and stops the moment one is not.
     *
     * ## The clock, and why there is one
     *
     * This used to publish `state.positionMs` straight into [positionMs] as each poll landed,
     * which meant the playhead moved **once a second, in one-second jumps**, and was already
     * a round trip out of date when it did. Everything downstream inherited that: the deck's
     * progress bar stepped, the lyric highlight sat behind the singer, and — worst — a scrub
     * or a skip was overwritten by the next poll, because Spotify's answer had been sampled
     * before it processed the command. From the outside that is a disc you cannot scratch and
     * a track you cannot skip: "i cant scratch disk and skip forward".
     *
     * So the position is a *clock* now — an anchor plus elapsed monotonic time, ticking at
     * [SPOTIFY_TICK_MS] — and the poll's job is to correct that clock:
     *
     * - **Round trip.** `progress_ms` was true when Spotify sampled it, somewhere in the middle
     *   of the call, not when the answer arrived. Half the measured round trip goes back on.
     * - **Grace.** Nothing Spotify says within [SPOTIFY_COMMAND_GRACE_MS] of one of our own
     *   commands may move the clock. Our value is the correct one until Spotify has caught up.
     * - **Resync or ease.** A track change, a play/pause from elsewhere, or a disagreement over
     *   [SPOTIFY_RESYNC_MS] is a real move and snaps. Anything smaller is drift, and a quarter
     *   of the error per poll takes it out without a visible jump.
     * - **Two idle reports, not one.** A single 204 or a momentary "nothing playing" used to
     *   set `isPlaying = false` for a second, and the room stops the disc dead when that is
     *   false. That flicker is the other half of what the owner saw as the disc lagging.
     *
     * The index is resolved by matching Spotify's track id against the queue rather than
     * trusting a counter, because the user can change tracks from Spotify itself — on another
     * device, or from the notification — and a counter would drift out of step with no way back.
     */
    private fun startSpotifyPolling() {
        spotifyJob?.cancel()
        spotifyIdleReports = 0
        spotifyJob = scope.launch {
            // The clock. Cheap: it reads two longs and writes one StateFlow, and it only runs
            // while a Spotify record is open.
            launch {
                while (true) {
                    if (_playback.value.isPlaying) _positionMs.value = spotifyPositionNow()
                    delay(SPOTIFY_TICK_MS)
                }
            }
            while (true) {
                if (heldOff()) {
                    // Asking during a 429 is what keeps a 429 alive.
                    delay(SpotifyPlayer.POLL_INTERVAL_MS)
                    continue
                }
                val sentAt = SystemClock.elapsedRealtime()
                val result = spotifyPlayer.playback()
                val now = SystemClock.elapsedRealtime()
                if (result is SpotifyResult.Failed) {
                    // A poll is not worth a notice of its own unless it is the rate limiter,
                    // which needs everything to stop; a one-off network blip should leave the
                    // clock running rather than put a message on screen.
                    val failure = result.failure
                    if (failure is SpotifyFailure.RateLimited) handleSpotify(result)
                }
                if (result is SpotifyResult.Ok) {
                    val state = result.value
                    if (state != null) {
                        spotifyIdleReports = 0
                        // Read the clock BEFORE the state is updated: `spotifyPositionNow`
                        // depends on `isPlaying`, and comparing against the new one would be
                        // comparing Spotify's answer with itself.
                        val local = spotifyPositionNow(now)
                        val wasPlaying = _playback.value.isPlaying
                        val index = queuedTracks.indexOfFirst { it.remoteId == state.trackId }
                        val changedTrack = index >= 0 && index != _playback.value.currentIndex
                        _playback.value = _playback.value.copy(
                            isPlaying = state.isPlaying,
                            currentIndex = if (index >= 0) index else _playback.value.currentIndex,
                            durationMs = state.durationMs
                        )

                        val lag = if (state.isPlaying) (now - sentAt) / 2 else 0L
                        val reported = state.positionMs + lag
                        val ours = now - spotifyCommandAt < SPOTIFY_COMMAND_GRACE_MS
                        when {
                            // A record we did not start, a transport we did not press, or a
                            // disagreement too large to be drift. Snap, and take Spotify's word.
                            changedTrack || wasPlaying != state.isPlaying ->
                                anchorSpotify(reported, ownCommand = false)
                            ours -> Unit
                            abs(reported - local) > SPOTIFY_RESYNC_MS ->
                                anchorSpotify(reported, ownCommand = false)
                            // Drift. Shift the whole line by a quarter of the error rather
                            // than jumping to it: converges in a couple of polls, invisibly.
                            else -> spotifyAnchorMs += (reported - local) / 4
                        }
                    } else if (++spotifyIdleReports >= SPOTIFY_IDLE_REPORTS_TO_STOP) {
                        // 204: Spotify is idle. Not an error, and not a reason to stop asking —
                        // the user may press play on another device at any moment. Two in a row
                        // before believing it, because one is usually a device handover.
                        _playback.value = _playback.value.copy(isPlaying = false)
                    }
                }
                delay(SpotifyPlayer.POLL_INTERVAL_MS)
            }
        }
    }

    suspend fun playPause() {
        activeSpotifyAlbum?.let {
            if (heldOff()) return
            val playing = _playback.value.isPlaying
            // Stop or start the clock here, not a second later when Spotify is next asked.
            // Order matters: the position is read while `isPlaying` still says what it was.
            anchorSpotify(spotifyPositionNow(), ownCommand = true)
            _playback.value = _playback.value.copy(isPlaying = !playing)
            if (playing) spotifyPlayer.pause() else spotifyPlayer.resume()
            return
        }
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

    suspend fun seekToNext() {
        activeSpotifyAlbum?.let {
            stepSpotifyTrack(+1)
            sendSoon { spotifyPlayer.next() }
            return
        }
        ensureController().seekToNextMediaItem()
    }

    suspend fun seekToPrevious() {
        activeSpotifyAlbum?.let {
            stepSpotifyTrack(-1)
            sendSoon { spotifyPlayer.previous() }
            return
        }
        ensureController().seekToPreviousMediaItem()
    }

    /**
     * Moves the deck to the next or previous track *before* Spotify confirms it.
     *
     * A skip that shows nothing for a second reads as a button that does not work, which is
     * how the owner read it. The poll corrects this within a second if Spotify did something
     * else — ran off the end of the record, or was already on another track — and a track
     * change always snaps rather than eases, so a wrong guess here costs one poll and never
     * sticks.
     */
    private fun stepSpotifyTrack(by: Int) {
        val next = _playback.value.currentIndex + by
        anchorSpotify(0L, ownCommand = true)
        if (next in queuedTracks.indices) {
            _playback.value = _playback.value.copy(currentIndex = next, isPlaying = true)
        }
    }

    /**
     * On a Spotify album this is the *only* thing the disc can do.
     *
     * Turning the platter normally scratches — resampling the audio as the finger moves, which
     * needs the samples. Spotify never provides them, so the gesture degrades to a seek: the
     * position follows the finger and the sound simply jumps. Every command is also a network
     * round trip, so a scrub cannot be fed to Spotify continuously the way it is fed to
     * ExoPlayer; the caller sends where the finger *landed*, not where it passed.
     */
    suspend fun seekTo(positionMs: Long) {
        activeSpotifyAlbum?.let {
            // The clock takes the new place immediately and owns it for the grace window, so
            // the poll that lands mid-seek cannot drag the playhead back to where it was.
            // The network call waits for the finger to stop — see sendSoon.
            anchorSpotify(positionMs, ownCommand = true)
            sendSoon { spotifyPlayer.seek(positionMs) }
            return
        }
        ensureController().seekTo(positionMs)
    }

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
        activeSpotifyAlbum?.let { album ->
            playSpotifyFrom(album, index, 0L)
            return
        }
        val controller = ensureController()
        controller.seekTo(index, 0L)
        controller.play()
    }

    /**
     * Seek to a position within a specific queue item — what the disc scratch needs, since
     * turning the platter far enough runs off the end of one track and into the next.
     *
     * Deliberately does not pause: seeking while playing is what makes a scrub audible.
     *
     * **This had no Spotify branch, and that was the whole of "i cant scratch disk".** The
     * scratch does not call the one-argument [seekTo] — it calls this one, because turning the
     * platter far enough crosses a track boundary and the gesture has to be able to say which
     * track it landed in. Without a branch here every scratch on a Spotify record went to the
     * local ExoPlayer, which on a Spotify album holds nothing at all: the call succeeded, moved
     * an empty queue, and the music carried on exactly as before. From the outside, a disc that
     * turns under the finger and does nothing.
     *
     * What it can do is still limited by Spotify never handing over the audio (see
     * `SpotifyPlayer`): the position follows the finger and the sound jumps to where it stops,
     * rather than being resampled as it moves. That is a seek, not a scratch, and it is the most
     * this gesture can mean on a record this app is only allowed to remote-control.
     */
    suspend fun seekTo(index: Int, positionMs: Long) {
        activeSpotifyAlbum?.let { album ->
            if (index == _playback.value.currentIndex) {
                seekTo(positionMs)
            } else {
                playSpotifyFrom(album, index, positionMs)
            }
            return
        }
        ensureController().seekTo(index, positionMs)
    }

    /**
     * Puts Spotify on a given track at a given offset, and moves the deck there at once.
     *
     * The Web API has no "change track within what is already playing": starting the album
     * again at an offset is how it is done, and it is why this is one call rather than a seek
     * after a skip. The deck is updated before the request so a tap on the tracklist responds
     * now instead of at the next poll — the poll corrects it within a second if Spotify did
     * something else.
     */
    private fun playSpotifyFrom(album: String, index: Int, positionMs: Long) {
        anchorSpotify(positionMs, ownCommand = true)
        if (index in queuedTracks.indices) {
            _playback.value = _playback.value.copy(currentIndex = index, isPlaying = true)
        }
        // Debounced like every other transport command, and for the sharpest version of the
        // reason: a scratch that crosses track boundaries lands HERE, and this is the call
        // that resolves a device before it plays. Sent twelve times a second it is the most
        // expensive thing this app can possibly do to its own rate limit.
        sendSoon { spotifyPlayer.playAlbum(album, index.coerceAtLeast(0), positionMs.coerceAtLeast(0L)) }
    }

    private companion object {
        /** Ten × the 500ms poll: the resume point is never more than five seconds stale. */
        const val POSITION_SAVE_EVERY_TICKS = 10

        /**
         * How often the Spotify clock publishes a position.
         *
         * Four times a second — twice the local player's rate, and deliberately not once per
         * frame. Nothing that reads [positionMs] needs more: the progress bar is a few hundred
         * pixels wide, a lyric line lasts seconds, and the disc's spin is driven by the frame
         * clock rather than by the playhead. Publishing at 60Hz would recompose the deck sixty
         * times a second to move a bar by a pixel, which is trap 14's mistake with a different
         * cause.
         */
        const val SPOTIFY_TICK_MS = 250L

        /** A disagreement bigger than this is a real move, not drift. */
        const val SPOTIFY_RESYNC_MS = 750L

        /**
         * How long one of our own commands owns the clock.
         *
         * Two seconds: long enough for Spotify to process a seek and for the *next* poll after
         * that to carry the new position, short enough that a command which silently failed is
         * corrected almost at once.
         */
        const val SPOTIFY_COMMAND_GRACE_MS = 2_000L

        /** Idle reports in a row before the disc is allowed to stop. */
        const val SPOTIFY_IDLE_REPORTS_TO_STOP = 2

        /**
         * How long a transport command waits for the finger to stop moving.
         *
         * 220ms against the scratch's own 80ms seek tick, so a drag of any length is one
         * request rather than twelve a second. Short enough that a tap on a track or a skip
         * still feels immediate — and the deck does not wait for it anyway, because the clock
         * moved the instant the gesture did.
         */
        const val SPOTIFY_SEND_DEBOUNCE_MS = 220L
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

/**
 * The same, for a track that lives on a music server.
 *
 * Two things differ from the local case, and both matter.
 *
 * **The URI is built now, not stored.** A Subsonic stream URL carries a one-shot authentication
 * salt (see [SubsonicServer]), so it is only valid as long as it takes to use it. Storing one in
 * the database would mean storing something already stale, and storing the credentials in every
 * row would mean rewriting every row when the password changes.
 *
 * **The cache key is the track, not the URL.** This is the line that makes streaming usable at
 * all. `CacheDataSource` keys on the URI unless told otherwise, and because the salt is fresh
 * every time, the same track would arrive under a different key on every play — a cache that
 * fills up and never once hits, and a scratch gesture that goes to the network for every seek.
 * Pinning the key to Jawon's own stable track id is what makes the second play, and every
 * scrub, come off local disk.
 */
private fun Track.toStreamedMediaItem(
    albumArtist: String?,
    streamUrl: String
): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(streamUrl)
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist?.takeIf { it.isNotBlank() } ?: albumArtist)
                .build()
        )
        .build()
