package com.jawon.app.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jawon.app.data.prefs.AppPreferences

/**
 * Overlaps the end of one track with the start of the next.
 *
 * **The session's player never changes, and that is the whole design.** Media3 gives a
 * `MediaSession` one `Player`, and everything in this app reads playback through it: the
 * notification, the deck's track index and position, the disc scrub, the equaliser's audio
 * session. Swapping which player the session owns — the obvious way to build a crossfade —
 * would put all of that in the blast radius of a feature nobody has asked to be load-bearing.
 *
 * So the main player keeps the entire album queued and stays in the session, untouched. All
 * this adds is a second, **write-only** player that plays the outgoing few seconds and is
 * never read by anything. At the transition the main player jumps early to the next track and
 * fades up, while the tail player carries the end of the previous one and fades down. What the
 * session reports is the incoming track, from the moment it starts becoming audible — which is
 * what the notification should say anyway.
 *
 * The tail player does **not** request audio focus. It is the same app playing the same
 * music, and a second focus request during a two-second overlap is how you get the platform
 * to duck or pause your own playback.
 *
 * **Known limit:** the equaliser is attached to the main player's audio session, so the
 * outgoing tail is not equalised for the length of the fade. Attaching a second effect to a
 * player that exists for two seconds at a time costs more than the inconsistency does.
 */
