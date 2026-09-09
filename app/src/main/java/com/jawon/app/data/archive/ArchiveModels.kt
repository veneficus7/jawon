package com.jawon.app.data.archive

/**
 * One record in the shop window — an Internet Archive item, before anything is known about
 * whether it can actually be played.
 *
 * **A search result is a promise, not a record.** The search index says an item is audio; it
 * does not say the item contains per-track audio *files*. A great many do not — netlabel items
 * in particular frequently ship a whole release as a single ZIP, with nothing streamable in it
 * at all. Only `/metadata/{identifier}` settles that, which is why [ArchiveAlbum] is a separate
 * type reached by a second request rather than something a search can return.
 */
data class ArchiveResult(
    /** The item's identifier, e.g. `ssdd2025-11-01.zoom-recorder`. Unique, and the primary key. */
    val identifier: String,
    val title: String,
    val creator: String,
    val year: Int?
)

/** One audio file inside an item, already chosen from among the formats offered. */
data class ArchiveTrack(
    /** File name within the item — the last path segment of the download URL. */
    val fileName: String,
    val title: String,
    val trackNumber: Int,
    val durationMs: Long,
    /** `audio/mpeg`, `audio/flac`. What the deck prints. */
    val mimeType: String,
    val bitrateBps: Int
)

/** An item that has been checked and does contain playable tracks. */
data class ArchiveAlbum(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: Int?,
    val tracks: List<ArchiveTrack>
) {
    /** Where the case artwork comes from. The Archive renders one for every item. */
    val coverUrl: String get() = "https://archive.org/services/img/$identifier"
}

/**
 * Which corner of the Archive to browse.
 *
 * Only two, and the omission is the point: the Archive's audio holdings are vast and mostly
 * unplayable by this app — spoken word, radio transcriptions, 78rpm digitisations with no
 * album structure, and releases distributed as a single ZIP. These two collections are the
 * ones that reliably carry per-track derivatives and permit redistribution.
 */
enum class ArchiveCollection(val query: String, val label: String, val blurb: String) {
    /**
     * Concert recordings from artists who explicitly allow taping and trading. Around a quarter
     * of a million shows, always with per-track FLAC and MP3 derivatives — which is why this is
     * the default.
     */
    LIVE_MUSIC("collection:(etree)", "LIVE SHOWS", "Concerts, from bands who allow taping"),

    /**
     * Netlabel releases, mostly Creative Commons.
     *
     * Less reliable than the live shows and deliberately second: a good share of these items
     * hold nothing but a ZIP, and are filtered out only after their metadata is fetched. What
     * survives is proper studio albums.
     */
    NETLABELS("collection:(netlabels)", "NETLABELS", "Creative Commons releases")
}
