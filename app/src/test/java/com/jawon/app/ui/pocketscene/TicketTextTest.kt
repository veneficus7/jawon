package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ticket on the table, as words. What would read as wrong: a destination running off the card,
 * a title cut mid-word where it had room to break, or a ticket that changes its number every time
 * it is drawn.
 */
class TicketTextTest {

    private val day = Triple(2026, 9, 29)

    @Test
    fun `the destination is the album, from its artist, and the berth is the track playing`() {
        val t = TicketText.of("a1", "King Crimson", "Red", trackIndex = 2, trackCount = 5, durationMs = 2_400_000L, day = day)
        assertEquals("KING CRIMSON", t.from)
        assertEquals(listOf("RED"), t.to)
        assertEquals("05", t.car)
        assertEquals("03", t.berth)
        assertEquals("29 SEP 26", t.date)
        assertEquals("40 MIN", t.journey)
    }

    @Test
    fun `a long title breaks between words onto a second line, and what will not fit ends in an ellipsis`() {
        val t = TicketText.of("a2", "King Crimson", "In the Court of the Crimson King", 0, 5, 1L, day)
        assertEquals(2, t.to.size)
        assertTrue(t.to.all { it.length <= TicketText.TO_CHARS })
        assertEquals("IN THE COURT OF THE", t.to[0])
        assertEquals("CRIMSON KING", t.to[1])
        val long = TicketText.wrap("A TITLE THAT GOES ON AND ON FAR PAST WHAT TWO LINES OF A TICKET HOLD", 21)
        assertEquals(2, long.size)
        assertTrue(long[1].endsWith("…"))
        assertTrue(long.all { it.length <= 21 })
    }

    @Test
    fun `a word longer than a line is broken rather than run off the card`() {
        val lines = TicketText.wrap("SUPERCALIFRAGILISTICEXPIALIDOCIOUS", 21)
        assertTrue(lines.all { it.length <= 21 })
        assertEquals("SUPERCALIFRAGILISTICE", lines[0])
    }

    @Test
    fun `nothing played yet, it is a ticket to anywhere`() {
        val t = TicketText.of(null, null, null, -1, 0, 0L, day)
        assertEquals(listOf("ANYWHERE"), t.to)
        assertEquals("--", t.berth)
    }

    @Test
    fun `the same album keeps the same number, and another gets another`() {
        val a = TicketText.of("album-1", "A", "B", 0, 1, 1L, day)
        val again = TicketText.of("album-1", "A", "B", 3, 9, 99L, Triple(2027, 1, 1))
        val other = TicketText.of("album-2", "A", "B", 0, 1, 1L, day)
        assertEquals(a.serial, again.serial)
        assertTrue(a.serial != other.serial)
    }

    @Test
    fun `an artist too long for the card is cut with an ellipsis`() {
        val t = TicketText.of("a3", "Godspeed You! Black Emperor and Friends of the Orchestra", "F#A#", 0, 3, 1L, day)
        assertTrue(t.from.length <= TicketText.FROM_CHARS)
        assertTrue(t.from.endsWith("…"))
    }
}
