package com.jawon.app.ui.pocketscene

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The earphone cord, as the owner asked for it — "if its pocket player it should be connected to
 * a wire … just to make it more realistic" (`docs/formats.md` §4): a chain of short links under
 * gravity, stepped by position (Verlet), so it hangs with its own weight, sways as the device
 * floats and trails behind it when it flies between the hand and the table.
 *
 * Three nodes are pinned: the first two to the plug — where the cord leaves the strain relief and
 * the direction it leaves in, so it comes out of the plug straight rather than kinked — and the
 * last to the far end, the earbuds, which is off the frame. Everything between is free. Pure, so
 * the hanging and the lengths can be tested.
 */
class Cord(val nodes: Int = NODES, val lengthM: Float = LENGTH_M) {

    val x = FloatArray(nodes)
    val y = FloatArray(nodes)
    val z = FloatArray(nodes)
    private val px = FloatArray(nodes)
    private val py = FloatArray(nodes)
    private val pz = FloatArray(nodes)
    private val link = lengthM / (nodes - 1)
    private var primed = false

    /**
     * One step of [dt] seconds. The cord leaves the plug at ([sx], [sy], [sz]) heading along the
     * unit ([ax], [ay], [az]); its far end is at ([ex], [ey], [ez]).
     */
    fun step(
        dt: Float,
        sx: Float, sy: Float, sz: Float,
        ax: Float, ay: Float, az: Float,
        ex: Float, ey: Float, ez: Float
    ) {
        if (!primed) {
            lay(sx, sy, sz, ax, ay, az, ex, ey, ez)
            primed = true
        }
        val h = min(dt, MAX_STEP_S)
        if (h <= 0f) {
            pin(sx, sy, sz, ax, ay, az, ex, ey, ez)
            return
        }
        // **Small steps rather than many iterations.** A chain carrying its own weight stretches
        // at the top when it is solved in one big step — 8% on the links nearest the plug, even at
        // thirty sweeps — and sub-stepping is what position-based cords do about it.
        val sub = h / SUBSTEPS
        val keep = exp(-DRAG_PER_S * sub)
        val fall = GRAVITY * sub * sub
        repeat(SUBSTEPS) {
            for (i in 2 until nodes - 1) {
                val vx = (x[i] - px[i]) * keep
                val vy = (y[i] - py[i]) * keep
                val vz = (z[i] - pz[i]) * keep
                px[i] = x[i]; py[i] = y[i]; pz[i] = z[i]
                x[i] += vx
                y[i] += vy - fall
                z[i] += vz
            }
            pin(sx, sy, sz, ax, ay, az, ex, ey, ez)
            // A cord resists folding: each node is eased toward the middle of its neighbours, so it
            // bends in curves rather than kinking where it leaves the plug. Once per sub-step,
            // before the lengths — run inside every sweep it fought them, and links came out short.
            for (i in 2 until nodes - 1) unkink(i)
            // Sweeping one way only converges slowly, so the sweep alternates direction.
            repeat(ITERATIONS) { k ->
                if (k % 2 == 0) {
                    for (i in 0 until nodes - 1) satisfy(i, i + 1)
                } else {
                    for (i in nodes - 2 downTo 0) satisfy(i, i + 1)
                }
                pin(sx, sy, sz, ax, ay, az, ex, ey, ez)
            }
        }
    }

    private fun lay(sx: Float, sy: Float, sz: Float, ax: Float, ay: Float, az: Float, ex: Float, ey: Float, ez: Float) {
        // Out of the plug, then straight to the far end: gravity settles the sag within a second.
        val bx = sx + ax * link
        val by = sy + ay * link
        val bz = sz + az * link
        for (i in 0 until nodes) {
            if (i == 0) {
                x[i] = sx; y[i] = sy; z[i] = sz
            } else {
                val t = (i - 1).toFloat() / (nodes - 2)
                x[i] = bx + (ex - bx) * t
                y[i] = by + (ey - by) * t
                z[i] = bz + (ez - bz) * t
            }
            px[i] = x[i]; py[i] = y[i]; pz[i] = z[i]
        }
    }

