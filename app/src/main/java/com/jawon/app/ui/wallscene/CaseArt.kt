package com.jawon.app.ui.wallscene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.jawon.app.R
import java.io.File
import kotlin.math.max
import kotlin.random.Random

/**
 * Everything that turns an album into pixels for the wall. All of it is CPU work on
 * bitmaps and must run off the main thread (ENGINEERING.md rule 5).
 */
object CaseArt {

    /** Spine texture size. ~1:1 with a shelved spine on a 1080x2340 screen at the M6 camera. */
    private const val SPINE_W = 96
    private const val SPINE_H = 852

    /** Covers are edge-on while shelved; M7 will want a larger swap when a case turns. */
    const val COVER_MAX_PX = 256

    // Proportional port of the prototype's spineTex(), so the layout survives a size change.
    private const val BAR_TOP = 0.046f
    private const val BAR_BOTTOM = 0.936f
    private const val BAR_THICKNESS = 0.018f
    private const val TITLE_SIZE = 0.347f     // of spine width
    private const val ARTIST_SIZE = 0.195f
    private const val TITLE_OFFSET = -0.125f  // across the spine, from centre
    private const val ARTIST_OFFSET = 0.195f
    private const val TEXT_MARGIN = 0.06f     // of spine length, each end

    private val LABEL_CREAM = Color.rgb(0xEF, 0xE3, 0xCC)
    private val LABEL_DIM = Color.rgb(0x9A, 0x8B, 0x76)

    class Fonts(context: Context) {
        val display: Typeface? = runCatching {
            ResourcesCompat.getFont(context, R.font.archivo_variable)
        }.getOrNull()
        val mono: Typeface? = runCatching {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono_variable)
        }.getOrNull()
    }

    /** Downsampled decode of a cover written by M1's CoverImageStore (an absolute path). */
    fun loadCover(path: String?, maxPx: Int = COVER_MAX_PX): Bitmap? {
        if (path.isNullOrEmpty() || !File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }

    /**
     * The album's dominant hue, as a 0..1 fraction. Saturation-weighted so a mostly-grey
     * sleeve doesn't hand back whatever noise happens to be in the shadows; null when the
     * cover has no meaningful colour at all, so the caller can fall back.
     *
     * Only the *hue* is taken — DESIGN.md's requirement that extracted colour "can never
     * break the warm-dark palette" is satisfied by construction, because
     * [CaseLayout.spineColor] supplies its own fixed low saturation and lightness.
     */
    fun dominantHue(cover: Bitmap): Float? {
        val w = 24
        val h = 24
        val small = runCatching { Bitmap.createScaledBitmap(cover, w, h, true) }.getOrNull() ?: return null
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== cover) small.recycle()

        val bins = FloatArray(36)
        val hsv = FloatArray(3)
        var total = 0f
        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            val weight = hsv[1] * hsv[2]
            if (weight <= 0.05f) continue
            bins[((hsv[0] / 10f).toInt()).coerceIn(0, 35)] += weight
            total += weight
        }
        if (total <= 0f) return null

        val best = bins.indices.maxByOrNull { bins[it] } ?: return null
        return (best * 10f + 5f) / 360f
    }

    /**
     * The spine itself, ported from the prototype's `spineTex()`: hue-tinted stock, two
     * printed accent bars, the title and artist set along the spine, an edge highlight and
     * shadow, and a little wear speckle.
     *
     * Deviation from the prototype, per DESIGN.md's "no competing saturated colour outside
     * the disc surface": the prototype sets the artist line in a saturated per-album hue and
     * its bars at 52% saturation. Here the title is labelCream, the artist labelDim, and the
     * bars are pulled down to 35% — the per-album colour stays in the low-saturation stock.
     */
    fun bakeSpine(
        albumId: String,
        title: String,
        artist: String,
        hue: Float,
        fonts: Fonts
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(SPINE_W, SPINE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = SPINE_W.toFloat()
        val h = SPINE_H.toFloat()
        val hueDeg = hue * 360f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Stock
        paint.color = Color.HSVToColor(floatArrayOf(hueDeg, 0.26f, 0.15f + hue * 0.13f))
        canvas.drawRect(0f, 0f, w, h, paint)

        // Printed bands
        paint.color = Color.HSVToColor(floatArrayOf(hueDeg, 0.35f, 0.42f + hue * 0.16f))
        canvas.drawRect(0f, h * BAR_TOP, w, h * (BAR_TOP + BAR_THICKNESS), paint)
        canvas.drawRect(0f, h * BAR_BOTTOM, w, h * (BAR_BOTTOM + BAR_THICKNESS), paint)

        // Title + artist, set along the spine
        val available = h * (1f - TEXT_MARGIN * 2f)
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(-90f)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.display
            runCatching { fontVariationSettings = "'wght' 800, 'wdth' 115" }
            textSize = w * TITLE_SIZE
            color = LABEL_CREAM
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            TextUtils.ellipsize(title, titlePaint, available, TextUtils.TruncateAt.END).toString(),
            0f,
            w * TITLE_OFFSET,
            titlePaint
        )

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.mono
            runCatching { fontVariationSettings = "'wght' 500" }
            textSize = w * ARTIST_SIZE
            color = LABEL_DIM
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        canvas.drawText(
            TextUtils.ellipsize(
                artist.uppercase(), artistPaint, available, TextUtils.TruncateAt.END
            ).toString(),
            0f,
            w * ARTIST_OFFSET,
            artistPaint
        )
        canvas.restore()

        // Wear. Seeded per album so a re-bake looks identical rather than shimmering.
        val random = Random(albumId.hashCode())
        val speck = Paint()
        repeat(180) {
            speck.color = Color.argb((random.nextFloat() * 23).toInt(), 0, 0, 0)
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            canvas.drawRect(x, y, x + 2f, y + 2f, speck)
        }

        // Edge highlight and shadow — what actually reads as thickness on a packed shelf.
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRect(0f, 0f, w * 0.04f, h, paint)
        paint.color = Color.argb(82, 0, 0, 0)
        canvas.drawRect(w * 0.96f, 0f, w, h, paint)

        return bitmap
    }

    /** Bytes a cached entry costs, for the cache's capacity policy. */
    fun byteCount(vararg bitmaps: Bitmap?): Int =
        bitmaps.filterNotNull().sumOf { max(it.byteCount, 0) }
}
