package com.jawon.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the crossfader is, shared by the two things that can make sound.
 *
 * **The fader appeared to do nothing, and it was right to.** It was wired only to
 * [ScratchAudio], which exists exclusively while a finger is on the disc — so sitting on the
 * deck with a record playing normally, dragging it cut nothing, because the thing it cut was
 * not running. A control that is visible and inert is worse than one that is absent.
 *
 * A transform scratch is precisely the case that breaks: the record runs at normal speed under
 * ExoPlayer while the *other* hand taps the fader. The cut has to reach normal playback for
 * that to be possible at all.
 *
 * So the position lives here, app-scoped, and both consumers read it: the service applies it to
 * the player's volume, and the scratch engine applies it to its own output. Neither owns it,
 * which is the same reasoning [PlayerVolume] exists for one level down.
 */
@Singleton
class FaderState @Inject constructor() {

    private val _position = MutableStateFlow(Crossfader.RESTING)

    /** 0 shut, 1 open. Observed by the service; read directly by the audio thread. */
    val position: StateFlow<Float> = _position.asStateFlow()

    /**
     * Read without suspending, for the scratch writer.
     *
     * `StateFlow.value` is already a volatile read, so the audio thread can take it per buffer
     * without a lock — which it must, because a contended lock on that thread is a dropout.
     */
    val current: Float get() = _position.value

    fun set(value: Float) {
        _position.value = value.coerceIn(0f, 1f)
    }
}
