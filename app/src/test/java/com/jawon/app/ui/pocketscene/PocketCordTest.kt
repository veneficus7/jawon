package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The earphone cord. What would read as wrong: a cord that comes away from the plug, stretches like
 * elastic, floats instead of hanging, or never stops swinging.
 */
class PocketCordTest {

    private fun Cord.run(seconds: Float, sy: Float = 0f, ey: Float = 0.30f, ez: Float = 0.30f) {
        repeat((seconds * 60).toInt()) { step(1f / 60f, 0f, sy, 0f, 0f, 1f, 0f, 0.05f, ey, ez) }
    }

    private fun Cord.linkLength(i: Int): Float {
        val dx = x[i + 1] - x[i]
        val dy = y[i + 1] - y[i]
        val dz = z[i + 1] - z[i]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `both ends stay where they are pinned, and it leaves the plug straight`() {
        val cord = Cord()
        cord.run(2f)
        assertEquals(0f, cord.x[0], 1e-6f)
        assertEquals(0f, cord.y[0], 1e-6f)
        assertEquals(0.30f, cord.y[cord.nodes - 1], 1e-6f)
        // The second node is straight out along the plug's axis (up).
        assertEquals(0f, cord.x[1], 1e-6f)
        assertTrue(cord.y[1] > 0f)
    }

    @Test
    fun `it keeps its length - a cord, not an elastic`() {
        val cord = Cord()
        cord.run(3f)
        val link = cord.lengthM / (cord.nodes - 1)
        for (i in 0 until cord.nodes - 1) {
            assertEquals("link $i", link, cord.linkLength(i), link * 0.03f)
        }
    }

    @Test
    fun `with slack it hangs - the middle sags below the straight line`() {
        // Ends 0.42m apart on a 0.62m cord: it has to hang somewhere.
        val cord = Cord()
        cord.run(4f)
        val mid = cord.nodes / 2
        val straightY = cord.y[1] + (0.30f - cord.y[1]) * ((mid - 1).toFloat() / (cord.nodes - 2))
        assertTrue("middle at ${cord.y[mid]}, straight line at $straightY", cord.y[mid] < straightY - 0.02f)
    }

    @Test
    fun `left still, it settles`() {
        val cord = Cord()
        cord.run(8f)
        val before = FloatArray(cord.nodes) { cord.y[it] }
        cord.run(0.5f)
        val moved = (0 until cord.nodes).maxOf { abs(cord.y[it] - before[it]) }
        assertTrue("still moving ${moved * 1000}mm", moved < 0.0005f)
    }

    @Test
    fun `when the plug moves, the cord follows, and a stall does not fling it`() {
        val cord = Cord()
        cord.run(2f)
        // The device carried off in a hurry, then one enormous frame.
        repeat(30) { i -> cord.step(1f / 60f, 0f, -0.01f * i, 0f, 0f, 1f, 0f, 0.05f, 0.30f, 0.30f) }
        cord.step(2f, 0f, -0.30f, 0f, 0f, 1f, 0f, 0.05f, 0.30f, 0.30f)
        for (i in 0 until cord.nodes) {
            assertFalse(cord.x[i].isNaN() || cord.y[i].isNaN() || cord.z[i].isNaN())
            assertTrue("node $i flung to ${cord.y[i]}", abs(cord.y[i]) < 1.5f && abs(cord.x[i]) < 1.5f)
        }
    }

    // ---- lying on the cloth -------------------------------------------------------------------

    /** A cord laid from the origin, heading −z, over [top], with the plug [plugY] up. */
    private fun laid(top: (Float, Float) -> Float, plugY: Float = 0.005f): Triple<FloatArray, FloatArray, FloatArray> {
        val cord = Cord()
        val xs = FloatArray(cord.nodes)
        val ys = FloatArray(cord.nodes)
        val zs = FloatArray(cord.nodes)
        RestCord.lay(0f, plugY, 0f, 0f, -1f, cord.linkM, cord.nodes, 1f, top, xs, ys, zs)
        return Triple(xs, ys, zs)
    }

    @Test
    fun `lying over folds, every link keeps its length - it drapes, it does not stretch`() {
        // A cloth rising and falling by a centimetre and a half every 12cm.
        val folds = { x: Float, z: Float -> 0.0075f * kotlin.math.sin(z / 0.02f) + 0.004f * kotlin.math.sin(x / 0.03f) }
        val (xs, ys, zs) = laid(folds)
        val link = Cord().linkM
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val dz = zs[i + 1] - zs[i]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            assertEquals("link $i", link, length, link * 0.03f)
        }
    }

