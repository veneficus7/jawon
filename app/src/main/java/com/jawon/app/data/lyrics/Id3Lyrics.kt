package com.jawon.app.data.lyrics

import java.nio.charset.Charset

/**
 * Digs the unsynchronised-lyrics frame out of an ID3 tag.
 *
 * **Why by hand.** `MediaMetadataRetriever` reads title, artist, album and a dozen other things
 * and does not expose lyrics at all; Media3's ID3 decoder hands unknown frames back as raw
 * bytes, which is this work with extra ceremony. The frame itself is simple enough that parsing
 * it directly is smaller than either.
 *
 * `USLT` carries plain words with no timing, which is the offline norm — the synchronised
 * frame, `SYLT`, exists and is written by almost nothing. Timed lyrics come from a `.lrc`
 * beside the file; see [LrcParser].
 *
 * Pure, over a byte array, so every awkward corner of the format is testable: three tag
 * versions with three different ways of writing a frame length, and four text encodings.
 */
object Id3Lyrics {

    /**
     * The unsynced lyrics in [bytes], or null if the tag has none.
     *
     * [bytes] should be the start of the file — the tag lives there. Anything malformed reads
     * as "no lyrics" rather than throwing: this runs over whatever files a stranger's library
     * happens to contain, and a bad tag is not a reason to fail an import.
     */
    fun read(bytes: ByteArray): String? = runCatching { parse(bytes) }.getOrNull()

    private fun parse(bytes: ByteArray): String? {
        if (bytes.size < HEADER || bytes[0] != 'I'.code.toByte() ||
            bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()
        ) return null

        val major = bytes[3].toInt()
        val flags = bytes[5].toInt()
        val tagSize = syncSafe(bytes, 6)
        var at = HEADER

        // An extended header sits between the tag header and the first frame, and its own length
        // is written differently in 2.3 (plain, not counting itself) and 2.4 (syncsafe, counting
        // itself). Getting this wrong lands mid-frame and finds nothing.
        if (flags and 0x40 != 0) {
            at += when (major) {
                3 -> 4 + plain(bytes, at, 4)
                4 -> syncSafe(bytes, at)
                else -> 0
            }
        }

        val end = minOf(bytes.size, HEADER + tagSize)
        // 2.2 used three-character frame names; 2.3 and 2.4 use four.
        val idLength = if (major == 2) 3 else 4
        val sizeLength = if (major == 2) 3 else 4
        val flagLength = if (major == 2) 0 else 2
        val wanted = if (major == 2) "ULT" else "USLT"

        while (at + idLength + sizeLength + flagLength <= end) {
            val id = String(bytes, at, idLength, Charsets.ISO_8859_1)
            // A run of zero bytes is the padding that ends the frames.
            if (id.isEmpty() || id[0] == '\u0000') return null

            val size = when {
                major == 4 -> syncSafe(bytes, at + idLength)
                else -> plain(bytes, at + idLength, sizeLength)
            }
            if (size <= 0) return null

            val body = at + idLength + sizeLength + flagLength
            if (body + size > end) return null

            if (id == wanted) return decodeFrame(bytes, body, size)
            at = body + size
        }
        return null
    }

    /**
     * `USLT` is an encoding byte, a three-letter language, a null-terminated description, and
     * then the words.
     *
     * The description is the part that catches people out: it is almost always empty, so a
     * parser that forgets to skip it still works on most files and mangles the rest.
     */
    private fun decodeFrame(bytes: ByteArray, start: Int, size: Int): String? {
        if (size < 5) return null
        val charset = charsetFor(bytes[start].toInt()) ?: return null
        // Encoding byte, then three bytes of language code.
        var at = start + 4
        val end = start + size

        // Two-byte terminators for the UTF-16 encodings, and the pair has to be aligned to the
        // character grid or a text byte that happens to be zero ends the description early.
        val wide = charset == Charsets.UTF_16 || charset == Charsets.UTF_16BE
        while (at < end) {
            if (wide) {
                if (at + 1 < end && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte()) {
                    at += 2
                    break
                }
                at += 2
            } else {
                if (bytes[at] == 0.toByte()) {
                    at += 1
                    break
                }
                at += 1
            }
        }
        if (at >= end) return null
        return String(bytes, at, end - at, charset)
            .trim('\u0000', '\uFEFF', ' ', '\n', '\r')
            .takeIf { it.isNotBlank() }
    }

    private fun charsetFor(encoding: Int): Charset? = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16   // with a byte-order mark, which the decoder consumes
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> null
    }

    /**
     * The tag's own length, and 2.4's frame lengths, use seven bits per byte.
     *
     * The eighth is always zero so the length can never contain a byte that looks like the
     * start of an MPEG frame. Reading it as a plain integer overstates the size for any tag
     * past 2MB, which then runs the frame walk off the end of the words.
     */
    private fun syncSafe(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0x7F) shl 21) or
            ((bytes[at + 1].toInt() and 0x7F) shl 14) or
            ((bytes[at + 2].toInt() and 0x7F) shl 7) or
            (bytes[at + 3].toInt() and 0x7F)

    private fun plain(bytes: ByteArray, at: Int, length: Int): Int {
        var value = 0
        for (i in 0 until length) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    private const val HEADER = 10

    /**
     * How much of a file to read looking for a tag.
     *
     * Tags carrying embedded artwork run to a megabyte; lyrics sit among the text frames, which
     * are usually before it, but not always. This is enough for the overwhelming majority and
     * bounded enough to read for every track at import without it being felt.
     */
    const val SCAN_BYTES = 1_500_000
}
