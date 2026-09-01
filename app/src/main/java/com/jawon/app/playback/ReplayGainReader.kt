package com.jawon.app.playback

import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.common.util.UnstableApi
import com.jawon.app.data.prefs.AppPreferences

/**
 * Pulls ReplayGain out of whatever the current track is tagged with, and levels to it.
 *
 * **Media3 has already done the parsing.** The tags live in different places per format — an
 * ID3 `TXXX` frame in an MP3, a Vorbis comment in a FLAC or Ogg — and the extractors surface
 * both through `Player.Listener.onMetadata`. Reading the files again to get at them would mean
 * writing tag parsers for three container formats to recover something the decoder has already
 * handed over.
 *
 * `MediaMetadataRetriever` is the obvious-looking alternative and cannot do this: it exposes a
 * fixed set of keys — title, artist, duration — and no arbitrary user-defined frame, which is
 * exactly what ReplayGain is.
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
        // A new track's tags have not arrived yet, and last track's must not be left applied
        // to it — a quiet record following a loud one would otherwise play at the loud one's
        // correction until its own metadata turned up.
        albumGainDb = null
        trackGainDb = null
        apply()
    }

    override fun onMetadata(metadata: Metadata) {
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
            when (key.uppercase()) {
                ReplayGain.KEY_ALBUM_GAIN -> { albumGainDb = db; changed = true }
                ReplayGain.KEY_TRACK_GAIN -> { trackGainDb = db; changed = true }
            }
        }
        if (changed) apply()
    }

    /** Re-level without waiting for the next track, for when the setting itself changes. */
    fun apply() {
        val mode = ReplayGain.Mode.fromName(preferences.replayGainMode.value)
        volume.setGain(ReplayGain.volumeFor(mode, albumGainDb, trackGainDb))
    }
}