    @Test
    fun `past the plug it lies on the surface, one radius up, and it leaves the plug at the plug`() {
        val slope = { x: Float, z: Float -> 0.15f * x - 0.1f * z }
        val (xs, ys, zs) = laid(slope, plugY = 0.02f)
        val link = Cord().linkM
        assertEquals(0.02f, ys[0], 1e-6f)
        // Its first link straight out of the plug, level — where the simulation pins it.
        assertEquals(0f, xs[1], 1e-6f)
        assertEquals(0.02f, ys[1], 1e-6f)
        assertEquals(-link, zs[1], 1e-6f)
        var s = link
        for (i in 2 until xs.size) {
            s += link
            if (s >= link + RestCord.LEAD_M) {
                assertEquals("node $i", slope(xs[i], zs[i]) + Cord.RADIUS_M, ys[i], 1e-5f)
            }
        }
    }

    // ---- between the hand and the cloth -------------------------------------------------------

    /**
     * The device between the hand and the cloth, as `PocketDevice` poses it — its flight, and its
     * centre and axes — driving a [CordRig] over a folded cloth, and measuring the drawn cord.
     */
    private class Handover {
        val rig = CordRig()
        val flight = RestFlight()
        /** A cloth in folds a centimetre deep, at the height the device lies at. */
        val top = { x: Float, z: Float ->
            RestPose.TABLE_TOP_Y + RestPose.SURFACE_T + 0.006f * (1f + kotlin.math.sin(x / 0.025f) * kotlin.math.sin(z / 0.035f))
        }
        /** The device as last posed: its centre, and its axes as a column-major 3×3. */
        val basis = FloatArray(9)
        var centreX = 0f
        var centreY = 0f
        var centreZ = 0f
        private val lastX = FloatArray(Cord.NODES)
        private val lastY = FloatArray(Cord.NODES)
        private val lastZ = FloatArray(Cord.NODES)
        private val velX = FloatArray(Cord.NODES)
        private val velY = FloatArray(Cord.NODES)
        private val velZ = FloatArray(Cord.NODES)
        private var first = true
        /**
         * In the last frame: the most any drawn node moved; and the largest **reversal** — a node
         * that moved one way last frame and back the other way this one, by the smaller of the two
         * moves. A move that eases passes through stillness to turn round, so it reverses by
         * nothing; a shiver reverses by millimetres, every frame.
         */
        var moved = 0f
        var reversal = 0f

        fun frame(dt: Float = 1f / 60f): Boolean {
            flight.step(dt)
            val d = flight.device
            val pitch = Math.toRadians((DEVICE_PITCH_DEG + (RestPose.DEVICE_PITCH_DEG - DEVICE_PITCH_DEG) * d).toDouble())
            val yaw = Math.toRadians((DEVICE_YAW_DEG + (RestPose.DEVICE_YAW_DEG - DEVICE_YAW_DEG) * d).toDouble())
            val cp = kotlin.math.cos(pitch).toFloat()
            val sp = kotlin.math.sin(pitch).toFloat()
            val cy = kotlin.math.cos(yaw).toFloat()
            val sy = kotlin.math.sin(yaw).toFloat()
            // Ry(yaw) · Rx(pitch), column by column — SceneView's order.
            basis[0] = cy; basis[1] = 0f; basis[2] = -sy
            basis[3] = sy * sp; basis[4] = cp; basis[5] = cy * sp
            basis[6] = sy * cp; basis[7] = -sp; basis[8] = cy * cp
            centreX = RestPose.DEVICE_X * d
            centreY = RestPose.DEVICE_Y * d
            centreZ = RestPose.DEVICE_Z * d
            val changed = rig.step(dt, centreX, centreY, centreZ, basis, d, top, 1)
            moved = 0f
            reversal = 0f
            for (i in 0 until Cord.NODES) {
                val dx = rig.shownX[i] - lastX[i]
                val dy = rig.shownY[i] - lastY[i]
                val dz = rig.shownZ[i] - lastZ[i]
                if (!first) {
                    val m = sqrt(dx * dx + dy * dy + dz * dz)
                    moved = maxOf(moved, m)
                    val before = sqrt(velX[i] * velX[i] + velY[i] * velY[i] + velZ[i] * velZ[i])
                    val dot = dx * velX[i] + dy * velY[i] + dz * velZ[i]
                    // Turned back by more than 120°.
                    if (dot < -0.5f * m * before) reversal = maxOf(reversal, minOf(m, before))
                }
                velX[i] = if (first) 0f else dx
                velY[i] = if (first) 0f else dy
                velZ[i] = if (first) 0f else dz
            }
            first = false
            rig.shownX.copyInto(lastX); rig.shownY.copyInto(lastY); rig.shownZ.copyInto(lastZ)
            return changed
        }

