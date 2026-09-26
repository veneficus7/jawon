package com.jawon.app.ui.pocketscene

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How far the phone has just been tilted, from wherever it is being held.
 *
 * The phone *is* the device in this room, so tilting it should tilt the device against the
 * world: the carriage's reflections sliding across the glass and the polished edge, the window
 * shifting behind. What is wanted is the tilt *away from how it is held*, not the phone's
 * absolute attitude — someone lying down and someone standing both hold it at "neutral" — so the
 * orientation is measured against a baseline that follows it slowly, and only the difference is
 * reported. The difference decays as the baseline catches up, which is also what keeps a slow
 * drift in the sensor from ever becoming a permanent lean.
 *
 * **Quaternions, not Euler angles.** A phone held upright is at a pitch of about -90°, which is
 * exactly where Euler angles lock and roll starts spinning freely. The relative rotation between
 * two quaternions has no such place.
 *
 * Pure: fed unit quaternions (x, y, z, w) from the game rotation vector, it hands back small
 * angles in radians about the phone's own X (top edge toward you, positive) and Y (right edge
 * away from you, positive — the right-hand rule about an axis pointing up the screen), clamped.
 */
class TiltFilter(
    /** How quickly "neutral" follows the phone, in seconds. */
    private val followS: Float = 1.6f,
    /** The largest tilt reported either way, radians. */
    private val limitRad: Float = 0.30f
) {
    private val ref = FloatArray(4)
    private var hasRef = false

    /** Radians about the phone's X axis: the top edge tipped toward you is positive. */
    var pitch = 0f
        private set

    /** Radians about the phone's Y axis: the right edge turned away from you is positive. */
    var yaw = 0f
        private set

    /** Takes one reading, [dtS] after the last. */
    fun update(x: Float, y: Float, z: Float, w: Float, dtS: Float) {
        if (!hasRef) {
            set(ref, x, y, z, w)
            normalise(ref)
            hasRef = true
            pitch = 0f
            yaw = 0f
            return
        }
        // delta = ref⁻¹ · now, in the phone's own frame. For a small rotation its vector part is
        // half the angle about each axis.
        val dx = ref[3] * x - ref[0] * w - ref[1] * z + ref[2] * y
        val dy = ref[3] * y - ref[1] * w - ref[2] * x + ref[0] * z
        var dw = ref[3] * w + ref[0] * x + ref[1] * y + ref[2] * z
        var sx = dx
        var sy = dy
        // The same rotation either way round: keep w positive so the angles are the short way.
        if (dw < 0f) {
            sx = -sx
            sy = -sy
            dw = -dw
        }
        pitch = (2f * sx).coerceIn(-limitRad, limitRad)
        yaw = (2f * sy).coerceIn(-limitRad, limitRad)

        // Neutral follows the phone: a normalised lerp toward it, which for the small steps a
        // frame takes is a slerp in all but name.
        val k = 1f - exp(-dtS.coerceAtLeast(0f) / followS)
        val flip = if (ref[0] * x + ref[1] * y + ref[2] * z + ref[3] * w < 0f) -1f else 1f
        ref[0] += (flip * x - ref[0]) * k
        ref[1] += (flip * y - ref[1]) * k
        ref[2] += (flip * z - ref[2]) * k
        ref[3] += (flip * w - ref[3]) * k
        normalise(ref)
    }

    /** Forgets the baseline: the next reading is neutral again — on returning to the room. */
    fun reset() {
        hasRef = false
        pitch = 0f
        yaw = 0f
    }

    private fun set(q: FloatArray, x: Float, y: Float, z: Float, w: Float) {
        q[0] = x; q[1] = y; q[2] = z; q[3] = w
    }

    private fun normalise(q: FloatArray) {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-6f) {
            set(q, 0f, 0f, 0f, 1f)
            return
        }
        for (i in 0..3) q[i] /= n
    }
}
