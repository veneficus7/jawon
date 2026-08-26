package com.jawon.app.ui.wallscene

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient

/**
 * The printed face of the CD, baked to a bitmap.
 *
 * Why baked rather than shaded: there is no custom material to draw a label ring with until
 * M8 part 2 brings in the iridescence shader, so the printed face is drawn into pixels —
 * the same approach [CaseArt] already takes for spines and covers.
 *
 * **The geometry is the mask, so nothing here needs alpha.** [DiscGeometry] lays down
 * radial, normalised UVs (`uv = cos0 * r/R * 0.5 + 0.5, …`), which trace the circle
 * inscribed in this square between [R_HOLE] and the rim. The bitmap's corners and its
 * centre are never sampled — the disc's real centre hole is a hole in the mesh. That is why
 * this can use Filament's *opaque* textured material and skip transparency sorting
 * entirely.
 *
 * All CPU bitmap work, so it runs off the main thread (ENGINEERING.md rule 5).
 */
object DiscArt {

    /**
     * Every disc face is baked to exactly this, square. Fixed size is not just tidiness —
     * the face is uploaded into one texture that is allocated once and reused for every
     * album, so the dimensions can never change. See the disc texture in `WallSceneScreen`.
     */
    const val DISC_PX = 512

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

        // A faint sweep, so the bare disc still reads as reflective rather than as a flat
        // grey coin. This is a stand-in: the real diffraction rainbow is M8 part 2's shader.
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(c, c, outer, paint)
    }
}
