package com.jawon.app.ui.wallscene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.google.android.filament.Texture
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The paper on the brick: **two-colour screen-printed promos for the owner's own records.**
 *
 * His pick, from four kinds of wall decor offered, alongside the framed albums and the neon
 * sign. A record shop's wall is not a gallery — it is paper, taped up crooked, overlapping,
 * printed cheaply in one ink on one stock — and that is deliberately a different texture from
 * [WallDecor]'s framed prints hanging in the same band. A wall with only frames on it reads as
 * a hotel corridor.
 *
 * ## What is printed, and what is not
 *
 * The artist, the title, and a duotone of the record's own cover. **No dates and no venues.**
 * A gig poster would want both, and both would be invented: these are real artists, and a
 * poster reading "LIVE AT THE FORUM, 12 MARCH" is a fact about a real person that this app
 * would have made up to decorate a wall. A label's promo poster for a release needs neither,
 * says the same thing, and is the commoner thing to find on a shop wall anyway.
 *
 * ## Why it is drawn rather than composed
 *
 * `ENGINEERING.md`'s architecture rule 6 sends in-world *UI* to SceneView's composable-as-texture,
 * and shelf label plates are the example it gives. A poster is decor, not UI: nothing here is
 * read, tapped or updated, it is baked once per album and handed over as a texture, and one
 * Android `View` per poster hung on a wall would be all of the cost of that mechanism and none
 * of its point. [NeonSign] draws its lettering the same way and for the same reason.
 */
object GigPoster {

    /** The paper, in pixels. Portrait, close to the √2 of an A-series sheet. */
    const val PX_W = 512
    const val PX_H = 724

    /** How big a sheet is in the room. A shop poster is bigger than a framed 12" sleeve. */
    const val WIDTH_M = 0.185f
    const val HEIGHT_M = WIDTH_M * PX_H / PX_W

    /** How far the paper stands off the brick. It is taped to it, so barely at all. */
    const val STANDOFF_M = 0.0015f

    /** Printed paper: matt, and a shade less reflective than the framed prints behind glass. */
    const val ROUGHNESS = 0.86f
    const val REFLECTANCE = 0.35f

    /** How far a sheet may be off square, in degrees. Taped by hand, not hung on a wire. */
    const val MAX_TILT_DEG = 3.2f

    /**
     * The stock: a warm off-white, and not paper-white.
     *
     * Cheap poster stock is never white and this room has no white in it anywhere else. It also
     * has to survive being the brightest thing on a dim wall without becoming a lamp, which is
     * what a 0xFF sheet would do under bloom.
     */
    private const val PAPER = 0xFFD8CEB8.toInt()

    /** The ink, before the album's hue is mixed in: a dense near-black with some warmth. */
    private const val INK = 0xFF1A1612.toInt()

    /**
     * How much of the album's own colour the ink takes.
     *
     * A screen print is one ink on one stock, and that ink is what carries the record's colour
     * onto the wall. Kept well short of the full hue: at full strength every poster on the wall
     * is a different saturated colour and the brick behind them stops being a room.
     */
    private const val INK_HUE = 0.55f

