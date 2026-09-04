package com.jawon.app.ui.common

import com.jawon.app.data.local.Track
import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.US, minutes, seconds)
}

private val LOSSLESS_MIME_TYPES = setOf(
    "audio/flac", "audio/x-flac", "audio/alac", "audio/wav", "audio/x-wav",
    "audio/x-ms-wav", "audio/vnd.wave", "audio/raw"
)

private fun codecLabel(mimeType: String): String = when (mimeType) {
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/alac" -> "ALAC"
    "audio/wav", "audio/x-wav", "audio/x-ms-wav", "audio/vnd.wave" -> "WAV"
    "audio/mpeg" -> "MP3"
    "audio/mp4a-latm", "audio/mp4" -> "AAC"
    "audio/opus" -> "OPUS"
    "audio/ogg", "audio/vorbis" -> "OGG"
    else -> mimeType.substringAfter('/').uppercase(Locale.ROOT)
}

/** e.g. `FLAC 16-BIT / 44.1 kHz` — what the file actually is. */
fun qualityReadoutSource(track: Track): String {
    val bitDepthPart = track.bitDepth?.let { "$it-BIT / " } ?: ""
    val rateKhz = track.sampleRateHz / 1000f
    return "${codecLabel(track.mimeType)} $bitDepthPart${"%.1f".format(Locale.US, rateKhz)} kHz"
}

/**
 * e.g. `LOSSLESS · WIRED → OUTPUT 48 kHz` — what is actually reaching your ears.
 *
 * PLAN.md §7: Android resamples nearly everything to the device's output rate, so reporting
 * the source rate alone would be a lie, and this app's audience is exactly the audience that
 * would notice. The output leg is only shown when it genuinely differs.
 */
fun qualityReadoutOutput(track: Track, routeLabel: String, outputSampleRateHz: Int): String {
    val losslessTag = if (track.mimeType in LOSSLESS_MIME_TYPES) "LOSSLESS" else "LOSSY"
    // Zero means the rate could not be established — over Bluetooth it genuinely cannot be,
    // since A2DP re-encodes to the codec's own rate. Printing nothing beats printing a guess.
    val resampled = outputSampleRateHz > 0 && outputSampleRateHz != track.sampleRateHz
    val outputPart =
        if (resampled) " → OUTPUT ${"%.0f".format(Locale.US, outputSampleRateHz / 1000f)} kHz" else ""
    val route = if (routeLabel.isBlank()) "" else " · $routeLabel"
    return "$losslessTag$route$outputPart"
}
