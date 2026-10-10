package com.jawon.app.surfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * The record on the widget, drawn as the thing itself: a small jewel case.
 *
 * A cover on its own is what every player's widget shows, and it is a picture of an album. The
 * room's whole idea is that a record is an *object* — it stands in a case, with a hinge down its
 * left edge and light on its lid — so the widget keeps that: the cover behind a lid, the smoky
 * spine strip with its two hinge tabs, a soft diagonal of light across the plastic.
 *
 * Drawn into a bitmap because a home screen can show nothing else: a widget is a handful of
 * stock views in another app's process, and this is one `ImageView` among them.
 */
object WidgetArt {

    /** The case is square, as a jewel case all but is. */
    fun case(cover: Bitmap?, ground: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = sizePx.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val corner = s * 0.035f
        val body = RectF(0f, 0f, s, s)

        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(body, corner, corner, Path.Direction.CW) })

        // The tray: what shows at the edges.
        canvas.drawColor(TRAY)

        // The cover, behind the lid: everything right of the spine strip.
        val spine = s * SPINE
        val inset = s * 0.018f
        val art = RectF(spine, inset, s - inset, s - inset)
        if (cover != null) {
            // Centre-cropped to the opening, which is a little narrower than it is tall.
            val want = art.width() / art.height()
            val have = cover.width.toFloat() / cover.height
            val from = if (have > want) {
                val w = (cover.height * want).toInt()
                val x = (cover.width - w) / 2
                Rect(x, 0, x + w, cover.height)
            } else {
                val h = (cover.width / want).toInt()
                val y = (cover.height - h) / 2
                Rect(0, y, cover.width, y + h)
            }
            canvas.drawBitmap(cover, from, art, paint)
        } else {
            // No cover: the record's colour as a plain sleeve, with a disc's rim and hole on it.
            paint.color = lighten(ground, 0.10f)
            canvas.drawRect(art, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.022f
            paint.color = AMBER
            canvas.drawCircle(art.centerX(), art.centerY(), art.width() * 0.26f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(art.centerX(), art.centerY(), art.width() * 0.045f, paint)
        }

        // The spine strip: smoked plastic, lighter down its middle, with the two tabs a real
        // case has where the lid pivots, and the shadow the lid's edge throws on it.
        paint.shader = LinearGradient(
            0f, 0f, spine, 0f,
            intArrayOf(0xFF0B0908.toInt(), 0xFF1F1A16.toInt(), 0xFF120F0D.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, spine, s, paint)
        paint.shader = null
        paint.color = 0x73000000
        canvas.drawRect(spine - s * 0.008f, 0f, spine, s, paint)
        paint.color = 0x30FFFFFF
        for (y in TABS) {
            canvas.drawRoundRect(
                RectF(spine * 0.26f, s * (y - 0.036f), spine * 0.70f, s * (y + 0.036f)),
                s * 0.01f, s * 0.01f, paint
            )
        }

        // Light on the lid: one soft diagonal, brightest at the top-left, gone by the middle.
        paint.shader = LinearGradient(
            spine, 0f, s * 0.52f, s * 0.36f,
            0x33FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(
            Path().apply {
                moveTo(spine, 0f)
                lineTo(s * 0.80f, 0f)
                lineTo(spine, s * 0.64f)
                close()
            },
            paint
        )
        paint.shader = null
        canvas.restore()

        // The case's own edge, catching a line of light.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (s * 0.008f).coerceAtLeast(1f)
        paint.color = 0x38FFFFFF
        val half = paint.strokeWidth / 2f
        canvas.drawRoundRect(RectF(half, half, s - half, s - half), corner, corner, paint)
        return bitmap
    }

    /** How much of the case's width the spine strip takes: a real one is about a ninth. */
    private const val SPINE = 0.115f

    /** Where the hinge tabs sit, down the strip. */
    private val TABS = floatArrayOf(0.14f, 0.86f)

    private const val TRAY = 0xFF0E0B09.toInt()
    private const val AMBER = 0xFFFFB43C.toInt()

    private fun lighten(color: Int, by: Float): Int {
        fun up(c: Int) = (c + (255 - c) * by).toInt().coerceIn(0, 255)
        return Color.rgb(up(Color.red(color)), up(Color.green(color)), up(Color.blue(color)))
    }
}
