package com.jawon.app.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.jawon.app.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The disk the streamed audio lands on, so the room stays a room and not a buffering spinner.
 *
 * ## Why this is not optional
 *
 * Jawon's signature interaction is grabbing the spinning disc and turning it, and a scrub is a
 * burst of seeks — dozens a second while a thumb is moving. Against a bare `http://` source
 * every one of those is a byte-range request across the internet, and the platter becomes the
 * laggiest thing in an app built around it feeling immediate. Crossfade has the same problem
 * from a different direction: `CrossfadeEngine` runs a second, write-only player for the
 * outgoing tail, which over a network is a second concurrent download of audio that was already
 * fetched once.
 *
 * With a cache in front, both problems disappear the moment a track has been heard once — the
 * seek and the tail are served off local storage at local storage speed. This is the component
 * that makes streaming compatible with the rest of the app rather than merely possible.
 *
 * ## Only the network is cached
 *
 * The factory is assembled deliberately: [DefaultDataSource] handles `content://` and `file://`
 * itself and is given the **cached** factory only for what it cannot — which is http and https.
 * Wrapping the whole chain instead would have the app dutifully making a second copy, in its own
 * cache, of files already sitting on the phone. On a device short enough of space to want
 * streaming in the first place, that is precisely the wrong failure.
 *
 * ## One instance, for the life of the process
 *
 * `SimpleCache` takes an exclusive lock on its directory and **throws** if a second instance is
 * built over the same folder while the first is alive. Singleton-scoped and held in a `by lazy`
 * for that reason, not for the allocation.
 *
 * ## The size is read once
 *
 * `LeastRecentlyUsedCacheEvictor` fixes its ceiling when it is constructed, so changing the
 * setting takes effect on the next launch rather than immediately. Rebuilding the cache under a
 * player that is mid-track to avoid that would risk a great deal for a setting nobody moves
 * twice; the settings screen says so instead.
 */
@UnstableApi
@Singleton
class StreamCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {

    /**
     * In `cacheDir`, not `filesDir`.
     *
     * Everything here is re-fetchable from the server, so the platform is welcome to delete it
     * when storage runs short — which is exactly the behaviour wanted from a phone that is
     * already tight on space. Putting it in `filesDir` would tell Android this data is
     * irreplaceable, which would be a lie, and would count against the app in the storage
     * screen for no benefit.
     */
    private val directory: File by lazy {
        File(context.cacheDir, "streams").apply { mkdirs() }
    }

    val cache: Cache by lazy {
        SimpleCache(
            directory,
            LeastRecentlyUsedCacheEvictor(preferences.streamCacheMb.value * BYTES_PER_MB),
            // Media3 keeps the cache index in SQLite. Standalone rather than the app's own Room
            // database: this is disposable scratch data with its own lifetime, and putting it in
            // `javon.db` would drag cache bookkeeping into the library's migrations and its
            // backups.
            StandaloneDatabaseProvider(context)
        )
    }

    /**
     * The factory the player reads everything through — local files direct, network via cache.
     */
    fun dataSourceFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            // A music server behind a reverse proxy very often answers on http and redirects to
            // https, or the other way round on a home LAN. Refusing to follow that is a
            // "connection failed" for a server that is working perfectly.
            .setAllowCrossProtocolRedirects(true)

        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http)
            // Without this, a network blip while writing the cache surfaces as a playback error
            // rather than as what it is — a cache miss. Playback continues from upstream and the
            // cache simply does not get that block.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultDataSource.Factory(context, cached)
    }

    /** How much of the allowance is currently spent, for the settings screen to report. */
    fun usedBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /**
     * Throws the cached audio away.
     *
     * Deliberately does not touch the library: a streamed album is a row plus a cover, and both
     * survive this. All that is lost is the head start on playing it again.
     */
    fun clear() {
        runCatching {
            cache.keys.toList().forEach { key ->
                cache.removeResource(key)
            }
        }
    }

    /**
     * Releases the directory lock.
     *
     * **Nothing calls this, and that is deliberate rather than an omission.** The obvious home
     * for it is `PlaybackService.onDestroy`, and that would be a bug: the service is destroyed
     * and recreated within a living process — stop playback, open a case again — while this
     * object is a singleton holding the cache in a `by lazy`. Released once, it can never be
     * rebuilt, so the second playback of the session would fail on a closed cache.
     *
     * The lock is held by the process and goes when the process does, which is the correct
     * lifetime. Kept as a named function so the next person to reach for it finds this note
     * instead of wiring it up.
     */
    fun release() {
        runCatching { cache.release() }
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val USER_AGENT = "Jawon/1.0"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
