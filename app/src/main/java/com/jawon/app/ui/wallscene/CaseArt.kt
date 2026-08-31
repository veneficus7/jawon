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
     * What the back of a case has to print, beyond the album's own name.
     *
     * Gathered in one object rather than passed as four arguments because the inlay is one
     * printed thing, and because the list of what belongs on it has already grown once.
     */
    data class BackInlay(
        val titles: List<String>,
        /** Sum of every track, for the runtime line. Zero when durations are unknown. */
        val totalMs: Long = 0L,
        /** e.g. `FLAC 16-BIT / 44.1 kHz`. Null when the tracks have no readable codec. */
        val formatLabel: String? = null
    )

    /** `58:21`, or `1:04:07` past the hour. Null when nothing is known. */
    private fun formatRuntime(totalMs: Long): String? {
        if (totalMs <= 0L) return null
        val totalSeconds = totalMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

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
     * printed panel inset from the edges, a rule under the title, and the fine print bottom
     * right — the runtime and the format, where a real case carries its barcode.
     *
     * The tracklist is printed down the panel in mono, numbered, exactly as a real back
     * inlay sets it — DESIGN.md §3 makes mono "non-negotiable" for track numbers and
     * durations, and this is the same language. Titles come from the database once per
     * album; a long list is truncated with a count rather than shrunk to illegibility.
     */
    fun bakeBack(
        albumId: String,
        title: String,
        artist: String,
        hue: Float,
        inlay: BackInlay,
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

        // The tracklist, set in mono the way a real inlay does.
        val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.mono
            textSize = w * 0.040f
            color = Color.HSVToColor(floatArrayOf(hueDeg, 0.45f, 0.62f))
            letterSpacing = 0.06f
        }
        val trackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.mono
            textSize = w * 0.040f
            color = LABEL_CREAM
        }
        val lineH = h * 0.0355f
        var y = h * 0.30f
        // Stop short of the barcode block rather than running under it.
        val listBottom = h - textLeft - h * 0.115f
        val numColumn = textLeft
        val titleColumn = textLeft + w * 0.075f
        val titleRoom = w - titleColumn - textLeft

        inlay.titles.forEachIndexed { index, track ->
            if (y > listBottom) return@forEachIndexed
            // One line reserved to say how many did not fit — a back inlay that silently
            // stops halfway reads as a rendering bug rather than a long album.
            val remaining = inlay.titles.size - index
            if (y + lineH > listBottom && remaining > 1) {
                canvas.drawText("+$remaining MORE", numColumn, y, numPaint)
                y += lineH
                return@forEachIndexed
            }
            canvas.drawText("%02d".format(index + 1), numColumn, y, numPaint)
            canvas.drawText(
                TextUtils.ellipsize(
                    track, trackPaint, titleRoom, TextUtils.TruncateAt.END
                ).toString(),
                titleColumn,
                y,
                trackPaint
            )
            y += lineH
        }

        // The fine print, bottom right, where a real inlay carries its barcode.
        //
        // **This used to be a barcode, and the barcode was nonsense.** Bar widths were
        // generated from the album id — consistent nonsense rather than random nonsense, but
        // still a block of ink that looked like information and was not. On a shelf of your
        // own records the one thing you cannot see at a glance is how long an album runs and
        // what it actually is, so that is what the space says now.
        //
        // Set as printed matter rather than as UI: small, mono, right-aligned, the way a
        // catalogue number and a runtime sit on the back of a real case.
        val printPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.mono
            color = LABEL_DIM
            textSize = h * 0.036f
            textAlign = Paint.Align.RIGHT
        }
        val printRight = w - textLeft
        var printY = h - textLeft

        // Bottom line first, working up: the format is the least important of the three and
        // the one most likely to be missing.
        inlay.formatLabel?.let {
            canvas.drawText(it.uppercase(), printRight, printY, printPaint)
            printY -= printPaint.textSize * 1.45f
        }

        printPaint.color = LABEL_CREAM
        val runtime = formatRuntime(inlay.totalMs)
        val count = inlay.titles.size
        canvas.drawText(
            "$count ${if (count == 1) "TRACK" else "TRACKS"}${runtime?.let { "  ·  $it" } ?: ""}",
            printRight,
            printY,
            printPaint
        )

        return bitmap
    }

    fun bakeSpine(
        albumId: String,
        title: String,
        artist: String,
        hue: Float,
        fonts: Fonts,
        /**
         * How played-in this case looks, `0f`..`1f` — see [Patina]. Drawn rather than
         * shaded: real shelf wear lands in particular places (the edge a thumb hooks, the
         * corners that rub the board) and a uniform darkening reads as a lighting bug.
         */
        wear: Float = 0f
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

        // ---- Wear -------------------------------------------------------------------
        //
        // Seeded per album so a re-bake looks identical rather than shimmering, and so a case
        // that crosses a `Patina` bucket ages *further* rather than rearranging its dirt.
        //
        // The base grain was here before and was the same on every case, which made it
        // texture rather than history. It stays as the floor — a pressed CD is not optically
        // clean even new — and everything below it is earned.
        val random = Random(albumId.hashCode())
        val speck = Paint()
        repeat(180 + (wear * 340f).toInt()) {
            speck.color = Color.argb(
                (random.nextFloat() * (23f + wear * 34f)).toInt(), 0, 0, 0
            )
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            canvas.drawRect(x, y, x + 2f, y + 2f, speck)
        }

        if (wear > 0f) {
            // The pull edge. A case comes off a shelf by hooking the outer edge of the spine,
            // so that strip loses its print first and goes pale where the ink has gone rather
            // than dark. This is the single most recognisable thing about a shelf-worn case.
            val scuff = Paint(Paint.ANTI_ALIAS_FLAG)
            val pullBand = w * 0.16f
            repeat((wear * 26f).toInt()) {
                val y = random.nextFloat() * h
                val len = h * (0.008f + random.nextFloat() * 0.05f)
                scuff.color = Color.argb(
                    (16f + random.nextFloat() * 40f * wear).toInt(), 246, 238, 222
                )
                canvas.drawRect(
                    random.nextFloat() * pullBand, y,
                    random.nextFloat() * pullBand + w * 0.05f, y + len,
                    scuff
                )
            }

            // Shelf rub. The ends carry the case's weight against the board and against its
            // neighbours, so the corners lighten before the middle of the spine does.
            val rub = Paint(Paint.ANTI_ALIAS_FLAG)
            rub.color = Color.argb((wear * 46f).toInt(), 248, 240, 226)
            val rubDepth = h * 0.022f
            canvas.drawRect(0f, 0f, w, rubDepth, rub)
            canvas.drawRect(0f, h - rubDepth, w, h, rub)

            // The printed bands go first, because they are ink on a laminate rather than part
            // of the stock. Knocking them back is what stops a worn case reading as a clean
            // case with dirt thrown at it.
            val fade = Paint()
            fade.color = Color.argb(
                (wear * 58f).toInt(),
                Color.red(LABEL_CREAM), Color.green(LABEL_CREAM), Color.blue(LABEL_CREAM)
            )
            canvas.drawRect(0f, h * BAR_TOP, w, h * (BAR_TOP + BAR_THICKNESS), fade)
            canvas.drawRect(0f, h * BAR_BOTTOM, w, h * (BAR_BOTTOM + BAR_THICKNESS), fade)
        }

        // Edge highlight and shadow — what actually reads as thickness on a packed shelf.
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRect(0f, 0f, w * 0.04f, h, paint)
        paint.color = Color.argb(82, 0, 0, 0)
        canvas.drawRect(w * 0.96f, 0f, w, h, paint)

        return bitmap
    }

    /**
     * The album's cover with the case's wear on the plastic over it.
     *
     * **Wear has to be here and not only on the spine.** A shelved case rests at
     * [CaseSlots.RESTING_TURN], which is −90° — cover toward the camera — so the spine is
     * edge-on for a fraction of each turn and, once someone sets the turn speed to zero, is
     * never presented at all. Patina drawn only on the spine is a feature most users would
     * never see.
     *
     * It is also the more honest place. A jewel case is clear polystyrene over printed card:
     * what actually marks with handling is the lid, and the artwork underneath stays pristine.
     * So this scratches the *plastic* — fine bright hairlines catching the lamp, a haze that
     * lifts the blacks, and worn corners — rather than damaging the picture.
     *
     * Returns a new bitmap; the decoded cover is left untouched because the deck shows the
     * same album at full size and a case being worn should not scratch the sleeve on screen.
     */
    fun wearCover(cover: Bitmap, albumId: String, wear: Float): Bitmap {
        if (wear <= 0f) return cover
        val worn = cover.copy(Bitmap.Config.ARGB_8888, true) ?: return cover
        val canvas = Canvas(worn)
        val w = worn.width.toFloat()
        val h = worn.height.toFloat()

        // Offset seed from the spine's, or a case wears in exactly the same pattern on both
        // faces and the repetition reads as a texture rather than as handling.
        val random = Random(albumId.hashCode() * 31 + 7)

        // Scuff haze. Polystyrene clouds before it scratches, and it is what stops a worn
        // cover reading as a clean cover with lines drawn on it.
        val haze = Paint()
        haze.color = Color.argb((wear * 20f).toInt(), 236, 228, 210)
        canvas.drawRect(0f, 0f, w, h, haze)

        // Hairlines. Bright rather than dark: a scratch in clear plastic scatters the lamp
        // back at you, which is why real scratches show up white against dark artwork.
        //
        // **Short and faint on purpose.** The first pass ran them to 42% of the cover width at
        // up to 70 alpha, which at full wear buried the artwork and read as damage rather than
        // as handling — PLAN.md asks for this to be "quietly wonderful", and a record you have
        // loved for forty hours should look loved, not broken. Real scuffing from sliding a
        // case in and out is a scatter of short marks, not long slashes.
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.strokeCap = Paint.Cap.ROUND
        repeat((wear * 20f).toInt()) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            // Loosely aligned, the way wear from sliding in and out of a slot actually lands,
            // rather than uniformly random in angle.
            val angle = (-28f + random.nextFloat() * 56f) * (Math.PI / 180f).toFloat()
            val len = w * (0.025f + random.nextFloat() * 0.13f)
            line.strokeWidth = 0.6f + random.nextFloat() * 0.6f
            line.color = Color.argb(
                (10f + random.nextFloat() * 26f * wear).toInt(), 255, 252, 244
            )
            canvas.drawLine(
                x, y,
                x + len * kotlin.math.cos(angle.toDouble()).toFloat(),
                y + len * kotlin.math.sin(angle.toDouble()).toFloat(),
                line
            )
        }

        // Corners. They take every knock, and on a real case they go cloudy first.
        val corner = Paint(Paint.ANTI_ALIAS_FLAG)
        val reach = w * 0.24f
        for (cx in listOf(0f, w)) {
            for (cy in listOf(0f, h)) {
                corner.shader = android.graphics.RadialGradient(
                    cx, cy, reach,
                    Color.argb((wear * 30f).toInt(), 244, 236, 220),
                    Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, reach, corner)
            }
        }

        return worn
    }

    /** Bytes a cached entry costs, for the cache's capacity policy. */
    fun byteCount(vararg bitmaps: Bitmap?): Int =
        bitmaps.filterNotNull().sumOf { max(it.byteCount, 0) }
}
