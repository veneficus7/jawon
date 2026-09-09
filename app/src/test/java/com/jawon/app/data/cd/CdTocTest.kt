package com.jawon.app.data.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of CD reading that can be checked without a drive — which is all of them except the
 * USB transfer itself.
 *
 * That split is the point of the design. A disc id computed slightly wrongly matches nothing on
 * MusicBrainz and is indistinguishable from a disc nobody has catalogued, so it has to be right
 * before hardware is ever involved.
 */
class CdTocTest {

    /**
     * A TOC response as a drive sends one: a four-byte header, then eight bytes per descriptor.
     * Three audio tracks and the lead-out.
     */
    private fun tocBytes(
        entries: List<Triple<Int, Int, Boolean>>,
        leadOutLba: Int
    ): ByteArray {
        val all = entries + Triple(CdToc.LEAD_OUT, leadOutLba, true)
        val body = ByteArray(all.size * 8)
        all.forEachIndexed { index, (number, lba, isAudio) ->
            val at = index * 8
            body[at] = 0
            // Control in the high nibble; bit 2 set marks a data track.
            body[at + 1] = (if (isAudio) 0x10 else 0x50).toByte()
            body[at + 2] = number.toByte()
            body[at + 3] = 0
            body[at + 4] = ((lba shr 24) and 0xFF).toByte()
            body[at + 5] = ((lba shr 16) and 0xFF).toByte()
            body[at + 6] = ((lba shr 8) and 0xFF).toByte()
            body[at + 7] = (lba and 0xFF).toByte()
        }
        val dataLength = body.size + 2
        val realTracks = all.filter { it.first != CdToc.LEAD_OUT }
        return byteArrayOf(
            ((dataLength shr 8) and 0xFF).toByte(),
            (dataLength and 0xFF).toByte(),
            1,
            // Zero when there are none — a lead-out-only TOC is what a blank or unreadable disc
            // reports, and the helper has to be able to build that case for the test below.
            (realTracks.maxOfOrNull { it.first } ?: 0).toByte()
        ) + body
    }

    // ---- the table of contents --------------------------------------------------------------

    @Test
    fun `track lengths come from the gap to whatever starts next`() {
        // A CD states where each track *begins* and never how long it is. The length of the last
        // track exists nowhere except as the distance to the lead-out, which is why the lead-out
        // has to be kept rather than discarded as "not a track".
        val toc = CdToc.parse(
            tocBytes(
                listOf(
                    Triple(1, 0, true),
                    Triple(2, 15_000, true),
                    Triple(3, 30_000, true)
                ),
                leadOutLba = 45_000
            )
        )!!

        assertEquals(3, toc.tracks.size)
        assertEquals(15_000, toc.tracks[0].sectors)
        assertEquals(15_000, toc.tracks[1].sectors)
        assertEquals(15_000, toc.tracks[2].sectors)
        assertEquals(45_000, toc.leadOutLba)
    }

    @Test
    fun `the lead-out is not a track`() {
        // Treating 0xAA as a track gives every disc a phantom final entry of zero or impossible
        // length, which then appears on the tracklist.
        val toc = CdToc.parse(tocBytes(listOf(Triple(1, 0, true)), leadOutLba = 10_000))!!
        assertEquals(1, toc.tracks.size)
        assertEquals(1, toc.tracks.single().number)
    }

    @Test
    fun `a data track on a mixed-mode disc is marked, not ripped`() {
        // Reading a data track as audio returns 2352 bytes of filesystem per sector, which plays
        // as full-scale noise. Enhanced CDs from the nineties are full of these.
        val toc = CdToc.parse(
            tocBytes(
                listOf(Triple(1, 0, true), Triple(2, 20_000, false)),
                leadOutLba = 40_000
            )
        )!!

        assertTrue(toc.tracks[0].isAudio)
        assertFalse(toc.tracks[1].isAudio)
        assertEquals("only the audio track is offered", 1, toc.audioTracks.size)
    }

    @Test
    fun `duration is derived at the Red Book rate`() {
        // 75 sectors per second, which is what makes a CD 74 minutes rather than a round number.
        val toc = CdToc.parse(
            tocBytes(listOf(Triple(1, 0, true)), leadOutLba = 75 * 60)
        )!!
        assertEquals(60_000L, toc.tracks.single().durationMs)
    }

    @Test
    fun `a truncated or empty response is refused rather than half-read`() {
        assertNull(CdToc.parse(ByteArray(0)))
        assertNull(CdToc.parse(byteArrayOf(0, 2, 1, 1)))
        // A TOC with a lead-out and nothing else is a blank or unreadable disc.
        assertNull(CdToc.parse(tocBytes(emptyList(), leadOutLba = 100)))
    }

    // ---- the disc id -------------------------------------------------------------------------

    @Test
    fun `the disc id matches a second, independent implementation of the algorithm`() {
        // **Checked against something other than itself.** This is the entire mechanism by which
        // an anonymous piece of plastic acquires an artist and a tracklist, and an id that is
        // nearly right matches nothing on MusicBrainz while looking exactly like a disc nobody
        // has catalogued. A round-trip test would prove nothing.
        //
        // The expected value below was produced by writing the documented algorithm a second
        // time, from the specification, in another language, and comparing. Two independent
        // implementations agreeing rules out the mistakes that matter here — lowercase hex, the
        // pregap omitted, the lead-out in the wrong slot, padding left on the base64 — because
        // each of those would have to be made identically twice.
        //
        // It is *not* a vector published by MusicBrainz. If this ever needs to be authoritative,
        // rip a real disc and compare against what their web service returns for it.
        val offsets = listOf(
            150, 18901, 39738, 59557, 79152, 100126, 124833, 147278,
            166336, 182560, 203821, 224720, 243951, 266083, 289594
        )
        val leadOut = 325732

        val tracks = offsets.mapIndexed { index, offset ->
            val next = offsets.getOrNull(index + 1) ?: leadOut
            CdTrack(
                number = index + 1,
                // Stored without the pregap; discId adds it back.
                startLba = offset - ScsiCommands.PREGAP_SECTORS,
                sectors = next - offset,
                isAudio = true
            )
        }
        val toc = CdToc(tracks, leadOutLba = leadOut - ScsiCommands.PREGAP_SECTORS)

        assertEquals("diNWqexTwQawRtHN.qRi7vOg4_I-", toc.discId())
    }

    @Test
    fun `the disc id is URL-safe`() {
        // Base64 with +, / and = would be mangled the moment it went into a query string, which
        // is the only place it is ever used.
        val toc = CdToc.parse(
            tocBytes(
                (1..8).map { Triple(it, it * 12_345, true) },
                leadOutLba = 120_000
            )
        )!!
        val id = toc.discId()

        assertTrue(id.isNotBlank())
        assertFalse(id.contains('+'))
        assertFalse(id.contains('/'))
        assertFalse(id.contains('='))
    }

    @Test
    fun `two different discs do not share an id`() {
        fun idFor(leadOut: Int) = CdToc.parse(
            tocBytes(listOf(Triple(1, 0, true), Triple(2, 20_000, true)), leadOutLba = leadOut)
        )!!.discId()

        assertTrue(idFor(40_000) != idFor(41_000))
    }

    @Test
    fun `a disc with no audio has no id rather than a misleading one`() {
        val toc = CdToc.parse(
            tocBytes(listOf(Triple(1, 0, false)), leadOutLba = 10_000)
        )!!
        assertEquals("", toc.discId())
    }
}
