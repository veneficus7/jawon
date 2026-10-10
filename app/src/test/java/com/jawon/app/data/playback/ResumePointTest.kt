package com.jawon.app.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a record picks up — one rule for the wall, the widget, a car and a voice. */
class ResumePointTest {

    private val record = listOf(200_000L, 180_000L, 240_000L)

    @Test
    fun `a record put back on is where it was left`() {
        assertEquals(ResumePoint.At(1, 42_000L), ResumePoint.of(1, 42_000L, record))
    }

    @Test
    fun `a record never played starts at the top`() {
        assertEquals(ResumePoint.TOP, ResumePoint.of(0, 0L, record))
    }

    @Test
    fun `a record that was finished starts again, rather than playing its last breath`() {
        assertEquals(ResumePoint.TOP, ResumePoint.of(2, 240_000L, record))
        assertEquals(ResumePoint.TOP, ResumePoint.of(2, 240_000L - ResumePoint.FINISHED_TAIL_MS, record))
        // A few seconds earlier than that and it was not finished.
        val earlier = 240_000L - ResumePoint.FINISHED_TAIL_MS - 1
        assertEquals(ResumePoint.At(2, earlier), ResumePoint.of(2, earlier, record))
    }

    @Test
    fun `the end of a track that is not the last is not the end of the record`() {
        assertEquals(ResumePoint.At(1, 179_000L), ResumePoint.of(1, 179_000L, record))
    }

    @Test
    fun `a place that no longer exists is brought back onto the record`() {
        // Tracks removed since: the saved index is past the end.
        assertEquals(ResumePoint.At(2, 10_000L), ResumePoint.of(7, 10_000L, record))
        assertEquals(ResumePoint.At(0, 0L), ResumePoint.of(-3, -50L, record))
        assertEquals(ResumePoint.TOP, ResumePoint.of(3, 10_000L, emptyList()))
    }
}
