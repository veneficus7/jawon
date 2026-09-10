package com.jawon.app.ui.wallscene

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * Gives the room's flat surfaces something for the light to find.
 *
 * ## The problem this exists for
 *
 * The wall was a single `CubeNode` wearing `createColorInstance(scheme.wall, 0f, 0.95f)` — one
 * constant albedo, no variation of any kind, spanning the entire view. A surface like that
 * returns the same radiance at every pixel, so **no amount of lighting or post-processing can
 * make it interesting**: the fog, the bloom, the soft shadows and the ambient occlusion in
 * [RoomAtmosphere] were all running into a flat fill and had nothing to articulate. That is most
 * of what the owner meant by "even the wall is empty brown, nothing aesthetic".
 *
 * ## Why a generated texture and not a hand-written material
 *
 * `docs/materials.md` lists the entire settable parameter set of SceneView 4.32's stock
 * materials, and `opaque_textured` exposes `metallic`, `roughness`, `reflectance` **and a
 * `texture` sampler** — confirmed by reading the `MAT_UIB` chunk of the shipped
 * `opaque_textured.filamat`, which names exactly those four. `MaterialLoader.createTextureInstance`
 * binds it. So an albedo-textured lit surface needs no `matc`, no new `.mat`, and no argument
 * with the locked decision that keeps `dust.mat` the only hand-written one.
 *
 * What that route does *not* give us is a normal map — the stock set has no sampler for one. So
 * this is luminance variation in the albedo only, which is less than a real look-dev pass would
 * want and is the single largest thing on screen right now that costs nothing to fix.
 *
 * ## Why the pattern has to be large and irregular
 *
 * A CubeNode's faces carry 0..1 UVs, and `opaque_textured` has no UV scale parameter, so the
 * texture is **stretched across the whole panel exactly once** — there is no tiling to be had.
 * Two consequences, and both are design constraints rather than limitations:
 *
 * 1. Only *large-scale* content survives. Fine grain would be smeared to nothing across four
 *    metres of wall, so what is drawn here is broad mottling at roughly the scale of a case.
 * 2. It must be **irregular**. A clean symmetric gradient would be baked into wall space and
 *    therefore slide past as the camera pans, reading as a painted stripe rather than as light.
 *    Real walls vary irregularly and the variation stays put, which is exactly what a noise
 *    field in wall space gives for free.
 *
 * The contrast is deliberately very low — a few percent. The aim is to stop the surface being
 * mathematically flat, not to put a pattern on it; anything a viewer could describe as "the
 * texture on the wall" would be too much.
 *
 * ## Everything here is deterministic
 *
 * A seeded hash, never `Math.random`. The wall must be the same wall on every launch: a room
 * whose stains moved between sessions would be subtly wrong in a way nobody could name, and it
 * would also make the generated bitmap impossible to test.
 */
object RoomSurface {

    /**
     * Texture side, in pixels.
     *
     * 512 against content whose finest feature is several centimetres of wall is already more
     * than enough, and it is 1 MB as `ARGB_8888` — built once per scheme, uploaded once, and
     * then owned by the GPU. Raising it would buy detail that the stretch across the panel
     * immediately throws away.
     */
    const val TEX_PX = 512

    /**
     * How many mottle cells the *shorter* side of a surface gets.
     *
     * The long side gets proportionally more — see [cellsFor] — so a cell stays roughly square
     * in world space however wide the library has grown. Six is about one cell per case height,
     * which is the scale at which variation reads as *surface* rather than as pattern.
     */
    const val CELLS_SHORT_SIDE = 6

    /** How far the mottle swings either side of flat, as a fraction. */
    const val WALL_CONTRAST = 0.085f

    /**
     * The floor is allowed more.
     *
     * A floor is walked on: it collects wear in a way a wall above head height does not, and it
     * is seen at a grazing angle where variation compresses toward the horizon. Both want more
     * contrast than the wall to read as the same amount of texture.
     */
    const val FLOOR_CONTRAST = 0.16f

    /** How much darker the floor is than the wall it meets. */
    const val FLOOR_DARKEN = 0.72f

