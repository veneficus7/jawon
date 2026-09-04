package com.jawon.app.data.local

/**
 * The small marks on a tracklist that say something about *this* record on *this* phone.
 *
 * **These are the badges that need nothing.** The planned online ones — producer credits from
 * MusicBrainz, awards from Wikidata — depend on two external services, a library tagged well
 * enough to be found in them, and a network. All three fail regularly, and on a library of
 * loose downloads the first one fails almost always. These four are computed from the play
 * history the app already records and the metadata the import already read, so they work
 * offline, cost nothing, and are the only ones that are true of nobody else's copy.
 *
 * Pure on purpose: everything here is counting and comparison, which is exactly the part where
 * a badge can be quietly wrong — and a badge that lies is worse than no badge, because it looks
 * like the app knows something.
 */
enum class TrackBadge {
    /**
     * The track you come back to.
     *
     * Awarded only to a **strict** maximum, and only from two plays up. Both conditions are
     * load-bearing: an album played once straight through leaves every track on one play, and
     * badging all of them — or arbitrarily picking one of a tie — would say nothing while
     * looking like it said something.
     */
    MOST_PLAYED,

    /**
     * Started more than once and never seen through.
     *
     * Two plays minimum, again deliberately. One abandoned listen is an interruption — a phone
     * call, a stop — and calling that "never finished" would badge half a library. Twice is a
     * pattern, and a pattern is the only thing worth printing.
     */
    NEVER_FINISHED,

    /**
     * Where you came into this record.
     *
     * **Only when it is not the opener.** The earliest-played track of an album is track 1 for
     * almost every album ever played, and a badge that is obvious four times in five is
     * clutter. It earns its place in the other case: the record you first met through one song
     * you already knew, halfway down the second side.
     */
    FIRST_PLAYED,

    /** No generation was lost getting here. FLAC, ALAC, WAV, AIFF. */
    LOSSLESS,

    /**
     * Past what a CD can hold — 24-bit, or above 48kHz.
     *
     * Either, not both, and the sample rate is doing most of the work in practice: the platform
     * extractor only reports a bit depth for raw PCM containers, so a 24-bit FLAC arrives with
     * its depth unknown and would otherwise never be marked. See `TrackMetadataReader`.
     */
    HI_RES;

    companion object {

        /** A track counts as finished at this much of its length. */
        const val FINISHED_FRACTION = 0.9f

        /** Below this many plays, a pattern is not a pattern. */
        const val PATTERN_PLAYS = 2

        /** Above CD, by either measure. */
        const val CD_SAMPLE_RATE = 48_000
        const val CD_BIT_DEPTH = 16

        private val LOSSLESS_TYPES = setOf(
            "audio/flac", "audio/x-flac",
            "audio/alac", "audio/x-alac",
            "audio/wav", "audio/x-wav", "audio/vnd.wave",
            "audio/aiff", "audio/x-aiff"
        )

        /**
         * Which badges this track has earned.
         *
         * @param stats how this track has actually been listened to, or null if never played.
         * @param isMostPlayedInAlbum whether it holds the album's strict play-count maximum,
         *   which only the caller can know — it is a fact about the album, not the track.
         * @param isFirstInAlbumOrder whether this is track 1, which is what suppresses an
         *   obvious [FIRST_PLAYED].
         * @param isEarliestPlayedInAlbum whether it was the first of this album ever started.
         */
        fun forTrack(
            durationMs: Long,
            mimeType: String,
            bitDepth: Int?,
            sampleRateHz: Int,
            stats: TrackPlayStats?,
            isMostPlayedInAlbum: Boolean,
            isFirstInAlbumOrder: Boolean,
            isEarliestPlayedInAlbum: Boolean
        ): List<TrackBadge> = buildList {
            if (isMostPlayedInAlbum && (stats?.plays ?: 0) >= PATTERN_PLAYS) add(MOST_PLAYED)

            if (stats != null && stats.plays >= PATTERN_PLAYS && durationMs > 0 &&
                stats.furthestMs < durationMs * FINISHED_FRACTION
            ) {
                add(NEVER_FINISHED)
            }

            if (isEarliestPlayedInAlbum && !isFirstInAlbumOrder && stats != null) add(FIRST_PLAYED)

            if (mimeType.lowercase() in LOSSLESS_TYPES) add(LOSSLESS)
            if ((bitDepth ?: CD_BIT_DEPTH) > CD_BIT_DEPTH || sampleRateHz > CD_SAMPLE_RATE) add(HI_RES)
        }

        /**
         * The album-wide facts [forTrack] cannot work out for itself.
         *
         * Kept beside it so the two definitions of "most played" can never drift apart, which
         * is the sort of thing that produces two badges on one album or none on any.
         */
        fun albumFacts(stats: Collection<TrackPlayStats>): AlbumBadgeFacts {
            val best = stats.filter { it.plays > 0 }.maxByOrNull { it.plays }
            // A tie is not a maximum. Two tracks on four plays each says the album was played
            // through twice, which is a fact about the album and not about either track.
            val uniqueBest = best?.takeIf { b -> stats.count { it.plays == b.plays } == 1 }
            val earliest = stats.filter { it.plays > 0 }.minByOrNull { it.firstStartedAt }
            return AlbumBadgeFacts(
                mostPlayedTrackId = uniqueBest?.trackId,
                earliestPlayedTrackId = earliest?.trackId
            )
        }
    }
}

/** How one track has actually been listened to. */
data class TrackPlayStats(
    val trackId: String,
    val plays: Int,
    /**
     * The furthest into the track any single listen reached.
     *
     * The *maximum*, not the total: a track played four times to the two-minute mark has eight
     * minutes against it and has still never been finished. Summing would mark it complete on
     * the strength of never completing it.
     */
    val furthestMs: Long,
    val firstStartedAt: Long
)

/** The facts about an album that decide badges its individual tracks cannot. */
data class AlbumBadgeFacts(
    val mostPlayedTrackId: String?,
    val earliestPlayedTrackId: String?
)
