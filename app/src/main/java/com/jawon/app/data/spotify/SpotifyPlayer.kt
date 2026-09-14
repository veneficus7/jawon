package com.jawon.app.data.spotify

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The remote control half: telling Spotify what to play, and asking what it is doing.
 *
 * ## This is a control surface, not a player
 *
 * Nothing here touches audio. Spotify decodes and outputs in its own process, and this sends it
 * instructions over HTTPS. Everything the room does *to* sound — the equaliser, the filter, the
 * drive, the echo, and the scratch — has no effect on a Spotify album, because the samples are
 * never in this app's address space. The disc still turns, and [seek] is the most it can mean.
 *
 * ## The active-device problem, and what is done about it
 *
 * The Web API plays to a *device*, and it cannot create one. If the Spotify app is not running,
 * every playback call returns 404 and the correct reading is "there is nowhere to play", not
 * "something broke". [ensureDevice] does what can be done from here: list the devices, pick the
 * phone's own Spotify if it is there, and transfer playback to it. When the list is empty the
 * only remedy is a human opening Spotify once, and [SpotifyFailure.NoDevice] says exactly that.
 *
 * This is the standing cost of the Spotify route and the reason the app-remote SDK exists. It is
 * accepted deliberately — see `SpotifyClient` for why that SDK is not used.
 */
@Singleton
class SpotifyPlayer @Inject constructor(
    private val client: SpotifyClient,
    private val session: SpotifySession
) {

    /**
     * Starts an album at a track and offset.
     *
     * Resolves a device first, because starting playback with none present fails in a way that
     * reads like a bug rather than like "open Spotify".
     */
    suspend fun playAlbum(
        albumUri: String,
        trackIndex: Int = 0,
        positionMs: Long = 0L
    ): SpotifyResult<Unit> {
        val token = session.token() ?: return SpotifyResult.Failed(SpotifyFailure.NeedsLogin)
        val device = when (val d = ensureDevice(token)) {
            is SpotifyResult.Failed -> return d
            is SpotifyResult.Ok -> d.value
        }
        val played = client.play(token, albumUri, trackIndex, positionMs, device)
        // A device that has gone away answers 404, and the id we were holding is the reason.
        // Drop it and let the next attempt look again rather than failing the same way twice.
        if (played is SpotifyResult.Failed) forgetDevice()
        return played
    }

    /**
     * The device [ensureDevice] last settled on, and when.
     *
     * Resolving a device is one request to list them and sometimes a second to transfer
     * playback, and it happened on **every** `playAlbum`. That is two thirds of the cost of
     * starting a record, paid again every time — and once the scratch was routed to Spotify it
     * was being paid several times a second, which is a rate limit rather than a cost. The
     * phone's own Spotify does not move between one track and the next, so re-asking is
     * mostly asking a question whose answer is already known.
     *
     * [DEVICE_TTL_MS] rather than forever, because a device genuinely can change — headphones,
     * a speaker, another phone — and [forgetDevice] on any failed play, because the one thing
     * worse than re-asking is confidently addressing a device that is no longer there.
     */
    private var cachedDeviceId: String? = null
    private var cachedDeviceAt = 0L

    private fun forgetDevice() {
        cachedDeviceId = null
        cachedDeviceAt = 0L
    }

    suspend fun pause(): SpotifyResult<Unit> = withToken { client.pause(it) }

    suspend fun resume(): SpotifyResult<Unit> = withToken { client.resume(it) }

    suspend fun next(): SpotifyResult<Unit> = withToken { client.next(it) }

    suspend fun previous(): SpotifyResult<Unit> = withToken { client.previous(it) }

    suspend fun seek(positionMs: Long): SpotifyResult<Unit> =
        withToken { client.seek(it, positionMs.coerceAtLeast(0L)) }

    /** What Spotify is doing, or null when it is idle. */
    suspend fun playback(): SpotifyResult<SpotifyPlayback?> = withToken { client.playback(it) }

    /** Whether the account can play at all. Worth asking once, before anything is attempted. */
    suspend fun profile(): SpotifyResult<SpotifyProfile> = withToken { client.profile(it) }

    /**
     * Picks somewhere to play and makes it current.
     *
     * Prefers a device that is already active — transferring away from whatever the user is
     * listening on right now would be rude and surprising. Otherwise it takes the first device
     * that will accept commands. `is_restricted` devices are skipped because they report
     * themselves but refuse control, which is the most confusing possible failure: a device is
     * listed, chosen, and then silently does nothing.
     */
    private suspend fun ensureDevice(token: String): SpotifyResult<String?> {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedDeviceId?.let { if (now - cachedDeviceAt < DEVICE_TTL_MS) return SpotifyResult.Ok(it) }

        val devices = when (val d = client.devices(token)) {
            is SpotifyResult.Failed -> return d
            is SpotifyResult.Ok -> d.value
        }
        val usable = devices.filter { !it.isRestricted && it.id != null }
        if (usable.isEmpty()) return SpotifyResult.Failed(SpotifyFailure.NoDevice)

        usable.firstOrNull { it.isActive }?.let {
            cachedDeviceId = it.id
            cachedDeviceAt = now
            return SpotifyResult.Ok(it.id)
        }

        val target = usable.first()
        return when (val transfer = client.transferTo(token, target.id!!)) {
            is SpotifyResult.Failed -> transfer
            is SpotifyResult.Ok -> {
                cachedDeviceId = target.id
                cachedDeviceAt = now
                SpotifyResult.Ok(target.id)
            }
        }
    }

    private suspend fun <T> withToken(block: suspend (String) -> SpotifyResult<T>): SpotifyResult<T> {
        val token = session.token() ?: return SpotifyResult.Failed(SpotifyFailure.NeedsLogin)
        return block(token)
    }

    companion object {
        /**
         * How often to ask Spotify what it is doing, in milliseconds.
         *
         * One second. Spotify's rate limit is computed over a rolling thirty-second window, and
         * a poll per second is a small share of it — but it is not free, and it is why this
         * polls only while a Spotify album is actually open rather than for the life of the app.
         * The local player needs none of this: it reports its own position for nothing.
         */
        const val POLL_INTERVAL_MS = 1_000L

        /** How long a resolved device is trusted before it is looked up again. */
        const val DEVICE_TTL_MS = 60_000L
    }
}
