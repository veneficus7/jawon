package com.jawon.app.ui.pocketscene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * **The ticket on the table** — a prop of the still life at rest (`docs/formats.md` §4): a card
 * ticket for the night train, whose destination is the album playing. From its artist, to its
 * title; the berth is the track playing, the car is the album's length in tracks, and the date
 * stamped on it is today's. What it says is decided here, purely, so it can be tested; drawing it
 * is [TicketArt].
 */
data class TicketText(
    val from: String,
    /** The destination, in one line or two. */
    val to: List<String>,
    val car: String,
    val berth: String,
    val date: String,
    val journey: String,
    /** The ticket's number — the same album, the same number. */
    val serial: String
) {
    companion object {
        /** How much fits across the ticket at each size — the type is monospaced, so it is characters. */
        const val TO_CHARS = 21
        const val FROM_CHARS = 30

        private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

        /**
         * The ticket for [title] by [artist] — the track at [trackIndex] of [trackCount] playing,
         * [durationMs] long in all — stamped on the day of [day] (year, month 1–12, day of month).
         * No album, and it is a ticket to anywhere.
         */
        fun of(
            albumId: String?,
            artist: String?,
            title: String?,
            trackIndex: Int,
            trackCount: Int,
            durationMs: Long,
            day: Triple<Int, Int, Int>
        ): TicketText {
            val (year, month, date) = day
            val stamped = "%02d %s %02d".format(Locale.ROOT, date, MONTHS[(month - 1).coerceIn(0, 11)], year % 100)
            val serialSource = albumId ?: "anywhere"
            val serial = (serialSource.hashCode().toLong() and 0xFFFFFFFFL).rem(10_000_000_000L)
                .toString().padStart(10, '0').chunked(3).joinToString(" ")
            if (title.isNullOrBlank()) {
                return TicketText(
                    from = "JAWON", to = listOf("ANYWHERE"), car = "--", berth = "--",
                    date = stamped, journey = "--", serial = serial
                )
            }
            val minutes = ((durationMs + 30_000L) / 60_000L).coerceAtLeast(0L)
            return TicketText(
                from = fit(artist.orEmpty().ifBlank { "JAWON" }.uppercase(Locale.ROOT), FROM_CHARS),
                to = wrap(title.uppercase(Locale.ROOT), TO_CHARS),
                car = if (trackCount > 0) "%02d".format(Locale.ROOT, trackCount) else "--",
                berth = if (trackIndex >= 0) "%02d".format(Locale.ROOT, trackIndex + 1) else "--",
                date = stamped,
                journey = if (durationMs > 0L) "$minutes MIN" else "--",
                serial = serial
            )
        }

        /** [s] cut to [max] characters, an ellipsis where it was cut. */
        internal fun fit(s: String, max: Int): String {
            val t = s.trim()
            return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
        }

        /**
         * [s] in at most two lines of [max] characters, broken between words where it can be; what
         * will not fit in two ends in an ellipsis.
         */
        internal fun wrap(s: String, max: Int): List<String> {
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return listOf("")
            val first = StringBuilder()
            var i = 0
            while (i < words.size) {
                val w = words[i]
                val next = if (first.isEmpty()) w else "$first $w"
                if (next.length > max) break
                first.setLength(0)
                first.append(next)
                i++
            }
            if (first.isEmpty()) {
                // One word longer than a line: break it.
                val w = words[0]
                val rest = (listOf(w.drop(max)) + words.drop(1)).joinToString(" ")
                return listOf(w.take(max)) + (if (rest.isBlank()) emptyList() else listOf(fit(rest, max)))
            }
            if (i >= words.size) return listOf(first.toString())
            return listOf(first.toString(), fit(words.drop(i).joinToString(" "), max))
        }

        /** Today, as [of] wants it. */
        fun today(): Triple<Int, Int, Int> {
            val c = Calendar.getInstance()
            return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }
    }
}

/**
 * Draws a [TicketText] as the card it is printed on: cream board with its fibres, a guilloche under
 * the print, the line's band across the top, the fields in a dot-matrix printer's monospace, and a
 * conductor's violet stamp gone on at an angle. [W] × [H] is the card's 85 × 54mm.
 */
object TicketArt {
    const val W = 1024
    const val H = 650

    private val PAPER = Color.rgb(238, 230, 210)
    private val BAND = Color.rgb(32, 70, 58)
    private val BAND_INK = Color.rgb(236, 228, 206)
    private val LABEL = Color.rgb(84, 104, 94)
    private val INK = Color.argb(228, 28, 30, 33)
    private val GUILLOCHE = Color.argb(34, 32, 96, 80)
    private val STAMP = Color.argb(150, 92, 58, 150)

