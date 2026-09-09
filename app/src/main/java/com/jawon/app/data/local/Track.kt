package com.jawon.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Track(
    @PrimaryKey val id: String,
    val albumId: String,
    val trackNumber: Int,

    /**
     * Which disc of a set this track came from, or null for a single-disc album.
     *
     * **Added because losing it was silently corrupting every box set.** The device scan reads
     * `MediaStore.Audio.Media.TRACK`, which packs the disc into the thousands place — 1006 is
     * disc 1, track 6 — and the importer used to recover the track by taking the value modulo
     * 1000 and throwing the disc away. Since a scanned multi-disc album arrives under one
     * `ALBUM_ID` and becomes one Jawon album, that left disc 1 track 6 and disc 2 track 6 both
     * numbered 6: a collision in the same album, with the tracklist order between them decided
     * by nothing at all.
     *
     * Null rather than 1 for a single disc, and the distinction earns its keep on the case: a
     * record that never had a disc number should not have one printed on it, and "not a set"
     * is a different fact from "disc one of one".
     */
    val discNumber: Int? = null,
    val title: String,
    /**
     * This track's own credited artist, where the file carried one.
     *
     * Separate from the album's artist because on a compilation they are not the same thing —
     * a various-artists record, a guest feature, a soundtrack — and the deck should say who is
     * actually singing rather than who the shelf is filed under.
     *
     * Nullable, and that is a real state rather than a gap to paper over: plenty of files carry
     * no per-track credit, and every track imported before this column existed has none. The
     * deck falls back to the album's artist in that case.
     */
    val artist: String? = null,
    /**
     * Words fetched from LRCLIB, so a track is looked up at most once.
     *
     * Null means "never looked up", not "has none" — a track that genuinely has no lyrics is
     * stored as an empty string, so it is not asked for again every time the page is opened.
     * Embedded tags and `.lrc` sidecars are not cached here: they are already on the device.
     */
    val fetchedLyrics: String? = null,
    val durationMs: Long,
    /**
     * Where the audio is.
     *
     * For a track on this phone, a content URI, and the only thing needed to play it. For a
     * track on a music server this is **empty**, and that is deliberate rather than lazy: a
     * Subsonic stream URL carries a one-shot authentication salt, so a URL stored here would
     * be stale by the time anything read it, and storing credentials in a hundred rows would
     * mean rewriting all of them when the password changes. [remoteId] is stored instead and
     * the URL is built at the moment of playing — see `PlaybackRepository`.
     */
    val fileUri: String,

    /**
     * This track's id on the music server it came from, or null for a local file.
     *
     * Paired with `Album.sourceId`: the album knows *which* server, the track knows *what* to
     * ask it for. Also the stable cache key — a stream URL changes on every request, so the
     * cache has to key off this instead or it would store the same track under a new name
     * every time and never hit.
     */
    val remoteId: String? = null,

    // captured at import, drives the quality readout
    val mimeType: String,
    /**
     * Zero where it is not known.
     *
     * Plain Subsonic does not report a sample rate, so a streamed track from a non-OpenSubsonic
     * server genuinely has no answer. Left as 0 and rendered as a blank rather than invented,
     * because the deck prints this and a made-up number is worse than an absent one.
     */
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val bitrateBps: Int
)
