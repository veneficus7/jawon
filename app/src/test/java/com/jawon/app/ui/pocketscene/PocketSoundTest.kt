package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rack as the pocket player's wheel moves it. What matters is where the steps land: the
 * middle of Speed is the only place a record plays at its own speed, and a wheel that could not
 * find it again would be a wheel that detunes everything it touches.
 */
class PocketSoundTest {

    @Test
    fun `a step moves a fader by its own step and stops at the ends`() {
        assertEquals(0.08f, PocketSound.nudge(SoundControl.DRIVE, 0f, 2), 1e-6f)
        assertEquals(1f, PocketSound.nudge(SoundControl.DRIVE, 0.96f, 5), 1e-6f)
        assertEquals(0f, PocketSound.nudge(SoundControl.DRIVE, 0.04f, -3), 1e-6f)
        assertEquals(-1f, PocketSound.nudge(SoundControl.FILTER, -0.9f, -9), 1e-6f)
    }

    @Test
    fun `a value set by hand in the rack snaps onto the wheel's grid, and the middle is exact`() {
        // The rack left Speed a hair off centre; one step back and one forward is the middle.
        val there = PocketSound.nudge(SoundControl.SPEED, 0.013f, 1)
        val back = PocketSound.nudge(SoundControl.SPEED, there, -1)
        assertEquals(0f, back, 0f)
        assertEquals("1.00×", PocketSound.label(SoundControl.SPEED, back))
    }

    @Test
    fun `values read the way the screen needs them`() {
        assertEquals("Off", PocketSound.label(SoundControl.FILTER, 0f))
        assertEquals("Low 40%", PocketSound.label(SoundControl.FILTER, -0.4f))
        assertEquals("High 25%", PocketSound.label(SoundControl.FILTER, 0.25f))
        assertEquals("Off", PocketSound.label(SoundControl.ECHO, 0f))
        assertEquals("36%", PocketSound.label(SoundControl.BASS, 0.36f))
        assertEquals("2.00×", PocketSound.label(SoundControl.SPEED, 1f))
        assertEquals("0.50×", PocketSound.label(SoundControl.SPEED, -1f))
    }

    @Test
    fun `the platform's reverb names read as words, not shouting`() {
        assertEquals(listOf("Off", "Small Room", "Large Hall"), PocketSound.reverbOptions(listOf("OFF", "SMALL ROOM", "LARGE HALL")))
    }
}
