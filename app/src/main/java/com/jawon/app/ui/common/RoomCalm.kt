package com.jawon.app.ui.common

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * **The rooms stand aside while Android is moving the app** (0.7.8).
 *
 * The owner, on his S25 Ultra: opening and closing Jawon, and coming back to it from recent apps,
 * drops frames, where every other app on the phone slides in and out cleanly. A room holds the
 * GPU near its limit by itself (`ENGINEERING.md`, "the room is not janky; it is hot"), and the slide
 * is drawn by the launcher on the same GPU, at the same moment, while the app is still resumed
 * and still drawing: the room was taking the launcher's frames.
 *
 * So, two rules, and they are the whole of this:
 *
 * - **Leaving, the room stops at once.** The first sign that the app is on its way out is its
 *   window losing focus — the swipe up, the recents button, the shade coming down — a good
 *   third of a second before it is paused. From that moment nothing is drawn: the last frame
 *   stays on the glass for the launcher to shrink away, which is all it ever needed.
 * - **Arriving, it draws once and then waits its turn.** A surface that has just been made is
 *   black until something is drawn into it, so the first frames go out immediately; after
 *   them the room draws at a quarter of its rate until the slide has finished.
 *
 * Pure, and held by [RoomCalm] for the activity to tell and the rooms to ask.
 */
class Calm {

    /** The app's window is not the one being used, though the app has not been paused: nothing need be drawn. */
    var away = false
        private set

    private var settlingFrom = NEVER
    private var drawnSince = 0
    private var left = false

    /** The window has gained or lost focus. Side by side with another app, losing it is not leaving. */
    fun focus(focused: Boolean, sideBySide: Boolean, nowMs: Long) {
        if (focused) arrive(nowMs) else if (!sideBySide) away = true
    }

    /** The app has been brought to the front — whether or not its window has been told so yet. */
    fun resumed(nowMs: Long) = arrive(nowMs)

    /** The app has left the front. (The screen going off says this without the window ever losing focus first.) */
    fun paused() {
        left = true
    }

    private fun arrive(nowMs: Long) {
        // Only a real arrival settles. Focus reported a second time to a room that never went
        // anywhere is not a reason to slow a room that is running.
        if (!away && !left && settlingFrom != NEVER) return
        away = false
        left = false
        settlingFrom = nowMs
        drawnSince = 0
    }

    /**
     * The most frames a second a room should draw at [nowMs], asked once for each frame it is
     * about to draw; [Float.MAX_VALUE] when there is no reason to hold back.
     */
    fun frameCap(nowMs: Long): Float {
        if (settlingFrom == NEVER || nowMs - settlingFrom >= SETTLE_MS) return Float.MAX_VALUE
        return if (drawnSince++ < FIRST_FRAMES) Float.MAX_VALUE else SETTLE_FPS
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** How long the launcher's slide lasts, with a little over: Samsung's is under half a second. */
        const val SETTLE_MS = 550L

        /** Frames drawn at once on arriving, so a new surface is never shown empty. */
        const val FIRST_FRAMES = 3

        /** A room's rate while the app is still sliding in. */
        const val SETTLE_FPS = 30f
    }
}

/** The one [Calm] there is: told by `MainActivity`, asked by whichever room is on the stage. */
object RoomCalm {
    private val calm = Calm()
    private val awayState = mutableStateOf(false)

    /** For a room's `isRendering`: true from the moment the app starts to leave. */
    val away: State<Boolean> get() = awayState

    fun onFocus(focused: Boolean, sideBySide: Boolean, nowMs: Long) {
        calm.focus(focused, sideBySide, nowMs)
        awayState.value = calm.away
    }

    fun onResumed(nowMs: Long) {
        calm.resumed(nowMs)
        awayState.value = calm.away
    }

    fun onPaused() = calm.paused()

    /** See [Calm.frameCap]. Main thread, from a room's frame loop. */
    fun frameCap(nowMs: Long): Float = calm.frameCap(nowMs)
}
