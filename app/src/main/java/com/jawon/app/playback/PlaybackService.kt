package com.jawon.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.jawon.app.MainActivity
import com.jawon.app.R
import com.jawon.app.data.playback.EqualizerController
import com.jawon.app.data.playback.NativeEffects
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the real ExoPlayer + MediaSession — this is the only place they're
 * constructed. Everything else in the app talks to playback through
 * data/playback/PlaybackRepository.kt's MediaController, never directly.
 *
 * **A library service since 0.7.6**, and exported: a car, the assistant and the system's own
 * media controls all reach a player by binding to it, browse it and ask it to play things by
 * name. What it says to them is [LibraryCallback]; who may ask is [SessionGate].
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    @Inject
    lateinit var shelfDao: com.jawon.app.data.local.ShelfDao

    @Inject
    lateinit var albumDao: com.jawon.app.data.local.AlbumDao

    @Inject
    lateinit var trackDao: com.jawon.app.data.local.TrackDao

    @Inject
    lateinit var queues: com.jawon.app.data.playback.QueueBuilder

    /**
     * The app's own view of the player. Held here only to be *woken*: a record started from a car
     * with the rooms never opened still has to be counted as listened to and have its place
     * remembered, and the repository does both by listening to this session.
     */
    @Inject
    lateinit var repository: com.jawon.app.data.playback.PlaybackRepository

    /** When the headphones going away paused the record; null if anything else did. */
    private var droppedOutAt: Long? = null

    /**
     * Headphones arriving — see [HeadphoneResume] for the one case in which that starts anything.
     * The platform calls this once on registering, with everything already plugged in, which is
     * not an arrival.
     */
    private val outputs = object : android.media.AudioDeviceCallback() {
        private var listed = false

        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
            if (!listed) {
                listed = true
                return
            }
            if (added.none { it.isSink && HeadphoneResume.isHeadphones(it.type) }) return
            val player = mediaSession?.player ?: return
            val resume = HeadphoneResume.resumes(
                optedIn = preferences.resumeWithHeadphones.value,
                hasQueue = player.mediaItemCount > 0,
                playing = player.playWhenReady,
                droppedAtMs = droppedOutAt,
                nowMs = android.os.SystemClock.elapsedRealtime()
            )
            if (resume) player.play()
        }
    }

    /**
     * The equaliser is attached to the player's audio session, and this service is the only
     * place that session exists. The controller itself is app-scoped: the user's settings
     * outlive any one player, and the effect has to be recreated with each new one.
     */
    @Inject
    lateinit var equalizer: EqualizerController

    /** Bass, width, reverb and loudness — the same session, the same lifecycle. */
    @Inject
    lateinit var nativeEffects: NativeEffects

    @Inject
    lateinit var preferences: AppPreferences

    /**
     * The effects rack's controls. App-scoped because a knob outlives the screen that drew it:
     * closing a case must not silently undo a filter sweep. See [RackState].
     */
    @Inject
    lateinit var rack: RackState

    /** Where onPlayerError goes, so a wedged pipeline is readable after the fact. */
    @Inject
    lateinit var crashLog: com.jawon.app.data.diagnostics.CrashLog

    /**
     * The disk streamed audio is read through.
     *
     * App-scoped rather than built here, because `SimpleCache` locks its directory and a second
     * instance over the same folder throws — and this service can be destroyed and recreated
     * while the process lives on. See [StreamCache].
     */
    @Inject
    lateinit var streamCache: StreamCache

    /** The scratch's way to the real player while it lives — see [ScratchLink]. */
    @Inject
    lateinit var scratchLink: ScratchLink

    private var crossfadeEngine: CrossfadeEngine? = null
    private var replayGainReader: ReplayGainReader? = null

    /**
     * Lives as long as the service; routing the player into the reverb tank is the only thing
     * on it.
     *
     * An auxiliary effect cannot be attached the way the others are — it is a shared tank that
     * a *player* sends into, and this service holds the only ExoPlayer. So the effect is built
     * in [NativeEffects] and its id arrives here.
     */
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    override fun onCreate() {
        super.onCreate()
        // Jawon's own effects go here, between the decoder and the sink.
        //
        // The platform's effects need none of this: the equaliser, bass boost, width and loudness
        // attach to the audio *session*, and reverb is routed to as an auxiliary send. A filter is
        // not among them at all, so anything the app invents has to live inside the player's own
        // pipeline — and this is the seam the rest of the rack uses.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ) = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Filter, then drive, then echo. Filter first means the drive is fed whatever the
                // filter left, so the two interact as they do on a real chain; echo last means the
                // repeats are echoes of the *driven* signal rather than clean copies underneath it.
                //
                // These were suspected of stalling the audio pipeline and taken out, and that was
                // wrong: the stall reproduces with this array **empty**, so nothing here causes
                // it. What does is repeatedly crossing the pitch control in and out of neutral,
                // which adds and removes Media3's own time-stretcher from the pipeline — a full
                // reconfiguration each way rather than a change of ratio. See `Varispeed`.
                .setAudioProcessors(
                    arrayOf(
                        // Tone first: it is the source's character, and the filter is what you
                        // sweep afterwards. See ToneAudioProcessor for why it is ours at all —
                        // the platform's bass boost and virtualiser are held device-wide on the
                        // target phone and cannot be instantiated by any app.
                        ToneAudioProcessor(rack),
                        FilterAudioProcessor(rack),
                        DriveAudioProcessor(rack),
                        EchoAudioProcessor(rack)
                    )
                )
                .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            // How every track is read, local or streamed.
            //
            // Local files are unaffected — `DefaultDataSource` still serves `content://`
            // straight off the phone, and the cache never sees them. What this adds is that a
            // track coming from a music server is read through disk, which is what lets the
            // scratch seek instantly and the crossfade tail replay without a second download.
            // See [StreamCache].
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    streamCache.dataSourceFactory()
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // The audio session is not ready when the player is.
        //
        // `audioSessionId` reads 0 straight after `build()` — ExoPlayer does not acquire one
        // until it initialises its audio sink, which happens on the first prepare. Attaching
        // here alone meant the equaliser never attached at all, and the settings screen said
        // the phone did not support one, which was simply untrue.
        //
        // So: try now in case an id already exists, and listen for the real one. The listener
        // is also what covers the session changing later, which the platform is allowed to do.

        // One owner for the main player's volume. The crossfade envelope and ReplayGain's
        // levelling both want that field, and written independently they overwrite each
        // other — see PlayerVolume.
        val volume = PlayerVolume(player)
        val crossfade =
            CrossfadeEngine(this, player, preferences, volume, streamCache.dataSourceFactory())
        crossfadeEngine = crossfade

        // Where the scratch finds the real player: its true position, and a hand on its level.
        scratchLink.attach(player, volume)

        val replayGain = ReplayGainReader(volume, preferences)
        replayGainReader = replayGain
        player.addListener(replayGain)

        player.addListener(object : Player.Listener {
            /**
             * Say what went wrong, loudly.
             *
             * Nothing was listening for this before, which is why a fault in the audio pipeline
             * showed up only as a deck stuck at `0:00` and a phone getting hot: the player
             * reported `ERROR` to its session and the actual throwable went nowhere. Media3 does
             * not log every one of these itself, so an unhandled error in a processor was
             * effectively invisible.
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e(
                    "JawonPlayer",
                    "playback failed: ${error.errorCodeName} (${error.message})",
                    error
                )
                // And to a file, because the line above reaches nobody. On the test hardware
                // logcat is buried by telephony spam (trap 15), and on a tester's phone there
                // is no logcat at all — this is the only route by which a wedged pipeline
                // becomes something the owner can read. The error code name is in the message
                // because it is the part that identifies the failure at a glance.
                crashLog.record("playback:${error.errorCodeName}", error)
            }

            // Remembered only when it was the output going away that paused the record: that
            // is the one pause headphones coming back may undo.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                droppedOutAt = if (!playWhenReady &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                ) {
                    android.os.SystemClock.elapsedRealtime()
                } else {
                    null
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer.attach(audioSessionId)
                nativeEffects.attach(audioSessionId)
            }

            // Handed straight over: only the engine knows which seeks are its own.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) = crossfade.onDiscontinuity(reason)
        })
        equalizer.attach(player.audioSessionId)
        nativeEffects.attach(player.audioSessionId)

        // Point the player at the reverb tank, and adjust how much it sends.
        //
        // `combine` rather than two collectors because both halves describe one routing
        // decision, and setting them separately would briefly send a full level into an effect
        // id of zero. Wrapped, because a released player throws and a reverb send is never worth
        // taking the service down for.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                nativeEffects.auxEffectId,
                nativeEffects.auxSendLevel
            ) { id, level -> id to level }.collect { (id, level) ->
                runCatching { player.setAuxEffectInfo(AuxEffectInfo(id, level)) }
            }
        }

        crossfade.start()

        // Jawon's own mark in the status bar and on the lock screen, in place of Media3's
        // stock music note — which is why the notification read as anonymous next to other
        // players. The app name is supplied by the platform from the package, so the icon is
        // the only half that had to be provided.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        val library = LibraryCallback(
            context = this,
            scope = serviceScope,
            shelfDao = shelfDao,
            albumDao = albumDao,
            trackDao = trackDao,
            queues = queues
        )
        mediaSession = MediaLibrarySession.Builder(this, player, library)
            // Tapping the notification opens the room. Without this the notification is
            // inert — the one thing every other player does that Jawon did not.
            //
            // SINGLE_TOP rather than a fresh task, so returning from the lock screen lands
            // back in the session already running instead of building a second one on top
            // of it.
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        getSystemService(android.media.AudioManager::class.java)
            ?.registerAudioDeviceCallback(outputs, null)

        // After the session exists: connecting is how the repository finds it.
        serviceScope.launch { runCatching { repository.attach() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Before the player goes: the effect is attached to that player's session, and one
        // left behind holds an audio effect slot the next player may need.
        serviceScope.cancel()
        scratchLink.detach()
        getSystemService(android.media.AudioManager::class.java)
            ?.unregisterAudioDeviceCallback(outputs)
        crossfadeEngine?.release()
        crossfadeEngine = null
        equalizer.release()
        nativeEffects.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
