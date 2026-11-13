package com.jawon.app.ui.tapescene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.jawon.app.R
import com.jawon.app.ui.vinylscene.Side
import com.jawon.app.ui.vinylscene.Sides

/**
 * Where everything on the inside of the J-card goes — the one piece of this room's printing that
 * is touched as well as read. The lid stands open on the deck and the J-card's inside is the
 * tracklist (`docs/formats.md` §6): a line touched winds the tape to that song. So where a line is
 * drawn and where a finger finds it are the same arithmetic, and this is it, pure.
 *
 * Fractions of the card as it stands — u to the right, v down from its top.
 */
object JCardLayout {

    class Heading(val side: Int, val left: Float, val top: Float)

    class Row(val trackIndex: Int, val left: Float, val right: Float, val top: Float, val bottom: Float)

    class Layout(val headings: List<Heading>, val rows: List<Row>, val rowHeight: Float) {
        /** The track whose line is under ([u], [v]), or −1. A finger's width either side of a line still finds it. */
        fun trackAt(u: Float, v: Float): Int {
            rows.firstOrNull { u >= it.left - SLACK_U && u <= it.right + SLACK_U && v >= it.top && v < it.bottom }?.let { return it.trackIndex }
            return -1
        }
    }

    const val MARGIN = 0.045f
    const val HEADER_BOTTOM = 0.215f
    const val HEADING_TOP = 0.245f
    const val HEADING_HEIGHT = 0.075f
    const val LIST_BOTTOM = 0.93f
    const val ROW_TALLEST = 0.088f
    private const val COLUMN_GAP = 0.04f
    private const val SLACK_U = 0.02f

    /** The two sides of [tape] of an album cut into [sides], laid out side by side. */
    fun of(sides: List<Side>, tape: Int): Layout {
        val first = tape * 2
        val shown = listOfNotNull(sides.getOrNull(first), sides.getOrNull(first + 1))
        if (shown.isEmpty()) return Layout(emptyList(), emptyList(), ROW_TALLEST)
        val most = shown.maxOf { it.trackDurationsMs.size }.coerceAtLeast(1)
        val top = HEADING_TOP + HEADING_HEIGHT
        val rowHeight = ((LIST_BOTTOM - top) / most).coerceAtMost(ROW_TALLEST)
        val columnWidth = (1f - 2 * MARGIN - COLUMN_GAP) / 2f
        val headings = ArrayList<Heading>()
        val rows = ArrayList<Row>()
        shown.forEachIndexed { column, side ->
            val left = MARGIN + column * (columnWidth + COLUMN_GAP)
            headings += Heading(first + column, left, HEADING_TOP)
            side.trackDurationsMs.indices.forEach { i ->
                val rowTop = top + i * rowHeight
                rows += Row(side.firstTrack + i, left, left + columnWidth, rowTop, rowTop + rowHeight)
            }
        }
        return Layout(headings, rows, rowHeight)
    }
}

/**
 * What the cassette room prints: a tape's two labels, its J-card's three panels, a rack's name.
 *
 * A pre-recorded tape's look, and nobody's in particular: the album's own colour in a band, the
 * cover small beside it, the words in Jawon's two faces, and on every shell and every J-card a
 * small JAWON — the room signing itself in its own material (roadmap §B).
 */
object TapeArt {

    /** A label, 88 × 46mm. */
    const val LABEL_W = 1024
    const val LABEL_H = 536

    /** The J-card's front panel, portrait, and its inside, landscape — 64 × 101mm both. */
    const val FRONT_W = 640
    const val FRONT_H = 1010
    const val INSIDE_W = 1024
    const val INSIDE_H = 648

    /** Its spine, 101 × 12.5mm; and the rack's spines, smaller, for the many of them. */
    const val SPINE_W = 1024
    const val SPINE_H = 128
    const val RACK_SPINE_W = 512
    const val RACK_SPINE_H = 64

    /** A rack's name card, 96 × 18mm. */
    const val RACK_LABEL_W = 512
    const val RACK_LABEL_H = 96

