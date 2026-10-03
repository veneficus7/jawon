package com.jawon.app.ui.pocketscene

import com.google.android.filament.MaterialInstance

/**
 * **Which one you bought** — the pocket player's colour, chosen in its own Settings (the owner,
 * 2026-09-27, answering his own reference photos: mostly silver classics with white wheels, one
 * black, one red nano).
 *
 * Only colour changes: the model's parts keep their shapes, their roughness and the wear maps on
 * them, so fingerprints and the thumb-polished ring on the wheel are the same on every finish. Each
 * is the base colour of four parts, in linear RGB — the front plate (anodised aluminium, a metal, so
 * its colour *is* its reflection), the wheel and the centre button (plastic), and the print on the
 * wheel, which has to stand off whatever the wheel is.
 */
enum class PocketFinish(
    val title: String,
    val front: FloatArray,
    /**
     * How rough the front's anodising is — a finish is a surface as well as a colour. Silver is
     * bead-blasted and spreads what it reflects into an even grey: at black's 0.34 its lower half
     * mirrored the dark carriage and went nearly black on the AVD.
     */
    val frontRoughness: Float,
    /**
     * How much of the front is a mirror. A pure metal only ever shows what it reflects, and in a
     * night carriage that is mostly dark: silver at 1.0 read as black below the lamp's reflection
     * on the AVD, which is true of a mirror and not of a bead-blasted, anodised front — that
     * scatters as well as reflects, and reads light grey under any lamp. Black keeps its 1.0.
     */
    val frontMetallic: Float,
    val wheel: FloatArray,
    val button: FloatArray,
    val print: FloatArray
) {
    /** The classic's own: silver anodising, a light grey-white wheel, grey lettering on it. */
    SILVER(
        "Silver",
        front = floatArrayOf(0.83f, 0.83f, 0.84f),
        frontRoughness = 0.56f,
        frontMetallic = 0.4f,
        wheel = floatArrayOf(0.80f, 0.80f, 0.79f),
        button = floatArrayOf(0.82f, 0.82f, 0.81f),
        print = floatArrayOf(0.30f, 0.30f, 0.31f)
    ),

    /** Graphite anodising, a black wheel, pale lettering — the device as it was first modelled. */
    BLACK(
        "Black",
        front = floatArrayOf(0.205f, 0.200f, 0.196f),
        frontRoughness = 0.34f,
        frontMetallic = 1f,
        wheel = floatArrayOf(0.072f, 0.071f, 0.070f),
        button = floatArrayOf(0.062f, 0.061f, 0.060f),
        print = floatArrayOf(0.62f, 0.62f, 0.63f)
    ),

    /** Candy-red anodising, a white wheel round a red button, like the red nano. */
    RED(
        "Red",
        front = floatArrayOf(0.62f, 0.045f, 0.05f),
        frontRoughness = 0.30f,
        frontMetallic = 0.6f,
        wheel = floatArrayOf(0.80f, 0.80f, 0.79f),
        button = floatArrayOf(0.42f, 0.02f, 0.03f),
        print = floatArrayOf(0.36f, 0.36f, 0.37f)
    );

    /** Puts this finish on the parts' own materials — the factors only, so nothing is rebuilt. */
    fun applyTo(front: MaterialInstance?, wheel: MaterialInstance?, button: MaterialInstance?, print: MaterialInstance?) {
        front?.setBase(this.front)
        front?.setParameter("roughnessFactor", frontRoughness)
        front?.setParameter("metallicFactor", frontMetallic)
        wheel?.setBase(this.wheel)
        button?.setBase(this.button)
        print?.setBase(this.print)
    }

    private fun MaterialInstance.setBase(rgb: FloatArray) =
        setParameter("baseColorFactor", rgb[0], rgb[1], rgb[2], 1f)

    companion object {
        /** What was stored, or silver — the look most of the owner's references show. */
        fun fromName(name: String?): PocketFinish = entries.firstOrNull { it.name == name } ?: SILVER
    }
}
