package com.jawon.app.data.importer

/**
 * A parsed `.cue` sheet: what a whole-disc rip carries instead of track boundaries.
 *
 * **Why this exists at all.** A CD ripped as one file plus a `.cue` is the normal shape for a
 * live record, a DJ mix, a classical work, or anything the rip wanted to keep continuous —
 * and it is the shape Jawon is currently blind to. [AudioScanner] matches audio extensions, so
 * the `.cue` is invisible, and the album imports as a single track containing the entire disc.
 *
 * Pure, and deliberately so. Everything here is text in and data out — no Android, no files,
 * no I/O — which is what makes the awkward parts testable: the frame arithmetic, the pregap,
 * and the several ways real sheets in the wild are malformed.
 */
data class CueSheet(
    /** Album artist, from the top-level `PERFORMER`. */
    val performer: String?,
    /** Album title, from the top-level `TITLE`. */
    val title: String?,
    val files: List<CueFile>
) {
    /** Every track across every file, which is the whole disc in order. */
    val tracks: List<CueTrack> get() = files.flatMap { it.tracks }
}

/**
 * One audio file named by the sheet, and the tracks that live inside it.
 *
 * Usually exactly one — that is the whole-disc rip this feature is for. A sheet with several
 * is a rip that was split per track and kept its cue anyway, which is legal and worth
 * surviving even though it needs no clipping.
 */
data class CueFile(
    val name: String,
    val tracks: List<CueTrack>
)

data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    /**
     * Where the track's audio starts, from `INDEX 01`.
     *
     * This is the boundary a listener means by "track 4 starts here", and the one a tracklist
     * should show.
     */
    val startMs: Long,
    /**
     * Where `INDEX 00` sat, when the sheet declares one — the pregap.
     *
     * Kept rather than folded into [startMs] because the two say different things and losing
     * the distinction loses real audio. The pregap is the run-out of the *previous* track: the
     * applause between two live songs, the silence a CD player counts down through, and
     * occasionally a hidden track sitting in front of track 1. A design that starts each track
     * at its `INDEX 01` and ends it at the next one's silently drops every one of those.
     */
    val pregapStartMs: Long?
)

/**
 * Reads the text of a `.cue` file.
 *
 * Returns null when the text is not a cue sheet at all. A sheet that is merely *odd* — unknown
 * commands, missing titles, a track with no index — parses as far as it can rather than
 * failing: these files are written by a dozen different rippers and hand-edited by people, and
 * refusing a whole album because one line is strange would be the wrong trade.
 */
object CueParser {

    /**
     * CD frames per second. The unit `INDEX` is written in, and the reason cue times cannot
     * simply be read as `mm:ss.cc` — the last field is 0..74, not hundredths.
     */
    const val FRAMES_PER_SECOND = 75

    fun parse(text: String): CueSheet? {
        var albumPerformer: String? = null
        var albumTitle: String? = null

        val files = mutableListOf<CueFile>()
        var currentFileName: String? = null
        var currentTracks = mutableListOf<CueTrack>()

        // The track being assembled. A track's fields arrive on the lines *after* its TRACK
        // command, so it can only be completed when the next TRACK, the next FILE or the end
        // of the sheet arrives.
        var number: Int? = null
        var title: String? = null
        var performer: String? = null
        var index00: Long? = null
        var index01: Long? = null
        var sawTrack = false

        fun flushTrack() {
            val n = number ?: return
            // A track with no INDEX 01 falls back to its pregap, and failing that to zero.
            // Malformed, but a sheet that names a track is asserting the track exists.
            currentTracks += CueTrack(
                number = n,
                title = title,
                performer = performer,
                startMs = index01 ?: index00 ?: 0L,
                pregapStartMs = index00
            )
            number = null; title = null; performer = null; index00 = null; index01 = null
        }

        fun flushFile() {
            flushTrack()
            val name = currentFileName ?: return
            files += CueFile(name, currentTracks.toList())
            currentTracks = mutableListOf()
            currentFileName = null
        }

        for (raw in text.lineSequence()) {
            // A BOM survives the decode and would otherwise make the very first command
            // unrecognisable — which, since that is usually PERFORMER or FILE, loses the album.
            val line = raw.removePrefix("﻿").trim()
            if (line.isEmpty()) continue

            val command = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()

            when (command) {
                // Comments, and where several rippers hide the date and genre. Skipped
                // explicitly rather than by falling through, so a `REM TITLE ...` inside a
                // comment can never be mistaken for the album's own title.
                "REM" -> continue

                "PERFORMER" -> if (sawTrack) performer = unquote(rest) else albumPerformer = unquote(rest)
                "TITLE" -> if (sawTrack) title = unquote(rest) else albumTitle = unquote(rest)

                "FILE" -> {
                    flushFile()
                    // `FILE "name.flac" WAVE` — the trailing type is not part of the name, and
                    // an unquoted name with spaces is common enough to handle.
                    currentFileName = if (rest.startsWith('"')) {
                        rest.substring(1).substringBefore('"')
                    } else {
                        rest.substringBeforeLast(' ').ifBlank { rest }
                    }
                    sawTrack = false
                }

                "TRACK" -> {
                    flushTrack()
                    sawTrack = true
                    number = rest.substringBefore(' ').trim().toIntOrNull()
                }

                "INDEX" -> {
                    val which = rest.substringBefore(' ').trim().toIntOrNull()
                    val time = parseTime(rest.substringAfter(' ', "").trim())
                    if (time != null) when (which) {
                        0 -> index00 = time
                        1 -> index01 = time
                    }
                }
            }
        }
        flushFile()

        // Nothing recognisable. A stray text file, or a sheet for something that is not audio.
        if (files.isEmpty() || files.all { it.tracks.isEmpty() }) return null

        return CueSheet(
            performer = albumPerformer,
            title = albumTitle,
            files = files
        )
    }

    /**
     * `mm:ss:ff` to milliseconds, where `ff` is CD frames at 75 per second.
     *
     * **The last field is the trap.** It looks like hundredths and is not: `00:02:37` is two
     * seconds and thirty-seven *frames*, which is 2493ms, not 2370. Reading it as hundredths
     * puts every track boundary on a long album progressively further out of place.
     *
     * Minutes are not capped at 59 — a cue sheet counts from the start of the disc, so a
     * seventy-minute record's last track legitimately begins at `68:14:22`.
     */
    fun parseTime(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].trim().toLongOrNull() ?: return null
        val seconds = parts[1].trim().toLongOrNull() ?: return null
        val frames = parts[2].trim().toLongOrNull() ?: return null
        if (minutes < 0 || seconds < 0 || frames < 0) return null
        return (minutes * 60 + seconds) * 1000L + frames * 1000L / FRAMES_PER_SECOND
    }

    /** Strips the quotes rippers put round every string, and tolerates the ones that do not. */
    private fun unquote(value: String): String? = value
        .trim()
        .removeSurrounding("\"")
        .trim()
        .takeIf { it.isNotEmpty() }
}
