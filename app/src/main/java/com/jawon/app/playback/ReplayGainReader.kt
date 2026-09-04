package com.jawon.app.playback

import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.common.util.UnstableApi
import com.jawon.app.data.prefs.AppPreferences
import java.util.Locale

/**
 * Pulls ReplayGain out of whatever the current track is tagged with, and levels to it.
 *
 * **Media3 has already done the parsing.** The tags live in different places per format — an
 * ID3 `TXXX` frame in an MP3, a Vorbis comment in a FLAC or Ogg — and the extractors surface
 * both as `Metadata` entries. Reading the files again to get at them would mean writing tag
 * parsers for three container formats to recover something the decoder has already handed over.
 *
 * `MediaMetadataRetriever` is the obvious-looking alternative and cannot do this: it exposes a
 * fixed set of keys — title, artist, duration — and no arbitrary user-defined frame, which is
 * exactly what ReplayGain is.
 *
 * ## Where the metadata actually arrives, which is not where this class first looked
 *
 * This originally hung entirely off `onMetadata`, and **that callback never fires for the
 * formats Jawon imports**, so the whole class was inert in production — every gain permanently
 * null, [ReplayGain.volumeFor] permanently returning `1f`. Its unit tests passed throughout,
 * because they test the arithmetic and the arithmetic was never wrong.
 *
 * `onMetadata` is dispatched only from a `MetadataRenderer`, i.e. from a genuine
 * `TRACK_TYPE_METADATA` track — an ID3 stream inside an MPEG-TS, say. `Mp3Extractor` and
 * `FlacExtractor` emit a single audio track and hang the file-header tags off
 * `Format.metadata` instead, which surfaces through [onTracksChanged] and nowhere else.
 *
 * Confirmed on a device rather than reasoned about: playing an MP3 logged
 * `tracksChanged … metadataEntries=4` carrying `TPE1`, `TIT2`, `TSSE` and `APIC`, and
 * `onMetadata` did not fire once. Both callbacks are handled now — `onTracksChanged` is the one
 * that works here, and `onMetadata` is kept because it is the correct source for the streaming
 * containers that do carry a metadata track, and costs nothing when it stays silent.
 *
 * Metadata arrives *after* a track starts, sometimes several hundred milliseconds after, so
 * levelling is applied when it lands rather than at the transition. The alternative would be
 * holding playback until the tags turn up, which trades an audible level step for an audible
 * delay on every single track, including the ones with no tags at all.
 */
@UnstableApi
class ReplayGainReader(
    private val volume: PlayerVolume,
    private val preferences: AppPreferences
) : Player.Listener {

    private var albumGainDb: Float? = null
    private var trackGainDb: Float? = null

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        // The *track's* tags have not arrived yet, and the last one's must not be left applied
        // to it — a quiet track following a loud one would otherwise play at the loud one's
        // correction until its own metadata turned up.
        trackGainDb = null

        // **Album gain deliberately survives an ordinary transition.** It is by definition one
        // value for the whole record, so clearing it at every boundary would make each track
        // snap to 0 dB and then step back down a moment later when the tag re-landed — an
        // audible pump between every track of an album, caused entirely by this class.
        //
        // A queue in Jawon is always exactly one album (`PlaybackRepository` calls
        // `setMediaItems` with that album's tracks), so a *new* album is always a playlist
        // change and never an auto-advance. That makes the reason code a reliable proxy for
        // "different record" without needing an album id on the MediaItem.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            albumGainDb = null
        }
        apply()
    }

    /**
     * Where the tags actually turn up for a file-based library — see the class KDoc.
     *
     * Every group is walked rather than only the selected audio track: which group carries the
     * file-header metadata is an extractor detail, and reading a gain from a track that is not
     * playing is harmless when the queue is one file at a time.
     */
    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
        var changed = false
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                if (readInto(group.getTrackFormat(i).metadata)) changed = true
            }
        }
        if (changed) apply()
    }

    override fun onMetadata(metadata: Metadata) {
        if (readInto(metadata)) apply()
    }

    /**
     * Pulls any ReplayGain out of one [Metadata] bag, returning whether anything landed.
     *
     * Shared by both callbacks because the entry types are identical whichever route the bag
     * arrived by — only the delivery differs.
     */
    private fun readInto(metadata: Metadata?): Boolean {
        if (metadata == null) return false
        var changed = false
        for (i in 0 until metadata.length()) {
            val (key, value) = when (val entry = metadata.get(i)) {
                // TXXX: the frame id is always "TXXX" and the name is in `description`.
                is TextInformationFrame ->
                    entry.description.orEmpty() to entry.values.firstOrNull()
                is VorbisComment -> entry.key to entry.value
                else -> continue
            }
            val db = ReplayGain.parseDb(value) ?: continue
            when (key.uppercase(Locale.ROOT)) {
                ReplayGain.KEY_ALBUM_GAIN -> { albumGainDb = db; changed = true }
                ReplayGain.KEY_TRACK_GAIN -> { trackGainDb = db; changed = true }
            }
        }
        return changed
    }

    /** Re-level without waiting for the next track, for when the setting itself changes. */
    fun apply() {
        val mode = ReplayGain.Mode.fromName(preferences.replayGainMode.value)
        volume.setGain(ReplayGain.volumeFor(mode, albumGainDb, trackGainDb))
    }
}
