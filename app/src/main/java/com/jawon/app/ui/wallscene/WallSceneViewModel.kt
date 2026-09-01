package com.jawon.app.ui.wallscene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.CoverImageStore
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.PlayEventDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.AudioRouteReader
import com.jawon.app.data.playback.EqBand
import com.jawon.app.data.playback.EqualizerController
import com.jawon.app.data.playback.PlaybackRepository
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.playback.SleepTimer
import com.jawon.app.ui.common.qualityReadoutSource
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the in-room deck needs to draw itself. */
data class DeckState(
    val tracks: List<Track> = emptyList(),
    val currentTrackIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val routeLabel: String = "",
    val outputSampleRateHz: Int = 0
) {
    val currentTrack: Track? get() = tracks.getOrNull(currentTrackIndex)
}

/**
 * Supplies the wall's *layout* and the deck's state, and owns playback for whichever case is
 * open. Pan, fling and the open sequence deliberately do not live here: per ENGINEERING.md,
 * high-frequency state must not drive Compose recomposition, so the scene owns it and writes
 * the nodes directly.
 */
@HiltViewModel
class WallSceneViewModel @Inject constructor(
    shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    playEventDao: PlayEventDao,
    private val playbackRepository: PlaybackRepository,
    private val coverStore: CoverImageStore,
    private val equalizer: EqualizerController,
    /** Bass, width, reverb and loudness — whichever of them this phone has. */
    private val nativeEffects: com.jawon.app.data.playback.NativeEffects,
    private val preferences: AppPreferences,
    private val audioRouteReader: AudioRouteReader,
    /**
     * Written by the frame loop, read by Settings. Exposed rather than injected into the
     * composable directly so the scene keeps talking to one object.
     */
    val diagnostics: RoomDiagnostics,
    /** Makes the sound while a hand is on the disc. See [com.jawon.app.playback.ScratchAudio]. */
    val scratchAudio: com.jawon.app.playback.ScratchAudio,
    /** The effects rack, shared with the service that runs it. See [com.jawon.app.playback.RackState]. */
    private val rack: com.jawon.app.playback.RackState
) : ViewModel() {


    /** What colour the room is lit in. The scene reads this and writes it onto the lights. */
    val roomScheme: StateFlow<RoomScheme> = preferences.roomScheme

    /** How fast shelved cases turn, 0..1. The frame driver scales the rotation by it. */
    val caseSpin: StateFlow<Float> = preferences.caseSpin

    /**
     * The wall, including how played-in each case looks.
     *
     * `albumTotals()` re-emits on every write to `PlayEvent`, which is often while music is
     * running. That is safe here only because [Patina] quantises wear to eight steps: between
     * steps every `CaseLayout` compares equal, the whole `WallLayout` therefore compares
     * equal, and `stateIn`'s `StateFlow` conflates the emission away without the scene ever
     * being told. Take the quantisation out and this becomes a bitmap re-bake per play tick.
     */
    val layout: StateFlow<WallLayout> =
        combine(
            shelfDao.getAll(),
            albumDao.getAll(),
            playEventDao.albumTotals(),
            preferences.wallSort
        ) { shelves, albums, totals, sortName ->
            val albumsByShelf = albums.groupBy { it.shelfId }
            // One read of the totals, two uses: the quantised wear the cases are drawn with,
            // and the raw milliseconds MOST PLAYED orders by. Deriving both here keeps the
            // two from ever disagreeing about which record has been played more.
            WallLayout.from(
                shelves.map { shelf -> ShelfWithAlbums(shelf, albumsByShelf[shelf.id].orEmpty()) },
                Patina.byAlbum(totals),
                WallSort.fromName(sortName),
                totals.associate { it.albumId to it.totalMs }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallLayout())

    /** How the wall is filed, for the chip on the shelf screen and the crate flip's copy. */
    val wallSort: StateFlow<WallSort> = preferences.wallSort
        .map { WallSort.fromName(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallSort.SHELF)

    fun onWallSortChanged(sort: WallSort) = preferences.setWallSort(sort.name)

    /** The album whose case should sit proud of its slot (PLAN.md §9 #3). */
    private val _nowPlayingAlbumId = MutableStateFlow<String?>(null)
    val nowPlayingAlbumId: StateFlow<String?> = _nowPlayingAlbumId.asStateFlow()

    // ---- Sleep timer -----------------------------------------------------------------

    val sleepMode: StateFlow<SleepTimer.Mode> = playbackRepository.sleepMode

    /**
     * Milliseconds left on a countdown, ticked once a second.
     *
     * Its own slow flow rather than the 2Hz position poll: the chip shows whole minutes, so
     * anything faster recomposes the deck for a number that has not changed.
     */
    val sleepRemainingMs: StateFlow<Long?> = playbackRepository.sleepEndsAt
        .flatMapLatest { endsAt ->
            if (endsAt == null) flowOf(null)
            else flow<Long?> {
                while (true) {
                    emit(SleepTimer.remainingMs(endsAt, System.currentTimeMillis()))
                    delay(1_000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onSleepModeChange(mode: SleepTimer.Mode) = playbackRepository.setSleepMode(mode)


    /**
     * The filter knob: −1 low-pass closed … 0 off … +1 high-pass closed.
     *
     * Unlike the fader this does not spring back — a filter is a state you leave a track in
     * while it plays, not a gesture you hold. See [com.jawon.app.playback.DjFilter].
     */
    val filterPosition: StateFlow<Float> = rack.filter

    fun onFilterChange(position: Float) = rack.setFilter(position)

    /**
     * Tempo and key, −1 … 0 … +1 each, and independent of one another.
     *
     * A turntable cannot do this — its pitch follows its speed because that is what a groove
     * passing a needle means — and [com.jawon.app.playback.ScratchTone] deliberately keeps them
     * welded for exactly that reason. Here Media3's time-stretcher takes them apart.
     */
    val speedPosition: StateFlow<Float> = rack.speed
    val pitchPosition: StateFlow<Float> = rack.pitch

    fun onSpeedChange(position: Float) = rack.setSpeed(position)

    fun onPitchChange(position: Float) = rack.setPitch(position)

    /**
     * Carries the two knobs to the player, at a rate the player can survive.
     *
     * **This used to fire per pointer event, and that was a real fault.** A drag emits sixty to
     * a hundred and twenty moves a second, each one previously launching a coroutine that made
     * an IPC to the session — and `setPlaybackParameters` is not a cheap setter. It makes
     * ExoPlayer reconfigure its audio pipeline: Sonic is rebuilt and every `AudioProcessor` in
     * the chain, of which Jawon now has three, is flushed. Several hundred of those in the
     * couple of seconds it takes to drag a slider is enough to stall playback and leave the
     * transport unresponsive, which is what the owner hit while testing speed, pitch and the
     * filter together.
     *
     * `conflate` is the fix and the right shape besides: while a call is in flight the
     * intermediate positions are dropped, the newest one is kept, and the value the finger
     * finally rested on is always the one that lands. No debounce, so it stays responsive —
     * this throttles to what the player can accept rather than to a timer.
     */
    init {
        viewModelScope.launch {
            combine(rack.speed, rack.pitch) { speed, pitch -> speed to pitch }
                .conflate()
                .collect { (speed, pitch) ->
                    playbackRepository.setSpeedAndPitch(
                        com.jawon.app.playback.Varispeed.speedFor(speed),
                        com.jawon.app.playback.Varispeed.pitchFor(pitch)
                    )
                }
        }
    }

    /** Saturation, 0 clean … 1 broken. See [com.jawon.app.playback.Drive]. */
    val drivePosition: StateFlow<Float> = rack.drive

    fun onDriveChange(position: Float) = rack.setDrive(position)

    /**
     * The four the platform provides, and which of them this phone actually has.
     *
     * Availability is a flow rather than a constant because the effects are built when a player
     * acquires its audio session, which happens after the room is already on screen.
     */
    val nativeAvailable: StateFlow<Set<com.jawon.app.data.playback.NativeEffect>> =
        nativeEffects.available
    val bassStrength: StateFlow<Float> = nativeEffects.bassStrength
    val stereoWidth: StateFlow<Float> = nativeEffects.stereoWidth
    val reverbPreset: StateFlow<Int> = nativeEffects.reverbPreset
    val loudnessGain: StateFlow<Float> = nativeEffects.loudnessGain

    fun onBassChange(value: Float) = nativeEffects.setBassStrength(value)
    fun onWidthChange(value: Float) = nativeEffects.setStereoWidth(value)
    fun onReverbChange(preset: Int) = nativeEffects.setReverbPreset(preset)
    fun onLoudnessChange(value: Float) = nativeEffects.setLoudnessGain(value)

    /** The echo's three knobs. See [com.jawon.app.playback.Echo]. */
    val echoTime: StateFlow<Float> = rack.echoTime
    val echoFeedback: StateFlow<Float> = rack.echoFeedback
    val echoMix: StateFlow<Float> = rack.echoMix

    fun onEchoTimeChange(position: Float) = rack.setEchoTime(position)
    fun onEchoFeedbackChange(position: Float) = rack.setEchoFeedback(position)
    fun onEchoMixChange(position: Float) = rack.setEchoMix(position)

    /** Read only by the progress bar, so the 2Hz tick can't recompose the tracklist. */
    val positionMs: StateFlow<Long> = playbackRepository.positionMs

    @Suppress("OPT_IN_USAGE")
    val deck: StateFlow<DeckState> = _nowPlayingAlbumId
        .flatMapLatest { albumId ->
            if (albumId == null) flowOf(emptyList()) else trackDao.getForAlbum(albumId)
        }
        .combine(playbackRepository.playback) { tracks, playback ->
            // Read per emission rather than once at construction. The old code took a single
            // reading when the ViewModel was built, so plugging in headphones after opening
            // the app left the readout insisting on SPEAKER for the rest of the session.
            val route = audioRouteReader.currentRoute()
            DeckState(
                tracks = tracks,
                currentTrackIndex = playback.currentIndex,
                isPlaying = playback.isPlaying,
                durationMs = playback.durationMs,
                routeLabel = route.routeLabel,
                outputSampleRateHz = route.outputSampleRateHz
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckState())

    /**
     * Takes an album off the shelf.
     *
     * **The user's audio files are never touched, and the confirmation says so.** They live
     * in the user's own folders and Jawon holds only a read grant on them; an album is a
     * record *about* those files. What does get deleted is what Jawon made: the row, its
     * tracks, and the cover and disc images copied into private storage at import — leaving
     * those behind would slowly fill the phone with orphans nothing can reach.
     *
     * Stops playback first if this is the album playing, so the deck is not left driving a
     * queue whose album no longer exists.
     */
    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            if (_nowPlayingAlbumId.value == albumId) stop()
            val album = albumDao.findById(albumId)
            trackDao.deleteForAlbum(albumId)
            albumDao.deleteById(albumId)
            album?.let { coverStore.deleteAll(it.caseCoverUri, it.discFaceUri) }
        }
    }

    /** Outstanding [scheduleDiscard] timers, so [skipDiscard] can cut one short. */
    private val discardJobs = mutableMapOf<String, Job>()

    /**
     * Deletes [albumId] after [afterMs] rather than immediately — this is what lets the wall
     * play [CaseDiscard] to completion on the shelf before the album disappears from [layout].
     *
     * **On [viewModelScope], deliberately not on a scope owned by the composable that calls
     * it.** A `rememberCoroutineScope()` in `WallSceneScreen` is cancelled the moment that
     * composable leaves composition — which a confirmed deletion must survive. The room is the
     * app's start destination, but a user can still background the app or open Settings in the
     * roughly one second between confirming and this actually running, and the album has to
     * come off the shelf either way. `viewModelScope` lives with the ViewModel, not the screen.
     */
    fun scheduleDiscard(albumId: String, afterMs: Long) {
        discardJobs[albumId]?.cancel()
        discardJobs[albumId] = viewModelScope.launch {
            delay(afterMs)
            deleteAlbum(albumId)
            discardJobs.remove(albumId)
        }
    }

    /** A second tap on a case already breaking apart — finish now instead of waiting it out. */
    fun skipDiscard(albumId: String) {
        discardJobs.remove(albumId)?.cancel()
        deleteAlbum(albumId)
    }

    /**
     * Called when the open sequence lands — the disc is up and spinning.
     *
     * Picks up where the record was left. A CD player that forgets its place is one nobody
     * trusts, and this room is pretending to be a physical object: take a record off, put it
     * back on, and it should be where you left it.
     *
     * The saved position is dropped when it lands in the last few seconds of the final track,
     * because "resuming" an album you finished means starting it again, not hearing its last
     * breath and stopping.
     */
    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val tracks = trackDao.getForAlbum(albumId).first()
            if (tracks.isEmpty()) return@launch
            _nowPlayingAlbumId.value = albumId

            val album = albumDao.findById(albumId)
            val index = album?.lastTrackIndex?.coerceIn(0, tracks.lastIndex) ?: 0
            val position = album?.lastPositionMs ?: 0L
            val finished = index == tracks.lastIndex &&
                position > 0L &&
                position >= tracks[index].durationMs - FINISHED_TAIL_MS

            if (finished) {
                playbackRepository.playAlbum(tracks, albumArtist = album?.artist)
            } else {
                playbackRepository.playAlbum(
                    tracks,
                    startIndex = index,
                    startPositionMs = position,
                    albumArtist = album?.artist
                )
            }
        }
    }

    /** Called when the case closes. */
    fun stop() {
        viewModelScope.launch {
            if (playbackRepository.playback.value.isPlaying) playbackRepository.playPause()
            _nowPlayingAlbumId.value = null
        }
    }

    fun onPlayPause() = viewModelScope.launch { playbackRepository.playPause() }

    /**
     * Track titles for one album, for printing on its case back. One shot, off the main
     * thread — the wall bakes each back once and keeps the bitmap.
     */
    suspend fun backInlay(albumId: String): CaseArt.BackInlay {
        val tracks = trackDao.getForAlbum(albumId).first()
        return CaseArt.BackInlay(
            titles = tracks.map { it.title },
            totalMs = tracks.sumOf { it.durationMs },
            // From the first track: an album is one recording in practice, and printing a
            // different format per track would be describing the files rather than the record.
            formatLabel = tracks.firstOrNull()
                ?.takeIf { it.sampleRateHz > 0 }
                ?.let { qualityReadoutSource(it) }
        )
    }

    /** Explicit rather than a toggle — see `PlaybackRepository.setPlaying`. */
    fun setPlaying(playing: Boolean) =
        viewModelScope.launch { playbackRepository.setPlaying(playing) }
    fun onNext() = viewModelScope.launch { playbackRepository.seekToNext() }
    fun onPrevious() = viewModelScope.launch { playbackRepository.seekToPrevious() }
    fun onSeek(positionMs: Long) = viewModelScope.launch { playbackRepository.seekTo(positionMs) }
    fun onSelectTrack(index: Int) = viewModelScope.launch { playbackRepository.seekToTrack(index) }

    /**
     * Where the disc scratch has dragged playback to. Called at the scratch's throttled rate
     * rather than per touch move — see [Scratch.SEEK_THROTTLE_MS].
     */
    fun onScrub(trackIndex: Int, positionMs: Long) =
        viewModelScope.launch { playbackRepository.seekTo(trackIndex, positionMs) }

    // ---- Equaliser, from inside the deck ------------------------------------------
    //
    // An equaliser you can only reach by closing the record you are listening to is one you
    // cannot actually use: it is adjusted by ear, and the ear needs the music playing.

    val eqAvailable: StateFlow<Boolean> = equalizer.available
    val eqEnabled: StateFlow<Boolean> = equalizer.enabled
    val eqBands: StateFlow<List<EqBand>> = equalizer.bands
    val eqRangeMb: StateFlow<Pair<Int, Int>> = equalizer.rangeMb
    val eqPresets: StateFlow<List<String>> = equalizer.presets

    fun onEqEnabledChanged(on: Boolean) = equalizer.setEnabled(on)
    fun onEqBandChanged(index: Int, levelMb: Int) = equalizer.setBand(index, levelMb)
    fun onEqPreset(index: Int) = equalizer.usePreset(index)
    fun onEqReset() = equalizer.reset()

    private companion object {
        /** Within this of the end of the last track, the album counts as finished. */
        const val FINISHED_TAIL_MS = 5_000L
    }
}
