package com.jawon.app.ui.pocketscene

/**
 * **The pocket player as an object** — the showroom's exhibit, standing on its plinth
 * (`ui/showroom/`). Its room draws the face (`PocketFace`); the showroom draws the whole device,
 * back, bead and all, so these are the device's numbers, not the face's.
 */

/** Where the device's model lives. Eight objects in one file — see `docs/models.md`. */
const val POCKET_MODEL_ASSET = "models/pocket.glb"

/**
 * The LCD panel, in the device's own frame — these must agree with `build_pocket()`.
 *
 * The live picture is `POCKET_SCREEN_W/H` less 1.2mm, centred `POCKET_SCREEN_Y` up the face, and
 * sits **0.1mm in front of the panel** — which is `POCKET_SCREEN_SINK` (1.0mm) behind the face and
 * fills the window as the LCD's black surround, so the picture has a 0.6mm mask all round as a
 * real LCD's active area does. 0.1mm is enough that the depth buffer never has to choose between
 * them, and still 0.45mm under the glass, which is what makes it read as a picture *under* a
 * window rather than a sticker on one.
 *
 * **Why the screen could not be seen for a morning**: the panel was 1.6mm down, *inside* the
 * back shell, which is solid up to the plate's underside. What the window showed was the
 * shell's polished top face reflecting the lamp. Two probes — the glass hidden, then the panel
 * hidden — changed nothing, and the one that moved the screen in front of everything showed it
 * working perfectly; the depth it had been given was the whole problem.
 */
internal const val SCREEN_W_M = 0.0443f
internal const val SCREEN_CENTRE_Y = 0.0255f
internal const val SCREEN_Z = 0.00525f - 0.0010f + 0.0001f

/**
 * The shell's polished steel: the glTF's own values (`build_pocket()`), with the lobe widened
 * across a pixel's footprint where the bead curves — see `materials/polished_steel.mat`.
 *
 * [STEEL_FOOTPRINT] is measured, not derived. On the flagship AVD the ceiling's runs crossed the
 * top of the bead as dashed lines at 1; at **4** they joined into continuous lines that still read
 * as a mirror; at 12 the bead went to satin. The arithmetic had predicted 1.
 */
internal const val POLISHED_STEEL_MATERIAL_ASSET = "materials/polished_steel.filamat"
internal val STEEL_BASE_COLOUR = floatArrayOf(0.560f, 0.570f, 0.580f)
internal const val STEEL_ROUGHNESS = 0.055f
internal const val STEEL_FOOTPRINT = 4f
