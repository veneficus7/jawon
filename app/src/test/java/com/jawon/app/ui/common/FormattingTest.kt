package com.jawon.app.ui.common

import com.jawon.app.data.local.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deck's quality readout.
 *
 * This screen exists to tell the truth about the audio to an audience that would notice if it
 * did not, so the cases worth asserting are the ones where a value is *missing* — where the
 * tempting default is a plausible-looking number that happens to be a lie.
 */
class FormattingTest {

    private fun track(
        mimeType: String = "audio/flac",
        sampleRateHz: Int = 44_100,
        bitDepth: Int? = 16
    ) = Track(
        id = "t", albumId = "a", trackNumber = 1, title = "T", durationMs = 1_000L,
        fileUri = "", mimeType = mimeType, sampleRateHz = sampleRateHz,
        bitDepth = bitDepth, bitrateBps = 900_000
    )

    @Test
    fun `a local file reports everything it knows`() {
        assertEquals("FLAC 16-BIT / 44.1 kHz", qualityReadoutSource(track()))
    }

    @Test
    fun `an unknown sample rate prints nothing rather than zero`() {
        // A track streamed from a plain Subsonic server has no `samplingRate` — it is an
        // OpenSubsonic addition. The naive formatting gives "FLAC 16-BIT / 0.0 kHz", which
        // is not a blank in the readout but a false claim in it.
        assertEquals("FLAC 16-BIT", qualityReadoutSource(track(sampleRateHz = 0)))
    }

    @Test
    fun `neither depth nor rate leaves just the codec, with no dangling separator`() {
        assertEquals(
            "MP3",
            qualityReadoutSource(track(mimeType = "audio/mpeg", sampleRateHz = 0, bitDepth = null))
        )
    }

    @Test
    fun `a lossy file with a known rate still reads normally`() {
        assertEquals(
            "MP3 44.1 kHz",
            qualityReadoutSource(track(mimeType = "audio/mpeg", bitDepth = null))
        )
    }
}
