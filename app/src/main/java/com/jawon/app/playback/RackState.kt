package com.jawon.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the effects rack's controls live, between the finger turning them and the audio thread
 * reading them.
 *
 * **App-scoped, and that is the whole point.** The rack outlives the deck: closing a case must
 * not silently undo a filter sweep, and the service that owns the player is a different object
 * from the composable that draws the knob. This is the one place both can see.
 *
 * Two views of each value on purpose. The [StateFlow] is for Compose, which wants to recompose
 * when it changes. The plain field is for the audio thread, which must never suspend, never
 * allocate and never wait on a lock — it reads the latest value and moves on. `@Volatile` is
 * exactly the right amount of synchronisation for that: the audio thread is guaranteed to see a
 * recent value, and a control that arrives one buffer late is inaudible.
 */
@Singleton
class RackState @Inject constructor() {

    private val _filter = MutableStateFlow(CENTRE)

    /** The filter knob, −1 (low-pass, closed) … 0 (off) … +1 (high-pass, closed). */
    val filter: StateFlow<Float> = _filter.asStateFlow()

    @Volatile
    var filterPosition: Float = CENTRE
        private set

    fun setFilter(position: Float) {
        val clamped = position.coerceIn(-1f, 1f)
        filterPosition = clamped
        _filter.value = clamped
    }

    companion object {
        /** Off. A filter knob rests in the middle, unlike a fader, which rests open. */
        const val CENTRE = 0f
    }
}
