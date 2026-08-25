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
            year = tags.year,
            durationMs = tags.durationMs,
            mimeType = technical.mimeType,
            sampleRateHz = technical.sampleRateHz,
            bitDepth = technical.bitDepth,
            bitrateBps = technical.bitrateBps
        )
    }

    private data class Tags(
        val title: String?,
        val album: String?,
        val artist: String?,
        val trackNumber: Int?,
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
