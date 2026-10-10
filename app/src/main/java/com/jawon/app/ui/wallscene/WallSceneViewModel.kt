package com.jawon.app.ui.wallscene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jawon.app.data.importer.CoverImageStore
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.PlayEventDao
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.ShelfWithAlbums
import com.jawon.app.data.local.Track
import com.jawon.app.data.local.TrackBadge
import com.jawon.app.data.local.TrackPlayStats
import com.jawon.app.data.local.TrackDao
import com.jawon.app.data.playback.AudioRouteReader
import com.jawon.app.data.playback.EqBand
import com.jawon.app.data.playback.EqualizerController
import com.jawon.app.data.playback.PlaybackRepository
import com.jawon.app.data.lyrics.Lyrics
import com.jawon.app.data.prefs.AppPreferences
import com.jawon.app.playback.AlbumDownloader
import com.jawon.app.playback.OfflineState
import com.jawon.app.playback.RackChannel
import com.jawon.app.playback.SleepTimer
import com.jawon.app.ui.common.qualityReadoutSource
import com.jawon.app.ui.theme.RoomScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the in-room deck needs to draw itself. */
data class DeckState(
    val tracks: List<Track> = emptyList(),
    /** Per track id, the marks it has earned. See [TrackBadge]. */
    val badges: Map<String, List<TrackBadge>> = emptyMap(),
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
 *
 * `@UnstableApi` for the same reason `SettingsViewModel` is: it holds an [AlbumDownloader],
 * which is built on Media3's cache APIs. The annotation is lint-enforced rather than
 * compiler-enforced, so leaving it off builds green and fails the release lint pass.
 */
@androidx.media3.common.util.UnstableApi
@HiltViewModel
class WallSceneViewModel @Inject constructor(
    shelfDao: ShelfDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    playEventDao: PlayEventDao,
    private val playbackRepository: PlaybackRepository,
    private val coverStore: CoverImageStore,
    private val albumRemover: com.jawon.app.data.local.AlbumRemover,
    private val equalizer: EqualizerController,
    /** Bass, width, reverb and loudness — whichever of them this phone has. */
    private val nativeEffects: com.jawon.app.data.playback.NativeEffects,
    /** Finds the current track's words. See [com.jawon.app.data.lyrics.LyricsRepository]. */
    private val lyricsRepository: com.jawon.app.data.lyrics.LyricsRepository,
    private val preferences: AppPreferences,
    private val audioRouteReader: AudioRouteReader,
    /** Keeps a streamed album on the phone on request. See [AlbumDownloader]. */
    private val downloader: AlbumDownloader,
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

    /**
     * The rooms' artwork, kept **here** rather than in the rooms (0.7.2). A room is rebuilt every
     * time you switch to it, and with the art in the room's own `remember` every rebuild baked
     * every case and decoded every cover again — the slowest part of arriving, and fresh bitmaps
     * each time for the GPU to upload and hold until a garbage collection got round to the old
     * ones. Measured on the owner's phone as GL memory climbing ~35MB per CD-and-back. This view
     * model outlives both rooms (it is the activity's), so the art is made once.
     */
    internal var caseArtCache: CaseArtCache? = null

    /** The pocket player's decoded covers, by album id — kept across rebuilds for the same reason. */
    internal val pocketCoverArt = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>()

    /** The same, decoded large for sideways Cover Flow, which fills the screen (0.7.3). */
    internal val pocketBigCoverArt = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>()



    /** What colour the room is lit in. The scene reads this and writes it onto the lights. */
    val roomScheme: StateFlow<RoomScheme> = preferences.roomScheme

    /** How fast shelved cases turn, 0..1. The frame driver scales the rotation by it. */
    val caseSpin: StateFlow<Float> = preferences.caseSpin

    /**
     * Albums taken off the wall and waiting out their undo window — see [removed]. Declared
     * before [layout], which reads it as it is built.
     */
    private val _removing = MutableStateFlow<Set<String>>(emptySet())

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
            preferences.wallSort,
            _removing
        ) { shelves, all, totals, sortName, removing ->
            // Albums in their undo window are off the wall while still in the database.
            val albums = if (removing.isEmpty()) all else all.filter { it.id !in removing }
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

    /**
     * The open record's title, for a lyrics lookup.
     *
     * LRCLIB matches on the album as well as the track, which is what separates an album cut
     * from the same song on a compilation. Empty is acceptable — the lookup simply gets less
     * to go on.
     */
    private suspend fun albumTitleNow(): String =
        _nowPlayingAlbumId.value?.let { albumDao.findById(it)?.title }.orEmpty()

    /**
     * The open record's artist, for the same lookup.
     *
     * Supplied because `Track.artist` is null for any file whose tag carried only an album
     * artist, and the lookup needs *an* artist or it cannot ask at all.
     */
    private suspend fun albumArtistNow(): String =
        _nowPlayingAlbumId.value?.let { albumDao.findById(it)?.artist }.orEmpty()

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

    /** Moves the readout only. The player is told on release — see [RackState.pitchApplied]. */
    fun onPitchChange(position: Float) = rack.setPitch(position)

    fun onPitchCommit() = rack.commitPitch()

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
            combine(rack.speedApplied, rack.pitchApplied) { speed, pitch -> speed to pitch }
                .conflate()
                .collect { (speed, pitch) ->
                    playbackRepository.setSpeedAndPitch(
                        com.jawon.app.playback.Varispeed.speedFor(speed),
                        com.jawon.app.playback.Varispeed.pitchFor(pitch)
                    )
                    // **The rate limit, and it has to be here rather than in a `debounce`.**
                    //
                    // `conflate` alone does not throttle anything: it only drops values while
                    // the collector is busy, and `setPlaybackParameters` returns as soon as it
                    // has posted its message, so the collector is never busy. A drag therefore
                    // still delivered its full sixty to a hundred and twenty changes a second.
                    //
                    // That is enough to break the player. Each change reconfigures the audio
                    // pipeline and rebuilds Media3's time-stretcher, and at that rate the
                    // playback thread never finishes: measured spinning at a full core — 607
                    // jiffies per six seconds against a healthy 8 — with playback stalling and
                    // Media3's own watchdog eventually reporting *"Player stuck playing with no
                    // progress for 10000 ms"*. Hot enough to throttle the phone. It reproduces
                    // with Jawon's own effects removed entirely, so this is the player's
                    // pipeline rather than anything in this app's DSP.
                    //
                    // Sleeping here is what makes `conflate` upstream do its job: the collector
                    // is now busy for this long, so everything the finger does in the meantime
                    // collapses to the newest value, and that value is still guaranteed to
                    // arrive when the sleep ends.
                    delay(SPEED_PITCH_SETTLE_MS)
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
    val reverbPreset: StateFlow<Int> = nativeEffects.reverbPreset
    val loudnessGain: StateFlow<Float> = nativeEffects.loudnessGain

    fun onReverbChange(preset: Int) = nativeEffects.setReverbPreset(preset)
    fun onLoudnessChange(value: Float) = nativeEffects.setLoudnessGain(value)

    /**
     * TONE's two knobs, which are the rack's own now rather than the platform's.
     *
     * They read from [RackState] like the filter, the drive and the echo do, because that is
     * where they are computed — see [com.jawon.app.playback.Tone]. The move is invisible from
     * up here apart from these two lines, which is the argument for the rack having owned them
     * all along.
     */
    val bassStrength: StateFlow<Float> = rack.bass
    val stereoWidth: StateFlow<Float> = rack.width

    fun onBassChange(value: Float) = rack.setBass(value)
    fun onWidthChange(value: Float) = rack.setWidth(value)

    /**
     * Which of the rack's eight cells are in circuit. See [RackChannel].
     *
     * **The equaliser is not the rack's to remember.** It has its own on/off, saved in its own
     * preference and shared with the Settings screen, and it defaults to *off* — while every
     * rack channel defaults to *in*. Holding a second copy of that truth here made the EQ cell
     * lie: it drew itself switched in while the equaliser was off, so the first tap turned off
     * something already off and appeared to do nothing at all. Every effect needed two taps.
     *
     * So the cell's state is read from the equaliser itself rather than mirrored.
     */
    val engagedChannels: StateFlow<Set<RackChannel>> =
        combine(rack.engaged, equalizer.enabled) { engaged, eqOn ->
            if (eqOn) engaged + RackChannel.EQ else engaged - RackChannel.EQ
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            rack.engaged.value - RackChannel.EQ
        )

    /**
     * A cell on the rack's grid has been tapped: switch that effect in, or take it out.
     *
     * Two of the eight are not this app's own DSP — [RackChannel.SPACE] runs on the platform's
     * reverb and loudness, and [RackChannel.EQ] is the system equaliser. [RackState] stays the
     * single answer to "what is in circuit" for everything it actually owns, and those two are
     * told rather than observed.
     *
     * TONE used to be a third. It is not any more: the platform would not give up its bass
     * boost or its virtualiser on the target phone, so both are Jawon's own arithmetic now and
     * the cell needs no telling. See [com.jawon.app.playback.Tone].
     */
    /**
     * Switches a cell in if it is out. The pocket player's sliders only ever mean "in" — turning
     * an effect up that is bypassed would otherwise do nothing you could hear.
     */
    fun engage(channel: RackChannel) {
        if (channel == RackChannel.EQ) {
            if (!equalizer.enabled.value) onEqEnabledChanged(true)
            return
        }
        if (!rack.isEngaged(channel)) onChannelToggle(channel)
    }

    fun onChannelToggle(channel: RackChannel) {
        // The equaliser owns its own switch, so this flips that and never touches the rack's
        // set — otherwise the two drift and the cell starts lying again.
        if (channel == RackChannel.EQ) {
            onEqEnabledChanged(!equalizer.enabled.value)
            return
        }
        rack.toggleEngaged(channel)
        val engaged = rack.isEngaged(channel)
        when (channel) {
            RackChannel.SPACE -> nativeEffects.setSpaceEngaged(engaged)
            else -> Unit
        }
    }

    /** The echo's three knobs. See [com.jawon.app.playback.Echo]. */
    val echoTime: StateFlow<Float> = rack.echoTime
    val echoFeedback: StateFlow<Float> = rack.echoFeedback
    val echoMix: StateFlow<Float> = rack.echoMix

    fun onEchoTimeChange(position: Float) = rack.setEchoTime(position)
    fun onEchoFeedbackChange(position: Float) = rack.setEchoFeedback(position)
    fun onEchoMixChange(position: Float) = rack.setEchoMix(position)

    /** Read only by the progress bar, so the 2Hz tick can't recompose the tracklist. */
    val positionMs: StateFlow<Long> = playbackRepository.positionMs

    /**
     * Fires when a record has played all the way through.
     *
     * The room answers it by closing the case — see the collector in `WallSceneScreen`. It is
     * deliberately *not* answered here by starting something else: an album is the atomic unit
     * (PLAN §2), and auto-advancing to another record is the shuffle-a-huge-library behaviour
     * this app exists not to have.
     */
    val albumEnded = playbackRepository.albumEnded

    @Suppress("OPT_IN_USAGE")
    val deck: StateFlow<DeckState> = _nowPlayingAlbumId
        .flatMapLatest { albumId ->
            if (albumId == null) {
                flowOf(emptyList<Track>() to emptyMap<String, List<TrackBadge>>())
            } else {
                // Badges are folded in *here*, not in the combine below, so they are recomputed
                // only when the tracklist or the play history actually changes. Doing it after
                // the playback combine would redo the whole album's arithmetic on every
                // transport event, for an answer that cannot have moved.
                combine(
                    trackDao.getForAlbum(albumId),
                    playEventDao.trackStatsForAlbum(albumId)
                ) { tracks, stats -> tracks to badgesFor(tracks, stats) }
            }
        }
        .combine(playbackRepository.playback) { (tracks, badges), playback ->
            // Read per emission rather than once at construction. The old code took a single
            // reading when the ViewModel was built, so plugging in headphones after opening
            // the app left the readout insisting on SPEAKER for the rest of the session.
            val route = audioRouteReader.currentRoute()
            DeckState(
                tracks = tracks,
                badges = badges,
                currentTrackIndex = playback.currentIndex,
                isPlaying = playback.isPlaying,
                durationMs = playback.durationMs,
                routeLabel = route.routeLabel,
                outputSampleRateHz = route.outputSampleRateHz
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckState())

    /**
     * Works out which marks each track of one album has earned.
     *
     * The decisions themselves are in [TrackBadge], which is pure and tested; this only gathers
     * what that needs — a track's own stats, and the two facts that are properties of the album
     * rather than of any track in it.
     *
     * Tracks that have never been played are still handed to [TrackBadge.albumFacts], with zero
     * plays, so "the most played" is decided against the whole record rather than against the
     * subset that happens to have history.
     */
    private fun badgesFor(
        tracks: List<Track>,
        stats: List<TrackPlayStats>
    ): Map<String, List<TrackBadge>> {
        if (tracks.isEmpty()) return emptyMap()
        val byId = stats.associateBy { it.trackId }
        val facts = TrackBadge.albumFacts(
            tracks.map { byId[it.id] ?: TrackPlayStats(it.id, plays = 0, furthestMs = 0L, firstStartedAt = 0L) }
        )
        val openerId = tracks.first().id
        return tracks.associate { track ->
            track.id to TrackBadge.forTrack(
                durationMs = track.durationMs,
                mimeType = track.mimeType,
                bitDepth = track.bitDepth,
                sampleRateHz = track.sampleRateHz,
                stats = byId[track.id],
                isMostPlayedInAlbum = facts.mostPlayedTrackId == track.id,
                isFirstInAlbumOrder = track.id == openerId,
                isEarliestPlayedInAlbum = facts.earliestPlayedTrackId == track.id
            )
        }
    }

    /**
     * The current track's words, or null when it has none.
     *
     * Keyed on the track rather than the album, and re-read only when the track actually
     * changes — `deck` re-emits on every playback tick, and re-reading a megabyte of tag at
     * that rate would be exactly the file I/O ENGINEERING.md rule 5 forbids. `LyricsRepository`
     * caches what it finds, so returning to a track costs nothing.
     */
    val lyrics: StateFlow<com.jawon.app.data.lyrics.LyricsState> = deck
        .map { it.currentTrack }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        // **Says it is looking, rather than saying there are none.**
        //
        // This used to be a plain `map` to a nullable, with null standing for both "the lookup
        // has not finished" and "there are no words" — which the page renders identically. The
        // third tier of the search is a request to LRCLIB, so for any track the file itself
        // cannot answer, opening the page asserted that the track has no lyrics and then
        // produced them a second later. That reads as the app having been wrong, not busy.
        //
        // `flatMapLatest` so a track change abandons a lookup already in flight: the answer to
        // the previous track is not merely late by then, it is wrong.
        .flatMapLatest { track ->
            flow {
                if (track == null) {
                    emit(com.jawon.app.data.lyrics.LyricsState.Idle)
                    return@flow
                }
                emit(com.jawon.app.data.lyrics.LyricsState.Looking)
                val found = lyricsRepository.lyricsFor(track, albumTitleNow(), albumArtistNow())
                emit(
                    if (found == null) com.jawon.app.data.lyrics.LyricsState.None
                    else com.jawon.app.data.lyrics.LyricsState.Ready(found)
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.jawon.app.data.lyrics.LyricsState.Idle
        )

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
        viewModelScope.launch { removeAlbum(albumId) }
    }

    /**
     * Stops playback if this is the record playing, then hands the cleanup to [AlbumRemover].
     *
     * The stop is this view model's concern and the deletion is not: leaving the deck driving a
     * queue whose album no longer exists is a room problem, while what an album leaves behind
     * in the database is the same wherever it is removed from.
     */
    private suspend fun removeAlbum(albumId: String) {
        if (_nowPlayingAlbumId.value == albumId) stop()
        albumRemover.remove(albumId)
    }

    /**
     * Outstanding removals — the animation's length, then the undo window — so [skipDiscard] can
     * cut one short and [undoRemove] can call one off.
     */
    private val discardJobs = mutableMapOf<String, Job>()

    /** A record taken off the wall and waiting out its undo window. */
    data class Removed(val albumId: String, val title: String)

    private val _removed = MutableStateFlow<Removed?>(null)

    /**
     * **The undo** (0.7.4): the record just taken off the wall, for as long as it can still come
     * back. Removing an album was a dialog and then gone; now the case still breaks apart and
     * leaves the shelf, but the album is only hidden ([_removing]) and the bar under the wall
     * offers UNDO for [UNDO_WINDOW_MS]. Only when that runs out is anything deleted.
     *
     * Hidden rather than deleted and restored, because restoring is the hard and risky half:
     * `AlbumRemover` takes the tracks, the listening history and the cover files with it. And if
     * the app is killed inside the window, nothing has been deleted — the record is simply back
     * on its shelf next time, which is the safe way for that to go wrong (ENGINEERING.md trap 31).
     */
    val removed: StateFlow<Removed?> = _removed.asStateFlow()

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
            takeOff(albumId)
        }
    }

    /** A second tap on a case already breaking apart — finish now instead of waiting it out. */
    fun skipDiscard(albumId: String) {
        discardJobs.remove(albumId)?.cancel()
        discardJobs[albumId] = viewModelScope.launch { takeOff(albumId) }
    }

    private val _restored = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A record UNDO just put back, for the wall to turn to. */
    val restored: kotlinx.coroutines.flow.SharedFlow<String> = _restored

    /** UNDO: the record goes back on its shelf, untouched. */
    fun undoRemove() {
        val r = _removed.value ?: return
        discardJobs.remove(r.albumId)?.cancel()
        _removing.value = _removing.value - r.albumId
        _removed.value = null
        _restored.tryEmit(r.albumId)
    }

    /**
     * Off the wall, into the undo window, and — if it is not called back — deleted. One undo at a
     * time: a second removal inside the first's window finishes the first.
     */
    private suspend fun takeOff(albumId: String) {
        if (_nowPlayingAlbumId.value == albumId) stop()
        _removed.value?.takeIf { it.albumId != albumId }?.let { previous ->
            discardJobs.remove(previous.albumId)?.cancel()
            viewModelScope.launch { finishRemoval(previous.albumId) }
        }
        val title = albumDao.findById(albumId)?.title.orEmpty()
        _removing.value = _removing.value + albumId
        _removed.value = Removed(albumId, title)
        delay(UNDO_WINDOW_MS)
        discardJobs.remove(albumId)
        finishRemoval(albumId)
    }

    private suspend fun finishRemoval(albumId: String) {
        removeAlbum(albumId)
        _removing.value = _removing.value - albumId
        if (_removed.value?.albumId == albumId) _removed.value = null
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
            // The record already on (0.7.3): closing a case no longer stops it, so opening it
            // again from the bar or the shelf finds it playing. Loading it afresh would cut the
            // music and jump back to the last saved place, up to five seconds behind — so it is
            // only asked to play, from exactly where it is.
            if (_nowPlayingAlbumId.value == albumId &&
                playbackRepository.playback.value.queuedTrackIds == tracks.map { it.id }
            ) {
                playbackRepository.setPlaying(true)
                return@launch
            }
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

    /**
     * Play an album from a track chosen off its list — the pocket player's tracklist.
     *
     * Not [playAlbum]: that resumes where the record was left, which is right for taking a
     * record off the wall and wrong for choosing a song by name. Choosing one is a request to
     * hear *that* one, from its start.
     */
    fun playAlbumFrom(albumId: String, trackIndex: Int) {
        viewModelScope.launch {
            val tracks = trackDao.getForAlbum(albumId).first()
            if (tracks.isEmpty()) return@launch
            _nowPlayingAlbumId.value = albumId
            val album = albumDao.findById(albumId)
            playbackRepository.playAlbum(
                tracks,
                startIndex = trackIndex.coerceIn(0, tracks.lastIndex),
                albumArtist = album?.artist
            )
        }
    }

    /** One album's track titles, in order, for a list that shows them. One shot, off the main thread. */
    suspend fun trackTitles(albumId: String): List<String> =
        trackDao.getForAlbum(albumId).first().map { it.title }

    /**
     * Take on whatever album the player already has queued, if this view model has none.
     *
     * Come back to a room while music played on in the background and it should know what is
     * on. The pocket player's Now Playing has always needed this; the CD room does too since
     * 0.7.3, when closing a case stopped stopping the record — its bar and its proud case are
     * the room saying what is on.
     */
    fun adoptPlayingAlbum() {
        viewModelScope.launch {
            playbackRepository.attach()
            if (_nowPlayingAlbumId.value != null) return@launch
            val first = playbackRepository.playback.value.queuedTrackIds.firstOrNull() ?: return@launch
            val albumId = trackDao.albumIdOf(first) ?: return@launch
            if (_nowPlayingAlbumId.value == null) _nowPlayingAlbumId.value = albumId
        }
    }

    /**
     * Called when the case closes. The record stays the one that is on either way — its case sits
     * proud and the bar carries it — and only the music's state depends on the owner's choice:
     * playing on (the default) or paused, as a disc put back in its box would be.
     */
    fun onCaseClosed() {
        if (preferences.keepPlayingOnClose.value) return
        viewModelScope.launch { playbackRepository.setPlaying(false) }
    }

    /**
     * Put the record away: pause it and let the wall forget it, so the bar goes and its case
     * settles back into its slot. The bar's swipe down, and removing the album that is on.
     */
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

    // ---- The record still turning while you browse ----------------------------------

    /**
     * What the mini-player needs: which record is on, and the disc to spin for it.
     *
     * The disc bitmap is baked here rather than read from `CaseArtCache`, and the reason is the
     * whole point of the feature. That cache holds the forty-odd cases nearest the frustum and
     * trims the rest, so the album you are *listening to* is the first thing it drops once you
     * pan away from it — which is exactly the moment a now-playing bar has to draw something.
     */
    data class NowPlaying(
        val albumId: String,
        val title: String,
        val artist: String,
        /** Null while it is still being baked, or if the album has no artwork at all. */
        val disc: android.graphics.Bitmap?
    )

    /**
     * The record that is on, or null when nothing is.
     *
     * Keyed on the album id alone, so this does not re-run on transport ticks — `deck` re-emits
     * on every playback event and re-baking a disc at that rate would be image decoding on a
     * flow, which is the file-I/O rule in a different costume.
     */
    @Suppress("OPT_IN_USAGE")
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlayingAlbumId
        // No `distinctUntilChanged` — a StateFlow already conflates equal values, and applying
        // it is both a no-op and deprecated for exactly that reason.
        .mapLatest { albumId ->
            if (albumId == null) return@mapLatest null
            withContext(Dispatchers.IO) {
                val album = albumDao.findById(albumId) ?: return@withContext null
                NowPlaying(
                    albumId = albumId,
                    title = album.title,
                    artist = album.artist,
                    // The same bake the room's own disc uses, so the small one is the same
                    // object seen further away rather than a second drawing of it.
                    disc = runCatching {
                        DiscArt.bakeFace(
                            face = CaseArt.loadCoverSquare(album.discFaceUri),
                            fallback = CaseArt.loadCoverSquare(album.caseCoverUri)
                        )
                    }.getOrNull()
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Keeping a streamed record on the phone ------------------------------------
    //
    // Only ever visible for an album that came from a server. A local album is already on the
    // phone, and offering to download it would be offering to copy a file onto itself.

    private val _offline = MutableStateFlow<OfflineState?>(null)

    /** How much of the open album is stored, or null when it is not a streamed one. */
    val offline: StateFlow<OfflineState?> = _offline.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _offlineMessage = MutableStateFlow<String?>(null)

    /** Why the last attempt did not work, for the deck to say out loud. */
    val offlineMessage: StateFlow<String?> = _offlineMessage.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            deck
                .map { it.tracks }
                // The deck re-emits on every playback tick; the tracklist changes when a case
                // opens. Reading the cache index sixty times a minute for an answer that only
                // moves when a record does would be the same mistake the lyrics flow documents.
                .distinctUntilChanged { a, b -> a.map { it.id } == b.map { it.id } }
                .collect { tracks ->
                    downloadJob?.cancel()
                    _offlineMessage.value = null
                    _offline.value = offlineStateOf(tracks)
                }
        }
    }

    /**
     * Null for a local album — that is what makes the control disappear rather than mislead.
     * A local album is already on the phone, and keeping it would be copying a file onto itself.
     */
    private fun offlineStateOf(tracks: List<Track>): OfflineState? =
        if (tracks.none { it.remoteId != null }) null else downloader.stateOf(tracks)

    /**
     * Pulls the open album down in full.
     *
     * Deliberately fire-and-forget from the deck's point of view: the progress is in [offline],
     * which the row draws, and leaving the deck does not stop it. Opening a *different* record
     * does, in the collector above — one download at a time, so a stream and a download are not
     * competing for the same connection.
     */
    fun onKeepOffline() {
        val tracks = deck.value.tracks
        if (tracks.isEmpty()) return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloading.value = true
            _offlineMessage.value = null
            try {
                when (val result = downloader.download(tracks) { _offline.value = it }) {
                    is AlbumDownloader.Result.Done -> Unit
                    is AlbumDownloader.Result.TooLarge -> _offlineMessage.value =
                        "Needs ${result.neededMb} MB — the offline store holds ${result.limitMb} MB. " +
                            "Make it bigger in Settings."
                    is AlbumDownloader.Result.Failed -> _offlineMessage.value = result.reason
                }
            } finally {
                // In a `finally` because cancelling unwinds rather than returning, and a
                // cancelled download that left this true would show a spinner forever.
                _downloading.value = false
                _offline.value = offlineStateOf(tracks)
            }
        }
    }

    fun onCancelKeepOffline() {
        downloadJob?.cancel()
    }

    /** Gives the space back. The album stays on the wall and goes back to streaming. */
    fun onDropOffline() = viewModelScope.launch {
        val tracks = deck.value.tracks
        downloadJob?.cancel()
        downloader.remove(tracks)
        _offlineMessage.value = null
        _offline.value = offlineStateOf(tracks)
    }

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

    // ---- The pocket player's own Settings menu --------------------------------------------
    //
    // Pass-throughs rather than a view model of its own: three preferences and a count are not
    // yet two rooms pulling this class in different directions (docs/formats.md §3).

    /** Sound Check, as the classic called it: which ReplayGain tags to level by. */
    val replayGainMode: StateFlow<String> = preferences.replayGainMode

    fun onReplayGainModeChanged(mode: com.jawon.app.playback.ReplayGain.Mode) =
        preferences.setReplayGainMode(mode.name)

    val pocketBacklightMs: StateFlow<Long> = preferences.pocketBacklightMs

    fun onPocketBacklightChanged(ms: Long) = preferences.setPocketBacklightMs(ms)

    val pocketClicker: StateFlow<Boolean> = preferences.pocketClicker

    fun onPocketClickerChanged(on: Boolean) = preferences.setPocketClicker(on)

    /** Which rooms' first-run hints have been put away (0.7.4). */
    val hintsSeen: StateFlow<Set<String>> = preferences.hintsSeen

    fun onHintSeen(id: String) = preferences.markHintSeen(id)

    /** Tilt & Turn — see `AppPreferences.pocketMotion`. */
    val pocketMotion: StateFlow<Boolean> = preferences.pocketMotion

    fun onPocketMotionChanged(on: Boolean) = preferences.setPocketMotion(on)

    /** Brick's best score; written only when beaten. */
    val pocketBrickBest: StateFlow<Int> = preferences.pocketBrickBest

    fun onBrickScore(score: Int) = preferences.setPocketBrickBest(score)

    /**
     * Shuffle Albums: a whole record at random, from its first track — never the one already on,
     * when there is another. The album stays the unit (PLAN §2); this only chooses which one.
     * Returns the album chosen, so the pocket player can show it straight away.
     */
    fun shuffleAlbum(albumIds: List<String>): String? {
        if (albumIds.isEmpty()) return null
        val choices = albumIds.filter { it != _nowPlayingAlbumId.value }.ifEmpty { albumIds }
        val chosen = choices.random()
        playAlbumFrom(chosen, 0)
        return chosen
    }

    /** The pocket player's colour, by name — see `PocketFinish`. */
    val pocketFinish: StateFlow<String?> = preferences.pocketFinish

    fun onPocketFinishChanged(name: String) = preferences.setPocketFinish(name)

    /** How many songs the library holds, for the pocket player's About. */
    val trackCount: StateFlow<Int> = trackDao.count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun onEqBandChanged(index: Int, levelMb: Int) = equalizer.setBand(index, levelMb)
    fun onEqPreset(index: Int) = equalizer.usePreset(index)
    fun onEqReset() = equalizer.reset()

    companion object {
        /** Within this of the end of the last track, the album counts as finished. */
        const val FINISHED_TAIL_MS = 5_000L

        /** How long a removed record can still be called back — see [removed]. */
        const val UNDO_WINDOW_MS = 7_000L

        /**
         * The shortest gap between two changes to playback speed or pitch.
         *
         * Eight or so updates a second: fast enough that a slider still feels live under the
         * finger, slow enough that the player's pipeline can finish rebuilding itself between
         * them. Sixty a second is what broke it.
         */
        const val SPEED_PITCH_SETTLE_MS = 120L
    }
}
