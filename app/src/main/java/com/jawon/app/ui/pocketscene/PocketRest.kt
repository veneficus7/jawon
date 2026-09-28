package com.jawon.app.ui.pocketscene

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The device's two states: **in the hand** and **at rest** (`docs/formats.md` §4, decided
 * 2026-09-27).
 *
 * In the hand it is the device the phone has become — life size, still, the reflections moving
 * with the phone's own tilt. At rest the camera rises over the carriage's table and the device is
 * set down on the sheet there, lying flat, seen from above, the way an iPod is photographed (it
 * floated over the table first; the owner's reference photos replaced that). The motion lives in
 * the move between the two, so it never fights the hand holding the phone.
 *
 * **When it rests**: twenty seconds untouched, or the phone laid flat and still for a second —
 * put down on a table. **When it comes back**: any touch, or the phone picked up off the table.
 * The clock that counts to twenty restarts from a pick-up as from a touch, or a phone lifted
 * after a long rest would fall straight back into it.
 */
class RestState(
    private val untouchedMs: Long = UNTOUCHED_MS,
    private val flatHoldMs: Long = FLAT_HOLD_MS
) {
    var atRest: Boolean = false
        private set

    private var flatSince = -1L
    private var liftable = false
    private var activeAt = -1L
    /**
     * Whether lying flat may put it to rest. A touch disarms it until the phone has been lifted
     * and put down again: touching a phone that lies on the table means using it there, and without
     * this the device came back to the hand and flew straight off to the table a second later.
     */
    private var flatArmed = true

    /** A finger on the screen: back to the hand, and the clock starts again. */
    fun touched(now: Long) {
        activeAt = now
        atRest = false
        liftable = false
        flatArmed = false
    }

    /**
     * One frame. [lyingFlat] is the phone face up and still — on a table, not in a hand held
     * level, which is never still. Returns whether the device is at rest.
     */
    fun update(now: Long, lyingFlat: Boolean): Boolean {
        if (activeAt < 0L) activeAt = now
        if (lyingFlat) {
            if (flatSince < 0L) flatSince = now
        } else {
            flatSince = -1L
            flatArmed = true
            // Picked up off the table: back to the hand.
            if (atRest && liftable) {
                atRest = false
                liftable = false
                activeAt = now
            }
        }
        val flatLongEnough = lyingFlat && flatArmed && now - flatSince >= flatHoldMs
        if (atRest) {
            // Resting on the clock, then put down: lifting it now should wake it too.
            if (flatLongEnough) liftable = true
        } else if (flatLongEnough) {
            atRest = true
            liftable = true
        } else if (now - activeAt >= untouchedMs) {
            atRest = true
            liftable = false
        }
        return atRest
    }

    companion object {
        const val UNTOUCHED_MS = 20_000L
        const val FLAT_HOLD_MS = 1_000L
    }
}

/**
 * The move between the hand and the table, as one number: how far along it is, 0 in the hand and
 * 1 at rest. A critically damped spring drives it, so it arrives without a bounce and reverses
 * smoothly from wherever it has got to.
 *
 * **Overlapped beats, as `OpenSequence`'s are**: going out, the camera leads — easing back before
 * the device has left the hand — and the device lands after it. Coming back is quicker and both
 * move at once, the way something is picked up rather than set down.
 */
class RestFlight {
    var progress = 0f
        private set
    private var velocity = 0f
    var target = 0f

    val isMoving: Boolean
        get() = abs(target - progress) > SETTLED_AT || abs(velocity) > SETTLED_SPEED

    fun step(dt: Float) {
        if (dt <= 0f) return
        val omega = if (target > progress) OMEGA_OUT else OMEGA_BACK
        // Sub-stepped so a long frame cannot overshoot a critically damped spring.
        val steps = maxOf(1, (dt / MAX_STEP).toInt() + 1)
        val h = dt / steps
        repeat(steps) {
            val accel = -2f * omega * velocity - omega * omega * (progress - target)
            velocity += accel * h
            progress += velocity * h
        }
        if (!isMoving) {
            progress = target
            velocity = 0f
        }
    }

    /** How far the camera has gone: it leads going out. */
    val camera: Float get() = smooth((progress / CAMERA_SPAN).coerceIn(0f, 1f))

    /** How far the device has gone: it follows going out, and lands last. */
    val device: Float get() = smooth(((progress - DEVICE_DELAY) / (1f - DEVICE_DELAY)).coerceIn(0f, 1f))

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    companion object {
        /** Stiffness out and back, rad/s: out settles in about a second and a half, back in ~0.7s. */
        const val OMEGA_OUT = 3.2f
        const val OMEGA_BACK = 6.5f
        const val CAMERA_SPAN = 0.85f
        const val DEVICE_DELAY = 0.15f
        /** Close enough to stop: a twentieth of a percent of the flight, well under a millimetre. */
        private const val SETTLED_AT = 5e-4f
        private const val SETTLED_SPEED = 2e-3f
        private const val MAX_STEP = 1f / 240f
    }
}

/**
 * A slow wander, for the device floating at rest: smooth value noise over time — random keys a
 * [periodS] apart, eased between — so it drifts and never repeats, which a sum of sines does and
 * which is the tell of a rendered float (`docs/realism.md` §3). −1…1.
 */
class Wander(seed: Int, private val periodS: Float) {
    private val keys = FloatArray(KEYS) { i -> hash(seed * 7919 + i) * 2f - 1f }

    fun at(timeS: Float): Float {
        val x = timeS / periodS
        val i = floor(x).toInt()
        val f = x - i
        val a = keys[Math.floorMod(i, KEYS)]
        val b = keys[Math.floorMod(i + 1, KEYS)]
        val s = f * f * (3f - 2f * f)
        return a + (b - a) * s
    }

    private fun hash(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    private companion object {
        const val KEYS = 257
    }
}

/**
 * The camera's rotation for looking from [from] toward [to], as SceneView's Euler angles in
 * degrees (x pitch, y yaw) — its rotation order puts the lens down −Z, turned by yaw and then
 * pitched. Written into [out] as (pitch, yaw).
 */
fun lookAngles(fromX: Float, fromY: Float, fromZ: Float, toX: Float, toY: Float, toZ: Float, out: FloatArray) {
    val dx = toX - fromX
    val dy = toY - fromY
    val dz = toZ - fromZ
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    if (len < 1e-6f) {
        out[0] = 0f
        out[1] = 0f
        return
    }
    out[0] = Math.toDegrees(asin((dy / len).coerceIn(-1f, 1f)).toDouble()).toFloat()
    out[1] = Math.toDegrees(atan2(-dx, -dz).toDouble()).toFloat()
}

/** Linear interpolation, for the poses. */
internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Exponential approach, frame-rate independent: how far to move toward a target in [dt]. */
internal fun approach(rate: Float, dt: Float): Float = 1f - exp(-rate * dt)

/**
 * Whether the phone is lying face up and still — put down on a table — from the game rotation
 * vector's quaternion. **Still matters as much as flat**: a phone held level in the hand is flat
 * but never still, and must not put the device to rest while it is being read.
 */
class FlatDetector {
    var lyingFlat: Boolean = false
        private set

    private var px = 0f
    private var py = 0f
    private var pz = 0f
    private var pw = 1f
    private var primed = false
    private var speed = 1f

    /** One sensor sample: the device-to-world rotation as a quaternion, and the time since the last. */
    fun update(x: Float, y: Float, z: Float, w: Float, dt: Float): Boolean {
        // The screen's normal in the world, z component: 1 lying face up, 0 held upright.
        val up = 1f - 2f * (x * x + y * y)
        if (primed && dt > 0f) {
            val dot = abs(x * px + y * py + z * pz + w * pw).coerceAtMost(1f)
            val turned = 2f * kotlin.math.acos(dot)
            val k = approach(1f / STILL_TAU_S, dt)
            speed += (turned / dt - speed) * k
        }
        px = x; py = y; pz = z; pw = w
        primed = true
        lyingFlat = up >= FLAT_COS && speed < STILL_RAD_S
        return lyingFlat
    }

    fun reset() {
        primed = false
        speed = 1f
        lyingFlat = false
    }

    companion object {
        /** Within 15° of face up. */
        val FLAT_COS = kotlin.math.cos(Math.toRadians(15.0)).toFloat()
        /** A hand's tremor is a few hundredths of a radian a second; a table's is nothing. */
        const val STILL_RAD_S = 0.02f
        const val STILL_TAU_S = 0.3f
    }
}

/**
 * Where things are at rest, in the room's metres (+Y up, +Z toward the viewer, the device held at
 * the origin). The table top is at `CARRIAGE_TABLE_TOP_Y` in `build_carriage()`, −0.30.
 */
object RestPose {
    /** The table's top — `CARRIAGE_TABLE_TOP_Y` in `build_carriage()`. */
    const val TABLE_TOP_Y = -0.30f

    /**
     * Where the device lies, over the table: on the sheet, pressed flat under it —
     * `SHEET_DEVICE_T` in `build_sheet()`, which `SheetSurfaceTest` reads back out of the map.
     */
    const val SURFACE_T = 0.014f

    /** Half the device's thickness — `POCKET_T` in `build_pocket()`. */
    private const val HALF_THICKNESS = 0.00525f

    /**
     * **Lying flat, seen from above** — the owner's call from his reference photos (2026-09-27):
     * the way an iPod is photographed, put down on a bed or a blanket, in place of the float it
     * had before. Face up on the middle of the table, its top toward the window so the cord runs
     * away up the frame, turned a little off square as a thing put down is.
     */
    const val DEVICE_X = 0.0f
    const val DEVICE_Y = TABLE_TOP_Y + SURFACE_T + HALF_THICKNESS
    const val DEVICE_Z = -0.26f
    const val DEVICE_PITCH_DEG = -90f
    const val DEVICE_YAW_DEG = 10f

    /**
     * The camera over it, looking down at about 75° from 65cm: the device a third of the frame's
     * height, as it is in the owner's reference photos, with room round it for the cloth and the
     * cord. At 44cm it filled half the frame and there was nowhere for either to be.
     */
    const val CAMERA_X = 0.02f
    const val CAMERA_Y = DEVICE_Y + 0.63f
    const val CAMERA_Z = DEVICE_Z + 0.17f
}

/**
 * **The carriage's light, turned for the table.** The room's environment ([PocketLighting]) is
 * placed for the device in the hand: its reading lamp is a softbox behind you, over your right
 * shoulder, where the face reflects it. Lying on the table the device looks up instead, and with
 * the environment left where it was the flat lay was lit from two places at once — the softbox lit
 * the cloth from the bottom right of the frame while the lamp that casts the shadows hangs at its
 * top right, and the screen's glass showed a ceiling light the shadows knew nothing about.
 *
 * So as the device goes down the environment turns with it — by the turn that shows the resting
 * device **the same room it sees in the hand**: its glass meeting the softbox's lower edge in the
 * same place, lined up the same way along the device, so the face keeps the gradient and the glass
 * the sheen that were tuned on the owner's phone with the device in his hand. Then the whole of it
 * swings [SWING_DEG] about the vertical, which carries the softbox from straight over the top of
 * the frame round to its upper right while moving what the face sees by only about a quarter as
 * much — the face looks nearly straight up, and a turn about the vertical barely moves a vertical
 * direction. The lamp that casts the shadows hangs where the softbox ends up ([restLamp]). Then
 * the shadow, the light on the sheet and every reflection on the device agree about the lamp.
 *
 * **Chosen from previews** of what the face and the glass see, then measured on the emulator.
 * Turned so the softbox sat squarely on the lamp's own direction, the face lit evenly, top and
 * bottom within 2%, and read as matt plastic; unswung, the lamp stood straight over the top of the
 * frame and the shadow fell under the device, where the camera cannot see it.
 *
 * Pure. Matrices are column-major 3×3, as `IndirectLight.setRotation` takes them.
 */
object RestLight {
    /** How far the room swings about the vertical at rest, degrees; negative swings it right. */
    const val SWING_DEG = -45f

    /** How far from the resting device the lamp hangs, along the swung softbox, metres. */
    const val LAMP_DISTANCE_M = 0.6f

    /** The whole of the turn at rest. */
    val atRest: FloatArray by lazy { FloatArray(9).also { restTurn(SWING_DEG, it) } }

    /** Where the lamp hangs over the resting device, in the room. */
    val restLamp: FloatArray by lazy {
        val softbox = FloatArray(3).also { PocketLighting.softboxDirection(it) }
        val m = atRest
        floatArrayOf(
            RestPose.DEVICE_X + LAMP_DISTANCE_M * (m[0] * softbox[0] + m[3] * softbox[1] + m[6] * softbox[2]),
            RestPose.DEVICE_Y + LAMP_DISTANCE_M * (m[1] * softbox[0] + m[4] * softbox[1] + m[7] * softbox[2]),
            RestPose.DEVICE_Z + LAMP_DISTANCE_M * (m[2] * softbox[0] + m[5] * softbox[1] + m[8] * softbox[2])
        )
    }

    /**
     * The turn at rest, swung [swingDeg] about the vertical, into [out]: the frame of the glass's
     * reflection and the device's length in the hand, taken onto the same pair at rest.
     */
    fun restTurn(swingDeg: Float, out: FloatArray) {
        // In the hand: at the origin, turned, seen straight on down −Z.
        val hand = deviceBasis(DEVICE_PITCH_DEG, DEVICE_YAW_DEG)
        val handMirror = reflect(floatArrayOf(0f, 0f, -1f), column(hand, 2))
        // At rest: lying on the sheet, seen from the camera over it.
        val rest = deviceBasis(RestPose.DEVICE_PITCH_DEG, RestPose.DEVICE_YAW_DEG)
        val view = unit(
            floatArrayOf(
                RestPose.DEVICE_X - RestPose.CAMERA_X,
                RestPose.DEVICE_Y - RestPose.CAMERA_Y,
                RestPose.DEVICE_Z - RestPose.CAMERA_Z
            )
        )
        val restMirror = reflect(view, column(rest, 2))
        val matched = FloatArray(9)
        multiplyTransposed(frame(restMirror, column(rest, 1)), frame(handMirror, column(hand, 1)), matched)
        multiply(yaw(swingDeg), matched, out)
    }

    /** A device turned by SceneView's Euler angles: `Ry(yaw) · Rx(pitch)`. */
    private fun deviceBasis(pitchDeg: Float, yawDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble())
        val cp = kotlin.math.cos(p).toFloat()
        val sp = kotlin.math.sin(p).toFloat()
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cp, sp, 0f, -sp, cp)
        val out = FloatArray(9)
        multiply(yaw(yawDeg), rx, out)
        return out
    }

    /** A turn of [deg] about +Y. */
    private fun yaw(deg: Float): FloatArray {
        val y = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(y).toFloat()
        val s = kotlin.math.sin(y).toFloat()
        return floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c)
    }

    private fun column(m: FloatArray, i: Int) = floatArrayOf(m[i * 3], m[i * 3 + 1], m[i * 3 + 2])

    private fun reflect(v: FloatArray, n: FloatArray): FloatArray {
        val d = 2f * (v[0] * n[0] + v[1] * n[1] + v[2] * n[2])
        return floatArrayOf(v[0] - d * n[0], v[1] - d * n[1], v[2] - d * n[2])
    }

    /** The frame [r], [up] made square to it, and their cross, as columns. */
    private fun frame(r: FloatArray, up: FloatArray): FloatArray {
        val a = unit(r)
        val d = up[0] * a[0] + up[1] * a[1] + up[2] * a[2]
        val b = unit(floatArrayOf(up[0] - d * a[0], up[1] - d * a[1], up[2] - d * a[2]))
        val c = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        return floatArrayOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2])
    }

    /** [full] turned only [t] of the way — the same axis, [t] of the angle — into [out]. */
    fun partial(full: FloatArray, t: Float, out: FloatArray) {
        val trace = full[0] + full[4] + full[8]
        val angle = kotlin.math.acos(((trace - 1f) / 2f).coerceIn(-1f, 1f))
        val s = kotlin.math.sin(angle)
        if (t <= 0f || angle < 1e-6f || s < 1e-6f) {
            if (t >= 1f) full.copyInto(out) else identity(out)
            return
        }
        // Column-major: R[row][col] = m[col * 3 + row].
        val x = (full[5] - full[7]) / (2f * s)
        val y = (full[6] - full[2]) / (2f * s)
        val z = (full[1] - full[3]) / (2f * s)
        val a = angle * t.coerceAtMost(1f)
        rodrigues(x, y, z, kotlin.math.sin(a), 1f - kotlin.math.cos(a), out)
    }

    /** a · b, column-major, into [out] — which must be neither. */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (col in 0..2) for (row in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[k * 3 + row] * b[col * 3 + k]
            out[col * 3 + row] = sum
        }
    }

    /** a · bᵀ, column-major, into [out] — which must be neither. */
    fun multiplyTransposed(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (col in 0..2) for (row in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[k * 3 + row] * b[k * 3 + col]
            out[col * 3 + row] = sum
        }
    }

    private fun identity(out: FloatArray) {
        for (i in 0..8) out[i] = if (i % 4 == 0) 1f else 0f
    }

    /** I + sin·K + versin·K², for K the cross-product matrix of (x, y, z), column-major. */
    private fun rodrigues(x: Float, y: Float, z: Float, sin: Float, versin: Float, out: FloatArray) {
        // K = [[0, −z, y], [z, 0, −x], [−y, x, 0]]; K² = a·aᵀ − |a|²·I.
        val n2 = x * x + y * y + z * z
        val r00 = 1f + versin * (x * x - n2)
        val r11 = 1f + versin * (y * y - n2)
        val r22 = 1f + versin * (z * z - n2)
        val r01 = -sin * z + versin * x * y
        val r02 = sin * y + versin * x * z
        val r10 = sin * z + versin * x * y
        val r12 = -sin * x + versin * y * z
        val r20 = -sin * y + versin * x * z
        val r21 = sin * x + versin * y * z
        out[0] = r00; out[1] = r10; out[2] = r20
        out[3] = r01; out[4] = r11; out[5] = r21
        out[6] = r02; out[7] = r12; out[8] = r22
    }

    private fun unit(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9f)
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}