    private val paper = Color.rgb(236, 229, 213)
    private val ink = Color.rgb(32, 28, 26)
    private val inkSoft = Color.rgb(108, 100, 90)
    private val cream = Color.rgb(240, 233, 220)
    private val amber = Color.rgb(214, 128, 40)

    /** The colour of a record: its cover's own, as the CD room's spine has it, deepened to print on. */
    fun band(hue: Float): Int = Color.HSVToColor(floatArrayOf(hue, 0.58f, 0.40f))

    // ---- The cassette's labels ----------------------------------------------------------------------

    /**
     * One face's label. The window through the middle is a hole in the label, so nothing is put
     * there: the band across the top, the side's letter and the tape's length in the strips either
     * side of the window, and JAWON along the foot.
     */
    fun label(context: Context, cover: Bitmap?, hue: Float, letter: String, title: String, artist: String, stock: String): Bitmap {
        val w = LABEL_W
        val h = LABEL_H
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(paper)
        // Where the window is cut, as fractions of the label (docs/models.md): kept clear.
        val bandBottom = h * 0.285f
        canvas.drawRect(0f, 0f, w.toFloat(), bandBottom, Paint().apply { color = band(hue) })
        val pad = h * 0.035f
        var textLeft = pad * 1.4f
        if (cover != null) {
            val side = (bandBottom - 2 * pad).toInt()
            canvas.drawBitmap(cover, centreSquare(cover), Rect(textLeft.toInt(), pad.toInt(), textLeft.toInt() + side, pad.toInt() + side), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft += side + pad * 1.2f
        }
        val display = font(context, R.font.archivo_variable)
        val mono = font(context, R.font.jetbrains_mono_variable)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        text.typeface = display
        text.color = cream
        text.textSize = h * 0.092f
        val room = w - textLeft - pad * 1.4f
        canvas.drawText(TextUtils.ellipsize(title.uppercase(), text, room, TextUtils.TruncateAt.END).toString(), textLeft, bandBottom * 0.50f, text)
        text.typeface = mono
        text.textSize = h * 0.052f
        text.letterSpacing = 0.10f
        text.color = Color.argb(215, 240, 233, 220)
        canvas.drawText(TextUtils.ellipsize(artist.uppercase(), text, room, TextUtils.TruncateAt.END).toString(), textLeft, bandBottom * 0.80f, text)
        text.letterSpacing = 0f

        // The side's letter, large, left of the window; the tape's length, right of it.
        text.typeface = display
        text.color = ink
        text.textSize = h * 0.20f
        text.textAlign = Paint.Align.CENTER
        val stripMiddle = h * 0.57f
        canvas.drawText(letter, w * 0.050f, stripMiddle + text.textSize * 0.36f, text)
        text.typeface = mono
        text.textSize = h * 0.050f
        text.color = inkSoft
        canvas.save()
        canvas.rotate(90f, w * 0.952f, stripMiddle)
        canvas.drawText(stock, w * 0.952f, stripMiddle + text.textSize * 0.36f, text)
        canvas.restore()

        // The foot: the room's name, and what a tape says.
        text.textAlign = Paint.Align.LEFT
        text.textSize = h * 0.046f
        text.letterSpacing = 0.30f
        text.color = ink
        canvas.drawText("JAWON", w * 0.10f, h * 0.945f, text)
        text.textAlign = Paint.Align.RIGHT
        text.letterSpacing = 0.14f
        text.color = inkSoft
        canvas.drawText("STEREO · NORMAL BIAS", w * 0.90f, h * 0.945f, text)
        return out
    }

    // ---- The J-card -------------------------------------------------------------------------------------

    /** The front, as the box is seen: the cover square at the top, the record's name in its colour under it. */
    fun jcardFront(context: Context, cover: Bitmap?, hue: Float, title: String, artist: String): Bitmap {
        val w = FRONT_W
        val h = FRONT_H
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(band(hue))
        if (cover != null) {
            canvas.drawBitmap(cover, centreSquare(cover), Rect(0, 0, w, w), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawRect(0f, 0f, w.toFloat(), w.toFloat(), Paint().apply { color = Color.rgb(40, 34, 30) })
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val pad = w * 0.07f
        text.typeface = font(context, R.font.archivo_variable)
        text.color = cream
        text.textSize = w * 0.085f
        val lines = wrap(title.uppercase(), text, w - 2 * pad, 2)
        var y = w + w * 0.13f
        for (line in lines) {
            canvas.drawText(line, pad, y, text)
            y += text.textSize * 1.08f
        }
        text.typeface = font(context, R.font.jetbrains_mono_variable)
        text.textSize = w * 0.042f
        text.letterSpacing = 0.12f
        text.color = Color.argb(210, 240, 233, 220)
        canvas.drawText(TextUtils.ellipsize(artist.uppercase(), text, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, y + text.textSize * 0.4f, text)
        text.textSize = w * 0.034f
        text.letterSpacing = 0.30f
        canvas.drawText("JAWON", pad, h - pad * 0.8f, text)
        text.textAlign = Paint.Align.RIGHT
        text.letterSpacing = 0.12f
        canvas.drawText("CASSETTE", w - pad, h - pad * 0.8f, text)
        return out
    }

    /**
     * The inside: the tracklist, both sides of the tape that is in, laid out by [JCardLayout] —
     * the song [playing] lit, and a side the deck is waiting to be turned to saying so.
     */
    fun jcardInside(
        context: Context,
        title: String,
        artist: String,
        sides: List<Side>,
        tape: Int,
        titles: List<String>,
        playing: Int,
        awaiting: Int,
        tapes: Int
    ): Bitmap {
        val w = INSIDE_W
        val h = INSIDE_H
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(paper)
        val display = font(context, R.font.archivo_variable)
        val sans = font(context, R.font.inter_variable)
        val mono = font(context, R.font.jetbrains_mono_variable)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val m = JCardLayout.MARGIN * w

        text.typeface = display
        text.color = ink
        text.textSize = h * 0.085f
        canvas.drawText(TextUtils.ellipsize(title.uppercase(), text, w - 2 * m, TextUtils.TruncateAt.END).toString(), m, h * 0.115f, text)
        text.typeface = mono
        text.color = inkSoft
        text.textSize = h * 0.040f
        text.letterSpacing = 0.12f
        val set = if (tapes > 1) "  ·  TAPE ${tape + 1} OF $tapes" else ""
        canvas.drawText(TextUtils.ellipsize(artist.uppercase() + set, text, w - 2 * m, TextUtils.TruncateAt.END).toString(), m, h * 0.180f, text)
        text.letterSpacing = 0f
        val rule = Paint().apply { color = inkSoft; strokeWidth = h * 0.003f }
        canvas.drawLine(m, JCardLayout.HEADER_BOTTOM * h, w - m, JCardLayout.HEADER_BOTTOM * h, rule)

        val layout = JCardLayout.of(sides, tape)
        for (heading in layout.headings) {
            val next = heading.side == awaiting
            text.typeface = mono
            text.textSize = h * 0.040f
            text.letterSpacing = 0.16f
            text.color = if (next) amber else inkSoft
            val words = if (next) "SIDE ${Sides.letter(heading.side)}  ·  TURN OVER" else "SIDE ${Sides.letter(heading.side)}"
            canvas.drawText(words, heading.left * w, (heading.top + JCardLayout.HEADING_HEIGHT * 0.60f) * h, text)
            text.letterSpacing = 0f
        }
        val size = (layout.rowHeight * 0.52f).coerceAtMost(0.050f) * h
        for (row in layout.rows) {
            val on = row.trackIndex == playing
            val left = row.left * w
            val right = row.right * w
            val base = (row.top + layout.rowHeight * 0.70f) * h
            if (on) {
                canvas.drawRoundRect(
                    RectF(left - w * 0.010f, row.top * h + h * 0.006f, right + w * 0.010f, row.bottom * h - h * 0.006f),
                    h * 0.012f, h * 0.012f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(62, 214, 128, 40) }
                )
            }
            text.typeface = mono
            text.textSize = size * 0.80f
            text.color = if (on) amber else inkSoft
            text.textAlign = Paint.Align.LEFT
            canvas.drawText((row.trackIndex + 1).toString().padStart(2, '0'), left, base, text)
            val length = sides.firstOrNull { row.trackIndex in it }?.let { it.trackDurationsMs[row.trackIndex - it.firstTrack] } ?: 0L
            val time = "%d:%02d".format(length / 60_000, length / 1000 % 60)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(time, right, base, text)
            val timeWidth = text.measureText(time)
            text.typeface = sans
            text.textSize = size
            text.color = if (on) Color.rgb(150, 80, 18) else ink
            text.textAlign = Paint.Align.LEFT
            val name = titles.getOrNull(row.trackIndex).orEmpty()
            val gap = w * 0.050f
            val room = right - left - gap - timeWidth - w * 0.012f
            canvas.drawText(TextUtils.ellipsize(name, text, room, TextUtils.TruncateAt.END).toString(), left + gap, base, text)
        }
        return out
    }

    /** The spine: the record's colour, and its name and whose it is, as a rack shows it. */
    fun spine(context: Context, hue: Float, title: String, artist: String, w: Int = SPINE_W, h: Int = SPINE_H): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(band(hue))
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val pad = h * 0.45f
        text.typeface = font(context, R.font.jetbrains_mono_variable)
        text.textSize = h * 0.40f
        text.letterSpacing = 0.10f
        text.color = Color.argb(205, 240, 233, 220)
        val who = artist.uppercase()
        val whoWidth = minOf(text.measureText(who), w * 0.36f)
        canvas.drawText(TextUtils.ellipsize(who, text, w * 0.36f, TextUtils.TruncateAt.END).toString(), pad, h * 0.5f + text.textSize * 0.36f, text)
        text.letterSpacing = 0f
        text.typeface = font(context, R.font.archivo_variable)
        text.textSize = h * 0.50f
        text.color = cream
        val left = pad + whoWidth + h * 0.6f
        canvas.drawText(TextUtils.ellipsize(title.uppercase(), text, w - left - pad * 1.6f, TextUtils.TruncateAt.END).toString(), left, h * 0.5f + text.textSize * 0.36f, text)
        // A short rule at the far end, where the J-card's spine always has something.
        canvas.drawRect(w - pad * 1.1f, h * 0.30f, w - pad * 0.7f, h * 0.70f, Paint().apply { color = Color.argb(150, 240, 233, 220) })
        return out
    }

    /** A rack's card: the shelf's name, typed, and which of its racks this is if it needs more than one. */
    fun rackLabel(context: Context, name: String, part: String): Bitmap {
        val w = RACK_LABEL_W
        val h = RACK_LABEL_H
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(230, 222, 204))
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context, R.font.jetbrains_mono_variable)
            color = ink
            letterSpacing = 0.08f
            textSize = h * 0.46f
        }
        val small = TextPaint(text).apply { textSize = h * 0.28f; color = inkSoft }
        val tail = if (part.isEmpty()) 0f else small.measureText(part) + h * 0.3f
        canvas.drawText(TextUtils.ellipsize(name.uppercase(), text, w - h * 0.5f - tail, TextUtils.TruncateAt.END).toString(), h * 0.25f, h * 0.5f + text.textSize * 0.36f, text)
        if (part.isNotEmpty()) {
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(part, w - h * 0.25f, h * 0.5f + text.textSize * 0.36f, small)
        }
        return out
    }

    private fun wrap(words: String, paint: TextPaint, width: Float, most: Int): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in words.split(' ')) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(trial) <= width || line.isEmpty()) {
                line = trial
            } else {
                lines += line
                line = word
                if (lines.size == most - 1) break
            }
        }
        if (lines.size < most && line.isNotEmpty()) lines += line
        if (lines.size == most) {
            val rest = words.substringAfter(lines.dropLast(1).joinToString(" "), "").trim()
            lines[most - 1] = TextUtils.ellipsize(rest.ifEmpty { lines[most - 1] }, paint, width, TextUtils.TruncateAt.END).toString()
        }
        return lines
    }

    private fun centreSquare(bitmap: Bitmap): Rect {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT
}
