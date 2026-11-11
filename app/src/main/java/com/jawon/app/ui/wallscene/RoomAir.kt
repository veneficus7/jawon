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

    // ---- Moving fog ---------------------------------------------------------------------

    /**
     * **Off, at the owner's call on 2026-09-12: "just remove fog for now".** A switch, not a
     * deletion, for the reason `Features.kt` gives: the raymarcher, its material and FogAir stay
     * built and tested, and bringing the fog back is this one line.
     *
     * Where it was when it went, so the next attempt starts from the lesson: the first tuning
     * put the room behind a milky veil (a colourful cover at 0.10 saturation), and it was pulled
     * back before anyone saw the second. Keep it sparse, keep extinction low, and remember it
     * is the most expensive thing in the room per pixel — see [QualityTier.fogSteps].
     */
    const val FOG_ENABLED = true

    /**
     * The fog volume, front to back: from just behind where the disc settles when a record is
     * open (FOCUS_DISC puts it about 0.23m out) back to the rack's front plane. The quad the
     * shader draws from hangs at the front; each ray ends at the back.
     */
    const val FOG_FRONT_Z = 0.20f
    const val FOG_BACK_Z = WallDimens.BOARD_D / 2f

    /**
     * How the fog is lit and how much it veils (fog.mat's `gain`): scattering from the strips,
     * from the ceiling spots, and extinction. The fog crosses 13cm of air in front of the rack,
     * so these are large per metre and small over the distance it actually spans.
     *
     * **The first values put the room behind a milky veil**, measured on the owner's phone: a
     * colourful cover at 0.10 saturation, the dark gaps between cases at 82 instead of near
     * black, the air above the rack at 62. The fog was both veiling everything (extinction 3)
     * and adding grey light over it everywhere. Now it veils about a third as much and scatters
     * less, so it reads as light in the air rather than as a filter over the room.
     */
    const val FOG_STRIP_GAIN = 5.0f
    const val FOG_SPOT_GAIN = 2.4f
    const val FOG_EXTINCTION = 1.1f

    /** Overall density: half, with fog.mat's sparser threshold making the gaps between wisps. */
    const val FOG_DENSITY = 0.20f

    // **Back on at 0.20 of its authored 0.5, 2026-09-14.** The owner parked it a day after
    // asking for it — "naah man just remove fog for now" — and has now asked for "a little
    // fog back" to see it against the room's new light. A little is the operative word, and
    // the reason is arithmetic rather than taste: fog scatters whatever is lit, so it and
    // [RoomLighting.AMBIENT_INTENSITY] multiply. Both went up in the same build, and at the
    // authored density the room would go milky rather than atmospheric.

    /**
     * How much of the lamp colour fog far from any fitting scatters. Nearly nothing: fog that
     * glows where there is no light is the grey veil, not haze. The fittings light it.
     */
    const val FOG_AMBIENT = 0.008f

    /** The fog's density texture: tiling value noise, sampled twice at two scales in fog.mat. */
    const val FOG_NOISE_PX = 256
    private const val FOG_NOISE_SEED = 0x0F06

    /**
     * How far, in texels on both axes, the warp's second lookup sits from the first. fog.mat
     * read it as `broadUv.yx + 0.37`, its own texture fetch every step; 95/256 is the nearest whole
     * texel (0.371). Being whole is what makes the green channel exact: with bilinear filtering and
     * no mipmaps (`FOG_SAMPLER`), green read at uv is red read at uv.yx + 95/256, as long as the
     * bitmap's rows reach the GPU in order (SceneView hands it to Filament's TextureHelper). If
     * they were flipped, the fog would show the same noise in a different arrangement.
     */
    const val FOG_WARP_SHIFT_PX = 95

    /**
     * Soft tiling noise for the fog to be made of: two octaves of value noise whose lattices
     * wrap at the texture's edge, so the shader can scroll it forever without a seam.
     *
     * Red is the noise. Green is red transposed and shifted by [FOG_WARP_SHIFT_PX], so fog.mat
     * gets both values its warp needs from one texture read: three reads a step became two
     * (docs/mali/FINDINGS.md). Blue is unused.
     */
    fun fogNoise(): Bitmap {
        val n = FOG_NOISE_PX
        val rg = fogNoiseRg()
        val pixels = IntArray(n * n) { i -> AndroidColor.rgb(rg[2 * i], rg[2 * i + 1], 0) }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
    }

    /**
     * The noise's red and green, 0..255, two values per texel, row by row: `[r0, g0, r1, g1, …]`.
     * Pure, so the green channel's arithmetic is tested without a device (`RoomAirTest`).
     */
    internal fun fogNoiseRg(): IntArray {
        val n = FOG_NOISE_PX
        val red = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val broad = periodic(x / 32f, y / 32f, n / 32, FOG_NOISE_SEED)
            val fine = periodic(x / 12.8f, y / 12.8f, 20, FOG_NOISE_SEED + 1)
            red[y * n + x] = ((broad * 0.7f + fine * 0.3f) * 255f).toInt().coerceIn(0, 255)
        }
        val rg = IntArray(2 * n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            rg[2 * i] = red[i]
            // Transposed: column y, row x, each shifted and wrapped like the texture repeats.
            rg[2 * i + 1] = red[((x + FOG_WARP_SHIFT_PX) % n) * n + (y + FOG_WARP_SHIFT_PX) % n]
        }
        return rg
    }

    private fun periodic(x: Float, y: Float, period: Int, seed: Int): Float {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val sx = xf * xf * (3f - 2f * xf)
        val sy = yf * yf * (3f - 2f * yf)
        fun h(a: Int, b: Int): Float {
            var v = Math.floorMod(a, period) * 374_761_393 + Math.floorMod(b, period) * 668_265_263 +
                seed * 1_274_126_177
            v = (v xor (v shr 13)) * 1_274_126_177
            v = v xor (v shr 16)
            return (v and 0xFFFFFF) / 0xFFFFFF.toFloat()
        }
        val top = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * sx
        val bottom = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * sx
        return top + (bottom - top) * sy
    }
}

