package com.jawon.app.ui.showroom

import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.jawon.app.ui.pocketscene.Wander
import com.jawon.app.ui.pocketscene.lookAngles
import com.jawon.app.ui.wallscene.RoomHaptics
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Runs the showroom from the frame loop and the touch stream: the row under the finger, the camera
 * through its framings, the exhibits floating, and the way out. What `PocketDevice` is to the pocket
 * room — the scene never owns app state, and this owns none either: it reports a departure and the
 * screen does the rest.
 */
internal class ShowroomDirector(
    private val haptics: RoomHaptics,
    /** The plinth of the room you came from — where you arrive. */
    private val from: Int,
    private val showUnbuilt: Boolean
) {
    val carousel = ShowroomCarousel(Exhibit.entries.size, start = from)

    var cameraNode: CameraNode? = null
    val exhibitNodes = arrayOfNulls<Node>(Exhibit.entries.size)

    /** Screen pixels a finger travels to carry the row one plinth. Set from the screen's width. */
    var pxPerPlinth = 1000f
    var touchSlopPx = 16f

    /** Called once, when the push into a room has finished and the screen is black. */
    var onDeparted: ((Exhibit) -> Unit)? = null

    /** For the captions: where the row is, read in the draw phase so nothing recomposes. */
    val position = mutableFloatStateOf(from.toFloat())

    /**
     * **Black over the scene**, 0…1: all of it until the showroom has something in it to show, then
     * lifting as the camera starts back; and again at the end of a push into a room.
     */
    val dark = mutableFloatStateOf(1f)

    /** Whether the black is there at all: the layer is composed only then (trap 14). */
    val darkShown = mutableStateOf(true)

    /**
     * Whether the room you came from's object is in the scene, set by the screen. The models take
     * the best part of a second to load, and an arrival that started at once finished in the dark:
     * the first thing seen was the plinth already at rest. So nothing moves until it is.
     */
    var loaded = false

    // ---- the camera's springs -------------------------------------------------------------------
    // Pulled back while the row moves; drawn in close on arrival, easing out to the hero shot.
    private var travel = 0f
    private var travelSpeed = 0f
    private var close = 1f
    private var closeSpeed = 0f
    private var departT = -1f
    private var started = false
    private var readyFrames = 0
    private var waitedS = 0f
    private var revealT = 0f
    private var departing: Exhibit? = null
    private var reported = false
    private val eye = FloatArray(6)
    private val sprung = FloatArray(2)
    private val look = FloatArray(2)

    // ---- the exhibits floating: smooth noise, never a loop -------------------------------------
    private var time = 0f
    private val drift = Array(Exhibit.entries.size) { i ->
        arrayOf(
            Wander(41 + i * 5, FLOAT_PERIOD_S),
            Wander(42 + i * 5, TURN_PERIOD_S),
            Wander(43 + i * 5, TILT_PERIOD_S),
            Wander(44 + i * 5, ROLL_PERIOD_S)
        )
    }

    // ---- the touch -----------------------------------------------------------------------------
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    val isLeaving: Boolean get() = departT >= 0f

    /** Anything moving that the eye would see stutter at 60: the room draws every vsync. */
    val isMoving: Boolean
        get() = carousel.isMoving || travel > SETTLED || close > SETTLED || isLeaving || revealT < 1f

    fun onTouch(e: MotionEvent): Boolean {
        if (isLeaving) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x
                downY = e.y
                moved = false
                carousel.press(e.x / pxPerPlinth)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!moved && hypot(e.x - downX, e.y - downY) > touchSlopPx) {
                    moved = true
                    // Caught from here, so crossing the slop does not jump the row.
                    carousel.press(e.x / pxPerPlinth)
                }
                if (moved) carousel.drag(e.x / pxPerPlinth)
            }
            MotionEvent.ACTION_UP -> {
                val t = tracker
                t?.addMovement(e)
                t?.computeCurrentVelocity(1000)
                val vx = t?.xVelocity ?: 0f
                t?.recycle()
                tracker = null
                if (moved) {
                    carousel.release(vx / pxPerPlinth)
                } else {
                    carousel.release(0f)
                    tap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
                carousel.release(0f)
            }
        }
        return true
    }

    /** A tap: on a room's plinth, into it; on one still coming, a tick and nothing more. */
    private fun tap() {
        if (abs(carousel.position - carousel.index) > TAP_SETTLED) return
        val exhibit = Exhibit.entries[carousel.index]
        if (Exhibit.opens(exhibit, showUnbuilt)) depart(exhibit) else haptics.tick()
    }

    fun depart(exhibit: Exhibit) {
        if (isLeaving) return
        departing = exhibit
        departT = 0f
        darkShown.value = true
        carousel.goTo(exhibit.ordinal)
        haptics.detent()
    }

    /**
     * Back: from the plinth you came in on, straight back into that room; from anywhere else,
     * false, and the caller simply goes back.
     */
    fun back(): Boolean {
        if (isLeaving) return true
        if (carousel.index != from || abs(carousel.position - from) > TAP_SETTLED) return false
        depart(Exhibit.entries[from])
        return true
    }

    fun onFrame(dtIn: Float) {
        val dt = dtIn.coerceIn(0f, MAX_DT_S)
        time += dt

        carousel.step(dt)
        while (carousel.ticked()) haptics.detent()
        position.floatValue = carousel.position

        // Pulled back while the row is moving under a finger or on its way to a plinth — not for a
        // tap, which never crosses the slop.
        val travelling = (carousel.isHeld && moved) ||
            abs(carousel.position - carousel.target) > TRAVEL_UNTIL
        spring(if (travelling) 1f else 0f, TRAVEL_OMEGA, dt, travel, travelSpeed)
        travel = sprung[0]
        travelSpeed = sprung[1]
        // The arrival waits for the scene, and is revealed from black as it begins.
        if (!started) {
            waitedS += dt
            if (loaded) readyFrames++
            started = readyFrames >= READY_FRAMES || waitedS > MAX_WAIT_S
        }
        if (started) {
            spring(0f, ARRIVE_OMEGA, dt, close, closeSpeed)
            close = sprung[0]
            closeSpeed = sprung[1]
            if (revealT < 1f && departT < 0f) {
                revealT = (revealT + dt / REVEAL_S).coerceAtMost(1f)
                dark.floatValue = 1f - smooth(revealT)
                if (revealT >= 1f) darkShown.value = false
            }
        }

        // The push into a room: accelerating, as a step through a door does, and black at the end.
        var inward = 0f
        if (departT >= 0f) {
            departT = (departT + dt / DEPART_S).coerceAtMost(1f)
            inward = departT * departT
            dark.floatValue = maxOf(dark.floatValue, smooth((departT - FADE_FROM) / (1f - FADE_FROM)))
            if (departT >= 1f && !reported) {
                reported = true
                departing?.let { onDeparted?.invoke(it) }
            }
        }

        ShowroomCamera.frame(carousel.position, travel, maxOf(close, inward), eye)
        cameraNode?.let { camera ->
            lookAngles(eye[0], eye[1], eye[2], eye[3], eye[4], eye[5], look)
            camera.position = Position(eye[0], eye[1], eye[2])
            // Written every frame: SceneView's camera re-aims itself otherwise (trap 8).
            camera.rotation = Rotation(look[0], look[1], 0f)
        }

        for (i in exhibitNodes.indices) {
            val node = exhibitNodes[i] ?: continue
            val w = drift[i]
            val centreY = ShowroomStage.CAP_TOP_Y + ShowroomStage.HOVER_M + HALF_HEIGHT_M[i]
            node.position = Position(
                ShowroomStage.plinthX(i.toFloat()),
                centreY + FLOAT_M * w[0].at(time),
                0f
            )
            node.rotation = Rotation(
                TILT_DEG * w[2].at(time),
                TURN_DEG * w[1].at(time),
                ROLL_DEG * w[3].at(time)
            )
        }
    }

    fun close() {
        tracker?.recycle()
        tracker = null
    }

    /** A critically damped spring toward [target], one step, into [sprung] as (value, speed). */
    private fun spring(target: Float, omega: Float, dt: Float, x: Float, v: Float) {
        if (abs(x - target) < SETTLED && abs(v) < SETTLED) {
            sprung[0] = target
            sprung[1] = 0f
            return
        }
        // The exact solution over dt, so it is the same at 60 and at 120.
        val d = x - target
        val e = exp(-omega * dt)
        val c = v + omega * d
        sprung[0] = target + (d + c * dt) * e
        sprung[1] = (v - omega * c * dt) * e
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        /** How far each exhibit's middle sits above its underside: half its height. */
        val HALF_HEIGHT_M = floatArrayOf(0.071f, 0.0518f, 0f, 0f)

        const val FLOAT_M = 0.004f
        const val TURN_DEG = 24f
        const val TILT_DEG = 3f
        const val ROLL_DEG = 1.8f
        const val FLOAT_PERIOD_S = 4.6f
        const val TURN_PERIOD_S = 6.8f
        const val TILT_PERIOD_S = 5.3f
        const val ROLL_PERIOD_S = 5.9f

        const val TRAVEL_OMEGA = 5f
        const val TRAVEL_UNTIL = 0.08f
        const val ARRIVE_OMEGA = 3.2f
        /** Frames the scene is drawn with the models in before it is shown — they settle first. */
        const val READY_FRAMES = 3
        /** How long the arrival waits for a model that may never come, before going on without it. */
        const val MAX_WAIT_S = 2.5f
        /** The black lifting off the showroom, as the camera starts back. */
        const val REVEAL_S = 0.5f
        const val DEPART_S = 0.75f
        const val FADE_FROM = 0.5f
        const val TAP_SETTLED = 0.03f
        const val SETTLED = 1e-4f
        const val MAX_DT_S = 0.1f
    }
}
