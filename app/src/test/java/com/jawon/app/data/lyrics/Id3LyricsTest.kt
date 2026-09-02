package com.jawon.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3LyricsTest {

    // ---- builders, so the tests read as tags rather than as byte soup -----------------

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun plain(value: Int, length: Int) = ByteArray(length) { i ->
        ((value shr (8 * (length - 1 - i))) and 0xFF).toByte()
    }

    /** A `USLT` body: encoding, language, description, words. */
    private fun uslt(
        words: String,
        encoding: Int = 3,
        description: String = ""
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write("eng".toByteArray(Charsets.ISO_8859_1))
        when (encoding) {
            0 -> { out.write(description.toByteArray(Charsets.ISO_8859_1)); out.write(0) }
            3 -> { out.write(description.toByteArray(Charsets.UTF_8)); out.write(0) }
            1 -> { out.write(description.toByteArray(Charsets.UTF_16)); out.write(0); out.write(0) }
            2 -> { out.write(description.toByteArray(Charsets.UTF_16BE)); out.write(0); out.write(0) }
        }
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        out.write(words.toByteArray(charset))
        return out.toByteArray()
    }

    private fun tag(
        major: Int,
        frames: List<Pair<String, ByteArray>>,
        extendedHeader: ByteArray? = null
    ): ByteArray {
        val body = ByteArrayOutputStream()
        extendedHeader?.let { body.write(it) }
        for ((id, content) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            when (major) {
                2 -> body.write(plain(content.size, 3))
                3 -> { body.write(plain(content.size, 4)); body.write(byteArrayOf(0, 0)) }
                else -> { body.write(syncSafe(content.size)); body.write(byteArrayOf(0, 0)) }
            }
            body.write(content)
        }
        // Padding, which is what really ends the frame list in most files.
        body.write(ByteArray(16))

        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major); out.write(0)
        out.write(if (extendedHeader != null) 0x40 else 0)
        out.write(syncSafe(body.size()))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    // ---- the versions ----------------------------------------------------------------

    @Test
    fun `reads lyrics from a 2 point 4 tag`() {
        val bytes = tag(4, listOf("USLT" to uslt("Line one\nLine two")))
        assertEquals("Line one\nLine two", Id3Lyrics.read(bytes))
    }

    @Test
    fun `reads lyrics from a 2 point 3 tag, whose frame sizes are not syncsafe`() {
        // 2.3 writes plain lengths and 2.4 writes seven-bit ones. Using the wrong reader walks
        // off into the middle of a frame and finds nothing.
        val bytes = tag(3, listOf("USLT" to uslt("Words")))
        assertEquals("Words", Id3Lyrics.read(bytes))
    }

    @Test
    fun `reads lyrics from a 2 point 2 tag, whose frame names are three letters`() {
        val bytes = tag(2, listOf("ULT" to uslt("Old words")))
        assertEquals("Old words", Id3Lyrics.read(bytes))
    }

    // ---- the encodings ---------------------------------------------------------------

    @Test
    fun `reads every encoding the frame allows`() {
        for (encoding in 0..3) {
            val words = if (encoding == 0) "Plain words" else "Wörds with ümlauts"
            val bytes = tag(4, listOf("USLT" to uslt(words, encoding = encoding)))
            assertEquals("encoding $encoding", words, Id3Lyrics.read(bytes))
        }
    }

    @Test
    fun `skips a description, which is the part everyone forgets`() {
        // Almost always empty, so a parser that ignores it works on most files and mangles the
        // rest — the description would arrive glued to the front of the first line.
        for (encoding in 0..3) {
            val bytes = tag(4, listOf("USLT" to uslt("The words", encoding, "Some description")))
            assertEquals("encoding $encoding", "The words", Id3Lyrics.read(bytes))
        }
    }

    // ---- finding it among other frames -----------------------------------------------

    @Test
    fun `finds the lyrics after other frames`() {
        val bytes = tag(
            4,
            listOf(
                "TIT2" to byteArrayOf(3) + "A title".toByteArray(),
                "APIC" to ByteArray(4_000) { 7 },
                "USLT" to uslt("Found me"),
                "TALB" to byteArrayOf(3) + "An album".toByteArray()
            )
        )
        assertEquals("Found me", Id3Lyrics.read(bytes))
    }

    @Test
    fun `steps over an extended header`() {
        // In 2.3 the size field does not count itself, so a stated size of 6 occupies ten bytes.
        // Reading it either way lands mid-frame and finds nothing.
        val extended = plain(6, 4) + ByteArray(6)
        val bytes = tag(3, listOf("USLT" to uslt("Behind the extra header")), extended)
        assertEquals("Behind the extra header", Id3Lyrics.read(bytes))
    }

    // ---- absence and damage ----------------------------------------------------------

    @Test
    fun `no tag, no lyrics, no complaint`() {
        assertNull(Id3Lyrics.read(ByteArray(0)))
        assertNull(Id3Lyrics.read(ByteArray(2_000)))
        assertNull(Id3Lyrics.read("Not an ID3 tag at all".toByteArray()))
    }

    @Test
    fun `a tag without lyrics reads as none`() {
        val bytes = tag(4, listOf("TIT2" to (byteArrayOf(3) + "Only a title".toByteArray())))
        assertNull(Id3Lyrics.read(bytes))
    }

    @Test
    fun `an empty lyrics frame is not lyrics`() {
        assertNull(Id3Lyrics.read(tag(4, listOf("USLT" to uslt("")))))
        assertNull(Id3Lyrics.read(tag(4, listOf("USLT" to uslt("   \n  ")))))
    }

    @Test
    fun `a truncated or lying tag never throws`() {
        // These run over whatever a stranger's library contains, and a bad tag is not a reason
        // to fail an import.
        val good = tag(4, listOf("USLT" to uslt("Words")))
        for (cut in 0 until good.size) {
            Id3Lyrics.read(good.copyOfRange(0, cut))
        }
        val lying = good.copyOf()
        // Claim a frame far larger than the tag.
        lying[16] = 0x7F; lying[17] = 0x7F; lying[18] = 0x7F; lying[19] = 0x7F
        Id3Lyrics.read(lying)
    }
}
