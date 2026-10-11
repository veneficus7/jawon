package com.jawon.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The audio kept decoded round the playhead, so a hand finds the record already there.
 *
 * What can go quietly wrong here is one thing: a stretch that says it starts somewhere it does
 * not. Nothing crashes and nothing clicks — the scratch simply takes over a few hundredths of a
 * second from where the player was, and the record under the finger is not quite the record that
 * was playing.
 */
class RollingStretchTest {

    private val rate = 1_000

    /** Frames whose value is their own number, so any frame can say where in the track it is from. */
    private fun frames(from: Int, count: Int, channels: Int) =
        ShortArray(count * channels) { ((from + it / channels) % 30_000).toShort() }

    @Test
    fun `what is held starts where it was told and ends where the audio does`() {
        val stretch = RollingStretch(rate, 2)
        stretch.reset(12.5)
        assertEquals(0, stretch.frames)
        assertEquals(12.5, stretch.endSeconds, 0.0)
        stretch.append(frames(0, 400, 2), 800)
        stretch.append(frames(400, 600, 2), 1_200)
        assertEquals(1_000, stretch.frames)
        assertEquals(12.5, stretch.startSeconds, 0.0)
        assertEquals(13.5, stretch.endSeconds, 1e-9)
    }

    @Test
    fun `letting go of the front moves the start by exactly what went - every frame is still where it says`() {
        for (channels in 1..2) {
            val stretch = RollingStretch(rate, channels)
            stretch.reset(40.0)
            var given = 0
            // Decoded in uneven pieces and dropped from the front as a playhead moves on.
            for (piece in listOf(700, 1_300, 450, 2_050, 900)) {
                stretch.append(frames(given, piece, channels), piece * channels)
                given += piece
                stretch.dropBefore(40.0 + given / rate.toDouble() - 1.8)
                val window = stretch.window()
                assertEquals(stretch.startSeconds, window.startSeconds, 0.0)
                assertEquals(stretch.endSeconds, window.endSeconds, 1e-9)
                assertEquals(40.0 + given / rate.toDouble(), window.endSeconds, 1e-9)
                // The frame at any time is the frame that was decoded for that time.
                for (i in 0 until window.frames step 97) {
                    val at = window.startSeconds + i.toDouble() / rate
                    val was = Math.round((at - 40.0) * rate).toInt()
                    for (c in 0 until channels) {
                        assertEquals("frame $i of ${window.frames}", was % 30_000, window.samples[i * channels + c].toInt())
                    }
                }
            }
        }
    }

    @Test
    fun `it lets go only when there is a second of it to let go, and never more than it holds`() {
        val stretch = RollingStretch(rate, 1)
        stretch.reset(0.0)
        stretch.append(frames(0, 3_000, 1), 3_000)
        // A playhead creeping forward: nothing is moved until a second has built up.
        stretch.dropBefore(0.4)
        stretch.dropBefore(0.99)
        assertEquals(0.0, stretch.startSeconds, 0.0)
        assertEquals(3_000, stretch.frames)
        stretch.dropBefore(1.25)
        assertEquals(1.25, stretch.startSeconds, 1e-9)
        assertEquals(1_750, stretch.frames)
        // Asked to drop past its own end, it is empty and starts where it ended.
        stretch.dropBefore(9.0)
        assertEquals(0, stretch.frames)
        assertEquals(3.0, stretch.startSeconds, 1e-9)
        assertEquals(3.0, stretch.endSeconds, 1e-9)
    }

    @Test
    fun `half a frame is not a frame, and a window is a copy nothing writes to again`() {
        val stretch = RollingStretch(rate, 2)
        stretch.reset(0.0)
        stretch.append(frames(0, 10, 2), 19)
        assertEquals(9, stretch.frames)
        val window = stretch.window()
        stretch.append(frames(9, 500, 2), 1_000)
        stretch.dropBefore(0.0, worthIt = 0.0)
        assertEquals(9, window.frames)
        assertEquals(18, window.samples.size)
        assertEquals(8, window.samples[16].toInt())
    }

    @Test
    fun `started again it holds nothing of what it had`() {
        val stretch = RollingStretch(rate, 1)
        stretch.reset(5.0)
        stretch.append(frames(0, 2_000, 1), 2_000)
        stretch.reset(61.0)
        assertEquals(0, stretch.frames)
        stretch.append(frames(7, 100, 1), 100)
        assertEquals(61.0, stretch.startSeconds, 0.0)
        assertEquals(61.1, stretch.endSeconds, 1e-9)
        assertEquals(7, stretch.window().samples[0].toInt())
    }
}
