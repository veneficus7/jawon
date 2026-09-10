package com.jawon.app.data.cd

import org.json.JSONObject

/**
 * The plan for turning a disc into a folder of files — everything about a rip that is arithmetic
 * rather than I/O.
 *
 * Kept apart from [CdRipper] for the reason the rest of `data/cd` is split that way: the SCSI
 * transfers cannot be tested without hardware nobody has yet, so anything that *can* be decided
 * without a drive is decided here, where it can be argued with on the JVM. What is left in the
 * ripper is a loop.
 */
object CdRip {

    /**
     * How many sectors to ask for in one `READ CD`.
     *
     * A raw CD sector is 2352 bytes, so this is about 60KB a transfer. The ceiling is not the
     * drive but the USB layer: a bulk transfer has to fit a buffer the app allocates, and asking
     * for a whole track at once would be a 50MB array. Small enough to stream, large enough that
     * the per-command overhead is not most of the time — a CD reads at roughly 176KB/s at 1x, so
     * this is a few transfers a second at the slowest possible speed.
     */
    const val SECTORS_PER_READ = 26

    /** One track, and where it is going. */
    data class RipTrack(
        val number: Int,
        val startLba: Int,
        val sectors: Int,
        /** What the file will be called, extension included. */
        val fileName: String,
        /** The title if a lookup found one, else null — the file name falls back to the number. */
        val title: String?
    ) {
        val bytes: Long get() = WavWriter.fileBytesFor(sectors)
    }

    /**
     * What to rip, in order, with names.
     *
     * **Data tracks are excluded here as well as in [CdToc].** A mixed-mode disc's data track is
     * not audio and reading it as though it were produces a file of noise — which is worse than
     * failing, because it imports successfully and only reveals itself when somebody plays it.
     *
     * Titles come from a MusicBrainz lookup when the disc was recognised, and are matched by
     * *position* rather than by number: the lookup returns a release's tracklist in order, and a
     * disc whose first audio track is number 2 (because track 1 is data) still starts at the
     * first title.
     */
    fun plan(toc: CdToc, titles: List<String> = emptyList()): List<RipTrack> =
        toc.audioTracks.mapIndexed { index, track ->
            val title = titles.getOrNull(index)?.takeIf { it.isNotBlank() }
            RipTrack(
                number = track.number,
                startLba = track.startLba,
                sectors = track.sectors,
                fileName = fileNameFor(track.number, title),
                title = title
            )
        }

    /**
     * What a ripped track is called on disk.
     *
     * **The file name is the metadata.** A WAV carries no tags, so after the rip the importer has
     * nothing to read but this — which makes the name the entire difference between a shelf of
     * "01, 02, 03" and a real tracklist. The leading two-digit number is not decoration either:
     * it is what the importer sorts on, and without it a folder listing puts track 10 before
     * track 2.
     *
     * Sanitised because a title can contain anything a record label felt like — slashes and
     * colons are common in classical and in remix credits, and both are illegal in a file name on
     * most of the storage this will be written to.
     */
    fun fileNameFor(number: Int, title: String?): String {
        val stem = title?.let(::sanitise)?.takeIf { it.isNotBlank() }
        return if (stem == null) "%02d.wav".format(number)
        else "%02d %s.wav".format(number, stem)
    }

    /**
     * A title reduced to something every filesystem will accept.
     *
     * Deliberately conservative: anything outside letters, digits, spaces and a short list of
     * harmless punctuation becomes a space, runs of spaces collapse, and the result is trimmed.
     * Losing a stylised character costs nothing — the title is retyped on the import screen if it
     * matters — while a name a provider rejects costs the whole track.
     */
    fun sanitise(title: String): String =
        title.map { if (it.isLetterOrDigit() || it in KEEP) it else ' ' }
            .joinToString("")
            .replace(SPACES, " ")
            .trim()
            .take(MAX_STEM)

    /**
     * The sector ranges to ask the drive for, in order.
     *
     * A separate function because the last chunk is the one that goes wrong: a track is rarely a
     * whole number of chunks, and reading a fixed [SECTORS_PER_READ] every time overruns the end
     * of the last track into the lead-out — which on many drives is not an error, just wrong
     * audio at the end of every album.
     */
    fun chunks(startLba: Int, sectors: Int, per: Int = SECTORS_PER_READ): List<IntArray> {
        if (sectors <= 0 || per <= 0) return emptyList()
        val out = ArrayList<IntArray>((sectors + per - 1) / per)
        var lba = startLba
        var left = sectors
        while (left > 0) {
            val take = minOf(per, left)
            out += intArrayOf(lba, take)
            lba += take
            left -= take
        }
        return out
    }

