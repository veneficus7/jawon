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
     *
     * **`createPredefined` is API 29 and the floor is 28.** These used to call it
     * unconditionally, in field initialisers, which is a `NoSuchMethodError` while
     * *constructing* this class — and the room builds one as it composes, so an Android 9
     * phone would have died on the way into the wall. Every Android 9 phone: the failure
     * needs only a vibrator to be present.
     *
     * It survived an Android 9 emulator precisely because emulators have no vibrator, so
     * `hasVibrator()` was false, `vibrator` was null and the `?.let` never reached the call.
     * There is no emulator configuration that would have caught this.
     *
     * Below 29, a hand-rolled one-shot. It is the "20ms buzz" this comment complains about,
     * but a slightly coarse tick on a 2018 phone is not in the same category as a crash.
     */
    private val tickEffect: VibrationEffect? = vibrator?.let { effect(light = true) }

    private val detentEffect: VibrationEffect? = vibrator?.let { effect(light = false) }

    /**
     * The vendor's own effect where the platform has it, a one-shot where it does not.
     *
     * A boolean rather than an effect id, so `EFFECT_TICK` and `EFFECT_CLICK` are only ever
     * *named* inside the version check. Both are API 29 fields: referenced outside it they get
     * inlined at compile time on older platforms, which is the same bug wearing a different
     * hat and is what lint's `InlinedApi` is warning about.
     */
    private fun effect(light: Boolean): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                if (light) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
            )
        } else {
            VibrationEffect.createOneShot(
                if (light) 12L else 22L,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        }

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
