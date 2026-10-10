package com.jawon.app.playback

/**
 * **Carry on when the headphones come back** (0.7.6, opt-in).
 *
 * Headphones drop out — a cable pulled, an earbud's battery, a train's doors — and Android pauses
 * the music so it does not come out of the speaker. With this on, the same headphones coming back
 * within a few minutes pick the record up where it stopped.
 *
 * The rule is deliberately narrow, because music that starts by itself is the worst thing a player
 * can do: it resumes only a record *the headphones' going away* paused. Paused by hand, by a call,
 * by another app, or not loaded at all — connecting headphones does nothing. And never without
 * the switch.
 *
 * Pure; `PlaybackService` owns the listening.
 */
object HeadphoneResume {

    /** How long after the drop-out a reconnection still means "carry on". */
    const val WINDOW_MS = 10 * 60_000L

    fun resumes(
        optedIn: Boolean,
        hasQueue: Boolean,
        playing: Boolean,
        /** When the output going away paused the record, on a monotonic clock; null if it did not. */
        droppedAtMs: Long?,
        nowMs: Long
    ): Boolean =
        optedIn && hasQueue && !playing && droppedAtMs != null && nowMs - droppedAtMs in 0..WINDOW_MS

    /**
     * Whether an output is something worn on the head — `AudioDeviceInfo`'s types, as their
     * numbers, so this stays testable off a phone: wired headset 3 and headphones 4, Bluetooth
     * A2DP 8, a USB headset 22, a hearing aid 23, a Bluetooth LE headset 26.
     */
    fun isHeadphones(deviceType: Int): Boolean = deviceType in HEADPHONE_TYPES

    private val HEADPHONE_TYPES = setOf(3, 4, 8, 22, 23, 26)
}
