package com.jawon.app.data.cd

import java.io.OutputStream

/**
 * Wraps raw CD sectors in a WAV header, which is all a rip actually requires.
 *
 * **A CD sector is already the audio.** 2,352 bytes of 16-bit little-endian stereo at 44,100 Hz —
 * byte for byte what a WAV file's data chunk holds. So "ripping" a track is not a conversion: it
 * is writing a forty-four byte header and then copying sectors. Nothing is decoded, nothing is
 * re-encoded, and the result is bit-identical to the disc.
 *
 * That is worth being deliberate about, because the alternative is tempting and worse. Encoding
 * to FLAC on the phone would save perhaps 40% of the space and cost a great deal of CPU, and
 * lossless-to-lossless means the only thing at stake is disk. Someone ripping their own CDs into
 * this app has already decided they care about the master; handing them the exact samples and
 * letting them convert later, on a computer, with a tool they chose, is the honest default.
 *
 * The header is written before the size is known, so the two length fields are patched
 * afterwards — see [writeHeader].
 */
object WavWriter {

    const val HEADER_BYTES = 44

    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16

    /**
     * A 44-byte canonical WAV header for [dataBytes] of PCM.
     *
     * Everything here is little-endian including the RIFF sizes, unlike the SCSI commands that
     * fetched the audio. Both appear within one file and the two are easy to confuse.
     */
    fun header(dataBytes: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteArray(HEADER_BYTES)

        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putIntLe(header, 4, 36 + dataBytes)   // everything after this field
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)

        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        putIntLe(header, 16, 16)              // PCM fmt chunk is 16 bytes
        putShortLe(header, 20, 1)             // format 1: uncompressed PCM
        putShortLe(header, 22, CHANNELS)
        putIntLe(header, 24, SAMPLE_RATE)
        putIntLe(header, 28, byteRate)
        putShortLe(header, 32, blockAlign)
        putShortLe(header, 34, BITS_PER_SAMPLE)

        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        putIntLe(header, 40, dataBytes)
        return header
    }

    /** Writes the header for a track of [sectors] raw CD sectors. */
    fun writeHeader(out: OutputStream, sectors: Int) {
        out.write(header(sectors * ScsiCommands.RAW_SECTOR_BYTES))
    }

    /** How large the finished file will be, so progress can be reported honestly. */
    fun fileBytesFor(sectors: Int): Long =
        HEADER_BYTES + sectors.toLong() * ScsiCommands.RAW_SECTOR_BYTES

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
