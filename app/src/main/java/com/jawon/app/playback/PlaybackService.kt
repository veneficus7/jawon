package com.jawon.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.jawon.app.MainActivity
import com.jawon.app.R
import com.jawon.app.data.playback.EqualizerController
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the real ExoPlayer + MediaSession — this is the only place they're
 * constructed. Everything else in the app talks to playback through
 * data/playback/PlaybackRepository.kt's MediaController, never directly.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * The equaliser is attached to the player's audio session, and this service is the only
     * place that session exists. The controller itself is app-scoped: the user's settings
     * outlive any one player, and the effect has to be recreated with each new one.
     */
    @Inject
    lateinit var equalizer: EqualizerController

    @Inject
    lateinit var preferences: AppPreferences

    /**
     * The crossfader. Applied to the *player* as well as to the scratch engine, because a
     * transform is exactly the case where the record runs normally under one hand while the
     * other cuts — see [FaderState].
     */
    @Inject
    lateinit var fader: FaderState

    /**
     * The effects rack's controls. App-scoped like the fader, and for the same reason — the knob
     * outlives the screen that drew it. See [RackState].
     */
    @Inject
    lateinit var rack: RackState

    private var crossfadeEngine: CrossfadeEngine? = null
    private var replayGainReader: ReplayGainReader? = null

    /** Lives as long as the service; the fader subscription is the only thing on it. */
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    override fun onCreate() {
        super.onCreate()
        // Jawon's own effects go here, between the decoder and the sink.
        //
        // The platform's effects (equaliser, reverb, compressor) attach to the audio *session* and
        // need none of this. A filter is not among them, so anything the app invents has to live
        // inside the player's own pipeline — and this is the seam the rest of the rack will use.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ) = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Filter first, so the echo's repeats inherit whatever the filter was doing when
                // they were made — see EchoAudioProcessor.
                .setAudioProcessors(arrayOf(FilterAudioProcessor(rack), EchoAudioProcessor(rack)))
                .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
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
        val crossfade = CrossfadeEngine(this, player, preferences, volume)
        crossfadeEngine = crossfade

        val replayGain = ReplayGainReader(volume, preferences)
        replayGainReader = replayGain
        player.addListener(replayGain)

        serviceScope.launch {
            fader.position.collect { volume.setCut(Crossfader.gainFor(it)) }
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer.attach(audioSessionId)
            }

            // Handed straight over: only the engine knows which seeks are its own.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) = crossfade.onDiscontinuity(reason)
        })
        equalizer.attach(player.audioSessionId)
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

        mediaSession = MediaSession.Builder(this, player)
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
        crossfadeEngine?.release()
        crossfadeEngine = null
        equalizer.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
