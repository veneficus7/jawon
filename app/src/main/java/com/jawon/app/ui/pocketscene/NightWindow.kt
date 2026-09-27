package com.jawon.app.ui.pocketscene

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The window behind the pocket player: where it is in the room, and how the night outside moves.
 *
 * The picture itself is `materials/night_window.mat` — lights as the out-of-focus discs a lens
 * makes of them, poles as soft sweeps, the sky as a gradient. What lives here is everything the
 * shader is told: how far each depth has travelled, and how the carriage rocks. Pure, so the
 * motion can be reasoned about without a device.
 */
object NightWindow {

    /** Where the window's picture hangs: this far behind the device, facing it. */
    const val PLANE_Z_M = -0.55f

    /**
     * The picture's size. In the hand the frame at that distance is ~0.41m tall and ~0.19m wide on
     * a 19.5:9 phone; at rest the camera pulls back and the whole of the carriage's window shows —
     * its opening is 0.80 × 0.55m, 5cm in front of this — so the picture is wider than the opening
     * with room to spare for the camera's parallax.
     */
    const val PLANE_W_M = 1.0f
    const val PLANE_H_M = 0.8f

    /**
     * How fast the train goes, m/s — about 58km/h. A calm speed, on purpose: at intercity speed
     * the middle distance smears across the window in a fraction of a second, and this is a room
     * to sit in.
     */
    const val BASE_SPEED_M_S = 16f

    /**
     * Where each layer of the outside *is*, in metres from the window: the far lights, the
     * middle distance, the near lights, and the poles along the track. A light at distance `d`
     * crosses the picture at `speed × D / d`, D being the picture's own distance from the eye —
     * which is all parallax is.
     */
    val LAYER_DEPTH_M = floatArrayOf(420f, 85f, 26f, 4.2f)

    /**
     * Each layer's cell width in the shader, in metres of picture — `lights(…, vec2(cell…))` and
     * `poles`' `cell`. **Must agree with the `.mat`**: the scroll is wrapped at exactly
     * [PERIOD_CELLS] of them, and a wrap at any other length is a visible jump.
     */
    val LAYER_CELL_M = floatArrayOf(0.050f, 0.072f, 0.110f, 0.95f)

    /** The shader's `PERIOD`: cells repeat every this many columns. */
    const val PERIOD_CELLS = 4096.0

    /** How far the picture is from the eye, for parallax: the camera is ~0.3m in front of the device. */
    private const val PICTURE_DISTANCE_M = 0.85f

    /** Rail joints go by every this many metres — the "clickety" the carriage rocks to. */
    const val JOINT_SPACING_M = 25f
}

/**
 * The train: its speed drifting as a real one's does, the distance it has covered, and the rock
 * of the carriage.
 *
 * **Nothing here is periodic in a way the eye can find.** The speed is three slow sines at
 * incommensurate rates, so it never settles into a rhythm; the bob is the same, plus a small
 * jolt at every rail joint — which *is* regular, as it is on a real track, and is the one
 * rhythm a train is allowed to have.
 */
class TrainMotion(private val baseSpeed: Float = NightWindow.BASE_SPEED_M_S) {

    private var t = 0.0
    private var travelled = 0.0
    private var lastJoint = 0.0
    private var jointAge = 10.0

    /** Seconds since the train set off. */
    val time: Double get() = t

    /** Metres covered. */
    val distance: Double get() = travelled

    /** The speed right now, m/s. Always well above zero — this train does not stop. */
    fun speed(at: Double = t): Float {
        val drift = 0.12 * sin(0.071 * at) + 0.07 * sin(0.193 * at + 1.3) + 0.04 * sin(0.437 * at + 2.1)
        return (baseSpeed * (1.0 + drift)).toFloat()
    }

    fun advance(dtS: Float) {
        if (dtS <= 0f) return
        val dt = dtS.coerceAtMost(0.1f).toDouble()
        // Midpoint speed: exact enough, and the same distance whatever the frame rate.
        travelled += speed(t + dt / 2.0) * dt
        t += dt
        jointAge += dt
        val joint = floor(travelled / NightWindow.JOINT_SPACING_M)
        if (joint > lastJoint) {
            lastJoint = joint
            jointAge = 0.0
        }
    }

    /**
     * How far layer [layer] of the picture has scrolled, in metres of picture, wrapped at exactly
     * the shader's period so the value stays small and the wrap cannot be seen.
     */
    fun scroll(layer: Int): Float {
        val factor = PICTURE_DISTANCE / NightWindow.LAYER_DEPTH_M[layer]
        val period = NightWindow.LAYER_CELL_M[layer] * NightWindow.PERIOD_CELLS
        val s = travelled * factor
        return (s - floor(s / period) * period).toFloat()
    }

    /**
     * The carriage's rock, as the outside's offset in metres of picture: a slow sway, a faster
     * shimmy, and a damped jolt as each rail joint passes under the bogie.
     */
    fun bob(): Pair<Float, Float> {
        val sway = 0.0011 * sin(0.9 * t + 0.4) + 0.0006 * sin(2.3 * t)
        val bounce = 0.0009 * sin(1.7 * t + 1.1) + 0.0005 * sin(3.9 * t + 0.3) + 0.0003 * sin(7.1 * t)
        return sway.toFloat() to (bounce + jolt()).toFloat()
    }

    /** The last rail joint's jolt, dying away: a sharp 6Hz shake gone within a quarter second. */
    fun jolt(): Double = JOLT_M * exp(-jointAge * 9.0) * sin(jointAge * 2.0 * PI * 6.0)

    companion object {
        private const val PICTURE_DISTANCE = 0.85

        /** The jolt's peak, in metres of picture. */
        const val JOLT_M = 0.0016
    }
}
