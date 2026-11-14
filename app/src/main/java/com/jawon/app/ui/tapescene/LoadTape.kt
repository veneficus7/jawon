package com.jawon.app.ui.tapescene

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A rotation, as (w, x, y, z). Small and pure, so a turn can be held by a test. */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    operator fun times(o: Quat): Quat = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    )

    /** [v] turned by this: into [out]. */
    fun rotate(vx: Float, vy: Float, vz: Float, out: FloatArray) {
        // v' = v + 2w(q × v) + 2 q × (q × v)
        val cx = y * vz - z * vy
        val cy = z * vx - x * vz
        val cz = x * vy - y * vx
        val ccx = y * cz - z * cy
        val ccy = z * cx - x * cz
        val ccz = x * cy - y * cx
        out[0] = vx + 2f * (w * cx + ccx)
        out[1] = vy + 2f * (w * cy + ccy)
        out[2] = vz + 2f * (w * cz + ccz)
    }

    fun normalised(): Quat {
        val l = sqrt(w * w + x * x + y * y + z * z)
        return if (l < 1e-9f) IDENTITY else Quat(w / l, x / l, y / l, z / l)
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        fun aboutX(deg: Float): Quat = axis(1f, 0f, 0f, deg)
        fun aboutY(deg: Float): Quat = axis(0f, 1f, 0f, deg)
        fun aboutZ(deg: Float): Quat = axis(0f, 0f, 1f, deg)

        fun axis(ax: Float, ay: Float, az: Float, deg: Float): Quat {
            val h = Math.toRadians(deg.toDouble()).toFloat() / 2f
            val s = sin(h)
            return Quat(cos(h), ax * s, ay * s, az * s)
        }

        /** The shorter way from [a] to [b], [t] of it. */
        fun slerp(a: Quat, b: Quat, t: Float): Quat {
            var bw = b.w
            var bx = b.x
            var by = b.y
            var bz = b.z
            var dot = a.w * bw + a.x * bx + a.y * by + a.z * bz
            if (dot < 0f) {
                bw = -bw; bx = -bx; by = -by; bz = -bz; dot = -dot
            }
            if (dot > 0.9995f) {
                return Quat(a.w + (bw - a.w) * t, a.x + (bx - a.x) * t, a.y + (by - a.y) * t, a.z + (bz - a.z) * t).normalised()
            }
            val theta = acos(dot.coerceIn(-1f, 1f))
            val s = sin(theta)
            val wa = sin((1f - t) * theta) / s
            val wb = sin(t * theta) / s
            return Quat(a.w * wa + bw * wb, a.x * wa + bx * wb, a.y * wa + by * wb, a.z * wa + bz * wb)
        }
    }
}

/** Where a thing is and which way it faces. */
class Pose {
    var x = 0f
    var y = 0f
    var z = 0f
    var q = Quat.IDENTITY

    fun set(x: Float, y: Float, z: Float, q: Quat): Pose {
        this.x = x
        this.y = y
        this.z = z
        this.q = q
        return this
    }

    fun copyFrom(o: Pose): Pose = set(o.x, o.y, o.z, o.q)
}

/**
 * **A tape taken from its rack and put in the deck**: one clock, and where the box, its lid and
 * the cassette are on it — the cassette room's `PutOn`.
 *
 * 1. The box slides out of its slot toward you.
 * 2. It turns to show its cover, as you would turn one over in your hand to look at it, while the
 *    camera starts down to the deck.
 * 3. It is carried down and laid on the deck's lid, spine to the back.
 * 4. The lid opens, and stands: the J-card's inside is the tracklist from here on.
 * 5. The door opens; the cassette is lifted out of its tray, turned to face you, and carried into
 *    the door — and the door shuts on it, which is the deck's to do, and so is PLAY.
 *
 * Pure: every beat a function of the one clock, so a touch that hurries it only runs the same
 * clock faster, and a room that stops being drawn half way can jump to the end of it.
 */
object LoadTape {

