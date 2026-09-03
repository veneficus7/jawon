package com.jawon.app.data.lyrics

/** One line of a song, and when it is sung. [timeMs] is [UNTIMED] for unsynced lyrics. */
data class LyricLine(val timeMs: Long, val text: String) {
    companion object {
        const val UNTIMED = -1L
    }
}

/** Where a set of lyrics came from. Shown to the user, because it explains what to expect. */
/**
 * Where a lookup has got to.
 *
 * **"Not found yet" and "there are none" are different answers and were being given the same
 * words.** The third tier of the search is a network request to LRCLIB, so opening the page for a
 * track the device cannot answer meant a second or two of the panel stating flatly that the track
 * has no lyrics — and then the lyrics arriving, which makes the app look like it was lying rather
 * than working. The page has to be able to say it is still looking.
 */
sealed interface LyricsState {
    /** No track, or nothing to look for. */
    data object Idle : LyricsState

    /** A lookup is running. Only ever reached when the file itself did not answer. */
    data object Looking : LyricsState

    /** Everywhere has been asked and nobody had them. */
    data object None : LyricsState

    data class Ready(val lyrics: Lyrics) : LyricsState
}

enum class LyricSource {
    /** The file's own tag. Always there, never times anything. */
    EMBEDDED,

    /** A `.lrc` beside the audio. Usually timed, and the only offline way to get timing. */
    SIDECAR,

    /** LRCLIB. The only one that leaves the device, and only when the other two find nothing. */
    ONLINE
}

/**
 * A song's words.
 *
 * [synced] is the distinction that matters to the UI: timed lyrics scroll themselves and
 * highlight the current line, untimed ones are a page you read. Both are worth having, and the
 * common case offline is untimed — see [LyricSource].
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: LyricSource
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * The index of the line being sung at [positionMs], or −1 before the first one.
     *
     * Binary search rather than a scan: this is called on every position tick while the lyrics
     * page is open, and a long song is a few hundred lines.
     */
    fun lineAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