    /**
     * Bakes one poster.
     *
     * [cover] may be null — a record with no artwork still gets a poster, and a typographic one
     * is what a label prints when the sleeve is not ready.
     */
    fun bake(
        artist: String,
        title: String,
        hue: Float,
        cover: Bitmap?,
        display: Typeface?,
        mono: Typeface?
    ): Bitmap {
        val sheet = Bitmap.createBitmap(PX_W, PX_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val ink = inkFor(hue)

        canvas.drawColor(PAPER)

        // ---- the image, as a duotone ------------------------------------------------------
        //
        // One ink means the cover cannot be printed as it is: it is mapped from its own
        // luminance onto the line between stock and ink, which is exactly what a duotone print
        // does and what makes a wall of these read as printed rather than as photographs.
        val imageTop = (PX_H * 0.085f).roundToInt()
        val imageSize = (PX_W * 0.83f).roundToInt()
        val imageLeft = (PX_W - imageSize) / 2
        if (cover != null) {
            val plate = duotone(cover, ink, imageSize)
            canvas.drawBitmap(plate, imageLeft.toFloat(), imageTop.toFloat(), null)
            plate.recycle()
        } else {
            // No sleeve: a solid block of ink, which is what the type sits on instead.
            val block = Paint().apply { color = ink; isAntiAlias = true }
            canvas.drawRect(
                imageLeft.toFloat(), imageTop.toFloat(),
                (imageLeft + imageSize).toFloat(), (imageTop + imageSize).toFloat(), block
            )
        }

        // ---- the rule ---------------------------------------------------------------------
        val ruleY = imageTop + imageSize + PX_H * 0.038f
        val rule = Paint().apply {
            color = ink
            strokeWidth = PX_H * 0.006f
            isAntiAlias = true
        }
        canvas.drawLine(
            imageLeft.toFloat(), ruleY, (imageLeft + imageSize).toFloat(), ruleY, rule
        )

        // ---- the artist, which is the whole point of the poster ----------------------------
        val name = artist.uppercase().ifBlank { title.uppercase() }
        val heavy = Paint().apply {
            color = ink
            isAntiAlias = true
            typeface = display?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val maxWidth = imageSize.toFloat()
        val lines = wrap(name, heavy, maxWidth, fitSize(name, heavy, maxWidth))
        var y = ruleY + PX_H * 0.072f
        lines.forEach { line ->
            canvas.drawText(line, imageLeft.toFloat(), y, heavy)
            y += heavy.textSize * 0.96f
        }

        // ---- the record ---------------------------------------------------------------------
        val small = Paint().apply {
            color = ink
            alpha = 0xC0
            isAntiAlias = true
            typeface = mono ?: Typeface.MONOSPACE
            textSize = PX_H * 0.0235f
            letterSpacing = 0.18f
        }
        val record = title.uppercase()
        canvas.drawText(
            ellipsise(record, small, maxWidth), imageLeft.toFloat(),
            y + PX_H * 0.012f, small
        )

        grain(canvas, ink)
        return sheet
    }

    /**
     * The ink for an album: the base ink pulled [INK_HUE] of the way toward its own colour.
     *
     * Packed by hand rather than through `android.graphics.Color`, and that is not a
     * micro-optimisation: every static of this object initialises when the class loads, and
     * one call into Android's `Color` there makes the whole file untestable on the JVM with
     * `ExceptionInInitializerError`, which is how this was found. The colour arithmetic is
     * ordinary arithmetic; only the drawing below needs a device.
     */
    fun inkFor(hue: Float): Int {
        val rgb = beamRgb(hue)
        val peak = max(max(rgb[0], rgb[1]), rgb[2]).coerceAtLeast(1e-4f)
        fun channel(base: Int, value: Float): Int {
            val tinted = base + (value / peak * 255f - base) * INK_HUE
            return tinted.roundToInt().coerceIn(0, 255)
        }
        return argb(
            channel(red(INK), rgb[0]),
            channel(green(INK), rgb[1]),
            channel(blue(INK), rgb[2])
        )
    }

    // ---- colour, without Android ------------------------------------------------------------
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * A cover reduced to one ink on the stock.
     *
     * Its own luminance picks a point between paper and ink, with the ends pushed apart a
     * little so a print keeps some contrast rather than turning to mud — a real duotone is
     * separated the same way before it goes on press.
     */
    private fun duotone(cover: Bitmap, ink: Int, size: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(cover, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== cover) scaled.recycle()

        val pr = red(PAPER)
        val pg = green(PAPER)
        val pb = blue(PAPER)
        val ir = red(ink)
        val ig = green(ink)
        val ib = blue(ink)

        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = (0.2126f * red(p) + 0.7152f * green(p) + 0.0722f * blue(p)) / 255f
            // Separated: the darkest fifth goes to solid ink and the lightest fifth to bare
            // stock, so the middle gets the whole of the range instead of a third of it.
            val t = ((1f - lum) - 0.18f) / 0.64f
            val k = t.coerceIn(0f, 1f)
            pixels[i] = argb(
                (pr + (ir - pr) * k).roundToInt(),
                (pg + (ig - pg) * k).roundToInt(),
                (pb + (ib - pb) * k).roundToInt()
            )
        }
        out.setPixels(pixels, 0, size, 0, 0, size, size)
        return out
    }

    /**
     * Paper grain, and a shadow of ink along two edges.
     *
     * A flat fill is the one thing that reads as a computer rather than as a sheet of paper.
     * Deterministic from the pixel index rather than random: the same album prints the same
     * poster every launch, which matters because the wall is rebuilt on every recomposition.
     */
    private fun grain(canvas: Canvas, ink: Int) {
        val speck = Paint().apply { isAntiAlias = false }
        var seed = 0x9E3779B9.toInt()
        repeat(GRAIN_SPECKS) {
            seed = seed * 1664525 + 1013904223
            val x = (seed ushr 8 and 0x1FF) % PX_W
            seed = seed * 1664525 + 1013904223
            val y = (seed ushr 8 and 0x3FF) % PX_H
            seed = seed * 1664525 + 1013904223
            val dark = (seed ushr 16 and 0x1F) < 16
            speck.color = if (dark) ink else 0xFFFFFFFF.toInt()
            speck.alpha = 10 + (seed ushr 4 and 0x0F)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1.6f, y + 1.6f, speck)
        }
    }

