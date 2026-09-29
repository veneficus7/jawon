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
 * gravity, stepped by position (Verlet), so it hangs with its own weight. **It is run to rest once,
 * for the shape it hangs in with the device in the hand** ([CordRig]) — not through the move between
 * the hand and the cloth, where it rang for seconds however it was tuned.
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
            repeat(ITERATIONS) { sweep ->
                if (sweep % 2 == 0) {
                    for (i in 0 until nodes - 1) satisfy(i, i + 1)
                } else {
                    for (i in nodes - 2 downTo 0) satisfy(i, i + 1)
                }
                pin(sx, sy, sz, ax, ay, az, ex, ey, ez)
            }
        }
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
        /** The cord's radius, metres — what it lies on the cloth by. `CordMesh`'s own. */
        const val RADIUS_M = 0.001f
        /** Where the cord leaves the plug's strain relief, in the device's frame — `EARPHONE_*` in the model script. */
        const val PLUG_X_M = 0.0155f
        const val PLUG_TIP_Y_M = 0.07995f

        /**
         * Where the far end — the earbuds — is, with the device in the hand: up, toward you and to
         * the side, where ears are, off the frame. **High enough that the cord rises out of the
         * frame**: level with the device and nearer, the slack hung in a loop across the screen.
         */
        const val EARS_X_M = 0.06f
        const val EARS_Y_M = 0.45f
        const val EARS_Z_M = 0.22f

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
            if (i == 0) {
                // The first link straight out of the plug and level, exactly where the simulation
                // pins it — so taking the cord back from here moves nothing.
                x += link * cx
                z += link * cz
                continue
            }
            // Where the next node lies, a link on: across the plane first, then shortened by
            // however far the surface climbs or falls over it.
            var step = link
            var ny = y
            repeat(2) {
                ny = height(x + step * cx, z + step * cz, s0 + link, link, sy, top)
                val rise = ny - y
                step = kotlin.math.sqrt((link * link - rise * rise).coerceAtLeast(link * link * 0.25f))
            }
            x += step * cx
            z += step * cz
            y = height(x, z, s0 + link, link, sy, top)
        }
    }

    /**
     * The cord's height at ([x], [z]), [s] along it: on the surface, eased down from the plug's
     * height over the lead that follows its first, level link — and never under the surface, which
     * the easing alone would put it where a fold stands higher than the plug.
     */
    private fun height(x: Float, z: Float, s: Float, link: Float, plugY: Float, top: (Float, Float) -> Float): Float {
        val lying = top(x, z) + Cord.RADIUS_M
        val t = ((s - link) / LEAD_M).coerceIn(0f, 1f)
        val e = t * t * (3f - 2f * t)
        return maxOf(plugY + (lying - plugY) * e, lying)
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

/**
 * **The cord between the hand and the cloth, without a simulation in the move.** In the hand it
 * hangs from the plug up to the earbuds by your ears — the chain's own settled shape ([Cord], run
 * once to rest when the rig is made, then held). On the cloth it lies in the resting curve
 * ([RestCord]). Between the two it **drapes**: it hangs from the plug down to the cloth and lies
 * along the resting curve from where it touches down. It swings about the plug from the hand's
 * shape into the drape at the very start of the set-down, while the device still stands facing you
 * and hides most of the swing behind it, and back into the hand's shape at the very end of the
 * pick-up.
 *
 * **Why not the simulation, for the move.** The owner saw the cord wiggle every time the device was
 * picked up off the cloth, and it did: dragging a 40-link chain from lying on the cloth to hanging
 * by your ears in 0.7s — its plug swinging through 90° and moving 17mm a frame, its far end a metre
 * — left it ringing, 25–40mm a frame of jitter at the peak and still a millimetre a frame two and a
 * half seconds later, and no tuning of the solver took that out (more sub-steps made it worse). A
 * move between two shapes that are both known has nothing to ring, and the hand's shape is the very
 * one the simulation settles to, so the look in the hand is what it was.
 *
 * **Why the drape, rather than easing straight to the resting curve** (its first version, filmed
 * frame by frame off the device on 2026-09-29). The resting curve was laid afresh each frame from
 * wherever the plug had got to — so as the device stood up out of it the curve, heading along the
 * device, swung round and ran down across its face; and with the far end eased down first while the
 * plug's end still rose, the cord bent into a hook above the plug — and the hand's shape itself,
 * carried as the device tipped back, curled over toward the camera rising above it. Now the resting
 * curve is laid once, where the device will land, and never moves: what lies on the cloth lies
 * where it will lie, and only the stretch hanging from the plug changes as the device comes down
 * onto it — as a cord already on a table does. And the swing out of the hand's shape is over before
 * the device has tipped far enough for the camera to see behind it.
 *
 * Every shape here is a function of the device's pose and its share of the flight alone — nothing
 * trails — so the cord stops the frame the device does, and runs backwards exactly.
 */
class CordRig(private val nodes: Int = Cord.NODES) {
    /** What to draw, after each [step] that returned true. */
    val shownX = FloatArray(nodes)
    val shownY = FloatArray(nodes)
    val shownZ = FloatArray(nodes)
    private val link = Cord.LENGTH_M / (nodes - 1)

    /** The resting curve, laid from where the plug lies at rest — once for each cloth. */
    private val restX = FloatArray(nodes)
    private val restY = FloatArray(nodes)
    private val restZ = FloatArray(nodes)
    private var restSurface = Int.MIN_VALUE

    /** The drape, this frame. */
    private val drapeX = FloatArray(nodes)
    private val drapeY = FloatArray(nodes)
    private val drapeZ = FloatArray(nodes)

    /** Scratch for the hanging stretch: its points, finely, and the arc length to each. */
    private val hangX = FloatArray(HANG_SAMPLES + 1)
    private val hangY = FloatArray(HANG_SAMPLES + 1)
    private val hangZ = FloatArray(HANG_SAMPLES + 1)
    private val hangS = FloatArray(HANG_SAMPLES + 1)

    /** The hanging shape in the hand, in the device's own frame: settled once, then held. */
    private val hand: Array<FloatArray> by lazy { settleInHand() }

    private var shownDown = Float.NaN
    private var shownSurface = Int.MIN_VALUE

    /**
     * One frame. The device's centre ([cx], [cy], [cz]) and its axes as a column-major 3×3
     * ([basis] — across, along, out of the face); how far it has come down onto the cloth, [down] —
     * 0 in the hand, 1 lying on it; and the cloth, [top], with its [surface] version — a new one lays
     * the cord down again. [dt] is unused: nothing here trails. Returns whether the drawn shape
     * changed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun step(
        dt: Float,
        cx: Float, cy: Float, cz: Float,
        basis: FloatArray,
        down: Float,
        top: (Float, Float) -> Float,
        surface: Int
    ): Boolean {
        // The device's pose is a function of [down] alone, so the same [down] is the same cord.
        if (down == shownDown && surface == shownSurface) return false

        val plugX = cx + basis[0] * Cord.PLUG_X_M + basis[3] * Cord.PLUG_TIP_Y_M
        val plugY = cy + basis[1] * Cord.PLUG_X_M + basis[4] * Cord.PLUG_TIP_Y_M
        val plugZ = cz + basis[2] * Cord.PLUG_X_M + basis[5] * Cord.PLUG_TIP_Y_M
        val anyDown = down > 0f
        if (anyDown) {
            if (surface != restSurface) {
                layAtRest(top)
                restSurface = surface
            }
            drape(plugX, plugY, plugZ, basis[3], basis[4], basis[5], top)
        }
        val hand = hand
        for (i in 0 until nodes) {
            // The hand's shape, carried with the device wherever it is.
            val lx = hand[0][i]
            val ly = hand[1][i]
            val lz = hand[2][i]
            val hx = cx + basis[0] * lx + basis[3] * ly + basis[6] * lz
            val hy = cy + basis[1] * lx + basis[4] * ly + basis[7] * lz
            val hz = cz + basis[2] * lx + basis[5] * ly + basis[8] * lz
            val w = if (anyDown) weight(i, down) else 0f
            if (w <= 0f) {
                shownX[i] = hx; shownY[i] = hy; shownZ[i] = hz
            } else if (w >= 1f) {
                shownX[i] = drapeX[i]; shownY[i] = drapeY[i]; shownZ[i] = drapeZ[i]
            } else {
                swing(i, plugX, plugY, plugZ, hx, hy, hz, w, basis)
            }
        }
        shownDown = down
        shownSurface = surface
        return true
    }

    /** How far node [i] has gone from the hand's shape to the drape, 0..1, as last drawn. */
    fun morph(i: Int): Float = if (shownDown.isNaN()) 0f else weight(i, shownDown)

    /**
     * How far node [i] has swung from the hand's shape to the drape, at [down]: all of it in the
     * first quarter of the device's way down, while the device still stands facing you — the far
     * end a moment ahead, as it is what falls when the earbuds come out — and last of all on the way
     * back up. Each node over a window of its own, eased.
     */
    private fun weight(i: Int, down: Float): Float {
        val u = i / (nodes - 1f)
        val from = NEAR_FROM + (FAR_FROM - NEAR_FROM) * u
        val span = NEAR_SPAN + (FAR_SPAN - NEAR_SPAN) * u
        val t = ((down - from) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Node [i], [w] of the way from the hand's shape ([hx], [hy], [hz]) to the drape, **swung about
     * the plug** rather than slid in a straight line: its direction from the plug turned and its
     * distance eased, so a stretch of cord swinging down keeps its length instead of folding in on
     * the plug half way.
     */
    private fun swing(
        i: Int, px: Float, py: Float, pz: Float, hx: Float, hy: Float, hz: Float, w: Float, basis: FloatArray
    ) {
        val ax = hx - px; val ay = hy - py; val az = hz - pz
        val bx = drapeX[i] - px; val by = drapeY[i] - py; val bz = drapeZ[i] - pz
        val ra = sqrt(ax * ax + ay * ay + az * az)
        val rb = sqrt(bx * bx + by * by + bz * bz)
        if (ra < 1e-6f || rb < 1e-6f) {
            shownX[i] = hx + (drapeX[i] - hx) * w
            shownY[i] = hy + (drapeY[i] - hy) * w
            shownZ[i] = hz + (drapeZ[i] - hz) * w
            return
        }
        val ux = ax / ra; val uy = ay / ra; val uz = az / ra
        val vx = bx / rb; val vy = by / rb; val vz = bz / rb
        val cos = (ux * vx + uy * vy + uz * vz).coerceIn(-1f, 1f)
        val angle = kotlin.math.acos(cos)
        val r = ra + (rb - ra) * w
        var dx: Float
        var dy: Float
        var dz: Float
        if (angle < 1e-3f) {
            dx = ux + (vx - ux) * w; dy = uy + (vy - uy) * w; dz = uz + (vz - uz) * w
        } else if (angle > kotlin.math.PI.toFloat() - 1e-2f) {
            // Straight back on itself: turn about the device's own across axis, which is the one a
            // cord swinging over the top edge turns about.
            val t = angle * w
            val kx = basis[0]; val ky = basis[1]; val kz = basis[2]
            val c = cos(t); val s = sin(t)
            val kd = kx * ux + ky * uy + kz * uz
            dx = ux * c + (ky * uz - kz * uy) * s + kx * kd * (1f - c)
            dy = uy * c + (kz * ux - kx * uz) * s + ky * kd * (1f - c)
            dz = uz * c + (kx * uy - ky * ux) * s + kz * kd * (1f - c)
        } else {
            val s = sin(angle)
            val a = sin((1f - w) * angle) / s
            val b = sin(w * angle) / s
            dx = ux * a + vx * b; dy = uy * a + vy * b; dz = uz * a + vz * b
        }
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        shownX[i] = px + dx / len * r
        shownY[i] = py + dy / len * r
        shownZ[i] = pz + dz / len * r
    }

    /**
     * The drape, for the plug at ([px], [py], [pz]) heading along ([ax], [ay], [az]): out of the plug
     * straight for a link, hanging from there down to the resting curve, and lying along it from
     * where it touches.
     *
     * **What lies, lies still.** Every node past the touch-down is exactly where it lies at rest;
     * only the touch-down moves — further along the curve the further the plug is from where it
     * lies at rest, [REACH] times as far — so as the device comes down the cord lays itself onto
     * the cloth toward the plug, and as it is lifted comes up off it the other way. (Its first
     * version kept the cord's length exact by sliding what lay back along its curve, and the slide
     * ran one way and then the other as the device rose: the lying cord shivered on the cloth.) The
     * stretch hanging is drawn a little longer than the cord it is made of, which a tube cannot show.
     * At rest the plug is where the curve starts, nothing hangs, and this is the resting curve exactly.
     */
    private fun drape(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, top: (Float, Float) -> Float) {
        drapeX[0] = px; drapeY[0] = py; drapeZ[0] = pz
        val qx = px + ax * link
        val qy = py + ay * link
        val qz = pz + az * link
        drapeX[1] = qx; drapeY[1] = qy; drapeZ[1] = qz
        val ox = qx - restX[1]
        val oy = qy - restY[1]
        val oz = qz - restZ[1]
        val touch = (link + REACH * sqrt(ox * ox + oy * oy + oz * oz)).coerceAtMost((nodes - 1) * link)
        onCurve(touch)
        val tx = curveX; val ty = curveY; val tz = curveZ
        val hx = curveHX; val hy = curveHY; val hz = curveHZ
        val cx = tx - qx; val cy = ty - qy; val cz = tz - qz
        val chord = sqrt(cx * cx + cy * cy + cz * cz)
        // The hanging stretch: a Hermite curve out of the plug's heading and into the lying one,
        // sampled finely and measured. It carries on out of the plug only a little — the cord is
        // soft and bends over under its own weight — and lies into the curve more gently.
        val k0 = chord * TANGENT_OUT
        val k1 = chord * TANGENT_IN
        for (n in 0..HANG_SAMPLES) {
            val t = n / HANG_SAMPLES.toFloat()
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2f * t3 - 3f * t2 + 1f
            val h10 = t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 = t3 - t2
            hangX[n] = h00 * qx + h10 * k0 * ax + h01 * tx + h11 * k1 * hx
            hangY[n] = h00 * qy + h10 * k0 * ay + h01 * ty + h11 * k1 * hy
            hangZ[n] = h00 * qz + h10 * k0 * az + h01 * tz + h11 * k1 * hz
            if (n == 0) {
                hangS[0] = 0f
            } else {
                val dx = hangX[n] - hangX[n - 1]
                val dy = hangY[n] - hangY[n - 1]
                val dz = hangZ[n] - hangZ[n - 1]
                hangS[n] = hangS[n - 1] + sqrt(dx * dx + dy * dy + dz * dz)
            }
        }
        val hangDrawn = hangS[HANG_SAMPLES]
        val hanging = touch - link
        var n = 0
        for (i in 2 until nodes) {
            // The cord's own length from the top of the hanging stretch to this node.
            val along = (i - 1) * link
            if (along >= hanging || hangDrawn < 1e-7f) {
                drapeX[i] = restX[i]; drapeY[i] = restY[i]; drapeZ[i] = restZ[i]
                continue
            }
            val want = along / hanging * hangDrawn
            while (n < HANG_SAMPLES - 1 && hangS[n + 1] < want) n++
            val span = (hangS[n + 1] - hangS[n]).coerceAtLeast(1e-9f)
            val g = ((want - hangS[n]) / span).coerceIn(0f, 1f)
            val x = hangX[n] + (hangX[n + 1] - hangX[n]) * g
            val y = hangY[n] + (hangY[n + 1] - hangY[n]) * g
            val z = hangZ[n] + (hangZ[n + 1] - hangZ[n]) * g
            drapeX[i] = x
            // Never under the cloth, which a curve settling into its touch-down could dip to.
            drapeY[i] = maxOf(y, top(x, z) + Cord.RADIUS_M)
            drapeZ[i] = z
        }
    }

    // The resting curve at a way along it, from [onCurve]: the point, and the unit heading there.
    private var curveX = 0f
    private var curveY = 0f
    private var curveZ = 0f
    private var curveHX = 0f
    private var curveHY = 0f
    private var curveHZ = 0f

    /**
     * The point [s] metres along the resting curve from the plug, and the curve's heading there —
     * carried on straight past its far end, where the cord slid back along it has left the curve.
     */
    private fun onCurve(s: Float) {
        val u = (s / link).coerceAtLeast(0f)
        val j = u.toInt().coerceAtMost(nodes - 2)
        val f = u - j
        var hx = restX[j + 1] - restX[j]
        var hy = restY[j + 1] - restY[j]
        var hz = restZ[j + 1] - restZ[j]
        curveX = restX[j] + hx * f
        curveY = restY[j] + hy * f
        curveZ = restZ[j] + hz * f
        val l = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-9f)
        hx /= l; hy /= l; hz /= l
        curveHX = hx; curveHY = hy; curveHZ = hz
    }

    /** The resting curve, laid from the plug as the device lies at rest ([RestPose]). */
    private fun layAtRest(top: (Float, Float) -> Float) {
        val b = restBasis()
        val sx = RestPose.DEVICE_X + b[0] * Cord.PLUG_X_M + b[3] * Cord.PLUG_TIP_Y_M
        val sy = RestPose.DEVICE_Y + b[1] * Cord.PLUG_X_M + b[4] * Cord.PLUG_TIP_Y_M
        val sz = RestPose.DEVICE_Z + b[2] * Cord.PLUG_X_M + b[5] * Cord.PLUG_TIP_Y_M
        val level = sqrt(b[3] * b[3] + b[5] * b[5]).coerceAtLeast(1e-6f)
        RestCord.lay(sx, sy, sz, b[3] / level, b[5] / level, link, nodes, TURN, top, restX, restY, restZ)
    }

    /**
     * The chain hanging from the plug to the earbuds with the device in the hand — life size at the
     * origin, turned as the room holds it — settled, and taken into the device's own frame.
     */
    private fun settleInHand(): Array<FloatArray> {
        val b = handBasis()
        val cord = Cord(nodes)
        val sx = b[0] * Cord.PLUG_X_M + b[3] * Cord.PLUG_TIP_Y_M
        val sy = b[1] * Cord.PLUG_X_M + b[4] * Cord.PLUG_TIP_Y_M
        val sz = b[2] * Cord.PLUG_X_M + b[5] * Cord.PLUG_TIP_Y_M
        repeat(SETTLE_FRAMES) {
            cord.step(1f / 60f, sx, sy, sz, b[3], b[4], b[5], Cord.EARS_X_M, Cord.EARS_Y_M, Cord.EARS_Z_M)
        }
        val out = Array(3) { FloatArray(nodes) }
        for (i in 0 until nodes) {
            // Bᵀ · p: the device's frame is orthonormal.
            out[0][i] = b[0] * cord.x[i] + b[1] * cord.y[i] + b[2] * cord.z[i]
            out[1][i] = b[3] * cord.x[i] + b[4] * cord.y[i] + b[5] * cord.z[i]
            out[2][i] = b[6] * cord.x[i] + b[7] * cord.y[i] + b[8] * cord.z[i]
        }
        return out
    }

    companion object {
        /**
         * Where each node's swing from the hand's shape to the drape starts, as a share of the
         * device's way down, and over how much of it: the far end from the start, the plug's end a
         * moment after — both done by a quarter of the way, before the device has tipped back far
         * enough for the camera, rising over it, to see the cord behind it. Swung later, from a
         * third of the way, the hand's shape carried on the tipping device read from above as a hook.
         */
        const val NEAR_FROM = 0.04f
        const val NEAR_SPAN = 0.22f
        const val FAR_FROM = 0.0f
        const val FAR_SPAN = 0.22f
        /**
         * Where the hanging cord touches down: this many times the plug's distance from where it
         * lies at rest, along the resting curve past it. The longer the reach, the nearer the
         * hanging stretch's drawn length is to the cord it is made of.
         */
        const val REACH = 1.5f
        /**
         * The hanging stretch's tangents, as shares of its chord: how far it carries on out of the
         * plug before it bends over, and how long a curve it lies into the cloth along. At 0.9 out
         * of the plug it stood up in an arch over it, like wire.
         */
        const val TANGENT_OUT = 0.42f
        const val TANGENT_IN = 0.6f
        const val HANG_SAMPLES = 48
        /** The resting curve turns this way: clockwise seen from above, off the frame's top right. */
        const val TURN = 1f
        /** Eight seconds of hanging — settled to well under a millimetre (`PocketCordTest`). */
        const val SETTLE_FRAMES = 480

        /** The device's axes in the hand, as `PocketDevice` holds it: `Ry(yaw) · Rx(pitch)`. */
        fun handBasis(): FloatArray = basis(DEVICE_PITCH_DEG, DEVICE_YAW_DEG)

        /** And lying at rest, as [RestPose] lays it. */
        fun restBasis(): FloatArray = basis(RestPose.DEVICE_PITCH_DEG, RestPose.DEVICE_YAW_DEG)

        private fun basis(pitchDeg: Float, yawDeg: Float): FloatArray {
            val p = Math.toRadians(pitchDeg.toDouble())
            val y = Math.toRadians(yawDeg.toDouble())
            val cp = kotlin.math.cos(p).toFloat()
            val sp = kotlin.math.sin(p).toFloat()
            val cy = kotlin.math.cos(y).toFloat()
            val sy = kotlin.math.sin(y).toFloat()
            // Columns: Ry·Rx applied to x, y and z.
            return floatArrayOf(
                cy, 0f, -sy,
                sy * sp, cp, cy * sp,
                sy * cp, -sp, cy * cp
            )
        }
    }
}