        fun run(seconds: Float) = repeat((seconds * 60).toInt()) { frame() }

        /** Laid on the cloth and left there. */
        fun lying(): Handover {
            run(1f)
            flight.target = 1f
            run(6f)
            return this
        }
    }

    @Test
    fun `lying on the cloth, it is still - not even redrawn`() {
        val h = Handover().lying()
        repeat(60) { assertFalse("redrawn while lying still", h.frame()) }
    }

    @Test
    fun `picked up off the cloth, it moves without a shiver`() {
        val h = Handover().lying()
        h.flight.target = 0f
        // The whole way back to the hand, frame by frame. What the owner saw was a shiver — a node
        // moving one way a frame and back the next, 25–40mm of it at the peak, for a second and a
        // half after the device was picked up.
        var worst = 0f
        repeat(240) {
            h.frame()
            worst = maxOf(worst, h.reversal)
        }
        assertTrue("a node turned back ${worst * 1000}mm in a frame", worst < 0.001f)
    }

    @Test
    fun `set down on the cloth, it lands without a shiver either`() {
        val h = Handover()
        h.run(1f)
        h.flight.target = 1f
        var worst = 0f
        repeat(360) {
            h.frame()
            worst = maxOf(worst, h.reversal)
        }
        assertTrue("a node turned back ${worst * 1000}mm in a frame", worst < 0.001f)
    }

    @Test
    fun `every point of it goes one way between the shapes - it never swings back`() {
        // A slow wobble reverses no faster than it moves, so no single frame shows it: a spring
        // that overshoots was invisible to the shiver tests. Each node's way between the hanging
        // and the lying shape must only ever go one way — down on the way down, up on the way up.
        val h = Handover()
        h.run(1f)
        h.flight.target = 1f
        val before = FloatArray(Cord.NODES)
        repeat(Cord.NODES) { before[it] = h.rig.morph(it) }
        repeat(360) {
            h.frame()
            for (i in 0 until Cord.NODES) {
                val now = h.rig.morph(i)
                assertTrue("node $i went back up the way on the way down", now >= before[i] - 1e-5f)
                before[i] = now
            }
        }
        h.flight.target = 0f
        repeat(360) {
            h.frame()
            for (i in 0 until Cord.NODES) {
                val now = h.rig.morph(i)
                assertTrue("node $i went back down the way on the way up", now <= before[i] + 1e-5f)
                before[i] = now
            }
        }
    }

    @Test
    fun `back in the hand, it comes to a stop - and hangs where it did`() {
        val h = Handover().lying()
        h.flight.target = 0f
        h.run(4f)
        // In the hand and arrived: not redrawn, and nothing moving.
        repeat(30) { assertFalse("redrawn in the hand", h.frame()) }
        // Hanging from the plug toward the earbuds, above it: the cord rises out of the frame.
        val rig = h.rig
        assertTrue("the far end is up by your ears", rig.shownY[Cord.NODES - 1] > 0.3f)
    }

    /** The resting curve over [top], laid from where the plug lies at rest — as the rig lays it. */
    private fun restCurve(top: (Float, Float) -> Float): Triple<FloatArray, FloatArray, FloatArray> {
        val b = CordRig.restBasis()
        val sx = RestPose.DEVICE_X + b[0] * Cord.PLUG_X_M + b[3] * Cord.PLUG_TIP_Y_M
        val sy = RestPose.DEVICE_Y + b[1] * Cord.PLUG_X_M + b[4] * Cord.PLUG_TIP_Y_M
        val sz = RestPose.DEVICE_Z + b[2] * Cord.PLUG_X_M + b[5] * Cord.PLUG_TIP_Y_M
        val level = sqrt(b[3] * b[3] + b[5] * b[5])
        val xs = FloatArray(Cord.NODES)
        val ys = FloatArray(Cord.NODES)
        val zs = FloatArray(Cord.NODES)
        RestCord.lay(sx, sy, sz, b[3] / level, b[5] / level, Cord.LENGTH_M / (Cord.NODES - 1), Cord.NODES,
            CordRig.TURN, top, xs, ys, zs)
        return Triple(xs, ys, zs)
    }

