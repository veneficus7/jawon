package com.jawon.app.playback

import android.net.Uri
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the scratch needs from the real player, and nothing more: where it truly is, whether it is
 * truly running, and a hand on its volume.
 *
 * A scratch is a hand-over, twice. When a finger lands the player is still sounding and the
 * scratch has to take the record from it without a gap or a click; when the finger lifts the
 * scratch winds the record back up and has to give it back in step. Both need the player's *real*
 * position — the one its audio clock gives, to a few thousandths of a second — and a controller
 * across the session can only offer an estimate that runs ahead of a player still starting up,
 * which is exactly the moment that matters here.
 *
 * So the service that owns the player leaves it here while it lives. In the same process, read on
 * the main thread only; like `RackState`, this is the audio pipeline talking to itself, not the
 * rooms reaching into the player.
 */
@Singleton
class ScratchLink @Inject constructor() {

    @Volatile
    private var player: Player? = null

    @Volatile
    private var volume: PlayerVolume? = null

    fun attach(player: Player, volume: PlayerVolume) {
        this.player = player
        this.volume = volume
        volume.setScratch(1f)
    }

    fun detach() {
        player = null
        volume = null
    }

    /** Where the player is, by its own audio clock. Main thread. */
    fun positionMs(): Long? = player?.currentPosition

    /** The file it is on, as it was given to it. Main thread. */
    fun uri(): Uri? = player?.currentMediaItem?.localConfiguration?.uri

    /** Actually producing sound, not merely asked to. Main thread. */
    fun isPlaying(): Boolean = player?.isPlaying == true

    /** Has what it needs to start the instant it is told to. Main thread. */
    fun isReady(): Boolean = player?.playbackState == Player.STATE_READY

    // ---- Moving it -------------------------------------------------------------------------------
    //
    // Straight to the player, not across the session to it. The hand-back is a matter of a few
    // thousandths of a second — the player is parked where the record will be and started as the
    // record arrives — and a command sent the long way round lands a frame or two later, on a main
    // thread that is also drawing a room. Each answers whether there was a player to tell; with
    // none, the caller asks the repository instead, which will start one.

    fun setPlaying(playing: Boolean): Boolean {
        val player = player ?: return false
        runCatching { if (playing) player.play() else player.pause() }
        return true
    }

    fun seek(trackIndex: Int, positionMs: Long): Boolean {
        val player = player ?: return false
        if (trackIndex !in 0 until player.mediaItemCount) return false
        runCatching { player.seekTo(trackIndex, positionMs) }
        return true
    }

    /**
     * How fast the player is running — the deck's tempo. Its key is not asked for: a record in
     * a hand has its speed and its pitch welded together, so with the two set apart on the deck
     * the scratch can keep time with the player or keep its pitch, and it keeps time. Main thread.
     */
    fun speed(): Double = (player?.playbackParameters?.speed ?: 1f).toDouble().coerceIn(0.25, 4.0)

    /** The player's own levelling (ReplayGain), which the scratch plays at too. Main thread. */
    fun levelling(): Float = volume?.levelling ?: 1f

    /** How much of the player is heard while the scratch has, or is handing back, the record. Main thread. */
    fun setLevel(level: Float) {
        volume?.setScratch(level)
    }
}
