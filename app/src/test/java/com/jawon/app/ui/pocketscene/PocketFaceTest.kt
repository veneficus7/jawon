package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face's numbers and where it sits on a display. The numbers are `pocket_face.glb`'s, read off
 * its exported bounds (`docs/models.md`), so a change to one side and not the other fails here.
 */
class PocketFaceTest {

    private fun mm(m: Float) = m * 1000f

    @Test
    fun `the face agrees with the model it is drawn over`() {
        // build_pocket_face(): "screen 60.45 x 45.27mm at y 41.93, picture 58.85 x 43.67mm,
        // wheel 51.0mm at y -25.57".
        assertEquals(58.85f, mm(PocketFace.LCD_W_M), 0.01f)
        assertEquals(43.67f, mm(PocketFace.LCD_H_M), 0.01f)
        assertEquals(41.93f, mm(PocketFace.SCREEN_Y_M), 0.01f)
        assertEquals(51.0f, mm(PocketFace.WHEEL_R_M * 2f), 0.01f)
        assertEquals(-25.57f, mm(PocketFace.WHEEL_Y_M), 0.01f)
        // The picture keeps the LCD's own proportions, so nothing on it is stretched.
        assertEquals(LCD_PX_W.toFloat() / LCD_PX_H, PocketFace.LCD_W_M / PocketFace.LCD_H_M, 1e-4f)
    }

    @Test
    fun `life size on the display it was drawn for`() {
        val fit = PocketFace.fit(PocketFace.DISPLAY_W_M, PocketFace.DISPLAY_H_M)
        assertEquals(1f, fit.scale, 1e-5f)
    }

    @Test
    fun `a narrower phone gets the same face a little smaller, scaled by its width`() {
        // A Pixel-sized display: 1080 x 2400 at 428ppi, 64.1 x 142.4mm.
        val w = 1080f / 428f * 0.0254f
        val h = 2400f / 428f * 0.0254f
        val fit = PocketFace.fit(w, h)
        assertEquals(w / PocketFace.DISPLAY_W_M, fit.scale, 1e-5f)
        assertTrue("the picture is the display's width less its margins", fit.lcdWidth < w)
    }

    @Test
    fun `a display wide for its height is fitted by its height`() {
        // A folding phone's inner screen, near square.
        val fit = PocketFace.fit(0.140f, 0.150f)
        assertEquals(0.150f / PocketFace.DISPLAY_H_M, fit.scale, 1e-5f)
    }

    @Test
    fun `the wheel stays clear of the gesture bar on every common shape`() {
        // 18:9 to 21:9 at the reference width: Android's gesture handle lives in the bottom ~5mm,
        // and a thumb circling the wheel's lowest edge must not reach it.
        for (aspect in listOf(18f / 9f, 19.5f / 9f, 20f / 9f, 21f / 9f)) {
            val w = PocketFace.DISPLAY_W_M
            val h = w * aspect
            val fit = PocketFace.fit(w, h)
            val clear = fit.wheelClearance(h)
            assertTrue("aspect $aspect: ${mm(clear)}mm under the wheel", clear > 0.020f)
        }
    }

    @Test
    fun `nothing to fit is fitted at life size`() {
        assertEquals(1f, PocketFace.fit(0f, 0.1f).scale, 0f)
    }
}
