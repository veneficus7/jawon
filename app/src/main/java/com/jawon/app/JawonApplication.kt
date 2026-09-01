package com.jawon.app

import android.app.Application
import com.jawon.app.data.importer.TrackArtistBackfill
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

    /** Lives as long as the process, which is the honest lifetime for a one-shot at launch. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Off the main thread and unawaited: this opens every file in the library, and
        // ENGINEERING.md rule 5 has no exception for "only on the first launch after an update".
        scope.launch { trackArtistBackfill.runOnce() }
    }
}
