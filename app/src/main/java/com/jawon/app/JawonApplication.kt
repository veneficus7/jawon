package com.jawon.app

import android.app.Application
import com.jawon.app.data.diagnostics.CrashLog
import com.jawon.app.data.importer.TrackArtistBackfill
import com.jawon.app.data.local.SpotifyCleanup
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class JawonApplication : Application() {

    /**
     * A one-time pass over files imported before tracks kept their own artist. Injected here
     * rather than into a screen because it belongs to no screen: it should happen once after
     * the update whether the user opens the room, the shelf list or Settings first.
     */
    @Inject
    lateinit var trackArtistBackfill: TrackArtistBackfill

    /**
     * The uncaught-exception handler, installed before anything else runs.
     *
     * Injected rather than constructed here so the same instance serves the non-fatal path —
     * `PlaybackService` records `onPlayerError` through it.
     */
    @Inject
    lateinit var crashLog: CrashLog

    /** Takes the removed Spotify route's albums and sign-in off the phone. See its KDoc. */
    @Inject
    lateinit var spotifyCleanup: SpotifyCleanup

    /** The widget, the tile and the shortcuts, kept in step with the player. See its KDoc. */
    @Inject
    lateinit var surfaces: com.jawon.app.surfaces.Surfaces

    /** Lives as long as the process, which is the honest lifetime for a one-shot at launch. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // First, and deliberately before the backfill below: the backfill opens every file in
        // the library, which makes it one of the more plausible things in here to throw on a
        // stranger's phone. Installing after it would miss exactly the crash worth catching.
        crashLog.install()

        // Off the main thread and unawaited: this opens every file in the library, and
        // ENGINEERING.md rule 5 has no exception for "only on the first launch after an update".
        scope.launch { trackArtistBackfill.runOnce() }

        // Every launch rather than once, unlike the backfill: a backup restored later can bring
        // Spotify albums back. Finds nothing, cheaply, on a phone that never had them.
        scope.launch { spotifyCleanup.run() }

        // Here rather than in a room: the process is not always started by one. A tap on the
        // widget or the tile starts it too, and each has to leave the others showing the truth.
        surfaces.start()
    }
}
