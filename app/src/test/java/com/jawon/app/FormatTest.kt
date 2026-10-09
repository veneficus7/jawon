package com.jawon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun `a build that hides unbuilt rooms never opens one, whatever was stored`() {
        // The owner's phone stored POCKET from a -Pjawon.showFormats build while it was unbuilt; a
        // published build has no showroom and no picker for an unbuilt room, so opening it there
        // would have been a room with no way out. Vinyl and cassette will be next.
        Format.entries.filter { !it.built }.forEach {
            assertEquals(Format.CD, Format.openable(it, showUnbuilt = false))
            assertEquals(it, Format.openable(it, showUnbuilt = true))
        }
    }

    @Test
    fun `a finished room opens in every build`() {
        Format.entries.filter { it.built }.forEach {
            assertEquals(it, Format.openable(it, showUnbuilt = false))
            assertEquals(it, Format.openable(it, showUnbuilt = true))
        }
    }

    @Test
    fun `the pocket player is a finished room since 0 7 1`() {
        assertTrue(Format.POCKET.built)
        assertEquals(Format.POCKET, Format.openable(Format.POCKET, showUnbuilt = false))
    }

    @Test
    fun `an unknown or missing name is the CD room`() {
        assertEquals(Format.CD, Format.fromName(null))
        assertEquals(Format.CD, Format.fromName("VINYL"))
        assertEquals(Format.POCKET, Format.fromName("POCKET"))
    }
}
