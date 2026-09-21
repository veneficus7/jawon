package com.jawon.app.ui.common

import com.jawon.app.data.local.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second half of the deck's quality readout: what is actually reaching your ears.
 *
 * `FormattingTest` covers the first half, what the file *is*. This half makes a claim about the
 * whole path — lossless or lossy, the route, and a resample when Android is doing one — and it is
 * the one an audience that cares about audio would catch being wrong.
 */
class QualityReadoutTest {

    private fun track(mimeType: String = "audio/flac") = Track(
        id = "track-1",
        albumId = "album-1",
        trackNumber = 1,
        title = "Idioteque",
        durationMs = 100_000L,
        fileUri = "content://media/external/audio/media/42",
        mimeType = mimeType,
        sampleRateHz = 44_100,
        bitDepth = 16,
        bitrateBps = 900_000
    )

    @Test
    fun `a local lossless file reads as lossless`() {
        val readout = qualityReadoutOutput(track(), "WIRED", 44_100)
        assertTrue(readout.startsWith("LOSSLESS"))
        assertTrue(readout.contains("WIRED"))
    }

    @Test
    fun `a local lossy file reads as lossy, and resampling shows`() {
        val readout = qualityReadoutOutput(track(mimeType = "audio/mpeg"), "WIRED", 48_000)
        assertTrue(readout.startsWith("LOSSY"))
        assertTrue("resampling is worth saying: $readout", readout.contains("48 kHz"))
    }

    @Test
    fun `the readout never ends in a stray separator`() {
        // With no route and no resampling there is nothing to append, and a trailing middot
        // is the kind of detail that makes a careful readout look careless.
        assertEquals("LOSSLESS", qualityReadoutOutput(track(), "", 44_100))
    }
}
