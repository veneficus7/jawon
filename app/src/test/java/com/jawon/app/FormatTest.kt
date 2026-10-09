package com.jawon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `a build that hides unbuilt rooms never opens one, whatever was stored`() {
        // The owner's phone stored POCKET from a -Pjawon.showFormats build; a published build has
        // no showroom and no picker, so opening it there would have been a room with no way out.
        assertEquals(Format.CD, Format.openable(Format.POCKET, showUnbuilt = false))
    }

    @Test
    fun `a build that offers unbuilt rooms opens the one that was stored`() {
        assertEquals(Format.POCKET, Format.openable(Format.POCKET, showUnbuilt = true))
    }

    @Test
    fun `a finished room opens in every build`() {
        assertEquals(Format.CD, Format.openable(Format.CD, showUnbuilt = false))
        assertEquals(Format.CD, Format.openable(Format.CD, showUnbuilt = true))
    }

    @Test
    fun `an unknown or missing name is the CD room`() {
        assertEquals(Format.CD, Format.fromName(null))
        assertEquals(Format.CD, Format.fromName("VINYL"))
        assertEquals(Format.POCKET, Format.fromName("POCKET"))
    }
}
