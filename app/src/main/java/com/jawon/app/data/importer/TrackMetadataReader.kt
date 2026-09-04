package com.jawon.app.data.importer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads tag + technical metadata for one file. Tags come from
 * [MediaMetadataRetriever]; sample rate/bitrate/channel count/codec mime come
 * from [MediaExtractor]'s [MediaFormat] since minSdk 29 predates
 * MediaMetadataRetriever.METADATA_KEY_SAMPLERATE (API 31). Bit depth is only
 * ever populated for raw PCM containers (e.g. WAV) — the platform extractor
 * doesn't expose it for compressed lossless streams, and guessing would be
 * exactly the "quality readout lie" DESIGN.md rules out.
 */
class TrackMetadataReader {

    suspend fun read(context: Context, file: DocumentFile): TrackMetadata = withContext(Dispatchers.IO) {
        val uri = file.uri
        val tags = readTags(context, uri)
        val technical = readTechnical(context, uri)
        TrackMetadata(
            fileUri = uri,
            displayName = file.name ?: uri.lastPathSegment.orEmpty(),
            title = tags.title ?: file.name?.substringBeforeLast('.') ?: "Untitled",
            album = tags.album,
            artist = tags.artist,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            year = tags.year,
            durationMs = tags.durationMs,
            mimeType = technical.mimeType,
            sampleRateHz = technical.sampleRateHz,
            bitDepth = technical.bitDepth,
            bitrateBps = technical.bitrateBps
        )
    }

    /**
     * Fills in the codec details a [TrackMetadata] is missing — what the quality readout needs.
     *
     * The device scan gets its tags from MediaStore, which is an index and answers instantly
     * for a whole library, but it does not record sample rate or bit depth. Reading those
     * means opening the file, so the scan leaves them at zero and this fills them in later —
     * at import, and only for the albums actually chosen. Someone scanning two thousand
     * tracks and importing five should pay for five.
     *
     * Returns the metadata unchanged if it already has a sample rate, so the folder importer
     * (which reads everything up front) passes through untouched, and so a track whose codec
     * genuinely cannot be read is not retried forever.
     */
    suspend fun withTechnical(context: Context, metadata: TrackMetadata): TrackMetadata =
        withContext(Dispatchers.IO) {
            if (metadata.sampleRateHz > 0) return@withContext metadata
            runCatching { readTechnical(context, metadata.fileUri) }.getOrNull()
                ?.let {
                    metadata.copy(
                        mimeType = it.mimeType,
                        sampleRateHz = it.sampleRateHz,
                        bitDepth = it.bitDepth,
                        bitrateBps = it.bitrateBps
                    )
                }
                ?: metadata
        }

    /**
     * The album art embedded in a file's tags, or null if it has none.
     *
     * Most ripped or downloaded albums carry their cover inside every track, and Jawon was
     * ignoring it and asking the user to go and find the image themselves — the single
     * biggest piece of busywork in adding an album. `MediaMetadataRetriever` was already open
     * here reading the title and artist; this is the same call on the same object.
     *
     * Deliberately not part of [TrackMetadata]. That is a data class held in a list, one per
     * track, and carrying a few hundred KB of JPEG per entry would mean an album's worth of
     * duplicated bitmaps in memory and an `equals` that compares image bytes.
     */
    suspend fun embeddedPicture(context: Context, uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } catch (_: Exception) {
                // A file whose art cannot be read is not an import failure — the album still
                // imports, it just does not get a free cover.
                null
            } finally {
                retriever.release()
            }
        }

    private data class Tags(
        val title: String?,
        val album: String?,
        val artist: String?,
        val trackNumber: Int?,
        val discNumber: Int?,
        val year: Int?,
        val durationMs: Long
    )

    private fun readTags(context: Context, uri: Uri): Tags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull(),
                // The other half of the same question, and the folder route's answer to what
                // the scan recovers from MediaStore's packed TRACK column. Written "2/3" as
                // often as "2", hence the same split as above. See Track.discNumber.
                discNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                year = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                    ?.take(4)?.toIntOrNull(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            )
        } finally {
            retriever.release()
        }
    }

    private data class Technical(
        val mimeType: String,
        val sampleRateHz: Int,
        val bitDepth: Int?,
        val bitrateBps: Int
    )

    private fun readTechnical(context: Context, uri: Uri): Technical {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException("No audio track in $uri")
            Technical(
                mimeType = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown",
                sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
                bitDepth = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)?.let(::pcmEncodingToBitDepth),
                bitrateBps = format.intOrNull(MediaFormat.KEY_BIT_RATE) ?: 0
            )
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun pcmEncodingToBitDepth(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
}
