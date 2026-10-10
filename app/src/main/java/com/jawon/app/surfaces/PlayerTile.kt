package com.jawon.app.surfaces

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jawon.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * **Play and pause from Quick Settings** (0.7.6): one tile, lit while a record is playing, with
 * the song under its name.
 *
 * A tap pauses or plays. With nothing loaded it puts the last record back on where it stopped,
 * and on a phone nothing has ever been played on, it opens Jawon — a tile that did nothing would
 * be worse than no tile.
 *
 * An *active* tile (the manifest says so): the system does not wake the app every time the shade
 * comes down to ask what the tile should say. `Surfaces` tells it when the answer has changed,
 * which is the only time there is anything new to say.
 */
@AndroidEntryPoint
class PlayerTile : TileService() {

    @Inject
    lateinit var surfaces: Surfaces

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        scope.launch { show(surfaces.state()) }
    }

    override fun onClick() {
        scope.launch {
            if (!surfaces.toggle()) {
                open()
                return@launch
            }
            show(surfaces.state())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(state: SurfaceState) {
        val tile = qsTile ?: return
        tile.state = if (state.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Jawon"
        val saying = if (state.albumId == null) "Open to play a record" else state.title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = saying
        tile.contentDescription = if (state.isPlaying) "Jawon, playing $saying. Pause." else "Jawon. Play $saying."
        tile.updateTile()
    }

    /** Opens the app from the shade — the one-argument form is gone from Android 14. */
    @Suppress("DEPRECATION")
    private fun open() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
