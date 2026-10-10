package com.jawon.app.surfaces

import com.jawon.app.data.local.Album
import com.jawon.app.data.playback.PlaybackRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the widget, the tile and the shortcuts show — one answer for all three. */
class SurfaceStateTest {

    private val loveless = album("loveless", "Loveless", "My Bloody Valentine", hue = 0.92f)
    private val blue = album("blue", "Blue", "Joni Mitchell")

    @Test
    fun `with a record on, the song is the headline and the record is under it`() {
        val state = SurfaceState.of(PlaybackRepository.OnNow(loveless, "Sometimes", isPlaying = true), lastPlayed = blue)
        assertEquals("Sometimes", state.title)
        assertEquals("Loveless · My Bloody Valentine", state.line)
        assertEquals("loveless", state.albumId)
        assertTrue(state.isPlaying && state.isLoaded)
        assertEquals(0.92f, state.spineHue, 0f)
    }

    @Test
    fun `a record loaded but between tracks is shown by its own name`() {
        val state = SurfaceState.of(PlaybackRepository.OnNow(loveless, trackTitle = null, isPlaying = false), null)
        assertEquals("Loveless", state.title)
        assertTrue(state.isLoaded)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `with the app closed the widget still holds the record that was on last`() {
        val state = SurfaceState.of(onNow = null, lastPlayed = blue)
        assertEquals("Blue", state.title)
        assertEquals("Joni Mitchell", state.line)
        // Not loaded: play means "put it back on", and there is no next track to go to.
        assertFalse(state.isLoaded)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `before anything has been played it is an invitation, not a blank`() {
        val state = SurfaceState.of(null, null)
        assertEquals(SurfaceState.EMPTY, state)
        assertNull(state.albumId)
        assertTrue(state.title.isNotBlank() && state.line.isNotBlank())
    }

    // ---- The card's colour -------------------------------------------------------------------

    @Test
    fun `a record with no colour of its own sits on the room's dark`() {
        assertEquals(SurfaceColors.NEUTRAL, SurfaceColors.ground(null))
    }

    @Test
    fun `the card leans toward the record's colour and never leaves the dark`() {
        for (step in 0..36) {
            val color = SurfaceColors.ground(step / 36f)
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            assertEquals("opaque", 0xFF, (color ushr 24))
            // Dark enough for cream text to read on, whatever the hue.
            assertTrue("too bright at $step: $r $g $b", maxOf(r, g, b) <= 48)
            // And tinted, not grey.
            assertTrue("no colour at $step", maxOf(r, g, b) - minOf(r, g, b) >= 12)
        }
    }

    @Test
    fun `the lean is the hue asked for - red leans red, blue leans blue`() {
        fun parts(hue: Float) = SurfaceColors.ground(hue).let { Triple((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
        val (rr, rg, rb) = parts(0f)
        assertTrue(rr > rg && rr > rb)
        val (gr, gg, gb) = parts(1f / 3f)
        assertTrue(gg > gr && gg > gb)
        val (br, bg, bb) = parts(2f / 3f)
        assertTrue(bb > br && bb > bg)
        // The wheel closes: 0 and 1 are the same red.
        assertEquals(SurfaceColors.ground(0f), SurfaceColors.ground(1f))
        assertNotEquals(SurfaceColors.ground(0f), SurfaceColors.ground(0.5f))
    }

    private fun album(id: String, title: String, artist: String, hue: Float = 0f) = Album(
        id = id,
        shelfId = "shelf",
        title = title,
        artist = artist,
        year = null,
        caseCoverUri = "/covers/$id.jpg",
        discFaceUri = null,
        spineHue = hue,
        slotIndex = 0,
        playCount = 0,
        addedAt = 0L
    )
}
