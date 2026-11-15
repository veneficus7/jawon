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
 * so `panel.mat` carries it instead ([RibbonLight]), and **that is what ships** (2026-11-15).
 * On the owner's phone, the wall panned by hand at thermal status 0, a frame took 27.7ms of GPU
 * with the lights and 14.0ms baked — 35 frames a second against 68 — and the owner judged the
 * two the same from stills of one frame under each.
 *
 * The lights stay here, because replacing a tuned look needs the old and the new compared in
 * one frame, and the next change to the room is measured the same way:
 *
 *     adb shell am start -n com.jawon.app/.MainActivity --es jawon.ribbon <mode> [--ef jawon.gain 1.1]
 *
 * | mode | panel material | ribbon lights | what it is for |
 * |---|---|---|---|
 * | `lights` | SceneView's stock | built | the look as it was tuned |
 * | `none` | stock | not built | everything the ribbon lit, by its absence |
 * | `zero` | `panel.mat`, ribbon term 0 | built | the material swap alone, which must change nothing |
 * | `baked` | `panel.mat` | not built | what ships |
 * | `mask` | `panel.mat`, painted magenta | not built | which pixels are panel |
 *
 * **The shelf-edge rail has the same five**, under `jawon.rail` and `jawon.railgain`, for its 60
 * heads against `plate.mat` on the case plates ([RailLight], [RailPlates]) — and **baked is what
 * ships for it too** (2026-11-16). On the owner's phone, the ribbon baked in both and the wall
 * panned by hand at thermal status 0, a frame took 14.1ms of GPU with the rail's heads and 6.8ms
 * with its light in the plates — 63 frames a second against 103, at a GPU clock the phone let
 * fall from 1200MHz to about 970 — and the owner judged the two the same from stills:
 *
 *     adb shell am start -n com.jawon.app/.MainActivity --es jawon.rail <mode> [--ef jawon.railgain 1.1]
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

    /** What a build without the bench flag always uses: the ribbon's light in the panel. */
    val SHIPPED = Ribbon.BAKED

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

    /** The rail's modes, as the ribbon's: its heads as lights, or its light in `plate.mat`. */
    enum class Rail(val lights: Boolean, val bakedPlates: Boolean) {
        LIGHTS(lights = true, bakedPlates = false),
        NONE(lights = false, bakedPlates = false),
        ZERO(lights = true, bakedPlates = true),
        BAKED(lights = false, bakedPlates = true),
        MASK(lights = false, bakedPlates = true),
    }

    /** The rail as a build without the bench flag has it: its light in the case plates. */
    val SHIPPED_RAIL = Rail.BAKED

    var rail by mutableStateOf(SHIPPED_RAIL)
        private set

    /** Scale on the rail's light in `plate.mat`. 1 is the light the heads gave. */
    var railGain by mutableFloatStateOf(1f)
        private set

    /** The plates' rail term as the material should apply it, for the current mode. */
    val plateGain: Float
        get() = when (rail) {
            Rail.ZERO -> 0f
            else -> railGain
        }

    /**
     * Whether the room draws shadows at all — `jawon.shadows` `on` or `off` — so a trace can say
     * what the shadow maps cost. Every build without the bench flag draws them as its quality tier
     * says; this only ever turns them all off for a measurement.
     */
    var shadows by mutableStateOf(true)
        private set

    /**
     * A mode always comes with its gain, 1 when none is given. The gain used to stay from the
     * last intent that set one, so a sweep ending at 1.1 made every later `baked` capture 10%
     * bright — read, for a while, as the bottom shelf alone being lit wrong. The ribbon and the
     * rail are read apart: an intent naming one leaves the other as it is.
     */
    fun read(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("jawon.ribbon")?.let { name ->
            Ribbon.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                ribbon = it
                gain = intent.getFloatExtra("jawon.gain", 1f)
            }
        }
        intent.getStringExtra("jawon.shadows")?.let { shadows = !it.equals("off", ignoreCase = true) }
        intent.getStringExtra("jawon.rail")?.let { name ->
            Rail.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                rail = it
                railGain = intent.getFloatExtra("jawon.railgain", 1f)
            }
        }
    }
}
