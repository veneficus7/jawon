package com.jawon.app.ui.common

import com.jawon.app.data.local.Track
import com.jawon.app.data.local.isRemoteControlled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the deck is allowed to claim about audio it has never seen.
 *
 * This exists because the readout got it wrong in the owner's hands, and got it wrong in the
 * most expensive direction: it told a paying Spotify subscriber that his lossless was lossy.
 * The mechanism was ordinary — a Spotify track's `mimeType` is empty, empty is not in the
 * lossless set, and the `else` branch said LOSSY — and the lesson is not about MIME types.
 * **An empty field is "unknown", and unknown is not a value to print a verdict from.**
 */
class QualityReadoutTest {

    private fun track(
        id: String = "track-1",
        mimeType: String = "audio/flac",
        sampleRateHz: Int = 44_100
    ) = Track(
        id = id,
        albumId = "album-1",
        trackNumber = 1,
        title = "Idioteque",
        durationMs = 100_000L,
        fileUri = if (mimeType.isBlank()) "" else "content://media/external/audio/media/42",
        mimeType = mimeType,
        sampleRateHz = sampleRateHz,
        bitDepth = 16,
        bitrateBps = 900_000
    )

    @Test
    fun `a spotify track is never called lossy or lossless`() {
        // The owner: "the audio quality is lossy, but i have lossless in spotify itself."
        val readout = qualityReadoutOutput(
            track(id = "spotify:track:4kflIGfjdZJW4ot2ioixTB", mimeType = "", sampleRateHz = 0),
            routeLabel = "WIRED",
            outputSampleRateHz = 48_000
        )
        assertFalse("must not claim lossy: $readout", readout.contains("LOSSY"))
        assertFalse("must not claim lossless either: $readout", readout.contains("LOSSLESS"))

        // And it should say who DOES decide, because that is the actionable half.
        assertTrue("should name Spotify: $readout", readout.contains("SPOTIFY"))
    }

    @Test
    fun `a spotify track does not report an output rate Jawon is not producing`() {
        // The resample leg describes what this app's own player is doing to a stream. On a
        // record playing in another process it is not a smaller truth, it is a different one.
        val readout = qualityReadoutOutput(
            track(id = "spotify:track:x", mimeType = "", sampleRateHz = 0),
            routeLabel = "",
            outputSampleRateHz = 48_000
        )
        assertFalse("must not report an output rate: $readout", readout.contains("48"))
    }

    @Test
    fun `a local lossless file still reads as lossless`() {
        // The other half of the fix: nothing about a record this app really does decode moved.
        val readout = qualityReadoutOutput(track(), "WIRED", 44_100)
        assertTrue(readout.startsWith("LOSSLESS"))
        assertTrue(readout.contains("WIRED"))
    }

    @Test
    fun `a local lossy file still reads as lossy, and resampling still shows`() {
        val readout = qualityReadoutOutput(track(mimeType = "audio/mpeg"), "WIRED", 48_000)
        assertTrue(readout.startsWith("LOSSY"))
        assertTrue("resampling is worth saying: $readout", readout.contains("48 kHz"))
    }

    @Test
    fun `only spotify ids are remote-controlled`() {
        // The id is the test rather than a nullable column, because the sync that owns the
        // record mints it. `remoteId` was the old test and a music server's tracks carry one
        // too, which is exactly how the offline control ended up on a Spotify album.
        assertTrue(track(id = "spotify:track:abc").isRemoteControlled)
        assertFalse(track(id = "subsonic:track:abc").isRemoteControlled)
        assertFalse(track(id = "archive:track:abc").isRemoteControlled)
        assertFalse(track(id = "0d1f-local-uuid").isRemoteControlled)
    }

    @Test
    fun `the readout never ends in a stray separator`() {
        // With no route and no resampling there is nothing to append, and a trailing middot
        // is the kind of detail that makes a careful readout look careless.
        assertEquals("LOSSLESS", qualityReadoutOutput(track(), "", 44_100))
        assertEquals(
            "SPOTIFY SETS THE QUALITY",
            qualityReadoutOutput(track(id = "spotify:track:x", mimeType = "", sampleRateHz = 0), "", 0)
        )
    }
}
