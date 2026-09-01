package com.jawon.app.ui.wallscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckSheetTest {

    @Test
    fun `each detent snaps to itself`() {
        for (detent in DeckDetent.entries) {
            assertEquals(detent, DeckDetent.nearestTo(detent.fraction))
        }
    }

    @Test
    fun `a release lands on the nearest stop, not the one it came from`() {
        // Just past the midpoint between PEEK and DECK should commit to DECK.
        val midPeekDeck = (DeckDetent.PEEK.fraction + DeckDetent.DECK.fraction) / 2f
        assertEquals(DeckDetent.PEEK, DeckDetent.nearestTo(midPeekDeck - 0.01f))
        assertEquals(DeckDetent.DECK, DeckDetent.nearestTo(midPeekDeck + 0.01f))

        val midDeckFull = (DeckDetent.DECK.fraction + DeckDetent.FULL.fraction) / 2f
        assertEquals(DeckDetent.DECK, DeckDetent.nearestTo(midDeckFull - 0.01f))
        assertEquals(DeckDetent.FULL, DeckDetent.nearestTo(midDeckFull + 0.01f))
    }

    @Test
    fun `dragging past either end still lands somewhere sensible`() {
        assertEquals(DeckDetent.PEEK, DeckDetent.nearestTo(-5f))
        assertEquals(DeckDetent.PEEK, DeckDetent.nearestTo(0f))
        assertEquals(DeckDetent.FULL, DeckDetent.nearestTo(1f))
        assertEquals(DeckDetent.FULL, DeckDetent.nearestTo(99f))
    }

    @Test
    fun `a nonsense fraction rests rather than vanishing`() {
        // A sheet that resolves to 0 is a deck that disappeared.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(DeckDetent.DECK, DeckDetent.nearestTo(bad))
            assertEquals(DeckDetent.DECK.fraction, DeckDetent.clamp(bad), 1e-6f)
        }
    }

    @Test
    fun `the drag has give past each end but not much`() {
        // A sheet that stops dead at its limit feels broken; a little give reads as a limit.
        assertTrue(DeckDetent.MIN_FRACTION < DeckDetent.PEEK.fraction)
        assertTrue(DeckDetent.MAX_FRACTION > DeckDetent.FULL.fraction)
        assertEquals(DeckDetent.MIN_FRACTION, DeckDetent.clamp(-1f), 1e-6f)
        assertEquals(DeckDetent.MAX_FRACTION, DeckDetent.clamp(2f), 1e-6f)
        // The give must not be enough to hide the disc or swallow the transport.
        assertTrue(DeckDetent.MIN_FRACTION > 0.2f)
        assertTrue(DeckDetent.MAX_FRACTION < 0.95f)
    }

    @Test
    fun `the stops are ordered and distinct`() {
        val fractions = DeckDetent.entries.map { it.fraction }
        assertEquals(fractions.sorted(), fractions)
        assertEquals(fractions.distinct().size, fractions.size)
        // Far enough apart that snapping is unambiguous under a thumb.
        for (i in 1 until fractions.size) {
            assertTrue("stops $i too close", fractions[i] - fractions[i - 1] > 0.15f)
        }
    }

    @Test
    fun `clamping leaves ordinary values alone`() {
        for (f in listOf(0.30f, 0.45f, 0.575f, 0.7f, 0.88f)) {
            assertEquals(f, DeckDetent.clamp(f), 1e-6f)
        }
    }
}
