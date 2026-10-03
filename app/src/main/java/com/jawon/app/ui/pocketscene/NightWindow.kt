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

    /**
     * **The stations** — where the train stops when the music does (`docs/formats.md` §4). Half a
     * platform's length, metres: the window is alongside it anywhere within this of its middle.
     * **Must agree with `night_window.mat`'s `PLATFORM_HALF`.**
     */
    const val PLATFORM_HALF_M = 24f

    /** How far from the window a station is drawn at all, metres along the line, either way. */
    const val STATION_RANGE_M = 70f

    /** How far from the window a tunnel is reported to the picture, metres along the line, either way. */
    const val TUNNEL_RANGE_M = 60.0

    /**
     * A tunnel's lamps are this far apart along its wall, the first half that in from the mouth.
     * **Must agree with `night_window.mat`'s `TUNNEL_LAMP_SPACING`.**
     */
    const val TUNNEL_LAMP_SPACING_M = 14.0
}

/**
 * The train: its speed drifting as a real one's does, the distance it has covered, the rock of
 * the carriage — **and it follows the music.** While something plays it runs; paused, it brakes
 * into a station and stands at the platform; played again, it pulls away.
 *
 * **Nothing here is periodic in a way the eye can find.** The cruising speed is three slow sines at
 * incommensurate rates, so it never settles into a rhythm; the bob is the same, plus a small
 * jolt at every rail joint — which *is* regular, as it is on a real track, and is the one
 * rhythm a train is allowed to have.
 *
 * **Braking is the S-curve a driver brakes to**: the speed falls as `1 − smoothstep`, so the
 * deceleration builds from nothing, peaks halfway and eases back to nothing as the train stands —
 * no jolt going in, no creep at the end. The station is placed where that curve ends, half the
 * distance the train would have covered at speed, so it comes to rest with the platform's middle
 * at the window; and the curve is a function of time, not a step, so a stop is the same at 60 and
 * at 120 frames a second. A critically damped spring was the first version: it never quite
 * arrives, and the platform was still visibly creeping twelve seconds after the pause.
 */
