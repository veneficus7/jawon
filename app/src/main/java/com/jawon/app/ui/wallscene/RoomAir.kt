package com.jawon.app.ui.wallscene

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the air and the lens do to the room's light: the haze under each strip, and the dirt
 * on the glass the whole room is seen through.
 *
 * **Both are fakes, and the reasons are specific to this engine.** The owner asked for "real
 * fog", lens dirt and an anamorphic lens. Filament has no volumetric lighting for point or spot
 * lights — its fog is a distance veil, and its only in-scattering is for a directional sun the
 * room does not have — so light hanging in the air under a strip has to be drawn. And
 * `BloomOptions.anamorphism` exists on the Java class but is **not forwarded** by
 * `setBloomOptions` in filament-android 1.72.1 (checked in the bytecode: eighteen fields reach
 * `nSetBloomOptions`, and neither `anamorphism` nor `quality` is among them), so the one knob
 * for a stretched, anamorphic bloom does nothing here. Lens dirt *is* forwarded — `dirt` and
 * `dirtStrength` both — which is why that one is real: [dirt] is only the texture.
 */
object RoomAir {

    // ---- Lens dirt ---------------------------------------------------------------------

    /**
     * The dirt texture's size. Portrait, at the test device's own aspect (1440x3120), because
     * Filament stretches it over the whole frame: a square texture would squash every smudge
     * into an ellipse. A third of the panel's resolution — nothing on it is sharp.
     */
    const val DIRT_W = 480
    const val DIRT_H = 1040

    /** Fixed, so the smudges are in the same place on every launch, like a real lens. */
    private const val DIRT_SEED = 0x1E45

    /**
     * What clean glass is in the texture: 0.4, so that times `RoomAtmosphere.LENS_DIRT_STRENGTH`
     * (2.5) the bloom passes at exactly its own strength, and a mark at full can lift it to
     * two and a half times. See [dirt] for why this cannot be zero.
     */
    const val DIRT_CLEAN = 0.4f

    /**
     * A lens that has been handled: soft smudges, a few wipe marks, dust specks and the odd
     * out-of-focus ring, over a flat mid-grey.
     *
     * **Filament multiplies the bloom by this — it does not add it.** The first version was
     * black with marks on it, on the reading that dirt adds to the glow where it sits, and it
     * erased the room's bloom everywhere the lens was clean: measured on the owner's phone, the
     * cyan halo beside Vigilia's tube went from (0, 79, 154) to (1, 13, 24), and the one glow
     * left was a smudge floating over the shelf. So the clean glass is [DIRT_CLEAN], which times
     * `LENS_DIRT_STRENGTH` in `RoomAtmosphere` is exactly 1 — the bloom passes untouched — and
     * each mark rises above it, so a strip's glow picks out every mark on the lens in front of it.
     * Keep the two numbers' product at 1, or the whole room's glow moves with it.
     *
     * Built feature by feature over each feature's own bounding box, not per pixel over every
     * feature: half a million pixels times two hundred features would be a hundred million
     * evaluations at startup, and this is a few hundred thousand.
     */
    fun dirt(): Bitmap {
        val w = DIRT_W
        val h = DIRT_H
        val v = FloatArray(w * h)
        val rnd = Random(DIRT_SEED)

        fun splat(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float, amp: Float, ring: Float) {
            val reach = maxOf(rx, ry) * 1.6f + 2f
            val x0 = (cx - reach).toInt().coerceIn(0, w - 1)
            val x1 = (cx + reach).toInt().coerceIn(0, w - 1)
            val y0 = (cy - reach).toInt().coerceIn(0, h - 1)
            val y1 = (cy + reach).toInt().coerceIn(0, h - 1)
            val c = cos(angle)
            val s = sin(angle)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val u = (dx * c + dy * s) / rx
                val q = (-dx * s + dy * c) / ry
                val d = kotlin.math.sqrt(u * u + q * q)
                val f = if (ring > 0f) {
                    // A ring: bright at d = 1, a faint disc inside — out-of-focus dust.
                    exp(-((d - 1f) / ring).pow(2)) + 0.25f * exp(-(d * d) * 1.2f)
                } else {
                    exp(-d * d * 2.5f)
                }
                v[y * w + x] += amp * f
            }
        }

