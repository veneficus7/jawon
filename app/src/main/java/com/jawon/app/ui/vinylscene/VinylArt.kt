package com.jawon.app.ui.vinylscene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.jawon.app.R

/**
 * Where everything on the back of a sleeve goes — the one piece of this room's printing that is
 * touched as well as read.
 *
 * The back of the sleeve *is* the tracklist (`docs/formats.md` §5): a line is touched and the
 * arm goes to that band. So where a line is drawn and where a finger finds it have to be the
 * same arithmetic, and this is it, pure: [VinylArt.sleeveBack] draws by it and the room hits by it.
 *
 * Everything is a fraction of the sleeve, from its top left.
 */
object SleeveBackLayout {

    class Heading(val side: Int, val left: Float, val top: Float)

    class Row(val trackIndex: Int, val left: Float, val right: Float, val top: Float, val bottom: Float)

    class Layout(val headings: List<Heading>, val rows: List<Row>, val rowHeight: Float) {
        /** The track whose line is under ([u], [v]), or −1. */
        fun trackAt(u: Float, v: Float): Int =
            rows.firstOrNull { u >= it.left && u <= it.right && v >= it.top && v < it.bottom }?.trackIndex ?: -1
    }

    const val MARGIN = 0.082f
    const val LIST_TOP = 0.245f
    const val LIST_BOTTOM = 0.800f
    const val HEADING_HEIGHT = 0.048f
    const val SIDE_GAP = 0.030f
    const val ROW_TALLEST = 0.064f
    const val ROW_SHORTEST = 0.034f
    private const val COLUMN_GAP = 0.05f

    /**
     * One column while the lines stay a comfortable height, two when a long record would make
     * them too small to put a finger on: the first half of its sides on the left, the rest on
     * the right.
     */
    fun of(sides: List<Side>): Layout {
        if (sides.isEmpty()) return Layout(emptyList(), emptyList(), ROW_TALLEST)
        fun heightFor(group: List<Side>): Float {
            val fixed = group.size * HEADING_HEIGHT + (group.size - 1) * SIDE_GAP
            return ((LIST_BOTTOM - LIST_TOP - fixed) / group.sumOf { it.trackDurationsMs.size }).coerceAtMost(ROW_TALLEST)
        }
        val single = heightFor(sides)
        val columns = if (single >= ROW_SHORTEST || sides.size < 2) listOf(sides) else {
            val half = (sides.size + 1) / 2
            listOf(sides.take(half), sides.drop(half))
        }
        // Never floored: a line that is too small is small print, as a three-record box's is; a
        // line held to a minimum runs off the bottom of the sleeve, which the first version did.
        val rowHeight = columns.minOf(::heightFor)
        val width = (1f - 2 * MARGIN - (columns.size - 1) * COLUMN_GAP) / columns.size
        val headings = ArrayList<Heading>()
        val rows = ArrayList<Row>()
        var side = 0
        for ((c, group) in columns.withIndex()) {
            val left = MARGIN + c * (width + COLUMN_GAP)
            var y = LIST_TOP
            for (s in group) {
                headings += Heading(side++, left, y)
                y += HEADING_HEIGHT
                for (i in s.trackDurationsMs.indices) {
                    rows += Row(s.firstTrack + i, left, left + width, y, y + rowHeight)
                    y += rowHeight
                }
                y += SIDE_GAP
            }
        }
        return Layout(headings, rows, rowHeight)
    }
}

/**
 * What is printed in this room: a record's two labels, the grooves of each side, and its sleeve.
 *
 * Baked into bitmaps on a worker and uploaded once per record — nothing here is drawn per frame.
 */
object VinylArt {

    const val LABEL_PX = 512
    const val SLEEVE_PX = 1024
    const val BAND_PX = 1024

    /** A sleeve standing in the crate among twenty others: all that is seen of most of them is a top edge. */
    const val CRATE_PX = 256

    /** The paper label on a divider's tab: four to one, as the model maps it. */
    const val LABEL_W = 512
    const val LABEL_H = 128

    private val cream = Color.rgb(236, 228, 212)
    private val dim = Color.rgb(176, 168, 152)
    private val amber = Color.rgb(240, 170, 90)

