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
    fun `the vinyl room is a finished room`() {
        assertTrue(Format.VINYL.built)
        assertEquals(Format.VINYL, Format.openable(Format.VINYL, showUnbuilt = false))
    }

    @Test
    fun `an unknown or missing name is the CD room`() {
        assertEquals(Format.CD, Format.fromName(null))
        // A room that does not exist yet — as vinyl was when this was written, and no longer is.
        assertEquals(Format.CD, Format.fromName("CASSETTE"))
        assertEquals(Format.POCKET, Format.fromName("POCKET"))
        assertEquals(Format.VINYL, Format.fromName("VINYL"))
    }
}
