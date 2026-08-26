package com.jawon.app.ui.wallscene

import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.sin

/**
 * The disc: a flat annulus in the XY plane, facing +Z.
 *
 * Built by hand rather than with `CylinderNode` (no centre hole — a CD without one is
 * wrong) or `ShapeNode` (it can triangulate a polygon with holes, but its UVs are literally
 * the vertex position, so a centred disc samples at ±0.5 with no offset available).
 *
 * UVs are radial and normalised — `uv = (cos0 * r/R * 0.5 + 0.5, sin0 * r/R * 0.5 + 0.5)` —
 * which maps a square disc-face image correctly onto the ring and is the convention M8's
 * iridescence shader will want.
 *
 * Both faces are generated so the disc doesn't vanish when it is caught edge-on partway
 * through the lift.
 */
object DiscGeometry {

    private const val SEGMENTS = 96

    fun build(
        engine: Engine,
        outerRadius: Float = WallDimens.DISC_RADIUS,
        innerRadius: Float = WallDimens.DISC_HOLE_RADIUS
    ): Geometry {
        val vertices = mutableListOf<Geometry.Vertex>()
        val indices = mutableListOf<Int>()

        val front = Direction(z = 1f)
        val back = Direction(z = -1f)

        // Front ring: inner and outer vertex per segment boundary.
        for (i in 0 until SEGMENTS) {
            val angle = (2.0 * Math.PI * i / SEGMENTS).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            val inner = innerRadius / outerRadius
            vertices += Geometry.Vertex(
                position = Position(c * innerRadius, s * innerRadius, 0f),
                normal = front,
                uvCoordinate = UvCoordinate(c * inner * 0.5f + 0.5f, s * inner * 0.5f + 0.5f)
            )
            vertices += Geometry.Vertex(
                position = Position(c * outerRadius, s * outerRadius, 0f),
                normal = front,
                uvCoordinate = UvCoordinate(c * 0.5f + 0.5f, s * 0.5f + 0.5f)
            )
        }
        val frontCount = vertices.size

        // Back ring: same positions and UVs, flipped normals.
        for (i in 0 until frontCount) {
            val v = vertices[i]
            vertices += Geometry.Vertex(
                position = v.position,
                normal = back,
                uvCoordinate = v.uvCoordinate
            )
        }

        for (i in 0 until SEGMENTS) {
            val next = (i + 1) % SEGMENTS
            val innerI = i * 2
            val outerI = i * 2 + 1
            val innerN = next * 2
            val outerN = next * 2 + 1

            // Front — counter-clockwise seen from +Z.
            indices += listOf(innerI, outerI, outerN)
            indices += listOf(innerI, outerN, innerN)

            // Back — reversed winding.
            indices += listOf(frontCount + innerI, frontCount + outerN, frontCount + outerI)
            indices += listOf(frontCount + innerI, frontCount + innerN, frontCount + outerN)
        }

        return Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(vertices)
            .indices(indices)
            .build(engine)
    }
}