class CrossfadeEngine(
    private val context: Context,
    private val main: ExoPlayer,
    private val preferences: AppPreferences,
    /**
     * The main player's volume, shared with ReplayGain. The fade sets its own factor here
     * rather than writing `main.volume`, so levelling survives a fade and a fade lands on top
     * of the level the record asked for — see [PlayerVolume].
     */
    private val mainVolume: PlayerVolume,
    /**
     * The same source chain the main player reads through — crucially, the same cache.
     *
     * Left to its own devices the tail player would build a default factory and fetch the
     * outgoing track's last seconds over the network **again**, having just streamed them. On a
     * phone that is streaming precisely because it has no room to store music, paying twice for
     * every transition is the wrong bargain. Sharing the factory means the tail is served from
     * the cache the main player just filled.
     */
    private val dataSourceFactory: androidx.media3.datasource.DataSource.Factory
) {

    private val handler = Handler(Looper.getMainLooper())
    private var tail: ExoPlayer? = null

    /** Prepared and waiting for the transition, so the fade does not open with a decoder stall. */
    private var armedForIndex: Int = NO_INDEX

    /** The fade in progress, if any. */
    private var running: Running? = null

    /**
     * Set for exactly one seek: the engine's own jump to the next track.
     *
     * Beginning a fade means seeking the main player, and a seek is a position discontinuity
     * like any other — so without this the engine cancels itself on the very frame it starts,
     * and the crossfade is a hard cut with extra steps.
     */
    private var ignoreOneSeek = false

    /**
     * Report a position discontinuity from the player.
     *
     * The engine decides rather than the caller, because only the engine knows which seeks
     * are its own. Anything the *user* did — a skip, a seek, the disc being scrubbed — means
     * the transition being faded toward is not the one about to happen, and a fade left
     * running through that strands the main player at part volume with no way back.
     */
    fun onDiscontinuity(reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
        if (ignoreOneSeek && reason == Player.DISCONTINUITY_REASON_SEEK) {
            ignoreOneSeek = false
            return
        }
        cancel()
    }

    private class Running(
        val startedAtUptimeMs: Long,
        val durationMs: Int,
        val tail: ExoPlayer
    )

    private val ticker = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun release() {
        handler.removeCallbacks(ticker)
        cancel()
        tail?.release()
        tail = null
    }

    /**
     * Abandon any fade in progress and put the main player back to full volume.
     *
     * Called whenever the user does something that makes the transition meaningless — a skip,
     * a seek, a scrub of the disc, or a pause. Leaving a fade running through one of those is
     * how a player ends up stuck at half volume with no way back.
     */
    fun cancel() {
        running?.tail?.let { runCatching { it.stop() } }
        running = null
        armedForIndex = NO_INDEX
        mainVolume.clearFade()
    }

    private fun step() {
        val fadeMs = preferences.crossfadeMs.value
        if (fadeMs < Crossfade.MIN_MS) {
            if (running != null) cancel()
            return
        }

        running?.let { active ->
            advance(active)
            return
        }

        if (!main.isPlaying) {
            // Paused mid-approach: un-arm, because the position it was armed against is no
            // longer where playback will resume from if the user seeks while paused.
            if (armedForIndex != NO_INDEX) armedForIndex = NO_INDEX
            return
        }

        val index = main.currentMediaItemIndex
        val duration = main.duration
        val position = main.currentPosition
        val hasNext = index + 1 < main.mediaItemCount
        if (duration <= 0L) return

        if (armedForIndex != index &&
            Crossfade.shouldArm(position, duration, fadeMs, hasNext)
        ) {
            arm(index)
        }

        if (armedForIndex == index &&
            Crossfade.shouldStart(position, duration, fadeMs, hasNext)
        ) {
            begin(index, duration, fadeMs)
        }
    }

    /**
     * Get the tail player holding the *current* track, prepared and paused at the point the
     * fade will begin, so starting it is instant.
     */
    private fun arm(index: Int) {
        val item = runCatching { main.getMediaItemAt(index) }.getOrNull() ?: return
        val duration = main.duration
        val fadeMs = preferences.crossfadeMs.value
        val startAt = Crossfade.startAtMs(duration, fadeMs)

        val player = tail ?: buildTailPlayer().also { tail = it }
        runCatching {
            player.volume = 0f
            player.setMediaItem(item)
            player.prepare()
            player.seekTo(startAt)
            player.playWhenReady = false
        }.onFailure {
            Log.w(TAG, "could not arm crossfade: ${it.message}")
            return
        }
        armedForIndex = index
    }

    private fun begin(index: Int, durationMs: Long, fadeMs: Int) {
        val player = tail ?: return
        val effective = Crossfade.effectiveMs(durationMs, fadeMs)
        if (effective == 0) return

        runCatching {
            // The tail takes over the end of this track at full volume...
            player.volume = 1f
            player.play()
            // ...and the main player jumps to the next one, silent, to come up under it.
            mainVolume.setFade(0f)
            ignoreOneSeek = true
            main.seekTo(index + 1, 0L)
        }.onFailure {
            Log.w(TAG, "could not begin crossfade: ${it.message}")
            cancel()
            return
        }

        armedForIndex = NO_INDEX
        running = Running(
            startedAtUptimeMs = android.os.SystemClock.uptimeMillis(),
            durationMs = effective,
            tail = player
        )
    }

    private fun advance(active: Running) {
        val elapsed = android.os.SystemClock.uptimeMillis() - active.startedAtUptimeMs
        val progress = (elapsed.toFloat() / active.durationMs).coerceIn(0f, 1f)

        runCatching {
            active.tail.volume = Crossfade.gainOut(progress)
            mainVolume.setFade(Crossfade.gainIn(progress))
        }

        if (progress >= 1f) {
            runCatching {
                active.tail.stop()
                mainVolume.clearFade()
            }
            running = null
        }
    }

    private fun buildTailPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // Explicitly NOT handling focus. This is the same app playing the same album
                // — a second focus request during the overlap would have the platform duck or
                // pause Jawon's own playback against itself.
                /* handleAudioFocus = */ false
            )
            .build()
            .apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // A tail that fails is a fade that does not happen, not a playback
                        // failure: the main player is already on the next track.
                        Log.w(TAG, "tail player error: ${error.message}")
                        cancel()
                    }
                })
            }

    private companion object {
        const val TAG = "CrossfadeEngine"
        const val NO_INDEX = -1

        /**
         * How often the approach to a transition is checked.
         *
         * 100ms while a fade is running is what makes the volume ramp smooth — the same tick
         * drives both the arming check and the gain updates, and a coarser one is audible as
         * stepping. It only runs while the service is alive.
         */
        const val TICK_MS = 100L
    }
}
