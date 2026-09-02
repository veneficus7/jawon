package com.jawon.app.data.lyrics

/**
 * Reads the `.lrc` files that sit beside audio, which is how timed lyrics actually travel.
 *
 * **Why this format and not the tag.** ID3 has a synchronised-lyrics frame, `SYLT`, and almost
 * nothing writes or reads it. `.lrc` is what every tool in the wild produces — it is what
 * LRCLIB serves and what LRCGET writes — so a sidecar is the only offline route to lyrics that
 * scroll in time.
 *
 * Pure, and tested without a device: the awkward parts of this format are all in the text.
 */
object LrcParser {

    /**
     * Parse [text] into lyrics, or null if it contains nothing usable.
     *
     * Returns untimed lyrics rather than nothing when a file has words but no timestamps — some
     * `.lrc` files in the wild are plain text with the wrong extension, and showing them is
     * better than pretending the song has no words.
     */
    fun parse(text: String): Lyrics? {
        val timed = mutableListOf<LyricLine>()
        val untimed = mutableListOf<String>()
        var offsetMs = 0L

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // `[offset:+250]` shifts every timestamp, and is the one metadata tag that changes
            // what the listener sees. Positive means the words come *earlier*, which is the
            // opposite of what the sign looks like — the tag describes the file's error, not the
            // correction. Getting this backwards is a lyric sheet that drifts the wrong way.
            val offset = OFFSET.matchEntire(line)
            if (offset != null) {
                offsetMs = -(offset.groupValues[1].trim().toLongOrNull() ?: 0L)
                continue
            }
            // Other metadata — title, artist, album, the tool that made it. Not words.
            if (METADATA.matches(line)) continue

            val stamps = STAMP.findAll(line).toList()
            if (stamps.isEmpty()) {
                untimed += line
                continue
            }

            // Everything after the last timestamp is the words, and a line may carry several
            // timestamps when the same phrase repeats.
            val words = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val at = millisOf(stamp) ?: continue
                timed += LyricLine((at + offsetMs).coerceAtLeast(0L), words)
            }
        }

        if (timed.isNotEmpty()) {
            // Sorted because repeated phrases are written where they first appear, not in time
            // order, and the whole UI assumes ascending stamps.
            return Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = LyricSource.SIDECAR)
        }
        if (untimed.isNotEmpty()) {
            return Lyrics(
                untimed.map { LyricLine(LyricLine.UNTIMED, it) },
                synced = false,
                source = LyricSource.SIDECAR
            )
        }
        return null
    }

    /** `[mm:ss]`, `[mm:ss.xx]` or `[mm:ss.xxx]`, in milliseconds. */
    private fun millisOf(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        // Two digits is hundredths, three is milliseconds — both are written in the wild, and
        // reading `.50` as half a millisecond would put every line half a second early.
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100
            2 -> (fraction.toLongOrNull() ?: 0L) * 10
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }

    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val METADATA = Regex("""\[[a-z]{2,}:.*]""", RegexOption.IGNORE_CASE)
}