    /**
     * A side's label: the cover, as a great many labels are, under the side's letter and what a
     * label always says.
     */
    fun label(context: Context, cover: Bitmap?, letter: String, title: String, artist: String): Bitmap {
        val px = LABEL_PX
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(28, 24, 22))
        if (cover != null) {
            // Darkened a little: paper under a lamp, not a screen.
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 225 }
            canvas.drawBitmap(cover, centreSquare(cover), Rect(0, 0, px, px), paint)
        }
        val mono = font(context, R.font.jetbrains_mono_variable)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; color = cream }
        // The letter, in a disc of its own to the left of the spindle; the speed, to the right.
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 16, 14, 13) }
        canvas.drawCircle(px * 0.24f, px * 0.5f, px * 0.075f, plate)
        text.textSize = px * 0.092f
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(letter, px * 0.24f, px * 0.5f + text.textSize * 0.36f, text)
        canvas.drawRoundRect(RectF(px * 0.66f, px * 0.465f, px * 0.86f, px * 0.535f), px * 0.02f, px * 0.02f, plate)
        text.textSize = px * 0.040f
        canvas.drawText("33⅓ RPM", px * 0.76f, px * 0.5f + text.textSize * 0.36f, text)
        // Round the edge, as type on a label runs: the record's name and whose it is.
        val arc = Path().apply { addArc(RectF(px * 0.09f, px * 0.09f, px * 0.91f, px * 0.91f), 200f, 140f) }
        text.textSize = px * 0.042f
        text.letterSpacing = 0.12f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 16, 14, 13); style = Paint.Style.STROKE; strokeWidth = px * 0.075f }
        canvas.drawArc(RectF(px * 0.09f, px * 0.09f, px * 0.91f, px * 0.91f), 198f, 144f, false, ring)
        val line = TextUtils.ellipsize("${title.uppercase()} · ${artist.uppercase()}", text, px * 0.95f, TextUtils.TruncateAt.END).toString()
        canvas.drawTextOnPath(line, arc, 0f, text.textSize * 0.36f, text)
        return out
    }

    /** A side's grooves, as one line of roughness — see [VinylBands]. Packed as glTF packs it: roughness in green. */
    fun bands(side: Side?): Bitmap {
        val rough = VinylBands.roughness(side, BAND_PX)
        val row = IntArray(BAND_PX) { Color.argb(255, 255, (rough[it] * 255f).toInt().coerceIn(0, 255), 0) }
        val pixels = IntArray(BAND_PX * 4) { row[it % BAND_PX] }
        return Bitmap.createBitmap(pixels, BAND_PX, 4, Bitmap.Config.ARGB_8888)
    }

    /** The front of the sleeve: the cover, or the record's name where it has none. */
    fun sleeveFront(context: Context, cover: Bitmap?, title: String, artist: String, px: Int = SLEEVE_PX): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (cover != null) {
            canvas.drawBitmap(cover, centreSquare(cover), Rect(0, 0, px, px), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
        canvas.drawColor(Color.rgb(38, 32, 28))
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font(context, R.font.archivo_variable); color = cream; textSize = px * 0.085f }
        canvas.drawText(TextUtils.ellipsize(title.uppercase(), text, px * 0.84f, TextUtils.TruncateAt.END).toString(), px * 0.08f, px * 0.52f, text)
        text.textSize = px * 0.036f
        text.color = dim
        canvas.drawText(artist.uppercase(), px * 0.082f, px * 0.58f, text)
        return out
    }

    /**
     * The back of the sleeve: the front's own colours, far out of focus and dark, and on them the
     * record's sides — every track a line, laid out by [SleeveBackLayout], the one that is
     * [playing] lit.
     */
    fun sleeveBack(
        context: Context,
        cover: Bitmap?,
        title: String,
        artist: String,
        sides: List<Side>,
        titles: List<String>,
        playing: Int,
        /** A side the record is waiting to be turned over to, or −1: its heading says so, in the lit line's colour. */
        turnTo: Int = -1
    ): Bitmap {
        val px = SLEEVE_PX
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(30, 26, 24))
        if (cover != null) {
            // Sixteen pixels across and stretched back up: the cheapest blur there is, and for a
            // field of colour the right one.
            val tiny = Bitmap.createScaledBitmap(cover, 12, 12, true)
            canvas.drawBitmap(tiny, null, Rect(0, 0, px, px), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 120 })
            canvas.drawColor(Color.argb(118, 12, 10, 9))
        }
        val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val sans = font(context, R.font.inter_variable)
        val mono = font(context, R.font.jetbrains_mono_variable)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val m = SleeveBackLayout.MARGIN * px

        text.typeface = serif
        text.color = cream
        text.textSize = px * 0.058f
        canvas.drawText(TextUtils.ellipsize(title.uppercase(), text, px - 2 * m, TextUtils.TruncateAt.END).toString(), m, px * 0.135f, text)
        text.typeface = mono
        text.color = dim
        text.textSize = px * 0.024f
        text.letterSpacing = 0.14f
        canvas.drawText(TextUtils.ellipsize(artist.uppercase(), text, px - 2 * m, TextUtils.TruncateAt.END).toString(), m, px * 0.180f, text)
        text.letterSpacing = 0f
        val rule = Paint().apply { color = dim; strokeWidth = px * 0.002f }
        canvas.drawLine(m, px * 0.208f, px - m, px * 0.208f, rule)

        val layout = SleeveBackLayout.of(sides)
        for (heading in layout.headings) {
            text.typeface = mono
            text.color = dim
            text.textSize = px * 0.021f
            text.letterSpacing = 0.16f
            val next = heading.side == turnTo
            text.color = if (next) amber else dim
            val words = if (next) "SIDE ${Sides.letter(heading.side)}  ·  TURN OVER" else "SIDE ${Sides.letter(heading.side)}"
            canvas.drawText(words, heading.left * px, (heading.top + SleeveBackLayout.HEADING_HEIGHT * 0.62f) * px, text)
            text.letterSpacing = 0f
        }
        val size = (layout.rowHeight * 0.52f).coerceAtMost(0.034f) * px
        for (row in layout.rows) {
            val on = row.trackIndex == playing
            val left = row.left * px
            val right = row.right * px
            val base = (row.top + layout.rowHeight * 0.68f) * px
            if (on) {
                canvas.drawRoundRect(
                    RectF(left - px * 0.014f, row.top * px + px * 0.004f, right + px * 0.014f, row.bottom * px - px * 0.004f),
                    px * 0.008f, px * 0.008f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 240, 170, 90) }
                )
            }
            text.typeface = mono
            text.textSize = size * 0.78f
            text.color = if (on) amber else dim
            text.textAlign = Paint.Align.LEFT
            canvas.drawText((row.trackIndex + 1).toString().padStart(2, '0'), left, base, text)
            val length = sides.firstOrNull { row.trackIndex in it }?.let { it.trackDurationsMs[row.trackIndex - it.firstTrack] } ?: 0L
            val time = "%d:%02d".format(length / 60_000, length / 1000 % 60)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(time, right, base, text)
            val timeWidth = text.measureText(time)
            text.typeface = sans
            text.textSize = size
            text.color = if (on) amber else cream
            text.textAlign = Paint.Align.LEFT
            val name = titles.getOrNull(row.trackIndex).orEmpty()
            val room = right - left - px * 0.062f - timeWidth - px * 0.02f
            canvas.drawText(TextUtils.ellipsize(name, text, room, TextUtils.TruncateAt.END).toString(), left + px * 0.062f, base, text)
        }

        canvas.drawLine(m, px * 0.842f, px - m, px * 0.842f, rule.apply { alpha = 120 })
        text.typeface = mono
        text.color = dim
        text.textSize = px * 0.019f
        text.letterSpacing = 0.14f
        val records = if (sides.size > 2) "${(sides.size + 1) / 2} RECORDS · " else ""
        canvas.drawText("${records}STEREO · 33⅓ RPM", m, px * 0.895f, text)
        return out
    }

    /**
     * The label on a divider's tab: a shelf's name and how many records are behind it, typed on
     * paper — or, in amber, the one card that is not a shelf.
     */
    fun dividerLabel(context: Context, name: String, detail: String, accent: Boolean): Bitmap {
        val out = Bitmap.createBitmap(LABEL_W, LABEL_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (accent) Color.rgb(34, 28, 22) else Color.rgb(226, 218, 200))
        val ink = if (accent) amber else Color.rgb(34, 30, 27)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 110; style = Paint.Style.STROKE; strokeWidth = LABEL_H * 0.03f }
        canvas.drawRect(LABEL_H * 0.07f, LABEL_H * 0.09f, LABEL_W - LABEL_H * 0.07f, LABEL_H * 0.91f, edge)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context, R.font.jetbrains_mono_variable)
            color = ink
            letterSpacing = 0.06f
        }
        val room = LABEL_W - LABEL_H * 0.44f
        text.textSize = LABEL_H * 0.40f
        val small = TextPaint(text).apply { textSize = LABEL_H * 0.24f; alpha = 190 }
        val tail = if (detail.isEmpty()) 0f else small.measureText(detail) + LABEL_H * 0.20f
        val shown = TextUtils.ellipsize(name.uppercase(), text, room - tail, TextUtils.TruncateAt.END).toString()
        val left = LABEL_H * 0.22f
        val base = LABEL_H * 0.5f + text.textSize * 0.36f
        canvas.drawText(shown, left, base, text)
        if (detail.isNotEmpty()) {
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(detail, LABEL_W - LABEL_H * 0.22f, base, small)
        }
        return out
    }

    /** The back of a sleeve as the crate shows it — which is hardly at all: card. */
    fun cardBack(px: Int = 64): Bitmap =
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(46, 40, 35)) }

    private fun centreSquare(bitmap: Bitmap): Rect {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT
}
