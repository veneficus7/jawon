package com.jawon.app.data.cd

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * A USB optical drive, spoken to directly.
 *
 * **Everything above this line is tested; nothing in it is.** The command bytes, the table of
 * contents, the disc id and the WAV header are pure and covered. This class is the seam where
 * they meet hardware, and it cannot be exercised without a drive plugged into a phone. It is
 * therefore kept as thin as it can be — it does transfers and nothing else, so that when it is
 * finally run against real hardware the surface that can be wrong is small.
 *
 * ## Why a CD needs a driver at all
 *
 * Android mounts data discs because they carry a filesystem. An audio CD carries none — see
 * [ScsiCommands]. So the drive is claimed as a raw USB device and driven with SCSI commands over
 * Bulk-Only Transport, which is what any operating system does underneath; there is simply no
 * higher-level Android API that reaches audio sectors.
 *
 * ## What has to be true of the drive
 *
 * USB Mass Storage, SCSI command set, Bulk-Only Transport — interface class 8, subclass 6,
 * protocol 80. That covers essentially every USB CD/DVD drive ever sold. It does **not** cover
 * drives that need more current than the phone will supply, which is the most likely practical
 * failure and one no code can detect: the drive simply never enumerates. A powered hub or a
 * drive with its own supply is the answer, and the UI should say so rather than reporting a
 * fault.
 */
class CdDrive(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) : AutoCloseable {

    private var tag = 1

    /**
     * Runs one command and returns the data it produced, or null if the drive refused.
     *
     * The three-phase conversation of Bulk-Only Transport: send the wrapper, read the data, read
     * the status. The status is checked rather than assumed — a drive that has no disc, or is
     * still spinning up, answers commands with a perfectly successful transfer carrying a
     * failure code, exactly as Subsonic does over HTTP.
     */
    fun command(command: ByteArray, expectedBytes: Int): ByteArray? {
        val thisTag = tag++
        val cbw = ScsiCommands.commandBlockWrapper(thisTag, expectedBytes, command)

        if (connection.bulkTransfer(bulkOut, cbw, cbw.size, TIMEOUT_MS) != cbw.size) return null

        val data = ByteArray(expectedBytes)
        var read = 0
        while (read < expectedBytes) {
            // A drive is free to answer a large request in several transfers, and treating the
            // first short read as the whole reply is how a rip ends up with truncated tracks.
            val chunk = connection.bulkTransfer(
                bulkIn,
                data,
                read,
                expectedBytes - read,
                TIMEOUT_MS
            )
            if (chunk <= 0) break
            read += chunk
        }

        val csw = ByteArray(ScsiCommands.CSW_LENGTH)
        if (connection.bulkTransfer(bulkIn, csw, csw.size, TIMEOUT_MS) != csw.size) return null
        val status = ScsiCommands.parseStatusWrapper(csw) ?: return null
        if (!status.isSuccess || status.tag != thisTag) return null

        return if (read == expectedBytes) data else data.copyOf(read)
    }

    /** True once the drive reports a disc present and ready. */
    fun isReady(): Boolean = command(ScsiCommands.testUnitReadyCommand(), 0) != null

    /** The disc's table of contents, or null if there is no readable audio disc in the tray. */
    fun readToc(): CdToc? {
        val response = command(ScsiCommands.readTocCommand(TOC_BUFFER_BYTES), TOC_BUFFER_BYTES)
            ?: return null
        return CdToc.parse(response)
    }

    /**
     * Reads part of a track.
     *
     * Deliberately a chunk at a time rather than a whole track. A CD track can be twenty minutes,
     * which is over two hundred megabytes of raw audio — asking a drive for that in one transfer
     * either fails outright or blocks for minutes with no way to report progress or to stop.
     * [SECTORS_PER_READ] is around a quarter-second of audio, which keeps each transfer small
     * enough to be cancelled between chunks.
     */
    fun readAudio(startLba: Int, sectors: Int): ByteArray? {
        val bytes = sectors * ScsiCommands.RAW_SECTOR_BYTES
        return command(ScsiCommands.readCdCommand(startLba, sectors), bytes)
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000

        /** Enough for ninety-nine tracks plus the header and lead-out, with room to spare. */
        private const val TOC_BUFFER_BYTES = 4 + 100 * 8

        /**
         * Sectors per transfer while ripping — about a third of a second of audio.
         *
         * Small enough that a cancel is responsive and progress moves visibly; large enough that
         * the per-command overhead is not the dominant cost. Drives also vary in the largest
         * transfer they will accept, and 27 is comfortably under every documented limit.
         */
        const val SECTORS_PER_READ = 27

        /**
         * Finds a connected optical drive, if the user has granted access to one.
         *
         * Matched on interface class rather than on vendor and product ids, because there is no
         * list of optical drives worth maintaining and the USB class codes exist precisely so
         * there does not have to be.
         */
        fun findDrive(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { device ->
                (0 until device.interfaceCount).any { index ->
                    val candidate = device.getInterface(index)
                    candidate.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                        candidate.interfaceSubclass == SUBCLASS_SCSI &&
                        candidate.interfaceProtocol == PROTOCOL_BULK_ONLY
                }
            }

        /** Claims the drive and locates its two bulk endpoints. Null if it cannot be opened. */
        fun open(manager: UsbManager, device: UsbDevice): CdDrive? {
            val target = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                        it.interfaceSubclass == SUBCLASS_SCSI &&
                        it.interfaceProtocol == PROTOCOL_BULK_ONLY
                } ?: return null

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until target.endpointCount) {
                val endpoint = target.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint
                else bulkOut = endpoint
            }
            if (bulkIn == null || bulkOut == null) return null

            val connection = manager.openDevice(device) ?: return null
            // Force-claimed: the kernel's own mass-storage driver may already hold this
            // interface, and without taking it every transfer fails silently.
            if (!connection.claimInterface(target, true)) {
                connection.close()
                return null
            }
            return CdDrive(connection, target, bulkIn, bulkOut)
        }

        private const val SUBCLASS_SCSI = 6
        private const val PROTOCOL_BULK_ONLY = 80
    }
}
