package com.jawon.app.data.cd

/**
 * The bytes an optical drive actually understands, and the envelope they travel in.
 *
 * ## Why a CD needs any of this
 *
 * An audio CD has **no filesystem**. There are no files on it to open — a Red Book disc is a
 * spiral of 2,352-byte sectors of raw stereo PCM, and the only structure is a table of contents
 * saying which sector each track begins at. Android can mount a *data* CD because that carries
 * ISO 9660 or UDF; it can do nothing at all with an audio one. So reading a CD means talking to
 * the drive in its own language: SCSI Multi-Media Commands, over USB Bulk-Only Transport.
 *
 * That is the whole reason this file exists rather than a call to `DocumentFile`.
 *
 * ## Bulk-Only Transport
 *
 * Every command is a three-part conversation on the drive's bulk endpoints:
 *
 * 1. a **CBW** — 31 bytes wrapping the command
 * 2. the **data** — in whichever direction the command asked for
 * 3. a **CSW** — 13 bytes saying whether it worked
 *
 * Everything in it is little-endian, which is worth stating because SCSI command blocks
 * themselves are big-endian. Both conventions appear within a few bytes of each other, and
 * getting either backwards produces a drive that answers every request with an error and no
 * indication why.
 *
 * All of this is pure byte assembly, so all of it is tested on the JVM. The part that cannot be
 * is the USB transfer itself — see [CdDrive].
 */
object ScsiCommands {

    /** `USBC`, little-endian. The drive rejects a CBW that does not start with it. */
    const val CBW_SIGNATURE = 0x43425355

    /** `USBS`, little-endian. */
    const val CSW_SIGNATURE = 0x53425355

    const val CBW_LENGTH = 31
    const val CSW_LENGTH = 13

    /** Data flows drive → phone. The only direction anything here needs. */
    const val DIRECTION_IN = 0x80.toByte()

    /** A raw CD audio sector: 588 stereo frames of 16-bit PCM. */
    const val RAW_SECTOR_BYTES = 2352

    /** Sectors per second of audio. The Red Book rate, and why a CD is 74 minutes. */
    const val SECTORS_PER_SECOND = 75

    /**
     * The gap before track one, in sectors.
     *
     * Two seconds that exist on every disc and are not addressable. Every offset a CD reports is
     * measured from the start of that gap rather than from the start of the audio, so it is added
     * when computing a disc id and subtracted when seeking. Forgetting it is the classic way a
     * disc id comes out wrong while looking entirely plausible.
     */
    const val PREGAP_SECTORS = 150

    /**
     * Wraps a command block for transport.
     *
     * [tag] comes back unchanged in the CSW and is how a reply is matched to its request. It has
     * to change per command; a fixed tag works right up until one command times out, after which
     * every subsequent reply looks like it belongs to the request before it.
     */
    fun commandBlockWrapper(tag: Int, dataLength: Int, command: ByteArray): ByteArray {
        require(command.size in 1..16) { "a command block is 1..16 bytes, was ${command.size}" }
        val cbw = ByteArray(CBW_LENGTH)
        putIntLe(cbw, 0, CBW_SIGNATURE)
        putIntLe(cbw, 4, tag)
        putIntLe(cbw, 8, dataLength)
        cbw[12] = DIRECTION_IN
        cbw[13] = 0 // LUN 0: an optical drive has exactly one.
        cbw[14] = command.size.toByte()
        command.copyInto(cbw, 15)
        return cbw
    }

    /** What a CSW said. [status] 0 is success, 1 is a command failure, 2 is a phase error. */
    data class StatusWrapper(val tag: Int, val residue: Int, val status: Int) {
        val isSuccess: Boolean get() = status == 0
    }

    fun parseStatusWrapper(bytes: ByteArray): StatusWrapper? {
        if (bytes.size < CSW_LENGTH) return null
        if (intLe(bytes, 0) != CSW_SIGNATURE) return null
        return StatusWrapper(
            tag = intLe(bytes, 4),
            residue = intLe(bytes, 8),
            status = bytes[12].toInt() and 0xFF
        )
    }

    /**
     * `READ TOC/PMA/ATIP` — the table of contents, in LBA form.
     *
     * Format 0 with MSF off returns each track's starting *logical block address* directly, which
     * is what the reader wants. The MSF alternative returns minute/second/frame and has to be
     * converted, which is one more place to lose the pregap.
     *
     * Starting track 0 rather than 1 so the lead-out descriptor (track 0xAA) comes back too — the
     * disc id needs where the audio *ends*, and nothing else reports it.
     */
    fun readTocCommand(allocationLength: Int): ByteArray = byteArrayOf(
        0x43,                                   // opcode
        0x00,                                   // MSF off — give LBAs
        0x00,                                   // format 0: the TOC itself
        0x00, 0x00, 0x00,                       // reserved
        0x00,                                   // starting track
        ((allocationLength shr 8) and 0xFF).toByte(),  // big-endian, unlike the CBW
        (allocationLength and 0xFF).toByte(),
        0x00                                    // control
    )

    /**
     * `READ CD` — the audio itself, raw.
     *
     * Sector type 1 restricts this to CD-DA, so a data track returns an error rather than 2,352
     * bytes of filesystem that would be played as noise. The main-channel selection byte `0xF8`
     * asks for user data only: no sync pattern, no headers, no subchannel — just the samples.
     *
     * `READ(10)`, the obvious-looking alternative, is for data discs and silently refuses or
     * returns nonsense on audio tracks, because there is no 2,048-byte block structure to read.
     */
    fun readCdCommand(startLba: Int, sectors: Int): ByteArray = byteArrayOf(
        0xBE.toByte(),                          // opcode
        0x04,                                   // expected sector type 1 (CD-DA), in bits 2..4
        ((startLba shr 24) and 0xFF).toByte(),  // LBA, big-endian
        ((startLba shr 16) and 0xFF).toByte(),
        ((startLba shr 8) and 0xFF).toByte(),
        (startLba and 0xFF).toByte(),
        ((sectors shr 16) and 0xFF).toByte(),   // transfer length, 3 bytes big-endian
        ((sectors shr 8) and 0xFF).toByte(),
        (sectors and 0xFF).toByte(),
        0xF8.toByte(),                          // user data only
        0x00,                                   // no subchannel
        0x00
    )

    /** `TEST UNIT READY` — is there a disc, and has the drive finished spinning up? */
    fun testUnitReadyCommand(): ByteArray = ByteArray(6)

    /** Big-endian, as SCSI itself is. */
    fun intBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Little-endian, as the USB wrappers are. */
    fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