        // Smudges: the fingertip marks, soft and broad.
        repeat(26) {
            val r = 18f + rnd.nextFloat() * 72f
            splat(rnd.nextFloat() * w, rnd.nextFloat() * h, r, r * (0.7f + rnd.nextFloat() * 0.6f),
                rnd.nextFloat() * 3.1416f, 0.10f + rnd.nextFloat() * 0.35f, 0f)
        }
        // Wipe marks: long and thin, where a cloth dragged across.
        repeat(10) {
            splat(rnd.nextFloat() * w, rnd.nextFloat() * h, 40f + rnd.nextFloat() * 120f,
                5f + rnd.nextFloat() * 14f, rnd.nextFloat() * 3.1416f,
                0.06f + rnd.nextFloat() * 0.18f, 0f)
        }
        // Out-of-focus rings.
        repeat(6) {
            val r = 14f + rnd.nextFloat() * 26f
            splat(rnd.nextFloat() * w, rnd.nextFloat() * h, r, r, 0f,
                0.10f + rnd.nextFloat() * 0.18f, 0.10f)
        }
        // Specks: dust sitting on the glass, small and sharp.
        repeat(140) {
            val r = 0.8f + rnd.nextFloat() * 1.7f
            splat(rnd.nextFloat() * w, rnd.nextFloat() * h, r, r, 0f,
                0.35f + rnd.nextFloat() * 0.65f, 0f)
        }

        val pixels = IntArray(w * h) {
            val g = ((DIRT_CLEAN + (1f - DIRT_CLEAN) * v[it]).coerceIn(0f, 1f) * 255f).toInt()
            AndroidColor.rgb(g, g, g)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- Haze -----------------------------------------------------------------------------

    /**
     * How far down a strip's haze reaches before it has faded to a third, in metres.
     *
     * Short. In a hazy shop the air lights up most right under the strip and has thinned out
     * well before the records' tops, so the glow reads as belonging to the tube; a long haze
     * turns the whole compartment into a lit box, which is the lightbox look the gradient was
     * built to get away from.
     */
    const val HAZE_FALLOFF_M = 0.045f

    /**
     * The two sheets of lit air in each compartment: one in the gap behind the records, tall
     * and denser, and one near the front of the shelf, short and faint.
     *
     * Two layers at different depths is what makes it read as a volume rather than a painted
     * gradient: when the wall pans, the front sheet slides against the back one, and the records
     * standing between them cut the back one off where they stand. The front sheet is kept to
     * the band above the records so it veils the air, not the artwork — covers washed grey is a
     * complaint this room has already had once.
     */
    const val HAZE_BACK_Z = WallDimens.WALL_Z + 0.024f
    const val HAZE_FRONT_Z = WallDimens.BOARD_D / 2f - 0.008f

    /** Peak opacity of each sheet, right under the strip. */
    const val HAZE_BACK_ALPHA = 0.20f
    const val HAZE_FRONT_ALPHA = 0.09f

    /** How tall the front sheet is: the band of air above the records, and a little into it. */
    const val HAZE_FRONT_H = 0.060f

    /**
     * How tall the back sheet is: from the underside of the tube all the way down to the board
     * the records stand on. Its texture fades out long before the bottom — see
     * [HAZE_FALLOFF_M] — but the sheet reaches the board so there is never a visible lower edge.
     */
    const val HAZE_BACK_H =
        WallDimens.CASE_H + RoomLighting.SHELF_LIGHT_LIFT - RoomLighting.STRIP_H / 2f

    /** Texture size. Horizontally uniform, so a sliver of width is enough. */
    private const val HAZE_W = 8
    private const val HAZE_H = 256

    /**
     * One sheet of lit air in [tubeLight]'s colour: opaque-ish at the top edge, fading down.
     *
     * [heightM] is the physical height the texture will be stretched over, so the falloff is in
     * metres whichever sheet it is. Horizontally uniform, deliberately: the sheets span the whole
     * rack, and any figure in them would repeat identically on every shelf.
     *
     * The colour goes in as sRGB because the texture is uploaded as colour; `tubeLight` is
     * linear. Android bitmaps are premultiplied, which is what Filament's transparent blending
     * expects, so the alpha needs no special handling.
     */
    fun haze(tubeLight: FloatArray, peakAlpha: Float, heightM: Float): Bitmap {
        val r = srgb(tubeLight[0])
        val g = srgb(tubeLight[1])
        val b = srgb(tubeLight[2])
        val pixels = IntArray(HAZE_W * HAZE_H)
        for (y in 0 until HAZE_H) {
            val depth = (y + 0.5f) / HAZE_H * heightM
            // A ramp in over the first millimetre and a half, so the sheet has no hard top edge
            // against the tube — short, because the air right under a light is the brightest
            // air there is. At 4mm this left the haze empty exactly where the old dark seam
            // under the tube was, and the two together read as a gap between fixture and glow.
            val ramp = (depth / 0.0015f).coerceAtMost(1f)
            val a = (peakAlpha * ramp * exp(-depth / HAZE_FALLOFF_M)).coerceIn(0f, 1f)
            val alpha = (a * 255f).toInt()
            val argb = AndroidColor.argb(alpha, r, g, b)
            for (x in 0 until HAZE_W) pixels[y * HAZE_W + x] = argb
        }
        return Bitmap.createBitmap(pixels, HAZE_W, HAZE_H, Bitmap.Config.ARGB_8888)
    }

    private fun srgb(linear: Float): Int =
        (linear.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f).toInt()
}
