package com.jawon.app.surfaces

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jawon.app.Launch
import com.jawon.app.MainActivity
import com.jawon.app.R
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.ui.wallscene.CaseArt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **What a long press on Jawon's icon offers** (0.7.6): Resume, A record from your shelves, Add
 * music.
 *
 * Published from code rather than declared in the manifest, for two reasons. A manifest shortcut
 * always clears the app's task when it is used — the platform's rule — which here would tear down
 * and rebuild both rooms to press play. And only a shortcut made in code can say *which* record:
 * "Resume Loveless", wearing its cover.
 */
@Singleton
class Shortcuts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val albumDao: AlbumDao
) {

    /** Puts the three shortcuts up, naming the record that was on last. Cheap; safe to repeat. */
    suspend fun publish() = withContext(Dispatchers.IO) {
        val last = albumDao.lastPlayed()
        val any = last != null || albumDao.listAll().isNotEmpty()

        val list = buildList {
            // Nothing to resume until something has been played; nothing to choose from on empty
            // shelves. A shortcut that can only say "nothing yet" is not offered.
            if (last != null) {
                add(
                    shortcut(
                        id = "resume",
                        short = "Resume",
                        long = "Resume ${last.title}".take(LONG_LABEL_MAX),
                        action = Launch.ACTION_RESUME,
                        icon = coverIcon(last.caseCoverUri)
                            ?: IconCompat.createWithResource(context, R.drawable.ic_shortcut_resume),
                        rank = 0
                    )
                )
            }
            if (any) {
                add(
                    shortcut(
                        id = "record",
                        short = "Play a record",
                        long = "A record from your shelves",
                        action = Launch.ACTION_PLAY_SOMETHING,
                        icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_record),
                        rank = 1
                    )
                )
            }
            add(
                shortcut(
                    id = "add",
                    short = "Add music",
                    long = "Add music",
                    action = Launch.ACTION_ADD_MUSIC,
                    icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_add),
                    rank = 2
                )
            )
        }
        // The platform limits how often an app in the background may do this; a refusal is not
        // worth a crash, and the shortcuts already up stay up.
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
    }

    private fun shortcut(id: String, short: String, long: String, action: String, icon: IconCompat, rank: Int) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(icon)
            .setRank(rank)
            // To the activity that is already open if there is one (it is `singleTop`); the action
            // is what says which shortcut this was.
            .setIntent(Intent(context, MainActivity::class.java).setAction(action))
            .build()

    /**
     * The record's cover as an icon. An adaptive icon is masked to the launcher's shape — a circle,
     * on most — so the cover is drawn into the middle two thirds of a larger square and what the
     * mask shows is the cover, round: a record's label.
     */
    private fun coverIcon(path: String?): IconCompat? {
        val cover = CaseArt.loadCoverSquare(path) ?: return null
        val size = ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF1E1713.toInt())
        val side = minOf(cover.width, cover.height)
        val from = Rect(
            (cover.width - side) / 2,
            (cover.height - side) / 2,
            (cover.width + side) / 2,
            (cover.height + side) / 2
        )
        // The safe zone is the middle 72dp of 108: drawn a little past it, so the mask cuts cover
        // and never the ground behind it.
        val inset = size * (1f - SAFE_ZONE) / 2f
        canvas.drawBitmap(
            cover,
            from,
            RectF(inset, inset, size - inset, size - inset),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    private companion object {
        const val ICON_PX = 324
        const val SAFE_ZONE = 0.72f
        const val LONG_LABEL_MAX = 25
    }
}
