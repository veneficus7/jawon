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
 * with the phone's own tilt. At rest the camera eases back into the carriage and the device lifts
 * off the table to float in the lamp's light. The motion the owner asked for lives in the second
 * state and the move between them, so it never fights the hand holding the phone.
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

    /** A finger on the screen: back to the hand, and the clock starts again. */
    fun touched(now: Long) {
        activeAt = now
        atRest = false
        liftable = false
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
            // Picked up off the table: back to the hand.
            if (atRest && liftable) {
                atRest = false
                liftable = false
                activeAt = now
            }
        }
        val flatLongEnough = lyingFlat && now - flatSince >= flatHoldMs
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
    /** The device over the middle of the table, its lower edge about four centimetres above it. */
    const val DEVICE_X = 0.0f
    const val DEVICE_Y = -0.205f
    const val DEVICE_Z = -0.25f
    /**
     * Nearly upright and turned toward the lamp. **Not leaning back**: at −14° the face reflected
     * the reading lamp's corner of the carriage, and the graphite read beige from across the table.
     */
    const val DEVICE_PITCH_DEG = -4f
    const val DEVICE_YAW_DEG = 18f

    /**
     * The camera, eased back and a little down: the table filling the foot of the frame, the device
     * over it, the window above. Further back, the table's front edge came into frame with nothing
     * under it.
     */
    const val CAMERA_X = 0.035f
    const val CAMERA_Y = -0.11f
    const val CAMERA_Z = 0.47f

    /** How far the float wanders: millimetres up and down, a few degrees of turn. */
    const val FLOAT_Y_M = 0.004f
    const val FLOAT_YAW_DEG = 7f
    const val FLOAT_PITCH_DEG = 2.5f
    const val FLOAT_ROLL_DEG = 1.8f
}
