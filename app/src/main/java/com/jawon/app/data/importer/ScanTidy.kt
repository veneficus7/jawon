package com.jawon.app.data.importer

import java.util.Locale

/**
 * **What a phone scan should and should not offer as a record** (0.7.4).
 *
 * The audit, on the owner's phone: a `Download` folder offered as an album, voice notes in the
 * list, and one-track "albums" crowding out the real records. All three come from the same
 * place — MediaStore indexes every audio file on the phone, and names an untagged file's album
 * after the folder it sits in — and none of them is a record anyone owns.
 *
 * Pure, so the rules are tested without a phone; [AlbumImportRepository.scanDevice] asks.
 */
object ScanTidy {

    /**
     * Folders that hold the phone's own recordings and messages, never records: a file anywhere
     * under one is not offered. Matched against each segment of the file's path, ignoring case.
     */
    private val NEVER_MUSIC = setOf(
        "recordings", "recording", "voice recorder", "voicerecorder", "voice recordings",
        "sound recorder", "soundrecorder", "call", "calls", "call recordings", "callrecordings",
        "voice notes", "whatsapp voice notes", "voice messages", "telegram voice", "audio notes",
        "ringtones", "notifications", "alarms", "ui", "sounds"
    )

    /**
     * Folders whose name says nothing about what is in them. MediaStore files an untagged song as
     * an album *named after its folder*, so a handful of songs downloaded into `Download` arrive
     * as one "album" called Download. In these folders each untagged file is offered on its own.
     */
    private val GENERIC_FOLDERS = setOf(
        "download", "downloads", "music", "audio", "media", "documents", "document", "files",
        "bluetooth", "share", "shared", "received", "telegram audio", "whatsapp audio",
        "telegram", "whatsapp", "sdcard", "0", "emulated", "storage", "misc", "other", "new folder"
    )

    /** Shorter than this, a file with no album of its own is a sound, a jingle or a snippet. */
    const val LOOSE_MIN_MS = 30_000L

    /** At most this many tracks and a record goes under Singles rather than among the albums. */
    const val SINGLE_MAX_TRACKS = 2

    private fun segments(path: String?): List<String> =
        path.orEmpty().replace('\\', '/').split('/')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

    /**
     * Whether a file is one of the phone's own recordings or sounds rather than music: under a
     * recordings or messages folder, or flagged a recording by Android (API 31+).
     */
    fun isNeverMusic(relativePath: String?, isRecording: Boolean): Boolean =
        isRecording || segments(relativePath).any { it in NEVER_MUSIC }

    /**
     * Whether a file's album name is only its folder's, and that folder says nothing: the album
     * MediaStore invented for an untagged file in `Download`, `Music` and the like.
     *
     * A folder named for a record — `Radiohead - In Rainbows` — is not generic, so untagged rips
     * in their own folder still come in together, as they should.
     */
    fun isFolderAlbum(album: String?, relativePath: String?): Boolean {
        val folder = segments(relativePath).lastOrNull() ?: return album.isNullOrBlank()
        val name = album?.trim()?.lowercase(Locale.ROOT)
        if (name.isNullOrEmpty() || name == "<unknown>") return true
        return name == folder && folder in GENERIC_FOLDERS
    }

    /** A record of [trackCount] tracks counts as a single. */
    fun isSingle(trackCount: Int): Boolean = trackCount <= SINGLE_MAX_TRACKS

    /**
     * The found records in the order the list shows them: the albums first, by title, then the
     * singles, by title. Returns the ordered list and where the singles begin.
     */
    fun <T> albumsThenSingles(found: List<T>, tracks: (T) -> Int, title: (T) -> String): Pair<List<T>, Int> {
        val (singles, albums) = found.partition { isSingle(tracks(it)) }
        val byTitle = compareBy<T> { title(it).lowercase(Locale.ROOT) }
        val ordered = albums.sortedWith(byTitle) + singles.sortedWith(byTitle)
        return ordered to albums.size
    }
}
