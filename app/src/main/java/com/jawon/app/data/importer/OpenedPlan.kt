package com.jawon.app.data.importer

import java.util.Locale

/**
 * What to do with each thing inside something another app handed over (0.7.6) — "Open with
 * Jawon", for a song, or for an album bought as a ZIP, which is how Bandcamp delivers one.
 *
 * A ZIP is someone else's idea of a folder, so nothing in it is taken on trust: an entry's path
 * is only ever used to *group* files into albums, never to decide where anything is written — the
 * file's own name, cleaned, goes into a folder of Jawon's choosing. That is the whole defence
 * against an archive whose entries are named `../../somewhere/else`.
 *
 * Pure. `OpenedMusic` does the reading and writing this plans.
 */
object OpenedPlan {

    val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "mp4", "wav", "ogg", "opus", "aac")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** A ZIP, by what it is called or what it says it is. */
    fun isZip(name: String?, mime: String?): Boolean =
        extension(name.orEmpty()) == "zip" ||
            mime == "application/zip" || mime == "application/x-zip-compressed"

    fun isAudio(name: String?, mime: String?): Boolean =
        extension(name.orEmpty()) in AUDIO_EXTENSIONS || mime?.startsWith("audio/") == true

    enum class Kind { AUDIO, COVER }

    data class Planned(
        val kind: Kind,
        /** The file's own name, safe to create: no path in it, nothing a filesystem refuses. */
        val name: String,
        /** Which album inside the archive this belongs to — its folder there, or "" at the top. */
        val group: String,
        /** For a cover: how sure it is the album's front, larger being surer. */
        val coverRank: Int = 0
    )

    /**
     * Whether an entry's path tries to leave the archive: a `..` in it, or a path from the root.
     *
     * Nothing that is honestly an album has one, so an archive that does is refused whole rather
     * than cleaned up and trusted. (Android 14 refuses such an archive by itself; this is the same
     * answer on the versions before it. [plan] would have defused the entry regardless.)
     */
    fun climbs(entryPath: String): Boolean {
        val path = entryPath.replace('\\', '/')
        return path.startsWith("/") || path.split('/').any { it == ".." }
    }

    /** What one entry of an archive is, or null for everything that is not music or its cover. */
    fun plan(entryPath: String, isDirectory: Boolean): Planned? {
        if (isDirectory) return null
        val parts = entryPath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val raw = parts.lastOrNull() ?: return null
        // What a Mac leaves behind in every archive it makes, and anything hidden.
        if (parts.any { it == "__MACOSX" } || raw.startsWith(".")) return null
        val name = safeName(raw) ?: return null
        // `..` is only ever a grouping key here, and is dropped from it all the same.
        val group = parts.dropLast(1).filter { it != ".." }.joinToString("/")
        val ext = extension(name)
        return when (ext) {
            in AUDIO_EXTENSIONS -> Planned(Kind.AUDIO, name, group)
            in IMAGE_EXTENSIONS -> Planned(Kind.COVER, name, group, coverRank(name))
            else -> null
        }
    }

    /**
     * A name that can be created anywhere: its last path segment only, control characters and the
     * characters Android's shared storage refuses replaced, never empty, never only dots.
     */
    fun safeName(raw: String): String? {
        val last = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = last.map { c -> if (c.code < 0x20 || c in FORBIDDEN) '_' else c }
            .joinToString("")
            .trim()
            .trimEnd('.')
        if (cleaned.isEmpty() || cleaned.all { it == '.' || it == '_' }) return null
        return if (cleaned.length <= MAX_NAME) cleaned
        else cleaned.take(MAX_NAME - 8).trimEnd() + "." + extension(cleaned).take(6)
    }

    /**
     * The folder an album from an archive is kept in, under `Music/Jawon`: the archive's own name
     * — "Artist - Album" is what a shop calls its download — and, when the archive holds more than
     * one album, that album's folder inside it.
     */
    fun folderFor(archiveName: String?, group: String, groups: Int): String {
        val archive = safeName(archiveName.orEmpty().substringBeforeLast('.'))?.take(MAX_FOLDER) ?: "Opened"
        val leaf = safeName(group.substringAfterLast('/'))?.take(MAX_FOLDER)
        return when {
            groups <= 1 || leaf == null -> archive
            // The archive's one folder is usually named for the album already.
            leaf.equals(archive, ignoreCase = true) -> archive
            else -> "$archive/$leaf"
        }
    }

    /** What the player needs to be told a file is, from its name. */
    fun mimeFor(name: String): String = when (extension(name)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/*"
    }

    /** `cover`, `folder` and `front` are what people call the picture they mean. */
    private fun coverRank(name: String): Int {
        val stem = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        return when {
            stem == "cover" || stem == "folder" || stem == "front" -> 3
            "cover" in stem || "front" in stem -> 2
            else -> 1
        }
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private const val FORBIDDEN = "\"*/:<>?\\|"
    private const val MAX_NAME = 120
    private const val MAX_FOLDER = 80

    /**
     * Where an archive stops being an album and starts being a mistake, or an attack: this many
     * files, or this much music once unpacked. A long box set is a few hundred tracks and a few
     * gigabytes; nothing bought as a download is more.
     */
    const val MAX_ENTRIES = 3_000
    const val MAX_BYTES = 6L * 1024 * 1024 * 1024
}