    /**
     * Mottle cell counts for a surface of a given aspect ratio.
     *
     * **This is what stops the texture smearing.** The bitmap is square in pixels but the panel
     * it stretches over is not — a library four shelves wide and one tall makes a panel four
     * times wider than it is high, and a square cell grid would arrive as cells four times wider
     * than tall. Scaling the count along the long axis cancels that exactly.
     */
    fun cellsFor(aspect: Float): Pair<Int, Int> {
        val safe = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        return if (safe >= 1f) {
            (CELLS_SHORT_SIDE * safe).toInt().coerceIn(CELLS_SHORT_SIDE, MAX_CELLS) to
                CELLS_SHORT_SIDE
        } else {
            CELLS_SHORT_SIDE to
                (CELLS_SHORT_SIDE / safe).toInt().coerceIn(CELLS_SHORT_SIDE, MAX_CELLS)
        }
    }

    /**
     * The luminance field, row-major, one value per texel, centred on 1.
     *
     * Pure and returned as plain floats rather than drawn straight into a `Bitmap`, because this
     * is the part with decisions in it: the octave mix, the contrast, and the promise that the
     * mean stays at 1 so a textured surface is no brighter or darker overall than the flat colour
     * it replaces. All of that is assertable on the JVM; the bitmap wrapper around it is not
     * worth testing.
     *
     * Two octaves. The first is the broad patchiness a wall actually has; the second, at a third
     * of the amplitude, keeps the first from looking like a deliberate pattern.
     */
    fun mottle(
        px: Int,
        cellsAcross: Int,
        cellsDown: Int,
        contrast: Float,
        seed: Int
    ): FloatArray {
        val out = FloatArray(px * px)
        val amount = if (contrast.isFinite()) abs(contrast) else 0f
        for (y in 0 until px) {
            val v = y.toFloat() / px
            for (x in 0 until px) {
                val u = x.toFloat() / px
                val broad = valueNoise(u * cellsAcross, v * cellsDown, seed)
                val fine = valueNoise(u * cellsAcross * 3f, v * cellsDown * 3f, seed + 1)
                // Weighted so the pair still spans 0..1 before being centred, which is what keeps
                // the mean at 1 and makes the bound in the test an equality rather than a hope.
                val n = (broad * OCTAVE_BROAD + fine * OCTAVE_FINE)
                out[y * px + x] = 1f + (n - 0.5f) * 2f * amount
            }
        }
        return out
    }

