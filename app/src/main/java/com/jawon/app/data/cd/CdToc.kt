package com.jawon.app.data.cd

import java.security.MessageDigest

/** One track on a disc, as the table of contents describes it. */
data class CdTrack(
    val number: Int,
    /** Where the audio starts, in sectors, not counting the pregap. */
    val startLba: Int,
    val sectors: Int,
    /** False for a data track on a mixed-mode disc, which must not be read as audio. */
    val isAudio: Boolean
) {
    val durationMs: Long get() = sectors * 1000L / ScsiCommands.SECTORS_PER_SECOND
    val bytes: Long get() = sectors.toLong() * ScsiCommands.RAW_SECTOR_BYTES
}

/** A whole disc: what is on it, and what it is. */
data class CdToc(val tracks: List<CdTrack>, val leadOutLba: Int) {

    val audioTracks: List<CdTrack> get() = tracks.filter { it.isAudio }

    val totalDurationMs: Long get() = audioTracks.sumOf { it.durationMs }

    /**
     * The MusicBrainz disc id — the fingerprint that turns an anonymous disc into an album.
     *
     * **This is the only thing that can name a CD.** A Red Book disc carries no title, no artist
     * and no track names; the plastic knows nothing about the music on it. What it does have is a
     * unique arrangement of track lengths, and hashing that arrangement gives an id that
     * MusicBrainz can look up. It is why a CD inserted into any player since the nineties
     * magically knows what it is.
     *
     * The exact recipe matters, because a disc id that is nearly right matches nothing at all and
     * looks identical to a disc nobody has catalogued:
     *
     * * uppercase hex throughout, first track and last track as two digits each
     * * the lead-out offset, then ninety-nine track offsets, as **eight** digits each
     * * absent tracks are eight zeros, not omitted
     * * every offset includes the 150-sector pregap
     * * SHA-1, base64, then `+/=` rewritten as `._-` so it is safe in a URL
     */
    fun discId(): String {
        val audio = audioTracks
        if (audio.isEmpty()) return ""
        val first = audio.first().number
        val last = audio.last().number

        val builder = StringBuilder()
        builder.append("%02X".format(first))
        builder.append("%02X".format(last))
        // Slot zero is the lead-out, which is where the *last* track ends — the one length that
        // no track descriptor carries.
        builder.append("%08X".format(leadOutLba + ScsiCommands.PREGAP_SECTORS))
        for (i in 1..99) {
            val track = audio.firstOrNull { it.number == i }
            builder.append(
                "%08X".format(
                    if (track == null) 0 else track.startLba + ScsiCommands.PREGAP_SECTORS
                )
            )
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(builder.toString().toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getEncoder().encodeToString(sha1)
            .replace('+', '.')
            .replace('/', '_')
            .replace('=', '-')
    }

    companion object {

        /**
         * Reads what `READ TOC` sent back.
         *
         * The reply is a four-byte header — a length, then the first and last track numbers —
         * followed by eight bytes per track. Track `0xAA` is not a track: it is the lead-out, the
         * marker for where the audio stops. Treating it as one is how a disc gains a phantom
         * final track of impossible length.
         *
         * A track's length is not stated anywhere. It is the distance to whatever starts next,
         * which is why the descriptors have to be read in order and the lead-out kept.
         */
        fun parse(response: ByteArray): CdToc? {
            if (response.size < 4) return null
            // The header length excludes its own two bytes.
            val dataLength = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
            val end = minOf(response.size, dataLength + 2)
            if (end < 12) return null

            data class Descriptor(val number: Int, val lba: Int, val isAudio: Boolean)

            val descriptors = mutableListOf<Descriptor>()
            var offset = 4
            while (offset + 8 <= end) {
                val control = (response[offset + 1].toInt() and 0xFF) shr 4
                val number = response[offset + 2].toInt() and 0xFF
                val lba = ScsiCommands.intBe(response, offset + 4)
                // Bit 2 of the control field marks a data track. On a mixed-mode disc reading one
                // as audio yields 2,352 bytes of filesystem, which plays as full-scale noise.
                descriptors += Descriptor(number, lba, isAudio = (control and 0b0100) == 0)
                offset += 8
            }
            if (descriptors.isEmpty()) return null

            val leadOut = descriptors.firstOrNull { it.number == LEAD_OUT } ?: return null
            val real = descriptors.filter { it.number != LEAD_OUT }.sortedBy { it.number }
            if (real.isEmpty()) return null

            val tracks = real.mapIndexed { index, descriptor ->
                val next = real.getOrNull(index + 1)?.lba ?: leadOut.lba
                CdTrack(
                    number = descriptor.number,
                    startLba = descriptor.lba,
                    sectors = (next - descriptor.lba).coerceAtLeast(0),
                    isAudio = descriptor.isAudio
                )
            }
            return CdToc(tracks, leadOut.lba)
        }

        /** The descriptor number that means "end of audio" rather than a track. */
        const val LEAD_OUT = 0xAA
    }
}