    private fun pin(sx: Float, sy: Float, sz: Float, ax: Float, ay: Float, az: Float, ex: Float, ey: Float, ez: Float) {
        x[0] = sx; y[0] = sy; z[0] = sz
        x[1] = sx + ax * link; y[1] = sy + ay * link; z[1] = sz + az * link
        val l = nodes - 1
        x[l] = ex; y[l] = ey; z[l] = ez
    }

    private fun unkink(i: Int) {
        val mx = (x[i - 1] + x[i + 1]) * 0.5f
        val my = (y[i - 1] + y[i + 1]) * 0.5f
        val mz = (z[i - 1] + z[i + 1]) * 0.5f
        x[i] += (mx - x[i]) * STIFFNESS
        y[i] += (my - y[i]) * STIFFNESS
        z[i] += (mz - z[i]) * STIFFNESS
    }

    private fun satisfy(a: Int, b: Int) {
        val wa = if (a <= 1 || a == nodes - 1) 0f else 1f
        val wb = if (b <= 1 || b == nodes - 1) 0f else 1f
        val w = wa + wb
        if (w == 0f) return
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        val dz = z[b] - z[a]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-7f) return
        val k = (d - link) / d
        x[a] += dx * k * wa / w; y[a] += dy * k * wa / w; z[a] += dz * k * wa / w
        x[b] -= dx * k * wb / w; y[b] -= dy * k * wb / w; z[b] -= dz * k * wb / w
    }

    companion object {
        /** Where the cord leaves the plug's strain relief, in the device's frame — `EARPHONE_*` in the model script. */
        const val PLUG_X_M = 0.0155f
        const val PLUG_TIP_Y_M = 0.07995f

        /**
         * Where the far end — the earbuds — is kept, relative to the device: up, toward you and to
         * the side, where ears are, off the frame. Held there on a lagging spring, so the cord
         * trails when the device moves. **High enough that the cord rises out of the frame**: level
         * with the device and nearer, the slack hung in a loop across the screen.
         */
        const val EARS_X_M = 0.06f
        const val EARS_Y_M = 0.45f
        const val EARS_Z_M = 0.22f
        const val EARS_FOLLOW_OMEGA = 4f

        const val NODES = 40
        /**
         * The plug to the ears is ~0.43m; this leaves four centimetres to sag — a cord that hangs,
         * not one that loops. (At 0.62 the slack hung across the screen.)
         */
        const val LENGTH_M = 0.47f
        const val GRAVITY = 9.81f
        /** Air on a light cord: a swing dies away in a couple of seconds. */
        const val DRAG_PER_S = 1.6f
        const val ITERATIONS = 12
        /** How much a node is eased toward straightness per sweep: a cord's, not a wire's. */
        const val STIFFNESS = 0.35f
        const val SUBSTEPS = 4
        /** A long frame is taken as this — a stall must not fling the cord. */
        const val MAX_STEP_S = 1f / 30f
    }
}

/**
 * The cord drawn: a white tube round the simulated chain, rebuilt every frame into buffers of its
 * own rather than through SceneView's `Geometry`, which takes a fresh list of vertex objects per
 * update — thousands of allocations a second for something that moves at 60fps. Three buffers in
 * turn, so the one Filament may still be reading is never the one being written.
 *
 * The ends are not capped: one is inside the plug's strain relief, the other off the frame.
 */
