package com.jawon.app.data.cd

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Turns a disc into a folder of WAV files.
 *
 * **Deliberately thin, because none of it can be tested.** Every decision this could have made —
 * which tracks, what to call them, how big a read, how far through it is — lives in [CdRip],
 * which is pure and covered. What is left here is a loop over sector ranges and a stream to write
 * them into, so the untestable surface is as small as the feature allows. That is the same
 * discipline `CdDrive` follows for the SCSI transfers, and for the same reason: there is still no
 * drive to check any of it against.
 *
 * ## Why it writes into a folder the user chose
 *
 * A CD is about 600MB as WAV, which is not something to put in app-private storage on a phone —
 * and more to the point it would be the *only* audio in this app the user does not own a path to.
 * `ENGINEERING.md` says every file Jawon touches arrives through SAF or MediaStore; a rip writes back
 * through the same door. It also means the import afterwards is not a new code path at all: the
 * folder goes to `AddAlbumViewModel.onFolderPicked` exactly as if it had been picked by hand.
 */
@Singleton
class CdRipper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** How a rip ended. */
    sealed interface Result {
        /** The folder holding the WAVs, ready to hand to the importer. */
        data class Done(val folder: Uri, val tracks: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /** Where a rip has got to, for the screen to draw. */
    data class Progress(
        val fraction: Float,
        val trackNumber: Int,
        val trackTitle: String?,
        val tracksDone: Int,
        val tracksTotal: Int
    )

    /**
     * Reads the disc into [destination], one file per audio track.
     *
     * Cancellable between chunks rather than only between tracks — a track is minutes long and a
     * rip somebody has changed their mind about should stop now, not at the end of the song.
     * A cancelled rip leaves its part-written files behind on purpose: deleting them is a second
     * thing that can fail, and a folder of obvious rubbish the user can delete is better than a
     * cleanup path that runs while the drive is being unplugged.
     */
    suspend fun rip(
        drive: CdDrive,
        plan: List<CdRip.RipTrack>,
        destination: Uri,
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (plan.isEmpty()) return@withContext Result.Failed("This disc has no audio tracks.")
        val folder = DocumentFile.fromTreeUri(context, destination)
            ?: return@withContext Result.Failed("That folder cannot be written to.")

        val done = mutableListOf<CdRip.RipTrack>()
        for (track in plan) {
            coroutineContext.ensureActive()
            val file = folder.createFile(WAV_MIME, track.fileName)
                ?: return@withContext Result.Failed("Could not create ${track.fileName}.")

            val stream = runCatching { context.contentResolver.openOutputStream(file.uri) }
                .getOrNull()
                ?: return@withContext Result.Failed("Could not write ${track.fileName}.")

            val outcome = stream.use { out ->
                writeTrack(drive, track, out, plan, done, onProgress)
            }
            if (outcome != null) return@withContext outcome
            done += track
        }

        onProgress(
            Progress(1f, plan.last().number, plan.last().title, plan.size, plan.size)
        )
        Result.Done(destination, plan.size)
    }

    /** Returns null on success, or the failure that stopped it. */
    private suspend fun writeTrack(
        drive: CdDrive,
        track: CdRip.RipTrack,
        out: OutputStream,
        plan: List<CdRip.RipTrack>,
        done: List<CdRip.RipTrack>,
        onProgress: (Progress) -> Unit
    ): Result? {
        // The header first, and sized from the sector count rather than from what is actually
        // read. A WAV whose header disagrees with its payload plays as silence or as noise past
        // the end, and the length is known exactly before a single byte is transferred.
        runCatching { WavWriter.writeHeader(out, track.sectors) }
            .onFailure { return Result.Failed("Could not write ${track.fileName}.") }

        var sectorsRead = 0
        for (chunk in CdRip.chunks(track.startLba, track.sectors)) {
            coroutineContext.ensureActive()
            val audio = drive.readAudio(chunk[0], chunk[1])
                ?: return Result.Failed(
                    "The drive stopped responding on track ${track.number}. " +
                        "A scratched disc or a drive the phone cannot power will both do this."
                )
            runCatching { out.write(audio) }
                .onFailure { return Result.Failed("Ran out of room writing ${track.fileName}.") }

            sectorsRead += chunk[1]
            onProgress(
                Progress(
                    fraction = CdRip.progress(done, track, sectorsRead, plan),
                    trackNumber = track.number,
                    trackTitle = track.title,
                    tracksDone = done.size,
                    tracksTotal = plan.size
                )
            )
        }
        return null
    }

    /**
     * Asks MusicBrainz what disc this is.
     *
     * Best-effort by design: the rip works without it, and an unrecognised disc is the normal
     * case for anything home-made. So every failure — no network, a captive portal, a 404, a
     * release with no tracklist — returns null and the tracks come out numbered.
     *
     * What leaves the device is the disc id, and nothing else. That is a fingerprint of the
     * *pressing* rather than of the listener: it is computed from track offsets alone, it is the
     * same string for every copy of that CD in the world, and it says nothing about who is
     * holding one. Same shape as the cover-art lookup, which sends a title and an artist.
     */
    suspend fun identify(discId: String): CdRip.DiscRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(CdRip.lookupUrl(discId)).openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    // MusicBrainz requires a client to identify itself and rate-limits those
                    // that do not. Same shape as the cover-art lookup's, which is the app's
                    // other MusicBrainz caller — its own copy is private to that class.
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                CdRip.parseDiscLookup(
                    connection.inputStream.bufferedReader().use { it.readText() },
                    discId
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { if (it is CancellationException) throw it else null }
    }

    private companion object {
        const val WAV_MIME = "audio/wav"
        const val TIMEOUT_MS = 10_000

        /** Named, with a way to reach the project — what MusicBrainz asks of every client. */
        const val USER_AGENT = "Jawon/0.5 ( https://github.com/veneficus7/javon )"
    }
}
