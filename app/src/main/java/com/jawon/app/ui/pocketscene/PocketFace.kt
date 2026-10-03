package com.jawon.app.ui.pocketscene

import kotlin.math.min

/**
 * **The pocket player's face — the phone is the player** (`docs/formats.md` §7, step 5c).
 *
 * The owner chose this on 2026-10-03 from four prototypes. The device shown as an object in a room
 * had a 44mm screen and a 39mm wheel on a 73mm-wide phone. Every iPod-style app that shipped fills
 * the phone instead, and the one that showed a small device drew reviews asking for the full width.
 * So the face is the whole display: a plate past every edge of it, the window across the top, and
 * the wheel where a thumb rests.
 *
 * These numbers are `pocket_face.glb`'s — `build_pocket_face()` in `tools/models/build_models.py`
 * — in the face's own frame: metres, X right, Y up, **Z = 0 at the front surface**, the origin at the
 * centre of the display the face was drawn for (the owner's S25 Ultra: 1440 × 3120 at 498ppi).
 * Pure, and tested in `PocketFaceTest`.
 */
object PocketFace {

    /** The display the face is drawn for, at life size. */
    const val DISPLAY_W_M = 1440f / 498f * 0.0254f      // 73.45mm
    const val DISPLAY_H_M = 3120f / 498f * 0.0254f      // 159.13mm

    private const val SCREEN_MARGIN_M = 0.0065f
    private const val LCD_INSET_M = 0.0008f
    private const val SCREEN_TOP_M = 0.015f

    /** The live picture: the LCD's active area, inside its black surround. */
    const val LCD_W_M = DISPLAY_W_M - 2f * SCREEN_MARGIN_M - 2f * LCD_INSET_M
    const val LCD_H_M = LCD_W_M * LCD_PX_H / LCD_PX_W
    private const val SCREEN_H_M = LCD_H_M + 2f * LCD_INSET_M

    /** The window's centre, and the picture's: up the face from its middle. */
    const val SCREEN_Y_M = DISPLAY_H_M / 2f - SCREEN_TOP_M - SCREEN_H_M / 2f

    /** The live picture's plane: 0.1mm over the panel, which is 1.0mm behind the front. */
    const val LCD_Z_M = -0.0009f

    /** The wheel: 51mm across, its centre 54mm above the display's bottom edge. */
    const val WHEEL_R_M = 0.0255f
    const val WHEEL_Y_M = -DISPLAY_H_M / 2f + 0.054f

    /** The wheel's front face, 0.6mm into its recess — where a touch is projected onto. */
    const val WHEEL_FACE_Z_M = -0.0006f

    /**
     * Where the face sits on a display [widthM] × [heightM]: scaled uniformly by [Fit.scale] about
     * its centre, which is the display's.
     *
     * **By the width, unless that would run it off the top and bottom.** On the display it was
     * drawn for it is life size; a narrower phone gets the same face a little smaller, and a taller
     * one the same face with a little more plate above the window and below the wheel. A display
     * wider for its height than this one — a folding phone's inner screen — scales it by the height
     * instead, and the plate, 180mm wide, fills the sides.
     */
    fun fit(widthM: Float, heightM: Float): Fit {
        if (widthM <= 0f || heightM <= 0f) return Fit(1f)
        return Fit(min(widthM / DISPLAY_W_M, heightM / DISPLAY_H_M))
    }

    data class Fit(val scale: Float) {
        /** The wheel's centre on the display, metres from its centre, up. */
        val wheelY: Float get() = WHEEL_Y_M * scale

        /** How far the wheel's lowest edge stands above the bottom of a display [heightM] tall. */
        fun wheelClearance(heightM: Float): Float = heightM / 2f + (WHEEL_Y_M - WHEEL_R_M) * scale

        /** The picture's width on the display. */
        val lcdWidth: Float get() = LCD_W_M * scale
    }
}
