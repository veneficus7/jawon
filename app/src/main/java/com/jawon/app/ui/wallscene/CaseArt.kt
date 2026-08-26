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
    const val SPINE_W = 96
    const val SPINE_H = 852

    /**
     * Covers are normalised to exactly this, square, so every cover bitmap has identical
     * dimensions. That is what lets the active case swap its art in place via
     * `ImageNode.bitmap` instead of being rebuilt — and rebuilding an ImageNode is what
     * aborts the process (see WallSceneScreen). Album covers are square by convention, so
     * the fit is not a compromise in practice.
     */
    const val COVER_PX = 256

    /** A transparent bitmap of a fixed size, used to build art nodes before any album is open. */
    fun blank(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

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

    /**
     * Downsampled decode of a cover written by M1's CoverImageStore (an absolute path),
     * normalised to [COVER_PX] square so every cover is interchangeable as a texture source.
     */
    fun loadCoverSquare(path: String?): Bitmap? {
        val decoded = loadCover(path, COVER_PX) ?: return null
        if (decoded.width == COVER_PX && decoded.height == COVER_PX) return decoded
        return runCatching {
            Bitmap.createScaledBitmap(decoded, COVER_PX, COVER_PX, true)
        }.getOrNull() ?: decoded
    }

    /** Downsampled decode of a cover written by M1's CoverImageStore (an absolute path). */
    fun loadCover(path: String?, maxPx: Int = COVER_PX): Bitmap? {
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
    /**
     * The back inlay, in the same 125:142 proportion as the case's back face.
     */
    const val BACK_W = 256
    const val BACK_H = 291

    /**
     * The printed back of the case — **generated, never uploaded.**
     *
     * The wall used to show only spines, so the back never faced anyone. Now that each album
     * turns a full circle in its bay, half of every revolution is the back, and a blank slab
     * of plastic there undoes the whole "precious artefact" idea.
     *
     * Asking users for a third image per album is the obvious fix and the wrong one: they
     * already supply a cover and a disc face, and PLAN.md is explicit that the library is
     * entirely user-supplied but the *effort* should stay small. So this is derived from what
     * we already have — the album's own hue, pulled from its cover by [dominantHue] — which
     * means every back is different, every back matches its cover, and the user does nothing.
     *
     * What makes it read as a CD back rather than a coloured rectangle is the furniture: a
     * printed panel inset from the edges, a rule under the title, and a barcode block bottom
     * right. The barcode is the strongest signal of the three and costs a dozen lines.
     *
     * The tracklist is deliberately absent. It wants real track data per album, which the
     * wall does not currently load, and it belongs to the case-back liner notes that the open
     * sequence will need anyway — see PLAN.md §9 #4.
     */
    fun bakeBack(
        albumId: String,
        title: String,
        artist: String,
        hue: Float,
        fonts: Fonts
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(BACK_W, BACK_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = BACK_W.toFloat()
        val h = BACK_H.toFloat()
        val hueDeg = hue * 360f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Card stock, a shade darker than the spine's so the two read as one printing.
        paint.color = Color.HSVToColor(floatArrayOf(hueDeg, 0.24f, 0.12f + hue * 0.10f))
        canvas.drawRect(0f, 0f, w, h, paint)

        // The printed panel, inset — the white border a real inlay leaves at the trim.
        val inset = w * 0.055f
        paint.color = Color.HSVToColor(floatArrayOf(hueDeg, 0.34f, 0.19f + hue * 0.12f))
        canvas.drawRect(inset, inset, w - inset, h - inset, paint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.display
            runCatching { fontVariationSettings = "'wght' 800, 'wdth' 115" }
            textSize = w * 0.085f
            color = LABEL_CREAM
        }
        val textLeft = inset * 2f
        val textWidth = w - textLeft * 2f
        canvas.drawText(
            TextUtils.ellipsize(title, titlePaint, textWidth, TextUtils.TruncateAt.END).toString(),
            textLeft,
            h * 0.15f,
            titlePaint
        )

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.mono
            runCatching { fontVariationSettings = "'wght' 500" }
            textSize = w * 0.048f
            color = LABEL_DIM
            letterSpacing = 0.12f
        }
        canvas.drawText(
            TextUtils.ellipsize(
                artist.uppercase(), artistPaint, textWidth, TextUtils.TruncateAt.END
            ).toString(),
            textLeft,
            h * 0.205f,
            artistPaint
        )

        // Rule under the masthead.
        paint.color = Color.HSVToColor(floatArrayOf(hueDeg, 0.40f, 0.46f + hue * 0.14f))
        canvas.drawRect(textLeft, h * 0.235f, w - textLeft, h * 0.238f, paint)

        // Barcode, bottom right. Bar widths come from the album id, so a given album always
        // prints the same code — it is nonsense, but it is *consistently* nonsense, and a
        // barcode that reshuffled every time the art was rebaked would read as noise.
        val codeRight = w - textLeft
        val codeBottom = h - textLeft
        val codeH = h * 0.085f
        val codeW = w * 0.42f
        paint.color = LABEL_CREAM
        canvas.drawRect(codeRight - codeW, codeBottom - codeH, codeRight, codeBottom, paint)

        paint.color = Color.BLACK
        var seed = albumId.fold(7) { acc, c -> acc * 31 + c.code }
        var x = codeRight - codeW + w * 0.012f
        val quietRight = codeRight - w * 0.012f
        while (x < quietRight) {
            seed = seed * 1103515245 + 12345
            val bar = ((seed ushr 16) and 0x3) + 1
            val barW = bar * w * 0.004f
            if (x + barW > quietRight) break
            canvas.drawRect(x, codeBottom - codeH + w * 0.012f, x + barW, codeBottom - w * 0.02f, paint)
            seed = seed * 1103515245 + 12345
            x += barW + (((seed ushr 16) and 0x3) + 1) * w * 0.004f
        }

        return bitmap
    }

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