class TrainMotion(
    private val baseSpeed: Float = NightWindow.BASE_SPEED_M_S,
    running: Boolean = true,
    /** What the line is made of — its tunnels. Seeded, so a ride can be replayed. */
    private val line: NightLine = NightLine()
) {

    private enum class Mode { RUNNING, BRAKING, STOPPED, DEPARTING }

    private var t = 0.0
    private var travelled = 0.0
    private var v = 0.0
    private var mode = Mode.RUNNING
    private var lastJoint = 0.0
    private var jointAge = 10.0

    /** The platform's middle, metres along the line — NaN when there is no station about. */
    private var station = Double.NaN

    /**
     * Where the train is to stand. The brake begins where its S-curve from the speed then will end
     * exactly here — straight away, usually, but a stop put beyond a tunnel is cruised to first.
     */
    private var stopAt = 0.0
    private var braking = false

    /** The brake: where it began, how fast the train was going, how long it takes, how far in. */
    private var brakeFrom = 0.0
    private var brakeSpeed = 0.0
    private var brakeFor = 1.0
    private var brakeT = 0.0

    private var departFrom = 0.0
    private var departFor = DEPART_S
    private var departT = 0.0
    private var stoppedAge = SETTLE_GONE_S

    init {
        placeAt(running)
    }

    /** Seconds since the train set off. */
    val time: Double get() = t

    /** Metres covered. */
    val distance: Double get() = travelled

    /** Whether it is standing at a platform. */
    val stopped: Boolean get() = mode == Mode.STOPPED

    /**
     * How far ahead of the window the station's middle is, metres along the line — negative once
     * it is behind — or NaN when there is none within [NightWindow.STATION_RANGE_M].
     */
    val stationAhead: Float
        get() {
            if (station.isNaN()) return Float.NaN
            val ahead = station - travelled
            return if (kotlin.math.abs(ahead) > NightWindow.STATION_RANGE_M) Float.NaN else ahead.toFloat()
        }

    /** The cruising speed at time [at], m/s: always well above zero — at speed, this train does not stop. */
    fun cruise(at: Double = t): Float {
        val drift = 0.12 * sin(0.071 * at) + 0.07 * sin(0.193 * at + 1.3) + 0.04 * sin(0.437 * at + 2.1)
        return (baseSpeed * (1.0 + drift)).toFloat()
    }

    /** The speed right now, m/s — nought at a platform. */
    fun speed(): Float = v.toFloat()

    /** How far the train runs on the S-curve from [speed] to a stand, metres. */
    private fun brakeDistance(speed: Double): Double =
        speed * (speed / BRAKE_MEAN_M_S2).coerceAtLeast(BRAKE_MIN_S) / 2.0

    /**
     * The nearest tunnel's mouth and exit, metres ahead of the window (negative behind), or NaN
     * when there is none within [NightWindow.TUNNEL_RANGE_M] — for the window to draw.
     */
    val tunnelEntryAhead: Float get() = tunnelEdge(entry = true)
    val tunnelExitAhead: Float get() = tunnelEdge(entry = false)

    private fun tunnelEdge(entry: Boolean): Float {
        val near = line.tunnelNear(travelled, NightWindow.TUNNEL_RANGE_M) ?: return Float.NaN
        return ((if (entry) near.from else near.to) - travelled).toFloat()
    }

    /**
     * How far into a tunnel the carriage is, 0…1: 1 inside, over its last few metres at each mouth
     * as the window passes the portal. What the room dims its ambient by, and what turns the lamps
     * going past into the tunnel's.
     */
    val tunnel: Float
        get() {
            val near = line.tunnelNear(travelled, TUNNEL_FADE_M) ?: return 0f
            val into = minOf(travelled - near.from, near.to - travelled)
            val u = ((into + TUNNEL_FADE_M) / (2.0 * TUNNEL_FADE_M)).coerceIn(0.0, 1.0)
            return (u * u * (3.0 - 2.0 * u)).toFloat()
        }

    /**
     * How much of a platform's light is coming in at the window, 0…1: all of it alongside the
     * platform, fading over its last few metres as the train runs in or pulls away.
     */
    val platformLight: Float
        get() {
            val ahead = stationAhead
            if (ahead.isNaN()) return 0f
            val over = (kotlin.math.abs(ahead) - (NightWindow.PLATFORM_HALF_M - PLATFORM_LIGHT_FADE_M)) /
                (2f * PLATFORM_LIGHT_FADE_M)
            val u = over.coerceIn(0f, 1f)
            return 1f - u * u * (3f - 2f * u)
        }

    /**
     * Whether the music is playing. Called every frame; only a change does anything.
     *
     * For the train's first [SETTLE_IN_S] seconds a change is taken as where it *is* rather than
     * something to animate: the room is built before the player has said whether it is playing,
     * and a train that arrived running only to brake into a station — or stood at one only to
     * pull away — every time the room was opened would be the room guessing out loud.
     */
    fun setRunning(running: Boolean) {
        val moving = mode == Mode.RUNNING || mode == Mode.DEPARTING
        if (running == moving) return
        if (t < SETTLE_IN_S) {
            placeAt(running)
            return
        }
        if (running) {
            // Pull away from wherever it is — at rest, or still running into the platform.
            departFrom = v
            departFor = (DEPART_S * (1.0 - v / cruise())).coerceAtLeast(1.0)
            departT = 0.0
            mode = Mode.DEPARTING
        } else {
            var stop = travelled + brakeDistance(v)
            val alongside = !station.isNaN() && kotlin.math.abs(station - travelled) < NightWindow.PLATFORM_HALF_M
            // Still alongside the platform it just left: stop there, as a real train would. Else
            // the next station — never in a tunnel. In one (or at its mouth), the train runs on out
            // of it first; one not reached yet is taken off the line, which nobody can see happen.
            if (!alongside) {
                val inside = line.tunnelAt(travelled) ?: line.tunnelNear(travelled + PORTAL_UNSEEN_M / 2.0, PORTAL_UNSEEN_M / 2.0)
                if (inside != null) stop = maxOf(stop, inside.to + PLATFORM_CLEAR_M)
                line.clear(after = travelled + PORTAL_UNSEEN_M, from = travelled, to = stop + PLATFORM_CLEAR_M)
                station = stop
            }
            stopAt = stop
            braking = false
            mode = Mode.BRAKING
        }
    }

    private fun placeAt(running: Boolean) {
        if (running) {
            mode = Mode.RUNNING
            v = cruise().toDouble()
        } else {
            mode = Mode.STOPPED
            v = 0.0
            station = travelled
            stoppedAge = SETTLE_GONE_S
        }
    }

    fun advance(dtS: Float) {
        if (dtS <= 0f) return
        val dt = dtS.coerceAtMost(0.1f).toDouble()
        when (mode) {
            Mode.RUNNING -> {
                // Midpoint speed: exact enough, and the same distance whatever the frame rate.
                v = cruise(t + dt / 2.0).toDouble()
                travelled += v * dt
            }
            Mode.DEPARTING -> {
                departT += dt
                val k = smooth((departT - dt / 2.0) / departFor)
                v = departFrom + (cruise(t + dt / 2.0) - departFrom) * k
                travelled += v * dt
                if (departT >= departFor) mode = Mode.RUNNING
            }
            Mode.BRAKING -> {
                if (!braking) {
                    // Running on to where the brake must begin — at once, unless the stop was put
                    // beyond a tunnel — then braking so the curve ends exactly at the stop.
                    val remaining = stopAt - travelled
                    if (remaining - v * dt <= brakeDistance(v) || v <= 0.0) {
                        braking = true
                        brakeFrom = travelled
                        brakeSpeed = v
                        brakeFor = if (v > 1e-6) (2.0 * remaining / v).coerceAtLeast(1e-3) else 1e-3
                        brakeT = 0.0
                    } else {
                        travelled += v * dt
                    }
                }
            }
            else -> Unit
        }
        if (mode == Mode.BRAKING && braking) {
            run {
                // Where the S-curve has the train now: speed 1 − smoothstep, distance its integral.
                brakeT += dt
                val u = (brakeT / brakeFor).coerceAtMost(1.0)
                travelled = brakeFrom + brakeSpeed * brakeFor * (u - u * u * u + u * u * u * u / 2.0)
                v = brakeSpeed * (1.0 - u * u * (3.0 - 2.0 * u))
                if (u >= 1.0) {
                    travelled = stopAt
                    v = 0.0
                    mode = Mode.STOPPED
                    stoppedAge = 0.0
                }
            }
        }
        if (mode == Mode.STOPPED) {
            v = 0.0
            stoppedAge += dt
        }
        t += dt
        line.layTo(travelled + LAY_AHEAD_M)
        line.forgetBehind(travelled)
        jointAge += dt
        val joint = floor(travelled / NightWindow.JOINT_SPACING_M)
        if (joint > lastJoint) {
            lastJoint = joint
            jointAge = 0.0
            jointSpeed = v
        }
        // A tunnel's lamps going by the window.
        val inside = line.tunnelAt(travelled)
        if (inside != null) {
            val k = floor((travelled - inside.from) / NightWindow.TUNNEL_LAMP_SPACING_M - 0.5).toLong()
            if (inside != lampTunnel) {
                lampTunnel = inside
                lampIndex = k
            } else if (k > lampIndex) {
                tunnelLampsPassed += k - lampIndex
                lampIndex = k
            }
        }
        // A station left far enough behind is gone.
        if (!station.isNaN() && travelled - station > NightWindow.STATION_RANGE_M && mode != Mode.BRAKING) {
            station = Double.NaN
        }
    }

    private var jointSpeed = 0.0

    /** How many of a tunnel's lamps the window has passed, ever — the room lights one for each. */
    var tunnelLampsPassed = 0L
        private set
    private var lampTunnel: NightLine.Span? = null
    private var lampIndex = 0L

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
     * The carriage's rock, as the outside's offset in metres of picture: a slow sway and a faster
     * shimmy that grow with the speed, a damped jolt as each rail joint passes under the bogie,
     * and — once, as it comes to rest — the carriage settling back on its springs.
     */
    fun bob(): Pair<Float, Float> {
        val motion = (v / baseSpeed).coerceIn(0.0, 1.2)
        val sway = (0.0011 * sin(0.9 * t + 0.4) + 0.0006 * sin(2.3 * t)) * motion
        val bounce = (0.0009 * sin(1.7 * t + 1.1) + 0.0005 * sin(3.9 * t + 0.3) + 0.0003 * sin(7.1 * t)) * motion
        return (sway + settle()).toFloat() to (bounce + jolt()).toFloat()
    }

    /** The last rail joint's jolt, dying away — as hard as the train was going when it crossed. */
    fun jolt(): Double =
        JOLT_M * (jointSpeed / baseSpeed).coerceIn(0.0, 1.2) * exp(-jointAge * 9.0) * sin(jointAge * 2.0 * PI * 6.0)

    /** The carriage rocking back on its springs as the train stops: a slow, damped lean along the line. */
    fun settle(): Double =
        if (stoppedAge >= SETTLE_GONE_S) 0.0
        else SETTLE_M * exp(-stoppedAge * 2.2) * sin(stoppedAge * 2.0 * PI * 0.9)

    private fun smooth(x: Double): Double {
        val u = x.coerceIn(0.0, 1.0)
        return u * u * (3.0 - 2.0 * u)
    }

    companion object {
        private const val PICTURE_DISTANCE = 0.85

        /** The jolt's peak, in metres of picture. */
        const val JOLT_M = 0.0016

        /**
         * How hard the brakes pull, on average, m/s²: a train at 16m/s stands at the platform ten
         * seconds after the music paused, 80m on, the deceleration peaking at 2.4 halfway. Calm,
         * as a stop is; not so long that the pause seems to have gone unnoticed.
         */
        const val BRAKE_MEAN_M_S2 = 1.6

        /** However slowly it was going, a stop takes this long at least. */
        private const val BRAKE_MIN_S = 2.0

        /** How long pulling away from a platform to cruising speed takes, seconds. */
        const val DEPART_S = 9.0

        /** The settle's lean, metres of picture, and how long it takes to die away. */
        const val SETTLE_M = 0.0022
        private const val SETTLE_GONE_S = 4.0

        /** How long after the room opens the player's state is taken as given — see [setRunning]. */
        const val SETTLE_IN_S = 0.8

        /** How far ahead the line is laid, metres — well beyond anything drawn or planned. */
        private const val LAY_AHEAD_M = 3_000.0

        /** A platform stands this far clear of a tunnel's mouth. */
        const val PLATFORM_CLEAR_M = 60.0

        /** A tunnel whose mouth is nearer than this is in view, and must not be taken away. */
        private const val PORTAL_UNSEEN_M = 6.0

        /** Over how many metres either side of a mouth the carriage goes from the night to the tunnel. */
        private const val TUNNEL_FADE_M = 3.0

        /** Over how many metres either side of the platform's end its light comes and goes. */
        private const val PLATFORM_LIGHT_FADE_M = 5f
    }
}