    const val OUT_AT = 0.0f
    const val OUT_S = 0.30f
    const val SHOW_AT = 0.18f
    const val SHOW_S = 0.55f
    const val DOWN_AT = 0.72f
    const val DOWN_S = 0.72f
    const val LID_AT = 1.40f
    const val LID_S = 0.42f
    /** The door is opened here — by the deck, told by the room. */
    const val DOOR_AT = 1.45f
    const val LIFT_AT = 1.78f
    const val LIFT_S = 0.38f
    const val INTO_AT = 2.12f
    const val INTO_S = 0.42f
    /** In its seat: the room tells the deck, which shuts the door and puts PLAY down. */
    const val SEATED_AT = INTO_AT + INTO_S
    const val TOTAL_S = SEATED_AT

    /** The camera sets off for the deck here: the box is seen to leave the rack before the view does. */
    const val CAMERA_AT = 0.55f

    /** A box lying in a rack, spine out: turned half round from how it lies on the deck. */
    val IN_RACK: Quat = Quat.aboutY(180f)

    /** Held up to look at: cover toward you, the spine on its left, as a book's is. */
    val SHOWN = Quat(0.5f, 0.5f, 0.5f, 0.5f)

    /** On the deck: as modelled, spine to the back. */
    val ON_DECK: Quat = Quat.IDENTITY

    /** The cassette lying face up in its tray, as the box holds it, relative to the box. */
    val LYING: Quat = Quat.aboutX(-90f)

    /** Where the box is held up to be looked at: in front of the rack it came from, a little toward the camera. */
    const val SHOW_FORWARD = 0.13f
    const val SHOW_UP = 0.015f

    class State {
        val case = Pose()
        /** The lid's opening, in degrees about its hinge: 0 shut, [TapeDimens.LID_OPEN_DEG] open. */
        var lidDeg = 0f
        /** The cassette, in the room. Its own until it is seated, when the deck has it. */
        val cassette = Pose()
        var cassetteSeen = true
        /** 0..1: how far the camera has come from the rack to the deck, as the sequence would have it. */
        var cameraDown = 0f
    }

    private val scratch = FloatArray(3)

    /**
     * Where everything is [t] seconds after a tape was taken from slot ([rackX], [slotY]), for a
     * door open [doorDeg] — and so where its seat is.
     */
    fun at(t: Float, rackX: Float, slotY: Float, doorDeg: Float, out: State) {
        // The box: out of its slot, up to be looked at, down onto the deck.
        val slotZ = TapeDimens.SLOT_CASE_Z
        val outZ = slotZ + TapeDimens.BOX_W + 0.010f
        val showX = rackX
        val showY = slotY + SHOW_UP
        val showZ = outZ + SHOW_FORWARD
        val out1 = ease((t - OUT_AT) / OUT_S)
        val show = ease((t - SHOW_AT) / SHOW_S)
        val down = ease((t - DOWN_AT) / DOWN_S)
        var x = rackX
        var y = slotY
        var z = slotZ + (outZ - slotZ) * out1
        var q = Quat.slerp(IN_RACK, SHOWN, show)
        if (show > 0f) {
            x += (showX - x) * show
            y += (showY - y) * show
            z += (showZ - z) * show
        }
        if (down > 0f) {
            // Down in an arc: away from the camera first, so it is not carried through the lens.
            val lift = sin(down * Math.PI.toFloat()) * 0.04f
            x += (0f - x) * down
            y += (TapeDimens.CASE_Y - y) * down + lift
            z += (TapeDimens.CASE_Z - z) * down - lift * 0.5f
            q = Quat.slerp(SHOWN, ON_DECK, down)
        }
        out.case.set(x, y, z, q)
        out.lidDeg = TapeDimens.LID_OPEN_DEG * ease((t - LID_AT) / LID_S)
        out.cameraDown = ease((t - CAMERA_AT) / (DOWN_AT + DOWN_S - CAMERA_AT))

        // The cassette: in its tray, then lifted and turned to face you, then into the door's seat.
        lyingIn(out.case, out.cassette)
        val lift = ease((t - LIFT_AT) / LIFT_S)
        val into = ease((t - INTO_AT) / INTO_S)
        if (lift > 0f) {
            val hx = 0f
            val hy = TapeDimens.DECK_TOP + 0.050f
            val hz = TapeDimens.CASE_Z + 0.075f
            val lx = out.cassette.x + (hx - out.cassette.x) * lift
            val ly = out.cassette.y + (hy - out.cassette.y) * lift
            val lz = out.cassette.z + (hz - out.cassette.z) * lift
            val lq = Quat.slerp(out.cassette.q, Quat.IDENTITY, lift)
            out.cassette.set(lx, ly, lz, lq)
            if (into > 0f) {
                // Into the door as a hand puts one in: over its top edge to just above the
                // holder, tilted with the open door — then down into the holder along the door
                // itself. Never through the glass (`TapeRackTest`).
                presented.set(lx, ly, lz, lq)
                clear(doorDeg, clearPose)
                if (into < INTO_CLEAR) {
                    lerp(presented, clearPose, ease(into / INTO_CLEAR), out.cassette)
                } else {
                    seat(doorDeg, seatPose)
                    lerp(clearPose, seatPose, ease((into - INTO_CLEAR) / (1f - INTO_CLEAR)), out.cassette)
                }
            }
        }
        out.cassetteSeen = true
    }

