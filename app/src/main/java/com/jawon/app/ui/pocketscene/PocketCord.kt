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
     * The surface the cord lies on when it comes down to it — the table, or what is on the table
     * — as a height in metres. A node that reaches it stays on it and barely slides: at rest the
     * device lies flat and the cord lies across the surface and away, as in the owner's reference
     * photos, rather than hanging.
     */
    var floorY = Float.NEGATIVE_INFINITY

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
            onFloor()
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
                onFloor()
            }
        }
    }

    /**
     * Puts the cord exactly where [xs], [ys], [zs] say, at rest — no motion carried in — so it can
     * be picked up from there with nothing to spring back from. What the flat lay does: the cord
     * lying still on the surface is a shape, not a simulation settling (see [RestCord]).
     */
    fun hold(xs: FloatArray, ys: FloatArray, zs: FloatArray) {
        for (i in 0 until nodes) {
            x[i] = xs[i]; y[i] = ys[i]; z[i] = zs[i]
            px[i] = xs[i]; py[i] = ys[i]; pz[i] = zs[i]
        }
        primed = true
    }

    /** The link length the cord is made of, metres. */
    val linkM: Float get() = link

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

    /** Keeps every free node on or above the floor, and a node on it from sliding. */
    private fun onFloor() {
        val lo = floorY + RADIUS_M
        for (i in 2 until nodes - 1) {
            if (y[i] < lo) {
                y[i] = lo
                py[i] = lo
                px[i] = x[i] - (x[i] - px[i]) * FLOOR_KEEP
                pz[i] = z[i] - (z[i] - pz[i]) * FLOOR_KEEP
            }
        }
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
        /** The cord's radius, metres — what it rests on the floor by. `CordMesh`'s own. */
        const val RADIUS_M = 0.001f

        /**
         * How much of its sliding a node on the floor keeps each sub-step. Grippy at first (0.35),
         * the cord froze in whatever shape it landed in, zigzags and all; this lets its own
         * stiffness ease the slack into a curve while it settles.
         */
        const val FLOOR_KEEP = 0.85f
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

        /**
         * At rest, where the far end lies: off the flat lay's right edge and up toward the window,
         * nearly the cord's length from the plug, so it lies across the surface in one easy curve.
         * With more slack than that — 10cm of it, at the first place tried — a cord pinned at both
         * ends and resting on cloth buckles, and it lay in zigzags.
         */
        const val REST_EARS_X_M = 0.42f
        const val REST_EARS_Z_M = -0.46f

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
                // Counter-clockwise seen from outside, so the outer surface is the front face. The
                // other way round it was the back face, and a double-sided material turns a back
                // face's normal inward: the cord was lit from inside — grey in the hand, where the
                // window behind it lit the inward side, and black lying on the table.
                indices.putShort(a); indices.putShort(b); indices.putShort(c)
                indices.putShort(b); indices.putShort(d); indices.putShort(c)
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
            // Its shadow on what it lies on is half of what says it is lying there.
            .castShadows(true)
            .receiveShadows(false)
            // The room's lights, and the lamp's light on white plastic — see CORD_LIGHT_CHANNEL.
            .lightChannel(0, true)
            .lightChannel(CORD_LIGHT_CHANNEL, true)
            .build(engine, entity)
    }

    /** Rebuilds the tube round [cord]'s current shape. */
    fun update(cord: Cord) = update(cord.x, cord.y, cord.z)

    /** Rebuilds the tube round the nodes at [xs], [ys], [zs]. */
    fun update(xs: FloatArray, ys: FloatArray, zs: FloatArray) {
        val pos = positions[slot]
        val tan = tangents[slot]
        pos.clear()
        tan.clear()
        for (i in 0 until nodes) {
            // Along the cord here: a central difference, one-sided at the ends.
            val i0 = if (i == 0) 0 else i - 1
            val i1 = if (i == nodes - 1) nodes - 1 else i + 1
            var tx = xs[i1] - xs[i0]
            var ty = ys[i1] - ys[i0]
            var tz = zs[i1] - zs[i0]
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
                pos.put(xs[i] + rx * radiusM)
                pos.put(ys[i] + ry * radiusM)
                pos.put(zs[i] + rz * radiusM)
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

/**
 * **The cord lying on the surface at rest**, as a shape: out of the jack along the plug, then round
 * in one broad curve and away across the surface — the way a cord lies in the owner's reference
 * photos, and the way a simulation settling on cloth never quite did (it froze in zigzags, or never
 * stopped moving). Built node by node at the cord's own link length, so the simulation can take it
 * back from here without a jolt.
 *
 * In the surface's plane: the heading starts along the plug, stays on it for [LEAD_M], turns
 * through [TURN_RAD] on a radius of [RADIUS_M] — the turn's start and end eased, so there is no
 * corner — and runs on straight. Pure.
 */
object RestCord {
    const val LEAD_M = 0.022f
    const val RADIUS_M = 0.075f
    const val TURN_RAD = 2.4f
    /** Over how much of the cord the curvature comes on and goes off. */
    const val EASE_M = 0.03f

    /**
     * Lays [nodes] nodes from the plug's tip ([sx], [sy], [sz]), heading along ([ax], [az]) in the
     * plane — [turnSign] +1 turns clockwise seen from above — over the surface [top] gives the
     * height of: the cord lies on it, a radius up, dropping to it from the plug's height over the
     * lead. **Each link keeps its length over the surface's slopes**, not only across the plane, so
     * the simulation takes the curve back at the length it is made of.
     */
    fun lay(
        sx: Float, sy: Float, sz: Float, ax: Float, az: Float, link: Float, nodes: Int,
        turnSign: Float, top: (Float, Float) -> Float, xs: FloatArray, ys: FloatArray, zs: FloatArray
    ) {
        var heading = kotlin.math.atan2(az, ax)
        var x = sx
        var z = sz
        var y = sy
        val turnLength = RADIUS_M * TURN_RAD
        for (i in 0 until nodes) {
            xs[i] = x; ys[i] = y; zs[i] = z
            // Curvature over this link, as the average of the eased turn across it.
            val s0 = i * link
            val bend = curvatureIntegral(s0 + link, turnLength) - curvatureIntegral(s0, turnLength)
            heading += turnSign * bend
            val cx = kotlin.math.cos(heading)
            val cz = kotlin.math.sin(heading)
            // Where the next node lies, a link on: across the plane first, then shortened by
            // however far the surface climbs or falls over it.
            var step = link
            var ny = y
            repeat(2) {
                ny = height(x + step * cx, z + step * cz, s0 + link, sy, top)
                val rise = ny - y
                step = kotlin.math.sqrt((link * link - rise * rise).coerceAtLeast(link * link * 0.25f))
            }
            x += step * cx
            z += step * cz
            y = height(x, z, s0 + link, sy, top)
        }
    }

    /** The cord's height at ([x], [z]), [s] along it: on the surface, eased down from the plug over the lead. */
    private fun height(x: Float, z: Float, s: Float, plugY: Float, top: (Float, Float) -> Float): Float {
        val lying = top(x, z) + Cord.RADIUS_M
        val t = (s / LEAD_M).coerceIn(0f, 1f)
        val e = t * t * (3f - 2f * t)
        return plugY + (lying - plugY) * e
    }

    /** The heading turned by arc length [s]: nothing along the lead, then the eased turn. */
    private fun curvatureIntegral(s: Float, turnLength: Float): Float {
        val into = (s - LEAD_M).coerceIn(0f, turnLength + EASE_M)
        // Curvature ramps up over EASE_M and down over the last EASE_M: integrate the plateau
        // and the two ramps so the total is exactly TURN_RAD.
        val k = TURN_RAD / turnLength
        return when {
            into <= EASE_M -> k * into * into / (2f * EASE_M)
            into <= turnLength -> k * (into - EASE_M / 2f)
            else -> {
                val over = into - turnLength
                k * (turnLength - EASE_M / 2f + over - over * over / (2f * EASE_M))
            }
        }
    }
}
