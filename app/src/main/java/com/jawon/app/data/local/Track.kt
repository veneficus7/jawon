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
    val fileUri: String,
    // captured at import, drives the quality readout
    val mimeType: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val bitrateBps: Int
)