    @Test
    fun `lying on the cloth, it lies exactly on the resting curve`() {
        val h = Handover().lying()
        val (xs, ys, zs) = restCurve(h.top)
        for (i in 0 until Cord.NODES) {
            assertEquals("node $i x", xs[i], h.rig.shownX[i], 1e-5f)
            assertEquals("node $i y", ys[i], h.rig.shownY[i], 1e-5f)
            assertEquals("node $i z", zs[i], h.rig.shownZ[i], 1e-5f)
        }
    }

    @Test
    fun `what lies on the cloth does not slide on it while the device comes down onto it`() {
        // The drape's first version kept the cord's length exact by sliding what lay back along its
        // curve, one way and then the other as the device moved: a cord on a table shivering. What
        // has touched the cloth stays where it touched — a tenth of a millimetre a frame at most,
        // for the stretch just settling at the touch-down.
        val h = Handover()
        h.run(1f)
        h.flight.target = 1f
        val wasOn = BooleanArray(Cord.NODES)
        val lastX = FloatArray(Cord.NODES)
        val lastY = FloatArray(Cord.NODES)
        val lastZ = FloatArray(Cord.NODES)
        repeat(360) { frame ->
            h.frame()
            for (i in 2 until Cord.NODES) {
                val x = h.rig.shownX[i]; val y = h.rig.shownY[i]; val z = h.rig.shownZ[i]
                val on = abs(y - (h.top(x, z) + Cord.RADIUS_M)) < 1e-5f
                if (on && wasOn[i]) {
                    val moved = sqrt((x - lastX[i]).let { it * it } + (y - lastY[i]).let { it * it } + (z - lastZ[i]).let { it * it })
                    assertTrue("node $i slid ${moved * 1000}mm on the cloth, frame $frame", moved < 1e-4f)
                }
                wasOn[i] = on
                lastX[i] = x; lastY[i] = y; lastZ[i] = z
            }
        }
    }

    @Test
    fun `the cord never passes through the device, going down or coming up`() {
        // The resting curve laid afresh from the plug as the device stood up out of it ran down
        // across the device's face. Every node, every frame, both ways and turned round half way.
        val h = Handover()
        h.run(1f)
        fun check(label: String) {
            val b = h.basis
            for (i in 0 until Cord.NODES) {
                val px = h.rig.shownX[i] - h.centreX
                val py = h.rig.shownY[i] - h.centreY
                val pz = h.rig.shownZ[i] - h.centreZ
                // Into the device's frame (its axes are orthonormal): across, along, out.
                val lx = b[0] * px + b[1] * py + b[2] * pz
                val ly = b[3] * px + b[4] * py + b[5] * pz
                val lz = b[6] * px + b[7] * py + b[8] * pz
                val inside = kotlin.math.abs(lx) < HALF_W + Cord.RADIUS_M &&
                    kotlin.math.abs(ly) < HALF_H + Cord.RADIUS_M &&
                    kotlin.math.abs(lz) < HALF_T + Cord.RADIUS_M
                assertFalse("$label: node $i inside the device at ($lx, $ly, $lz)", inside)
            }
        }
        h.flight.target = 1f
        repeat(360) { h.frame(); check("going down") }
        h.flight.target = 0f
        repeat(240) { h.frame(); check("coming up") }
        h.flight.target = 1f
        repeat(40) { h.frame(); check("going down again") }
        h.flight.target = 0f
        repeat(240) { h.frame(); check("turned round") }
    }

    @Test
    fun `the cord is never drawn under the cloth`() {
        val h = Handover()
        h.run(1f)
        fun check(label: String) {
            // Past the plug: its first two nodes are the plug's, wherever the device holds it.
            for (i in 2 until Cord.NODES) {
                val x = h.rig.shownX[i]; val z = h.rig.shownZ[i]
                val floor = h.top(x, z) + Cord.RADIUS_M
                assertTrue("$label: node $i ${(floor - h.rig.shownY[i]) * 1000}mm under the cloth", h.rig.shownY[i] >= floor - 1e-5f)
            }
        }
        h.flight.target = 1f
        repeat(360) { h.frame(); check("going down") }
        h.flight.target = 0f
        repeat(240) { h.frame(); check("coming up") }
    }

    private companion object {
        /** Half the device's width, height and thickness — `POCKET_*` in `build_pocket()`. */
        const val HALF_W = 0.0309f
        const val HALF_H = 0.05175f
        const val HALF_T = 0.00525f
    }
}
