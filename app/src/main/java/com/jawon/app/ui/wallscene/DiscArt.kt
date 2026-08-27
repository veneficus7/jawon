package com.jawon.app.ui.wallscene

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient

/**
 * The printed face of the CD, baked to a bitmap.
 *
 * Why baked rather than shaded: the printed face is artwork, so it is drawn into pixels —
 * the same approach [CaseArt] already takes for spines and covers.
 *
 * **The geometry is the mask, so nothing here needs alpha.** The disc model laid down by
 * `tools/models/build_models.py` carries a planar projection from the front, normalised
 * over the disc's bounding square — the same convention the hand-built `DiscGeometry` used
 * before it, so this bakery did not have to change when the geometry did. The UVs trace the
 * circle inscribed in this square between [R_HOLE] and the rim; the bitmap's corners and
 * its centre are never sampled, because the disc's real centre hole is a hole in the mesh.
 * That is why an opaque material works and transparency sorting can be skipped entirely.
 *
 * All CPU bitmap work, so it runs off the main thread (ENGINEERING.md rule 5).
 */
object DiscArt {

    /**
     * Every disc face is baked to exactly this, square. Fixed size is not just tidiness —
     * the face is uploaded into one texture that is allocated once and reused for every
     * album, so the dimensions can never change. See the disc texture in `WallSceneScreen`.
     *
     * **1024, up from 512.** Once a case is open the disc is the largest thing on screen —
     * it spans most of the frame's width on a phone — so it is the one texture in the room
     * that is looked at closely. 512 across a screen-filling circle is roughly half a device
     * pixel per texel, and the artwork visibly softened.
     *
     * One texture at 4MB plus its mip chain, for the single album that is open. The wall's
     * hundreds of covers stay small; this is the exception that earns the memory.
     */
    const val DISC_PX = 1024

    // Fractions of the bitmap's half-width, matching a real CD (120mm disc, 15mm hole,
    // ~46mm printable inner edge, ~33mm clamping ring).
    //
    // [R_HOLE] is not drawn — it is where the mesh's own hole begins, and it matches
    // `DISC_HOLE_RADIUS / DISC_RADIUS` by construction. Anything inside it is never sampled.
    private const val R_OUTER = 1.0f
    private const val R_LABEL_INNER = 0.29f
    private const val R_CLAMP_INNER = 0.185f
    private const val R_HOLE = 0.125f

    private val PLASTIC = Color.rgb(0xC2, 0xC8, 0xCE)
    private val PLASTIC_DIM = Color.rgb(0x8A, 0x92, 0x9A)
    private val CLAMP_RING = Color.rgb(0xD6, 0xDB, 0xE0)

    /**
     * @param face the user's uploaded disc-face image, already decoded
     * @param fallback the case cover, used when no disc face was uploaded
     */
    fun bakeFace(face: Bitmap?, fallback: Bitmap?): Bitmap {
        val size = DISC_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // The unprinted plastic: everything from the clamping ring out to the rim. A CD is
        // silver here, not white, and slightly darker toward the edge.
        paint.shader = RadialGradient(
            c, c, c * R_OUTER,
            intArrayOf(CLAMP_RING, PLASTIC, PLASTIC_DIM),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(c, c, c * R_OUTER, paint)
        paint.shader = null

        // A faint sweep, so the bare disc reads as reflective rather than as a flat grey
        // coin. Deliberately colourless and very low contrast — the iridescent version of
        // this was removed because the rainbow was not wanted.
        paint.shader = SweepGradient(
            c, c,
            intArrayOf(0x18FFFFFF, 0x06FFFFFF, 0x14FFFFFF, 0x04FFFFFF, 0x18FFFFFF),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        )
        canvas.drawCircle(c, c, c * R_OUTER, paint)
        paint.shader = null

        // The printed label.
        val art = face ?: fallback
        if (art != null && art.width > 0 && art.height > 0) {
            drawLabel(canvas, art, c, c * R_OUTER)
        }

        // The clamping ring and the clear plastic inside it — unprinted on every real disc,
        // so the face reads as a CD even when the user's art is a full-bleed square.
        paint.color = CLAMP_RING
        canvas.drawCircle(c, c, c * R_LABEL_INNER, paint)
        paint.color = PLASTIC_DIM
        canvas.drawCircle(c, c, c * R_CLAMP_INNER, paint)

        return bitmap
    }

    /** Contrast and saturation applied to a label as it is printed — see [drawLabel]. */
    private const val LABEL_CONTRAST = 1.22f
    private const val LABEL_SATURATION = 1.16f

    /**
     * A contrast matrix that pivots about mid-grey, so raising contrast brightens the top half
     * and darkens the bottom half rather than shifting the whole image.
     */
    private fun contrastMatrix(scale: Float): FloatArray {
        val shift = 127.5f * (1f - scale)
        return floatArrayOf(
            scale, 0f, 0f, 0f, shift,
            0f, scale, 0f, 0f, shift,
            0f, 0f, scale, 0f, shift,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /**
     * Draws [art] centre-cropped into the label annulus.
     *
     * The label is the *ring* between the clamping ring and the rim, so the image is drawn
     * over the whole disc and the caller then lays its clamping ring over the middle —
     * cheaper and sharper than building a ring-shaped clip path.
     */
    private fun drawLabel(canvas: Canvas, art: Bitmap, c: Float, outer: Float) {
        val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // Centre-crop: scale by the larger ratio so the shorter side fills the disc.
        val scale = (outer * 2f) / minOf(art.width, art.height).toFloat()
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    c - art.width * scale / 2f,
                    c - art.height * scale / 2f
                )
            }
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            // Printed, not displayed.
            //
            // The label is a lit, glossy surface in a dim room: the lamp's specular sheen
            // lifts its blacks and the warm ambient pulls everything toward the room's own
            // amber, so artwork that looks right as a flat image arrives on the disc washed
            // out and low-contrast. Pushing contrast and saturation in the bake is the cheap
            // half of the answer — it costs one filter on a bitmap drawn once per album,
            // where the alternative is fighting the material and the lighting.
            //
            // Held modest on purpose. This is a correction for what the room does to the
            // image, not a look: crushed blacks on a real CD label would read as a printing
            // fault rather than as contrast.
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(LABEL_SATURATION)
                    postConcat(ColorMatrix(contrastMatrix(LABEL_CONTRAST)))
                }
            )
        }
        canvas.drawCircle(c, c, outer, paint)
    }
}