class CordMesh(
    private val engine: Engine,
    material: MaterialInstance,
    private val nodes: Int = Cord.NODES,
    private val sides: Int = SIDES,
    private val radiusM: Float = RADIUS_M
) {
    val entity: Int = EntityManager.get().create()
    private val vertexCount = nodes * sides
    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(vertexCount)
        .bufferCount(2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
        .build(engine)
    private val indexBuffer: IndexBuffer
    private val positions = Array(BUFFERS) { direct(vertexCount * 3) }
    private val tangents = Array(BUFFERS) { direct(vertexCount * 4) }
    private var slot = 0
    private val quat = FloatArray(4)

    // The frame carried along the cord (parallel transport), so the tube does not twist.
    private var nx = 1f
    private var ny = 0f
    private var nz = 0f

    init {
        val quads = (nodes - 1) * sides
        val indices = ByteBuffer.allocateDirect(quads * 6 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until nodes - 1) {
            for (j in 0 until sides) {
                val a = (i * sides + j).toShort()
                val b = (i * sides + (j + 1) % sides).toShort()
                val c = ((i + 1) * sides + j).toShort()
                val d = ((i + 1) * sides + (j + 1) % sides).toShort()
                indices.putShort(a); indices.putShort(c); indices.putShort(b)
                indices.putShort(b); indices.putShort(c); indices.putShort(d)
            }
        }
        indices.flip()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(quads * 6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)
        RenderableManager.Builder(1)
            // Wherever the cord swings, it stays inside this; culling is off in any case.
            .boundingBox(Box(0f, 0f, 0f, 3f, 3f, 3f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, material)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            // The room's lights, and the lamp's light on white plastic — see CORD_LIGHT_CHANNEL.
            .lightChannel(0, true)
            .lightChannel(CORD_LIGHT_CHANNEL, true)
            .build(engine, entity)
    }

    /** Rebuilds the tube round [cord]'s current shape. */
    fun update(cord: Cord) {
        val pos = positions[slot]
        val tan = tangents[slot]
        pos.clear()
        tan.clear()
        for (i in 0 until nodes) {
            // Along the cord here: a central difference, one-sided at the ends.
            val i0 = if (i == 0) 0 else i - 1
            val i1 = if (i == nodes - 1) nodes - 1 else i + 1
            var tx = cord.x[i1] - cord.x[i0]
            var ty = cord.y[i1] - cord.y[i0]
            var tz = cord.z[i1] - cord.z[i0]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-7f)
            tx /= tl; ty /= tl; tz /= tl
            if (i == 0) {
                // Any direction across the cord to start from.
                if (kotlin.math.abs(tx) < 0.9f) { nx = 1f; ny = 0f; nz = 0f } else { nx = 0f; ny = 1f; nz = 0f }
            }
            // Carry the previous normal round the bend: remove its component along the cord.
            val dot = nx * tx + ny * ty + nz * tz
            nx -= tx * dot; ny -= ty * dot; nz -= tz * dot
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-7f)
            nx /= nl; ny /= nl; nz /= nl
            // And the third axis.
            val bx = ty * nz - tz * ny
            val by = tz * nx - tx * nz
            val bz = tx * ny - ty * nx
            for (j in 0 until sides) {
                val a = (2.0 * Math.PI * j / sides)
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                val rx = nx * c + bx * s
                val ry = ny * c + by * s
                val rz = nz * c + bz * s
                pos.put(cord.x[i] + rx * radiusM)
                pos.put(cord.y[i] + ry * radiusM)
                pos.put(cord.z[i] + rz * radiusM)
                // Tangent along the cord, normal out from it, bitangent across.
                MathUtils.packTangentFrame(
                    tx, ty, tz,
                    ry * tz - rz * ty, rz * tx - rx * tz, rx * ty - ry * tx,
                    rx, ry, rz,
                    quat
                )
                tan.put(quat)
            }
        }
        pos.flip()
        tan.flip()
        vertexBuffer.setBufferAt(engine, 0, pos)
        vertexBuffer.setBufferAt(engine, 1, tan)
        slot = (slot + 1) % BUFFERS
    }

    fun destroy() {
        engine.destroyEntity(entity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        EntityManager.get().destroy(entity)
    }

    private fun direct(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    companion object {
        /**
         * **White plastic in a room exposed for polished metal reads grey.** The room is exposed so
         * the device's mirror-bright softbox reflection sits at ~40 of 255, and a white diffuse
         * surface under the same carriage lands near the same — the cord and plug first rendered
         * dark grey. This channel carries one light, from the reading lamp's direction over your
         * shoulder, onto the cord and the plug and nothing else: the plastic catches the lamp as it
         * would, and nothing about the device or the room moves.
         */
        const val CORD_LIGHT_CHANNEL = 1
        const val SIDES = 10
        /** A 2mm cord. */
        const val RADIUS_M = 0.001f
        const val BUFFERS = 3
    }
}
