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
     * A sheet of veneered board: long grain, no seams, no board-to-board banding.
     *
     * **This replaced a boards-with-seams generator for every surface of the rack, and the reason is on the owner's
     * phone.** The boards-with-seams pattern drew a dark seam and a tone step every 10cm across
     * a panel that the camera sees 26cm of, and under a ribbon grazing it from above those read
     * as vertical folds — a theatre curtain, not a cupboard back. Real rack backs are a sheet:
     * plywood or veneered board, with grain and without joins every hand's width.
     *
     * The grain runs along [grainAlongX] — along the shelves and along the back panel both, so
     * the whole carcass is one timber running one way, which is what a cabinet made from one
     * sheet looks like. Horizontal grain also stops the panel's figure fighting the ribbon's
     * gradient, which runs vertically: a pattern that varies in the same direction as the light
     * reads as the light, one that varies across it reads as stripes.
     *
     * ## Wrapping, which is still the whole constraint
     *
     * The rack is tiled ([TILE_M] per node, one texture shared), so every term here is exactly
     * periodic over the tile:
     *
     * - Across the grain, three sines at whole-number frequencies (13, 29 and 61 cycles per
     *   tile). Whole numbers make them wrap; three unrelated ones beat against each other into
     *   figure instead of pinstripe.
     * - Along the grain, a wander added to the across coordinate — a sum of sines in the along
     *   coordinate, periodic by construction — so the lines bend the way grain does.
     * - A slow swell, one cycle per tile each way, so the sheet is not uniformly toned.
     *
     * Contrast is deliberately low. Grain is a few percent of albedo at a metre in any real
     * timber; what reads as *wood* is that it is there at all, not how strong it is.
     */
    fun veneerTile(color: Color, seed: Int, grainAlongX: Boolean): Bitmap {
        val argb = color.toArgb()
        val baseR = AndroidColor.red(argb).toFloat()
        val baseG = AndroidColor.green(argb).toFloat()
        val baseB = AndroidColor.blue(argb).toFloat()
        val p0 = hash(0, 3, seed) * TWO_PI
        val p1 = hash(1, 3, seed) * TWO_PI
        val p2 = hash(2, 3, seed) * TWO_PI
        val p3 = hash(3, 3, seed) * TWO_PI

        val pixels = IntArray(TEX_PX * TEX_PX)
        for (y in 0 until TEX_PX) {
            for (x in 0 until TEX_PX) {
                // a runs across the grain, b along it; both 0..1 over the tile.
                val a = (if (grainAlongX) y else x).toFloat() / TEX_PX
                val b = (if (grainAlongX) x else y).toFloat() / TEX_PX

                val wander = kotlin.math.sin(b * TWO_PI + p0) * VENEER_WANDER +
                    kotlin.math.sin(b * 2f * TWO_PI + p1) * VENEER_WANDER * 0.4f
                val w = a + wander
                val grain =
                    kotlin.math.sin(w * 13f * TWO_PI + p2) * 0.50f +
                        kotlin.math.sin(w * 29f * TWO_PI + p3) * 0.32f +
                        kotlin.math.sin(w * 61f * TWO_PI) * 0.18f
                val swell = kotlin.math.sin(a * TWO_PI + p1) * 0.5f +
                    kotlin.math.sin(b * TWO_PI + p2) * 0.5f

                val k = 1f + grain * VENEER_GRAIN + swell * VENEER_SWELL
                pixels[y * TEX_PX + x] = AndroidColor.rgb(
                    (baseR * k).toInt().coerceIn(0, 255),
                    (baseG * k).toInt().coerceIn(0, 255),
                    (baseB * k).toInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(pixels, TEX_PX, TEX_PX, Bitmap.Config.ARGB_8888)
    }

    /** The old flat-mottle wall, kept for the tests that pin the mean of [mottle]. */
    /**
     * **Painted brick, for the room's own wall — the one behind and around the rack.**
     *
     * The room had no wall. Outside the rack's back panel there was nothing to draw, so most of
     * the frame was black: a friend of the owner's said he could not see the shelf for the dark,
     * and that the room looked like a void. A record shop's rack stands against a wall, and
     * painted brick is the shop wall — it has a pattern the eye reads as a surface at any light
     * level, and a ceiling spot raking across it shows it off.
     *
     * One [TILE_M] tile holds whole bricks so it wraps: two per course, [BRICK_COURSES] courses,
     * the running bond offset by exactly half a brick, which also wraps because two halves make
     * a brick. Every random term is taken modulo the tile, so neighbouring tiles meet with no
     * seam. Mortar is darker than the face — no normal map is available (see ENGINEERING.md's
     * custom-materials row), so the recess is carried in the albedo, softened over a couple of
     * texels so the joints do not shimmer.
     */
    fun brickTile(color: Color, seed: Int): Bitmap {
        val argb = color.toArgb()
        val baseR = AndroidColor.red(argb).toFloat()
        val baseG = AndroidColor.green(argb).toFloat()
        val baseB = AndroidColor.blue(argb).toFloat()
        val brickW = TEX_PX / 2f
        val courseH = TEX_PX / BRICK_COURSES.toFloat()
        val joint = BRICK_JOINT_PX

        val pixels = IntArray(TEX_PX * TEX_PX)
        for (y in 0 until TEX_PX) {
            val course = (y / courseH).toInt()
            val inCourseY = y - course * courseH
            val offset = if (course % 2 == 1) brickW / 2f else 0f
            for (x in 0 until TEX_PX) {
                val sx = (x + offset) % TEX_PX
                val brick = (sx / brickW).toInt() % 2
                val inBrickX = sx - brick * brickW

                // Distance to the nearest joint, in texels, both ways.
                val dx = minOf(inBrickX, brickW - inBrickX)
                val dy = minOf(inCourseY, courseH - inCourseY)
                val d = minOf(dx, dy)
                val face = smooth(joint * 0.5f - 1f, joint * 0.5f + 1f, d)

                // Each brick its own tone — keyed on (brick, course), both already modulo the tile.
                val tone = 1f + (hash(brick, course, seed) - 0.5f) * 2f * BRICK_TONE
                // Fine texture across the face: periodic value noise, 16 cells a tile.
                val grain = 1f + (periodicNoise(x / 32f, y / 32f, 16, seed + 7) - 0.5f) * 2f * BRICK_GRAIN

                val k = MORTAR_LEVEL + (tone * grain - MORTAR_LEVEL) * face
                pixels[y * TEX_PX + x] = AndroidColor.rgb(
                    (baseR * k).toInt().coerceIn(0, 255),
                    (baseG * k).toInt().coerceIn(0, 255),
                    (baseB * k).toInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(pixels, TEX_PX, TEX_PX, Bitmap.Config.ARGB_8888)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** [valueNoise], but its lattice wraps every [period] cells, so a tile of it tiles. */
    private fun periodicNoise(x: Float, y: Float, period: Int, seed: Int): Float {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val sx = xf * xf * (3f - 2f * xf)
        val sy = yf * yf * (3f - 2f * yf)
        fun h(a: Int, b: Int) = hash(Math.floorMod(a, period), Math.floorMod(b, period), seed)
        val top = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * sx
        val bottom = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * sx
        return top + (bottom - top) * sy
    }

    fun wallBitmap(color: Color, aspect: Float): Bitmap =
        bitmapOf(color, aspect, WALL_CONTRAST, WALL_SEED, tintScale = 1f)


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





    /** How strong the grain is, how strong the swell, and how far the grain wanders. */
    private const val VENEER_GRAIN = 0.075f
    private const val VENEER_SWELL = 0.045f
    private const val VENEER_WANDER = 0.018f

    private const val TWO_PI = 6.2831855f

    /**
     * Brick courses per tile: six, so a course is 50mm and a brick 150mm — a little under a
     * standard 215x65 brick, in about its proportion, which keeps the pattern legible in a frame
     * that is only 26cm wide.
     */
    const val BRICK_COURSES = 6

    /** Mortar joint width in texels: 12, about 7mm at [TILE_M] over [TEX_PX]. */
    private const val BRICK_JOINT_PX = 12f

    /** How dark the mortar is against the brick face — the recess, carried in the albedo. */
    private const val MORTAR_LEVEL = 0.55f

    /** Brick-to-brick tone spread, and the fine grain across each face. Paint evens both out. */
    private const val BRICK_TONE = 0.10f
    private const val BRICK_GRAIN = 0.05f

    private const val WALL_SEED = 0x5A17
    private const val FLOOR_SEED = 0x10F1
}