    /** How far through the whole disc a rip is, 0..1. */
    fun progress(done: List<RipTrack>, current: RipTrack?, sectorsDone: Int, all: List<RipTrack>): Float {
        val total = all.sumOf { it.sectors.toLong() }
        if (total <= 0L) return 0f
        val finished = done.sumOf { it.sectors.toLong() }
        val within = if (current == null) 0L else sectorsDone.toLong().coerceIn(0L, current.sectors.toLong())
        return ((finished + within).toFloat() / total).coerceIn(0f, 1f)
    }

    /** What a disc will occupy once ripped, for saying so before it starts. */
    fun totalBytes(tracks: List<RipTrack>): Long = tracks.sumOf { it.bytes }

    // ---- The MusicBrainz side --------------------------------------------------------------

    /** What a disc lookup found. */
    data class DiscRelease(val title: String, val artist: String, val trackTitles: List<String>)

    /**
     * Read a release out of a MusicBrainz `discid` response.
     *
     * Pure and separate from the request for the same reason `SubsonicClient`'s parsers are: the
     * shapes this has to survive are the interesting part, and none of them need a network. In
     * particular a disc id can match **several** releases — the same pressing sold in three
     * territories — and the media array can hold more than one disc, only one of which is ours.
     *
     * Returns null rather than guessing when there is nothing usable. An unrecognised disc is the
     * normal case for anything home-made or obscure, and the rip works perfectly without titles.
     */
    fun parseDiscLookup(body: String, discId: String): DiscRelease? = runCatching {
        val releases = JSONObject(body).optJSONArray("releases") ?: return null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val media = release.optJSONArray("media") ?: continue
            for (m in 0 until media.length()) {
                val disc = media.optJSONObject(m) ?: continue
                // **Only the medium that actually carries our disc id.** A box set's release
                // lists every disc in it, and taking the first would name a rip of disc three
                // with the tracklist of disc one.
                if (!mediumHasDisc(disc, discId)) continue
                val tracks = disc.optJSONArray("tracks") ?: continue
                val titles = (0 until tracks.length()).mapNotNull { t ->
                    tracks.optJSONObject(t)?.optString("title")?.takeIf { it.isNotBlank() }
                }
                if (titles.isEmpty()) continue
                return DiscRelease(
                    title = release.optString("title").ifBlank { "Untitled" },
                    artist = artistOf(release),
                    trackTitles = titles
                )
            }
        }
        null
    }.getOrNull()

    private fun mediumHasDisc(medium: JSONObject, discId: String): Boolean {
        val discs = medium.optJSONArray("discs") ?: return false
        for (i in 0 until discs.length()) {
            if (discs.optJSONObject(i)?.optString("id") == discId) return true
        }
        return false
    }

    /**
     * The credited artist, joined the way the release credits it.
     *
     * `joinphrase` is what carries "&" or "feat." between names, and using it rather than a comma
     * is the difference between *Simon & Garfunkel* and *Simon, Garfunkel*.
     */
    private fun artistOf(release: JSONObject): String {
        val credits = release.optJSONArray("artist-credit") ?: return "Unknown artist"
        val name = StringBuilder()
        for (i in 0 until credits.length()) {
            val credit = credits.optJSONObject(i) ?: continue
            name.append(
                credit.optString("name").ifBlank {
                    credit.optJSONObject("artist")?.optString("name").orEmpty()
                }
            )
            name.append(credit.optString("joinphrase"))
        }
        return name.toString().trim().ifBlank { "Unknown artist" }
    }

    /** The lookup URL. `inc` asks for the tracklist and the credits in one request. */
    fun lookupUrl(discId: String): String =
        "https://musicbrainz.org/ws/2/discid/$discId" +
            "?fmt=json&inc=artist-credits+recordings"

    private const val KEEP = " '&()-_,.!"
    private val SPACES = Regex("\\s+")

    /** Long enough for any real title, short of the 255-byte limit most filesystems have. */
    private const val MAX_STEM = 80
}
