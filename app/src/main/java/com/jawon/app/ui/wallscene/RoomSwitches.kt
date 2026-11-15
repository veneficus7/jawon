package com.jawon.app.ui.wallscene

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the CD room's shelf ribbon is lit, switchable from a launch intent on a build made with
 * `-Pjawon.bench=true` — and fixed at the shipped choice everywhere else.
 *
 * The ribbon used to be four rows of 27 spotlights, and on the S25 Ultra the shelf fixtures
 * were most of a 39ms frame (ENGINEERING.md, trap 46). Its light on the back panel never moves,
 * so `panel.mat` can carry it instead ([RibbonLight]). Replacing a tuned look needs the old and
 * the new compared in one frame, which is what these are for:
 *
 *     adb shell am start -n com.jawon.app/.MainActivity --es jawon.ribbon <mode> [--ef jawon.gain 1.0]
 *
 * | mode | panel material | ribbon lights | what it is for |
 * |---|---|---|---|
 * | `lights` | SceneView's stock | built | the look as it was tuned |
 * | `none` | stock | not built | everything the ribbon lit, by its absence |
 * | `zero` | `panel.mat`, ribbon term 0 | built | the material swap alone, which must change nothing |
 * | `baked` | `panel.mat` | not built | the replacement |
 * | `mask` | `panel.mat`, painted magenta | not built | which pixels are panel |
 *
 * Read from the intent the activity is created with and from every new one (`singleTask`), so a
 * mode can be changed without the room moving. State, so the scene recomposes when it changes.
 */
object RoomSwitches {

    enum class Ribbon(val lights: Boolean, val bakedPanel: Boolean) {
        LIGHTS(lights = true, bakedPanel = false),
        NONE(lights = false, bakedPanel = false),
        ZERO(lights = true, bakedPanel = true),
        BAKED(lights = false, bakedPanel = true),
        MASK(lights = false, bakedPanel = true),
    }

    /** What a build without the bench flag always uses. */
    val SHIPPED = Ribbon.LIGHTS

    var ribbon by mutableStateOf(SHIPPED)
        private set

    /** Scale on the ribbon's light in `panel.mat`. 1 is the light the 27 diodes gave. */
    var gain by mutableFloatStateOf(1f)
        private set

    /** The panel term's scale as the material should apply it, for the current mode. */
    val panelGain: Float
        get() = when (ribbon) {
            Ribbon.ZERO -> 0f
            else -> gain
        }

    fun read(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("jawon.ribbon")?.let { name ->
            Ribbon.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { ribbon = it }
        }
        if (intent.hasExtra("jawon.gain")) gain = intent.getFloatExtra("jawon.gain", 1f)
    }
}