    fun draw(text: TicketText): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(PAPER)
        val rng = Random(text.serial.hashCode())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // The board's fibres: short light and dark flecks, too small to see one, enough to see board.
        p.strokeWidth = 1.3f
        repeat(2600) {
            val x = rng.nextFloat() * W
            val y = rng.nextFloat() * H
            val a = rng.nextFloat() * 6.2832f
            val len = 2f + rng.nextFloat() * 5f
            p.color = if (rng.nextBoolean()) Color.argb(14, 90, 70, 40) else Color.argb(20, 255, 252, 240)
            c.drawLine(x, y, x + len * kotlin.math.cos(a), y + len * kotlin.math.sin(a), p)
        }

        // The guilloche under the body: fine waves, as any ticket printed against copying carries.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f
        p.color = GUILLOCHE
        for (k in 0 until 26) {
            val path = android.graphics.Path()
            val y0 = 118f + k * 19f
            path.moveTo(0f, y0)
            var x = 0f
            while (x <= W) {
                val y = y0 + 7f * kotlin.math.sin((x / 37f) + k * 0.7f) + 3f * kotlin.math.sin(x / 11f + k)
                path.lineTo(x, y)
                x += 6f
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL

        // The line's band.
        p.color = BAND
        c.drawRect(0f, 0f, W.toFloat(), 96f, p)
        p.color = BAND_INK
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textSize = 40f
        p.letterSpacing = 0.14f
        c.drawText("JAWON NIGHT EXPRESS", 44f, 62f, p)
        p.textSize = 24f
        p.letterSpacing = 0.10f
        p.textAlign = Paint.Align.RIGHT
        c.drawText("SLEEPER · 2ND CLASS", W - 44f, 60f, p)
        p.textAlign = Paint.Align.LEFT

        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        fun label(s: String, x: Float, y: Float) {
            p.color = LABEL
            p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            p.textSize = 22f
            p.letterSpacing = 0.12f
            c.drawText(s, x, y, p)
        }
        fun value(s: String, x: Float, y: Float, size: Float) {
            p.color = INK
            p.typeface = mono
            p.textSize = size
            p.letterSpacing = 0.02f
            c.drawText(s, x, y, p)
        }

        label("FROM", 44f, 150f)
        value(text.from, 44f, 192f, 34f)
        label("TO", 44f, 246f)
        text.to.forEachIndexed { i, line -> value(line, 44f, 300f + i * 58f, 50f) }

        val row = 470f
        label("CAR", 44f, row)
        value(text.car, 44f, row + 44f, 38f)
        label("BERTH", 200f, row)
        value(text.berth, 200f, row + 44f, 38f)
        label("DATE", 380f, row)
        value(text.date, 380f, row + 44f, 38f)
        label("JOURNEY", 690f, row)
        value(text.journey, 690f, row + 44f, 38f)

        // The small print and the ticket's number.
        p.color = LABEL
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        p.textSize = 19f
        p.letterSpacing = 0.06f
        c.drawText("VALID FOR ONE JOURNEY · KEEP UNTIL THE LAST TRACK", 44f, 590f, p)
        p.typeface = mono
        p.textAlign = Paint.Align.RIGHT
        p.textSize = 22f
        c.drawText("Nº ${text.serial}", W - 44f, 590f, p)
        p.textAlign = Paint.Align.LEFT

        // The conductor's stamp: a ring and the date, violet, on at an angle and not quite all there.
        c.save()
        c.rotate(-13f, 860f, 250f)
        p.color = STAMP
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        c.drawCircle(860f, 250f, 96f, p)
        p.strokeWidth = 2.5f
        c.drawCircle(860f, 250f, 82f, p)
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.letterSpacing = 0.08f
        p.textSize = 22f
        c.drawText("JAWON", 860f, 222f, p)
        p.textSize = 30f
        c.drawText(text.date, 860f, 262f, p)
        p.textSize = 18f
        c.drawText("CHECKED", 860f, 296f, p)
        p.textAlign = Paint.Align.LEFT
        c.restore()
        // Where the stamp's ink did not take: flecks of paper back over it.
        p.color = PAPER
        repeat(160) {
            val a = rng.nextFloat() * 6.2832f
            val r = 70f + rng.nextFloat() * 34f
            c.drawCircle(860f + r * kotlin.math.cos(a), 250f + r * kotlin.math.sin(a), 1f + rng.nextFloat() * 2.2f, p)
        }
        return bitmap
    }
}
