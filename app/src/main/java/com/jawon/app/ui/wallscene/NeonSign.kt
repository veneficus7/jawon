package com.jawon.app.ui.wallscene

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The neon JAWON sign on the brick above the rack.
 *
 * The owner picked it from four kinds of wall decor, and a friend's first impression is why it
 * reads what it reads: "i cant see the Jawon". It is decor and a light at once — neon.mat draws
 * the tubes hot enough for the bloom, halo and lens dirt to catch them, and a real point light in
 * front of it washes the brick around it in its colour.
 *
 * **Outline lettering**, the classic shop-window style: the letters of JAWON set in the app's
 * own display face (Archivo, heavy — the face the album titles use), and a tube run along every
 * contour of every letter, so each glyph is drawn by its own outline in light.
 *
 * In each room's accent colour rather than its tube colour, so the sign is the room's own mark:
 * amber in Vesper, cyan in Vigilia, phosphor in Officina — and molten orange in Glacies, which is
 * that room's design exactly: a cold room where the one thing that means something burns hot.
 */
object NeonSign {

    const val TEXT = "JAWON"

    /** The mask's size: wide enough for five letters' contours to stay crisp at this scale. */
    const val MASK_W = 1024
    const val MASK_H = 320

    /**
     * How wide the sign is, in metres: about the width of the frame at the default zoom, so it
     * reads whole when the owner scrolls to the top of the rack. Its height follows the mask.
     */
    const val WIDTH_M = 0.24f
    const val HEIGHT_M = WIDTH_M * MASK_H / MASK_W

    /** How far above the top of the rack's cap the sign's bottom edge sits. */
    const val GAP_ABOVE_CAP_M = 0.035f

    /**
     * How far off the brick it stands — neon is mounted on standoffs, and a few millimetres of
     * air behind it is what lets its own light spread onto the wall around it.
     */
    const val STANDOFF_M = 0.006f

    /** How hot the core burns and how bright the glass glow is (neon.mat's `coreGlow`). */
    const val CORE = 4.0f
    const val GLOW = 1.1f

    /**
     * The light in front of the sign, washing the brick in its colour. Close and short-reached:
     * a sign lights the wall around itself, not the room.
     */
    const val LIGHT_Z_M = 0.05f
    const val LIGHT_CANDELA = 160f
    const val LIGHT_FALLOFF = 0.45f

    /** Tube widths, in mask texels: the hot core, and the glow around it before blurring. */
    private const val CORE_PX = 6f
    private const val GLOW_PX = 22f
    private const val GLOW_BLUR_PX = 16f

    /**
     * The mask neon.mat reads: red is the tube core along every contour of the lettering, green
     * the soft glow in the glass around it. Built on Canvas, so off the main thread — it is a
     * blur over a third of a megapixel.
     */
    fun mask(face: Typeface): Bitmap {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            // Heavy: thick letters give their inner and outer contours room apart, so the two
            // tubes of each stroke read as two tubes.
            fontVariationSettings = "'wght' 800"
            textSize = 100f
        }
        // Size the lettering to 86% of the mask's width, then centre it.
        text.textSize = 100f * (MASK_W * 0.86f) / text.measureText(TEXT)
        val outline = Path()
        val x = (MASK_W - text.measureText(TEXT)) / 2f
        val baseline = MASK_H / 2f - (text.ascent() + text.descent()) / 2f
        text.getTextPath(TEXT, 0, TEXT.length, x, baseline, outline)

        fun stroke(width: Float, blur: Float): IntArray {
            val bitmap = Bitmap.createBitmap(MASK_W, MASK_H, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = AndroidColor.WHITE
                if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            Canvas(bitmap).drawPath(outline, paint)
            val pixels = IntArray(MASK_W * MASK_H)
            bitmap.getPixels(pixels, 0, MASK_W, 0, 0, MASK_W, MASK_H)
            bitmap.recycle()
            return pixels
        }

        val core = stroke(CORE_PX, 0f)
        val glow = stroke(GLOW_PX, GLOW_BLUR_PX)
        val combined = IntArray(MASK_W * MASK_H) { i ->
            AndroidColor.argb(255, AndroidColor.alpha(core[i]), AndroidColor.alpha(glow[i]), 0)
        }
        return Bitmap.createBitmap(combined, MASK_W, MASK_H, Bitmap.Config.ARGB_8888)
    }

    /** The room's accent, as the linear colour neon.mat and the light take. */
    fun linear(accent: Color): FloatArray =
        floatArrayOf(accent.red.pow(2.2f), accent.green.pow(2.2f), accent.blue.pow(2.2f))
}
