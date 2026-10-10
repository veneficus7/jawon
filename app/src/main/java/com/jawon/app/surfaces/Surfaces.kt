package com.jawon.app.surfaces

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.playback.PlaybackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the places outside the app in step with the player (0.7.6): the home-screen widget, the
 * Quick Settings tile and the shortcuts held out of the icon.
 *
 * One listener for all three, started with the process — which is not always started by a room:
 * a tap on the widget, a tile, a button on a headset each start it too, and each must leave the
 * others showing the truth.
 */
@Singleton
class Surfaces @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: PlaybackRepository,
    private val albumDao: AlbumDao,
    private val shortcuts: Shortcuts
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called once, from `JawonApplication`. */
    fun start() {
        scope.launch { shortcuts.publish() }
        scope.launch {
            // Latest only: a burst of player events is one redraw of what it ended as.
            repository.onNow.collectLatest { draw(it) }
        }
        scope.launch {
            // The shortcut names the record that was on; it changes when the record does.
            repository.onNow.map { it?.album?.id }.distinctUntilChanged().drop(1).collectLatest {
                if (it != null) shortcuts.publish()
            }
        }
    }

    /** What to show now: the player's word if it has one, the last record played if not. */
    suspend fun state(): SurfaceState {
        val now = repository.onNow.value
        return SurfaceState.of(now, if (now == null) albumDao.lastPlayed() else null)
    }

    /** Redraws everything from what is true now — the system asking a widget to draw itself. */
    fun refresh() {
        scope.launch { draw(repository.onNow.value) }
    }

    private suspend fun draw(now: PlaybackRepository.OnNow?) {
        val state = SurfaceState.of(now, if (now == null) albumDao.lastPlayed() else null)
        runCatching { RecordWidget.push(context, state) }
        // An "active" tile is not polled by the system; it is told when to ask.
        runCatching { TileService.requestListeningState(context, ComponentName(context, PlayerTile::class.java)) }
    }

    /**
     * Does something to the player on behalf of a broadcast — the widget's buttons — and tells the
     * system when it is done. Bounded: a receiver has ten seconds, and a player that cannot start
     * in a few should not be waited for.
     */
    fun act(pending: BroadcastReceiver.PendingResult, what: suspend Surfaces.() -> Unit) {
        scope.launch {
            try {
                withTimeoutOrNull(ACT_TIMEOUT_MS) { what() }
            } finally {
                pending.finish()
            }
        }
    }

    /** Play if paused, pause if playing; with nothing loaded, the last record back on. */
    suspend fun toggle(): Boolean = runCatching { repository.toggle() }.getOrDefault(false)

    suspend fun next() {
        runCatching { repository.seekToNext() }
    }

    private companion object {
        const val ACT_TIMEOUT_MS = 8_000L
    }
}