    /**
     * The shelf's **back panel**: sawn boards, running vertically, with grain.
     *
     * ## Why this replaced the mottle
     *
     * The owner's word for the room was that it had a wall with floating shelves in front of it
     * rather than a cupboard — *shkaf* — and he was right. A record rack is a carcass: boards,
     * and a back panel of real material that the shelf lights fall on. What was here was a few
     * percent of irregular luminance noise, which is enough to stop a surface being
     * mathematically flat and nowhere near enough to say what it is made of. Light landing on it
     * read as a glow in space.
     *
     * ## The stretch, and why tiling is the whole answer
     *
     * `docs/materials.md` records that a `CubeNode`'s faces carry 0..1 UVs and `opaque_textured`
     * has no UV scale, so a texture is stretched across a panel exactly once and **there is no
     * tiling to be had**. That is true of one node and it is not true of a wall.
     *
     * The measurement that forced this: the camera sees about **26cm** of wall across a portrait
     * phone, while the backing panel is nearly two metres wide. One 512px texture over that is
     * roughly 77 texels across 1440 screen pixels — one texel per nineteen pixels — so any
     * detail is a blur before it is drawn. Proved rather than assumed, by replacing the pattern
     * with hard black-and-white stripes and counting how many reached the screen: one and a half.
     *
     * So the panel is a **grid of nodes sharing one texture**, each node a tile of about
     * [TILE_M] on a side. Each tile gets the full 512px, which is a third of a millimetre per
     * texel instead of three and a half, and the pattern repeats across the wall the way boards
     * on a real back panel do.
     *
     * ## Which means it has to wrap
     *
     * A tile whose edges do not match its neighbour puts a seam every [TILE_M] across the whole
     * wall — the one artefact guaranteed to be noticed. So everything here is periodic by
     * construction rather than by luck:
     *
     * - Across, the board count is a whole number, so `u = 0` and `u = 1` are both a board edge
     *   and the seam at a tile join is just another seam between boards.
     * - Down, the grain's lateral wander is a sum of sines in `v`, which is exactly periodic
     *   over the tile. This is why it is not the value noise used everywhere else in this file:
     *   lattice noise does not wrap without being rewritten to, and a sine does it for free.
     */
    fun woodTile(
        color: Color,
        seed: Int,
        tintScale: Float,
        /**
         * Which way the boards run.
         *
         * The back panel's boards stand upright; a shelf's board lies along its length, and its
         * grain with it. Same generator, axes swapped on the way out — a shelf wearing the back
         * panel's texture would have its grain running across the board, which is the one thing
         * no sawn timber does.
         */
        acrossInstead: Boolean = false
    ): Bitmap {
        val argb = color.toArgb()
        val baseR = AndroidColor.red(argb) * tintScale
        val baseG = AndroidColor.green(argb) * tintScale
        val baseB = AndroidColor.blue(argb) * tintScale

        val pixels = IntArray(TEX_PX * TEX_PX)
        for (x in 0 until TEX_PX) {
            val u = x.toFloat() / TEX_PX * PLANKS_PER_TILE
            val board = kotlin.math.floor(u).toInt()
            val withinBoard = u - board

            // Per-board tone. Real boards are cut from different parts of a log and no two
            // match; a fixed offset per board is what stops a repeating texture reading as
            // wallpaper. Held small — boards from one rack are the same timber.
            val tone = 1f + (hash(board, 0, seed) - 0.5f) * 2f * PLANK_TONE

            // The seam: dark at both edges of a board, so two neighbours make one line.
            val edge = kotlin.math.min(withinBoard, 1f - withinBoard)
            val seam = 1f - SEAM_DEPTH * (1f - smoothStep01(edge / SEAM_WIDTH))

            // A phase per board, so the grain does not march in step across the panel.
            val phase = hash(board, 7, seed) * 6.2831855f

            for (y in 0 until TEX_PX) {
                val v = y.toFloat() / TEX_PX

                // Lateral wander, periodic in v so the tile joins itself top to bottom. Two
                // harmonics rather than one: a single sine is a wave, two is a wander.
                val wander =
                    kotlin.math.sin(v * 6.2831855f + phase) * GRAIN_WARP +
                        kotlin.math.sin(v * 12.566371f + phase * 1.7f) * GRAIN_WARP * 0.45f

                val across = (withinBoard + wander) * GRAIN_LINES * 6.2831855f
                val grain = 1f +
                    (kotlin.math.sin(across) * 0.7f +
                        kotlin.math.sin(across * 2.3f + phase) * 0.3f) * GRAIN_CONTRAST

                val k = tone * seam * grain
                val index = if (acrossInstead) x * TEX_PX + y else y * TEX_PX + x
                pixels[index] = AndroidColor.rgb(
                    (baseR * k).toInt().coerceIn(0, 255),
                    (baseG * k).toInt().coerceIn(0, 255),
                    (baseB * k).toInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(pixels, TEX_PX, TEX_PX, Bitmap.Config.ARGB_8888)
    }

    /** The old flat-mottle wall, kept for the tests that pin the mean of [mottle]. */
    fun wallBitmap(color: Color, aspect: Float): Bitmap =
        bitmapOf(color, aspect, WALL_CONTRAST, WALL_SEED, tintScale = 1f)

    private fun smoothStep01(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /**
     * The floor's surface.
     *
     * Built from the *wall's* colour rather than a colour of its own, darkened. A floor that
     * picked its own hue would have to be tuned four times and would still be the one surface in
     * the room that did not belong to the mode; derived, it is automatically right in all four
     * and cannot drift when a scheme is retuned.
     */
    fun floorBitmap(wallColor: Color, aspect: Float): Bitmap =
        bitmapOf(wallColor, aspect, FLOOR_CONTRAST, FLOOR_SEED, tintScale = FLOOR_DARKEN)

    private fun bitmapOf(
        color: Color,
        aspect: Float,
        contrast: Float,
        seed: Int,
        tintScale: Float
    ): Bitmap {
        val (across, down) = cellsFor(aspect)
        val field = mottle(TEX_PX, across, down, contrast, seed)
        val argb = color.toArgb()
        val baseR = AndroidColor.red(argb) * tintScale
        val baseG = AndroidColor.green(argb) * tintScale
        val baseB = AndroidColor.blue(argb) * tintScale

        val pixels = IntArray(field.size)
        for (i in field.indices) {
            val k = field[i]
            pixels[i] = AndroidColor.rgb(
                (baseR * k).toInt().coerceIn(0, 255),
                (baseG * k).toInt().coerceIn(0, 255),
                (baseB * k).toInt().coerceIn(0, 255)
            )
        }
        return Bitmap.createBitmap(pixels, TEX_PX, TEX_PX, Bitmap.Config.ARGB_8888)
    }

    /**
     * Smoothed value noise at one point of an infinite lattice.
     *
     * Value noise rather than gradient noise on purpose: it is a handful of operations, it has no
     * directional bias that could read as a grain running one way across the wall, and at this
     * contrast nobody could tell the difference. Smoothstep on the interpolant rather than a
     * linear blend, because linear leaves visible creases along the lattice lines — which at a
     * six-cell grid across four metres of wall would be six straight lines, the one artefact
     * guaranteed to be noticed.
     */
    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val sx = xf * xf * (3f - 2f * xf)
        val sy = yf * yf * (3f - 2f * yf)

        val n00 = hash(xi, yi, seed)
        val n10 = hash(xi + 1, yi, seed)
        val n01 = hash(xi, yi + 1, seed)
        val n11 = hash(xi + 1, yi + 1, seed)

        val top = n00 + (n10 - n00) * sx
        val bottom = n01 + (n11 - n01) * sx
        return top + (bottom - top) * sy
    }

    /** A stable 0..1 from a lattice point. Integer mixing only — no floating-point drift. */
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374_761_393 + y * 668_265_263 + seed * 1_274_126_177
        h = (h xor (h shr 13)) * 1_274_126_177
        h = h xor (h shr 16)
        // `and 0xFFFFFF` rather than abs(): abs(Int.MIN_VALUE) is still negative, which would
        // put a single black texel somewhere in the field for one seed in four billion.
        return (h and 0xFFFFFF) / 0xFFFFFF.toFloat()
    }

    private const val OCTAVE_BROAD = 0.75f
    private const val OCTAVE_FINE = 0.25f

    /**
     * A ceiling on the cell count, so a very wide library does not ask for cells smaller than a
     * few texels — below that the smoothstep has nothing to smooth and the field turns to noise.
     */
    private const val MAX_CELLS = 48

    // Fixed seeds, so the room is the same room every launch. Different from each other so the
    // floor's wear is not a mirror of the wall's — which, on two surfaces that meet along a
    // line, would be visible as a pattern continuing across the join.
    /**
     * How much wall one tile covers, in metres.
     *
     * Small enough that a 512px texture is real detail on it — a third of a millimetre per
     * texel — and large enough that the whole visible wall is a couple of dozen nodes rather
     * than hundreds. The camera sees about 26cm of wall, so this is roughly one tile across the
     * frame.
     */
    const val TILE_M = 0.30f

    /** Boards per tile. A whole number, so the pattern wraps at the tile join. */
    private const val PLANKS_PER_TILE = 3

    /** How dark a seam goes, and how wide it is as a fraction of a board. */
    private const val SEAM_DEPTH = 0.45f
    private const val SEAM_WIDTH = 0.030f

    /** How much board-to-board tone varies. Same timber, different cuts. */
    private const val PLANK_TONE = 0.11f

    /** Grain: how many strokes across a board, how strong, how far they wander. */
    private const val GRAIN_LINES = 5f
    private const val GRAIN_CONTRAST = 0.10f
    private const val GRAIN_WARP = 0.055f

    private const val WALL_SEED = 0x5A17
    private const val FLOOR_SEED = 0x10F1
}