    /** How much of the way in is spent getting over the door to above its holder; the rest is sliding down into it. */
    private const val INTO_CLEAR = 0.55f

    /** Slid up out of the holder by this much, a cassette is clear of the door's top edge. */
    const val CLEAR_UP = 0.074f

    /** Out in front of the open door, where a tape is turned over: high enough to clear its top edge, far enough to turn. */
    const val OUTSIDE_Y = 0.142f
    const val OUTSIDE_Z = 0.105f

    private val presented = Pose()
    private val clearPose = Pose()
    private val seatPose = Pose()
    private val outsidePose = Pose()

    /** The cassette slid up out of the holder of a door open [doorDeg], still tilted with it. */
    fun clear(doorDeg: Float, out: Pose) {
        val q = Quat.aboutX(doorDeg)
        q.rotate(TapeDimens.SEAT_X, TapeDimens.SEAT_Y + CLEAR_UP - TapeDimens.DOOR_HINGE_Y, TapeDimens.SEAT_Z - TapeDimens.DOOR_HINGE_Z, scratch)
        out.set(scratch[0], TapeDimens.DOOR_HINGE_Y + scratch[1], TapeDimens.DOOR_HINGE_Z + scratch[2], q)
    }

    /**
     * Out of the door to be turned over: [l] 0 in its seat, ½ slid up clear of the holder, 1 out
     * in front of the door and upright. The deck turns it over there, and puts it back the same way.
     */
    fun liftedOut(doorDeg: Float, l: Float, out: Pose) {
        if (l <= 0.5f) {
            seat(doorDeg, seatPose)
            clear(doorDeg, clearPose)
            lerp(seatPose, clearPose, ease(l / 0.5f), out)
        } else {
            clear(doorDeg, clearPose)
            outsidePose.set(0f, OUTSIDE_Y, OUTSIDE_Z, Quat.IDENTITY)
            lerp(clearPose, outsidePose, ease((l - 0.5f) / 0.5f), out)
        }
    }

    private fun lerp(a: Pose, b: Pose, t: Float, out: Pose) {
        out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, Quat.slerp(a.q, b.q, t))
    }

    /** The cassette lying in the box at [case]: its middle, and which way it faces. */
    fun lyingIn(case: Pose, out: Pose) {
        case.q.rotate(0f, TapeDimens.TRAY_FLOOR + TapeDimens.CAS_T / 2, 0f, scratch)
        out.set(case.x + scratch[0], case.y + scratch[1], case.z + scratch[2], case.q * LYING)
    }

    /** The seat in the door, with the door open [doorDeg]: the door turns about its foot, and the cassette with it. */
    fun seat(doorDeg: Float, out: Pose) {
        val q = Quat.aboutX(doorDeg)
        q.rotate(TapeDimens.SEAT_X, TapeDimens.SEAT_Y - TapeDimens.DOOR_HINGE_Y, TapeDimens.SEAT_Z - TapeDimens.DOOR_HINGE_Z, scratch)
        out.set(scratch[0], TapeDimens.DOOR_HINGE_Y + scratch[1], TapeDimens.DOOR_HINGE_Z + scratch[2], q)
    }

    fun ease(s: Float): Float {
        val t = s.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
