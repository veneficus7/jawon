package com.jawon.app.surfaces

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import com.jawon.app.MainActivity
import com.jawon.app.R
import com.jawon.app.ui.wallscene.CaseArt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * **The record that is on, on the home screen** (0.7.6): its jewel case, the song, and play.
 *
 * A row wide, or — dragged narrow — just the case with a button over its corner. The card leans
 * toward the record's own colour ([SurfaceColors]). Tapping the case opens Jawon; play, pause and
 * next work without opening anything, and play on a phone where Jawon is closed puts the last
 * record back on where it stopped.
 *
 * `RemoteViews` rather than Glance: the widget is two small layouts and a bitmap, and Glance
 * would have added a library to an app that had just spent a release getting 15MB lighter.
 *
 * Not exported. The system reaches it regardless, and its own buttons arrive as pending intents
 * the app made itself; nothing else on the phone should be able to press play.
 */
@AndroidEntryPoint
class RecordWidget : AppWidgetProvider() {

    @Inject
    lateinit var surfaces: Surfaces

    override fun onReceive(context: Context, intent: Intent) {
        // First: Hilt injects in super.
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> surfaces.act(goAsync()) { toggle() }
            ACTION_NEXT -> surfaces.act(goAsync()) { next() }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        surfaces.refresh()
    }

    /** Dragged to a new size: the wide row and the square case are different layouts. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) {
        surfaces.refresh()
    }

    companion object {
        private const val ACTION_TOGGLE = "com.jawon.app.widget.TOGGLE"
        private const val ACTION_NEXT = "com.jawon.app.widget.NEXT"

        /** Narrower than this, in dp, the widget is the case alone. */
        private const val SQUARE_BELOW_DP = 180

        /** The case's bitmap, in pixels: sharp at the widget's largest, well inside a binder transaction. */
        private const val CASE_PX = 288

        // One case kept: the widget is redrawn on every play and pause, and the record changes
        // far less often than that. Guarded by its own lock; drawn off the main thread.
        private val lock = Any()
        private var drawnFor: String? = null
        private var drawnCase: Bitmap? = null
        private var drawnGround: Int = SurfaceColors.NEUTRAL

        fun hasAny(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, RecordWidget::class.java)).isNotEmpty()

        /** Draws [state] onto every copy of the widget. Off the main thread: it decodes a cover. */
        fun push(context: Context, state: SurfaceState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isEmpty()) return

            val (case, ground) = synchronized(lock) {
                val key = "${state.albumId}|${state.coverPath}|${state.spineHue}"
                if (drawnFor != key || drawnCase == null) {
                    val cover = CaseArt.loadCoverSquare(state.coverPath)
                    val hue = state.spineHue.takeIf { it != 0f } ?: cover?.let { CaseArt.dominantHue(it) }
                    drawnGround = SurfaceColors.ground(hue)
                    drawnCase = WidgetArt.case(cover, drawnGround, CASE_PX)
                    drawnFor = key
                }
                drawnCase!! to drawnGround
            }

            for (id in ids) {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val square = width in 1 until SQUARE_BELOW_DP
                val views = RemoteViews(context.packageName, if (square) R.layout.widget_square else R.layout.widget_wide)
                views.setInt(R.id.widget_ground, "setColorFilter", ground)
                views.setImageViewBitmap(R.id.widget_case, case)
                views.setImageViewResource(
                    R.id.widget_toggle,
                    if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                )
                views.setContentDescription(
                    R.id.widget_toggle,
                    context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play)
                )
                views.setOnClickPendingIntent(R.id.widget_toggle, broadcast(context, ACTION_TOGGLE))
                val open = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (square) {
                    views.setOnClickPendingIntent(R.id.widget_case, open)
                    views.setContentDescription(R.id.widget_case, "${state.title}. ${state.line}")
                } else {
                    views.setTextViewText(R.id.widget_title, state.title)
                    views.setTextViewText(R.id.widget_line, state.line)
                    views.setOnClickPendingIntent(R.id.widget_open, open)
                    views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT))
                    // "Next" with nothing loaded has nowhere to go.
                    views.setViewVisibility(
                        R.id.widget_next,
                        if (state.isLoaded) android.view.View.VISIBLE else android.view.View.GONE
                    )
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, RecordWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
