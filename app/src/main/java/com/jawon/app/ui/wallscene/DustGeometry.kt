package com.jawon.app.ui.wallscene

import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.random.Random

/**
 * The dust cloud — DESIGN.md §1's "dust hanging in the light".
 *
 * Motes are quads, not GL points. The prototype uses `gl_PointSize`, but Filament's
 * material language has no writable point size, and unsized points render a single pixel
 * each. Quads also need no billboarding here: this camera only ever pans and dollies, it
 * never rotates, so a quad in the XY plane always faces it.
 *
 * Each mote carries its own randomness so the shader can animate it without the CPU
 * touching anything per frame:
 * - `uv0` is the corner, 0..1 across the quad, which the fragment shader turns into a round
 *   soft mote.
 * - the vertex colour's red channel is the drift phase, **identical across all four
 *   corners** of a mote so they displace together rather than tearing the quad apart.
 *
 * Built once per wall size rather than per frame; see `dust.mat` for the motion itself.
 */
object DustGeometry {

    /** Mote width in metres. About 1.5mm — a speck, not a snowflake. */
    private const val MOTE_SIZE = 0.0015f

    private const val MIN_MOTES = 220
    private const val MAX_MOTES = 900

    /**
     * Depth band the cloud occupies, in metres, between the wall and the camera. The
     * prototype's -0.4..3.8 decimetres, converted.
     */
    private const val NEAR_Z = -0.04f
    private const val FAR_Z = 0.38f

    /**
     * @param width/height the wall's extent, so the cloud covers wherever the user can pan
     * @param density motes per square metre of wall, so a bigger library gets a
     *   proportionally full room. Comes from [QualityTier.dustDensity] — dust is pure
     *   atmosphere and every mote is a blended quad, so it is among the first things a
     *   struggling device should stop paying for.
     * @param seed fixed by default, so the room looks the same every launch rather than
     *   reshuffling its dust each time the app starts
     */
    fun build(
        engine: Engine,
        width: Float,
        height: Float,
        density: Float,
        seed: Int = 1_705
    ): Geometry {
        val random = Random(seed)
        val count = (width * height * density).toInt().coerceIn(MIN_MOTES, MAX_MOTES)

        val vertices = ArrayList<Geometry.Vertex>(count * 4)
        val indices = ArrayList<Int>(count * 6)
        val half = MOTE_SIZE / 2f
        val facing = Direction(z = 1f)

        // Corner offsets and their UVs, in the order the two triangles below index them.
        val corners = arrayOf(
            Float2(-half, -half) to Float2(0f, 0f),
            Float2(half, -half) to Float2(1f, 0f),
            Float2(half, half) to Float2(1f, 1f),
            Float2(-half, half) to Float2(0f, 1f)
        )

        for (i in 0 until count) {
            val cx = (random.nextFloat() - 0.5f) * width
            val cy = (random.nextFloat() - 0.5f) * height
            val cz = NEAR_Z + random.nextFloat() * (FAR_Z - NEAR_Z)

            // One phase per mote, shared by its four corners. Also scales the mote a little
            // so the cloud is not uniform.
            val phase = random.nextFloat()
            val scale = 0.6f + random.nextFloat() * 0.8f
            val tint = Float4(phase, scale, 0f, 1f)

            val base = vertices.size
            for ((offset, uv) in corners) {
                vertices += Geometry.Vertex(
                    position = Position(cx + offset.x * scale, cy + offset.y * scale, cz),
                    normal = facing,
                    uvCoordinate = UvCoordinate(uv.x, uv.y),
                    color = tint
                )
            }
            indices += listOf(base, base + 1, base + 2)
            indices += listOf(base, base + 2, base + 3)
        }

        return Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(vertices)
            .indices(indices)
            .build(engine)
    }
}
