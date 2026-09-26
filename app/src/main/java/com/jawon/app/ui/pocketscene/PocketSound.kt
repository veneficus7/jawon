package com.jawon.app.ui.pocketscene

import com.jawon.app.playback.Echo
import com.jawon.app.playback.RackChannel
import com.jawon.app.playback.Varispeed
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The rack, as the pocket player reaches it: Settings → Sound, each effect a row with its value,
 * each row a slider the wheel turns — the classic's own way of setting a level, from its
 * Brightness screen.
 *
 * The same faders the CD room's rack draws ([com.jawon.app.playback.RackState]); this only
 * decides how a turn of the wheel moves one and how its value reads on a small screen. Pure, so
 * the steps and the readouts are tested rather than eyeballed.
 */
enum class SoundControl(
    val title: String,
    /** Centred at zero, −1…+1, rather than 0…1. */
    val bipolar: Boolean,
    /** How far one step of the wheel moves the fader. */
    val step: Float,
    /** Which of the rack's cells it belongs to, so touching it can switch that cell in. */
    val channel: RackChannel
) {
    FILTER("Filter", bipolar = true, step = 0.05f, channel = RackChannel.FILTER),
    SPEED("Speed", bipolar = true, step = 0.05f, channel = RackChannel.SPEED),
    DRIVE("Drive", bipolar = false, step = 0.04f, channel = RackChannel.DRIVE),
    ECHO("Echo", bipolar = false, step = 0.04f, channel = RackChannel.ECHO),
    ECHO_TIME("Echo Time", bipolar = false, step = 0.04f, channel = RackChannel.ECHO),
    ECHO_FEEDBACK("Echo Repeats", bipolar = false, step = 0.04f, channel = RackChannel.ECHO),
    BASS("Bass", bipolar = false, step = 0.04f, channel = RackChannel.TONE),
    WIDTH("Width", bipolar = false, step = 0.04f, channel = RackChannel.TONE);

    val min: Float get() = if (bipolar) -1f else 0f
}

object PocketSound {

    /** Reverb's choices, as the platform names them — index 0 is off. Title-cased for the screen. */
    fun reverbOptions(names: List<String>): List<String> =
        names.map { name -> name.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }

    /**
     * [position] moved [steps] of the wheel, clamped to the control's travel.
     *
     * Rounded to the step grid, so a fader set by hand in the rack to some in-between value
     * snaps onto the wheel's detents at the first turn instead of carrying the odd remainder
     * forever — and so returning to the middle lands on *exactly* the middle, which is where
     * Speed's detent is and the only place it plays at the record's own speed.
     */
    fun nudge(control: SoundControl, position: Float, steps: Int): Float {
        val grid = (position / control.step).roundToInt()
        val moved = (grid + steps) * control.step
        return moved.coerceIn(control.min, 1f)
    }

    /** How the value reads at the right of its row and on its slider. */
    fun label(control: SoundControl, position: Float): String = when (control) {
        SoundControl.FILTER -> when {
            abs(position) < 0.02f -> "Off"
            position < 0f -> "Low ${percent(-position)}"
            else -> "High ${percent(position)}"
        }
        // Locale.ROOT: a readout on a device, not prose — "1,00×" on a comma locale reads as
        // a list, and the classic never localised a number on its screen.
        SoundControl.SPEED -> String.format(java.util.Locale.ROOT, "%.2f×", Varispeed.speedFor(position))
        SoundControl.ECHO_TIME -> "${Echo.timeMsFor(position).roundToInt()} ms"
        SoundControl.ECHO_FEEDBACK -> percent(position)
        else -> if (position < 0.01f) "Off" else percent(position)
    }

    /** What each end of a slider is called, where that says more than a bare bar. */
    fun ends(control: SoundControl): Pair<String, String> = when (control) {
        SoundControl.FILTER -> "Low" to "High"
        SoundControl.SPEED -> "½×" to "2×"
        SoundControl.ECHO_TIME -> "Short" to "Long"
        SoundControl.WIDTH -> "Narrow" to "Wide"
        else -> "" to ""
    }

    private fun percent(fraction: Float) = "${(fraction * 100f).roundToInt()}%"
}
