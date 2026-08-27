package com.jawon.app.ui.wallscene

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The room's vibration, for the platter.
 *
 * **Why this exists rather than `View.performHapticFeedback`.** The scratch used
 * `HapticFeedbackConstants.CLOCK_TICK`, and testers reported the disc had no vibration at
 * all. That constant is routed through the platform's *touch feedback* channel, which is a
 * separate user setting — "Touch interactions" on Samsung — and is switched off on a great
 * many phones. When it is off, `performHapticFeedback` returns quietly and nothing happens.
 * It also honours the calling view's own `isHapticFeedbackEnabled`, which is one more thing
 * that can silently be false.
 *
 * That channel is the right one for incidental UI feedback, where a user who has turned
 * touch buzz off genuinely means it. It is the wrong one here: turning the disc *is* the
 * interaction, the way a controller's rumble is, and it should behave like a game's haptics
 * rather than a keyboard's. So this goes to the vibrator directly under the normal `VIBRATE`
 * permission — which is granted at install with no prompt — and still respects the system's
 * master vibration intensity.
 *
 * Everything here is a no-op on a device with no vibrator, which is checked once.
 */
class RoomHaptics(context: Context) {

    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }

    /**
     * Predefined effects rather than hand-rolled durations. The vendor tunes these to its own
     * actuator, so `EFFECT_TICK` on a phone with a good linear motor is a crisp click rather
     * than the 20ms buzz that a raw `createOneShot` produces on the same hardware.
     */
    private val tickEffect: VibrationEffect? =
        vibrator?.let { VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) }

    private val detentEffect: VibrationEffect? =
        vibrator?.let { VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) }

    /**
     * One notch of the platter. Fired every [Scratch.HAPTIC_STEP_DEG] of rotation, so this
     * runs up to forty times a second during a fast scrub and has to stay cheap.
     */
    fun tick() = play(tickEffect)

    /**
     * A track boundary went past under the finger — heavier than [tick], because it marks
     * something that happened rather than continuous texture.
     *
     * This is the "vibration on skip" the testers asked for, and it is deliberately a
     * different effect from the scrub texture rather than a louder one: the whole point is
     * being able to feel a boundary go by without looking.
     */
    fun detent() = play(detentEffect)

    private fun play(effect: VibrationEffect?) {
        val v = vibrator ?: return
        val e = effect ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Classified as hardware feedback so the platform does not mistake it for a
            // notification and suppress it under Do Not Disturb. Built inside the version
            // check rather than cached in a field, so the class is never resolved on a
            // platform that does not have it.
            v.vibrate(
                e,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            )
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(e)
        }
    }
}