    private const val GRAIN_SPECKS = 2600

    /** The largest size at which [text] fits [maxWidth] in at most two lines. */
    private fun fitSize(text: String, paint: Paint, maxWidth: Float): Float {
        var size = PX_H * 0.082f
        while (size > PX_H * 0.030f) {
            paint.textSize = size
            if (wrap(text, paint, maxWidth, size).size <= 2) return size
            size *= 0.94f
        }
        return size
    }

    /** Greedy word wrap. A word longer than the measure is left to overhang rather than cut. */
    fun wrap(text: String, paint: Paint, maxWidth: Float, size: Float): List<String> {
        paint.textSize = size
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf(text)
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                lines += line.toString()
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** Cuts [text] to fit, with an ellipsis, the way the tracklist does. */
    fun ellipsise(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1) {
            val candidate = text.take(end).trimEnd() + "…"
            if (paint.measureText(candidate) <= maxWidth) return candidate
            end--
        }
        return text.take(1)
    }

    /**
     * How far off square this album's sheet is taped, in degrees.
     *
     * From the album id so a poster does not twitch between recompositions, and signed so the
     * wall leans both ways. A wall of sheets all tilted the same way reads as a mistake in the
     * layout rather than as tape.
     */
    fun tiltFor(albumId: String): Float {
        var h = 7
        albumId.forEach { h = h * 31 + it.code }
        val unit = (abs(h) % 1000) / 1000f * 2f - 1f
        return unit * MAX_TILT_DEG
    }

    /** Where sheets hang between [left] and [right], offset from the frames' own grid. */
    fun slots(left: Float, right: Float): List<Float> =
        WallDecor.slots(left, right).map { it + WallDecor.PITCH_M / 2f }
            .filter { it + WIDTH_M / 2f <= right && it - WIDTH_M / 2f >= left }
            .filter { abs(it) >= WallDecor.CLEAR_OF_SIGN }

    private fun textBounds(paint: Paint, text: String): Rect =
        Rect().also { paint.getTextBounds(text, 0, min(text.length, 64), it) }
}

/** One printed sheet: which album it is for, and the paper's texture. */
class Poster(val albumId: String, val texture: Texture)

/**
 * Tapes [posters] along the wall at [bandY], repeating them if the wall is longer than the list.
 *
 * Each sheet takes its own MaterialInstance — a PlaneNode destroys the one it is handed — and
 * they share their textures, which nothing here destroys.
 */
@Composable
fun SceneScope.GigPosters(
    posters: List<Poster>,
    materialLoader: MaterialLoader,
    positions: List<Float>,
    bandY: Float,
    wallZ: Float
) {
    if (posters.isEmpty()) return
    positions.forEachIndexed { index, x ->
        val poster = posters[index % posters.size]
        key("poster", index, poster.albumId) {
            val paper = remember(index, poster.albumId) {
                materialLoader.createTextureInstance(
                    poster.texture,
                    metallic = 0f,
                    roughness = GigPoster.ROUGHNESS,
                    reflectance = GigPoster.REFLECTANCE
                )
            }
            PlaneNode(
                size = Size(GigPoster.WIDTH_M, GigPoster.HEIGHT_M, 0f),
                normal = Direction(z = 1f),
                materialInstance = paper,
                position = Position(x, bandY, wallZ + GigPoster.STANDOFF_M),
                apply = {
                    // Taped by hand, so never quite square. See GigPoster.tiltFor.
                    rotation = Rotation(0f, 0f, GigPoster.tiltFor(poster.albumId))
                    isTouchable = false
                    // Paper on a wall casts almost nothing and the wall washers are the only
                    // thing near it; a shadow map spent here would buy a millimetre of dark.
                    isShadowCaster = false
                    isShadowReceiver = true
                }
            )
        }
    }
}