/**
 * How stirred the room's air is, and where the fog has drifted to — the "interacting" in the
 * owner's "real moving interacting fog".
 *
 * The fog sheets are part of the room, so a pan already slides them against the rack by
 * parallax. This adds what air does when something moves through it: it is dragged a little
 * the way the view moved, and it churns while the motion lasts, then settles. Two numbers
 * written to fog.mat once a frame; nothing per pixel on the CPU.
 *
 * Plain Kotlin, driven by the frame clock and the pan, so it is tested without a device.
 */
class FogAir {
    /** Accumulated drag, in metres of noise space. Only ever eased, so it never snaps. */
    var windX = 0f
        private set
    var windY = 0f
        private set

    /** 0 = still air, 1 = fully stirred. */
    var stir = 0f
        private set

    private var lastClock = Float.NaN
    private var lastPanX = 0f
    private var lastPanY = 0f

    fun update(clock: Float, panX: Float, panY: Float) {
        if (lastClock.isNaN()) {
            lastClock = clock
            lastPanX = panX
            lastPanY = panY
            return
        }
        val dt = (clock - lastClock).coerceIn(0f, 0.1f)
        lastClock = clock
        if (dt <= 0f) return
        val dx = panX - lastPanX
        val dy = panY - lastPanY
        lastPanX = panX
        lastPanY = panY

        // Drag: the air follows the view a fraction of the way.
        windX += dx * DRAG
        windY += dy * DRAG

        // Stir: how fast the view is moving, against a brisk swipe, eased in fast and out slow
        // — air takes a moment to settle after something has gone through it.
        val speed = kotlin.math.sqrt(dx * dx + dy * dy) / dt
        val target = (speed / BRISK_M_PER_S).coerceIn(0f, 1f)
        val rate = if (target > stir) RISE_PER_S else SETTLE_PER_S
        stir += (target - stir) * (1f - kotlin.math.exp(-rate * dt))
    }

    companion object {
        /** How much of the pan the air is dragged by. */
        const val DRAG = 0.35f

        /** A pan this fast, in metres per second, stirs the air fully. */
        const val BRISK_M_PER_S = 0.6f

        /** How quickly stirring builds, and how slowly it dies away, per second. */
        const val RISE_PER_S = 6f
        const val SETTLE_PER_S = 0.8f
    }
}
